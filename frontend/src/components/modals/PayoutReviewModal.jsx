import React, { useState } from 'react';
import { Check, X } from 'lucide-react';
import { Modal } from './Modal';
import { Button } from '../buttons/Button';
import { StatusBadge } from '../feedback/StatusBadge';
import { money } from '../../data';

export function PayoutReviewModal({
  isOpen = true,
  onClose,
  payout,
  onDecision,
  isLoading = false,
}) {
  const [rejectReason, setRejectReason] = useState('');
  const [showRejectInput, setShowRejectInput] = useState(false);

  if (!payout) return null;

  const handleApprove = () => {
    onDecision(payout, 'Approved');
  };

  const handleReject = () => {
    if (!showRejectInput) {
      setShowRejectInput(true);
      return;
    }
    onDecision(payout, 'Rejected', rejectReason || 'Rejected by operations review');
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={!isLoading ? onClose : undefined}
      maxWidth="max-w-lg"
      showClose={!isLoading}
    >
      <div className="flex items-start justify-between">
        <div>
          <p className="text-[10px] font-bold uppercase tracking-wider text-gold-800">
            Payout Review
          </p>
          <h2 className="mt-1 text-xl font-bold text-ink">{payout.name}</h2>
          <p className="mt-1 text-xs text-slate-400">ID: {payout.id}</p>
        </div>
        <StatusBadge>{payout.risk || 'PENDING'}</StatusBadge>
      </div>

      <div className="mt-5 rounded-2xl bg-slate-50 p-4 text-center border border-slate-100">
        <div className="text-[10px] font-semibold uppercase tracking-wider text-slate-400">
          Payout amount
        </div>
        <div className="mt-1 text-[28px] font-bold text-ink">{money(payout.amount || 0)}</div>
      </div>

      <div className="my-4 space-y-3">
        {[
          ['Destination', payout.bank],
          ['Risk assessment', payout.risk],
          ['Requested', payout.requested],
          ['Review notes', 'Outbound withdrawal requiring dual-authorization.'],
        ].map(([k, v]) => (
          <div key={k} className="flex justify-between gap-4 border-b border-slate-100 pb-2 text-xs">
            <span className="text-slate-500">{k}</span>
            <span className="text-right font-semibold text-ink">{v}</span>
          </div>
        ))}
      </div>

      {showRejectInput && (
        <div className="mb-4">
          <label className="block text-xs font-semibold text-slate-700 mb-1">
            Rejection reason
          </label>
          <input
            type="text"
            className="form-control"
            placeholder="Reason for rejecting this payout..."
            value={rejectReason}
            onChange={(e) => setRejectReason(e.target.value)}
            disabled={isLoading}
          />
        </div>
      )}

      <div className="flex gap-2 pt-2">
        <Button
          variant="secondary"
          className="flex-1"
          disabled={isLoading}
          onClick={handleReject}
          isLoading={isLoading && showRejectInput}
          loadingText="Rejecting…"
        >
          <X size={15} /> {showRejectInput ? 'Confirm Reject' : 'Reject'}
        </Button>
        <Button
          className="flex-1"
          disabled={isLoading}
          onClick={handleApprove}
          isLoading={isLoading && !showRejectInput}
          loadingText="Approving…"
        >
          <Check size={15} /> Approve payout
        </Button>
      </div>

      <p className="mt-3 text-center text-[10px] text-slate-400">
        All operations actions are permanently recorded in the immutable audit log.
      </p>
    </Modal>
  );
}

export default PayoutReviewModal;
