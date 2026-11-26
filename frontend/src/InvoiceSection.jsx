import { Printer } from 'lucide-react';
import { formatDateTime, formatOrderPaymentMethod, formatPaymentStatus, formatPaymentTiming, formatPeso } from './formatters.js';
import { billingTypeBadgeLabel, formatActualWeight, formatOrderQuantity, normalizeBillingType } from './orderPricing.js';

function formatLineQuantity(type, rawQuantity, rawUnit) {
  if (type === 'FLAT') return 'Flat rate';
  if (rawQuantity == null || rawQuantity === '') return 'N/A';
  const value = Number(rawQuantity);
  if (!Number.isFinite(value) || value <= 0) return 'N/A';
  const unit = rawUnit || 'kg';
  if (type === 'PER_LOAD') return `${Number.isInteger(value) ? value : value.toFixed(2)} ${value === 1 ? 'load' : 'loads'}`;
  if (type === 'PER_PIECE') return `${Number.isInteger(value) ? value : value.toFixed(2)} ${value === 1 ? 'piece' : 'pieces'}`;
  return `${value.toFixed(2)} ${unit}`;
}

export default function InvoiceSection({ invoice, order, customerView = false }) {
  if (!invoice) return null;
  const service = invoice.service || {};
  const type = normalizeBillingType(service);
  const qtyOrder = {
    ...order,
    quantity: invoice.quantity ?? order?.quantity ?? order?.kilos,
    quantityUnit: invoice.quantityUnit || order?.quantityUnit || order?.unit,
    unit: invoice.quantityUnit || order?.unit,
    service,
    billingType: service.billingType || order?.billingType || type,
    actualWeightKg: invoice.actualWeightKg ?? order?.actualWeightKg,
  };
  const badge = billingTypeBadgeLabel(qtyOrder);
  const lineQty = formatLineQuantity(type, qtyOrder.quantity, qtyOrder.quantityUnit);
  const actualWeight = formatActualWeight(qtyOrder);
  const status = invoice.paymentStatus || order?.paymentStatus || 'UNPAID';
  const method = invoice.paymentMethod || order?.paymentMethod;
  const timing = invoice.paymentTiming || order?.paymentTiming;
  const paidAt = invoice.paidAt || order?.paidAt;

  return (
    <section className="invoice-print-root rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-7" aria-label="Invoice">
      <div className="invoice-print-hide mb-5 flex flex-wrap items-center justify-between gap-3">
        <div>
          <p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Receipt</p>
          <h2 className="mt-1 text-lg font-semibold text-ink">Invoice</h2>
        </div>
        <button
          type="button"
          onClick={() => window.print()}
          className="inline-flex min-h-10 items-center gap-2 rounded-xl border border-slate-200 px-4 text-sm font-semibold text-slate-700 hover:bg-slate-50"
        >
          <Printer size={16} />
          Print / Save as PDF
        </button>
      </div>

      <div className="border-b-2 border-slate-900 pb-5">
        <p className="text-xs font-bold uppercase tracking-[.16em] text-slate-500">{invoice.shopName || 'Laundry shop'}</p>
        <h3 className="mt-1 text-2xl font-semibold text-slate-950">{invoice.orderCode || order?.orderCode || 'Invoice'}</h3>
        {invoice.shopContactNumber && <p className="mt-1 text-sm text-slate-600">{invoice.shopContactNumber}</p>}
        <p className="mt-1 text-sm text-slate-600">Created {formatDateTime(invoice.createdAt)}</p>
      </div>

      <div className="border-b border-slate-200 py-4">
        <p className="text-[11px] font-bold uppercase tracking-wide text-slate-500">Billed to</p>
        <p className="mt-1 font-semibold text-slate-900">{invoice.customerName || order?.customerName || 'Customer'}</p>
      </div>

      <div className="overflow-x-auto">
        <table className="mt-3 w-full min-w-[440px] text-sm">
          <thead>
            <tr className="border-b border-slate-200 text-left text-[11px] font-bold uppercase tracking-wide text-slate-500">
              <th className="py-2 pr-3">Description</th>
              <th className="py-2 px-2 text-right">Qty</th>
              <th className="py-2 px-2 text-right">Unit price</th>
              <th className="py-2 pl-2 text-right">Amount</th>
            </tr>
          </thead>
          <tbody>
            <tr className="border-b border-slate-100">
              <td className="py-3 pr-3">
                <p className="font-semibold text-slate-900">{service.name || order?.serviceName || 'Laundry service'}</p>
                <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-slate-500">
                  <span>{service.category || 'Other'}</span>
                  {badge && (
                    <span className="inline-flex items-center rounded-md bg-teal-50 px-2 py-0.5 text-[11px] font-semibold text-teal-800 ring-1 ring-inset ring-teal-200">
                      {badge}
                    </span>
                  )}
                </div>
                {actualWeight && <p className="mt-1 text-xs text-slate-500">{actualWeight} (not billed)</p>}
              </td>
              <td className="px-2 py-3 text-right">{lineQty}</td>
              <td className="px-2 py-3 text-right">{service.unitPrice == null ? '\u2014' : formatPeso(service.unitPrice)}</td>
              <td className="py-3 pl-2 text-right font-medium">{formatPeso(invoice.serviceSubtotal)}</td>
            </tr>
            {(invoice.addOns || []).map((item, index) => (
              <tr key={`${item.id || item.name}-${index}`} className="border-b border-slate-100">
                <td className="py-3 pr-3">
                  <p className="font-semibold text-slate-900">{item.name || 'Add-on'}</p>
                  <p className="mt-0.5 text-xs text-slate-500">Add-on {'\u00b7'} per {item.unit || 'unit'}</p>
                </td>
                <td className="px-2 py-3 text-right">{item.quantity} {item.unit || ''}</td>
                <td className="px-2 py-3 text-right">{formatPeso(item.unitPrice)}</td>
                <td className="py-3 pl-2 text-right font-medium">{formatPeso(item.lineTotal)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="ml-auto mt-4 max-w-xs space-y-2 border-t border-slate-200 pt-3 text-sm">
        <div className="flex justify-between gap-4">
          <span className="text-slate-600">Service</span>
          <span>{formatPeso(invoice.serviceSubtotal)}</span>
        </div>
        <div className="flex justify-between gap-4">
          <span className="text-slate-600">Add-ons</span>
          <span>{formatPeso(invoice.addOnsSubtotal)}</span>
        </div>
        <div className="flex justify-between gap-4 border-t border-slate-200 pt-2 text-lg font-bold">
          <span>Total</span>
          <span>{formatPeso(invoice.total)}</span>
        </div>
      </div>

      <div className="mt-5 grid gap-3 border-t border-slate-200 pt-4 text-sm sm:grid-cols-2">
        <p><span className="font-semibold">Payment method:</span> {formatOrderPaymentMethod(order, method)}</p>
        <p><span className="font-semibold">Payment timing:</span> {formatPaymentTiming(timing)}</p>
        <p><span className="font-semibold">Payment status:</span> {formatPaymentStatus(status)}</p>
        {status === 'PAID' && paidAt && (
          <p><span className="font-semibold">Paid:</span> {formatDateTime(paidAt)}</p>
        )}
      </div>

      {!customerView && order?.mobile && (
        <p className="mt-4 border-t border-dashed border-slate-200 pt-3 text-xs text-slate-500">
          Customer contact: {order.mobile}
        </p>
      )}
    </section>
  );
}
