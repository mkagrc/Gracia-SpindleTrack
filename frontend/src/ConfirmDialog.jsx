import { useEffect, useId, useRef, useState } from 'react';
import { LoaderCircle } from 'lucide-react';

export function requestDialogClose(loading, onCancel) {
  if (loading) return false;
  onCancel?.();
  return true;
}

export function handleDialogBackdropClick(event, loading, onCancel) {
  if (event.target !== event.currentTarget) return false;
  return requestDialogClose(loading, onCancel);
}

export function handleDialogKeyDown(event, { loading, onCancel, dialogElement, activeElement }) {
  if (event.key === 'Escape') {
    event.preventDefault();
    return requestDialogClose(loading, onCancel);
  }
  if (event.key !== 'Tab' || !dialogElement) return false;

  const focusable = Array.from(dialogElement.querySelectorAll(
    'button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])',
  ));
  if (focusable.length === 0) {
    event.preventDefault();
    dialogElement.focus();
    return true;
  }

  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  const current = activeElement || document.activeElement;
  if (event.shiftKey && (current === first || !dialogElement.contains(current))) {
    event.preventDefault();
    last.focus();
    return true;
  }
  if (!event.shiftKey && (current === last || !dialogElement.contains(current))) {
    event.preventDefault();
    first.focus();
    return true;
  }
  return false;
}

export async function executeConfirmDialogAction(onConfirm, formatError) {
  try {
    await onConfirm?.();
    return { ok: true, errorMessage: '' };
  } catch (error) {
    let errorMessage = '';
    try {
      errorMessage = formatError?.(error) || error?.response?.data?.message || error?.message || '';
    } catch {
      errorMessage = '';
    }
    return {
      ok: false,
      errorMessage: errorMessage || 'The action could not be completed. Please try again.',
    };
  }
}

export function ConfirmDialog({
  title,
  message,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  tone = 'default',
  loading = false,
  onConfirm,
  onCancel,
  formatError,
  error = '',
  reasonRequired = false,
  reasonValue = '',
  onReasonChange,
}) {
  const dialogRef = useRef(null);
  const cancelRef = useRef(null);
  const confirmRef = useRef(null);
  const callbacksRef = useRef({ loading, onCancel });
  const [actionError, setActionError] = useState('');
  const titleId = useId();
  const messageId = useId();
  const errorId = useId();
  const reasonId = useId();
  callbacksRef.current = { loading, onCancel };

  useEffect(() => {
    const previouslyFocused = document.activeElement;
    const initialFocus = tone === 'danger' ? cancelRef.current : confirmRef.current;
    initialFocus?.focus();
    const handleKeyDown = (event) => handleDialogKeyDown(event, {
      ...callbacksRef.current,
      dialogElement: dialogRef.current,
    });
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      previouslyFocused?.focus?.();
    };
  }, [tone]);

  const visibleError = actionError || error;
  const reasonInvalid = reasonRequired && (!reasonValue.trim() || reasonValue.length > 500);
  const describedBy = [message ? messageId : null, visibleError ? errorId : null]
    .filter(Boolean)
    .join(' ') || undefined;
  const close = () => requestDialogClose(loading, onCancel);
  const confirm = async () => {
    if (loading || reasonInvalid) return;
    setActionError('');
    const result = await executeConfirmDialogAction(onConfirm, formatError);
    if (!result.ok) setActionError(result.errorMessage);
  };

  return <div
    className="fixed inset-0 z-[60] grid place-items-center overflow-y-auto bg-slate-950/55 p-4 sm:p-6"
    onClick={(event) => handleDialogBackdropClick(event, loading, onCancel)}
  >
    <section
      ref={dialogRef}
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
      aria-describedby={describedBy}
      tabIndex={-1}
      className="my-auto w-full max-w-lg rounded-3xl border border-slate-100 bg-white p-5 text-ink shadow-2xl outline-none sm:p-7"
    >
      <h2 id={titleId} className="text-xl font-semibold tracking-tight text-ink sm:text-2xl">{title}</h2>
      {message && <p id={messageId} className="mt-3 whitespace-pre-line text-sm leading-6 text-slate-600">{message}</p>}
      {reasonRequired && <div className="mt-5">
        <label htmlFor={reasonId} className="block text-sm font-semibold text-ink">Rejection reason</label>
        <textarea
          id={reasonId}
          required
          maxLength={500}
          rows={4}
          value={reasonValue}
          onChange={onReasonChange}
          placeholder="Explain what needs to be corrected…"
          className="mt-1.5 w-full rounded-xl border border-slate-300 bg-white px-3.5 py-3 text-sm outline-none transition placeholder:text-slate-400 focus:border-brand focus:ring-4 focus:ring-teal-50"
        />
        <p className="mt-1 text-right text-xs text-slate-500">{reasonValue.length}/500</p>
      </div>}
      {visibleError && <p id={errorId} role="alert" aria-live="assertive" className="mt-4 rounded-xl border border-red-200 bg-red-50 p-3 text-sm leading-5 text-red-800">{visibleError}</p>}
      <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <button
          ref={cancelRef}
          type="button"
          disabled={loading}
          data-autofocus={tone === 'danger' ? 'cancel' : undefined}
          onClick={close}
          className="min-h-11 rounded-xl border border-slate-200 px-4 text-sm font-semibold text-slate-700 transition hover:bg-slate-50 focus:outline-none focus:ring-4 focus:ring-teal-100 disabled:cursor-not-allowed disabled:opacity-50"
        >{cancelLabel}</button>
        <button
          ref={confirmRef}
          type="button"
          disabled={loading || reasonInvalid}
          onClick={confirm}
          className={`inline-flex min-h-11 items-center justify-center gap-2 rounded-xl px-4 text-sm font-semibold text-white transition focus:outline-none focus:ring-4 disabled:cursor-not-allowed disabled:opacity-50 ${tone === 'danger' ? 'bg-rose-600 hover:bg-rose-700 focus:ring-rose-100' : 'bg-brand hover:bg-teal-700 focus:ring-teal-100'}`}
        >{loading && <LoaderCircle size={16} className="animate-spin" aria-hidden="true" />}{loading ? 'Please wait…' : confirmLabel}</button>
      </div>
    </section>
  </div>;
}

export default ConfirmDialog;
