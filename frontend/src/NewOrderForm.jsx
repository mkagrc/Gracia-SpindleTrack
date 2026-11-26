import { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ArrowLeft, ArrowRight, BadgeCheck, Check, Copy, ExternalLink, LoaderCircle, Plus, Search, Trash2, X } from 'lucide-react';
import { QRCodeSVG } from 'qrcode.react';
import api, { apiErrorMessage } from './api.js';
import { formatPeso } from './formatters.js';
import { billingUnit, normalizeBillingType, previewOrderTotal, quantityInputRules } from './orderPricing.js';

export function createClientRequestId(cryptoSource = globalThis.crypto) {
  if (cryptoSource?.randomUUID) return cryptoSource.randomUUID();
  if (!cryptoSource?.getRandomValues) throw new Error('Secure UUID generation is unavailable.');
  const bytes = cryptoSource.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40; bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

const fieldClass = 'mt-1.5 min-h-11 w-full rounded-xl border border-slate-300 bg-white px-3.5 text-sm outline-none focus:border-brand focus:ring-4 focus:ring-teal-50';
const sectionClass = 'rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-6';
const validDecimal = (value, max) => /^\d+(\.\d{1,2})?$/.test(value) && Number(value) > 0 && Number(value) <= max;

export default function NewOrderForm() {
  const navigate = useNavigate();
  const [services, setServices] = useState([]); const [addOns, setAddOns] = useState([]);
  const [loading, setLoading] = useState(true); const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  const [customerName, setCustomerName] = useState(''); const [mobile, setMobile] = useState('');
  const [serviceId, setServiceId] = useState(''); const [quantity, setQuantity] = useState(''); const [actualWeightKg, setActualWeightKg] = useState('');
  const [rows, setRows] = useState([]); const [notes, setNotes] = useState('');
  const [paymentMethodPreference, setPaymentMethodPreference] = useState('CASH'); const [paymentTiming, setPaymentTiming] = useState('PAY_AT_CLAIM');
  const [lookupEmail, setLookupEmail] = useState(''); const [attachedEmail, setAttachedEmail] = useState(''); const [candidate, setCandidate] = useState(null); const [lookupBusy, setLookupBusy] = useState(false); const [lookupError, setLookupError] = useState('');
  const [createdOrderResult, setCreatedOrderResult] = useState(null);
  const [copiedLink, setCopiedLink] = useState(false);
  const requestIdentity = useRef({ fingerprint: '', id: '' });
  useEffect(() => {
    let active = true;
    Promise.all([api.get('/api/shops/services'), api.get('/api/shops/add-ons')])
      .then(([serviceResult, addOnResult]) => { if (active) { setServices(Array.isArray(serviceResult.data) ? serviceResult.data.filter((item) => item.active) : []); setAddOns(Array.isArray(addOnResult.data) ? addOnResult.data.filter((item) => item.active) : []); } })
      .catch((reason) => { if (active) setError(apiErrorMessage(reason, 'Catalogs could not be loaded.')); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);
  const service = useMemo(() => services.find((item) => item.id === serviceId), [services, serviceId]);
  const rules = quantityInputRules(service || {}); const type = normalizeBillingType(service || {});
  const cleanRows = rows.filter((row) => row.addOnId && row.quantity !== '').map((row) => ({ addOnId: row.addOnId, quantity: Number(row.quantity) }));
  const estimate = useMemo(() => previewOrderTotal(service, rules.fixedQuantity ?? quantity, cleanRows, addOns), [service, rules.fixedQuantity, quantity, cleanRows, addOns]);
  const capacityExceeded = type === 'PER_LOAD' && Number(actualWeightKg) > 0 && Number(service?.capacityKg) > 0 && Number(actualWeightKg) > Number(quantity || 0) * Number(service.capacityKg);
  const quantityValid = !service ? false : rules.fixedQuantity != null || (type === 'PER_KG' ? validDecimal(quantity, 999.99) : /^\d+$/.test(quantity) && Number(quantity) >= 1 && Number(quantity) <= 999);
  const actualWeightValid = !actualWeightKg || validDecimal(actualWeightKg, 999.99);
  const addOnsValid = rows.every((row) => !row.addOnId || (/^\d+$/.test(row.quantity) && Number(row.quantity) >= 1 && Number(row.quantity) <= 999));
  const findCustomer = async () => { setLookupBusy(true); setLookupError(''); setCandidate(null); try { const { data } = await api.get('/api/shops/orders/customer-match', { params: { email: lookupEmail.trim() } }); setCandidate(data); } catch (reason) { setLookupError(apiErrorMessage(reason, 'No verified customer account matches that email.')); } finally { setLookupBusy(false); } };
  const attachCustomer = () => { if (!candidate || !lookupEmail.trim()) return; setCustomerName(candidate.name || ''); setAttachedEmail(lookupEmail.trim()); setCandidate(null); setLookupError(''); };
  const dismissCandidate = () => { setCandidate(null); setLookupError(''); setLookupEmail(''); };
  const submit = async (event) => {
    event.preventDefault();
    if (candidate && !attachedEmail) { setLookupError('Click "Attach to order" to link the verified customer, or dismiss to create a walk-in order.'); return; }
    setBusy(true); setError('');
    const payload = { customerName: customerName.trim(), mobile, serviceId, quantity: rules.fixedQuantity ?? Number(quantity), ...(type === 'PER_LOAD' && actualWeightKg ? { actualWeightKg: Number(actualWeightKg) } : {}), addOns: cleanRows, notes: notes.trim(), paymentMethodPreference, paymentTiming, ...(attachedEmail ? { customerEmail: attachedEmail } : {}) };
    const fingerprint = JSON.stringify(payload);
    if (!requestIdentity.current.id || requestIdentity.current.fingerprint !== fingerprint) requestIdentity.current = { fingerprint, id: createClientRequestId() };
    try {
      const { data } = await api.post('/api/shops/orders', { ...payload, clientRequestId: requestIdentity.current.id });
      if (paymentMethodPreference === 'ONLINE' && paymentTiming === 'PAY_NOW') {
        let payPalData = null;
        let payPalError = '';
        try {
          const response = await api.post(`/api/shops/orders/${encodeURIComponent(data.id)}/payments/paypal-order`, {
            clientRequestId: createClientRequestId(),
          });
          payPalData = response.data;
        } catch (payReason) {
          payPalError = apiErrorMessage(payReason, 'Could not generate PayPal link yet. Open the order details to share the link.');
        }
        setCreatedOrderResult({
          order: payPalData?.order || data,
          approvalUrl: payPalData?.approvalUrl || payPalData?.paymentLinkUrl || '',
          payPalError,
        });
        return;
      }
      navigate(`/staff/orders/${encodeURIComponent(data.id)}`, { state: { toast: `Order ${data.orderCode} was created.` } });
    }
    catch (reason) { setError(apiErrorMessage(reason, 'The order could not be created.')); }
    finally { setBusy(false); }
  };
  const resetForAnotherOrder = () => {
    setCreatedOrderResult(null); setCopiedLink(false); setError('');
    setCustomerName(''); setMobile(''); setServiceId(''); setQuantity(''); setActualWeightKg('');
    setRows([]); setNotes(''); setLookupEmail(''); setAttachedEmail(''); setCandidate(null); setLookupError('');
    requestIdentity.current = { fingerprint: '', id: '' };
  };
  const copyApprovalUrl = async (url) => {
    if (!url || typeof navigator === 'undefined' || !navigator.clipboard?.writeText) return;
    try {
      await navigator.clipboard.writeText(url);
      setCopiedLink(true);
      setTimeout(() => setCopiedLink(false), 2500);
    } catch { /* clipboard optional */ }
  };
  if (loading) return <div role="status" className="flex min-h-48 items-center justify-center gap-3 text-sm text-slate-500"><LoaderCircle className="animate-spin text-brand" size={20} />Loading service and add-on catalogs…</div>;
  if (!services.length) return <div className="rounded-2xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">There are no active services available. Ask the owner to activate a service before creating an order.</div>;
  if (createdOrderResult) {
    const created = createdOrderResult.order;
    const approvalUrl = createdOrderResult.approvalUrl;
    return <section className="mx-auto max-w-2xl rounded-3xl border border-teal-100 bg-white p-6 shadow-soft sm:p-8">
      <div className="flex items-center gap-3 text-emerald-700"><BadgeCheck size={26} /><div><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Order created</p><h2 className="text-2xl font-semibold text-ink">{created.orderCode}</h2></div></div>
      <div className="mt-4 grid gap-3 rounded-2xl bg-slate-50 p-4 sm:grid-cols-3">
        <div><p className="text-xs font-semibold uppercase text-slate-400">Customer</p><p className="mt-1 text-sm font-semibold text-ink">{created.customerName}</p></div>
        <div><p className="text-xs font-semibold uppercase text-slate-400">Payment</p><p className="mt-1 text-sm font-semibold text-ink">Online payment (PayPal)</p></div>
        <div><p className="text-xs font-semibold uppercase text-slate-400">Total</p><p className="mt-1 text-sm font-semibold text-ink">{formatPeso(created.totalPrice)}</p></div>
      </div>
      {approvalUrl ? <div className="mt-5 rounded-2xl border border-sky-200 bg-sky-50/70 p-5">
        <h3 className="text-base font-semibold text-ink">Share PayPal payment link with customer</h3>
        <p className="mt-1 text-sm text-slate-600">Ask the customer to scan this QR code or open the link on their own device to pay with PayPal. Once they approve, you can confirm payment from the order page.</p>
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <a href={approvalUrl} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-1.5 text-sm font-semibold text-brand underline">Customer PayPal link <ExternalLink size={14} /></a>
          <button type="button" onClick={() => void copyApprovalUrl(approvalUrl)} className="inline-flex min-h-9 items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 text-xs font-semibold text-slate-700 hover:bg-slate-50">
            {copiedLink ? <Check size={14} className="text-emerald-600" /> : <Copy size={14} />}{copiedLink ? 'Copied link' : 'Copy link'}
          </button>
        </div>
        <div className="mt-4 inline-flex rounded-xl border border-slate-200 bg-white p-3"><QRCodeSVG value={approvalUrl} size={160} level="M" /></div>
      </div> : <div role="alert" className="mt-5 rounded-2xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
        {createdOrderResult.payPalError || 'PayPal link is not ready yet. Open the order details to share the PayPal payment link.'}
      </div>}
      <div className="mt-6 flex flex-wrap gap-3">
        <Link to={`/staff/orders/${encodeURIComponent(created.id)}`} className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand px-5 text-sm font-semibold text-white hover:bg-teal-700">View order details <ArrowRight size={16} /></Link>
        <button type="button" onClick={resetForAnotherOrder} className="inline-flex min-h-11 items-center rounded-xl border border-slate-200 px-4 text-sm font-semibold text-slate-700 hover:bg-slate-50">Create another order</button>
      </div>
    </section>;
  }
  return <form onSubmit={submit} className="grid items-start gap-5 xl:grid-cols-[minmax(0,1fr)_320px]">
    <div className="space-y-5">
      {error && <div role="alert" className="rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">{error}</div>}
      <section className={sectionClass}><div className="mb-4"><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Step 1</p><h2 className="mt-1 text-lg font-semibold">Customer information</h2></div>
        <div className="rounded-2xl border border-slate-200 bg-slate-50 p-4"><p className="text-sm font-semibold">Link a verified customer account (optional)</p><p className="mt-1 text-xs leading-5 text-slate-500">Search by exact email; account details are only attached after you confirm the match.</p>{attachedEmail ? <div className="mt-3 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-emerald-200 bg-emerald-50 p-3"><span className="inline-flex items-center gap-2 text-sm font-medium text-emerald-900"><BadgeCheck size={17} />Linked customer: {customerName}</span><button type="button" onClick={() => { setAttachedEmail(''); setCustomerName(''); setLookupEmail(''); }} className="inline-flex min-h-9 items-center gap-1 rounded-lg border border-emerald-300 bg-white px-3 text-xs font-semibold"><X size={14} />Walk-in order</button></div> : <div className="mt-3 flex flex-col gap-2 sm:flex-row"><input type="email" value={lookupEmail} onChange={(event) => { setLookupEmail(event.target.value); setCandidate(null); }} placeholder="customer@example.com" aria-label="Customer exact email" className={`${fieldClass} mt-0 min-w-0 flex-1`} /><button type="button" disabled={lookupBusy || !lookupEmail.trim()} onClick={() => void findCustomer()} className="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl border border-slate-300 bg-white px-4 text-sm font-semibold disabled:opacity-50">{lookupBusy ? <LoaderCircle size={16} className="animate-spin" /> : <Search size={16} />}{lookupBusy ? 'Searching…' : 'Find exact email'}</button></div>}{lookupError && <p role="alert" className="mt-2 text-sm text-red-700">{lookupError}</p>}{candidate && <div className="mt-3 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-teal-200 bg-teal-50 p-3"><span className="text-sm font-medium text-teal-900">Verified customer found: {candidate.name}</span><div className="flex flex-wrap gap-2"><button type="button" onClick={attachCustomer} className="min-h-9 rounded-lg bg-brand px-3 text-xs font-semibold text-white">Attach to order</button><button type="button" onClick={dismissCandidate} className="min-h-9 rounded-lg border border-teal-200 bg-white px-3 text-xs font-semibold text-teal-900">Walk-in instead</button></div></div>}{!attachedEmail && !candidate && lookupEmail.trim() && <p className="mt-2 text-xs text-slate-500">Click &ldquo;Find exact email&rdquo; and then &ldquo;Attach to order&rdquo; to link this customer account.</p>}</div>
        <div className="mt-4 grid gap-4 sm:grid-cols-2"><label className="block text-sm font-medium text-slate-700">Customer name<input required maxLength={120} value={customerName} readOnly={Boolean(attachedEmail)} onChange={(event) => setCustomerName(event.target.value)} className={`${fieldClass} read-only:bg-slate-50`} /></label><label className="block text-sm font-medium text-slate-700">Mobile number<input required type="tel" maxLength={32} value={mobile} onChange={(event) => setMobile(event.target.value)} placeholder="e.g. +63 917 123 4567" className={fieldClass} /></label></div>
      </section>
      <section className={sectionClass}><div className="mb-4"><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Step 2</p><h2 className="mt-1 text-lg font-semibold">Service &amp; quantity</h2></div>
        <label className="block text-sm font-medium text-slate-700">Service<select required value={serviceId} onChange={(event) => { setServiceId(event.target.value); setQuantity(''); setActualWeightKg(''); }} className={fieldClass}><option value="">Select an active service</option>{services.map((item) => <option key={item.id} value={item.id}>{item.name} — {item.category || 'Other'} — {item.billingType || item.unit} — {formatPeso(item.pricePerUnit)} per {billingUnit(normalizeBillingType(item))}</option>)}</select></label>
        {service && <div className="mt-3 rounded-2xl border border-teal-100 bg-teal-50/70 p-4"><div className="flex flex-wrap items-center gap-2"><span className="rounded-full bg-white px-2.5 py-1 text-xs font-semibold text-teal-800">{service.category || 'Other'}</span><span className="rounded-full bg-white px-2.5 py-1 text-xs font-semibold text-slate-600">{type.replaceAll('_', ' ')}</span><span className="text-sm font-semibold text-teal-950">{formatPeso(service.pricePerUnit)} per {billingUnit(type)}</span></div>{service.capacityKg && <p className="mt-2 text-sm text-teal-950">Capacity: up to {service.capacityKg} kg per load</p>}{service.minimumKg && <p className="mt-1 text-sm text-teal-950">Minimum charge: {service.minimumKg} kg</p>}{service.notes && <p className="mt-2 text-sm leading-5 text-slate-600">{service.notes}</p>}</div>}
        <div className="mt-4 grid gap-4 sm:grid-cols-2">{rules.fixedQuantity != null ? <div className="rounded-xl border border-slate-200 bg-slate-50 p-3"><p className="text-sm font-medium text-slate-700">Flat rate quantity</p><p className="mt-1 text-sm text-slate-600">Fixed at 1</p></div> : <label className="block text-sm font-medium text-slate-700">{rules.label}<input required type="number" min={rules.min} max={rules.max} step={rules.step} value={quantity} onChange={(event) => setQuantity(event.target.value)} placeholder={type === 'PER_KG' ? 'e.g. 2.35' : 'Enter a whole number'} className={fieldClass} /></label>}
          {type === 'PER_LOAD' && <label className="block text-sm font-medium text-slate-700">Actual weight (kg) <span className="font-normal text-slate-400">(optional)</span><input type="number" min="0.01" max="999.99" step="0.01" value={actualWeightKg} onChange={(event) => setActualWeightKg(event.target.value)} placeholder="Recorded only; not billed" className={fieldClass} /></label>}
        </div>{capacityExceeded && <p role="status" className="mt-3 rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900">Actual weight is above the selected service capacity ({Number(quantity || 0) * Number(service.capacityKg)} kg). Check the load count or service selection.</p>}
      </section>
      <section className={sectionClass}><div className="mb-4 flex flex-wrap items-center justify-between gap-3"><div><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Step 3</p><h2 className="mt-1 text-lg font-semibold">Add-ons</h2></div><button type="button" onClick={() => setRows((current) => [...current, { addOnId: '', quantity: '1' }])} disabled={!addOns.length} className="inline-flex min-h-9 items-center gap-2 rounded-lg border border-slate-200 px-3 text-xs font-semibold disabled:opacity-50"><Plus size={15} />Add item</button></div>
        {!addOns.length ? <p className="rounded-xl bg-slate-50 p-3 text-sm text-slate-500">No active add-ons are available.</p> : !rows.length ? <p className="rounded-xl bg-slate-50 p-3 text-sm text-slate-500">Add optional detergent, conditioner, bleach, or other items.</p> : <div className="space-y-3">{rows.map((row, index) => { const item = addOns.find((entry) => entry.id === row.addOnId); return <div key={index} className="grid items-end gap-3 rounded-xl border border-slate-200 p-3 sm:grid-cols-[minmax(0,1fr)_120px_auto]"><label className="block text-sm font-medium text-slate-700">Add-on<select value={row.addOnId} onChange={(event) => setRows((current) => current.map((line, i) => i === index ? { ...line, addOnId: event.target.value } : line))} className={fieldClass}><option value="">Select item</option>{addOns.map((addOn) => <option key={addOn.id} value={addOn.id}>{addOn.itemName} — {formatPeso(addOn.addOnPrice)} / {addOn.unit}</option>)}</select>{item && <span className="mt-1 block text-xs text-slate-500">{item.category} · {formatPeso(item.addOnPrice)} per {item.unit}</span>}</label><label className="block text-sm font-medium text-slate-700">Quantity<input required={Boolean(row.addOnId)} type="number" min="1" max="999" step="1" value={row.quantity} onChange={(event) => setRows((current) => current.map((line, i) => i === index ? { ...line, quantity: event.target.value } : line))} className={fieldClass} /></label><button type="button" aria-label={`Remove add-on row ${index + 1}`} onClick={() => setRows((current) => current.filter((_, i) => i !== index))} className="mb-1 grid h-10 w-10 place-items-center rounded-lg border border-rose-200 text-rose-700"><Trash2 size={16} /></button></div>; })}</div>}
      </section>
      <section className={sectionClass}><div className="mb-4"><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Step 4</p><h2 className="mt-1 text-lg font-semibold">Payment &amp; notes</h2></div><div className="grid gap-4 sm:grid-cols-2"><label className="block text-sm font-medium text-slate-700">Payment method<select value={paymentMethodPreference} onChange={(event) => setPaymentMethodPreference(event.target.value)} className={fieldClass}><option value="CASH">Cash</option><option value="ONLINE">Online payment (PayPal)</option></select></label><label className="block text-sm font-medium text-slate-700">When will the customer pay?<select value={paymentTiming} onChange={(event) => setPaymentTiming(event.target.value)} className={fieldClass}><option value="PAY_NOW">Pay now</option><option value="PAY_AT_CLAIM">Pay when claiming the laundry</option></select></label></div><label className="mt-4 block text-sm font-medium text-slate-700">Notes <span className="font-normal text-slate-400">(optional)</span><textarea maxLength={1000} rows={3} value={notes} onChange={(event) => setNotes(event.target.value)} className="mt-1.5 w-full rounded-xl border border-slate-300 px-3.5 py-3 text-sm" /></label></section>
    </div>
    <aside className="h-fit rounded-3xl border border-teal-100 bg-white p-5 shadow-soft xl:sticky xl:top-24 sm:p-6"><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Estimated total</p><div className="mt-4 space-y-3 border-b border-slate-100 pb-4"><div className="flex justify-between gap-3 text-sm"><span className="text-slate-600">{service ? `${service.name} · ${type.replaceAll('_', ' ')}` : 'Select a service'}</span><span className="font-medium">{formatPeso(estimate.serviceSubtotal)}</span></div>{estimate.addOnLines.map((line, index) => <div key={`${line.addOnId}-${index}`} className="flex justify-between gap-3 text-sm"><span className="text-slate-600">{line.name || 'Add-on'} × {line.quantity || 0}</span><span className="font-medium">{formatPeso(line.lineTotal)}</span></div>)}</div><p className="mt-4 text-xs text-slate-500">Preview only. The server calculates the final total from the selected catalog prices.</p><p className="mt-3 text-3xl font-semibold tracking-tight text-ink">{formatPeso(estimate.total)}</p><button disabled={busy || Boolean(candidate && !attachedEmail) || !customerName.trim() || !mobile.trim() || !serviceId || !quantityValid || !actualWeightValid || capacityExceeded || !addOnsValid} className="mt-6 inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-brand px-4 font-semibold text-white disabled:opacity-50">{busy ? <><LoaderCircle size={17} className="animate-spin" />Creating…</> : <>Create order <ArrowRight size={17} /></>}</button></aside>
  </form>;
}

export function NewOrderHeading() {
  return <div className="mb-7 flex flex-wrap items-end justify-between gap-4"><div><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Staff workspace</p><h1 className="mt-2 text-2xl font-semibold tracking-tight text-ink sm:text-3xl">New order</h1><p className="mt-2 max-w-2xl text-sm leading-6 text-slate-500">Choose an active service and quantity; the server computes the amount from current catalog prices.</p></div><Link to="/staff/orders" className="inline-flex min-h-10 items-center gap-2 rounded-xl border px-3 text-sm font-semibold"><ArrowLeft size={16} />Orders</Link></div>;
}
