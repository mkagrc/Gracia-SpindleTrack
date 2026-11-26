import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, ExternalLink, LoaderCircle, RefreshCw, Search } from 'lucide-react';
import api, { apiErrorMessage } from './api.js';
import { normalizeLookupCode, normalizeLookupMobile } from './publicOrderLookup.js';
import { clearPublicLookupCredentials, readPublicLookupCredentials, writePublicLookupCredentials } from './publicLookupSession.js';
import { savePendingPayPalOrder } from './paypalSession.js';
import OrderStatusTracker, { stageDisplayLabel } from './components/OrderStatusTracker.jsx';
import OrderQuantityBadge from './components/OrderQuantityBadge.jsx';
import { isPayPalCheckoutEligible, normalizePublicOrderLookup } from './orderPageData.js';

function sessionStorageOrNull() {
  try { return typeof window === 'undefined' ? null : window.sessionStorage; }
  catch { return null; }
}

function createClientRequestId(cryptoSource = globalThis.crypto) {
  if (cryptoSource?.randomUUID) return cryptoSource.randomUUID();
  if (!cryptoSource?.getRandomValues) throw new Error('Secure UUID generation is unavailable.');
  const bytes = cryptoSource.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export default function PublicOrderLookupPage() {
  const [orderCode, setOrderCode] = useState(() => readPublicLookupCredentials(sessionStorageOrNull())?.orderCode || '');
  const [mobile, setMobile] = useState(() => readPublicLookupCredentials(sessionStorageOrNull())?.mobile || '');
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const [payBusy, setPayBusy] = useState(false);
  const [payError, setPayError] = useState('');
  const [error, setError] = useState('');
  const inFlight = useRef(false);
  const payRequestIdRef = useRef('');

  const lookup = async (code, phone, persist = true) => {
    if (inFlight.current) return;
    const credentials = { orderCode: String(code || '').trim(), mobile: String(phone || '').trim() };
    if (!credentials.orderCode || !credentials.mobile) return;
    if (persist) writePublicLookupCredentials(sessionStorageOrNull(), credentials);
    inFlight.current = true;
    setBusy(true);
    setError('');
    try {
      const { data } = await api.get('/api/public/orders/lookup', {
        params: { orderCode: normalizeLookupCode(credentials.orderCode), mobile: normalizeLookupMobile(credentials.mobile) },
      });
      setResult(normalizePublicOrderLookup(data));
    } catch (reason) {
      setError(apiErrorMessage(reason, 'Order could not be found. Check the order code and mobile number.'));
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  };

  useEffect(() => {
    const refreshVisibleTab = () => {
      if (typeof document === 'undefined' || document.visibilityState !== 'visible') return;
      const saved = readPublicLookupCredentials(sessionStorageOrNull());
      if (saved) void lookup(saved.orderCode, saved.mobile, false);
    };
    refreshVisibleTab();
    const timer = setInterval(refreshVisibleTab, 30_000);
    if (typeof document !== 'undefined') document.addEventListener('visibilitychange', refreshVisibleTab);
    return () => {
      clearInterval(timer);
      if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', refreshVisibleTab);
    };
  }, []);

  const submit = (event) => { event.preventDefault(); void lookup(orderCode, mobile); };
  const trackAnother = () => { clearPublicLookupCredentials(sessionStorageOrNull()); setResult(null); setError(''); setPayError(''); setOrderCode(''); setMobile(''); };
  const payEligible = isPayPalCheckoutEligible(result);
  const payAtClaimWaiting = result && result.paymentStatus !== 'PAID'
    && result.paymentMethodPreference === 'ONLINE'
    && result.paymentTiming === 'PAY_AT_CLAIM'
    && result.currentStage !== 'READY'
    && result.currentStage !== 'CLAIMED';

  const payWithPayPal = async () => {
    const cleanCode = normalizeLookupCode(orderCode);
    const cleanMobile = normalizeLookupMobile(mobile);
    if (!cleanCode || !cleanMobile) return;
    setPayBusy(true);
    setPayError('');
    try {
      if (!payRequestIdRef.current) payRequestIdRef.current = createClientRequestId();
      writePublicLookupCredentials(sessionStorageOrNull(), { orderCode: String(orderCode).trim(), mobile: String(mobile).trim() });
      const { data } = await api.post('/api/public/orders/paypal-order', {
        orderCode: cleanCode,
        mobile: cleanMobile,
        clientRequestId: payRequestIdRef.current,
      });
      savePendingPayPalOrder(sessionStorageOrNull(), {
        flow: 'public',
        orderId: '',
        orderCode: cleanCode,
        paypalOrderId: data.paypalOrderId || '',
      });
      if (data.approvalUrl && typeof window !== 'undefined') {
        window.location.assign(data.approvalUrl);
        return;
      }
      setPayError('PayPal checkout link is unavailable. Please try again.');
    } catch (reason) {
      payRequestIdRef.current = '';
      setPayError(apiErrorMessage(reason, 'PayPal checkout could not be started.'));
    } finally {
      setPayBusy(false);
    }
  };

  return <div className="mx-auto flex w-full max-w-3xl flex-1 flex-col bg-slate-50 px-5 py-10 text-slate-900 sm:px-8 sm:py-14">
    <div className="w-full flex-1">
      <div className="mb-7"><p className="text-xs font-bold uppercase tracking-[.16em] text-teal-700">Walk-in tracking</p><h1 className="mt-2 text-3xl font-semibold tracking-tight sm:text-4xl">Track your laundry</h1><p className="mt-3 max-w-xl text-sm leading-6 text-slate-600">Enter the order code and the mobile number provided to the shop. For your privacy, the result shows only a masked number and order progress.</p></div>
      <section className="rounded-3xl border border-slate-100 bg-white p-5 shadow-sm sm:p-7"><form onSubmit={submit} className="grid gap-4 sm:grid-cols-2">
        <label className="text-sm font-medium text-slate-700">Order code<input required maxLength={32} autoComplete="off" value={orderCode} onChange={(event) => setOrderCode(event.target.value)} placeholder="e.g. O-000123" className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-300 px-3.5 outline-none focus:border-teal-600 focus:ring-4 focus:ring-teal-50" /></label>
        <label className="text-sm font-medium text-slate-700">Mobile number<input required type="tel" maxLength={32} autoComplete="tel" value={mobile} onChange={(event) => setMobile(event.target.value)} placeholder="Number given to the shop" className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-300 px-3.5 outline-none focus:border-teal-600 focus:ring-4 focus:ring-teal-50" /></label>
        <button disabled={busy} className="inline-flex min-h-12 items-center justify-center gap-2 rounded-xl bg-teal-600 px-5 font-semibold text-white hover:bg-teal-700 disabled:opacity-60 sm:col-span-2">{busy ? <><LoaderCircle size={18} className="animate-spin" />Looking up…</> : <><Search size={17} />Find my order</>}</button>
      </form>{error && <p role="alert" className="mt-4 rounded-xl border border-red-200 bg-red-50 p-3 text-sm leading-5 text-red-800">{error}</p>}</section>
      {result && <section aria-live="polite" className="mt-6 rounded-3xl border border-slate-100 bg-white p-5 shadow-sm sm:p-7">
        <div className="flex flex-wrap items-start justify-between gap-3"><div><p className="text-xs font-bold uppercase tracking-[.16em] text-slate-400">Order code</p><h2 className="mt-1 text-2xl font-semibold">{result.orderCode}</h2><p className="mt-1 text-sm text-slate-500">Mobile ending {String(result.maskedMobile || '').slice(-4)}</p></div><span className="rounded-full bg-teal-50 px-3 py-1.5 text-sm font-semibold text-teal-800">{stageDisplayLabel(result.currentStage)}</span></div>
        <div className="mt-5 rounded-xl bg-slate-50 p-3"><p className="text-xs font-semibold uppercase tracking-wide text-slate-400">Quantity</p><div className="mt-1 text-sm font-semibold"><OrderQuantityBadge order={result} showActualWeight={false} /></div></div>
        <div className="mt-6"><h3 className="mb-3 text-sm font-semibold">Order status</h3><OrderStatusTracker stageHistory={result.stageHistory || []} currentStage={result.currentStage} showStaffName={false} /></div>
        <div className="mt-6 border-t border-slate-100 pt-5"><div className="flex flex-wrap items-center justify-between gap-2"><h3 className="text-sm font-semibold">Payment status</h3><span className={`rounded-full px-3 py-1.5 text-xs font-semibold ${result.paymentStatus === 'PAID' ? 'bg-emerald-50 text-emerald-800' : result.paymentStatus === 'PENDING' ? 'bg-amber-50 text-amber-800' : 'bg-slate-100 text-slate-700'}`}>{result.paymentStatus === 'PAID' ? 'Paid' : result.paymentStatus === 'PENDING' ? 'Pending online' : 'Unpaid'}</span></div>{payError && <p role="alert" className="mt-3 rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-800">{payError}</p>}{payEligible && <div className="mt-4"><button type="button" disabled={payBusy} onClick={() => void payWithPayPal()} className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-teal-600 px-5 text-sm font-semibold text-white hover:bg-teal-700 disabled:opacity-50">{payBusy ? <LoaderCircle size={16} className="animate-spin" /> : <ExternalLink size={16} />}{payBusy ? 'Opening PayPal…' : 'Pay with PayPal'}</button></div>}{payAtClaimWaiting && <p className="mt-3 text-xs text-slate-500">Online payment (PayPal) will be available here when your order is Ready for pickup.</p>}</div>
        <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 pt-4"><p className="text-xs text-slate-400">Auto-refreshes every 30 seconds while this tab is visible.</p><div className="flex flex-wrap gap-2"><button type="button" disabled={busy} onClick={() => void lookup(orderCode, mobile)} className="inline-flex min-h-10 items-center gap-2 rounded-xl border border-slate-200 px-3 text-sm font-semibold text-slate-700 disabled:opacity-50"><RefreshCw size={15} className={busy ? 'animate-spin' : ''} />Refresh status</button><button type="button" disabled={busy} onClick={trackAnother} className="inline-flex min-h-10 items-center rounded-xl border border-slate-200 px-3 text-sm font-semibold text-slate-700 disabled:opacity-50">Track another order</button></div></div>
      </section>}
      <p className="mt-6 text-xs leading-5 text-slate-400">For privacy, the lookup does not display your name, full mobile number, shop details, notes, or order price. Lookup details stay in this tab session only. Need help? Contact the laundry shop.</p>
      <Link to="/" className="mt-6 inline-flex items-center gap-1 text-sm font-semibold text-teal-700 hover:text-teal-900">Back to SpindleTrack <ArrowRight size={15} /></Link>
    </div>
  </div>;
}
