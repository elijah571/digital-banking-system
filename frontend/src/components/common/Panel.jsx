import React from 'react';

export function Panel({ children, className = '', ...props }) {
  const surface = /\bbg-(?!white(?:\s|$))\S+/.test(className) ? '' : 'bg-white';
  return (
    <section
      className={`rounded-2xl border border-slate-200/80 ${surface} shadow-panel ${className}`}
      {...props}
    >
      {children}
    </section>
  );
}

export default Panel;
