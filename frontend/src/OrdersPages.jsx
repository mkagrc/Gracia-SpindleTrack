import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { ArrowLeft, ArrowRight, BadgeCheck, ClipboardList, Clock3, Copy, Droplets, ExternalLink, LoaderCircle, Package, Plus, RefreshCw, Search, Store } from 'lucide-react';
import api, { apiErrorMessage } from './api.js';
import ConfirmDialog from './ConfirmDialog.jsx';
import Toast from './Toast.jsx';
import OrderStatusTracker, { stageDisplayLabel } from './components/OrderStatusTracker.jsx';
import OrderQuantityBadge from './components/OrderQuantityBadge.jsx';
import { normalizeOrderDetailResponse, normalizePagedRows } from './orderPageData.js';
import { formatDateTime, formatPaymentMethod, formatPeso } from './formatters.js';
import { QRCodeSVG } from 'qrcode.react';
import NewOrderForm, { NewOrderHeading } from './NewOrderForm.jsx';
import InvoiceSection from './InvoiceSection.jsx';
import { formatOrderQuantity, quantityInputRules, previewOrderTotal } from './orderPricing.js';

export const LAUNDRY_STAGES = ['RECEIVED', 'WASHING', 'DRYING', 'FOLDING', 'READY', 'CLAIMED'];
export const nextLaundryStage = (stage) => { const index = LAUNDRY_STAGES.indexOf(stage); return index >= 0 && index < LAUNDRY_STAGES.length - 1 ? LAUNDRY_STAGES[index + 1] : null; };
const money = formatPeso;
const stageName = stageDisplayLabel;
const dateTime = formatDateTime;
export function createClientRequestId(cryptoSource = globalThis.crypto) {
  if (cryptoSource?.randomUUID) return cryptoSource.randomUUID();
  if (!cryptoSource?.getRandomValues) throw new Error('Secure UUID generation is unavailable.');
  const bytes = cryptoSource.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
export const paymentStatusLabel = (status) => status === 'PAID' ? 'Paid' : status === 'PENDING' ? 'Pending online' : 'Unpaid';
export const suggestTotalPrice = (pricePerUnit, kilos) => { const estimate = previewOrderTotal({ billingType: 'PER_KG', pricePerUnit }, kilos); return estimate.total > 0 || Number(pricePerUnit) === 0 ? estimate.total.toFixed(2) : ''; };
export const quantityInputLabel = (service) => quantityInputRules(typeof service === 'string' ? { unit: service } : service || {}).label;
export const orderQuantityDisplay = (order) => formatOrderQuantity(order);
export const weightLabel = () => 'Quantity';
export const createOrderPayload = (fields, linkedEmail = '') => { const { kilos, totalPrice, price, pricePerUnit, unit, ...rest } = fields; return { ...rest, quantity: fields.quantity ?? kilos, ...(linkedEmail.trim() ? { customerEmail: linkedEmail.trim() } : {}) }; };
export function customerOrderSnapshot(rows, total) {
  const pageRows = Array.isArray(rows) ? rows : [];
  const parsedTotal = Number(total);
  return {
    totalOrders: Number.isFinite(parsedTotal) && parsedTotal >= 0 ? parsedTotal : pageRows.length,
    ordersOnPage: pageRows.length,
    inProgressOnPage: pageRows.filter((order) => order?.stage && order.stage !== 'CLAIMED').length,
    readyOnPage: pageRows.filter((order) => order?.stage === 'READY').length,
  };
}
export function stageProgressPercent(stage) {
  const index = LAUNDRY_STAGES.indexOf(stage);
  return index < 0 ? 0 : Math.round((index / (LAUNDRY_STAGES.length - 1)) * 100);
}
function Heading({ eyebrow, title, description, action }) { return <div className="mb-7 flex flex-wrap items-end justify-between gap-4"><div><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">{eyebrow}</p><h1 className="mt-2 text-2xl font-semibold tracking-tight text-ink sm:text-3xl">{title}</h1>{description && <p className="mt-2 max-w-2xl text-sm leading-6 text-slate-500">{description}</p>}</div>{action}</div>; }
function Loading({ label = 'Loading orders\u2026' }) { return <div role="status" className="flex min-h-56 items-center justify-center gap-3 text-sm text-slate-500"><LoaderCircle size={20} className="animate-spin text-brand" />{label}</div>; }
function ErrorMessage({ error, retry }) { return error ? <div role="alert" className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">{error}{retry && <button type="button" onClick={retry} className="font-semibold underline">Try again</button>}</div> : null; }
function StagePill({ stage }) { return <span className="inline-flex rounded-full bg-teal-50 px-2.5 py-1 text-xs font-semibold text-teal-800">{stageName(stage)}</span>; }
function PaymentPill({ status }) { const paid = status === 'PAID'; const pending = status === 'PENDING'; return <span className={`inline-flex rounded-full px-2.5 py-1 text-xs font-semibold ${paid ? 'bg-emerald-50 text-emerald-800' : pending ? 'bg-sky-50 text-sky-800' : 'bg-amber-50 text-amber-800'}`}>{paymentStatusLabel(status)}</span>; }

function CustomerStageProgress({ stage }) {
  const activeIndex = LAUNDRY_STAGES.indexOf(stage);
  const shortLabels = ['Pending', 'Wash', 'Dry', 'Fold', 'Ready', 'Claimed'];
  return <ol aria-label={`Laundry progress: ${stageName(stage)}`} className="grid grid-cols-6 gap-1">
    {LAUNDRY_STAGES.map((value, index) => <li key={value} className="min-w-0 text-center" aria-current={index === activeIndex ? 'step' : undefined}>
      <span className={`block h-1.5 rounded-full ${activeIndex >= 0 && index <= activeIndex ? 'bg-brand' : 'bg-slate-200'}`} />
      <span title={stageName(value)} className={`mt-1.5 block truncate text-[9px] font-medium sm:text-[10px] ${index === activeIndex ? 'text-teal-800' : index < activeIndex ? 'text-slate-600' : 'text-slate-400'}`}>{shortLabels[index]}</span>
    </li>)}
  </ol>;
}

function CustomerOrderCard({ order }) {
  return <Link to={`/customer/orders/${encodeURIComponent(order.id)}`} className="group block rounded-2xl border border-slate-200 bg-white p-4 transition hover:-translate-y-0.5 hover:border-teal-200 hover:shadow-soft focus:outline-none focus:ring-4 focus:ring-teal-100 sm:p-5">
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0"><p className="text-xs font-bold uppercase tracking-wide text-slate-400">{order.orderCode}</p><h3 className="mt-1 truncate text-base font-semibold text-ink">{order.shopName || 'Laundry shop'}</h3><p className="mt-1 text-xs text-slate-500">{dateTime(order.createdAt)}</p></div>
      <div className="flex flex-wrap gap-2"><StagePill stage={order.stage} /><PaymentPill status={order.paymentStatus} /></div>
    </div>
    <div className="mt-4 grid grid-cols-3 gap-3 rounded-xl bg-slate-50 p-3">
      <div className="min-w-0"><p className="text-[10px] font-semibold uppercase tracking-wide text-slate-400">Service</p><p className="mt-1 truncate text-sm font-medium text-ink">{order.serviceName || 'Laundry service'}</p></div>
      <div><p className="text-[10px] font-semibold uppercase tracking-wide text-slate-400">Quantity</p><div className="mt-1 text-sm font-medium text-ink"><OrderQuantityBadge order={order} /></div></div>
      <div><p className="text-[10px] font-semibold uppercase tracking-wide text-slate-400">Total</p><p className="mt-1 text-sm font-semibold text-ink">{money(order.totalPrice)}</p></div>
    </div>
    <div className="mt-4"><div className="mb-2 flex items-center justify-between gap-2"><p className="text-xs font-semibold text-slate-600">Laundry progress</p><p className="text-xs font-semibold text-teal-800">{stageName(order.stage)}</p></div><CustomerStageProgress stage={order.stage} /></div>
    <div className="mt-4 flex items-center justify-end gap-1 border-t border-slate-100 pt-3 text-xs font-semibold text-brand">View order details <ArrowRight size={14} className="transition group-hover:translate-x-0.5" /></div>
  </Link>;
}

export function OrdersListPage({ readOnly = false }) {
  const [stage, setStage] = useState(''); const [search, setSearch] = useState(''); const [page, setPage] = useState(0); const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(true); const [error, setError] = useState(''); const [reload, setReload] = useState(0);
  const base = readOnly ? '/owner/orders' : '/staff/orders';
  useEffect(() => { let active = true; setLoading(true); setError('');
    api.get('/api/shops/orders', { params: { ...(stage ? { stage } : {}), ...(search.trim() ? { search: search.trim() } : {}), page } })
      .then(({ data }) => { if (active) setResult(normalizePagedRows(data)); }).catch((reason) => { if (active) setError(apiErrorMessage(reason)); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [stage, search, page, reload]);
  const rows = Array.isArray(result?.content) ? result.content : []; const total = result?.totalElements || 0; const pages = Math.max(1, result?.totalPages || 1);
  return <>
    <Heading eyebrow={readOnly ? 'Owner workspace' : 'Staff workspace'} title="Orders" description={readOnly ? 'Orders for your shop. Owner access is read-only.' : 'Create and track orders for your shop.'} action={!readOnly && <Link to="/staff/new-order" className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand px-4 text-sm font-semibold text-white"><Plus size={17} />New order</Link>} />
    <section className="mb-5 grid gap-3 rounded-3xl border border-slate-100 bg-white p-4 shadow-soft sm:grid-cols-[minmax(0,1fr)_220px] sm:p-5"><label className="block text-sm font-medium text-slate-700">Search by order code or customer<input type="search" value={search} onChange={(event) => { setSearch(event.target.value); setPage(0); }} placeholder="e.g. O-000123 or customer name" className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-300 px-3.5 text-sm" /></label><label className="block text-sm font-medium text-slate-700">Laundry stage<select value={stage} onChange={(event) => { setStage(event.target.value); setPage(0); }} className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-300 bg-white px-3 text-sm"><option value="">All stages</option>{LAUNDRY_STAGES.map((value) => <option key={value} value={value}>{stageName(value)}</option>)}</select></label></section>
    <ErrorMessage error={error} retry={() => setReload((n) => n + 1)} />
    {loading ? <Loading /> : error ? null : !rows.length ? <div className="mt-5 rounded-3xl border border-dashed border-slate-200 bg-white px-6 py-14 text-center"><ClipboardList size={26} className="mx-auto text-brand" /><h2 className="mt-4 font-semibold text-ink">No orders found</h2><p className="mt-2 text-sm text-slate-500">{search || stage ? 'Try another search or stage filter.' : 'Orders created for this shop will appear here.'}</p>{!readOnly && <Link to="/staff/new-order" className="mt-5 inline-flex min-h-10 items-center gap-2 rounded-xl bg-brand px-4 text-sm font-semibold text-white">Create an order <ArrowRight size={16} /></Link>}</div> : <section className="mt-5 overflow-hidden rounded-3xl border border-slate-200 bg-white shadow-soft"><div className="flex items-center justify-between border-b border-slate-100 px-5 py-4"><div><h2 className="font-semibold">Shop orders</h2><p className="mt-0.5 text-xs text-slate-500">{`${total} total \u00b7 newest first \u00b7 20 per page`}</p></div><span className="text-xs text-slate-500">Page {page + 1} of {pages}</span></div><div className="divide-y divide-slate-100">{rows.map((order) => <Link key={order.id} to={`${base}/${encodeURIComponent(order.id)}`} className="flex flex-wrap items-center justify-between gap-3 px-5 py-4 hover:bg-slate-50"><div className="min-w-0"><p className="font-semibold text-ink">{`${order.orderCode} \u00b7 ${order.customerName}`}</p><div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-slate-500"><span>{dateTime(order.createdAt)}</span><span>{'\u00b7'}</span><OrderQuantityBadge order={order} /><span>{'\u00b7'}</span><span>{order.serviceName}</span></div></div><div className="flex items-center gap-3"><div className="text-right"><div className="flex flex-wrap justify-end gap-2"><StagePill stage={order.stage} /><PaymentPill status={order.paymentStatus} /></div><p className="mt-1 text-sm font-semibold text-ink">{money(order.totalPrice)}</p></div><ArrowRight size={17} className="text-slate-400" /></div></Link>)}</div><div className="flex items-center justify-between border-t border-slate-100 px-4 py-3"><p className="text-xs text-slate-500">Showing {rows.length} of {total}</p><div className="flex gap-2"><button type="button" disabled={page === 0} onClick={() => setPage((n) => Math.max(0, n - 1))} className="min-h-9 rounded-lg border px-3 text-xs disabled:opacity-40">Previous</button><button type="button" disabled={page + 1 >= pages} onClick={() => setPage((n) => Math.min(pages - 1, n + 1))} className="min-h-9 rounded-lg border px-3 text-xs disabled:opacity-40">Next</button></div></div></section>}
  </>;
}

export function NewOrderPage() { return <><NewOrderHeading /><NewOrderForm /></>; }

export function OrderDetailPage({ readOnly = false }) {
  const { orderId } = useParams(); const location = useLocation();
  const [detail, setDetail] = useState(null); const [loading, setLoading] = useState(true); const [error, setError] = useState(''); const [reload, setReload] = useState(0);
  const [dialog, setDialog] = useState(false); const [cashDialog, setCashDialog] = useState(false); const [cancelLinkDialog, setCancelLinkDialog] = useState(false); const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState(location?.state?.toast ? { type: 'success', message: location.state.toast } : null);
  const stageRequestIds = useRef(new Map()); const linkRequestIds = useRef(new Map()); const base = readOnly ? '/owner/orders' : '/staff/orders';
  useEffect(() => { let active = true; setLoading(true); setError('');
    api.get(`/api/shops/orders/${encodeURIComponent(orderId)}`).then(({ data }) => { if (active) setDetail(normalizeOrderDetailResponse(data)); })
      .catch((reason) => { if (active) setError(apiErrorMessage(reason)); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [orderId, reload]);
  const order = detail?.order; const nextStage = nextLaundryStage(order?.stage); const paid = order?.paymentStatus === 'PAID'; const pendingPayment = order?.paymentStatus === 'PENDING';
  const paymentMethodPreference = order?.paymentMethodPreference || null; const paymentTiming = order?.paymentTiming || 'PAY_AT_CLAIM';
  const paymentDueNow = paymentTiming === 'PAY_NOW' || order?.stage === 'READY';
  const mayRecordPayment = !paid && order?.stage !== 'CLAIMED' && paymentDueNow;
  const advance = async () => {
    if (!order?.stage || !nextStage) return;
    setBusy(true);
    try {
      const key = `${orderId}:${order.stage}`; let clientRequestId = stageRequestIds.current.get(key);
      if (!clientRequestId) { clientRequestId = createClientRequestId(); stageRequestIds.current.set(key, clientRequestId); }
      const { data } = await api.post(`/api/shops/orders/${encodeURIComponent(orderId)}/advance`, { expectedStage: order.stage, clientRequestId });
      stageRequestIds.current.delete(key); setDialog(false); setToast({ type: 'success', message: `Order ${data.orderCode} moved to ${stageName(data.stage)}.` }); setReload((n) => n + 1);
    } catch (reason) { throw new Error(apiErrorMessage(reason)); } finally { setBusy(false); }
  };
  const recordCashPayment = async () => { setBusy(true); try { const { data } = await api.post(`/api/shops/orders/${encodeURIComponent(orderId)}/payments/cash`); setCashDialog(false); setToast({ type: 'success', message: `Cash payment recorded for ${data.orderCode}.` }); setReload((n) => n + 1); } catch (reason) { throw new Error(apiErrorMessage(reason)); } finally { setBusy(false); } };
  const createPayMongoLink = async () => {
    setBusy(true);
    try {
      const key = `${orderId}:${order?.totalPrice}:${paymentTiming}`; let clientRequestId = linkRequestIds.current.get(key);
      if (!clientRequestId) { clientRequestId = createClientRequestId(); linkRequestIds.current.set(key, clientRequestId); }
      const { data } = await api.post(`/api/shops/orders/${encodeURIComponent(orderId)}/payments/paymongo-link`, { clientRequestId });
      setToast({ type: 'success', message: `Payment link ready for ${data.order?.orderCode || order?.orderCode}.` }); setReload((n) => n + 1);
    } catch (reason) { setToast({ type: 'error', message: apiErrorMessage(reason, 'Payment link could not be created.') }); }
    finally { setBusy(false); }
  };
  const copyPaymentLink = async () => {
    try { await navigator.clipboard.writeText(order.paymentLinkUrl); setToast({ type: 'success', message: 'Payment link copied.' }); }
    catch { setToast({ type: 'error', message: 'Could not copy the payment link. Open it and copy the URL from your browser.' }); }
  };
  const confirmOnlinePayment = async () => { setBusy(true); try { const { data } = await api.post(`/api/shops/orders/${encodeURIComponent(orderId)}/payments/confirm-online`); setToast({ type: 'success', message: `PayMongo confirmed payment for ${data.orderCode}.` }); setReload((n) => n + 1); } catch (reason) { throw new Error(apiErrorMessage(reason)); } finally { setBusy(false); } };
  const cancelPaymentLink = async () => { setBusy(true); try { await api.post(`/api/shops/orders/${encodeURIComponent(orderId)}/payments/cancel-link`); setCancelLinkDialog(false); setToast({ type: 'success', message: `Pending payment link cancelled for ${order?.orderCode}.` }); setReload((n) => n + 1); } catch (reason) { throw new Error(apiErrorMessage(reason)); } finally { setBusy(false); } };
  return <><Toast type={toast?.type} message={toast?.message} onDismiss={() => setToast(null)} />
    <Heading eyebrow={readOnly ? 'Owner workspace' : 'Staff workspace'} title={order?.orderCode || 'Order details'} description={readOnly ? 'Order details are read-only for owners.' : 'Review order preferences, payment status, and laundry progress.'} action={<Link to={base} className="inline-flex min-h-10 items-center gap-2 rounded-xl border border-slate-200 px-3 text-sm font-semibold"><ArrowLeft size={16} />Orders</Link>} />
    <ErrorMessage error={error} retry={() => setReload((n) => n + 1)} />
    {loading ? <Loading label={'Loading order details\u2026'} /> : error ? null : !order ? <div className="rounded-2xl border border-dashed border-slate-200 bg-white p-10 text-center text-sm text-slate-500">Order not found.</div> : <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_360px]">
      <div className="space-y-5">
        <section className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-7">
          <div className="flex flex-wrap items-start justify-between gap-3"><div><p className="text-xs font-bold uppercase tracking-wide text-slate-400">Customer</p><h2 className="mt-1 text-xl font-semibold text-ink">{order.customerName || 'Customer'}</h2><p className="mt-1 text-sm text-slate-500">{order.mobile || '\u2014'}</p></div><div className="flex flex-wrap gap-2"><StagePill stage={order.stage} /><PaymentPill status={order.paymentStatus} /></div></div>
          <div className="mt-5 grid gap-4 rounded-2xl bg-slate-50 p-4 sm:grid-cols-3"><div><p className="text-xs font-semibold uppercase text-slate-400">Service</p><p className="mt-1 font-semibold">{order.serviceName || '\u2014'}</p></div><div><p className="text-xs font-semibold uppercase text-slate-400">{weightLabel(order.unit)}</p><div className="mt-1 font-semibold"><OrderQuantityBadge order={order} /></div></div><div><p className="text-xs font-semibold uppercase text-slate-400">Total price</p><p className="mt-1 text-lg font-semibold">{money(order.totalPrice)}</p></div></div>
          <div className="mt-4 rounded-2xl border border-slate-100 p-4"><p className="text-xs font-semibold uppercase text-slate-400">Payment</p><p className="mt-1 text-sm font-medium">{paid ? `Paid by ${order.paymentMethod ? formatPaymentMethod(order.paymentMethod) : '\u2014'}` : pendingPayment ? 'Payment link pending' : 'Payment due'}</p><div className="mt-2 flex flex-wrap gap-x-5 gap-y-1 text-xs text-slate-500"><span>Chosen method: {paymentMethodPreference === 'ONLINE' ? 'Online payment (PayMongo)' : paymentMethodPreference === 'CASH' ? 'Cash' : 'Not specified (both available)'}</span><span>Timing: {paymentTiming === 'PAY_AT_CLAIM' ? 'Pay when claiming the laundry' : 'Pay now'}</span></div>
            {pendingPayment && <><p className="mt-2 text-xs text-slate-500">Awaiting PayMongo confirmation for {money(Number(order.paymentAmountCentavos || 0) / 100)}.</p>{order.paymentLinkUrl && <><div className="mt-2 flex flex-wrap gap-3"><a className="inline-flex items-center gap-1 text-sm font-semibold text-brand underline" href={order.paymentLinkUrl} target="_blank" rel="noopener noreferrer">Open payment link <ExternalLink size={14} /></a><button type="button" onClick={() => void copyPaymentLink()} className="inline-flex items-center gap-1 text-sm font-semibold text-brand underline"><Copy size={14} />Copy link</button></div><div className="mt-3 inline-flex rounded-xl border border-slate-100 bg-white p-3"><QRCodeSVG value={order.paymentLinkUrl} size={144} level="M" /></div></>}</>}
            {paid && <><p className="mt-2 text-xs text-slate-500">Paid {dateTime(order.paidAt)}</p>{order.paidByName && <p className="mt-1 text-xs text-slate-500">Recorded by {order.paidByName}</p>}</>}
          </div>
          {order.notes && <div className="mt-5 rounded-2xl bg-slate-50 p-4"><p className="text-xs font-semibold uppercase text-slate-400">Notes</p><p className="mt-1 whitespace-pre-wrap text-sm">{order.notes}</p></div>}
          <p className="mt-5 text-xs text-slate-400">Created {dateTime(order.createdAt)}</p>
        </section>
        {detail?.invoice && <InvoiceSection invoice={detail.invoice} order={order} />}
        <section className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-7"><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Order status</p><h2 className="mt-1 text-lg font-semibold">Order status</h2><div className="mt-5"><OrderStatusTracker stageHistory={detail?.history || []} currentStage={order.stage} showStaffName /></div></section>
        <section className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-7"><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Price audit</p><h2 className="mt-1 text-lg font-semibold">Price history</h2>{!(detail?.priceLog || []).length ? <p className="mt-4 text-sm text-slate-500">No price history is available.</p> : <ol className="mt-4 divide-y divide-slate-100">{detail.priceLog.map((entry, index) => <li key={entry.id || `${entry.changedAt || 'price'}-${index}`} className="flex flex-wrap justify-between gap-2 py-3"><div><p className="font-semibold">{entry.previousPrice == null ? 'Initial price' : `${money(entry.previousPrice)} \u2192 ${money(entry.newPrice)}`}</p><p className="mt-1 text-xs text-slate-500">{`${entry.changedByName || 'Staff'} \u00b7 ${dateTime(entry.changedAt)}`}</p></div><p className="font-semibold">{money(entry.newPrice)}</p></li>)}</ol>}</section>
      </div>
      <aside className="h-fit rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-6"><p className="text-xs font-bold uppercase tracking-[.16em] text-slate-400">Current stage</p><p className="mt-2 text-xl font-semibold">{stageName(order.stage)}</p>
        {readOnly ? <p className="mt-3 text-sm leading-6 text-slate-500">Owner order access is read-only.</p> : <>
          {!paid && !pendingPayment && mayRecordPayment && (paymentMethodPreference === 'ONLINE' || paymentMethodPreference === null) && <button type="button" disabled={busy} onClick={createPayMongoLink} className="mt-5 min-h-11 w-full rounded-xl border border-sky-200 bg-sky-50 px-4 text-sm font-semibold text-sky-800 hover:bg-sky-100 disabled:opacity-50">{busy ? 'Creating payment link\u2026' : 'Create PayMongo payment link'}</button>}
          {!paid && !pendingPayment && mayRecordPayment && (paymentMethodPreference === 'CASH' || paymentMethodPreference === null) && <button type="button" disabled={busy} onClick={() => setCashDialog(true)} className="mt-3 min-h-11 w-full rounded-xl border border-emerald-200 bg-emerald-50 px-4 text-sm font-semibold text-emerald-800 hover:bg-emerald-100 disabled:opacity-50">Record cash payment</button>}
          {pendingPayment && <><button type="button" disabled={busy} onClick={() => setCancelLinkDialog(true)} className="mt-3 min-h-11 w-full rounded-xl border border-rose-200 bg-rose-50 px-4 text-sm font-semibold text-rose-800 disabled:opacity-50">Cancel payment link</button><button type="button" disabled={busy} onClick={() => { void confirmOnlinePayment().catch((reason) => setToast({ type: 'error', message: reason.message })); }} className="mt-3 min-h-11 w-full rounded-xl border border-slate-200 px-4 text-sm font-semibold disabled:opacity-50">Confirm payment status</button>{(paymentMethodPreference === 'CASH' || paymentMethodPreference === null) && mayRecordPayment && <button type="button" disabled={busy} onClick={() => setCashDialog(true)} className="mt-3 min-h-11 w-full rounded-xl border border-emerald-200 bg-emerald-50 px-4 text-sm font-semibold text-emerald-800 disabled:opacity-50">Record cash payment instead</button>}</>}
          {nextStage ? <button type="button" disabled={busy || (nextStage === 'CLAIMED' && !paid)} onClick={() => setDialog(true)} className="mt-3 min-h-11 w-full rounded-xl bg-brand px-4 text-sm font-semibold text-white disabled:opacity-50">{nextStage === 'CLAIMED' && !paid ? 'Payment required before claim' : `Move to ${stageName(nextStage)}`}</button> : <p className="mt-3 rounded-xl bg-emerald-50 p-3 text-sm text-emerald-800">This order has reached the final stage: Claimed.</p>}
        </>}
      </aside>
    </div>}
    {dialog && order && <ConfirmDialog title={`Move ${order.orderCode} to ${stageName(nextStage)}?`} message="This advances the order by exactly one stage. Stage changes cannot be undone." confirmLabel="Move to next stage" cancelLabel="Cancel" tone="default" loading={busy} onConfirm={advance} onCancel={() => setDialog(false)} formatError={apiErrorMessage} />}
    {cancelLinkDialog && order && <ConfirmDialog title={`Cancel the payment link for ${order.orderCode}?`} message="The PayMongo link will be archived and the order will return to Unpaid." confirmLabel="Cancel payment link" cancelLabel="Keep link" tone="danger" loading={busy} onConfirm={cancelPaymentLink} onCancel={() => setCancelLinkDialog(false)} formatError={apiErrorMessage} />}
    {cashDialog && order && <ConfirmDialog title="Record cash payment?" message={`Mark order ${order.orderCode} as paid in cash?${pendingPayment ? ' This will archive and supersede the pending online link.' : ''} This cannot be undone.`} confirmLabel="Record payment" cancelLabel="Cancel" tone="default" loading={busy} onConfirm={recordCashPayment} onCancel={() => setCashDialog(false)} formatError={apiErrorMessage} />}
  </>;
}
export function CustomerOrdersPage({ customerName = '' }) {
  const [result, setResult] = useState(null); const [page, setPage] = useState(0); const [loading, setLoading] = useState(true); const [error, setError] = useState(''); const [reload, setReload] = useState(0);
  useEffect(() => { let active = true; setLoading(true); setError(''); api.get('/api/me/orders', { params: { page } }).then(({ data }) => { if (active) setResult(normalizePagedRows(data)); }).catch((reason) => { if (active) setError(apiErrorMessage(reason)); }).finally(() => { if (active) setLoading(false); }); return () => { active = false; }; }, [page, reload]);
  const rows = Array.isArray(result?.content) ? result.content : []; const total = result?.totalElements ?? 0; const pages = Math.max(1, result?.totalPages || 1); const snapshot = customerOrderSnapshot(rows, total); const firstName = customerName.trim().split(/\s+/)[0] || 'there';
  return <div className="space-y-6">
    <section className="relative overflow-hidden rounded-[28px] bg-gradient-to-br from-[#0F172A] via-[#123A44] to-[#0D9488] px-6 py-7 text-white shadow-soft sm:px-9 sm:py-9">
      <div className="relative z-10 max-w-2xl"><p className="inline-flex items-center gap-2 rounded-full border border-white/20 bg-white/10 px-3 py-1.5 text-xs font-semibold text-teal-50"><Droplets size={15} /> Customer workspace</p><h1 className="mt-4 text-3xl font-semibold tracking-tight sm:text-4xl">Welcome back, {firstName}.</h1><p className="mt-3 max-w-xl text-sm leading-6 text-teal-50/90 sm:text-base">{'Keep up with the laundry orders linked to your verified account\u2014stage, shop, and payment status in one place.'}</p><Link to="/track-order" className="mt-5 inline-flex min-h-11 items-center gap-2 rounded-xl bg-white px-4 text-sm font-semibold text-slate-900 transition hover:bg-teal-50">Track a walk-in order <ExternalLink size={15} /></Link></div>
      <div aria-hidden="true" className="pointer-events-none absolute -right-10 -top-16 h-64 w-64 rounded-full border border-white/10 bg-white/5" /><div aria-hidden="true" className="pointer-events-none absolute -bottom-28 right-24 h-64 w-64 rounded-full border border-white/10" />
    </section>
    <section aria-label="Order overview" className="grid gap-3 sm:grid-cols-3">
      <article className="rounded-2xl border border-slate-100 bg-white p-4 shadow-soft sm:p-5"><div className="flex items-center gap-3"><span className="grid h-10 w-10 place-items-center rounded-xl bg-teal-50 text-brand"><Package size={19} /></span><div><p className="text-xs font-medium text-slate-500">All linked orders</p><p className="mt-0.5 text-2xl font-semibold text-ink">{loading && result === null ? '\u2014' : snapshot.totalOrders}</p></div></div><p className="mt-3 text-xs text-slate-500">Across your verified customer account</p></article>
      <article className="rounded-2xl border border-slate-100 bg-white p-4 shadow-soft sm:p-5"><div className="flex items-center gap-3"><span className="grid h-10 w-10 place-items-center rounded-xl bg-sky-50 text-sky-700"><Clock3 size={19} /></span><div><p className="text-xs font-medium text-slate-500">In progress</p><p className="mt-0.5 text-2xl font-semibold text-ink">{loading && result === null ? '\u2014' : snapshot.inProgressOnPage}</p></div></div><p className="mt-3 text-xs text-slate-500">On the currently loaded page</p></article>
      <article className="rounded-2xl border border-slate-100 bg-white p-4 shadow-soft sm:p-5"><div className="flex items-center gap-3"><span className="grid h-10 w-10 place-items-center rounded-xl bg-emerald-50 text-emerald-700"><BadgeCheck size={19} /></span><div><p className="text-xs font-medium text-slate-500">Ready for pickup</p><p className="mt-0.5 text-2xl font-semibold text-ink">{loading && result === null ? '\u2014' : snapshot.readyOnPage}</p></div></div><p className="mt-3 text-xs text-slate-500">On the currently loaded page</p></article>
    </section>
    <div className="grid items-start gap-5 xl:grid-cols-[minmax(0,1fr)_300px]">
      <section className="min-w-0 rounded-3xl border border-slate-100 bg-white p-4 shadow-soft sm:p-6">
        <div className="mb-5 flex flex-wrap items-start justify-between gap-3"><div><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Your laundry</p><h2 className="mt-1 text-xl font-semibold text-ink">My orders</h2><p className="mt-1 text-sm text-slate-500">Orders linked to your verified account. Walk-in orders can be tracked separately.</p></div><button type="button" disabled={loading} onClick={() => setReload((n) => n + 1)} className="inline-flex min-h-10 items-center gap-2 rounded-xl border border-slate-200 px-3 text-sm font-semibold text-slate-700 transition hover:bg-slate-50 disabled:opacity-50"><RefreshCw size={15} className={loading ? 'animate-spin' : ''} />Refresh</button></div>
        <ErrorMessage error={error} retry={() => setReload((n) => n + 1)} />
        {loading ? <Loading label={'Loading your orders\u2026'} /> : error ? null : !rows.length ? <div className="rounded-2xl border border-dashed border-slate-200 bg-slate-50/70 px-5 py-10 text-center sm:px-8"><span className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-white text-brand shadow-sm"><ClipboardList size={25} /></span><h3 className="mt-4 text-lg font-semibold text-ink">No linked orders yet</h3><p className="mx-auto mt-2 max-w-md text-sm leading-6 text-slate-600">When you place an order, ask the shop to attach it using the exact email on your verified SpindleTrack account. It will appear here once linked.</p><Link to="/track-order" className="mt-5 inline-flex min-h-10 items-center gap-2 rounded-xl bg-brand px-4 text-sm font-semibold text-white hover:bg-teal-700">Track a walk-in order <ArrowRight size={15} /></Link></div> : <>
          <div aria-live="polite" className="space-y-3">{rows.map((order) => <CustomerOrderCard key={order.id} order={order} />)}</div>
          <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 pt-4"><p className="text-xs text-slate-500">{`${total} linked order${total === 1 ? '' : 's'} \u00b7 page ${page + 1} of ${pages}`}</p><div className="flex gap-2"><button type="button" disabled={page === 0 || loading} onClick={() => setPage((n) => Math.max(0, n - 1))} className="min-h-9 rounded-lg border border-slate-200 px-3 text-xs font-semibold disabled:opacity-40">Previous</button><button type="button" disabled={page + 1 >= pages || loading} onClick={() => setPage((n) => Math.min(pages - 1, n + 1))} className="min-h-9 rounded-lg border border-slate-200 px-3 text-xs font-semibold disabled:opacity-40">Next</button></div></div>
        </>}
      </section>
      <aside className="space-y-4">
        <section className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft"><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Quick links</p><Link to="/track-order" className="mt-4 flex items-start gap-3 rounded-2xl border border-slate-100 p-3 transition hover:border-teal-200 hover:bg-teal-50/50"><span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-teal-50 text-brand"><Search size={17} /></span><span><span className="block text-sm font-semibold text-ink">Track a walk-in order</span><span className="mt-1 block text-xs leading-5 text-slate-500">Use the order code and mobile number given to the shop.</span></span></Link><Link to="/register-shop" className="mt-3 flex items-start gap-3 rounded-2xl border border-slate-100 p-3 transition hover:border-teal-200 hover:bg-teal-50/50"><span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-slate-100 text-slate-700"><Store size={17} /></span><span><span className="block text-sm font-semibold text-ink">Register your shop</span><span className="mt-1 block text-xs leading-5 text-slate-500">Own a laundry business? Submit it for review.</span></span></Link></section>
        <section className="rounded-3xl border border-teal-100 bg-teal-50/70 p-5"><div className="flex items-center gap-2 text-sm font-semibold text-teal-950"><BadgeCheck size={17} /> Why an order may be missing</div><p className="mt-2 text-sm leading-6 text-teal-900/80">Account orders appear after shop staff link them to your exact verified email. Walk-in lookup is separate and asks for your order code and mobile number.</p></section>
      </aside>
    </div>
  </div>;
}

export function CustomerOrderDetailPage() {
  const { orderId } = useParams(); const [detail, setDetail] = useState(null); const [loading, setLoading] = useState(true); const [error, setError] = useState(''); const [reload, setReload] = useState(0);
  const [lastUpdated, setLastUpdated] = useState(null);
  const stageRef = useRef(null);
  useEffect(() => { let active = true; const fetchOrder = (showLoading) => { if (showLoading) setLoading(true); setError(''); return api.get(`/api/me/orders/${encodeURIComponent(orderId)}`).then(({ data }) => { if (active) { const next = normalizeOrderDetailResponse(data); stageRef.current = next.order?.stage || null; setDetail(next); setLastUpdated(new Date()); } }).catch((reason) => { if (active) setError(apiErrorMessage(reason)); }).finally(() => { if (active && showLoading) setLoading(false); }); }; void fetchOrder(true); const refresh = () => { if (typeof document !== 'undefined' && document.visibilityState !== 'visible') return; if (stageRef.current === 'CLAIMED') return; void fetchOrder(false); }; const timer = setInterval(refresh, 30_000); if (typeof document !== 'undefined') document.addEventListener('visibilitychange', refresh); return () => { active = false; clearInterval(timer); if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', refresh); }; }, [orderId, reload]);
  const order = detail?.order;
  return <><Heading eyebrow="Customer" title={order?.orderCode || 'Order details'} description={order?.shopName ? `Laundry order at ${order.shopName}.` : 'Read-only details for your linked order.'} action={<Link to="/customer/orders" className="inline-flex min-h-10 items-center gap-2 rounded-xl border border-slate-200 px-3 text-sm font-semibold"><ArrowLeft size={16} />My orders</Link>} /><ErrorMessage error={error} retry={() => setReload((n) => n + 1)} />{loading && !order ? <Loading label={'Loading your order\u2026'} /> : error && !order ? null : !order ? <div className="rounded-2xl border border-dashed border-slate-200 bg-white p-10 text-center text-sm text-slate-500">Order not found.</div> : <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_360px]"><section className="space-y-5"><article className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-7"><div className="flex flex-wrap items-start justify-between gap-3"><div><p className="text-xs font-bold uppercase tracking-wide text-slate-400">{order.shopName}</p><h2 className="mt-1 text-xl font-semibold text-ink">{order.serviceName}</h2><div className="mt-1 flex flex-wrap items-center gap-2 text-sm text-slate-500"><OrderQuantityBadge order={order} /><span>{`\u00b7 Created ${dateTime(order.createdAt)}`}</span></div></div><div className="flex flex-wrap gap-2"><StagePill stage={order.stage} /><PaymentPill status={order.paymentStatus} /></div></div><p className="mt-5 text-2xl font-semibold text-ink">{money(order.totalPrice)}</p>{order.paymentMethod && <p className="mt-1 text-sm text-slate-500">{`Payment method: ${formatPaymentMethod(order.paymentMethod)}${order.paidAt ? ` \u00b7 ${dateTime(order.paidAt)}` : ''}`}</p>}</article><article className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-7"><div className="flex flex-wrap items-center justify-between gap-3"><h2 className="font-semibold text-ink">Order status</h2><button type="button" disabled={loading} onClick={() => setReload((n) => n + 1)} className="inline-flex min-h-9 items-center gap-2 rounded-lg border border-slate-200 px-3 text-xs font-semibold disabled:opacity-50"><RefreshCw size={14} className={loading ? 'animate-spin' : ''} />Refresh status</button></div><div className="mt-4"><OrderStatusTracker stageHistory={detail.history || []} currentStage={order.stage} showStaffName={false} /></div><p className="mt-3 text-xs text-slate-400">{`${lastUpdated ? `Last updated: ${formatDateTime(lastUpdated)}` : 'Updating\u2026'} \u00b7 Auto-refreshes every 30 seconds while visible.`}</p></article><article className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-7"><h2 className="font-semibold text-ink">Payment history</h2>{!(detail.paymentHistory || []).length ? <p className="mt-3 text-sm text-slate-500">No payment events are available yet.</p> : <ol className="mt-4 divide-y divide-slate-100">{detail.paymentHistory.map((event, index) => <li key={`${event.status}-${event.occurredAt || index}`} className="flex flex-wrap items-center justify-between gap-2 py-3"><p className="text-sm font-semibold">{`${paymentStatusLabel(event.status)}${event.method ? ` \u00b7 ${formatPaymentMethod(event.method)}` : ''}`}</p><time className="text-xs text-slate-500">{dateTime(event.occurredAt)}</time></li>)}</ol>}</article></section><aside className="h-fit rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-6"><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Current status</p><div className="mt-3"><StagePill stage={order.stage} /></div><p className="mt-3 text-sm leading-6 text-slate-500">For updates about this order, contact {order.shopName} directly.</p></aside></div>}</>;
}
