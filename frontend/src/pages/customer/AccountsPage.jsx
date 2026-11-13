import React from 'react';
import { ArrowRight, ArrowUpRight, Plus } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { SectionHeading } from '../../components/common/SectionHeading';
import { AccountCard } from '../../components/accounts/AccountCard';
import { money } from '../../data';

export function AccountsPage({ accounts = [], setView, setOpenAccount, currentUser }) {
  const total = accounts.reduce((sum, account) => sum + (Number(account.balance) || 0), 0);

  return (
    <div className="fade-in">
      <PageHeader
        title="My accounts"
        description="Manage your accounts and see where your money lives."
        actions={
          <>
            <Button variant="secondary" onClick={() => setView('account-upgrade')}>
              <ArrowUpRight size={15} /> Upgrade account
            </Button>
            <Button onClick={() => setOpenAccount ? setOpenAccount(true) : setView('account-open')}>
              <Plus size={16} /> Open account
            </Button>
          </>
        }
      />

      <Panel className="mb-5 flex flex-wrap items-center justify-between gap-4 bg-ink p-5 text-white sm:px-7">
        <div>
          <p className="text-[11px] font-medium text-slate-300">TOTAL ACCOUNT BALANCE</p>
          <p className="mt-1.5 text-[27px] font-bold tracking-tight">{money(total)}</p>
        </div>
        <div className="text-right">
          <p className="text-[11px] text-slate-400">{accounts.length} account{accounts.length === 1 ? '' : 's'}</p>
          <p className="mt-1 text-xs text-green-300">All accounts active</p>
        </div>
      </Panel>

      <div className="mb-5 flex flex-col gap-4 rounded-2xl border border-gold-100 bg-gold-50/70 p-5 sm:flex-row sm:items-center sm:justify-between sm:p-6">
        <div className="flex items-start gap-3">
          <span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-white text-gold-800 shadow-sm">
            <ArrowUpRight size={18} />
          </span>
          <div>
            <h2 className="text-sm font-bold text-ink">Need higher transaction limits?</h2>
            <p className="mt-1 max-w-2xl text-xs leading-5 text-slate-600">
              Upgrade your account with BVN, proof-of-address, and live face verification.
            </p>
          </div>
        </div>
        <Button className="shrink-0" onClick={() => setView('account-upgrade')}>
          Upgrade account <ArrowRight size={15} />
        </Button>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        {accounts.map((account) => (
          <AccountCard
            key={account.id}
            account={account}
            onTransfer={() => setView('transfers')}
          />
        ))}
      </div>

      <Panel className="mt-5 p-5">
        <SectionHeading title="Account details" />
        <div className="grid gap-4 sm:grid-cols-3">
          {[
            ['Account holder', currentUser?.fullName || 'Customer'],
            ['Bank', 'Eli‑Pay'],
            ['Account type', 'Personal · NGN'],
          ].map(([label, value]) => (
            <div key={label} className="border-t border-slate-100 pt-3">
              <p className="text-[10px] text-slate-400">{label}</p>
              <p className="mt-1 text-xs font-semibold text-slate-700">{value}</p>
            </div>
          ))}
        </div>
      </Panel>
    </div>
  );
}

export default AccountsPage;
