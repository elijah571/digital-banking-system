import React from 'react';
import { ArrowRight, ChevronDown, CircleHelp, ShieldCheck } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';

export function SupportPage({ setView }) {
  return (
    <div className="fade-in">
      <PageHeader
        title="Help centre"
        description="Quick answers and shortcuts to resolve common banking questions."
      />
      <div className="grid gap-4 md:grid-cols-2">
        <Panel className="p-5">
          <span className="grid h-10 w-10 place-items-center rounded-xl bg-gold-50 text-gold-800">
            <CircleHelp size={18} />
          </span>
          <h2 className="mt-4 text-sm font-bold text-ink">Transfer status</h2>
          <p className="mt-1 text-xs leading-5 text-slate-500">
            Check your transaction history for the latest clearing status and receipt downloads.
          </p>
          <Button
            variant="secondary"
            className="mt-4"
            onClick={() => setView('transactions')}
          >
            Open transactions <ArrowRight size={14} />
          </Button>
        </Panel>

        <Panel className="p-5">
          <span className="grid h-10 w-10 place-items-center rounded-xl bg-gold-50 text-gold-800">
            <ShieldCheck size={18} />
          </span>
          <h2 className="mt-4 text-sm font-bold text-ink">Account security</h2>
          <p className="mt-1 text-xs leading-5 text-slate-500">
            Update your account password or change your 4-digit transaction PIN anytime.
          </p>
          <Button
            variant="secondary"
            className="mt-4"
            onClick={() => setView('security')}
          >
            Open security settings <ArrowRight size={14} />
          </Button>
        </Panel>
      </div>

      <Panel className="mt-5 p-5 sm:p-7">
        <h2 className="text-sm font-bold text-ink">Frequently asked questions</h2>
        <div className="mt-3 divide-y divide-slate-100">
          {[
            [
              'How long do transfers take to settle?',
              'Internal transfers between Eli accounts settle instantaneously. External bank transfers via NIP typically clear within seconds, but may occasionally take longer depending on the destination bank clearing switch.',
            ],
            [
              'How do I change my transaction PIN?',
              'Navigate to Preferences > Security & PIN, click "Email me a code", and submit the 6-digit OTP code along with your account password and new 4-digit PIN.',
            ],
            [
              'Where can I download my account statement?',
              'Navigate to Transactions and click "Download statement". A structured CSV file containing ledger lines, entry types, running balances, and reference identifiers will be downloaded.',
            ],
            [
              'How do I upgrade my account limits?',
              'Go to Upgrade Account and provide your 11-digit BVN, address proof, and complete a live selfie verification to unlock Tier 3 limits.',
            ],
          ].map(([question, answer]) => (
            <details key={question} className="group py-4">
              <summary className="flex cursor-pointer list-none items-center justify-between text-xs font-semibold text-ink">
                {question}
                <ChevronDown
                  size={15}
                  className="transition group-open:rotate-180 text-slate-400"
                />
              </summary>
              <p className="mt-2 max-w-2xl text-xs leading-5 text-slate-500">
                {answer}
              </p>
            </details>
          ))}
        </div>
      </Panel>
    </div>
  );
}

export default SupportPage;
