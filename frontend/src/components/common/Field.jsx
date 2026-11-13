import React from 'react';

export function Field({
  label,
  hint,
  error,
  className = '',
  as,
  inputClassName = '',
  children,
  required = false,
  ...inputProps
}) {
  return (
    <label className={`block ${className}`}>
      {label && (
        <span className="mb-2 block text-[13px] font-semibold text-slate-700">
          {label} {required && <span className="text-rose-600">*</span>}
        </span>
      )}
      {as === 'select' ? (
        <select
          {...inputProps}
          required={required}
          className={`form-control ${error ? 'border-rose-300 ring-rose-200' : ''} ${inputClassName}`}
        >
          {children}
        </select>
      ) : as === 'textarea' ? (
        <textarea
          {...inputProps}
          required={required}
          className={`form-control min-h-24 resize-y ${error ? 'border-rose-300 ring-rose-200' : ''} ${inputClassName}`}
        />
      ) : (
        <input
          {...inputProps}
          required={required}
          className={`form-control ${error ? 'border-rose-300 ring-rose-200' : ''} ${inputClassName}`}
        />
      )}
      {hint && !error && <span className="mt-1.5 block text-xs text-slate-400">{hint}</span>}
      {error && <span role="alert" className="mt-1.5 block text-xs font-medium text-rose-600">{error}</span>}
    </label>
  );
}

export default Field;
