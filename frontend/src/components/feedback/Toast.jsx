import React, { useEffect } from 'react';
import { Check, AlertCircle, Info, X } from 'lucide-react';

export function Toast({ toast, onClose, duration = 3500 }) {
  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => {
      onClose?.();
    }, duration);
    return () => window.clearTimeout(timer);
  }, [toast, onClose, duration]);

  if (!toast) return null;

  const isError = toast.type === 'error';
  const isSuccess = toast.type === 'success' || !toast.type;

  return (
    <div
      role="status"
      className="fixed bottom-5 left-1/2 z-[60] flex -translate-x-1/2 items-center gap-3 rounded-2xl bg-ink px-4 py-3 text-xs font-medium text-white shadow-2xl transition fade-in"
    >
      {isSuccess && <Check size={16} className="text-green-300 shrink-0" />}
      {isError && <AlertCircle size={16} className="text-rose-400 shrink-0" />}
      {!isSuccess && !isError && <Info size={16} className="text-gold-400 shrink-0" />}
      <span className="max-w-xs sm:max-w-md">{toast.message}</span>
      <button
        onClick={onClose}
        className="rounded-lg p-1 text-slate-400 hover:bg-white/10 hover:text-white"
        aria-label="Close notification"
      >
        <X size={14} />
      </button>
    </div>
  );
}

export default Toast;
