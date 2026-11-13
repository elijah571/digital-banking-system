import React from 'react';
import {
  Activity,
  ArrowRight,
  ArrowUpRight,
  BadgeCheck,
  Bell,
  CircleHelp,
  FileCheck2,
  FileClock,
  Fingerprint,
  Grid2X2,
  LogOut,
  MoreHorizontal,
  Plus,
  ReceiptText,
  Send,
  Settings,
  ShieldAlert,
  Users,
  Wallet,
  X,
} from 'lucide-react';
import { BrandMark } from './BrandMark';

const customerLinks = [
  { label: 'Overview', view: 'overview', icon: Grid2X2 },
  { label: 'My accounts', view: 'accounts', icon: Wallet },
  { label: 'Transfers', view: 'transfers', icon: Send },
  { label: 'Add money', view: 'add-money', icon: Plus },
  { label: 'Withdraw', view: 'withdraw', icon: ArrowUpRight },
  { label: 'Identity verification', view: 'kyc', icon: Fingerprint },
  { label: 'Upgrade account', view: 'account-upgrade', icon: BadgeCheck },
  { label: 'Transactions', view: 'transactions', icon: ReceiptText },
  { label: 'Beneficiaries', view: 'beneficiaries', icon: Users },
  { label: 'Notifications', view: 'notifications', icon: Bell },
];

const adminLinks = [
  { label: 'Dashboard', view: 'ops-dashboard', icon: Grid2X2 },
  { label: 'Payout queue', view: 'payouts', icon: FileClock, badge: 'Queue' },
  { label: 'Risk alerts', view: 'risk-alerts', icon: ShieldAlert },
  { label: 'Audit log', view: 'audit-log', icon: FileCheck2 },
  { label: 'Customers', view: 'customers', icon: Users },
  { label: 'Reconciliation', view: 'reconciliation', icon: Activity },
];

export function Sidebar({
  view,
  setView,
  adminMode,
  setAdminMode,
  open,
  onClose,
  unreadCount = 0,
  currentUser,
  isAdmin = false,
  onLogout,
}) {
  const links = adminMode ? adminLinks : customerLinks;

  const navigate = (nextView) => {
    setView(nextView);
    onClose?.();
  };

  const initials = (currentUser?.fullName || 'U')
    .split(/\s+/)
    .map((part) => part[0])
    .join('')
    .slice(0, 2)
    .toUpperCase();

  return (
    <>
      {open && (
        <button
          className="fixed inset-0 z-30 bg-slate-950/40 xl:hidden"
          onClick={onClose}
          aria-label="Close navigation"
        />
      )}
      <aside
        className={`fixed inset-y-0 left-0 z-40 flex w-[248px] flex-col overflow-y-auto bg-ink px-4 pb-4 pt-5 text-white transition-transform duration-200 xl:sticky xl:top-0 xl:h-screen xl:translate-x-0 ${
          open ? 'translate-x-0' : '-translate-x-full'
        }`}
      >
        <div className="flex items-center justify-between px-2">
          <BrandMark />
          <button
            onClick={onClose}
            className="rounded-lg p-2 text-slate-400 hover:bg-white/10 xl:hidden"
            aria-label="Close navigation"
          >
            <X size={18} />
          </button>
        </div>

        <div className="mt-9 px-3 text-[10px] font-bold uppercase tracking-[0.18em] text-slate-500">
          {adminMode ? 'Operations' : 'Workspace'}
        </div>

        <nav className="mt-2 space-y-1">
          {links.map(({ label, view: linkView, icon: Icon, badge }) => {
            const linkBadge =
              linkView === 'notifications' && unreadCount > 0 ? unreadCount : badge;
            const isActive =
              view === linkView ||
              (linkView === 'transfers' &&
                ['transfer-review', 'verify', 'transfer-status'].includes(view));

            return (
              <button
                key={linkView}
                onClick={() => navigate(linkView)}
                className={`group flex w-full items-center gap-3 rounded-xl border-l-2 px-3 py-2.5 text-left text-[13px] font-medium transition ${
                  isActive
                    ? 'border-brand bg-white/10 text-white shadow-inner'
                    : 'border-transparent text-slate-400 hover:bg-white/[0.06] hover:text-white'
                }`}
              >
                <Icon
                  size={16}
                  strokeWidth={1.8}
                  className={
                    isActive
                      ? 'text-brand'
                      : 'text-slate-400 group-hover:text-slate-200'
                  }
                />
                <span className="flex-1">{label}</span>
                {linkBadge && (
                  <span className="rounded-md bg-rose-400/15 px-1.5 py-0.5 text-[10px] font-bold text-rose-200">
                    {linkBadge}
                  </span>
                )}
              </button>
            );
          })}
        </nav>

        {!adminMode && (
          <>
            <div className="mb-2 mt-8 px-3 text-[10px] font-bold uppercase tracking-[0.18em] text-slate-500">
              Preferences
            </div>
            <button
              onClick={() => navigate('settings')}
              className={`flex w-full items-center gap-3 rounded-xl border-l-2 px-3 py-2.5 text-left text-[13px] font-medium transition ${
                view === 'settings' || view === 'security'
                  ? 'border-brand bg-white/10 text-white'
                  : 'border-transparent text-slate-400 hover:bg-white/[0.06] hover:text-white'
              }`}
            >
              <Settings size={16} strokeWidth={1.8} /> Settings
            </button>
          </>
        )}

        <div className="mt-auto pt-4">
          <button
            onClick={() => navigate('support')}
            className="mb-4 w-full rounded-2xl border border-white/10 bg-white/[0.06] p-3.5 text-left transition hover:bg-white/10"
          >
            <div className="flex items-center gap-2 text-[12px] font-semibold">
              <CircleHelp size={15} className="text-green-300" /> Need a hand?
            </div>
            <p className="mt-1.5 text-[11px] leading-4 text-slate-400">
              Our support team is here when you need us.
            </p>
            <span className="mt-2 inline-flex items-center gap-1 text-[11px] font-semibold text-green-300">
              Visit help centre <ArrowRight size={12} />
            </span>
          </button>

          <div className="flex items-center gap-3 border-t border-white/10 px-2 pt-4">
            <div className="grid h-9 w-9 place-items-center rounded-full bg-slate-800 text-xs font-bold text-white">
              {initials}
            </div>
            <div className="min-w-0 flex-1">
              <div className="truncate text-[12px] font-semibold">
                {currentUser?.fullName || 'Your account'}
              </div>
              <div className="mt-0.5 truncate text-[10px] text-slate-400">
                {currentUser?.email || (adminMode ? 'Administrator' : 'Personal account')}
              </div>
            </div>

            {isAdmin && (
              <button
                title={
                  adminMode
                    ? 'Switch to personal banking'
                    : 'Open operations workspace'
                }
                onClick={() => {
                  setAdminMode(!adminMode);
                  setView(adminMode ? 'overview' : 'ops-dashboard');
                }}
                className="rounded-lg p-1.5 text-slate-400 hover:bg-white/10 hover:text-white"
              >
                <MoreHorizontal size={17} />
              </button>
            )}

            <button
              title="Sign out"
              onClick={onLogout}
              className="rounded-lg p-1.5 text-slate-400 hover:bg-white/10 hover:text-white"
            >
              <LogOut size={16} />
            </button>
          </div>
        </div>
      </aside>
    </>
  );
}

export default Sidebar;
