import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { AlertTriangle, ArrowLeft, BadgeCheck, Info, LoaderCircle, RefreshCw, XCircle } from 'lucide-react';
import api, { apiErrorMessage } from './api.js';
import { useAuth } from './AuthContext.jsx';
import { clearPublicLookupCredentials, readPublicLookupCredentials } from './publicLookupSession.js';
import { clearPendingPayPalOrder, readPendingPayPalOrder, validatePayPalReturnSession } from './paypalSession.js';

function sessionStorageOrNull() {
  try { return typeof window === 'undefined' ? null : window.sessionStorage; }
  catch { return null; }
}

export default function PayPalReturnPage({ mode = 'return' }) {
  const [searchParams] = useSearchParams();
  const { session } = useAuth();
  const stored = readPendingPayPalOrder(sessionStorageOrNull());
  const orderIdParam = searchParams.get('orderId') || '';
  const token = searchParams.get('token') || '';
  const validation = validatePayPalReturnSession({ stored, token, orderIdParam });

  const initialStatus = () => {
    if (mode === 'cancel') return stored ? 'cancelled' : 'missing_session';
    if (validation.status === 'missing_session') return 'missing_session';
    if (validation.status === 'error') return 'error';
    return 'capturing';
  };

  const [status, setStatus] = useState(initialStatus);
  const [error, setError] = useState(validation.status === 'error' ? validation.reason : '');
  const [order, setOrder] = useState(null);
  const startedRef = useRef(false);

  const flow = stored?.flow || (session?.role === 'STAFF' ? 'staff' : session?.role === 'CUSTOMER' ? 'customer' : 'public');
  const orderId = stored?.orderId || orderIdParam || '';

  const backHref = flow === 'public'
    ? '/track-order'
    : flow === 'staff'
      ? (orderId ? `/staff/orders/${encodeURIComponent(orderId)}` : '/staff/orders')
      : (orderId ? `/customer/orders/${encodeURIComponent(orderId)}` : '/customer/orders');

  const backLabel = flow === 'public'
    ? 'Back to walk-in lookup'
    : flow === 'staff'
      ? 'Back to shop order'
      : 'Back to my order';

  const runCapture = async () => {
    const currentStored = readPendingPayPalOrder(sessionStorageOrNull());
    const currentCheck = validatePayPalReturnSession({ stored: currentStored, token, orderIdParam });
    if (currentCheck.status === 'missing_session') {
      setStatus('missing_session');
      return;
    }
    if (currentCheck.status === 'error') {
      setStatus('error');
      setError(currentCheck.reason);
      return;
    }

    setStatus('capturing');
    setError('');
    try {
      let data;
      if (currentCheck.flow === 'public') {
        const lookup = readPublicLookupCredentials(sessionStorageOrNull());
        const code = currentCheck.orderCode || lookup?.orderCode || '';
        const mobile = lookup?.mobile || '';
        if (!code || !mobile) {
          setStatus('missing_session');
          return;
        }
        ({ data } = await api.post('/api/public/orders/paypal-capture', {
          orderCode: code,
          mobile,
          paypalOrderId: currentCheck.paypalOrderId,
        }));
        clearPublicLookupCredentials(sessionStorageOrNull());
        clearPendingPayPalOrder(sessionStorageOrNull());
        setOrder(data);
        if (data?.paymentStatus === 'PAID') {
          setStatus('success');
        } else {
          setError(`Payment is not completed yet (current status: ${data?.paymentStatus || 'UNPAID'}). Complete approval in PayPal or ask shop staff to confirm.`);
          setStatus('incomplete');
        }
        return;
      }

      const captureUrl = currentCheck.flow === 'staff'
        ? `/api/shops/orders/${encodeURIComponent(currentCheck.orderId)}/payments/paypal-capture`
        : `/api/me/orders/${encodeURIComponent(currentCheck.orderId)}/payments/paypal-capture`;

      ({ data } = await api.post(captureUrl, { paypalOrderId: currentCheck.paypalOrderId }));
      clearPendingPayPalOrder(sessionStorageOrNull());
      setOrder(data);
      setStatus('success');
    } catch (reason) {
      setError(apiErrorMessage(reason, 'Could not confirm the PayPal payment yet. Check My orders or ask shop staff to confirm.'));
      setStatus('error');
    }
  };

  useEffect(() => {
    if (mode !== 'return' || startedRef.current || status !== 'capturing') return;
    startedRef.current = true;
    void runCapture();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode]);

  return (
    <div className="mx-auto max-w-xl py-8">
      <section className="rounded-3xl border border-slate-100 bg-white p-6 shadow-soft sm:p-8">
        <p className="text-xs font-bold uppercase tracking-[.16em] text-brand">PayPal Sandbox</p>

        {status === 'capturing' && (
          <div className="mt-4 flex items-center gap-3 text-slate-700" role="status">
            <LoaderCircle size={22} className="animate-spin text-brand" />
            <div>
              <h1 className="text-xl font-semibold text-ink">Confirming PayPal payment…</h1>
              <p className="mt-1 text-sm text-slate-500">Verifying the captured amount and currency with PayPal Sandbox.</p>
            </div>
          </div>
        )}

        {status === 'success' && (
          <div>
            <div className="mt-3 flex items-center gap-3 text-emerald-700">
              <BadgeCheck size={26} />
              <h1 className="text-2xl font-semibold text-ink">Payment confirmed</h1>
            </div>
            <p className="mt-3 text-sm leading-6 text-slate-600">
              PayPal Sandbox payment was captured and order <span className="font-semibold text-ink">{order?.orderCode || stored?.orderCode || orderId}</span> is now marked <span className="font-semibold text-emerald-800">Paid</span>.
            </p>
            <div className="mt-6 flex flex-wrap gap-3">
              <Link to={backHref} className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand px-5 text-sm font-semibold text-white hover:bg-teal-700">
                <ArrowLeft size={16} />{backLabel}
              </Link>
            </div>
          </div>
        )}

        {status === 'missing_session' && (
          <div>
            <div className="mt-3 flex items-center gap-3 text-sky-700">
              <Info size={26} />
              <h1 className="text-2xl font-semibold text-ink">Check your payment status</h1>
            </div>
            <p className="mt-3 text-sm leading-6 text-slate-600">
              Open My orders or Track a walk-in order to check your payment. If you approved on another device or browser, shop staff can also confirm your PayPal payment from the shop order page.
            </p>
            <div className="mt-6 flex flex-wrap gap-3">
              {session?.role === 'CUSTOMER' && (
                <Link to="/customer/orders" className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand px-5 text-sm font-semibold text-white hover:bg-teal-700">
                  My orders
                </Link>
              )}
              <Link to="/track-order" className={`inline-flex min-h-11 items-center gap-2 rounded-xl px-5 text-sm font-semibold ${session?.role === 'CUSTOMER' ? 'border border-slate-200 text-slate-700 hover:bg-slate-50' : 'bg-brand text-white hover:bg-teal-700'}`}>
                Track a walk-in order
              </Link>
              {session?.role === 'STAFF' && (
                <Link to="/staff/orders" className="inline-flex min-h-11 items-center gap-2 rounded-xl border border-slate-200 px-5 text-sm font-semibold text-slate-700 hover:bg-slate-50">
                  Shop orders
                </Link>
              )}
            </div>
          </div>
        )}

        {status === 'cancelled' && (
          <div>
            <div className="mt-3 flex items-center gap-3 text-amber-700">
              <XCircle size={26} />
              <h1 className="text-2xl font-semibold text-ink">PayPal approval cancelled</h1>
            </div>
            <p className="mt-3 text-sm leading-6 text-slate-600">
              The PayPal Sandbox approval flow was cancelled. No payment was captured. You can return to your order to try again.
            </p>
            <div className="mt-6 flex flex-wrap gap-3">
              <Link to={backHref} className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand px-5 text-sm font-semibold text-white hover:bg-teal-700">
                <ArrowLeft size={16} />{backLabel}
              </Link>
            </div>
          </div>
        )}

        {status === 'incomplete' && (
          <div>
            <div className="mt-3 flex items-center gap-3 text-amber-700">
              <Info size={26} />
              <h1 className="text-2xl font-semibold text-ink">Payment not completed</h1>
            </div>
            <p role="status" className="mt-3 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
              {error}
            </p>
            <div className="mt-6 flex flex-wrap gap-3">
              <Link to={backHref} className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand px-5 text-sm font-semibold text-white hover:bg-teal-700">
                <ArrowLeft size={16} />{backLabel}
              </Link>
            </div>
          </div>
        )}

        {status === 'error' && (
          <div>
            <div className="mt-3 flex items-center gap-3 text-rose-700">
              <AlertTriangle size={26} />
              <h1 className="text-2xl font-semibold text-ink">Could not confirm PayPal payment</h1>
            </div>
            <p role="alert" className="mt-3 rounded-2xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-800">
              {error}
            </p>
            <div className="mt-6 flex flex-wrap gap-3">
              {validation.status === 'valid' && (
                <button type="button" onClick={() => void runCapture()} className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand px-4 text-sm font-semibold text-white hover:bg-teal-700">
                  <RefreshCw size={16} />Retry capture
                </button>
              )}
              <Link to={backHref} className="inline-flex min-h-11 items-center gap-2 rounded-xl border border-slate-200 px-4 text-sm font-semibold text-slate-700 hover:bg-slate-50">
                <ArrowLeft size={16} />{backLabel}
              </Link>
            </div>
          </div>
        )}
      </section>
    </div>
  );
}
