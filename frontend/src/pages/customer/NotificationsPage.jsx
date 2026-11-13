import React from 'react';
import { ArrowRight, Bell } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { Button } from '../../components/buttons/Button';
import { StatusBadge } from '../../components/feedback/StatusBadge';

export function NotificationsPage({ setView, currentUser }) {
  return (
    <div className="fade-in">
      <PageHeader
        title="Email updates"
        description="Customer banking alerts and security notifications are sent to your verified email address."
      />
      <Panel className="mx-auto max-w-2xl p-6 sm:p-8">
        <div className="flex items-start gap-4">
          <span className="grid h-11 w-11 place-items-center rounded-xl bg-gold-50 text-gold-800">
            <Bell size={19} />
          </span>
          <div>
            <h2 className="text-sm font-bold text-ink">Your delivery address</h2>
            <p className="mt-1 break-all text-xs font-mono text-slate-600">
              {currentUser?.email || '—'}
            </p>
            <div className="mt-2">
              <StatusBadge tone={currentUser?.emailVerified ? 'green' : 'amber'}>
                {currentUser?.emailVerified ? 'Verified' : 'Email verification required'}
              </StatusBadge>
            </div>
          </div>
        </div>

        <p className="mt-5 text-xs leading-5 text-slate-500">
          Instant alerts for payments, deposits, withdrawals, transfers, fraud alerts, password resets, PIN changes, and one-time verification codes are delivered in real time to your registered inbox.
        </p>

        <Button
          variant="secondary"
          className="mt-6"
          onClick={() => setView('settings')}
        >
          Email settings <ArrowRight size={14} />
        </Button>
      </Panel>
    </div>
  );
}

export default NotificationsPage;
