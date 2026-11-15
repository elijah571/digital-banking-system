import React, { useState } from 'react';
import { ArrowDownToLine, ArrowUpFromLine, Copy, RefreshCw } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { StatusBadge } from '../../components/feedback/StatusBadge';
import {
  useCryptoAssetsQuery, useCryptoBalancesQuery, useCryptoAddressesQuery, useCryptoTransactionsQuery,
  useCreateCryptoAddressMutation, useCreateCryptoWithdrawalMutation,
} from '../../store/api';

const formatDate = (value) => value ? new Date(value).toLocaleString() : '—';
const tone = (status) => ['COMPLETED'].includes(status) ? 'green' : ['FAILED','REJECTED','CANCELLED'].includes(status) ? 'red' : 'amber';

export function CryptoPage({ showToast }) {
  const { data: assets = [], isLoading: assetsLoading } = useCryptoAssetsQuery();
  const { data: balances = [], refetch: refreshBalances } = useCryptoBalancesQuery();
  const { data: addresses = [], refetch: refreshAddresses } = useCryptoAddressesQuery();
  const { data: transactions = [], refetch: refreshTransactions } = useCryptoTransactionsQuery();
  const [createAddress, addressState] = useCreateCryptoAddressMutation();
  const [withdraw, withdrawalState] = useCreateCryptoWithdrawalMutation();
  const [depositAsset, setDepositAsset] = useState('');
  const [withdrawAsset, setWithdrawAsset] = useState('');
  const [error, setError] = useState('');

  const assetKey = (depositAsset || assets[0]?.assetKey || '');
  const withdrawalKey = (withdrawAsset || assets[0]?.assetKey || '');
  const submitDeposit = async () => {
    if (!assetKey) return;
    try { await createAddress({ assetKey }).unwrap(); setError(''); showToast?.('Deposit address is ready.', 'success'); }
    catch (failure) { setError(failure?.data?.detail || 'Could not create a deposit address.'); }
  };
  const submitWithdrawal = async (event) => {
    event.preventDefault(); setError(''); const form = event.currentTarget;
    const data = new FormData(event.currentTarget);
    try {
      const item = await withdraw({ body: { assetKey: withdrawalKey, amount: data.get('amount'), destinationAddress: data.get('destinationAddress'), destinationTag: data.get('destinationTag') || null, pin: data.get('pin') } }).unwrap();
      form.reset(); showToast?.(`Withdrawal ${item.status.toLowerCase().replaceAll('_',' ')}.`, 'success');
    } catch (failure) { setError(failure?.data?.detail || 'Could not create the withdrawal.'); }
  };
  const refresh = () => { refreshBalances(); refreshTransactions(); refreshAddresses(); };

  return <div className="fade-in">
    <PageHeader title="Crypto" description="Receive and send supported digital assets held in your custodial wallet." action={<Button variant="secondary" onClick={refresh}><RefreshCw size={15}/>Refresh</Button>} />
    {error && <div role="alert" className="mb-4 rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-800">{error}</div>}
    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      {balances.map((balance) => <Panel key={balance.assetKey} className="p-4"><p className="text-xs text-slate-500">{balance.symbol} · {balance.networkCode}</p><p className="mt-2 text-xl font-bold text-ink">{balance.available}</p><p className="mt-1 text-xs text-slate-500">Available · {balance.reserved} reserved</p></Panel>)}
      {!balances.length && <Panel className="p-4 text-sm text-slate-500">Your crypto balances will appear here.</Panel>}
    </div>

    <div className="mt-5 grid gap-5 xl:grid-cols-2">
      <Panel className="p-5 sm:p-6">
        <h2 className="flex items-center gap-2 text-base font-bold text-ink"><ArrowDownToLine size={18}/>Receive crypto</h2>
        <p className="mt-1 text-xs leading-5 text-slate-500">Create a deposit address for one exact asset and network. Sending another network’s asset to it can result in permanent loss.</p>
        <div className="mt-4 flex flex-col gap-3 sm:flex-row">
          <select aria-label="Deposit asset and network" value={assetKey} onChange={(e) => setDepositAsset(e.target.value)} className="min-h-11 flex-1 rounded-xl border border-slate-200 bg-white px-3 text-sm" disabled={assetsLoading || !assets.length}>
            {assets.map((asset) => <option key={asset.assetKey} value={asset.assetKey}>{asset.displaySymbol} · {asset.networkCode}</option>)}
          </select>
          <Button onClick={submitDeposit} disabled={!assetKey || addressState.isLoading} isLoading={addressState.isLoading} loadingText="Preparing address">Create address</Button>
        </div>
        <div className="mt-5 space-y-3">
          {addresses.map((address) => <div key={address.assetKey} className="rounded-xl border border-slate-200 p-3">
            <div className="flex items-center justify-between gap-2"><p className="text-xs font-semibold text-ink">{address.symbol} · {address.networkCode}</p><Button variant="ghost" onClick={() => navigator.clipboard.writeText(address.address).then(() => showToast?.('Address copied.', 'success'))}><Copy size={14}/>Copy</Button></div>
            <p className="mt-2 break-all font-mono text-xs text-slate-600">{address.address}</p>
            {address.tag && <p className="mt-1 text-xs text-slate-500">Memo / tag: {address.tag}</p>}
          </div>)}
        </div>
      </Panel>

      <Panel className="p-5 sm:p-6">
        <h2 className="flex items-center gap-2 text-base font-bold text-ink"><ArrowUpFromLine size={18}/>Send crypto</h2>
        <p className="mt-1 text-xs leading-5 text-slate-500">Withdrawals require your wallet PIN and risk screening. Network fees are accounted for separately.</p>
        {!assets.length ? <p className="mt-5 rounded-lg bg-slate-50 p-4 text-sm text-slate-500">Crypto transfers are not available yet.</p> : <form className="mt-4 space-y-3" onSubmit={submitWithdrawal}>
          <select aria-label="Withdrawal asset and network" value={withdrawalKey} onChange={(e) => setWithdrawAsset(e.target.value)} className="min-h-11 w-full rounded-xl border border-slate-200 bg-white px-3 text-sm">
            {assets.map((asset) => <option key={asset.assetKey} value={asset.assetKey}>{asset.displaySymbol} · {asset.networkCode}</option>)}
          </select>
          <input name="amount" inputMode="decimal" placeholder="Amount" required className="min-h-11 w-full rounded-xl border border-slate-200 px-3 text-sm" />
          <input name="destinationAddress" placeholder="Recipient wallet address" required maxLength={255} className="min-h-11 w-full rounded-xl border border-slate-200 px-3 text-sm" />
          <input name="destinationTag" placeholder="Memo / destination tag, if required" maxLength={255} className="min-h-11 w-full rounded-xl border border-slate-200 px-3 text-sm" />
          <input name="pin" type="password" inputMode="numeric" autoComplete="off" placeholder="4-digit wallet PIN" required pattern="[0-9]{4}" maxLength={4} className="min-h-11 w-full rounded-xl border border-slate-200 px-3 text-sm" />
          <Button type="submit" disabled={withdrawalState.isLoading} isLoading={withdrawalState.isLoading} loadingText="Submitting withdrawal">Review and send</Button>
        </form>}
      </Panel>
    </div>

    <Panel className="mt-5 overflow-hidden"><div className="border-b border-slate-100 p-5"><h2 className="text-base font-bold text-ink">Crypto activity</h2><p className="mt-1 text-xs text-slate-500">Deposits are credited after network confirmations and any required manual review.</p></div>
      {transactions.length ? <div className="divide-y divide-slate-100">{transactions.map((item) => <div key={item.id} className="flex flex-col gap-2 p-4 sm:flex-row sm:items-center sm:justify-between"><div><p className="text-sm font-semibold text-ink">{item.direction === 'DEPOSIT' ? 'Received' : 'Sent'} {item.amount} {item.symbol}</p><p className="mt-1 text-xs text-slate-500">{item.networkCode} · {formatDate(item.createdAt)}{item.confirmations ? ` · ${item.confirmations} confirmations` : ''}</p>{item.txHash && <p className="mt-1 break-all font-mono text-[10px] text-slate-400">{item.txHash}</p>}</div><StatusBadge tone={tone(item.status)}>{item.status.replaceAll('_',' ')}</StatusBadge></div>)}</div> : <p className="p-6 text-sm text-slate-500">No crypto transactions yet.</p>}
    </Panel>
  </div>;
}
