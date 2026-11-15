import React from 'react';
import { Check, Clock3, FileText, X } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { StatusBadge } from '../../components/feedback/StatusBadge';
import { useKycReviewsQuery, useReviewKycMutation } from '../../store/api';

const asset = (value, mime) => value ? (value.startsWith('data:') ? value : `data:${mime};base64,${value}`) : '';

export function KycReviewsPage({ showToast }) {
  const { data = [], isLoading, refetch } = useKycReviewsQuery();
  const [review, result] = useReviewKycMutation();
  const decide = async (item, type, approve) => {
    const reason = approve ? '' : window.prompt('Reason for rejecting this application:');
    if (!approve && !reason?.trim()) return;
    try { await review({ ownerId: item.ownerId, type, approve, reason }).unwrap(); showToast?.('KYC decision saved.', 'success'); }
    catch (error) { showToast?.(error?.data?.detail || 'Could not save the KYC decision.', 'error'); }
  };
  return <div className="fade-in"><PageHeader title="KYC review" description="Review customer identity documents and prompted face recordings before approving verification or account upgrades." action={<Button variant="secondary" onClick={refetch}>Refresh</Button>}/>
    {isLoading ? <Panel className="p-6 text-sm text-slate-500">Loading review queue…</Panel> : data.length === 0 ? <Panel className="p-8 text-center"><Clock3 className="mx-auto text-slate-400"/><p className="mt-3 text-sm text-slate-500">No KYC applications are waiting for review.</p></Panel> : <div className="space-y-5">{data.map((item) => <Panel key={item.ownerId} className="p-5 sm:p-6"><div className="flex flex-wrap items-start justify-between gap-3"><div><h2 className="font-bold text-ink">Customer {item.ownerId}</h2><p className="mt-1 text-xs text-slate-500">Submitted {item.submittedAt ? new Date(item.submittedAt).toLocaleString() : ''}</p></div><div className="flex gap-2">{item.identityStatus === 'PENDING_REVIEW' && <StatusBadge tone="amber">Identity pending</StatusBadge>}{item.upgradeStatus === 'PENDING_REVIEW' && <StatusBadge tone="amber">Upgrade pending</StatusBadge>}</div></div>
      {item.identityStatus === 'PENDING_REVIEW' && <section className="mt-5 rounded-xl border border-slate-200 p-4"><h3 className="font-semibold text-ink">Identity verification · {item.documentType}</h3><Evidence document={item.identityDocumentImage} video={item.identityFaceVideo}/><Decision item={item} type="IDENTITY" decide={decide} busy={result.isLoading}/></section>}
      {item.upgradeStatus === 'PENDING_REVIEW' && <section className="mt-5 rounded-xl border border-slate-200 p-4"><h3 className="font-semibold text-ink">Account upgrade · {item.firstName} {item.lastName}</h3><p className="mt-1 text-xs text-slate-600">BVN {item.maskedBvn} · {item.addressDocumentType}<br/>{item.residentialAddress}</p><Evidence document={item.upgradeDocumentImage} video={item.upgradeFaceVideo}/><Decision item={item} type="UPGRADE" decide={decide} busy={result.isLoading}/></section>}
    </Panel>)}</div>}
  </div>;
}

function Evidence({ document, video }) { return <div className="mt-4 grid gap-4 md:grid-cols-2"><div><p className="mb-2 flex items-center gap-2 text-xs font-semibold"><FileText size={14}/> Submitted document</p>{document ? <a href={asset(document, 'image/jpeg')} target="_blank" rel="noreferrer"><img src={asset(document, 'image/jpeg')} alt="Submitted identity evidence" className="max-h-72 rounded-lg border object-contain"/></a> : <p className="text-xs text-slate-400">No document</p>}</div><div><p className="mb-2 text-xs font-semibold">Prompted face recording</p>{video ? <video controls playsInline className="max-h-72 w-full rounded-lg bg-black"><source src={asset(video, 'video/webm')} type="video/webm"/></video> : <p className="text-xs text-slate-400">No recording</p>}</div></div>; }
function Decision({ item, type, decide, busy }) { return <div className="mt-4 flex gap-2"><Button disabled={busy} onClick={() => decide(item, type, true)}><Check size={15}/>Approve</Button><Button variant="secondary" disabled={busy} onClick={() => decide(item, type, false)}><X size={15}/>Reject</Button></div>; }
