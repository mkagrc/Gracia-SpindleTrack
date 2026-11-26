import { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, BarChart3, ClipboardList, LoaderCircle, RefreshCw } from 'lucide-react';
import api, { apiErrorMessage } from './api.js';
import { useAuth } from './AuthContext.jsx';
import OrderQuantityBadge from './components/OrderQuantityBadge.jsx';
import { normalizeReadyOrderLists } from './orderPageData.js';

function PageHeader({ title, detail, reload, busy, staff = false }) {
  return <div className="mb-7 flex flex-wrap items-end justify-between gap-3"><div><p className="text-xs font-bold uppercase tracking-[.16em] text-teal-700">{staff ? 'Staff workspace' : 'Owner workspace'}</p><h1 className="mt-2 text-2xl font-semibold text-slate-900">{title}</h1><p className="mt-2 text-sm text-slate-500">{detail}</p></div><button type="button" onClick={reload} disabled={busy} className="inline-flex min-h-10 items-center gap-2 rounded-xl border border-slate-200 bg-white px-3 text-sm font-semibold text-slate-700 disabled:opacity-50"><RefreshCw size={16} className={busy ? 'animate-spin' : ''} />Refresh</button></div>;
}
function LoadError({ message, retry }) { return <div role="alert" className="rounded-2xl border border-red-200 bg-red-50 p-4 text-sm text-red-800"><div className="flex items-start gap-2"><AlertTriangle size={18} />{message}</div><button type="button" onClick={retry} className="mt-3 font-semibold underline">Try again</button></div>; }
function MetricCard({ label, value, description }) { return <article className="rounded-2xl border border-slate-100 bg-white p-5 shadow-sm"><p className="text-sm text-slate-500">{label}</p><p className="mt-2 text-2xl font-semibold text-slate-900">{value}</p><p className="mt-1 text-xs text-slate-400">{description}</p></article>; }
const money = (value) => new Intl.NumberFormat(undefined, { style: 'currency', currency: 'PHP' }).format(Number(value || 0));
const date = (value) => value ? new Date(value).toLocaleString() : '—';

export function OwnerDashboardMetrics() {
  const { session } = useAuth();
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const load = useCallback(async () => { setBusy(true); setError(''); try { const response = await api.get('/api/shops/mine/reports'); setData(response.data); } catch (reason) { setError(apiErrorMessage(reason, 'Dashboard metrics could not be loaded.')); } finally { setBusy(false); } }, []);
  useEffect(() => { if (session?.shop?.status === 'ACTIVE') void load(); }, [load, session?.shop?.status]);
  if (session?.shop?.status !== 'ACTIVE') return null;
  return <section aria-label="Shop dashboard metrics" className="mt-6"><div className="mb-3 flex items-center justify-between"><h2 className="font-semibold text-slate-900">Recent performance</h2><button type="button" disabled={busy} onClick={load} className="text-sm font-semibold text-teal-700 disabled:opacity-50">Refresh</button></div>{error ? <p role="alert" className="rounded-xl bg-red-50 p-3 text-sm text-red-800">{error}</p> : !data ? <p role="status" className="py-5 text-sm text-slate-500">Loading shop metrics…</p> : <div className="grid gap-3 sm:grid-cols-2"><MetricCard label="Income today" value={money(data.today?.income)} description={`${data.today?.orderCount || 0} orders today`} /><MetricCard label="Income this month" value={money(data.thisMonth?.income)} description={`${data.thisMonth?.orderCount || 0} orders this month`} /></div>}</section>;
}

export function OwnerReportsPage() {
  const [data, setData] = useState(null); const [busy, setBusy] = useState(true); const [error, setError] = useState('');
  const load = useCallback(async () => { setBusy(true); setError(''); try { const response = await api.get('/api/shops/mine/reports'); setData(response.data); } catch (reason) { setError(apiErrorMessage(reason, 'Reports could not be loaded.')); } finally { setBusy(false); } }, []);
  useEffect(() => { void load(); }, [load]);
  return <section><PageHeader title="Shop reports" detail="Income is based on recorded PAID payments; order totals count orders created in each period." reload={load} busy={busy} />
    {error && <LoadError message={error} retry={load} />}{busy && !data ? <div role="status" className="flex items-center gap-2 py-12 text-slate-500"><LoaderCircle className="animate-spin" />Loading reports…</div> : null}
    {data && <><div className="grid gap-4 sm:grid-cols-2"><MetricCard label={`Today · ${data.today?.date || ''}`} value={money(data.today?.income)} description={`${data.today?.orderCount || 0} orders created today`} /><MetricCard label={`This month · ${data.thisMonth?.month || ''}`} value={money(data.thisMonth?.income)} description={`${data.thisMonth?.orderCount || 0} orders created this month`} /></div>
      <div className="mt-7 rounded-2xl border border-slate-100 bg-white p-5"><div className="flex items-center gap-2"><BarChart3 size={18} className="text-teal-700" /><h2 className="font-semibold text-slate-900">Busiest days · last 30 days</h2></div>{data.busiestDays?.length ? <div className="mt-4 divide-y divide-slate-100">{data.busiestDays.map((row) => <div key={row.date} className="flex items-center justify-between gap-3 py-3 text-sm"><span className="font-medium text-slate-700">{new Date(`${row.date}T00:00:00`).toLocaleDateString()}</span><span className="text-slate-500">{row.orderCount} orders</span></div>)}</div> : <p className="mt-4 text-sm text-slate-500">No orders in the last 30 days.</p>}</div></>}
  </section>;
}

