import React from 'react';
import { Shield, UserRound } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Field } from '../../components/common/Field';
import { SectionHeading } from '../../components/common/SectionHeading';
import { StatusBadge } from '../../components/feedback/StatusBadge';

export function SettingsPage({ setView, currentUser }) {
  return (
    <div className="fade-in">
      <PageHeader
        title="Settings"
        description="Review your profile, registered identity, and email delivery preferences."
      />
      <div className="grid gap-5 lg:grid-cols-[220px_1fr]">
        <div className="space-y-1">
          {[
            ['Profile information', 'settings', UserRound],
            ['Security & PIN', 'security', Shield],
          ].map(([label, targetView, Icon], i) => (
            <button
              key={`${label}-${i}`}
              onClick={() => setView(targetView)}
              className={`flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-xs font-semibold transition ${
                i === 0
                  ? 'bg-white text-ink shadow-sm'
                  : 'text-slate-500 hover:bg-white hover:text-ink'
              }`}
            >
              <Icon size={15} />
              {label}
            </button>
          ))}
        </div>

        <div className="space-y-4">
          <Panel className="p-5 sm:p-6">
            <SectionHeading title="Personal information" />
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Full name" value={currentUser?.fullName || ''} readOnly />
              <Field label="Email address" type="email" value={currentUser?.email || ''} readOnly />
              <Field label="Account role" value={currentUser?.role || 'CUSTOMER'} readOnly />
            </div>
            <p className="mt-4 text-[11px] text-slate-500">
              To update your legal name or registered email address, please contact customer support.
            </p>
          </Panel>

          <Panel className="p-5 sm:p-6">
            <SectionHeading title="Customer email updates" />
            <p className="text-xs leading-5 text-slate-600">
              Payment, deposit, withdrawal, transfer, fraud, authentication, and one-time-code emails are sent to your verified email address.
            </p>
            <div className="mt-3 flex items-center gap-2">
              <span className="text-[11px] text-slate-500">Verification status:</span>
              <StatusBadge tone={currentUser?.emailVerified ? 'green' : 'amber'}>
                {currentUser?.emailVerified ? 'Verified' : 'Unverified'}
              </StatusBadge>
            </div>
          </Panel>
        </div>
      </div>
    </div>
  );
}

export default SettingsPage;
