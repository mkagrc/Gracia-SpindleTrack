import { useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, ArrowRight, ClipboardList, LoaderCircle, Plus } from 'lucide-react';
import api, { apiErrorMessage } from './api.js';
import ConfirmDialog from './ConfirmDialog.jsx';
import Toast from './Toast.jsx';

export const LAUNDRY_STAGES = ['RECEIVED', 'WASHING', 'DRYING', 'FOLDING', 'READY', 'CLAIMED'];
export const nextLaundryStage = (stage) => {
  const index = LAUNDRY_STAGES.indexOf(stage);
  return index >= 0 && index < LAUNDRY_STAGES.length - 1 ? LAUNDRY_STAGES[index + 1] : null;
};
const money = (amount) => `₱${Number(amount || 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
const stageName = (stage) => (stage || 'UNKNOWN').replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase());
const dateTime = (value) => value ? new Date(value).toLocaleString() : '—';

function Heading({ eyebrow, title, description, action }) {
  return <div className="mb-7 flex flex-wrap items-end justify-between gap-4"><div><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">{eyebrow}</p><h1 className="mt-2 text-2xl font-semibold tracking-tight text-ink sm:text-3xl">{title}</h1>{description && <p className="mt-2 max-w-2xl text-sm leading-6 text-slate-500">{description}</p>}</div>{action}</div>;
}
function Loading({ label = 'Loading orders…' }) { return <div role="status" className="flex min-h-56 items-center justify-center gap-3 text-sm text-slate-500"><LoaderCircle size={20} className="animate-spin text-brand" />{label}</div>; }
function ErrorMessage({ error, retry }) { return error ? <div role="alert" className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">{error}{retry && <button type="button" onClick={retry} className="font-semibold underline">Try again</button>}</div> : null; }
function StagePill({ stage }) { return <span className="inline-flex rounded-full bg-teal-50 px-2.5 py-1 text-xs font-semibold text-teal-800">{stageName(stage)}</span>; }

export function OrdersListPage({ readOnly = false }) {
  const [stage, setStage] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  const base = readOnly ? '/owner/orders' : '/staff/orders';
  useEffect(() => {
    let active = true;
    setLoading(true); setError('');
    api.get('/api/shops/orders', { params: { ...(stage ? { stage } : {}), ...(search.trim() ? { search: search.trim() } : {}), page } })
      .then(({ data }) => { if (active) setResult(data); })
      .catch((reason) => { if (active) setError(apiErrorMessage(reason)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [stage, search, page, reload]);
  const rows = result?.content || [];
  const total = result?.totalElements || 0;
  const pages = Math.max(1, result?.totalPages || 1);
  const retry = () => setReload((value) => value + 1);
  return <>
    <Heading eyebrow={readOnly ? 'Owner workspace' : 'Staff workspace'} title="Orders" description={readOnly ? 'Orders for your shop. Owner access is read-only.' : 'Create and track orders for your shop.'} action={!readOnly && <Link to="/staff/new-order" className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand px-4 text-sm font-semibold text-white hover:bg-teal-700"><Plus size={17} />New order</Link>} />
    <section className="mb-5 grid gap-3 rounded-3xl border border-slate-100 bg-white p-4 shadow-soft sm:grid-cols-[minmax(0,1fr)_220px] sm:p-5">
      <label className="block text-sm font-medium text-slate-700">Search by order code or customer<input type="search" value={search} onChange={(event) => { setSearch(event.target.value); setPage(0); }} placeholder="e.g. O-000123 or customer name" className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-300 px-3.5 text-sm outline-none focus:border-brand focus:ring-4 focus:ring-teal-50" /></label>
      <label className="block text-sm font-medium text-slate-700">Laundry stage<select value={stage} onChange={(event) => { setStage(event.target.value); setPage(0); }} className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-300 bg-white px-3 text-sm outline-none focus:border-brand focus:ring-4 focus:ring-teal-50"><option value="">All stages</option>{LAUNDRY_STAGES.map((value) => <option key={value} value={value}>{stageName(value)}</option>)}</select></label>
    </section>
    <ErrorMessage error={error} retry={retry} />
    {loading ? <Loading /> : error ? null : !rows.length ? <div className="mt-5 rounded-3xl border border-dashed border-slate-200 bg-white px-6 py-14 text-center"><ClipboardList size={26} className="mx-auto text-brand" /><h2 className="mt-4 font-semibold text-ink">No orders found</h2><p className="mt-2 text-sm text-slate-500">{search || stage ? 'Try another search or stage filter.' : 'Orders created for this shop will appear here.'}</p>{!readOnly && <Link to="/staff/new-order" className="mt-5 inline-flex min-h-10 items-center gap-2 rounded-xl bg-brand px-4 text-sm font-semibold text-white">Create an order <ArrowRight size={16} /></Link>}</div> : <section className="mt-5 overflow-hidden rounded-3xl border border-slate-200 bg-white shadow-soft"><div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 px-5 py-4"><div><h2 className="font-semibold">Shop orders</h2><p className="mt-0.5 text-xs text-slate-500">{total} total · newest first · 20 per page</p></div><span className="text-xs text-slate-500">Page {page + 1} of {pages}</span></div><div className="divide-y divide-slate-100">{rows.map((order) => <Link key={order.id} to={`${base}/${encodeURIComponent(order.id)}`} className="flex flex-wrap items-center justify-between gap-3 px-5 py-4 transition hover:bg-slate-50"><div className="min-w-0"><p className="font-semibold text-ink">{order.orderCode} <span className="font-normal text-slate-400">·</span> {order.customerName}</p><p className="mt-1 text-xs text-slate-500">{dateTime(order.createdAt)} · {order.items?.length || 0} item{order.items?.length === 1 ? '' : 's'}</p></div><div className="flex items-center gap-3"><div className="text-right"><StagePill stage={order.stage} /><p className="mt-1 text-sm font-semibold text-ink">{money(order.total)}</p></div><ArrowRight size={17} className="text-slate-400" /></div></Link>)}</div><div className="flex items-center justify-between gap-3 border-t border-slate-100 px-4 py-3"><p className="text-xs text-slate-500">Showing {rows.length} of {total}</p><div className="flex gap-2"><button type="button" disabled={page === 0} onClick={() => setPage((value) => Math.max(0, value - 1))} className="min-h-9 rounded-lg border border-slate-200 px-3 text-xs font-semibold disabled:opacity-40">Previous</button><button type="button" disabled={page + 1 >= pages} onClick={() => setPage((value) => Math.min(pages - 1, value + 1))} className="min-h-9 rounded-lg border border-slate-200 px-3 text-xs font-semibold disabled:opacity-40">Next</button></div></div></section>}
  </>;
}

export function NewOrderPage() {
  const navigate = useNavigate();
  const [services, setServices] = useState([]);
  const [servicesLoading, setServicesLoading] = useState(true);
  const [servicesError, setServicesError] = useState('');
  const [customerName, setCustomerName] = useState('');
  const [mobile, setMobile] = useState('');
  const [notes, setNotes] = useState('');
  const [lines, setLines] = useState([{ serviceId: '', quantity: '1' }]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [toast, setToast] = useState(null);
  useEffect(() => {
    let active = true;
    api.get('/api/shops/services').then(({ data }) => { if (active) setServices(Array.isArray(data) ? data.filter((service) => service.active) : []); })
      .catch((reason) => { if (active) setServicesError(apiErrorMessage(reason)); })
      .finally(() => { if (active) setServicesLoading(false); });
    return () => { active = false; };
  }, []);
  const total = useMemo(() => lines.reduce((sum, line) => {
    const service = services.find((item) => item.id === line.serviceId);
    return sum + Number(service?.pricePerUnit || 0) * Math.max(0, Number.parseInt(line.quantity, 10) || 0);
  }, 0), [lines, services]);
  const updateLine = (index, key, value) => setLines((current) => current.map((line, row) => row === index ? { ...line, [key]: value } : line));
  const submit = async (event) => {
    event.preventDefault(); setBusy(true); setError('');
    try {
      const payload = { customerName, mobile, notes, items: lines.map((line) => ({ serviceId: line.serviceId, quantity: Number.parseInt(line.quantity, 10) })) };
      const { data } = await api.post('/api/shops/orders', payload);
      setToast({ type: 'success', message: `Order ${data.orderCode} was created.` });
      navigate(`/staff/orders/${encodeURIComponent(data.id)}`, { state: { toast: `Order ${data.orderCode} was created.` } });
    } catch (reason) { setError(apiErrorMessage(reason, 'The order could not be created.')); }
    finally { setBusy(false); }
  };
  return <>
    <Toast type={toast?.type} message={toast?.message} onDismiss={() => setToast(null)} />
    <Heading eyebrow="Staff workspace" title="New order" description="Choose active services. Prices and totals are verified by the server." action={<Link to="/staff/orders" className="inline-flex min-h-10 items-center gap-2 rounded-xl border border-slate-200 px-3 text-sm font-semibold text-slate-700"><ArrowLeft size={16} />Orders</Link>} />
    {servicesLoading ? <Loading label="Loading active services…" /> : servicesError ? <ErrorMessage error={servicesError} retry={() => window.location.reload()} /> : !services.length ? <div className="rounded-2xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">There are no active services available for this shop. Ask the owner to activate a service before creating an order.</div> : <form onSubmit={submit} className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_320px]">
      <section className="space-y-5 rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-7">
        <ErrorMessage error={error} />
        <div className="grid gap-4 sm:grid-cols-2"><label className="block text-sm font-medium text-slate-700">Customer name<input required maxLength={120} value={customerName} onChange={(event) => setCustomerName(event.target.value)} className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-300 px-3.5 outline-none focus:border-brand focus:ring-4 focus:ring-teal-50" /></label><label className="block text-sm font-medium text-slate-700">Mobile number<input required type="tel" maxLength={32} value={mobile} onChange={(event) => setMobile(event.target.value)} placeholder="e.g. +63 917 123 4567" className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-300 px-3.5 outline-none focus:border-brand focus:ring-4 focus:ring-teal-50" /></label></div>
        <div><div className="mb-3 flex items-center justify-between gap-3"><div><h2 className="font-semibold text-ink">Services</h2><p className="mt-1 text-xs text-slate-500">Choose each active service once and enter a quantity.</p></div><button type="button" disabled={lines.length >= 50 || lines.some((line) => !line.serviceId)} onClick={() => setLines((current) => [...current, { serviceId: '', quantity: '1' }])} className="inline-flex min-h-9 items-center gap-1 rounded-lg border border-slate-200 px-3 text-xs font-semibold disabled:opacity-40"><Plus size={14} />Add item</button></div><div className="space-y-3">{lines.map((line, index) => {
          const service = services.find((item) => item.id === line.serviceId);
          return <div key={`order-line-${index}`} className="grid gap-3 rounded-2xl border border-slate-100 bg-slate-50 p-3 sm:grid-cols-[minmax(0,1fr)_110px_110px_auto] sm:items-end"><label className="block text-xs font-semibold text-slate-600">Service<select required value={line.serviceId} onChange={(event) => updateLine(index, 'serviceId', event.target.value)} className="mt-1.5 min-h-10 w-full rounded-xl border border-slate-300 bg-white px-3 text-sm font-normal"><option value="">Select a service</option>{services.filter((item) => item.id === line.serviceId || !lines.some((other, row) => row !== index && other.serviceId === item.id)).map((item) => <option key={item.id} value={item.id}>{item.name} · {money(item.pricePerUnit)}/{item.unit}</option>)}</select></label><label className="block text-xs font-semibold text-slate-600">Quantity<input required type="number" min="1" step="1" value={line.quantity} onChange={(event) => updateLine(index, 'quantity', event.target.value)} className="mt-1.5 min-h-10 w-full rounded-xl border border-slate-300 bg-white px-3 text-sm font-normal" /></label><div className="text-xs text-slate-500">Line total<p className="mt-1 text-sm font-semibold text-ink">{money(Number(service?.pricePerUnit || 0) * (Number.parseInt(line.quantity, 10) || 0))}</p></div>{lines.length > 1 && <button type="button" onClick={() => setLines((current) => current.filter((_, row) => row !== index))} className="min-h-9 rounded-lg px-3 text-xs font-semibold text-rose-700 hover:bg-rose-50">Remove</button>}</div>;
        })}</div></div>
        <label className="block text-sm font-medium text-slate-700">Notes <span className="font-normal text-slate-400">(optional)</span><textarea maxLength={1000} rows={3} value={notes} onChange={(event) => setNotes(event.target.value)} className="mt-1.5 w-full rounded-xl border border-slate-300 px-3.5 py-3 text-sm outline-none focus:border-brand focus:ring-4 focus:ring-teal-50" /></label>
      </section>
      <aside className="h-fit rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-6"><p className="text-xs font-bold uppercase tracking-[.16em] text-slate-400">Order total</p><p className="mt-2 text-3xl font-semibold text-ink">{money(total)}</p><p className="mt-2 text-xs leading-5 text-slate-500">Displayed estimate. The server calculates the saved total from current active service prices.</p><button disabled={busy || !customerName.trim() || !mobile.trim() || lines.some((line) => !line.serviceId || Number.parseInt(line.quantity, 10) < 1)} className="mt-6 inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-brand px-4 font-semibold text-white hover:bg-teal-700 disabled:opacity-50">{busy ? <><LoaderCircle size={17} className="animate-spin" />Creating…</> : <>Create order <ArrowRight size={17} /></>}</button></aside>
    </form>}
  </>;
}

export function OrderDetailPage({ readOnly = false }) {
  const { orderId } = useParams();
  const location = useLocation();
  const [detail, setDetail] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  const [dialog, setDialog] = useState(false);
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState(location?.state?.toast ? { type: 'success', message: location.state.toast } : null);
  const base = readOnly ? '/owner/orders' : '/staff/orders';
  useEffect(() => {
    let active = true;
    setLoading(true); setError('');
    api.get(`/api/shops/orders/${encodeURIComponent(orderId)}`).then(({ data }) => { if (active) setDetail(data); })
      .catch((reason) => { if (active) setError(apiErrorMessage(reason)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [orderId, reload]);
  const order = detail?.order;
  const nextStage = nextLaundryStage(order?.stage);
  const advance = async () => {
    setBusy(true);
    try {
      const { data } = await api.post(`/api/shops/orders/${encodeURIComponent(orderId)}/advance`);
      setDialog(false);
      setToast({ type: 'success', message: `Order ${data.orderCode} moved to ${stageName(data.stage)}.` });
      setReload((value) => value + 1);
    } catch (reason) { throw new Error(apiErrorMessage(reason)); }
    finally { setBusy(false); }
  };
  return <>
    <Toast type={toast?.type} message={toast?.message} onDismiss={() => setToast(null)} />
    <Heading eyebrow={readOnly ? 'Owner workspace' : 'Staff workspace'} title={order?.orderCode || 'Order details'} description={readOnly ? 'Order details are read-only for owners.' : 'Review the order and move it forward one laundry stage at a time.'} action={<Link to={base} className="inline-flex min-h-10 items-center gap-2 rounded-xl border border-slate-200 px-3 text-sm font-semibold text-slate-700"><ArrowLeft size={16} />Orders</Link>} />
    <ErrorMessage error={error} retry={() => setReload((value) => value + 1)} />
    {loading ? <Loading label="Loading order details…" /> : error ? null : !order ? <div className="rounded-2xl border border-dashed border-slate-200 bg-white p-10 text-center text-sm text-slate-500">Order not found.</div> : <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_360px]">
      <div className="space-y-5">
        <section className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-7"><div className="flex flex-wrap items-start justify-between gap-3"><div><p className="text-xs font-bold uppercase tracking-wide text-slate-400">Customer</p><h2 className="mt-1 text-xl font-semibold text-ink">{order.customerName}</h2><p className="mt-1 text-sm text-slate-500">{order.mobile}</p></div><StagePill stage={order.stage} /></div><div className="mt-5 overflow-x-auto"><table className="w-full min-w-[480px] text-left text-sm"><thead className="border-b border-slate-100 text-xs text-slate-400"><tr><th className="py-3">Service</th><th className="py-3">Quantity</th><th className="py-3">Unit price</th><th className="py-3 text-right">Line total</th></tr></thead><tbody className="divide-y divide-slate-100">{(order.items || []).map((item, index) => <tr key={`${item.serviceId}-${index}`}><td className="py-3 font-medium text-ink">{item.name}</td><td className="py-3 text-slate-600">{item.quantity} {item.unit}</td><td className="py-3 text-slate-600">{money(item.unitPrice)}</td><td className="py-3 text-right font-medium">{money(item.lineTotal)}</td></tr>)}</tbody><tfoot><tr><td colSpan="3" className="pt-4 text-right font-semibold">Total</td><td className="pt-4 text-right text-lg font-semibold text-ink">{money(order.total)}</td></tr></tfoot></table></div>{order.notes && <div className="mt-5 rounded-2xl bg-slate-50 p-4"><p className="text-xs font-semibold uppercase tracking-wide text-slate-400">Notes</p><p className="mt-1 whitespace-pre-wrap text-sm text-slate-700">{order.notes}</p></div>}<p className="mt-5 text-xs text-slate-400">Created {dateTime(order.createdAt)}</p></section>
        <section className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-7"><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Laundry timeline</p><h2 className="mt-1 text-lg font-semibold">Order stages</h2><ol className="mt-5 space-y-0">{LAUNDRY_STAGES.map((stage, index) => {
          const activeIndex = LAUNDRY_STAGES.indexOf(order.stage);
          const completed = index <= activeIndex;
          const historyRow = (detail.history || []).find((entry) => entry.stage === stage);
          return <li key={stage} className="relative flex gap-4 pb-5 last:pb-0"><span className={`relative z-10 mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-full text-xs font-bold ${completed ? 'bg-brand text-white' : 'border-2 border-slate-200 bg-white text-slate-400'}`}>{index + 1}</span>{index < LAUNDRY_STAGES.length - 1 && <span className={`absolute left-[13px] top-7 h-[calc(100%-1.25rem)] w-px ${index < activeIndex ? 'bg-brand' : 'bg-slate-200'}`} aria-hidden="true" />}<div><p className={`text-sm font-semibold ${index === activeIndex ? 'text-teal-800' : completed ? 'text-ink' : 'text-slate-400'}`}>{stageName(stage)}</p><p className="mt-0.5 text-xs text-slate-500">{historyRow ? dateTime(historyRow.changedAt) : index === 0 && order.createdAt ? dateTime(order.createdAt) : completed ? 'Recorded' : 'Not reached'}</p></div></li>;
        })}</ol></section>
      </div>
      <aside className="h-fit rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-6"><p className="text-xs font-bold uppercase tracking-[.16em] text-slate-400">Current stage</p><p className="mt-2 text-xl font-semibold text-ink">{stageName(order.stage)}</p>{readOnly ? <p className="mt-3 text-sm leading-6 text-slate-500">Owner order access is read-only. Staff can update the laundry stage.</p> : nextStage ? <button type="button" onClick={() => setDialog(true)} className="mt-5 min-h-11 w-full rounded-xl bg-brand px-4 text-sm font-semibold text-white hover:bg-teal-700">Move to {stageName(nextStage)}</button> : <p className="mt-3 rounded-xl bg-emerald-50 p-3 text-sm text-emerald-800">This order has reached the final stage: Claimed.</p>}</aside>
      </div>}
    {dialog && <ConfirmDialog title={`Move ${order.orderCode} to ${stageName(nextStage)}?`} message="This advances the order by exactly one stage. Stage changes cannot be undone." confirmLabel="Move to next stage" cancelLabel="Cancel" tone="default" loading={busy} onConfirm={advance} onCancel={() => setDialog(false)} formatError={apiErrorMessage} />}
  </>;
}
