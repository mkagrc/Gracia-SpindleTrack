import { useEffect } from 'react';
import { AlertCircle, CheckCircle2, X } from 'lucide-react';

export default function Toast({ type = 'success', message, onDismiss, duration = 5000 }) {
  useEffect(() => {
    if (!message || !onDismiss || duration <= 0) return undefined;
    const timer = window.setTimeout(onDismiss, duration);
    return () => window.clearTimeout(timer);
  }, [message, onDismiss, duration]);

  if (!message) return null;
  const isError = type === 'error';
  const Icon = isError ? AlertCircle : CheckCircle2;
  return <div
    className={`fixed right-4 top-4 z-[70] flex w-[calc(100%-2rem)] max-w-md items-start gap-3 rounded-2xl border bg-white p-4 shadow-xl sm:right-6 sm:top-6 ${isError ? 'border-red-200 text-red-900' : 'border-emerald-200 text-emerald-900'}`}
    role={isError ? 'alert' : 'status'}
    aria-live={isError ? 'assertive' : 'polite'}
  >
    <Icon size={19} className={`mt-0.5 shrink-0 ${isError ? 'text-red-600' : 'text-emerald-600'}`} aria-hidden="true" />
    <p className="min-w-0 flex-1 text-sm leading-5">{message}</p>
    <button type="button" onClick={onDismiss} aria-label="Dismiss notification" className="grid h-7 w-7 shrink-0 place-items-center rounded-lg text-slate-500 hover:bg-slate-100 focus:outline-none focus:ring-4 focus:ring-teal-100"><X size={16} /></button>
  </div>;
}
