import React from 'react';
import { Loader2 } from 'lucide-react';

export function Button({
  children,
  variant = 'primary',
  className = '',
  isLoading = false,
  loadingText,
  disabled,
  type = 'button',
  ...props
}) {
  const styles = {
    primary: 'bg-brand text-ink shadow-sm hover:bg-gold-600 focus-visible:ring-brand',
    secondary: 'border border-slate-200 bg-white text-ink hover:border-slate-300 hover:bg-slate-50 focus-visible:ring-brand',
    dark: 'bg-ink text-white hover:bg-slate-800 focus-visible:ring-brand',
    subtle: 'text-slate-600 hover:bg-slate-100 hover:text-ink focus-visible:ring-brand',
    danger: 'bg-rose-600 text-white hover:bg-rose-700 focus-visible:ring-brand',
  };

  return (
    <button
      type={type}
      disabled={disabled || isLoading}
      className={`inline-flex min-h-10 items-center justify-center gap-2 rounded-xl px-4 py-2.5 text-sm font-semibold transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50 ${styles[variant] || styles.primary} ${className}`}
      {...props}
    >
      {isLoading && (
        <Loader2 className="h-4 w-4 animate-spin text-current shrink-0" />
      )}
      {isLoading && loadingText ? loadingText : children}
    </button>
  );
}

export default Button;
