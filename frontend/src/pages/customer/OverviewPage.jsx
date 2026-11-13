import React from 'react';
import {
  ArrowDownLeft,
  ArrowUpRight,
  Download,
  Eye,
  Plus,
  Send,
  ShieldCheck,
} from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { SectionHeading } from '../../components/common/SectionHeading';
import { AccountCard } from '../../components/accounts/AccountCard';
import { ActivityChart } from '../../components/accounts/ActivityChart';
import { money } from '../../data';

export function OverviewPage({ accounts = [], transactions = [], setView }) {
  const total = accounts.reduce((sum, account) => sum + (Number(account.balance) || 0), 0);

  return (
    <div className="fade-in">
      <PageHeader
        title="Your money, in one clear view"
        description="Here’s what’s happening across your accounts."
        actions={
          <Button variant="secondary" onClick={() => setView('transactions')}>
            <Download size={15} /> Statement
          </Button>
        }
      />
      <div className="grid gap-4 lg:grid-cols-[1.45fr_0.8fr]">
        <div className="space-y-4">
          <Panel className="relative overflow-hidden bg-ink p-6 text-white sm:p-7">
            <div className="absolute -right-8 -top-20 h-56 w-56 rounded-full border border-white/[0.08]" />
            <div className="absolute -right-1 -top-12 h-44 w-44 rounded-full border border-white/[0.08]" />
            <div className="relative flex items-start justify-between">
              <div>
                <div className="flex items-center gap-2 text-[11px] font-medium text-slate-300">
                  <Eye size={14} /> TOTAL AVAILABLE BALANCE
                </div>
                <div className="mt-3 text-[32px] font-bold tracking-tight sm:text-[38px]">
                  {money(total)}
                </div>
                <div className="mt-2 flex items-center gap-2 text-[11px] text-slate-400">
                  <span className="rounded-md bg-green-400/15 px-2 py-1 font-semibold text-green-300">
                    ↑ 8.6%
                  </span>{' '}
                  compared to last month
                </div>
              </div>
              <span className="hidden rounded-lg border border-white/10 bg-white/[0.06] px-3 py-1.5 text-[10px] font-semibold text-slate-300 sm:inline-flex">
                NGN · Nigerian naira
              </span>
            </div>
            <div className="relative mt-7 flex flex-wrap gap-2">
              <Button className="min-h-9" onClick={() => setView('transfers')}>
                <Send size={14} /> Transfer
              </Button>
              <Button
                className="min-h-9 border border-white/15 bg-white/10 text-white hover:bg-white/15"
                onClick={() => setView('add-money')}
              >
                <Plus size={14} /> Add money
              </Button>
              <Button
                className="min-h-9 border border-white/15 bg-white/10 text-white hover:bg-white/15"
                onClick={() => setView('withdraw')}
              >
                <ArrowUpRight size={14} /> Withdraw
              </Button>
            </div>
          </Panel>

          <div className="grid gap-4 md:grid-cols-2">
            {accounts.map((account) => (
              <AccountCard
                key={account.id}
                account={account}
                onTransfer={() => setView('transfers')}
              />
            ))}
          </div>

          <Panel className="p-5">
            <SectionHeading
              title="Monthly snapshot"
              action="View transactions"
              onAction={() => setView('transactions')}
            />
            <div className="grid grid-cols-3 gap-3">
              {[
                ['Money in', '₦1,420,000', 'green'],
                ['Money out', '₦386,500', 'neutral'],
                ['Net change', '+₦1,033,500', 'green'],
              ].map(([label, amount, tone]) => (
                <div key={label} className="rounded-xl bg-slate-50 p-3 sm:p-4">
                  <div className="text-[10px] text-slate-500 sm:text-xs">{label}</div>
                  <div
                    className={`mt-1.5 text-[14px] font-bold sm:text-[17px] ${
                      tone === 'green' ? 'text-green-700' : 'text-ink'
                    }`}
                  >
                    {amount}
                  </div>
                  <div className="mt-1 text-[9px] text-slate-400">This month</div>
                </div>
              ))}
            </div>
          </Panel>
        </div>

        <div className="space-y-4">
          <Panel className="p-5">
            <SectionHeading title="Spending activity" action="Last 6 months" />
            <div className="flex items-end justify-between">
              <div>
                <p className="text-[10px] text-slate-400">Total outflow</p>
                <p className="mt-1 text-xl font-bold text-ink">₦386,500</p>
              </div>
              <span className="rounded-lg bg-green-50 px-2 py-1 text-[10px] font-semibold text-green-700">
                ↓ 4.2%
              </span>
            </div>
            <ActivityChart />
          </Panel>

          <Panel className="p-5">
            <SectionHeading
              title="Recent activity"
              action="View all"
              onAction={() => setView('transactions')}
            />
            <div className="space-y-1">
              {transactions.slice(0, 4).map((tx) => (
                <div key={tx.id} className="flex items-center gap-3 rounded-xl py-2.5">
                  <span
                    className={`grid h-9 w-9 shrink-0 place-items-center rounded-xl ${
                      tx.amount > 0
                        ? 'bg-green-50 text-green-700'
                        : 'bg-slate-100 text-slate-600'
                    }`}
                  >
                    {tx.amount > 0 ? (
                      <ArrowDownLeft size={16} />
                    ) : (
                      <ArrowUpRight size={16} />
                    )}
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-xs font-semibold text-ink">
                      {tx.title}
                    </div>
                    <div className="mt-1 text-[10px] text-slate-400">
                      {tx.subtitle}
                    </div>
                  </div>
                  <div
                    className={`whitespace-nowrap text-xs font-bold ${
                      tx.amount > 0 ? 'text-green-700' : 'text-ink'
                    }`}
                  >
                    {tx.amount > 0 ? '+' : '−'}
                    {money(Math.abs(tx.amount))}
                  </div>
                </div>
              ))}
            </div>
          </Panel>

          <div className="rounded-2xl border border-gold-100 bg-gold-50/80 p-4">
            <div className="flex gap-3">
              <span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-white text-gold-800">
                <ShieldCheck size={17} />
              </span>
              <div>
                <h3 className="text-xs font-bold text-ink">Your money is protected</h3>
                <p className="mt-1 text-[11px] leading-5 text-slate-500">
                  Every transfer is secured with your PIN and real-time monitoring.
                </p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

export default OverviewPage;
