import React from 'react';
import { Button } from '../buttons/Button';

export function EmptyState({
  icon: Icon,
  title,
  description,
  actionLabel,
  onAction,
  className = '',
}) {
  return (
    <div className={`p-8 text-center sm:p-12 ${className}`}>
      {Icon && (
        <span className="mx-auto grid h-12 w-12 place-items-center rounded-2xl bg-slate-100 text-slate-500">
          <Icon size={22} />
        </span>
      )}
      {title && <h3 className="mt-4 text-sm font-bold text-ink">{title}</h3>}
      {description && (
        <p className="mx-auto mt-1.5 max-w-sm text-xs leading-5 text-slate-500">
          {description}
        </p>
      )}
      {actionLabel && onAction && (
        <Button onClick={onAction} className="mt-5">
          {actionLabel}
        </Button>
      )}
    </div>
  );
}

export default EmptyState;
