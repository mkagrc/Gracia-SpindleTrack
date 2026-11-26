import { useEffect, useRef, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft, ExternalLink, LoaderCircle, Receipt, RefreshCw } from 'lucide-react';
import api, { apiErrorMessage } from './api.js';
import { formatDateTime, formatOrderPaymentMethod, formatPaymentMethod, formatPaymentStatus, formatPeso } from './formatters.js';
import { isPayPalCheckoutEligible, normalizeOrderDetailResponse } from './orderPageData.js';
import { savePendingPayPalOrder } from './paypalSession.js';
import OrderStatusTracker, { stageDisplayLabel } from './components/OrderStatusTracker.jsx';
import OrderQuantityBadge from './components/OrderQuantityBadge.jsx';
import InvoiceSection from './InvoiceSection.jsx';

function createClientRequestId(cryptoSource = globalThis.crypto) {
  if (cryptoSource?.randomUUID) return cryptoSource.randomUUID();
  if (!cryptoSource?.getRandomValues) throw new Error('Secure UUID generation is unavailable.');
  const bytes = cryptoSource.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export default function CustomerOrderDetailPage() {
  const { orderId } = useParams();
  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [payBusy, setPayBusy] = useState(false);
  const [payError, setPayError] = useState('');
  const [reload, setReload] = useState(0);
  const requestIdRef = useRef('');

  useEffect(() => {
    let active = true;
    const fetchOrder = (showLoading) => {
      if (showLoading) setLoading(true);
      setError('');
      return api.get(`/api/me/orders/${encodeURIComponent(orderId)}`)
        .then(({ data }) => { if (active) setDetail(normalizeOrderDetailResponse(data)); })
        .catch((reason) => { if (active) setError(apiErrorMessage(reason)); })
        .finally(() => { if (active && showLoading) setLoading(false); });
    };
    void fetchOrder(true);
    const timer = setInterval(() => {
      if (typeof document === 'undefined' || document.visibilityState === 'visible') void fetchOrder(false);
    }, 30_000);
    return () => { active = false; clearInterval(timer); };
  }, [orderId, reload]);

  const order = detail?.order;
  const paid = order?.paymentStatus === 'PAID';
  const payEligible = isPayPalCheckoutEligible(order);
  const payAtClaimWaiting = !paid && order?.paymentMethodPreference === 'ONLINE'
    && order?.paymentTiming === 'PAY_AT_CLAIM' && order?.stage !== 'READY' && order?.stage !== 'CLAIMED';

  const payWithPayPal = async () => {
    setPayBusy(true);
    setPayError('');
    try {
      if (!requestIdRef.current) requestIdRef.current = createClientRequestId();
      const { data } = await api.post(`/api/me/orders/${encodeURIComponent(orderId)}/payments/paypal-order`, {
        clientRequestId: requestIdRef.current,
      });
      const approvalUrl = data.approvalUrl || data.paymentLinkUrl;
      const paypalOrderId = data.paypalOrderId || data.paymentLinkId;
      const storage = typeof window !== 'undefined' ? window.sessionStorage : null;
      savePendingPayPalOrder(storage, {
        flow: 'customer',
        orderId,
        orderCode: data.order?.orderCode || order?.orderCode || '',
        paypalOrderId,
      });
      if (approvalUrl && typeof window !== 'undefined') {
        window.location.assign(approvalUrl);
        return;
      }
      setPayError('PayPal checkout link is unavailable. Please try again.');
    } catch (reason) {
      requestIdRef.current = '';
      setPayError(apiErrorMessage(reason, 'PayPal checkout could not be started.'));
    } finally {
      setPayBusy(false);
    }
  };

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Customer</p>
          <h1 className="mt-2 text-2xl font-semibold text-ink sm:text-3xl">{order?.orderCode || 'Order details'}</h1>
          <p className="mt-2 text-sm text-slate-500">{order?.shopName ? `Laundry order at ${order.shopName}.` : 'Private details for your linked order.'}</p>
        </div>
        <Link to="/customer/orders" className="inline-flex min-h-10 items-center gap-2 rounded-xl border border-slate-200 px-3 text-sm font-semibold">
          <ArrowLeft size={16} />My orders
        </Link>
      </div>

      {error && (
        <div role="alert" className="flex flex-wrap justify-between gap-3 rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
          <span>{error}</span>
          <button type="button" onClick={() => setReload((value) => value + 1)} className="font-semibold underline">Try again</button>
        </div>
      )}

      {loading && !order ? (
        <div role="status" className="flex min-h-48 items-center justify-center gap-3 text-sm text-slate-500">
          <LoaderCircle size={20} className="animate-spin text-brand" />Loading your order{'\u2026'}
        </div>
      ) : order ? (
        <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_360px]">
          <section className="space-y-5">
            <article className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-7">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <p className="text-xs font-bold uppercase tracking-wide text-slate-400">{order.shopName || 'Laundry shop'}</p>
                  <h2 className="mt-1 text-xl font-semibold text-ink">{order.serviceName || 'Laundry service'}</h2>
                  <div className="mt-1.5 flex flex-wrap items-center gap-2 text-sm text-slate-500">
                    <OrderQuantityBadge order={order} />
                    <span>{'\u00b7'} Created {formatDateTime(order.createdAt)}</span>
                  </div>
                </div>
                <span className="rounded-full bg-teal-50 px-2.5 py-1 text-xs font-semibold text-teal-800">
                  {stageDisplayLabel(order.stage || 'RECEIVED')}
                </span>
              </div>
              <p className="mt-5 text-2xl font-semibold text-ink">
                {formatPeso(detail.invoice?.total ?? order.totalPrice)}
              </p>
              {order.paymentMethod && (
                <p className="mt-1 text-sm text-slate-500">
                  Payment method: {formatOrderPaymentMethod(order)}
                  {order.paidAt ? ` \u00b7 ${formatDateTime(order.paidAt)}` : ''}
                </p>
              )}
            </article>

            {detail.invoice ? (
              <InvoiceSection invoice={detail.invoice} order={order} customerView />
            ) : (
              <article className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-7" aria-label="Invoice status">
                <div className="flex items-start gap-3">
                  <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-amber-50 text-amber-700">
                    <Receipt size={18} />
                  </span>
                  <div>
                    <p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Receipt</p>
                    <h2 className="mt-1 text-lg font-semibold text-ink">Invoice</h2>
                    <p className="mt-2 text-sm text-slate-600">
                      Your invoice will be available once your payment is recorded.
                    </p>
                  </div>
                </div>
              </article>
            )}

            <article className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-7">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <h2 className="font-semibold text-ink">Order status</h2>
                <button
                  type="button"
                  disabled={loading}
                  onClick={() => setReload((value) => value + 1)}
                  className="inline-flex min-h-9 items-center gap-2 rounded-lg border border-slate-200 px-3 text-xs font-semibold disabled:opacity-50"
                >
                  <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />Refresh status
                </button>
              </div>
              <div className="mt-4">
                <OrderStatusTracker stageHistory={detail.history || []} currentStage={order.stage} showStaffName={false} />
              </div>
            </article>

            <article className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-7">
              <h2 className="font-semibold text-ink">Payment history</h2>
              {!(detail.paymentHistory || []).length ? (
                <p className="mt-3 text-sm text-slate-500">No payment events are available yet.</p>
              ) : (
                <ol className="mt-4 divide-y divide-slate-100">
                  {detail.paymentHistory.map((event, index) => (
                    <li key={`${event.status}-${event.occurredAt || index}`} className="flex flex-wrap items-center justify-between gap-2 py-3">
                      <p className="text-sm font-semibold">
                        {formatPaymentStatus(event.status)}
                        {event.method ? ` \u00b7 ${formatPaymentMethod(event.method)}` : ''}
                      </p>
                      <time className="text-xs text-slate-500">{formatDateTime(event.occurredAt)}</time>
                    </li>
                  ))}
                </ol>
              )}
            </article>
          </section>

          <aside className="h-fit rounded-3xl border border-slate-100 bg-white p-5 shadow-soft">
            <p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Payment</p>
            <p className="mt-2 text-lg font-semibold">{formatPaymentStatus(order.paymentStatus)}</p>
            {paid && order.paidAt && (
              <p className="mt-1 text-xs text-slate-500">Paid {formatDateTime(order.paidAt)}</p>
            )}
            {payError && (
              <p role="alert" className="mt-3 rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-800">
                {payError}
              </p>
            )}
            {payEligible && (
              <button
                type="button"
                disabled={payBusy}
                onClick={() => void payWithPayPal()}
                className="mt-4 inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-xl bg-brand px-4 text-sm font-semibold text-white hover:bg-teal-700 disabled:opacity-50"
              >
                {payBusy ? <LoaderCircle size={16} className="animate-spin" /> : <ExternalLink size={16} />}
                {payBusy ? 'Opening PayPal…' : 'Pay with PayPal'}
              </button>
            )}
            {payAtClaimWaiting && (
              <p className="mt-3 rounded-xl bg-slate-50 p-3 text-xs leading-5 text-slate-600">
                Online payment (PayPal) will be available once your order is Ready for pickup.
              </p>
            )}
            <p className="mt-3 text-sm leading-6 text-slate-500">
              For updates about this order, contact {order.shopName || 'the laundry shop'} directly.
            </p>
          </aside>
        </div>
      ) : !loading && (
        <div className="rounded-2xl border border-dashed border-slate-200 bg-white p-10 text-center text-sm text-slate-500">
          Order not found.
        </div>
      )}
    </div>
  );
}
