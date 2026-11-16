import React, { useState } from 'react';
import { Check, Plus, RefreshCw, X } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { StatusBadge } from '../../components/feedback/StatusBadge';
import { useAdminCryptoAssetsQuery, useCryptoReviewsQuery, useDecideCryptoReviewMutation, useUpsertCryptoAssetMutation } from '../../store/api';
import { hasPermission, isSuperAdmin } from '../../utils/permissions';

const localTime = (value) => value ? new Date(value).toLocaleString() : '—';

export function CryptoOperationsPage({ showToast, currentUser }) {
  const canReadAssets = hasPermission(currentUser, 'crypto.wallets.read');
  const canApprove = hasPermission(currentUser, 'crypto.operations.approve');
  const canManageAssets = isSuperAdmin(currentUser);
  const { data: assets = [], isError: assetsError, refetch: reloadAssets } = useAdminCryptoAssetsQuery(undefined, { skip: !canReadAssets });
  const { data: reviews = [], isError: reviewsError, refetch: reloadReviews } = useCryptoReviewsQuery(undefined, { skip: !hasPermission(currentUser, 'crypto.transactions.read') });
  const [saveAsset, saveState] = useUpsertCryptoAssetMutation();
  const [decide, decisionState] = useDecideCryptoReviewMutation();
  const [error, setError] = useState('');
  const submitAsset = async (event) => {
    event.preventDefault(); setError(''); const form = event.currentTarget; const fields = new FormData(form);
    const body = {
      assetKey: fields.get('assetKey').trim().toUpperCase(), fireblocksAssetId: fields.get('fireblocksAssetId').trim(),
      networkCode: fields.get('networkCode').trim().toUpperCase(), displaySymbol: fields.get('displaySymbol').trim().toUpperCase(),
      displayName: fields.get('displayName').trim(), contractAddress: fields.get('contractAddress').trim() || null,
      addressMode: fields.get('addressMode'), feeAssetKey: fields.get('feeAssetKey').trim().toUpperCase(),
      decimals: Number(fields.get('decimals')), addressPattern: fields.get('addressPattern').trim() || null,
      confirmationThreshold: Number(fields.get('confirmationThreshold')),
      dailyWithdrawalLimitBaseUnits: fields.get('dailyWithdrawalLimitBaseUnits').trim(),
      manualDepositReview: fields.get('manualDepositReview') === 'on', enabled: fields.get('enabled') === 'on',
    };
    try { await saveAsset(body).unwrap(); form.reset(); showToast?.('Asset configuration saved.', 'success'); }
    catch (failure) { setError(failure?.data?.detail || 'Could not save the asset configuration.'); }
  };
  const review = async (item, approve) => {
    if (approve && !window.confirm(`Approve ${item.direction.toLowerCase()} of ${item.amount} ${item.symbol}?`)) return;
    const reason = approve ? '' : window.prompt('Reason for rejecting this transaction:');
    if (!approve && !reason?.trim()) return;
    try { await decide({ id: item.id, approve, reason }).unwrap(); showToast?.('Crypto review decision saved.', 'success'); }
    catch (failure) { showToast?.(failure?.data?.detail || 'Could not save the decision.', 'error'); }
  };
  return <div className="fade-in">
    <PageHeader title="Crypto operations" description="Configure explicitly supported Fireblocks assets and review flagged withdrawals or confirmed deposits." actions={<Button variant="secondary" onClick={() => { reloadAssets(); reloadReviews(); }}><RefreshCw size={15}/>Refresh</Button>} />
    {error && <p role="alert" className="mb-4 rounded-xl bg-rose-50 p-3 text-sm text-rose-800">{error}</p>}
    {(assetsError || reviewsError) && <p role="alert" className="mb-4 rounded-xl border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">Some crypto operations data could not be loaded. Check administrator access and refresh.</p>}
    <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_minmax(300px,0.8fr)]">
      {canManageAssets && <>
      <Panel className="p-5 sm:p-6"><h2 className="text-base font-bold text-ink">Supported asset and network</h2><p className="mt-1 text-xs leading-5 text-slate-500">Use the exact Fireblocks asset ID and configure the matching contract/network. Enabling requires Fireblocks credentials on the server.</p>
        <form onSubmit={submitAsset} className="mt-4 grid gap-3 sm:grid-cols-2">
          <Input name="assetKey" label="Internal asset key" placeholder="USDC_ETH_TEST" required />
          <Input name="fireblocksAssetId" label="Fireblocks asset ID" placeholder="USDC_ETH_TEST5" required />
          <Input name="networkCode" label="Network code" placeholder="ETH_TEST" required />
          <Input name="displaySymbol" label="Display symbol" placeholder="USDC" required />
          <Input name="displayName" label="Display name" placeholder="USD Coin" required />
          <Input name="contractAddress" label="Token contract (if applicable)" />
          <label className="text-xs font-semibold text-slate-700">Base unit decimals<input name="decimals" type="number" min="0" max="36" required className="mt-1 min-h-10 w-full rounded-lg border border-slate-200 px-3 text-sm" /></label>
          <label className="text-xs font-semibold text-slate-700">Deposit address method<select name="addressMode" className="mt-1 min-h-10 w-full rounded-lg border border-slate-200 bg-white px-3 text-sm"><option value="ACCOUNT">One address per vault asset</option><option value="DERIVED">Generate a new address</option></select></label>
          <Input name="feeAssetKey" label="Network fee asset key" placeholder="ETH_ETH_TEST" required />
          <Input name="addressPattern" label="Java address validation regex" placeholder="^0x[a-fA-F0-9]{40}$" />
          <Input name="confirmationThreshold" label="Required confirmations" type="number" min="1" required />
          <Input name="dailyWithdrawalLimitBaseUnits" label="Daily limit (integer base units)" placeholder="100000000" required />
          <label className="flex items-center gap-2 text-xs text-slate-700"><input name="manualDepositReview" type="checkbox" defaultChecked className="accent-brand"/> Require admin approval before deposit credit</label>
          <label className="flex items-center gap-2 text-xs font-semibold text-slate-700"><input name="enabled" type="checkbox" className="accent-brand"/> Enable this asset/network</label>
          <div className="sm:col-span-2"><Button type="submit" disabled={saveState.isLoading} isLoading={saveState.isLoading} loadingText="Saving configuration"><Plus size={15}/>Save asset configuration</Button></div>
        </form>
      </Panel>
      </>}
      {canReadAssets && <Panel className="p-5 sm:p-6"><h2 className="text-base font-bold text-ink">Configured assets</h2><div className="mt-4 space-y-3">{assets.length ? assets.map((asset) => <div key={asset.assetKey} className="rounded-xl border border-slate-200 p-3"><div className="flex items-start justify-between gap-3"><div><p className="text-sm font-semibold text-ink">{asset.displaySymbol} · {asset.networkCode}</p><p className="mt-1 text-xs text-slate-500">{asset.assetKey} · provider {asset.fireblocksAssetId} · {asset.decimals} decimals</p><p className="mt-1 text-xs text-slate-500">{asset.confirmationThreshold} confirmations · fee asset {asset.feeAssetKey}</p></div><StatusBadge tone={asset.enabled ? 'green' : 'slate'}>{asset.enabled ? 'Enabled' : 'Disabled'}</StatusBadge></div></div>) : <p className="text-sm text-slate-500">No assets configured.</p>}</div></Panel>}
    </div>
    <Panel className="mt-5 overflow-hidden"><div className="border-b border-slate-100 p-5"><h2 className="text-base font-bold text-ink">Manual review queue</h2><p className="mt-1 text-xs text-slate-500">Confirm the asset, destination and requested amount before approving. Approving a withdrawal submits it to Fireblocks.</p></div>
      {reviews.length ? <div className="divide-y divide-slate-100">{reviews.map((item) => <div key={item.id} className="flex flex-col gap-3 p-4"><div className="flex flex-wrap items-start justify-between gap-3"><div><p className="text-sm font-semibold text-ink">{item.direction} · {item.amount} {item.symbol} · {item.networkCode}</p><p className="mt-1 text-xs text-slate-500">Customer {item.userId} · {localTime(item.createdAt)}</p>{item.destinationAddress && <p className="mt-2 break-all font-mono text-xs text-slate-600">Destination: {item.destinationAddress}</p>}{item.destinationTag && <p className="mt-1 text-xs text-slate-500">Tag: {item.destinationTag}</p>}</div><StatusBadge tone="amber">Review required</StatusBadge></div>{canApprove ? <div className="flex gap-2"><Button disabled={decisionState.isLoading} onClick={() => review(item,true)}><Check size={15}/>Approve</Button><Button variant="secondary" disabled={decisionState.isLoading} onClick={() => review(item,false)}><X size={15}/>Reject</Button></div> : <p className="text-xs text-slate-600">Read-only review. Your role cannot approve crypto operations.</p>}</div>)}</div> : <p className="p-6 text-sm text-slate-500">No crypto transactions are waiting for review.</p>}
    </Panel>
  </div>;
}

function Input({ name, label, ...props }) { return <label className="text-xs font-semibold text-slate-700">{label}<input name={name} {...props} className="mt-1 min-h-10 w-full rounded-lg border border-slate-200 px-3 text-sm font-normal" /></label>; }
