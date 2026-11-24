import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, CheckCircle2, Droplets, LoaderCircle, Search } from 'lucide-react';
import api, { apiErrorMessage } from './api.js';
import { formatLookupTime, normalizeLookupCode, normalizeLookupMobile } from './publicOrderLookup.js';

const label = (value) => (value || '').replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase());
function PublicBrand() {
  return <Link to="/" className="inline-flex items-center gap-3 rounded-xl text-slate-900"><span className="grid h-10 w-10 place-items-center rounded-2xl bg-teal-600 text-white"><Droplets size={22} aria-hidden="true" /></span><span><span className="block text-base font-bold">SpindleTrack</span><span className="block text-[10px] font-semibold uppercase tracking-[.18em] text-slate-400">Laundry operations</span></span></Link>;
}

export default function PublicOrderLookupPage() {
  const [orderCode, setOrderCode] = useState('');
  const [mobile, setMobile] = useState('');
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const submit = async (event) => {
    event.preventDefault();
    setBusy(true); setError(''); setResult(null);
    try {
      const { data } = await api.get('/api/public/orders/lookup', {
        params: { orderCode: normalizeLookupCode(orderCode), mobile: normalizeLookupMobile(mobile) },
      });
      setResult(data);
    } catch (reason) {
      setError(apiErrorMessage(reason, 'Order could not be found. Check the order code and mobile number.'));
    } finally { setBusy(false); }
  };
  return <div className="flex min-h-screen flex-col bg-slate-50 text-slate-900">
    <header className="border-b border-slate-200 bg-white"><div className="mx-auto flex min-h-[68px] max-w-5xl items-center justify-between gap-4 px-5 sm:px-8"><PublicBrand /><Link to="/sign-in" className="inline-flex min-h-10 items-center rounded-xl px-4 text-sm font-semibold text-slate-700 hover:bg-slate-100">Sign in</Link></div></header>
    <main className="mx-auto w-full max-w-3xl flex-1 px-5 py-10 sm:px-8 sm:py-14">
      <div className="mb-7"><p className="text-xs font-bold uppercase tracking-[.16em] text-teal-700">Walk-in tracking</p><h1 className="mt-2 text-3xl font-semibold tracking-tight sm:text-4xl">Track your laundry</h1><p className="mt-3 max-w-xl text-sm leading-6 text-slate-600">Enter the order code and the mobile number provided to the shop. For your privacy, the result shows only a masked number and order progress.</p></div>
      <section className="rounded-3xl border border-slate-100 bg-white p-5 shadow-sm sm:p-7"><form onSubmit={submit} className="grid gap-4 sm:grid-cols-2">
        <label className="text-sm font-medium text-slate-700">Order code<input required maxLength={32} autoComplete="off" value={orderCode} onChange={(event) => setOrderCode(event.target.value)} placeholder="e.g. O-000123" className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-300 px-3.5 outline-none focus:border-teal-600 focus:ring-4 focus:ring-teal-50" /></label>
        <label className="text-sm font-medium text-slate-700">Mobile number<input required type="tel" maxLength={32} autoComplete="tel" value={mobile} onChange={(event) => setMobile(event.target.value)} placeholder="Number given to the shop" className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-300 px-3.5 outline-none focus:border-teal-600 focus:ring-4 focus:ring-teal-50" /></label>
        <button disabled={busy} className="inline-flex min-h-12 items-center justify-center gap-2 rounded-xl bg-teal-600 px-5 font-semibold text-white hover:bg-teal-700 disabled:opacity-60 sm:col-span-2">{busy ? <><LoaderCircle size={18} className="animate-spin" />Looking up…</> : <><Search size={17} />Find my order</>}</button>
      </form>{error && <p role="alert" className="mt-4 rounded-xl border border-red-200 bg-red-50 p-3 text-sm leading-5 text-red-800">{error}</p>}</section>
      {result && <section aria-live="polite" className="mt-6 rounded-3xl border border-slate-100 bg-white p-5 shadow-sm sm:p-7">
        <div className="flex flex-wrap items-start justify-between gap-3"><div><p className="text-xs font-bold uppercase tracking-[.16em] text-slate-400">Order code</p><h2 className="mt-1 text-2xl font-semibold">{result.orderCode}</h2><p className="mt-1 text-sm text-slate-500">Mobile ending {String(result.maskedMobile || '').slice(-4)}</p></div><span className="rounded-full bg-teal-50 px-3 py-1.5 text-sm font-semibold text-teal-800">{label(result.currentStage)}</span></div>
        <div className="mt-5 rounded-xl bg-slate-50 p-3"><p className="text-xs font-semibold uppercase tracking-wide text-slate-400">Service quantity</p><p className="mt-1 text-sm font-semibold">{Number(result.quantity || 0).toFixed(2)} {result.unit || 'kg'}</p></div>
        <div className="mt-6"><h3 className="text-sm font-semibold">Laundry stages</h3><ol className="mt-3 grid gap-2 sm:grid-cols-3">{(result.stageHistory || []).map((event, index) => <li key={`${event.stage}-${event.changedAt || index}`} className="flex items-start gap-3 rounded-xl bg-slate-50 p-3"><CheckCircle2 size={17} className="mt-0.5 shrink-0 text-teal-600" /><span><span className="block text-sm font-semibold">{label(event.stage)}</span><span className="mt-0.5 block text-xs text-slate-500">{formatLookupTime(event.changedAt)}</span></span></li>)}</ol>{(!result.stageHistory || result.stageHistory.length === 0) && <p className="mt-2 text-sm text-slate-500">No stage history is available yet.</p>}</div>
        <div className="mt-6 border-t border-slate-100 pt-5"><div className="flex flex-wrap items-center justify-between gap-2"><h3 className="text-sm font-semibold">Payment status</h3><span className={`rounded-full px-3 py-1.5 text-xs font-semibold ${result.paymentStatus === 'PAID' ? 'bg-emerald-50 text-emerald-800' : result.paymentStatus === 'PENDING' ? 'bg-amber-50 text-amber-800' : 'bg-slate-100 text-slate-700'}`}>{label(result.paymentStatus)}</span></div></div>
      </section>}
      <p className="mt-6 text-xs leading-5 text-slate-400">For privacy, the lookup does not display your name, full mobile number, shop details, notes, or order price. Need help? Contact the laundry shop.</p>
      <Link to="/" className="mt-6 inline-flex items-center gap-1 text-sm font-semibold text-teal-700 hover:text-teal-900">Back to SpindleTrack <ArrowRight size={15} /></Link>
    </main>
    <footer className="border-t border-slate-200 bg-white px-5 py-5 text-center text-xs text-slate-400">SpindleTrack · Order tracking</footer>
  </div>;
}
