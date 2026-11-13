import React, { useState } from 'react';
import { Menu, Bell } from 'lucide-react';

export function TopBar({
  setView,
  onMenu,
  adminMode,
  unreadCount = 0,
  currentUser,
}) {
  const [notificationsOpen, setNotificationsOpen] = useState(false);

  return (
    <header className="sticky top-0 z-20 flex h-[68px] items-center justify-between border-b border-slate-200/80 bg-canvas px-4 backdrop-blur-md sm:px-7 lg:px-9">
      <div className="flex items-center gap-3">
        <button
          onClick={onMenu}
          aria-label="Open navigation"
          className="rounded-xl border border-slate-200 bg-white p-2 text-ink xl:hidden"
        >
          <Menu size={18} />
        </button>
        <div className="hidden text-[11px] font-medium text-slate-400 sm:block">
          {adminMode ? 'OPERATIONS' : 'YOUR MONEY, IN ONE CLEAR VIEW'}
        </div>
        <div className="text-sm font-bold text-ink xl:hidden">
          {adminMode ? 'Eli Operations' : 'Eli‑Pay'}
        </div>
      </div>
      <div className="flex items-center gap-2 sm:gap-4">
        {!adminMode && (
          <div className="hidden text-right sm:block">
            <div className="text-[11px] text-slate-400">Good morning,</div>
            <div className="text-[12px] font-semibold text-ink">
              {currentUser?.fullName?.split(' ')[0] || 'Customer'}
            </div>
          </div>
        )}
        <div className="relative">
          <button
            onClick={() => setNotificationsOpen(!notificationsOpen)}
            aria-label="Notifications"
            className="relative rounded-xl border border-slate-200 bg-white p-2.5 text-slate-500 transition hover:text-ink"
          >
            <Bell size={17} />
            {unreadCount > 0 && (
              <span className="absolute right-2 top-2 h-1.5 w-1.5 rounded-full bg-rose-500 ring-2 ring-white" />
            )}
          </button>
          {notificationsOpen && (
            <div className="absolute right-0 top-12 z-30 w-72 rounded-2xl border border-slate-200 bg-white p-4 shadow-xl fade-in">
              <strong className="text-sm text-ink">Email updates</strong>
              <p className="mt-3 text-xs leading-5 text-slate-500">
                Transaction and security updates are sent to your verified email address.
              </p>
              <p className="mt-2 break-all text-[11px] font-semibold text-ink">
                {currentUser?.email || 'Sign in to view your delivery address'}
              </p>
              <button
                className="mt-3 text-xs font-semibold text-gold-800"
                onClick={() => {
                  setNotificationsOpen(false);
                  setView('settings');
                }}
              >
                View email settings
              </button>
            </div>
          )}
        </div>
        <button
          onClick={() => setView(adminMode ? 'ops-dashboard' : 'settings')}
          className="grid h-9 w-9 place-items-center rounded-xl border border-slate-200 bg-white text-xs font-bold text-ink shadow-sm"
          title="Profile settings"
        >
          {(currentUser?.fullName || 'E').charAt(0).toUpperCase()}
        </button>
      </div>
    </header>
  );
}

export default TopBar;
