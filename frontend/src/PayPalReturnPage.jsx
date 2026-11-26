import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { AlertTriangle, ArrowLeft, BadgeCheck, LoaderCircle, RefreshCw, XCircle } from 'lucide-react';
import api, { apiErrorMessage } from './api.js';
import { clearPendingPayPalOrder, readPendingPayPalOrder } from './paypalSession.js';

function sessionStorageOrNull() {
  try { return typeof window === 'undefined' ? null : window.sessionStorage; }
  catch { return null; }
}

export default function PayPalReturnPage({ mode = 'return' }) {
  const [searchParams] = useSearchParams();
  const stored = readPendingPayPalOrder(sessionStorageOrNull());
  const orderId = searchParams.get('orderId') || stored?.orderId || '';
  const paypalOrderId = searchParams.get('token') || stored?.paypalOrderId || '';

  const [status, setStatus] = useState(mode === 'cancel' ? 'cancelled' : 'capturing');
  const [error, setError] = useState('');
  const [order, setOrder] = useState(null);
  const startedRef = useRef(false);

  const runCapture = async () => {
    if (!orderId) {
      setStatus('error');
      setError('Order reference is missing. Return to Staff Orders and click "Confirm PayPal payment" on the order.');
      return;
    }
    setStatus('capturing');
    setError('');
    try {
      const { data } = await api.post(
        `/api/shops/orders/${encodeURIComponent(orderId)}/payments/paypal-capture`,
        paypalOrderId ? { paypalOrderId } : {},
      );
      clearPendingPayPalOrder(sessionStorageOrNull());
      setOrder(data);
      setStatus('success');
    } catch (reason) {
      setError(apiErrorMessage(reason, 'Could not capture the PayPal payment. You can retry or confirm from the order page.'));
      setStatus('error');
    }
  };

  useEffect(() => {
    if (mode !== 'return' || startedRef.current) return;
    startedRef.current = true;
    void runCapture();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode]);

  const orderHref = orderId ? `/staff/orders/${encodeURIComponent(orderId)}` : '/staff/orders';

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
              PayPal Sandbox payment was captured and order <span className="font-semibold text-ink">{order?.orderCode || orderId}</span> is now marked <span className="font-semibold text-emerald-800">Paid</span>.
            </p>
            <div className="mt-6 flex flex-wrap gap-3">
              <Link to={orderHref} className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand px-5 text-sm font-semibold text-white hover:bg-teal-700">
                <ArrowLeft size={16} />View order details
              </Link>
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
              The PayPal Sandbox approval flow was cancelled. No payment was captured. You can return to the order to retry or cancel the pending online payment.
            </p>
            <div className="mt-6 flex flex-wrap gap-3">
              <Link to={orderHref} className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand px-5 text-sm font-semibold text-white hover:bg-teal-700">
                <ArrowLeft size={16} />Back to order
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
              {orderId && (
                <button type="button" onClick={() => void runCapture()} className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand px-4 text-sm font-semibold text-white hover:bg-teal-700">
                  <RefreshCw size={16} />Retry capture
                </button>
              )}
              <Link to={orderHref} className="inline-flex min-h-11 items-center gap-2 rounded-xl border border-slate-200 px-4 text-sm font-semibold text-slate-700 hover:bg-slate-50">
                <ArrowLeft size={16} />Back to order
              </Link>
            </div>
          </div>
        )}
      </section>
    </div>
  );
}
