import React from 'react';

export function IconButton({
  children,
  className = '',
  variant = 'secondary',
  title,
  'aria-label': ariaLabel,
  disabled = false,
  ...props
}) {
  const styles = {
    primary: 'bg-brand text-ink hover:bg-gold-600',
    secondary: 'border border-slate-200 bg-white text-ink hover:border-slate-300 hover:bg-slate-50',
    subtle: 'text-slate-400 hover:bg-white/10 hover:text-white',
    ghost: 'text-slate-500 hover:bg-slate-100 hover:text-ink',
    danger: 'text-rose-600 hover:bg-rose-50',
  };

  return (
    <button
      type="button"
      title={title}
      aria-label={ariaLabel || title}
      disabled={disabled}
      className={`grid place-items-center rounded-xl p-2 transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand disabled:cursor-not-allowed disabled:opacity-50 ${styles[variant] || styles.secondary} ${className}`}
      {...props}
    >
      {children}
    </button>
  );
}

export default IconButton;