export function OwnerUnclaimedPage({ staff = false }) {
  const [tab, setTab] = useState('unclaimed');
  const [data, setData] = useState(null);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState('');
  const load = useCallback(async () => {
    setBusy(true); setError('');
    try {
      const response = await api.get('/api/shops/mine/ready-orders');
      setData(normalizeReadyOrderLists(response.data));
    } catch (reason) {
      setError(apiErrorMessage(reason, 'Orders could not be loaded.'));
    } finally { setBusy(false); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const unclaimed = Array.isArray(data?.unclaimed) ? data.unclaimed : [];
  const claimed   = Array.isArray(data?.claimed) ? data.claimed : [];

  return (
    <section>
      <PageHeader
        title="Unclaimed &amp; claimed"
        detail="Unclaimed lists all Ready orders regardless of payment status. Claimed shows completed pickups."
        reload={load} busy={busy} staff={staff}
      />
      {error && <LoadError message={error} retry={load} />}

      <div className="mb-5 flex gap-1 rounded-xl border border-slate-200 bg-slate-50 p-1 w-fit">
        {[['unclaimed', `Unclaimed (${unclaimed.length})`], ['claimed', `Claimed (${claimed.length})`]].map(([value, label]) => (
          <button key={value} type="button" onClick={() => setTab(value)}
            className={`min-h-9 rounded-lg px-4 text-sm font-semibold transition ${tab === value ? 'bg-white text-teal-800 shadow-sm' : 'text-slate-600 hover:text-slate-900'}`}
          >{label}</button>
        ))}
      </div>

      {busy && !data && !error && (
        <div role="status" className="flex items-center gap-2 py-12 text-slate-500"><LoaderCircle className="animate-spin" />Loading orders…</div>
      )}

      {!busy && !error && tab === 'unclaimed' && (unclaimed.length === 0
        ? <div className="rounded-2xl border border-dashed border-slate-200 bg-white px-6 py-12 text-center"><ClipboardList size={24} className="mx-auto text-teal-700" /><h2 className="mt-3 font-semibold text-slate-900">No unclaimed orders</h2><p className="mt-1 text-sm text-slate-500">All Ready orders have been picked up.</p></div>
        : <div className="overflow-x-auto rounded-2xl border border-slate-100 bg-white"><table className="w-full min-w-[760px] text-left text-sm"><thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500"><tr><th className="px-4 py-3">Order</th><th className="px-4 py-3">Customer</th><th className="px-4 py-3">Quantity</th><th className="px-4 py-3">Payment</th><th className="px-4 py-3">Ready since</th><th className="px-4 py-3">Overdue</th><th className="px-4 py-3">Total</th></tr></thead>
          <tbody className="divide-y divide-slate-100">{unclaimed.map((row) => (
            <tr key={row.id} className={row.overdue ? 'bg-rose-50/50' : ''}>
              <td className="px-4 py-3 font-semibold text-slate-900">{row.orderCode || row.id}</td>
              <td className="px-4 py-3">{row.customerName}</td>
              <td className="px-4 py-3 text-slate-600"><OrderQuantityBadge order={row} /></td>
              <td className="px-4 py-3"><span className={`inline-flex rounded-full px-2.5 py-1 text-xs font-semibold ${row.paymentStatus === 'PAID' ? 'bg-emerald-50 text-emerald-800' : row.paymentStatus === 'PENDING' ? 'bg-sky-50 text-sky-800' : 'bg-amber-50 text-amber-800'}`}>{row.paymentStatus === 'PAID' ? 'Paid' : row.paymentStatus === 'PENDING' ? 'Pending' : 'Unpaid'}</span></td>
              <td className="px-4 py-3 text-slate-600">{date(row.readyAt)}</td>
              <td className="px-4 py-3">{row.overdue && <span className="inline-flex items-center gap-1 rounded-full bg-rose-100 px-2.5 py-1 text-xs font-semibold text-rose-800"><AlertTriangle size={11} />Overdue 3+ days</span>}</td>
              <td className="px-4 py-3 text-slate-600">{money(row.totalPrice)}</td>
            </tr>
          ))}</tbody></table></div>
      )}

      {!busy && !error && tab === 'claimed' && (claimed.length === 0
        ? <div className="rounded-2xl border border-dashed border-slate-200 bg-white px-6 py-12 text-center"><ClipboardList size={24} className="mx-auto text-teal-700" /><h2 className="mt-3 font-semibold text-slate-900">No claimed orders</h2><p className="mt-1 text-sm text-slate-500">Orders will appear here once they reach the Claimed stage.</p></div>
        : <div className="overflow-x-auto rounded-2xl border border-slate-100 bg-white"><table className="w-full min-w-[700px] text-left text-sm"><thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500"><tr><th className="px-4 py-3">Order</th><th className="px-4 py-3">Customer</th><th className="px-4 py-3">Quantity</th><th className="px-4 py-3">Payment</th><th className="px-4 py-3">Claimed at</th><th className="px-4 py-3">Total</th></tr></thead>
          <tbody className="divide-y divide-slate-100">{claimed.map((row) => (
            <tr key={row.id}>
              <td className="px-4 py-3 font-semibold text-slate-900">{row.orderCode || row.id}</td>
              <td className="px-4 py-3">{row.customerName}</td>
              <td className="px-4 py-3 text-slate-600"><OrderQuantityBadge order={row} /></td>
              <td className="px-4 py-3"><span className={`inline-flex rounded-full px-2.5 py-1 text-xs font-semibold ${row.paymentStatus === 'PAID' ? 'bg-emerald-50 text-emerald-800' : 'bg-amber-50 text-amber-800'}`}>{row.paymentStatus === 'PAID' ? 'Paid' : 'Unpaid'}</span></td>
              <td className="px-4 py-3 text-slate-600">{date(row.claimedAt)}</td>
              <td className="px-4 py-3 text-slate-600">{money(row.totalPrice)}</td>
            </tr>
          ))}</tbody></table></div>
      )}
    </section>
  );
}
