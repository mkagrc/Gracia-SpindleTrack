export const normalizeLookupCode = (value) => String(value || '').trim().toUpperCase();
export const normalizeLookupMobile = (value) => String(value || '').replace(/\D/g, '');
export const formatLookupTime = (value) => {
  if (!value) return 'Time unavailable';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? 'Time unavailable' : parsed.toLocaleString();
};
