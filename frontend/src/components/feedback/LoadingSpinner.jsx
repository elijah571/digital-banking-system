import React from 'react';
import { Loader2 } from 'lucide-react';

export function LoadingSpinner({
  size = 20,
  className = '',
  label,
  fullHeight = false,
}) {
  const content = (
    <div className={`inline-flex items-center justify-center gap-2.5 text-xs text-slate-500 ${className}`}>
      <Loader2 size={size} className="animate-spin text-gold-800" />
      {label && <span className="font-medium text-slate-600">{label}</span>}
    </div>
  );

  if (fullHeight) {
    return (
      <div className="flex min-h-[220px] w-full items-center justify-center p-8">
        {content}
      </div>
    );
  }

  return content;
}

export default LoadingSpinner;
