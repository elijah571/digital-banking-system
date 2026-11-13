import React, { useState } from 'react';
import { ArrowLeft, ArrowRight, LockKeyhole, Activity } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { money } from '../../data';

export function VerifyTransferPage({
  transfer,
  onComplete,
  onBack,
  isLoading = false,
  error = '',
}) {
  const [code, setCode] = useState('');

  const handleSubmit = (e) => {
    e?.preventDefault();
    if (code.length === 6) {
      onComplete(code);
    }
  };

  return (
    <div className="fade-in">
      <PageHeader
        title="Verify this transfer"
        description="One extra security step is required to complete this transfer."
        actions={
          <Button variant="secondary" onClick={onBack} disabled={isLoading}>
            <ArrowLeft size={15} /> Back
          </Button>
        }
      />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.25fr)_minmax(260px,0.75fr)]">
        <Panel className="p-6 sm:p-8">
          <div className="mx-auto max-w-md">
            <div className="flex items-center gap-2 text-sm font-bold text-ink">
              <LockKeyhole size={17} className="text-gold-800" /> Confirm it’s really you
            </div>
            <p className="mt-3 text-xs leading-5 text-slate-500">
              We sent a 6-digit one-time authorization code to your verified email address. Enter it below to complete your transfer.
            </p>

            <form onSubmit={handleSubmit} className="mt-6">
              <label className="block text-[11px] font-bold uppercase tracking-[0.1em] text-slate-500">
                Enter 6-digit verification code
              </label>
              <input
                aria-label="One-time verification code"
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={6}
                value={code}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                disabled={isLoading}
                className="mt-3 w-full rounded-xl border border-slate-200 bg-slate-50 px-4 py-4 text-center text-2xl font-bold tracking-[0.55em] font-mono text-ink outline-none transition focus:border-brand focus:bg-white focus:ring-4 focus:ring-brand"
                placeholder="••••••"
                required
              />

              {error && (
                <p role="alert" className="mt-3 text-center text-xs font-medium text-rose-700">
                  {error}
                </p>
              )}

              <Button
                type="submit"
                disabled={code.length !== 6 || isLoading}
                isLoading={isLoading}
                loadingText="Verifying code…"
                className="mt-5 w-full"
              >
                Verify and complete transfer <ArrowRight size={15} />
              </Button>
            </form>

            <p className="mt-4 rounded-xl bg-gold-50/70 p-3 text-[11px] leading-5 text-slate-500">
              Never share a verification code with anyone. Eli‑Pay staff will never ask for this code.
            </p>
          </div>
        </Panel>

        <Panel className="h-fit p-5">
          <div className="flex items-center gap-2">
            <Activity size={17} className="text-gold-800" />
            <h3 className="text-sm font-bold text-ink">Transfer being verified</h3>
          </div>
          <p className="mt-5 text-[10px] font-medium uppercase tracking-wide text-slate-400">
            Amount
          </p>
          <p className="mt-1 text-[25px] font-bold text-ink">
            {money(Number(transfer?.amount) || 0)}
          </p>
          <div className="mt-4 border-t border-slate-100 pt-3 text-xs">
            <div className="text-slate-400">Recipient</div>
            <div className="mt-1 font-semibold text-ink">
              {transfer?.recipientName || 'Verified recipient'}
            </div>
            {transfer?.bank && (
              <div className="mt-0.5 text-slate-500">{transfer.bank}</div>
            )}
          </div>
        </Panel>
      </div>
    </div>
  );
}

export default VerifyTransferPage;
