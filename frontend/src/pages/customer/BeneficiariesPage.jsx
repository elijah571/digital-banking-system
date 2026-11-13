import React, { useMemo, useState } from 'react';
import { Plus, Search, Send, Users } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { Field } from '../../components/common/Field';
import { banks } from '../../data';

export function BeneficiariesPage({
  beneficiaries = [],
  onAdd,
  onRemove,
  onSend,
}) {
  const [query, setQuery] = useState('');
  const [isAdding, setIsAdding] = useState(false);
  const [newBeneficiary, setNewBeneficiary] = useState({
    name: '',
    bank: '',
    accountNumber: '',
  });

  const filteredBeneficiaries = useMemo(() => {
    return beneficiaries.filter((beneficiary) =>
      `${beneficiary.name} ${beneficiary.bank} ${beneficiary.accountNumber}`
        .toLowerCase()
        .includes(query.toLowerCase())
    );
  }, [beneficiaries, query]);

  const submitBeneficiary = (event) => {
    event.preventDefault();
    if (onAdd?.(newBeneficiary)) {
      setNewBeneficiary({ name: '', bank: '', accountNumber: '' });
      setIsAdding(false);
    }
  };

  return (
    <div className="fade-in">
      <PageHeader
        title="Saved beneficiaries"
        description="Keep trusted recipients handy for quick and accurate bank transfers."
        actions={
          <Button onClick={() => setIsAdding((open) => !open)}>
            <Plus size={15} />
            {isAdding ? 'Cancel' : 'Add beneficiary'}
          </Button>
        }
      />

      {isAdding && (
        <Panel className="mb-5 p-5 sm:p-6 fade-in">
          <h2 className="text-sm font-bold text-ink">Add a beneficiary</h2>
          <p className="mt-1 text-xs text-slate-500">
            Recipient details are saved locally in your active browser session.
          </p>
          <form
            onSubmit={submitBeneficiary}
            className="mt-5 grid gap-4 sm:grid-cols-2 lg:grid-cols-4"
          >
            <Field
              label="Recipient name"
              maxLength="120"
              autoComplete="name"
              placeholder="Full legal name"
              value={newBeneficiary.name}
              onChange={(event) =>
                setNewBeneficiary((old) => ({ ...old, name: event.target.value }))
              }
              required
            />
            <Field
              label="Bank"
              as="select"
              value={newBeneficiary.bank}
              onChange={(event) =>
                setNewBeneficiary((old) => ({ ...old, bank: event.target.value }))
              }
              required
            >
              <option value="">Select a bank</option>
              {banks.map((bank) => (
                <option key={bank} value={bank}>
                  {bank}
                </option>
              ))}
            </Field>
            <Field
              label="Account number"
              inputMode="numeric"
              autoComplete="off"
              maxLength="10"
              pattern="[0-9]{10}"
              placeholder="10-digit account number"
              value={newBeneficiary.accountNumber}
              onChange={(event) =>
                setNewBeneficiary((old) => ({
                  ...old,
                  accountNumber: event.target.value.replace(/\D/g, ''),
                }))
              }
              required
            />
            <div className="flex items-end">
              <Button type="submit" className="w-full">
                Save beneficiary
              </Button>
            </div>
          </form>
        </Panel>
      )}

      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-sm font-bold text-ink">
            {beneficiaries.length} saved recipient{beneficiaries.length === 1 ? '' : 's'}
          </h2>
          <p className="mt-0.5 text-xs text-slate-500">
            Verify recipient name details before confirming a transfer.
          </p>
        </div>
        <label className="relative min-w-[200px] flex-1 sm:w-64 sm:flex-none">
          <Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search recipients..."
            className="form-control pl-9"
          />
        </label>
      </div>

      {filteredBeneficiaries.length ? (
        <div className="grid gap-4 lg:grid-cols-2">
          {filteredBeneficiaries.map((beneficiary) => (
            <Panel key={beneficiary.id} className="p-5">
              <div className="flex items-start gap-3">
                <span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-gold-50 text-sm font-bold text-gold-800">
                  {beneficiary.name
                    .split(/\s+/)
                    .slice(0, 2)
                    .map((part) => part[0])
                    .join('')
                    .toUpperCase()}
                </span>
                <div className="min-w-0 flex-1">
                  <h3 className="truncate text-sm font-bold text-ink">{beneficiary.name}</h3>
                  <p className="mt-0.5 text-xs text-slate-500">{beneficiary.bank}</p>
                  <p className="mt-2 text-xs font-mono font-medium tracking-wide text-slate-600">
                    •••• {beneficiary.accountNumber.slice(-4)}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => onRemove?.(beneficiary)}
                  className="rounded-lg px-2 py-1 text-[11px] font-semibold text-slate-400 hover:bg-rose-50 hover:text-rose-700 transition"
                  aria-label={`Remove ${beneficiary.name}`}
                >
                  Remove
                </button>
              </div>
              <Button
                variant="secondary"
                className="mt-5 w-full"
                onClick={() => onSend?.(beneficiary)}
              >
                <Send size={14} /> Send money
              </Button>
            </Panel>
          ))}
        </div>
      ) : (
        <Panel className="p-10 text-center">
          <span className="mx-auto grid h-12 w-12 place-items-center rounded-xl bg-slate-100 text-slate-500">
            <Users size={20} />
          </span>
          <h2 className="mt-4 text-sm font-bold text-ink">
            {query ? 'No recipients found' : 'No beneficiaries yet'}
          </h2>
          <p className="mx-auto mt-2 max-w-md text-xs leading-5 text-slate-500">
            {query
              ? 'Try searching with another name, bank, or account number.'
              : 'Add a trusted recipient to make recurring transfers faster.'}
          </p>
          {!query && (
            <Button className="mt-5" onClick={() => setIsAdding(true)}>
              <Plus size={15} /> Add beneficiary
            </Button>
          )}
        </Panel>
      )}
    </div>
  );
}

export default BeneficiariesPage;
