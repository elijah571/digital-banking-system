import React from 'react';

export function StatusBadge({ children, tone }) {
  const statusTone =
    tone ||
    (children === 'Success' ||
    children === 'Approved' ||
    children === 'Active' ||
    children === 'COMPLETED' ||
    children === 'SUCCESSFUL' ||
    children === 'VERIFIED'
      ? 'green'
      : children === 'High' ||
        children === 'Blocked' ||
        children === 'FAILED' ||
        children === 'REJECTED' ||
        children === 'CANCELLED' ||
        children === 'Disabled'
      ? 'red'
      : children === 'FLAGGED' ||
        children === 'PENDING' ||
        children === 'PENDING_APPROVAL' ||
        children === 'PENDING_VERIFICATION' ||
        children === 'PENDING_REVIEW' ||
        children === 'Review'
      ? 'amber'
      : 'slate');

  const colors = {
    green: 'bg-green-50 text-green-700 ring-green-600/10',
    amber: 'bg-amber-50 text-amber-700 ring-amber-600/10',
    red: 'bg-rose-50 text-rose-700 ring-rose-600/10',
    gold: 'bg-gold-50 text-gold-700 ring-gold-600/10',
    slate: 'bg-slate-100 text-slate-600 ring-slate-500/10',
  };

  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-[11px] font-semibold ring-1 ring-inset ${
        colors[statusTone] || colors.slate
      }`}
    >
      {children}
    </span>
  );
}

export { StatusBadge as Status };
export default StatusBadge;
