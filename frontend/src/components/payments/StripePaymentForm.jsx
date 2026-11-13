import React, { useState } from 'react';
import { PaymentElement, useElements, useStripe } from '@stripe/react-stripe-js';
import { ArrowRight } from 'lucide-react';
import { Button } from '../buttons/Button';

export function StripePaymentForm({ onPaid }) {
  const stripe = useStripe();
  const elements = useElements();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async (event) => {
    event.preventDefault();
    if (!stripe || !elements) return;

    setBusy(true);
    setError('');
    try {
      const result = await stripe.confirmPayment({
        elements,
        redirect: 'if_required',
      });
      if (result.error) {
        setError(result.error.message || 'Card payment could not be confirmed.');
        return;
      }
      onPaid?.(result);
    } catch {
      setError('Card payment could not be confirmed. Please retry.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <form onSubmit={submit} className="mt-5 space-y-4">
      <PaymentElement />
      <Button
        className="w-full"
        type="submit"
        disabled={!stripe || busy}
        isLoading={busy}
        loadingText="Confirming payment…"
      >
        Pay securely <ArrowRight size={15} />
      </Button>
      {error && (
        <p role="alert" className="text-xs text-rose-700 font-medium">
          {error}
        </p>
      )}
    </form>
  );
}

export default StripePaymentForm;
