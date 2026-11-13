import React, { useState } from 'react';
import { Fingerprint, ArrowRight } from 'lucide-react';
import { Modal } from './Modal';
import { Button } from '../buttons/Button';

export function PinModal({
  isOpen = true,
  onClose,
  onConfirm,
  title = 'Enter your transaction PIN',
  subtitle = 'Authorize this transfer securely.',
  isLoading = false,
  error = '',
}) {
  const [pin, setPin] = useState('');

  const handleSubmit = (e) => {
    e.preventDefault();
    if (pin.length === 4) {
      onConfirm(pin);
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} maxWidth="max-w-sm" showClose={!isLoading}>
      <div className="mx-auto grid h-12 w-12 place-items-center rounded-xl bg-gold-50 text-gold-800">
        <Fingerprint size={24} />
      </div>
      <h2 className="mt-4 text-center text-lg font-bold text-ink">{title}</h2>
      <p className="mt-1 text-center text-xs text-slate-500">{subtitle}</p>

      <form onSubmit={handleSubmit} className="mt-5 space-y-4">
        <input
          aria-label="4-digit Transaction PIN"
          type="password"
          inputMode="numeric"
          autoFocus
          maxLength={4}
          value={pin}
          onChange={(e) => setPin(e.target.value.replace(/\D/g, '').slice(0, 4))}
          placeholder="••••"
          disabled={isLoading}
          className="form-control text-center text-2xl tracking-[0.6em] font-mono py-3.5"
          required
        />

        {error && (
          <p role="alert" className="text-center text-xs font-medium text-rose-700">
            {error}
          </p>
        )}

        <Button
          type="submit"
          className="w-full"
          disabled={pin.length !== 4 || isLoading}
          isLoading={isLoading}
          loadingText="Verifying PIN…"
        >
          Confirm transfer <ArrowRight size={15} />
        </Button>

        {!isLoading && (
          <button
            type="button"
            onClick={onClose}
            className="w-full text-center text-xs font-semibold text-slate-400 hover:text-slate-600 transition pt-1"
          >
            Cancel
          </button>
        )}
      </form>
    </Modal>
  );
}

export default PinModal;
