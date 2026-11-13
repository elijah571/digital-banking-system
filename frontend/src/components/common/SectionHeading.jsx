import React from 'react';

export function SectionHeading({ title, action, onAction, subtitle }) {
  return (
    <div className="mb-4 flex items-center justify-between">
      <div>
        <h2 className="text-sm font-bold text-ink">{title}</h2>
        {subtitle && <p className="mt-0.5 text-xs text-slate-400">{subtitle}</p>}
      </div>
      {action && onAction && (
        <button
          type="button"
          onClick={onAction}
          className="text-xs font-semibold text-gold-800 hover:text-gold-900 transition"
        >
          {action}
        </button>
      )}
    </div>
  );
}

export default SectionHeading;
