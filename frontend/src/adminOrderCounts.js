export function formatAdminOrderCount(value) {
  const count = Number(value ?? 0);
  return Number.isFinite(count) && count >= 0 ? count.toLocaleString() : '0';
}
