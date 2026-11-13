import React, { useState } from 'react';
import { Modal } from './Modal';
import { Button } from '../buttons/Button';
import { Field } from '../common/Field';

export function AdjustBalanceModal({
  isOpen = true,
  onClose,
  account,
  onSubmit,
  isLoading = false,
  error = '',
}) {
  const [action, setAction] = useState('credit');
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');

  const handleSubmit = (e) => {
    e.preventDefault();
    onSubmit({
      account,
      action,
      amount: String(amount).trim(),
      reason: String(reason).trim(),
    });
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={!isLoading ? onClose : undefined}
      maxWidth="max-w-md"
      title="Adjust account balance"
      subtitle={`Account ending in ${account?.accountNumber?.slice(-4)}`}
      showClose={!isLoading}
    >
      <form onSubmit={handleSubmit} className="space-y-4">
        <label className="block text-xs font-semibold text-slate-700">
          Action <span className="text-rose-600">*</span>
          <select
            name="action"
            value={action}
            onChange={(e) => setAction(e.target.value)}
            disabled={isLoading}
            className="form-control mt-1.5"
            required
          >
            <option value="credit">Credit account (Deposit/Adjustment)</option>
            <option value="deduct">Deduct from account (Debit/Correction)</option>
          </select>
        </label>

        <Field
          label="Amount (NGN)"
          type="number"
          min="0.01"
          step="0.01"
          placeholder="0.00"
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
          disabled={isLoading}
          required
        />

        <Field
          label="Reason for adjustment"
          as="textarea"
          placeholder="Detailed audit explanation for ledger adjustment..."
          minLength={3}
          maxLength={200}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          disabled={isLoading}
          required
        />

        {error && (
          <p role="alert" className="rounded-xl bg-rose-50 p-3 text-xs text-rose-700">
            {error}
          </p>
        )}

        <div className="flex gap-2 pt-1">
          <Button
            type="button"
            variant="secondary"
            className="flex-1"
            disabled={isLoading}
            onClick={onClose}
          >
            Cancel
          </Button>
          <Button
            type="submit"
            className="flex-1"
            disabled={isLoading || !amount || !reason}
            isLoading={isLoading}
            loadingText="Recording…"
          >
            Record adjustment
          </Button>
        </div>

        <p className="text-[10px] leading-4 text-slate-400 text-center">
          This posts a double-entry ledger record and stores your identity in the audit trail.
        </p>
      </form>
    </Modal>
  );
}

export default AdjustBalanceModal;
