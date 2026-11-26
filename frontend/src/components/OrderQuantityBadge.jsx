import { billingTypeBadgeLabel, formatActualWeight, formatOrderQuantity } from '../orderPricing.js';

export default function OrderQuantityBadge({ order, showActualWeight = true, className = '' }) {
  const qtyText = formatOrderQuantity(order);
  const badge = qtyText === 'N/A' ? null : billingTypeBadgeLabel(order);
  const actualWeight = showActualWeight ? formatActualWeight(order) : '';
  const isFlat = badge === 'Flat rate' && qtyText === 'Flat rate';

  return (
    <span className={`inline-flex flex-wrap items-center gap-1.5 ${className}`}>
      {badge && (
        <span className="inline-flex items-center rounded-md bg-teal-50 px-2 py-0.5 text-[11px] font-semibold text-teal-800 ring-1 ring-inset ring-teal-200">
          {badge}
        </span>
      )}
      {!isFlat && <span>{qtyText}</span>}
      {actualWeight && <span className="text-xs font-normal text-slate-500">({actualWeight})</span>}
    </span>
  );
}
