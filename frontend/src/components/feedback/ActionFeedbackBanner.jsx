import React, { useEffect } from 'react';
import { Loader2, CheckCircle2, AlertTriangle, X } from 'lucide-react';

export function ActionFeedbackBanner({
  actionStatus,
  onDismiss,
  autoDismissSuccessMs = 4000,
  className = '',
}) {
  const { status, title, message } = actionStatus || {};

  useEffect(() => {
    if (status === 'success' && autoDismissSuccessMs > 0) {
      const timer = setTimeout(() => {
        onDismiss?.();
      }, autoDismissSuccessMs);
      return () => clearTimeout(timer);
    }
  }, [status, autoDismissSuccessMs, onDismiss]);

  if (!status || status === 'idle') return null;

  const configs = {
    loading: {
      container: 'border-gold-200 bg-gold-50/90 text-gold-950',
      icon: <Loader2 className="h-5 w-5 animate-spin text-gold-800 shrink-0" />,
      badge: 'Processing',
      badgeClass: 'bg-gold-200/60 text-gold-900',
    },
    success: {
      container: 'border-green-200 bg-green-50/90 text-green-950',
      icon: <CheckCircle2 className="h-5 w-5 text-green-700 shrink-0" />,
      badge: 'Completed',
      badgeClass: 'bg-green-200/60 text-green-900',
    },
    error: {
      container: 'border-rose-200 bg-rose-50/90 text-rose-950',
      icon: <AlertTriangle className="h-5 w-5 text-rose-700 shrink-0" />,
      badge: 'Action Failed',
      badgeClass: 'bg-rose-200/60 text-rose-900',
    },
  };

  const current = configs[status] || configs.loading;

  return (
    <div
      role="alert"
      className={`relative mb-5 flex items-start gap-3.5 rounded-2xl border p-4 shadow-sm transition fade-in sm:items-center ${current.container} ${className}`}
    >
      <div className="mt-0.5 sm:mt-0">{current.icon}</div>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className={`rounded-md px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wider ${current.badgeClass}`}>
            {current.badge}
          </span>
          {title && <h3 className="text-xs font-bold">{title}</h3>}
        </div>
        {message && <p className="mt-1 text-xs opacity-90 leading-5">{message}</p>}
      </div>
      {status !== 'loading' && onDismiss && (
        <button
          onClick={onDismiss}
          className="rounded-lg p-1.5 opacity-60 hover:opacity-100 transition"
          aria-label="Dismiss message"
        >
          <X size={16} />
        </button>
      )}
    </div>
  );
}

export default ActionFeedbackBanner;
