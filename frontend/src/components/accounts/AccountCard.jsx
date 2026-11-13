import React from 'react';
import { Landmark, Wallet, Send } from 'lucide-react';
import { Panel } from '../common/Panel';
import { StatusBadge } from '../feedback/StatusBadge';
import { money } from '../../data';

export function AccountCard({ account, onTransfer, onSelect }) {
  const isGold = account.accent === 'gold';

  return (
    <Panel className="overflow-hidden transition hover:border-slate-300">
      <div className="flex items-start justify-between p-5">
        <div className="flex items-center gap-3">
          <div
            className={`grid h-10 w-10 place-items-center rounded-xl ${
              isGold ? 'bg-gold-50 text-gold-800' : 'bg-gold-50 text-gold-700'
            }`}
          >
            {isGold ? <Landmark size={18} /> : <Wallet size={18} />}
          </div>
          <div>
            <h3 className="text-sm font-bold text-ink">{account.name}</h3>
            <p className="mt-0.5 text-xs text-slate-400">
              {account.type} · •••• {account.lastFour}
            </p>
          </div>
        </div>
        <StatusBadge tone="green">{account.status}</StatusBadge>
      </div>

      <div className="border-t border-slate-100 px-5 py-4">
        <p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-slate-400">
          Available balance
        </p>
        <p className="mt-1 text-[22px] font-bold tracking-tight text-ink">
          {money(account.balance)}
        </p>
      </div>

      <div className="flex items-center justify-between border-t border-slate-100 bg-slate-50/60 px-5 py-3">
        <button
          type="button"
          onClick={onTransfer}
          className="inline-flex items-center gap-1.5 rounded-lg px-2 py-1.5 text-xs font-semibold text-gold-800 hover:bg-gold-50 transition"
        >
          <Send size={13} /> Transfer
        </button>
        <span className="text-[11px] text-slate-400">
          Acc: <b className="ml-1 font-semibold text-slate-600 font-mono">{account.number}</b>
        </span>
      </div>
    </Panel>
  );
}

export default AccountCard;
