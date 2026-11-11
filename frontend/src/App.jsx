import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Activity,
  ArrowDownLeft,
  ArrowLeft,
  ArrowRight,
  ArrowUpRight,
  BadgeCheck,
  Banknote,
  Bell,
  Check,
  CheckCheck,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  CircleHelp,
  Clock3,
  CreditCard,
  Download,
  Eye,
  FileCheck2,
  FileClock,
  FileText,
  Filter,
  Fingerprint,
  Grid2X2,
  Landmark,
  LockKeyhole,
  Menu,
  MoreHorizontal,
  Plus,
  ReceiptText,
  RefreshCw,
  Search,
  Send,
  Settings,
  Shield,
  ShieldAlert,
  ShieldCheck,
  SlidersHorizontal,
  Sparkles,
  UserRound,
  Users,
  Wallet,
  X,
} from 'lucide-react';
import { banks, initialAccounts, initialBeneficiaries, initialNotifications, initialPayouts, initialTransactions, money } from './data';

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
  { label: 'Payout queue', view: 'payouts', icon: FileClock, badge: '12' },
  { label: 'Risk alerts', view: 'risk-alerts', icon: ShieldAlert, badge: '3' },
  { label: 'Audit log', view: 'audit-log', icon: FileCheck2 },
  { label: 'Customers', view: 'customers', icon: Users },
  { label: 'Reconciliation', view: 'reconciliation', icon: Activity },
];

const viewTitles = {
  overview: 'Overview',
  accounts: 'My accounts',
  transfers: 'Transfers',
  'transfer-review': 'Review transfer',
  'add-money': 'Add money',
  withdraw: 'Withdraw',
  kyc: 'Identity verification',
  'account-upgrade': 'Upgrade account',
  transactions: 'Transactions',
  verify: 'Verify transfer',
  'transfer-status': 'Transfer status',
  settings: 'Settings',
  security: 'Security',
  'account-open': 'Open an account',
  support: 'Help & support',
  beneficiaries: 'Beneficiaries',
  notifications: 'Notifications',
  payouts: 'Payout queue',
  'ops-dashboard': 'Operations overview',
  'risk-alerts': 'Risk alerts',
  'audit-log': 'Audit log',
  customers: 'Customers',
  reconciliation: 'Reconciliation',
};

function Button({ children, variant = 'primary', className = '', ...props }) {
  const styles = {
    primary: 'bg-brand text-ink shadow-sm hover:bg-gold-600 focus-visible:ring-brand',
    secondary: 'border border-slate-200 bg-white text-ink hover:border-slate-300 hover:bg-slate-50 focus-visible:ring-brand',
    dark: 'bg-ink text-white hover:bg-slate-800 focus-visible:ring-brand',
    subtle: 'text-slate-600 hover:bg-slate-100 hover:text-ink focus-visible:ring-brand',
    danger: 'bg-rose-600 text-white hover:bg-rose-700 focus-visible:ring-brand',
  };
  return (
    <button
      className={`inline-flex min-h-10 items-center justify-center gap-2 rounded-xl px-4 py-2.5 text-sm font-semibold transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50 ${styles[variant]} ${className}`}
      {...props}
    >
      {children}
    </button>
  );
}

function Panel({ children, className = '', ...props }) {
  const surface = /\bbg-(?!white(?:\s|$))\S+/.test(className) ? '' : 'bg-white';
  return <section className={`rounded-2xl border border-slate-200/80 ${surface} shadow-panel ${className}`} {...props}>{children}</section>;
}

function PageHeader({ eyebrow, title, description, actions }) {
  return (
    <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div>
        {eyebrow && <p className="mb-1 text-[11px] font-bold uppercase tracking-[0.16em] text-gold-800">{eyebrow}</p>}
        <h1 className="text-[26px] font-bold tracking-[-0.035em] text-ink sm:text-[30px]">{title}</h1>
        {description && <p className="mt-1.5 max-w-2xl text-sm leading-6 text-slate-500">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

function Field({ label, hint, className = '', as, inputClassName = '', children, ...inputProps }) {
  return (
    <label className={`block ${className}`}>
      <span className="mb-2 block text-[13px] font-semibold text-slate-700">{label}</span>
      {as === 'select' ? (
        <select {...inputProps} className={`form-control ${inputClassName}`}>
          {children}
        </select>
      ) : as === 'textarea' ? (
        <textarea {...inputProps} className={`form-control min-h-24 resize-y ${inputClassName}`} />
      ) : (
        <input {...inputProps} className={`form-control ${inputClassName}`} />
      )}
      {hint && <span className="mt-1.5 block text-xs text-slate-400">{hint}</span>}
    </label>
  );
}

function Status({ children, tone }) {
  const statusTone = tone || (children === 'Success' || children === 'Approved' || children === 'Active' ? 'green' : children === 'High' || children === 'Blocked' ? 'red' : 'amber');
  const colors = {
    green: 'bg-green-50 text-green-700 ring-green-600/10',
    amber: 'bg-amber-50 text-amber-700 ring-amber-600/10',
    red: 'bg-rose-50 text-rose-700 ring-rose-600/10',
    gold: 'bg-gold-50 text-gold-700 ring-gold-600/10',
    slate: 'bg-slate-100 text-slate-600 ring-slate-500/10',
  };
  return <span className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-[11px] font-semibold ring-1 ring-inset ${colors[statusTone]}`}>{children}</span>;
}

function BrandMark({ compact = false }) {
  return (
    <div className="flex items-center gap-3">
      <div className="grid h-9 w-9 place-items-center rounded-xl bg-brand text-base font-extrabold text-ink shadow-sm">E</div>
      {!compact && (
        <div className="leading-tight">
          <div className="text-[12px] font-bold tracking-[0.12em] text-white">ELI</div>
          <div className="mt-0.5 text-[9px] font-medium tracking-[0.17em] text-slate-400">DIGITAL BANK</div>
        </div>
      )}
    </div>
  );
}

function Sidebar({ view, setView, adminMode, setAdminMode, open, onClose, unreadCount }) {
  const links = adminMode ? adminLinks : customerLinks;
  const navigate = (nextView) => {
    setView(nextView);
    onClose?.();
  };
  return (
    <>
      {open && <button className="fixed inset-0 z-30 bg-slate-950/40 xl:hidden" onClick={onClose} aria-label="Close navigation" />}
      <aside className={`fixed inset-y-0 left-0 z-40 flex w-[248px] flex-col overflow-y-auto bg-ink px-4 pb-4 pt-5 text-white transition-transform duration-200 xl:sticky xl:top-0 xl:h-screen xl:translate-x-0 ${open ? 'translate-x-0' : '-translate-x-full'}`}>
        <div className="flex items-center justify-between px-2">
          <BrandMark />
          <button onClick={onClose} className="rounded-lg p-2 text-slate-400 hover:bg-white/10 xl:hidden" aria-label="Close navigation"><X size={18} /></button>
        </div>
        <div className="mt-9 px-3 text-[10px] font-bold uppercase tracking-[0.18em] text-slate-500">{adminMode ? 'Operations' : 'Workspace'}</div>
        <nav className="mt-2 space-y-1">
          {links.map(({ label, view: linkView, icon: Icon, badge }) => {
            const linkBadge = linkView === 'notifications' && unreadCount > 0 ? unreadCount : badge;
            return (
            <button key={linkView} onClick={() => navigate(linkView)} className={`group flex w-full items-center gap-3 rounded-xl border-l-2 px-3 py-2.5 text-left text-[13px] font-medium transition ${view === linkView || (linkView === 'transfers' && ['transfer-review', 'verify', 'transfer-status'].includes(view)) ? 'border-brand bg-white/10 text-white shadow-inner' : 'border-transparent text-slate-400 hover:bg-white/[0.06] hover:text-white'}`}>
              <Icon size={16} strokeWidth={1.8} className={view === linkView ? 'text-brand' : 'text-slate-400 group-hover:text-slate-200'} />
              <span className="flex-1">{label}</span>
              {linkBadge && <span className="rounded-md bg-rose-400/15 px-1.5 py-0.5 text-[10px] font-bold text-rose-200">{linkBadge}</span>}
            </button>
            );
          })}
        </nav>
        {!adminMode && (
          <>
            <div className="mb-2 mt-8 px-3 text-[10px] font-bold uppercase tracking-[0.18em] text-slate-500">Preferences</div>
            <button onClick={() => navigate('settings')} className={`flex w-full items-center gap-3 rounded-xl border-l-2 px-3 py-2.5 text-left text-[13px] font-medium ${view === 'settings' || view === 'security' ? 'border-brand bg-white/10 text-white' : 'border-transparent text-slate-400 hover:bg-white/[0.06] hover:text-white'}`}>
              <Settings size={16} strokeWidth={1.8} /> Settings
            </button>
          </>
        )}
        <div className="mt-auto">
          <button onClick={() => navigate('support')} className="mb-4 w-full rounded-2xl border border-white/10 bg-white/[0.06] p-3.5 text-left transition hover:bg-white/10">
            <div className="flex items-center gap-2 text-[12px] font-semibold"><CircleHelp size={15} className="text-green-300" /> Need a hand?</div>
            <p className="mt-1.5 text-[11px] leading-4 text-slate-400">Our support team is here when you need us.</p>
            <span className="mt-2 inline-flex items-center gap-1 text-[11px] font-semibold text-green-300">Visit help centre <ArrowRight size={12} /></span>
          </button>
          <div className="flex items-center gap-3 border-t border-white/10 px-2 pt-4">
            <div className="grid h-9 w-9 place-items-center rounded-full bg-slate-800 text-xs font-bold text-white">{adminMode ? 'EO' : 'E'}</div>
            <div className="min-w-0 flex-1">
              <div className="truncate text-[12px] font-semibold">{adminMode ? 'Eli Operations' : 'Elijah Okafor'}</div>
              <div className="mt-0.5 text-[10px] text-slate-400">{adminMode ? 'Administrator' : 'Personal account'}</div>
            </div>
            <button title={adminMode ? 'Switch to personal banking' : 'Open operations workspace'} onClick={() => { setAdminMode(!adminMode); setView(adminMode ? 'overview' : 'ops-dashboard'); }} className="rounded-lg p-1.5 text-slate-400 hover:bg-white/10 hover:text-white">
              <MoreHorizontal size={17} />
            </button>
          </div>
        </div>
      </aside>
    </>
  );
}

function TopBar({ setView, onMenu, adminMode, notifications, unreadCount }) {
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  return (
    <header className="sticky top-0 z-20 flex h-[68px] items-center justify-between border-b border-slate-200/80 bg-canvas px-4 backdrop-blur-md sm:px-7 lg:px-9">
      <div className="flex items-center gap-3">
        <button onClick={onMenu} aria-label="Open navigation" className="rounded-xl border border-slate-200 bg-white p-2 text-ink xl:hidden"><Menu size={18} /></button>
        <div className="hidden text-[11px] font-medium text-slate-400 sm:block">{adminMode ? 'OPERATIONS' : 'YOUR MONEY, IN ONE CLEAR VIEW'}</div>
        <div className="text-sm font-bold text-ink xl:hidden">{adminMode ? 'Eli Operations' : 'Eli Digital Bank'}</div>
      </div>
      <div className="flex items-center gap-2 sm:gap-4">
        {!adminMode && <div className="hidden text-right sm:block"><div className="text-[11px] text-slate-400">Good morning,</div><div className="text-[12px] font-semibold text-ink">Elijah</div></div>}
        <div className="relative">
          <button onClick={() => setNotificationsOpen(!notificationsOpen)} aria-label="Notifications" className="relative rounded-xl border border-slate-200 bg-white p-2.5 text-slate-500 transition hover:text-ink">
            <Bell size={17} />{unreadCount > 0 && <span className="absolute right-2 top-2 h-1.5 w-1.5 rounded-full bg-rose-500 ring-2 ring-white" />}
          </button>
          {notificationsOpen && <div className="absolute right-0 top-12 z-30 w-72 rounded-2xl border border-slate-200 bg-white p-4 shadow-xl"><div className="flex items-center justify-between"><strong className="text-sm text-ink">Notifications</strong><span className="text-[10px] font-semibold text-gold-800">{unreadCount} unread</span></div><div className="mt-3 divide-y divide-slate-100">{notifications.slice(0, 2).map((notification) => <div key={notification.id} className="py-2 text-xs leading-5 text-slate-500"><span className="block font-semibold text-ink">{notification.title}</span><span>{notification.detail}</span></div>)}</div><button className="mt-3 text-xs font-semibold text-gold-800" onClick={() => { setNotificationsOpen(false); setView('notifications'); }}>View all notifications</button></div>}
        </div>
        <button onClick={() => setView(adminMode ? 'ops-dashboard' : 'settings')} className="grid h-9 w-9 place-items-center rounded-xl border border-slate-200 bg-white text-xs font-bold text-ink shadow-sm">E</button>
      </div>
    </header>
  );
}

function Metric({ label, value, change, icon: Icon, color = 'gold' }) {
  const palettes = { gold: 'bg-gold-50 text-gold-800', green: 'bg-green-50 text-green-700', amber: 'bg-amber-50 text-amber-700' };
  return (
    <Panel className="p-5">
      <div className="flex items-start justify-between">
        <div><p className="text-xs font-medium text-slate-500">{label}</p><p className="mt-2 text-[23px] font-bold tracking-tight text-ink">{value}</p>{change && <p className="mt-1 text-[11px] text-green-700">{change}</p>}</div>
        <div className={`rounded-xl p-2.5 ${palettes[color]}`}><Icon size={18} /></div>
      </div>
    </Panel>
  );
}

function SectionHeading({ title, action, onAction }) {
  return <div className="mb-4 flex items-center justify-between"><h2 className="text-sm font-bold text-ink">{title}</h2>{action && <button onClick={onAction} className="text-xs font-semibold text-gold-800 hover:text-gold-900">{action}</button>}</div>;
}

function AccountCard({ account, onTransfer }) {
  const isGold = account.accent === 'gold';
  return (
    <Panel className="overflow-hidden">
      <div className="flex items-start justify-between p-5">
        <div className="flex items-center gap-3">
          <div className={`grid h-10 w-10 place-items-center rounded-xl ${isGold ? 'bg-gold-50 text-gold-800' : 'bg-gold-50 text-gold-700'}`}>{isGold ? <Landmark size={18} /> : <Wallet size={18} />}</div>
          <div><h3 className="text-sm font-bold text-ink">{account.name}</h3><p className="mt-0.5 text-xs text-slate-400">{account.type} · •••• {account.lastFour}</p></div>
        </div>
        <Status tone="green">{account.status}</Status>
      </div>
      <div className="border-t border-slate-100 px-5 py-4">
        <p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-slate-400">Available balance</p>
        <p className="mt-1 text-[22px] font-bold tracking-tight text-ink">{money(account.balance)}</p>
      </div>
      <div className="flex items-center gap-2 border-t border-slate-100 bg-slate-50/60 px-5 py-3">
        <button onClick={onTransfer} className="inline-flex items-center gap-1.5 rounded-lg px-2 py-1.5 text-xs font-semibold text-gold-800 hover:bg-gold-50"><Send size={13} /> Transfer</button>
        <span className="h-4 w-px bg-slate-200" />
        <span className="text-[11px] text-slate-400">Account number <b className="ml-1 font-semibold text-slate-600">{account.number}</b></span>
      </div>
    </Panel>
  );
}

function ActivityChart() {
  const bars = [28, 38, 31, 52, 43, 62, 49, 74, 56, 83, 68, 96];
  return (
    <div className="mt-4">
      <div className="flex h-[150px] items-end gap-2 border-b border-slate-100 pb-2 sm:gap-3">
        {bars.map((height, index) => <div key={index} className="group relative flex h-full flex-1 items-end justify-center"><div className={`w-full max-w-8 rounded-t-md transition ${index === 9 ? 'bg-brand' : 'bg-gold-100 group-hover:bg-gold-200'}`} style={{ height: `${height}%` }} /></div>)}
      </div>
      <div className="mt-2 flex justify-between text-[10px] text-slate-400"><span>Jun</span><span>Jul</span><span>Aug</span><span>Sep</span><span>Oct</span><span>Nov</span></div>
    </div>
  );
}

function TransactionTable({ rows, onSelect, compact = false }) {
  const typeIcon = (type) => type === 'Deposit' ? ArrowDownLeft : type === 'Withdrawal' ? ArrowUpRight : type === 'Payment' ? CreditCard : Send;
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[650px] text-left">
        <thead><tr className="border-b border-slate-100 text-[10px] font-bold uppercase tracking-[0.11em] text-slate-400"><th className="px-5 py-3">Description</th><th className="px-4 py-3">Reference</th><th className="px-4 py-3">Type</th><th className="px-4 py-3">Amount</th><th className="px-4 py-3">Status</th><th className="px-5 py-3" /></tr></thead>
        <tbody>
          {rows.slice(0, compact ? 4 : undefined).map((row) => {
            const Icon = typeIcon(row.type);
            return <tr key={row.id} onClick={() => onSelect?.(row)} className="cursor-pointer border-b border-slate-50 last:border-0 hover:bg-slate-50/80">
              <td className="px-5 py-3.5"><div className="flex items-center gap-3"><span className={`grid h-9 w-9 shrink-0 place-items-center rounded-xl ${row.amount > 0 ? 'bg-green-50 text-green-700' : 'bg-slate-100 text-slate-600'}`}><Icon size={15} /></span><span><span className="block text-xs font-semibold text-ink">{row.title}</span><span className="mt-1 block text-[10px] text-slate-400">{row.subtitle}</span></span></div></td>
              <td className="px-4 py-3.5 text-[11px] font-medium text-slate-500">{row.id}</td>
              <td className="px-4 py-3.5 text-xs text-slate-500">{row.type}</td>
              <td className={`whitespace-nowrap px-4 py-3.5 text-xs font-bold ${row.amount > 0 ? 'text-green-700' : 'text-ink'}`}>{row.amount > 0 ? '+' : '−'}{money(Math.abs(row.amount))}</td>
              <td className="px-4 py-3.5"><Status>{row.status}</Status></td>
              <td className="px-5 py-3.5 text-right text-slate-400"><ChevronRight size={15} /></td>
            </tr>;
          })}
          {rows.length === 0 && <tr><td colSpan="6" className="px-5 py-12 text-center text-sm text-slate-400">No transactions match these filters.</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

function Overview({ accounts, transactions, setView }) {
  const total = accounts.reduce((sum, account) => sum + account.balance, 0);
  return (
    <div className="fade-in">
      <PageHeader title="Your money, in one clear view" description="Here’s what’s happening across your accounts." actions={<Button variant="secondary" onClick={() => setView('transactions')}><Download size={15} /> Statement</Button>} />
      <div className="grid gap-4 lg:grid-cols-[1.45fr_0.8fr]">
        <div className="space-y-4">
          <Panel className="relative overflow-hidden bg-ink p-6 text-white sm:p-7">
            <div className="absolute -right-8 -top-20 h-56 w-56 rounded-full border border-white/[0.08]" /><div className="absolute -right-1 -top-12 h-44 w-44 rounded-full border border-white/[0.08]" />
            <div className="relative flex items-start justify-between">
              <div><div className="flex items-center gap-2 text-[11px] font-medium text-slate-300"><Eye size={14} /> TOTAL AVAILABLE BALANCE</div><div className="mt-3 text-[32px] font-bold tracking-tight sm:text-[38px]">{money(total)}</div><div className="mt-2 flex items-center gap-2 text-[11px] text-slate-400"><span className="rounded-md bg-green-400/15 px-2 py-1 font-semibold text-green-300">↑ 8.6%</span> compared to last month</div></div>
              <span className="hidden rounded-lg border border-white/10 bg-white/[0.06] px-3 py-1.5 text-[10px] font-semibold text-slate-300 sm:inline-flex">NGN · Nigerian naira</span>
            </div>
            <div className="relative mt-7 flex flex-wrap gap-2">
              <Button className="min-h-9" onClick={() => setView('transfers')}><Send size={14} /> Transfer</Button>
              <Button className="min-h-9 border border-white/15 bg-white/10 text-white hover:bg-white/15" onClick={() => setView('add-money')}><Plus size={14} /> Add money</Button>
              <Button className="min-h-9 border border-white/15 bg-white/10 text-white hover:bg-white/15" onClick={() => setView('withdraw')}><ArrowUpRight size={14} /> Withdraw</Button>
            </div>
          </Panel>
          <div className="grid gap-4 md:grid-cols-2">
            {accounts.map((account) => <AccountCard key={account.id} account={account} onTransfer={() => setView('transfers')} />)}
          </div>
          <Panel className="p-5">
            <SectionHeading title="Monthly snapshot" action="View transactions" onAction={() => setView('transactions')} />
            <div className="grid grid-cols-3 gap-3">
              {[['Money in', '₦1,420,000', 'green'], ['Money out', '₦386,500', 'neutral'], ['Net change', '+₦1,033,500', 'green']].map(([label, amount, tone]) => <div key={label} className="rounded-xl bg-slate-50 p-3 sm:p-4"><div className="text-[10px] text-slate-500 sm:text-xs">{label}</div><div className={`mt-1.5 text-[14px] font-bold sm:text-[17px] ${tone === 'green' ? 'text-green-700' : 'text-ink'}`}>{amount}</div><div className="mt-1 text-[9px] text-slate-400">This month</div></div>)}
            </div>
          </Panel>
        </div>
        <div className="space-y-4">
          <Panel className="p-5">
            <SectionHeading title="Spending activity" action="Last 6 months" />
            <div className="flex items-end justify-between"><div><p className="text-[10px] text-slate-400">Total outflow</p><p className="mt-1 text-xl font-bold text-ink">₦386,500</p></div><span className="rounded-lg bg-green-50 px-2 py-1 text-[10px] font-semibold text-green-700">↓ 4.2%</span></div>
            <ActivityChart />
          </Panel>
          <Panel className="p-5">
            <SectionHeading title="Recent activity" action="View all" onAction={() => setView('transactions')} />
            <div className="space-y-1">
              {transactions.slice(0, 4).map((tx) => <div key={tx.id} className="flex items-center gap-3 rounded-xl py-2.5">
                <span className={`grid h-9 w-9 shrink-0 place-items-center rounded-xl ${tx.amount > 0 ? 'bg-green-50 text-green-700' : 'bg-slate-100 text-slate-600'}`}>{tx.amount > 0 ? <ArrowDownLeft size={16} /> : <ArrowUpRight size={16} />}</span>
                <div className="min-w-0 flex-1"><div className="truncate text-xs font-semibold text-ink">{tx.title}</div><div className="mt-1 text-[10px] text-slate-400">{tx.subtitle}</div></div>
                <div className={`whitespace-nowrap text-xs font-bold ${tx.amount > 0 ? 'text-green-700' : 'text-ink'}`}>{tx.amount > 0 ? '+' : '−'}{money(Math.abs(tx.amount))}</div>
              </div>)}
            </div>
          </Panel>
          <div className="rounded-2xl border border-gold-100 bg-gold-50/80 p-4">
            <div className="flex gap-3"><span className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-white text-gold-800"><ShieldCheck size={17} /></span><div><h3 className="text-xs font-bold text-ink">Your money is protected</h3><p className="mt-1 text-[11px] leading-5 text-slate-500">Every transfer is secured with your PIN and real-time monitoring.</p></div></div>
          </div>
        </div>
      </div>
    </div>
  );
}

function Accounts({ accounts, setView, setOpenAccount }) {
  const total = accounts.reduce((sum, account) => sum + account.balance, 0);
  return (
    <div className="fade-in">
      <PageHeader title="My accounts" description="Manage your accounts and see where your money lives." actions={<><Button variant="secondary" onClick={() => setView('account-upgrade')}><ArrowUpRight size={15} /> Upgrade account</Button><Button onClick={() => setOpenAccount(true)}><Plus size={16} /> Open account</Button></>} />
      <Panel className="mb-5 flex flex-wrap items-center justify-between gap-4 bg-ink p-5 text-white sm:px-7">
        <div><p className="text-[11px] font-medium text-slate-300">TOTAL ACCOUNT BALANCE</p><p className="mt-1.5 text-[27px] font-bold tracking-tight">{money(total)}</p></div><div className="text-right"><p className="text-[11px] text-slate-400">{accounts.length} accounts</p><p className="mt-1 text-xs text-green-300">All accounts active</p></div>
      </Panel>
      <div className="mb-5 flex flex-col gap-4 rounded-2xl border border-gold-100 bg-gold-50/70 p-5 sm:flex-row sm:items-center sm:justify-between sm:p-6">
        <div className="flex items-start gap-3"><span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-white text-gold-800 shadow-sm"><ArrowUpRight size={18} /></span><div><h2 className="text-sm font-bold text-ink">Need higher transaction limits?</h2><p className="mt-1 max-w-2xl text-xs leading-5 text-slate-600">Upgrade your account with BVN, proof-of-address, and live face verification.</p></div></div>
        <Button className="shrink-0" onClick={() => setView('account-upgrade')}>Upgrade account <ArrowRight size={15} /></Button>
      </div>
      <div className="grid gap-4 lg:grid-cols-2">{accounts.map((account) => <AccountCard key={account.id} account={account} onTransfer={() => setView('transfers')} />)}</div>
      <Panel className="mt-5 p-5">
        <SectionHeading title="Account details" />
        <div className="grid gap-4 sm:grid-cols-3">
          {[['Account holder', 'Elijah Okafor'], ['Bank', 'Eli Digital Bank'], ['Account type', 'Personal · NGN']].map(([label, value]) => <div key={label} className="border-t border-slate-100 pt-3"><p className="text-[10px] text-slate-400">{label}</p><p className="mt-1 text-xs font-semibold text-slate-700">{value}</p></div>)}
        </div>
      </Panel>
    </div>
  );
}

function TransferForm({ accounts, transfer, setTransfer, onSubmit, onEnquiry }) {
  const update = (key, value) => setTransfer((old) => ({ ...old, [key]: value }));
  const [enquiryDone, setEnquiryDone] = useState(false);
  const enquiry = () => { setEnquiryDone(true); update('recipientName', 'Adaeze Okafor'); onEnquiry?.(); };
  return (
    <div className="fade-in">
      <PageHeader title="Send money" description="Move money to another Eli customer or to a bank account." />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.35fr)_minmax(260px,0.65fr)]">
        <Panel className="p-5 sm:p-7">
          <div className="mb-6 flex items-center gap-3">
            {[['1', 'Recipient'], ['2', 'Amount'], ['3', 'Review']].map(([num, label], index) => <div key={num} className="flex items-center gap-2"><span className={`grid h-7 w-7 place-items-center rounded-full text-[11px] font-bold ${index === 0 ? 'bg-brand text-ink' : 'bg-slate-100 text-slate-400'}`}>{num}</span><span className={`text-xs font-semibold ${index === 0 ? 'text-ink' : 'text-slate-400'}`}>{label}</span>{index < 2 && <span className="mx-1 h-px w-5 bg-slate-200 sm:w-9" />}</div>)}
          </div>
          <form onSubmit={onSubmit} className="space-y-4">
            <Field label="Transfer type" as="select" value={transfer.type} onChange={(e) => { update('type', e.target.value); setEnquiryDone(false); }}>
              <option value="Bank transfer">To another bank</option><option value="Internal transfer">To Eli Digital Bank</option>
            </Field>
            {transfer.type === 'Bank transfer' && <Field label="Destination bank" as="select" value={transfer.bank} onChange={(e) => update('bank', e.target.value)}><option value="">Select a bank</option>{banks.map((bank) => <option key={bank}>{bank}</option>)}</Field>}
            <div className="grid gap-3 sm:grid-cols-[1fr_auto] sm:items-end">
              <Field label="Account number" placeholder="Enter 10-digit account number" inputMode="numeric" maxLength="10" value={transfer.accountNumber} onChange={(e) => { update('accountNumber', e.target.value.replace(/\D/g, '')); setEnquiryDone(false); update('recipientName', ''); }} required />
              <Button type="button" variant="secondary" className="h-[44px]" onClick={enquiry} disabled={transfer.accountNumber.length !== 10 || (transfer.type === 'Bank transfer' && !transfer.bank)}>Verify account</Button>
            </div>
            {enquiryDone && <div className="flex items-center gap-2 rounded-xl border border-green-100 bg-green-50 px-3.5 py-3 text-xs font-semibold text-green-800"><BadgeCheck size={16} /> Account verified · {transfer.recipientName}</div>}
            <Field label="From account" as="select" value={transfer.accountId} onChange={(e) => update('accountId', e.target.value)}>{accounts.map((account) => <option key={account.id} value={account.id}>{account.name} · •••• {account.lastFour} · {money(account.balance)}</option>)}</Field>
            <Field label="Amount (NGN)" type="number" placeholder="0.00" min="100" step="100" value={transfer.amount} onChange={(e) => update('amount', e.target.value)} required hint="Minimum transfer ₦100.00" />
            <Field label="Description (optional)" placeholder="What is this transfer for?" value={transfer.note} onChange={(e) => update('note', e.target.value)} />
            <Button type="submit" className="mt-2 w-full">Continue to amount <ArrowRight size={15} /></Button>
          </form>
        </Panel>
        <div className="space-y-4">
          <Panel className="p-5"><div className="flex items-center gap-2"><ShieldCheck size={17} className="text-green-600" /><h3 className="text-sm font-bold text-ink">Safe transfers</h3></div><ul className="mt-4 space-y-3">{['Recipient name is verified', 'Your PIN protects every transfer', 'Some transfers may need OTP'].map((item) => <li key={item} className="flex items-start gap-2 text-xs text-slate-600"><Check size={14} className="mt-0.5 shrink-0 text-green-600" />{item}</li>)}</ul></Panel>
          <Panel className="p-5"><div className="flex items-center gap-2"><Clock3 size={16} className="text-gold-800" /><h3 className="text-sm font-bold text-ink">Transfer limits</h3></div><div className="mt-4 space-y-3 text-xs"><div className="flex justify-between"><span className="text-slate-500">Daily limit</span><b className="text-ink">₦5,000,000</b></div><div className="flex justify-between"><span className="text-slate-500">Used today</span><b className="text-ink">₦175,000</b></div><div className="h-1.5 overflow-hidden rounded-full bg-slate-100"><div className="h-full w-[8%] rounded-full bg-brand" /></div></div></Panel>
          <div className="rounded-2xl border border-slate-200 bg-white p-4 text-[11px] leading-5 text-slate-500">Always confirm the recipient’s name before you send. Eli will never ask you to share your PIN or one-time code.</div>
        </div>
      </div>
    </div>
  );
}

function TransferReview({ transfer, accounts, onConfirm, onBack, onFlag }) {
  const source = accounts.find((account) => account.id === transfer.accountId) || accounts[0];
  const entries = [['Recipient', transfer.recipientName || 'Adaeze Okafor'], ['Bank', transfer.bank || 'Eli Digital Bank'], ['Account number', transfer.accountNumber || '4821904821'], ['From account', `${source?.name} · •••• ${source?.lastFour}`], ['Transfer fee', '₦0.00'], ['Reference', 'Will be generated']];
  return (
    <div className="fade-in">
      <PageHeader title="Review transfer" description="Check the details carefully before authorizing your payment." actions={<Button variant="secondary" onClick={onBack}><ArrowLeft size={15} /> Edit details</Button>} />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.25fr)_minmax(270px,0.75fr)]">
        <Panel className="p-5 sm:p-7">
          <div className="rounded-2xl border border-slate-200 bg-slate-50/80 p-5 text-center">
            <p className="text-[10px] font-bold uppercase tracking-[0.13em] text-slate-400">You’re sending</p>
            <p className="mt-2 text-[32px] font-bold tracking-tight text-ink">{money(Number(transfer.amount) || 0)}</p>
            <p className="mt-1 text-xs text-slate-500">{transfer.note || 'Personal transfer'}</p>
          </div>
          <div className="mt-5 divide-y divide-slate-100">
            {entries.map(([label, value]) => <div key={label} className="flex items-center justify-between gap-3 py-3"><span className="text-xs text-slate-500">{label}</span><span className="text-right text-xs font-semibold text-ink">{value}</span></div>)}
          </div>
          <div className="mt-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-xs leading-5 text-amber-800">You’ll be asked to enter your transaction PIN to complete this transfer.</div>
          <Button className="mt-4 w-full" onClick={onConfirm}>Confirm and enter PIN <ArrowRight size={15} /></Button>
          <button onClick={onFlag} className="mt-3 w-full text-center text-[11px] font-semibold text-slate-400 hover:text-gold-800">Preview additional verification step</button>
        </Panel>
        <div className="space-y-4">
          <Panel className="p-5"><div className="flex items-center gap-2"><LockKeyhole size={17} className="text-gold-800" /><h3 className="text-sm font-bold text-ink">Security check</h3></div><p className="mt-3 text-xs leading-5 text-slate-500">This transfer is protected by your PIN and real-time fraud checks.</p><Status tone="green"><Check size={12} /> Protected</Status></Panel>
          <Panel className="p-5"><h3 className="text-sm font-bold text-ink">Need to change something?</h3><p className="mt-2 text-xs leading-5 text-slate-500">Go back to review the recipient or amount before you continue.</p><button onClick={onBack} className="mt-3 inline-flex items-center gap-1 text-xs font-semibold text-gold-800">Edit transfer <ArrowRight size={13} /></button></Panel>
        </div>
      </div>
    </div>
  );
}

function AddMoney({ accounts, deposit, setDeposit, onSubmit }) {
  return (
    <div className="fade-in">
      <PageHeader title="Add money" description="Top up your account securely with a debit or credit card." />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.3fr)_minmax(260px,0.7fr)]">
        <Panel className="p-5 sm:p-7">
          <form onSubmit={onSubmit} className="space-y-5">
            <Field label="Credit to" as="select" value={deposit.accountId} onChange={(e) => setDeposit((old) => ({ ...old, accountId: e.target.value }))}>{accounts.map((account) => <option key={account.id} value={account.id}>{account.name} · •••• {account.lastFour}</option>)}</Field>
            <Field label="Amount (NGN)" type="number" min="1000" max="5000000" step="500" placeholder="50,000.00" value={deposit.amount} onChange={(e) => setDeposit((old) => ({ ...old, amount: e.target.value }))} required />
            <div><p className="mb-2 text-[13px] font-semibold text-slate-700">Quick amounts</p><div className="grid grid-cols-4 gap-2">{['₦5,000', '₦10,000', '₦20,000', '₦50,000'].map((amount) => <button key={amount} type="button" onClick={() => setDeposit((old) => ({ ...old, amount: amount.replace(/[₦,]/g, '') }))} className="rounded-xl border border-slate-200 px-2 py-2.5 text-xs font-semibold text-slate-600 transition hover:border-brand hover:bg-gold-50 hover:text-gold-800">{amount}</button>)}</div></div>
            <div className="flex items-center gap-3 rounded-xl border border-gold-100 bg-gold-50/80 p-3.5"><span className="grid h-9 w-9 place-items-center rounded-lg bg-white text-gold-800"><CreditCard size={17} /></span><div><p className="text-xs font-bold text-ink">Secure card checkout</p><p className="mt-1 text-[10px] text-slate-500">You’ll continue to our secure payment page.</p></div><ShieldCheck size={16} className="ml-auto text-green-600" /></div>
            <Button type="submit" className="w-full">Continue to secure checkout <ArrowRight size={15} /></Button>
          </form>
        </Panel>
        <div className="space-y-4">
          <Panel className="p-5"><p className="text-xs font-semibold text-slate-500">Your deposit</p><p className="mt-2 text-[26px] font-bold tracking-tight text-ink">{money(Number(deposit.amount) || 0)}</p><p className="mt-1 text-[11px] text-slate-400">Estimated arrival: usually instant</p></Panel>
          <Panel className="p-5"><div className="flex items-center gap-2"><Shield size={16} className="text-green-600" /><h3 className="text-sm font-bold text-ink">Protected by design</h3></div><p className="mt-3 text-xs leading-5 text-slate-500">Card details are handled by a secure payment provider. Eli never stores your full card number.</p><div className="mt-4 flex items-center gap-2 text-[10px] font-medium text-slate-400"><LockKeyhole size={13} /> Encrypted and secure</div></Panel>
        </div>
      </div>
    </div>
  );
}

function Withdraw({ accounts, withdrawal, setWithdrawal, onSubmit }) {
  return (
    <div className="fade-in">
      <PageHeader title="Withdraw to bank" description="Send money from your account to a verified bank account." />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.3fr)_minmax(260px,0.7fr)]">
        <Panel className="p-5 sm:p-7">
          <form onSubmit={onSubmit} className="space-y-4">
            <Field label="From account" as="select" value={withdrawal.accountId} onChange={(e) => setWithdrawal((old) => ({ ...old, accountId: e.target.value }))}>{accounts.map((account) => <option key={account.id} value={account.id}>{account.name} · •••• {account.lastFour} · {money(account.balance)}</option>)}</Field>
            <Field label="Destination bank" as="select" value={withdrawal.bank} onChange={(e) => setWithdrawal((old) => ({ ...old, bank: e.target.value }))}><option value="">Select destination bank</option>{banks.map((bank) => <option key={bank}>{bank}</option>)}</Field>
            <div className="grid gap-3 sm:grid-cols-[1fr_auto] sm:items-end"><Field label="Account number" inputMode="numeric" maxLength="10" placeholder="Enter 10-digit account number" value={withdrawal.accountNumber} onChange={(e) => setWithdrawal((old) => ({ ...old, accountNumber: e.target.value.replace(/\D/g, ''), verified: false }))} required /><Button type="button" variant="secondary" className="h-[44px]" onClick={() => setWithdrawal((old) => ({ ...old, verified: true, recipient: 'Adaeze Okafor' }))} disabled={withdrawal.accountNumber.length !== 10 || !withdrawal.bank}>Verify account</Button></div>
            {withdrawal.verified && <div className="flex items-center gap-2 rounded-xl border border-green-100 bg-green-50 px-3.5 py-3 text-xs font-semibold text-green-800"><BadgeCheck size={16} /> Verified account name: {withdrawal.recipient}</div>}
            <Field label="Amount (NGN)" type="number" min="100" step="100" placeholder="0.00" value={withdrawal.amount} onChange={(e) => setWithdrawal((old) => ({ ...old, amount: e.target.value }))} required />
            <Button type="submit" className="mt-2 w-full">Review withdrawal <ArrowRight size={15} /></Button>
          </form>
        </Panel>
        <div className="space-y-4">
          <Panel className="p-5"><h3 className="text-sm font-bold text-ink">Before you withdraw</h3><ul className="mt-4 space-y-3">{['A valid name enquiry is required', 'You’ll confirm the amount', 'Some requests need admin approval'].map((item) => <li key={item} className="flex gap-2 text-xs text-slate-600"><Check size={14} className="mt-0.5 shrink-0 text-green-600" />{item}</li>)}</ul></Panel>
          <Panel className="p-5"><h3 className="text-sm font-bold text-ink">Payout timing</h3><p className="mt-2 text-xs leading-5 text-slate-500">Most withdrawals arrive in a few minutes. Some banks may take up to one business day.</p><div className="mt-4 flex items-center gap-2 text-[11px] font-medium text-slate-500"><Clock3 size={14} className="text-gold-800" /> Estimated: today</div></Panel>
        </div>
      </div>
    </div>
  );
}

function LiveFaceSimulation({ verified, onVerified }) {
  const [phase, setPhase] = useState('idle');
  const [cameraStream, setCameraStream] = useState(null);
  const [cameraError, setCameraError] = useState('');
  const videoRef = useRef(null);
  const streamRef = useRef(null);
  const timerRef = useRef(null);

  useEffect(() => {
    if (videoRef.current && cameraStream) videoRef.current.srcObject = cameraStream;
  }, [cameraStream]);

  useEffect(() => () => {
    window.clearTimeout(timerRef.current);
    streamRef.current?.getTracks().forEach((track) => track.stop());
  }, []);

  const stopCamera = () => {
    window.clearTimeout(timerRef.current);
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    setCameraStream(null);
  };

  const startSimulation = async () => {
    setCameraError('');
    onVerified(false);
    if (!navigator.mediaDevices?.getUserMedia) {
      setCameraError('Camera access is not available in this browser. Use a secure connection and try again.');
      setPhase('error');
      return;
    }

    setPhase('requesting');
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: false, video: { facingMode: 'user' } });
      streamRef.current = stream;
      setCameraStream(stream);
      setPhase('scanning');
      timerRef.current = window.setTimeout(() => {
        setPhase('complete');
        onVerified(true);
        stopCamera();
      }, 2200);
    } catch (error) {
      setPhase('error');
      setCameraError(error.name === 'NotAllowedError'
        ? 'Camera access was denied. Allow camera access and try again.'
        : 'The camera could not be started. Check that it is connected and available.');
    }
  };

  return (
    <section aria-labelledby="live-face-title" className="rounded-2xl border border-slate-200 p-4 sm:p-5">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div><h3 id="live-face-title" className="text-sm font-bold text-ink">Live face check <span className="text-rose-600">*</span></h3><p className="mt-1 text-xs leading-5 text-slate-500">Allow camera access to run the facial-recognition demo.</p></div>
        {verified && <Status tone="green">Demo complete</Status>}
      </div>
      {cameraStream && <video ref={videoRef} autoPlay muted playsInline className="mt-4 aspect-video w-full rounded-xl bg-ink object-cover" aria-label="Live camera preview for facial-recognition demo" />}
      <div className="mt-4 flex flex-wrap items-center gap-3">
        <Button type="button" onClick={startSimulation} disabled={phase === 'requesting' || phase === 'scanning'}>
          <Fingerprint size={15} />
          {phase === 'scanning' ? 'Scanning face...' : verified ? 'Run demo again' : 'Start live face check'}
        </Button>
        {phase === 'scanning' && <span aria-live="polite" className="text-xs font-medium text-gold-800">Keep your face centred and look at the camera.</span>}
      </div>
      {cameraError && <p role="alert" className="mt-2 text-xs font-medium text-rose-700">{cameraError}</p>}
      <p className="mt-3 text-[10px] leading-5 text-slate-400">Simulation only: no biometric model runs and no video or image is recorded, uploaded, or sent to the backend.</p>
    </section>
  );
}

function IdentityVerification({ status, setStatus, showToast }) {
  const [documentType, setDocumentType] = useState('NIN_SLIP');
  const [documentImage, setDocumentImage] = useState(null);
  const [documentError, setDocumentError] = useState('');
  const [faceVerified, setFaceVerified] = useState(false);
  const documentInputRef = useRef(null);

  const handleDocumentChange = (event) => {
    const file = event.target.files?.[0];
    setDocumentError('');
    if (!file) {
      setDocumentImage(null);
      return;
    }
    const allowedTypes = ['image/jpeg', 'image/png'];
    const hasAllowedExtension = /\.(jpe?g|png)$/i.test(file.name);
    if ((!allowedTypes.includes(file.type) && !hasAllowedExtension) || file.size > 5 * 1024 * 1024) {
      setDocumentImage(null);
      event.target.value = '';
      setDocumentError(file.size > 5 * 1024 * 1024 ? 'Choose an image smaller than 5 MB.' : 'Use a JPG or PNG image.');
      return;
    }
    setDocumentImage(file);
  };

  const handleSubmit = (event) => {
    event.preventDefault();
    if (!documentImage) {
      setDocumentError('Upload a clear image of your identity document.');
      documentInputRef.current?.focus();
      return;
    }
    if (!faceVerified) return showToast('Complete the live facial-recognition demo first.');
    setDocumentImage(null);
    if (documentInputRef.current) documentInputRef.current.value = '';
    setStatus('Pending review');
    showToast('Document KYC demo recorded. Nothing was stored or sent.');
  };

  return (
    <div className="fade-in">
      <PageHeader title="Identity verification" description="Verify your identity using an approved document and a live facial-recognition demo." />
      {status === 'Pending review' ? (
        <Panel className="mx-auto max-w-3xl p-6 sm:p-8">
          <div className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-gold-50 text-gold-800"><Clock3 size={25} /></div>
          <div className="mt-4 text-center"><Status tone="amber">Pending review</Status><h2 className="mt-3 text-xl font-bold text-ink">Demo verification request recorded</h2><p className="mx-auto mt-2 max-w-lg text-sm leading-6 text-slate-500">The document was not stored or sent to Dojah, and the face check was simulated locally.</p></div>
          <div className="mt-6 rounded-xl border border-gold-100 bg-gold-50/70 p-4 text-xs leading-5 text-slate-600"><b className="text-ink">Privacy note:</b> No BVN is needed for document KYC. Your document file is cleared after submission.</div>
          <div className="mt-5 flex justify-center"><Button variant="secondary" onClick={() => setStatus('Not started')}>Update details</Button></div>
        </Panel>
      ) : (
        <>
          <Panel className="mb-5 overflow-hidden bg-ink p-5 text-white sm:p-6">
            <div className="flex flex-col gap-5 sm:flex-row sm:items-center sm:justify-between">
              <div className="flex items-start gap-3"><span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-brand text-ink"><Fingerprint size={19} /></span><div><h2 className="text-sm font-bold">Document-only identity check</h2><p className="mt-1 max-w-xl text-xs leading-5 text-slate-300">Use an identity document and a live face check. BVN is not required for this step.</p></div></div>
              <Status tone="gold">Action needed</Status>
            </div>
            <div className="mt-5 grid gap-2 text-[11px] text-slate-300 sm:grid-cols-3">
              {['1. Identity document', '2. Live face check', '3. Review'].map((step, index) => <div key={step} className={`rounded-lg border px-3 py-2 ${index === 0 ? 'border-brand/50 bg-white/10 text-white' : 'border-white/10'}`}>{step}</div>)}
            </div>
          </Panel>
          <div className="grid gap-5 lg:grid-cols-[minmax(0,1.35fr)_minmax(260px,0.65fr)]">
            <Panel className="p-5 sm:p-7">
              <div className="mb-5"><h2 className="text-sm font-bold text-ink">Identity document</h2><p className="mt-1 text-xs text-slate-500">Select the document type and upload a clear photo of the front.</p></div>
              <form onSubmit={handleSubmit} className="space-y-5">
                <Field label="Document type" as="select" value={documentType} onChange={(event) => setDocumentType(event.target.value)}>
                  <option value="NIN_SLIP">NIN or National ID</option>
                  <option value="PASSPORT">Passport</option>
                  <option value="DRIVING_LICENSE">Driver's licence</option>
                  <option value="VOTER_CARD">Voter's card</option>
                </Field>
                <div>
                  <label htmlFor="identity-document" className="mb-2 block text-[13px] font-semibold text-slate-700">Identity document image <span className="text-rose-600">*</span></label>
                  <label htmlFor="identity-document" className="flex cursor-pointer flex-col items-center justify-center rounded-xl border border-dashed border-slate-300 bg-slate-50 px-4 py-6 text-center transition hover:border-brand hover:bg-gold-50/50 focus-within:ring-2 focus-within:ring-brand">
                    <span className="grid h-10 w-10 place-items-center rounded-xl bg-white text-gold-800 shadow-sm"><FileText size={18} /></span>
                    <span className="mt-3 text-xs font-semibold text-ink">{documentImage ? documentImage.name : 'Choose an identity document image'}</span>
                    <span className="mt-1 text-[10px] text-slate-500">JPG or PNG · up to 5 MB</span>
                    <input ref={documentInputRef} id="identity-document" type="file" accept=".jpg,.jpeg,.png,image/jpeg,image/png" onChange={handleDocumentChange} className="sr-only" aria-describedby={documentError ? 'identity-document-error' : 'identity-document-hint'} />
                  </label>
                  <p id="identity-document-hint" className="mt-2 text-[10px] text-slate-400">Your document stays in this demo and is not uploaded.</p>
                  {documentError && <p id="identity-document-error" role="alert" className="mt-2 text-xs font-medium text-rose-700">{documentError}</p>}
                </div>
                <LiveFaceSimulation verified={faceVerified} onVerified={setFaceVerified} />
                <label className="flex items-start gap-2.5 rounded-xl border border-slate-200 p-3.5 text-xs leading-5 text-slate-600">
                  <input type="checkbox" className="mt-1 h-4 w-4 shrink-0 accent-brand" required />
                  <span>I confirm this identity document belongs to me and consent to document verification.</span>
                </label>
                <Button type="submit" className="w-full sm:w-auto">Submit identity check <ArrowRight size={15} /></Button>
              </form>
            </Panel>
            <div className="space-y-4">
              <Panel className="p-5"><div className="flex items-center gap-2"><ShieldCheck size={17} className="text-green-700" /><h3 className="text-sm font-bold text-ink">Your information matters</h3></div><p className="mt-3 text-xs leading-5 text-slate-500">Never share your one-time password, card PIN, or online banking password during identity verification.</p></Panel>
              <Panel className="p-5"><h3 className="text-sm font-bold text-ink">Before you begin</h3><ul className="mt-4 space-y-3">{['Use a current government-issued document', 'Make sure all document details are legible', 'Allow camera access for the face-check demo'].map((item) => <li key={item} className="flex gap-2 text-xs text-slate-600"><Check size={14} className="mt-0.5 shrink-0 text-green-700" />{item}</li>)}</ul></Panel>
              <div className="rounded-xl border border-gold-100 bg-gold-50/70 p-4 text-[11px] leading-5 text-slate-600"><b className="text-ink">Demo notice:</b> The camera check is simulated. This screen does not call the backend or Dojah.</div>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function AccountUpgrade({ status, setStatus, showToast }) {
  const [addressDocumentType, setAddressDocumentType] = useState('UTILITY_BILL');
  const [addressDocument, setAddressDocument] = useState(null);
  const [documentError, setDocumentError] = useState('');
  const [faceVerified, setFaceVerified] = useState(false);
  const addressDocumentRef = useRef(null);

  const handleAddressDocumentChange = (event) => {
    const file = event.target.files?.[0];
    setDocumentError('');
    if (!file) {
      setAddressDocument(null);
      return;
    }
    const allowedTypes = ['image/jpeg', 'image/png'];
    const hasAllowedExtension = /\.(jpe?g|png)$/i.test(file.name);
    if ((!allowedTypes.includes(file.type) && !hasAllowedExtension) || file.size > 5 * 1024 * 1024) {
      setAddressDocument(null);
      event.target.value = '';
      setDocumentError(file.size > 5 * 1024 * 1024 ? 'Choose an image smaller than 5 MB.' : 'Use a JPG or PNG image.');
      return;
    }
    setAddressDocument(file);
  };

  const handleSubmit = (event) => {
    event.preventDefault();
    if (!addressDocument) {
      setDocumentError('Upload a proof-of-address image to continue.');
      addressDocumentRef.current?.focus();
      return;
    }
    if (!faceVerified) return showToast('Complete the live facial-recognition demo first.');
    setAddressDocument(null);
    if (addressDocumentRef.current) addressDocumentRef.current.value = '';
    setStatus('Pending review');
    showToast('BVN, address, and facial-check demo recorded. Nothing was stored or sent.');
  };

  return (
    <div className="fade-in">
      <PageHeader title="Upgrade your account" description="Verify your BVN, residential address, and live face to request a higher account tier." />
      {status === 'Pending review' ? (
        <Panel className="mx-auto max-w-3xl p-6 sm:p-8">
          <div className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-gold-50 text-gold-800"><Clock3 size={25} /></div>
          <div className="mt-4 text-center"><Status tone="amber">Pending review</Status><h2 className="mt-3 text-xl font-bold text-ink">Demo upgrade request recorded</h2><p className="mx-auto mt-2 max-w-lg text-sm leading-6 text-slate-500">This is a demo state. No information was saved or sent, and your transaction limits have not changed.</p></div>
          <div className="mt-6 rounded-xl border border-gold-100 bg-gold-50/70 p-4 text-xs leading-5 text-slate-600"><b className="text-ink">Privacy note:</b> This demo does not store or send your BVN, address document, or facial data.</div>
          <div className="mt-5 flex justify-center"><Button variant="secondary" onClick={() => setStatus('Not started')}>Back to upgrade form</Button></div>
        </Panel>
      ) : (
        <div className="grid gap-5 lg:grid-cols-[minmax(0,1.3fr)_minmax(260px,0.7fr)]">
          <Panel className="p-5 sm:p-7">
            <div className="mb-6 flex items-start gap-3 rounded-xl bg-slate-50 p-4"><span className="grid h-9 w-9 shrink-0 place-items-center rounded-lg bg-white text-gold-800"><BadgeCheck size={17} /></span><div><h2 className="text-sm font-bold text-ink">BVN and address verification</h2><p className="mt-1 text-xs leading-5 text-slate-500">Dojah checks your BVN, address document, and liveness. A live camera check is simulated here.</p></div></div>
            <form onSubmit={handleSubmit} className="space-y-5">
              <Field label="Bank Verification Number (BVN)" inputMode="numeric" autoComplete="off" maxLength={11} minLength={11} pattern="[0-9]{11}" placeholder="Enter your 11-digit BVN" hint="Enter the BVN linked to your bank identity." required />
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label="First name" autoComplete="given-name" maxLength="80" placeholder="As on your BVN" required />
                <Field label="Last name" autoComplete="family-name" maxLength="120" placeholder="As on your BVN" required />
              </div>
              <Field label="Residential address" autoComplete="street-address" maxLength={500} placeholder="House number, street, city, and state" hint="Enter the address shown on your proof document." required />
              <Field label="Address document type" as="select" value={addressDocumentType} onChange={(event) => setAddressDocumentType(event.target.value)}>
                <option value="UTILITY_BILL">Utility bill</option>
                <option value="BANK_STATEMENT">Bank statement</option>
                <option value="LEASE_AGREEMENT">Lease or tenancy agreement</option>
                <option value="GOVERNMENT_LETTER">Government letter</option>
              </Field>
              <div>
                <label htmlFor="upgrade-address-document" className="mb-2 block text-[13px] font-semibold text-slate-700">Proof of address image <span className="text-rose-600">*</span></label>
                <label htmlFor="upgrade-address-document" className="flex cursor-pointer flex-col items-center justify-center rounded-xl border border-dashed border-slate-300 bg-slate-50 px-4 py-6 text-center transition hover:border-brand hover:bg-gold-50/50 focus-within:ring-2 focus-within:ring-brand">
                  <span className="grid h-10 w-10 place-items-center rounded-xl bg-white text-gold-800 shadow-sm"><FileText size={18} /></span>
                  <span className="mt-3 text-xs font-semibold text-ink">{addressDocument ? addressDocument.name : 'Choose a proof-of-address image'}</span>
                  <span className="mt-1 text-[10px] text-slate-500">JPG or PNG · up to 5 MB</span>
                  <input ref={addressDocumentRef} id="upgrade-address-document" type="file" accept=".jpg,.jpeg,.png,image/jpeg,image/png" onChange={handleAddressDocumentChange} className="sr-only" aria-describedby={documentError ? 'upgrade-address-error' : 'upgrade-address-hint'} />
                </label>
                <p id="upgrade-address-hint" className="mt-2 text-[10px] text-slate-400">The document must show your name and residential address. It stays in this demo.</p>
                {documentError && <p id="upgrade-address-error" role="alert" className="mt-2 text-xs font-medium text-rose-700">{documentError}</p>}
              </div>
              <LiveFaceSimulation verified={faceVerified} onVerified={setFaceVerified} />
              <label className="flex items-start gap-2.5 rounded-xl border border-slate-200 p-3.5 text-xs leading-5 text-slate-600">
                <input type="checkbox" className="mt-1 h-4 w-4 shrink-0 accent-brand" required />
                <span>I consent to Dojah checking my BVN and address document. I confirm the details belong to me.</span>
              </label>
              <Button type="submit" className="w-full sm:w-auto">Request account upgrade <ArrowRight size={15} /></Button>
            </form>
          </Panel>
          <div className="space-y-4">
            <Panel className="p-5"><h3 className="text-sm font-bold text-ink">How verification works</h3><ul className="mt-4 space-y-3">{['BVN identity and selfie are matched', 'Address document is checked against your address', 'Live facial-recognition demo completes in this UI', 'All accounts owned by you upgrade on success'].map((item) => <li key={item} className="flex gap-2 text-xs text-slate-600"><Check size={14} className="mt-0.5 shrink-0 text-green-700" />{item}</li>)}</ul></Panel>
            <Panel className="p-5"><div className="flex items-center gap-2"><ShieldCheck size={17} className="text-green-700" /><h3 className="text-sm font-bold text-ink">Protect your details</h3></div><p className="mt-3 text-xs leading-5 text-slate-500">Only enter your BVN on a trusted device. Never share your PIN, password, or one-time code during an account upgrade.</p></Panel>
            <div className="rounded-xl border border-gold-100 bg-gold-50/70 p-4 text-[11px] leading-5 text-slate-600"><b className="text-ink">Demo notice:</b> This screen is independent. It does not call the backend or Dojah and will not change account limits.</div>
          </div>
        </div>
      )}
    </div>
  );
}

function Transactions({ transactions, setView, onSelect }) {
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('All activity');
  const filtered = useMemo(() => transactions.filter((tx) => {
    const matchesText = `${tx.title} ${tx.id} ${tx.type}`.toLowerCase().includes(query.toLowerCase());
    const matchesType = filter === 'All activity' || (filter === 'Money in' ? tx.amount > 0 : tx.amount < 0);
    return matchesText && matchesType;
  }), [transactions, query, filter]);
  return (
    <div className="fade-in">
      <PageHeader title="Transaction history" description="Track every movement across your accounts." actions={<Button variant="secondary"><Download size={15} /> Download statement</Button>} />
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div className="inline-flex rounded-xl border border-slate-200 bg-white p-1">{['All activity', 'Money in', 'Money out'].map((tab) => <button key={tab} onClick={() => setFilter(tab)} className={`rounded-lg px-3 py-2 text-xs font-semibold ${filter === tab ? 'bg-brand text-ink' : 'text-slate-500 hover:bg-slate-50'}`}>{tab}</button>)}</div>
        <div className="flex flex-1 gap-2 sm:flex-none"><label className="relative min-w-[180px] flex-1 sm:w-64"><Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" /><input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search activity..." className="form-control pl-9" /></label><Button variant="secondary" className="px-3"><Filter size={15} /><span className="hidden sm:inline">Filters</span></Button></div>
      </div>
      <Panel className="overflow-hidden">
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4"><div><h2 className="text-sm font-bold text-ink">All transactions</h2><p className="mt-1 text-[10px] text-slate-400">{filtered.length} records · Last 30 days</p></div><button className="inline-flex items-center gap-1 text-xs font-semibold text-slate-500 hover:text-gold-800"><SlidersHorizontal size={14} /> Date range</button></div>
        <TransactionTable rows={filtered} onSelect={onSelect} />
        <div className="flex items-center justify-between border-t border-slate-100 px-5 py-3 text-[10px] text-slate-400"><span>Showing {filtered.length} of {transactions.length} transactions</span><div className="flex gap-1"><button className="rounded-lg border border-slate-200 p-1.5"><ChevronLeft size={14} /></button><button className="rounded-lg bg-brand px-2.5 py-1 font-semibold text-ink">1</button><button className="rounded-lg border border-slate-200 px-2.5 py-1">2</button><button className="rounded-lg border border-slate-200 p-1.5"><ChevronRight size={14} /></button></div></div>
      </Panel>
      <div className="mt-4 flex items-center justify-between rounded-xl bg-gold-50/70 px-4 py-3 text-xs"><span className="text-slate-600">Need a record for your files?</span><button onClick={() => setView('support')} className="font-semibold text-gold-800">Request a statement</button></div>
    </div>
  );
}

function Beneficiaries({ beneficiaries, onAdd, onRemove, onSend }) {
  const [query, setQuery] = useState('');
  const [isAdding, setIsAdding] = useState(false);
  const [newBeneficiary, setNewBeneficiary] = useState({ name: '', bank: '', accountNumber: '' });
  const filteredBeneficiaries = useMemo(() => beneficiaries.filter((beneficiary) =>
    `${beneficiary.name} ${beneficiary.bank} ${beneficiary.accountNumber}`.toLowerCase().includes(query.toLowerCase()),
  ), [beneficiaries, query]);

  const submitBeneficiary = (event) => {
    event.preventDefault();
    if (onAdd(newBeneficiary)) {
      setNewBeneficiary({ name: '', bank: '', accountNumber: '' });
      setIsAdding(false);
    }
  };

  return (
    <div className="fade-in">
      <PageHeader title="Saved beneficiaries" description="Keep trusted recipients handy for your next bank transfer." actions={<Button onClick={() => setIsAdding((open) => !open)}><Plus size={15} />{isAdding ? 'Cancel' : 'Add beneficiary'}</Button>} />
      {isAdding && <Panel className="mb-5 p-5 sm:p-6">
        <h2 className="text-sm font-bold text-ink">Add a beneficiary</h2>
        <p className="mt-1 text-xs text-slate-500">Recipient details are stored in this browser session only.</p>
        <form onSubmit={submitBeneficiary} className="mt-5 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <Field label="Recipient name" maxLength="120" autoComplete="name" placeholder="Full name" value={newBeneficiary.name} onChange={(event) => setNewBeneficiary((old) => ({ ...old, name: event.target.value }))} required />
          <Field label="Bank" as="select" value={newBeneficiary.bank} onChange={(event) => setNewBeneficiary((old) => ({ ...old, bank: event.target.value }))} required>
            <option value="">Select a bank</option>{banks.map((bank) => <option key={bank}>{bank}</option>)}
          </Field>
          <Field label="Account number" inputMode="numeric" autoComplete="off" maxLength="10" pattern="[0-9]{10}" placeholder="10-digit account number" value={newBeneficiary.accountNumber} onChange={(event) => setNewBeneficiary((old) => ({ ...old, accountNumber: event.target.value.replace(/\D/g, '') }))} required />
          <div className="flex items-end"><Button type="submit" className="w-full">Save beneficiary</Button></div>
        </form>
      </Panel>}
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div><h2 className="text-sm font-bold text-ink">{beneficiaries.length} saved recipients</h2><p className="mt-1 text-xs text-slate-500">Verify the account name again before sending money.</p></div>
        <label className="relative min-w-[180px] flex-1 sm:w-64 sm:flex-none"><Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search recipients..." className="form-control pl-9" /></label>
      </div>
      {filteredBeneficiaries.length ? <div className="grid gap-4 lg:grid-cols-2">
        {filteredBeneficiaries.map((beneficiary) => (
          <Panel key={beneficiary.id} className="p-5">
            <div className="flex items-start gap-3">
              <span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-gold-50 text-sm font-bold text-gold-800">{beneficiary.name.split(/\s+/).slice(0, 2).map((part) => part[0]).join('').toUpperCase()}</span>
              <div className="min-w-0 flex-1"><h3 className="truncate text-sm font-bold text-ink">{beneficiary.name}</h3><p className="mt-1 text-xs text-slate-500">{beneficiary.bank}</p><p className="mt-2 text-xs font-medium tracking-wide text-slate-600">•••• {beneficiary.accountNumber.slice(-4)}</p></div>
              <button type="button" onClick={() => onRemove(beneficiary)} className="rounded-lg px-2 py-1 text-[11px] font-semibold text-slate-400 hover:bg-rose-50 hover:text-rose-700" aria-label={`Remove ${beneficiary.name}`}>Remove</button>
            </div>
            <Button variant="secondary" className="mt-5 w-full" onClick={() => onSend(beneficiary)}><Send size={14} />Send money</Button>
          </Panel>
        ))}
      </div> : <Panel className="p-10 text-center">
        <span className="mx-auto grid h-12 w-12 place-items-center rounded-xl bg-slate-100 text-slate-500"><Users size={20} /></span>
        <h2 className="mt-4 text-sm font-bold text-ink">{query ? 'No recipients found' : 'No beneficiaries yet'}</h2>
        <p className="mx-auto mt-2 max-w-md text-xs leading-5 text-slate-500">{query ? 'Try another name, bank, or account number.' : 'Add a trusted recipient to make future transfers easier.'}</p>
        {!query && <Button className="mt-5" onClick={() => setIsAdding(true)}><Plus size={15} />Add beneficiary</Button>}
      </Panel>}
      <div className="mt-5 rounded-xl border border-gold-100 bg-gold-50/70 p-4 text-xs leading-5 text-slate-600"><b className="text-ink">Demo notice:</b> Saved recipients are not sent to or stored by the banking backend. Always verify the recipient name before confirming a transfer.</div>
    </div>
  );
}

function Notifications({ notifications, onMarkRead, onMarkAllRead, setView }) {
  const [filter, setFilter] = useState('All');
  const visibleNotifications = notifications.filter((notification) => filter === 'All' || !notification.read);
  const iconForType = { statement: FileText, deposit: ArrowDownLeft, security: ShieldCheck };

  return (
    <div className="fade-in">
      <PageHeader title="Notifications" description="Important account updates and activity, all in one place." actions={<Button variant="secondary" onClick={onMarkAllRead} disabled={!notifications.some((notification) => !notification.read)}><CheckCheck size={15} />Mark all as read</Button>} />
      <div className="mb-4 flex items-center justify-between gap-3">
        <div className="inline-flex rounded-xl border border-slate-200 bg-white p-1">{['All', 'Unread'].map((tab) => <button key={tab} onClick={() => setFilter(tab)} className={`rounded-lg px-3 py-2 text-xs font-semibold ${filter === tab ? 'bg-brand text-ink' : 'text-slate-500 hover:bg-slate-50'}`}>{tab}{tab === 'Unread' ? ` (${notifications.filter((notification) => !notification.read).length})` : ''}</button>)}</div>
        <span className="text-xs text-slate-400">{notifications.length} updates</span>
      </div>
      <Panel className="divide-y divide-slate-100 px-5">
        {visibleNotifications.map((notification) => {
          const Icon = iconForType[notification.type] || Bell;
          return <article key={notification.id} className={`flex gap-3 py-5 sm:gap-4 ${notification.read ? 'opacity-75' : ''}`}>
            <span className={`grid h-10 w-10 shrink-0 place-items-center rounded-xl ${notification.type === 'deposit' ? 'bg-green-50 text-green-700' : notification.type === 'security' ? 'bg-slate-100 text-slate-600' : 'bg-gold-50 text-gold-800'}`}><Icon size={17} /></span>
            <div className="min-w-0 flex-1"><div className="flex flex-wrap items-center gap-2"><h2 className="text-xs font-bold text-ink">{notification.title}</h2>{!notification.read && <span className="h-1.5 w-1.5 rounded-full bg-gold-600" aria-label="Unread" />}</div><p className="mt-1 text-xs leading-5 text-slate-500">{notification.detail}</p><p className="mt-2 text-[10px] text-slate-400">{notification.time}</p><div className="mt-3 flex flex-wrap gap-3"><button onClick={() => setView('transactions')} className="text-[11px] font-semibold text-gold-800 hover:text-gold-900">View activity</button>{!notification.read && <button onClick={() => onMarkRead(notification.id)} className="text-[11px] font-semibold text-slate-500 hover:text-ink">Mark as read</button>}</div></div>
          </article>;
        })}
        {!visibleNotifications.length && <div className="py-12 text-center"><CheckCheck size={22} className="mx-auto text-green-700" /><p className="mt-3 text-sm font-semibold text-ink">You’re all caught up</p><p className="mt-1 text-xs text-slate-500">There are no unread notifications.</p></div>}
      </Panel>
      <p className="mt-4 text-[11px] leading-5 text-slate-400">These are sample notifications for the frontend demo. Notification preferences are managed in Settings.</p>
    </div>
  );
}

function VerifyTransfer({ transfer, onComplete, onBack }) {
  const [code, setCode] = useState('');
  return (
    <div className="fade-in">
      <PageHeader title="Verify this transfer" description="One extra step is needed to keep this payment safe." actions={<Button variant="secondary" onClick={onBack}><ArrowLeft size={15} /> Back</Button>} />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.25fr)_minmax(260px,0.75fr)]">
        <Panel className="p-6 sm:p-8">
          <div className="mx-auto max-w-md">
            <div className="flex items-center gap-2 text-sm font-bold text-ink"><LockKeyhole size={17} className="text-gold-800" /> Confirm it’s really you</div>
            <p className="mt-3 text-xs leading-5 text-slate-500">We sent a one-time code to your verified email address. Enter it below to complete your transfer.</p>
            <label className="mt-6 block text-[11px] font-bold uppercase tracking-[0.1em] text-slate-500">Enter 6-digit verification code</label>
            <input aria-label="One-time verification code" inputMode="numeric" autoComplete="one-time-code" maxLength={6} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))} className="mt-3 w-full rounded-xl border border-slate-200 bg-slate-50 px-4 py-4 text-center text-2xl font-bold tracking-[0.55em] text-ink outline-none transition focus:border-brand focus:bg-white focus:ring-4 focus:ring-brand" placeholder="••••••" />
            <button className="mt-3 text-xs font-semibold text-gold-800">Didn’t receive a code? Resend in 00:28</button>
            <Button onClick={onComplete} disabled={code.length !== 6} className="mt-5 w-full">Verify and complete transfer <ArrowRight size={15} /></Button>
            <p className="mt-4 rounded-xl bg-gold-50/70 p-3 text-[11px] leading-5 text-slate-500">Never share a verification code with anyone, including Eli staff.</p>
          </div>
        </Panel>
        <Panel className="h-fit p-5"><div className="flex items-center gap-2"><Activity size={17} className="text-gold-800" /><h3 className="text-sm font-bold text-ink">Transfer being verified</h3></div><p className="mt-5 text-[10px] font-medium uppercase tracking-wide text-slate-400">Amount</p><p className="mt-1 text-[25px] font-bold text-ink">{money(Number(transfer.amount) || 0)}</p><div className="mt-4 border-t border-slate-100 pt-3 text-xs"><div className="text-slate-400">Recipient</div><div className="mt-1 font-semibold text-ink">{transfer.recipientName || 'Adaeze Okafor'}</div></div></Panel>
      </div>
    </div>
  );
}

function TransferStatus({ transfer, setView, transactions }) {
  return (
    <div className="fade-in">
      <PageHeader title="Transfer status" description="Your transaction has been received and the details are below." />
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.2fr)_minmax(270px,0.8fr)]">
        <Panel className="p-6 text-center sm:p-9">
          <div className="mx-auto grid h-16 w-16 place-items-center rounded-2xl bg-green-50 text-green-600"><Check size={30} strokeWidth={2.5} /></div>
          <Status tone="green">Transfer successful</Status>
          <h2 className="mt-3 text-2xl font-bold tracking-tight text-ink">Your money is on its way.</h2>
          <p className="mt-2 text-xs text-slate-500">The transfer was completed successfully.</p>
          <p className="mt-5 text-[32px] font-bold tracking-tight text-ink">{money(Number(transfer.amount) || 0)}</p>
          <div className="mx-auto mt-6 max-w-sm divide-y divide-slate-100 border-y border-slate-100 text-left">
            {[['Recipient', transfer.recipientName || 'Adaeze Okafor'], ['Bank', transfer.bank || 'Access Bank'], ['Reference', transactions[0]?.id || 'TXN-849218'], ['Date', '10 Nov 2026 · 10:42 AM']].map(([label, value]) => <div key={label} className="flex justify-between gap-3 py-3 text-xs"><span className="text-slate-500">{label}</span><span className="text-right font-semibold text-ink">{value}</span></div>)}
          </div>
          <div className="mt-6 flex flex-wrap justify-center gap-2"><Button variant="dark" onClick={() => window.print()}><Download size={15} /> Download receipt</Button><Button onClick={() => setView('overview')}>Back to overview <ArrowRight size={15} /></Button></div>
        </Panel>
        <Panel className="h-fit p-5"><h3 className="text-sm font-bold text-ink">Transaction timeline</h3><div className="mt-5 space-y-4">{[['Request received', '10:42:01 AM'], ['PIN verified', '10:42:04 AM'], ['Ledger updated', '10:42:05 AM'], ['Provider confirmed', '10:42:07 AM']].map(([label, time], index) => <div key={label} className="flex gap-3"><span className="relative mt-0.5 grid h-5 w-5 shrink-0 place-items-center rounded-full bg-green-50 text-green-600">{index === 3 ? <Check size={12} /> : <span className="h-1.5 w-1.5 rounded-full bg-green-600" />}{index < 3 && <span className="absolute left-1/2 top-5 h-5 w-px bg-green-100" />}</span><div className="flex flex-1 justify-between gap-2 text-xs"><span className="font-medium text-slate-700">{label}</span><span className="text-[10px] text-slate-400">{time}</span></div></div>)}</div><div className="mt-6 rounded-xl bg-slate-50 p-3 text-[11px] leading-5 text-slate-500">A receipt has been added to your transaction history.</div></Panel>
      </div>
    </div>
  );
}

function SettingsPage({ setView, showToast }) {
  const [emailAlerts, setEmailAlerts] = useState(true);
  const [transactionAlerts, setTransactionAlerts] = useState(true);
  return (
    <div className="fade-in">
      <PageHeader title="Settings" description="Manage your profile and how Eli keeps you in the loop." />
      <div className="grid gap-5 lg:grid-cols-[220px_1fr]">
        <div className="space-y-1">{[['Profile information', 'settings', UserRound], ['Security & PIN', 'security', Shield], ['Notifications', 'notifications', Bell]].map(([label, view, Icon], i) => <button key={`${label}-${i}`} onClick={() => setView(view)} className={`flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-xs font-semibold ${i === 0 ? 'bg-white text-ink shadow-sm' : 'text-slate-500 hover:bg-white'}`}><Icon size={15} />{label}</button>)}</div>
        <div className="space-y-4">
          <Panel className="p-5 sm:p-6"><SectionHeading title="Personal information" /><div className="grid gap-4 sm:grid-cols-2"><Field label="Full name" defaultValue="Elijah Okafor" /><Field label="Email address" type="email" defaultValue="elijah.okafor@email.com" /><Field label="Phone number" type="tel" defaultValue="+234 801 234 5678" /><Field label="Preferred currency" as="select" defaultValue="NGN"><option value="NGN">NGN · Nigerian naira</option></Field></div><Button className="mt-5" onClick={() => showToast('Profile changes saved in this demo.')}>Save changes</Button></Panel>
          <Panel className="p-5 sm:p-6"><SectionHeading title="Notifications" /><div className="space-y-4">{[['Transaction alerts', 'Get notified when money moves.', transactionAlerts, setTransactionAlerts], ['Email updates', 'Statements, security and account updates.', emailAlerts, setEmailAlerts]].map(([title, detail, checked, setter]) => <label key={title} className="flex cursor-pointer items-center justify-between gap-4"><span><span className="block text-xs font-semibold text-ink">{title}</span><span className="mt-1 block text-[11px] text-slate-400">{detail}</span></span><input type="checkbox" checked={checked} onChange={(e) => setter(e.target.checked)} className="h-4 w-4 accent-brand" /></label>)}</div></Panel>
        </div>
      </div>
    </div>
  );
}

function SecurityPage({ showToast }) {
  const [pin, setPin] = useState('');
  return (
    <div className="fade-in">
      <PageHeader title="Security & PIN" description="Keep your account and every transfer protected." />
      <div className="grid gap-5 lg:grid-cols-[1.1fr_0.9fr]">
        <Panel className="p-5 sm:p-6"><SectionHeading title="Transaction PIN" /><p className="mb-5 text-xs leading-5 text-slate-500">Your 4-digit PIN is required to approve transfers and other sensitive actions. Never share it with anyone.</p><form onSubmit={(e) => { e.preventDefault(); setPin(''); showToast('PIN updated in this demo.'); }} className="space-y-4"><Field label="Current PIN" type="password" inputMode="numeric" maxLength="4" placeholder="••••" /><Field label="New PIN" type="password" inputMode="numeric" maxLength="4" placeholder="Enter a new 4-digit PIN" /><Field label="Confirm new PIN" type="password" inputMode="numeric" maxLength="4" placeholder="Re-enter your new PIN" /><Button type="submit">Update transaction PIN</Button></form></Panel>
        <div className="space-y-4">
          <Panel className="p-5"><div className="flex items-center gap-3"><span className="grid h-10 w-10 place-items-center rounded-xl bg-green-50 text-green-700"><ShieldCheck size={18} /></span><div><h3 className="text-sm font-bold text-ink">Your account is secure</h3><p className="mt-1 text-[11px] text-slate-400">Last checked just now</p></div></div><div className="mt-5 space-y-3">{[['Email verified', true], ['Transaction PIN enabled', true], ['Fraud monitoring active', true]].map(([label, enabled]) => <div key={label} className="flex items-center justify-between text-xs"><span className="text-slate-600">{label}</span><CheckCheck size={15} className={enabled ? 'text-green-600' : 'text-slate-300'} /></div>)}</div></Panel>
          <Panel className="p-5"><h3 className="text-sm font-bold text-ink">Password & sign-in</h3><p className="mt-2 text-xs leading-5 text-slate-500">Update your password and review the devices that can access your account.</p><Button variant="secondary" className="mt-4" onClick={() => showToast('Password reset flow is ready to connect.')}>Change password <ArrowRight size={14} /></Button></Panel>
          <Panel className="border-rose-100 p-5"><h3 className="text-sm font-bold text-rose-800">Lost your device?</h3><p className="mt-2 text-xs leading-5 text-slate-500">Contact our support team to secure your account right away.</p><button onClick={() => showToast('Support request started.')} className="mt-3 text-xs font-semibold text-rose-700">Get urgent help</button></Panel>
        </div>
      </div>
    </div>
  );
}

function OpenAccount({ onCancel, onCreate }) {
  const [accountType, setAccountType] = useState('Savings');
  const [openingDeposit, setOpeningDeposit] = useState('1000');
  return (
    <div className="fade-in">
      <PageHeader title="Open a new account" description="Choose the account that works for your day-to-day." actions={<Button variant="secondary" onClick={onCancel}><ArrowLeft size={15} /> Back to accounts</Button>} />
      <div className="mx-auto grid max-w-4xl gap-5 lg:grid-cols-[1.1fr_0.9fr]">
        <Panel className="p-5 sm:p-7"><form onSubmit={(e) => { e.preventDefault(); onCreate(accountType, Number(openingDeposit)); }} className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-2">
            {['Savings', 'Current'].map((type) => <button type="button" key={type} onClick={() => setAccountType(type)} className={`rounded-2xl border p-4 text-left transition ${accountType === type ? 'border-brand bg-gold-50/70 ring-2 ring-gold-100' : 'border-slate-200 hover:border-slate-300'}`}><span className={`mb-3 grid h-9 w-9 place-items-center rounded-xl ${accountType === type ? 'bg-white text-gold-800' : 'bg-slate-100 text-slate-500'}`}>{type === 'Savings' ? <Landmark size={17} /> : <Wallet size={17} />}</span><span className="block text-sm font-bold text-ink">{type} account</span><span className="mt-1 block text-[11px] leading-5 text-slate-500">{type === 'Savings' ? 'Save towards your goals and earn more.' : 'Flexible everyday banking and payments.'}</span></button>)}
          </div>
          <Field label="Account name" defaultValue={accountType === 'Savings' ? 'My savings' : 'Everyday account'} />
          <Field label="Opening deposit (NGN)" type="number" min="1000" step="1000" value={openingDeposit} onChange={(e) => setOpeningDeposit(e.target.value)} required hint="Your opening deposit will be added to your account balance." />
          <Button type="submit" className="w-full">Open {accountType.toLowerCase()} account <ArrowRight size={15} /></Button>
        </form></Panel>
        <Panel className="h-fit p-5"><h3 className="text-sm font-bold text-ink">Your new account includes</h3><ul className="mt-4 space-y-3">{['A unique 10-digit account number', 'Instant internal transfers', 'Protected transaction history', 'No monthly maintenance fee'].map((item) => <li key={item} className="flex gap-2 text-xs text-slate-600"><Check size={14} className="mt-0.5 text-green-600" />{item}</li>)}</ul><p className="mt-5 rounded-xl bg-slate-50 p-3 text-[10px] leading-5 text-slate-500">Demo only: account details are stored in browser memory and are not submitted to a bank.</p></Panel>
      </div>
    </div>
  );
}

function Support({ showToast }) {
  return (
    <div className="fade-in">
      <PageHeader title="How can we help?" description="Find an answer or get in touch with our support team." />
      <div className="grid gap-4 md:grid-cols-3">{[[CircleHelp, 'Help centre', 'Browse answers to common questions.'], [ShieldCheck, 'Report a concern', 'Tell us about a card or transfer issue.'], [FileText, 'Request a document', 'Get a statement or account confirmation.']].map(([Icon, title, desc]) => <Panel key={title} className="p-5"><span className="grid h-10 w-10 place-items-center rounded-xl bg-gold-50 text-gold-800"><Icon size={18} /></span><h2 className="mt-4 text-sm font-bold text-ink">{title}</h2><p className="mt-1 text-xs leading-5 text-slate-500">{desc}</p><button onClick={() => showToast(`${title} request started.`)} className="mt-4 inline-flex items-center gap-1 text-xs font-semibold text-gold-800">Get started <ArrowRight size={13} /></button></Panel>)}</div>
      <Panel className="mt-5 p-5 sm:p-7"><h2 className="text-sm font-bold text-ink">Frequently asked questions</h2><div className="mt-3 divide-y divide-slate-100">{[['How long do transfers take?', 'Eli-to-Eli transfers are usually instant. Bank transfers may take longer depending on the receiving bank.'], ['How do I reset my PIN?', 'Go to Settings, then Security & PIN. We will verify your identity before allowing a reset.'], ['Where can I find my statement?', 'Open Transactions and select Download statement to view a statement for your selected period.']].map(([question, answer]) => <details key={question} className="group py-4"><summary className="flex cursor-pointer list-none items-center justify-between text-xs font-semibold text-ink">{question}<ChevronDown size={15} className="transition group-open:rotate-180" /></summary><p className="mt-2 max-w-2xl text-xs leading-5 text-slate-500">{answer}</p></details>)}</div></Panel>
    </div>
  );
}

function AuthPage({ view, setView, onSignIn, showToast }) {
  const isRegister = view === 'register';
  const isForgot = view === 'forgot-password';
  const [sent, setSent] = useState(false);
  return (
    <main className="grid min-h-screen bg-white lg:grid-cols-[1.05fr_0.95fr]">
      <section className="relative hidden overflow-hidden bg-ink p-12 text-white lg:flex lg:flex-col lg:justify-between xl:p-16">
        <div className="absolute -right-20 top-1/4 h-96 w-96 rounded-full border border-white/[0.08]" /><div className="absolute -right-4 top-[30%] h-72 w-72 rounded-full border border-white/[0.08]" />
        <BrandMark />
        <div className="relative max-w-lg"><div className="mb-5 inline-flex items-center gap-2 rounded-full border border-white/10 bg-white/[0.06] px-3 py-1.5 text-[11px] font-medium text-slate-300"><Sparkles size={13} className="text-green-300" /> Your money, made clearer</div><h1 className="text-5xl font-bold leading-[1.1] tracking-tight">Banking that<br />moves with you.</h1><p className="mt-5 max-w-sm text-sm leading-6 text-slate-300">A calmer, clearer way to manage everyday money. Your whole financial life, in one place.</p><div className="mt-10 flex items-center gap-3"><span className="grid h-10 w-10 place-items-center rounded-xl bg-green-400/10 text-green-300"><ShieldCheck size={18} /></span><span className="text-xs text-slate-300">Secure by design. Simple by nature.</span></div></div>
        <p className="relative text-[10px] text-slate-500">© 2026 Eli Digital Bank · Banking made clear.</p>
      </section>
      <section className="flex items-center justify-center p-5 sm:p-10">
        <div className="w-full max-w-[410px]">
          <div className="mb-8 flex items-center gap-3 lg:hidden"><BrandMark compact /><span className="text-sm font-bold text-ink">ELI DIGITAL BANK</span></div>
          {view === 'verify-email' ? <div className="text-center"><span className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-green-50 text-green-600"><Check size={26} /></span><h2 className="mt-5 text-2xl font-bold text-ink">Check your inbox</h2><p className="mt-2 text-sm leading-6 text-slate-500">We sent a verification link to your email address. Verify your email before signing in.</p><Button className="mt-6 w-full" onClick={() => setView('login')}>Back to sign in</Button></div> : sent ? <div className="text-center"><span className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-gold-50 text-gold-800"><Check size={26} /></span><h2 className="mt-5 text-2xl font-bold text-ink">Check your email</h2><p className="mt-2 text-sm leading-6 text-slate-500">If an account exists for that email, a secure password reset link is on its way.</p><Button className="mt-6 w-full" onClick={() => setView('login')}>Back to sign in</Button></div> : <>
            <button onClick={() => setView('overview')} className="mb-8 hidden items-center gap-1 text-xs font-semibold text-slate-400 hover:text-ink lg:inline-flex"><ArrowLeft size={14} /> Back to demo</button>
            <p className="text-[11px] font-bold uppercase tracking-[0.15em] text-gold-800">{isRegister ? 'GET STARTED' : isForgot ? 'ACCOUNT RECOVERY' : 'WELCOME BACK'}</p>
            <h2 className="mt-2 text-[29px] font-bold tracking-tight text-ink">{isRegister ? 'Create your account' : isForgot ? 'Reset your password' : 'Sign in to Eli'}</h2>
            <p className="mt-2 text-sm text-slate-500">{isRegister ? 'A better banking experience starts here.' : isForgot ? 'We’ll email you a secure link to get back in.' : 'Your money is right where you left it.'}</p>
            <form onSubmit={(e) => { e.preventDefault(); if (isForgot) { setSent(true); return; } if (isRegister) { setView('verify-email'); return; } onSignIn(); }} className="mt-7 space-y-4">
              {isRegister && <Field label="Full name" placeholder="Your full name" required />}
              <Field label="Email address" type="email" placeholder="you@example.com" autoComplete="email" required />
              {!isForgot && <Field label="Password" type="password" placeholder={isRegister ? 'At least 8 characters' : 'Enter your password'} autoComplete={isRegister ? 'new-password' : 'current-password'} required />}
              {isRegister && <Field label="Phone number" type="tel" placeholder="+234 800 000 0000" required />}
              {!isRegister && !isForgot && <div className="-mt-1 flex justify-end"><button type="button" onClick={() => setView('forgot-password')} className="text-[11px] font-semibold text-gold-800">Forgot password?</button></div>}
              <Button type="submit" className="mt-1 w-full">{isRegister ? 'Create account' : isForgot ? 'Send reset link' : 'Sign in'} <ArrowRight size={15} /></Button>
            </form>
            <div className="my-6 flex items-center gap-3 text-[10px] text-slate-300"><span className="h-px flex-1 bg-slate-200" />SECURE, ENCRYPTED BANKING<span className="h-px flex-1 bg-slate-200" /></div>
            <div className="rounded-xl border border-slate-200 bg-slate-50 p-3 text-[10px] leading-5 text-slate-500"><b className="text-slate-700">Prototype sign-in:</b> use any email and password. This demo does not authenticate against the backend.</div>
            <p className="mt-6 text-center text-xs text-slate-500">{isRegister ? 'Already banking with us?' : isForgot ? 'Remember your password?' : 'New to Eli?'} <button onClick={() => setView(isRegister || isForgot ? 'login' : 'register')} className="font-semibold text-gold-800">{isRegister || isForgot ? 'Sign in' : 'Create an account'}</button></p>
          </>}
        </div>
      </section>
    </main>
  );
}

function AdminDashboard({ setView, payouts }) {
  const pendingTotal = payouts.reduce((sum, payout) => sum + payout.amount, 0);
  return (
    <div className="fade-in">
      <PageHeader eyebrow="OPERATIONS WORKSPACE" title="Good morning, Eli" description="Here’s what needs your attention today." actions={<Button variant="secondary"><Download size={15} /> Export report</Button>} />
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Metric label="Pending payouts" value={String(payouts.length + 8)} change="4 need your attention" icon={FileClock} color="gold" />
        <Metric label="Payout value" value={money(pendingTotal + 8180000)} change="Across all pending requests" icon={Banknote} color="green" />
        <Metric label="Flagged by risk" value="3" change="2 high priority" icon={ShieldAlert} color="amber" />
        <Metric label="Active customers" value="12,408" change="+184 this month" icon={Users} color="gold" />
      </div>
      <div className="mt-5 grid gap-5 xl:grid-cols-[1.35fr_0.65fr]">
        <Panel className="overflow-hidden"><div className="flex items-center justify-between border-b border-slate-100 p-5"><div><h2 className="text-sm font-bold text-ink">Payouts needing review</h2><p className="mt-1 text-[10px] text-slate-400">Requests waiting for an operations decision</p></div><button onClick={() => setView('payouts')} className="text-xs font-semibold text-gold-800">View queue <ArrowRight size={13} className="ml-1 inline" /></button></div><PayoutTable payouts={payouts.slice(0, 3)} onReview={() => setView('payouts')} compact /></Panel>
        <Panel className="p-5"><SectionHeading title="Platform health" action="View reconciliation" onAction={() => setView('reconciliation')} /><div className="space-y-4">{[['Ledger balance', 'Reconciled', 'green'], ['Payment provider', 'Operational', 'green'], ['Notification queue', 'Operational', 'green'], ['Fraud checks', 'Monitoring', 'gold']].map(([label, value, tone]) => <div key={label} className="flex items-center justify-between"><span className="text-xs text-slate-600">{label}</span><Status tone={tone}><span className="h-1.5 w-1.5 rounded-full bg-current" />{value}</Status></div>)}</div><div className="mt-5 rounded-xl bg-slate-50 p-3 text-[10px] leading-5 text-slate-500">System indicators are sample data in this UI prototype.</div></Panel>
      </div>
      <div className="mt-5 grid gap-4 sm:grid-cols-2">
        <button onClick={() => setView('risk-alerts')} className="rounded-2xl border border-amber-200 bg-amber-50/70 p-4 text-left transition hover:bg-amber-50"><div className="flex items-center gap-3"><span className="grid h-9 w-9 place-items-center rounded-xl bg-white text-amber-700"><ShieldAlert size={17} /></span><div><p className="text-xs font-bold text-ink">3 risk alerts need review</p><p className="mt-1 text-[10px] text-slate-500">Review suspicious transfers and payout patterns.</p></div><ArrowRight size={15} className="ml-auto text-amber-700" /></div></button>
        <button onClick={() => setView('audit-log')} className="rounded-2xl border border-slate-200 bg-white p-4 text-left transition hover:bg-slate-50"><div className="flex items-center gap-3"><span className="grid h-9 w-9 place-items-center rounded-xl bg-gold-50 text-gold-800"><FileCheck2 size={17} /></span><div><p className="text-xs font-bold text-ink">Recent admin activity</p><p className="mt-1 text-[10px] text-slate-500">All operations actions are recorded in the audit log.</p></div><ArrowRight size={15} className="ml-auto text-slate-400" /></div></button>
      </div>
    </div>
  );
}

function PayoutTable({ payouts, onReview, onDecision, compact = false }) {
  return (
    <div className="overflow-x-auto"><table className="w-full min-w-[710px] text-left"><thead><tr className="border-b border-slate-100 text-[10px] font-bold uppercase tracking-[0.1em] text-slate-400"><th className="px-5 py-3">Customer</th><th className="px-4 py-3">Destination</th><th className="px-4 py-3">Amount</th><th className="px-4 py-3">Risk</th><th className="px-4 py-3">Requested</th><th className="px-5 py-3 text-right">Action</th></tr></thead><tbody>{payouts.length ? payouts.map((payout) => <tr key={payout.id} className="border-b border-slate-50 last:border-0 hover:bg-slate-50/70"><td className="px-5 py-3.5"><div className="text-xs font-semibold text-ink">{payout.name}</div><div className="mt-1 text-[10px] text-slate-400">{payout.id}</div></td><td className="px-4 py-3.5 text-xs text-slate-600">{payout.bank}</td><td className="whitespace-nowrap px-4 py-3.5 text-xs font-bold text-ink">{money(payout.amount)}</td><td className="px-4 py-3.5"><Status>{payout.risk}</Status></td><td className="px-4 py-3.5 text-[10px] text-slate-500">{payout.requested}</td><td className="px-5 py-3.5 text-right"><div className="flex justify-end gap-1.5"><Button variant="secondary" className="min-h-8 px-2.5 py-1.5 text-[10px]" onClick={() => onReview?.(payout)}>View</Button>{!compact && <Button className="min-h-8 px-2.5 py-1.5 text-[10px]" onClick={() => onDecision(payout, 'Approved')}>Approve</Button>}</div></td></tr>) : <tr><td colSpan="6" className="p-10 text-center text-xs text-slate-400">No payouts are waiting for review.</td></tr>}</tbody></table></div>
  );
}

function Payouts({ payouts, onDecision }) {
  const [selected, setSelected] = useState(null);
  return (
    <div className="fade-in">
      <PageHeader title="Payout approvals" description="Review high-value or flagged outbound payments before release." actions={<Button variant="secondary"><Filter size={15} /> Filter queue</Button>} />
      <div className="mb-5 grid gap-3 sm:grid-cols-3"><Metric label="Awaiting review" value={String(payouts.length)} icon={FileClock} /><Metric label="Total value" value={money(payouts.reduce((sum, p) => sum + p.amount, 0))} icon={Banknote} color="green" /><Metric label="Flagged by risk" value={String(payouts.filter((p) => p.risk === 'High').length)} icon={ShieldAlert} color="amber" /></div>
      <Panel className="overflow-hidden"><div className="flex items-center justify-between border-b border-slate-100 px-5 py-4"><div><h2 className="text-sm font-bold text-ink">Pending payouts</h2><p className="mt-1 text-[10px] text-slate-400">Check recipient and amount before approving</p></div><Button variant="secondary" className="min-h-8 px-3 py-1.5 text-xs"><RefreshCw size={13} /> Refresh</Button></div><PayoutTable payouts={payouts} onReview={setSelected} onDecision={onDecision} /></Panel>
      {selected && <div className="fixed inset-0 z-50 grid place-items-center bg-slate-950/40 p-4" onClick={() => setSelected(null)}><Panel className="w-full max-w-lg p-6" onClick={(e) => e.stopPropagation()}><div className="flex items-start justify-between"><div><p className="text-[10px] font-bold uppercase tracking-wider text-gold-800">Payout review</p><h2 className="mt-1 text-xl font-bold text-ink">{selected.name}</h2><p className="mt-1 text-xs text-slate-400">{selected.id}</p></div><button onClick={() => setSelected(null)} className="rounded-lg p-2 text-slate-400 hover:bg-slate-100"><X size={17} /></button></div><div className="mt-5 rounded-2xl bg-slate-50 p-4 text-center"><div className="text-[10px] font-semibold uppercase tracking-wider text-slate-400">Payout amount</div><div className="mt-1 text-[28px] font-bold text-ink">{money(selected.amount)}</div></div><div className="my-4 space-y-3">{[['Destination', selected.bank], ['Risk assessment', selected.risk], ['Requested', selected.requested], ['Review notes', 'No prior payout history to this account.']].map(([k, v]) => <div key={k} className="flex justify-between gap-4 border-b border-slate-100 pb-2 text-xs"><span className="text-slate-500">{k}</span><span className="text-right font-semibold text-ink">{v}</span></div>)}</div><div className="flex gap-2"><Button variant="secondary" className="flex-1" onClick={() => { onDecision(selected, 'Rejected'); setSelected(null); }}>Reject</Button><Button className="flex-1" onClick={() => { onDecision(selected, 'Approved'); setSelected(null); }}>Approve payout <Check size={14} /></Button></div><p className="mt-3 text-center text-[10px] text-slate-400">Actions will be recorded in the audit log.</p></Panel></div>}
    </div>
  );
}

function RiskAlerts({ showToast }) {
  const [alerts, setAlerts] = useState([
    { id: 'RISK-2081', title: 'Unusual transfer velocity', customer: 'Elijah Okafor · •••• 8070', detail: '6 transfers in 4 minutes · ₦420,000 total', level: 'High', time: '4 min ago' },
    { id: 'RISK-2079', title: 'Large payout to new beneficiary', customer: 'Tunde Bello · •••• 1160', detail: 'Payout is 92% of available balance', level: 'High', time: '18 min ago' },
    { id: 'RISK-2074', title: 'New device sign-in', customer: 'Chika Nwosu · •••• 7004', detail: 'New device detected from an unfamiliar location', level: 'Review', time: '1 hour ago' },
  ]);
  return <div className="fade-in"><PageHeader title="Risk alerts" description="Review activity flagged by the fraud monitoring service." actions={<Button variant="secondary"><Filter size={15} /> Filters</Button>} /><div className="mb-4 flex items-center gap-2 rounded-xl border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900"><ShieldAlert size={16} /> Alerts are signals for review, not a final decision.</div><div className="space-y-3">{alerts.map((alert) => <Panel key={alert.id} className="p-5"><div className="flex flex-col gap-4 sm:flex-row sm:items-start"><span className={`grid h-10 w-10 shrink-0 place-items-center rounded-xl ${alert.level === 'High' ? 'bg-rose-50 text-rose-600' : 'bg-amber-50 text-amber-700'}`}><ShieldAlert size={18} /></span><div className="min-w-0 flex-1"><div className="flex flex-wrap items-center gap-2"><h2 className="text-sm font-bold text-ink">{alert.title}</h2><Status>{alert.level}</Status></div><p className="mt-1 text-xs text-slate-600">{alert.customer}</p><p className="mt-2 text-xs text-slate-500">{alert.detail}</p><p className="mt-2 text-[10px] text-slate-400">{alert.id} · {alert.time}</p></div><div className="flex gap-2 sm:flex-col"><Button variant="secondary" className="min-h-8 px-3 py-1 text-[11px]" onClick={() => { setAlerts((old) => old.filter((item) => item.id !== alert.id)); showToast('Alert dismissed.'); }}>Dismiss</Button><Button className="min-h-8 px-3 py-1 text-[11px]" onClick={() => { setAlerts((old) => old.filter((item) => item.id !== alert.id)); showToast('Alert marked as reviewed.'); }}>Review</Button></div></div></Panel>)}</div>{alerts.length === 0 && <Panel className="p-10 text-center"><ShieldCheck className="mx-auto text-green-600" /><p className="mt-3 text-sm font-semibold text-ink">You’re all caught up</p><p className="mt-1 text-xs text-slate-400">No alerts currently need your attention.</p></Panel>}</div>;
}

function AuditLog() {
  const events = [
    ['Payout approved', 'Eli Operations · PO-4882', 'Today, 10:12 AM', 'Approved'],
    ['Risk alert reviewed', 'Eli Operations · RISK-2061', 'Today, 09:54 AM', 'Reviewed'],
    ['Customer account blocked', 'Eli Operations · ACC-7751', 'Today, 09:18 AM', 'Security'],
    ['Payout rejected', 'Eli Operations · PO-4878', 'Yesterday, 04:42 PM', 'Rejected'],
    ['Reconciliation completed', 'System · Daily ledger check', 'Yesterday, 03:00 AM', 'System'],
  ];
  return <div className="fade-in"><PageHeader title="Audit log" description="A clear, time-stamped record of administrative and system activity." actions={<Button variant="secondary"><Download size={15} /> Export log</Button>} /><Panel className="overflow-hidden"><div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 p-5"><div><h2 className="text-sm font-bold text-ink">Recent events</h2><p className="mt-1 text-[10px] text-slate-400">Events are append-only and cannot be edited.</p></div><div className="flex gap-2"><Button variant="secondary" className="min-h-8 px-3 py-1.5 text-xs"><Search size={13} /> Search</Button><Button variant="secondary" className="min-h-8 px-3 py-1.5 text-xs"><Filter size={13} /> Filter</Button></div></div><div className="overflow-x-auto"><table className="w-full min-w-[600px] text-left"><thead><tr className="border-b border-slate-100 text-[10px] font-bold uppercase tracking-wider text-slate-400"><th className="px-5 py-3">Event</th><th className="px-4 py-3">Actor / Reference</th><th className="px-4 py-3">Time</th><th className="px-5 py-3">Category</th></tr></thead><tbody>{events.map(([event, actor, time, type]) => <tr key={event} className="border-b border-slate-50 last:border-0"><td className="px-5 py-4 text-xs font-semibold text-ink">{event}</td><td className="px-4 py-4 text-xs text-slate-500">{actor}</td><td className="whitespace-nowrap px-4 py-4 text-[11px] text-slate-500">{time}</td><td className="px-5 py-4"><Status tone="slate">{type}</Status></td></tr>)}</tbody></table></div></Panel><p className="mt-3 text-[10px] text-slate-400">Showing sample events. Production audit records will come from the backend.</p></div>;
}

function Customers({ showToast }) {
  const [query, setQuery] = useState('');
  const customers = [['Elijah Okafor', 'elijah.okafor@email.com', '•••• 8070', 'Active', '₦4,850,250'], ['Adaeze Okafor', 'adaeze.okafor@email.com', '•••• 4821', 'Active', '₦1,240,000'], ['Tunde Bello', 'tunde.bello@email.com', '•••• 1160', 'Review', '₦890,500'], ['Chika Nwosu', 'chika.nwosu@email.com', '•••• 7004', 'Active', '₦2,310,400']];
  const results = customers.filter(([name, email]) => `${name} ${email}`.toLowerCase().includes(query.toLowerCase()));
  return <div className="fade-in"><PageHeader title="Customers" description="Search customer profiles and review account status." actions={<Button variant="secondary"><Download size={15} /> Export</Button>} /><div className="mb-4 flex items-center gap-2"><label className="relative w-full max-w-sm"><Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" /><input value={query} onChange={(e) => setQuery(e.target.value)} className="form-control pl-9" placeholder="Search name or email" /></label><Button variant="secondary"><Filter size={15} /></Button></div><Panel className="overflow-hidden"><div className="overflow-x-auto"><table className="w-full min-w-[650px] text-left"><thead><tr className="border-b border-slate-100 text-[10px] font-bold uppercase tracking-wider text-slate-400"><th className="px-5 py-3">Customer</th><th className="px-4 py-3">Account</th><th className="px-4 py-3">Status</th><th className="px-4 py-3">Balance</th><th className="px-5 py-3" /></tr></thead><tbody>{results.map(([name, email, account, status, balance]) => <tr key={email} className="border-b border-slate-50 last:border-0"><td className="px-5 py-3.5"><div className="text-xs font-semibold text-ink">{name}</div><div className="mt-1 text-[10px] text-slate-400">{email}</div></td><td className="px-4 py-3.5 text-xs text-slate-600">{account}</td><td className="px-4 py-3.5"><Status tone={status === 'Active' ? 'green' : 'amber'}>{status}</Status></td><td className="px-4 py-3.5 text-xs font-semibold text-ink">{balance}</td><td className="px-5 py-3.5 text-right"><button onClick={() => showToast(`Customer profile for ${name} is ready to connect.`)} className="text-xs font-semibold text-gold-800">View profile</button></td></tr>)}</tbody></table></div>{results.length === 0 && <p className="p-8 text-center text-xs text-slate-400">No customers found.</p>}</Panel></div>;
}

function Reconciliation({ showToast }) {
  return <div className="fade-in"><PageHeader title="Ledger reconciliation" description="Confirm the ledger is balanced and review any entries that need attention." actions={<Button onClick={() => showToast('A new reconciliation run has been queued in this demo.')}><RefreshCw size={15} /> Run reconciliation</Button>} /><Panel className="mb-5 flex flex-col gap-4 border-green-100 bg-green-50/60 p-5 sm:flex-row sm:items-center"><span className="grid h-11 w-11 shrink-0 place-items-center rounded-xl bg-white text-green-700"><CheckCheck size={21} /></span><div className="flex-1"><h2 className="text-sm font-bold text-green-900">Ledger is balanced</h2><p className="mt-1 text-xs text-green-800/70">Last reconciliation completed today at 03:00 AM.</p></div><Status tone="green">All checks passed</Status></Panel><div className="grid gap-4 sm:grid-cols-3"><Metric label="Journal entries checked" value="18,492" icon={FileCheck2} /><Metric label="Unbalanced entries" value="0" icon={Activity} color="green" /><Metric label="Pending settlements" value="2" icon={Clock3} color="amber" /></div><Panel className="mt-5 overflow-hidden"><div className="border-b border-slate-100 p-5"><h2 className="text-sm font-bold text-ink">Recent reconciliation runs</h2></div><div className="overflow-x-auto"><table className="w-full min-w-[540px] text-left"><thead><tr className="border-b border-slate-100 text-[10px] font-bold uppercase tracking-wider text-slate-400"><th className="px-5 py-3">Run</th><th className="px-4 py-3">Date</th><th className="px-4 py-3">Entries</th><th className="px-5 py-3">Result</th></tr></thead><tbody>{[['REC-1127', 'Today, 03:00 AM', '18,492'], ['REC-1126', 'Yesterday, 03:00 AM', '18,317'], ['REC-1125', '08 Nov, 03:00 AM', '18,104']].map(([id, date, entries]) => <tr key={id} className="border-b border-slate-50 last:border-0"><td className="px-5 py-4 text-xs font-semibold text-ink">{id}</td><td className="px-4 py-4 text-xs text-slate-500">{date}</td><td className="px-4 py-4 text-xs text-slate-500">{entries}</td><td className="px-5 py-4"><Status tone="green">Balanced</Status></td></tr>)}</tbody></table></div></Panel></div>;
}

function App() {
  const [view, setView] = useState('overview');
  const [accounts, setAccounts] = useState(initialAccounts);
  const [transactions, setTransactions] = useState(initialTransactions);
  const [payouts, setPayouts] = useState(initialPayouts);
  const [beneficiaries, setBeneficiaries] = useState(initialBeneficiaries);
  const [notifications, setNotifications] = useState(initialNotifications);
  const [adminMode, setAdminMode] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const [toast, setToast] = useState('');
  const [transfer, setTransfer] = useState({ type: 'Bank transfer', bank: 'Access Bank', accountNumber: '', accountId: initialAccounts[0].id, amount: '', recipientName: '', note: '' });
  const [deposit, setDeposit] = useState({ accountId: initialAccounts[0].id, amount: '' });
  const [withdrawal, setWithdrawal] = useState({ accountId: initialAccounts[0].id, bank: '', accountNumber: '', amount: '', verified: false, recipient: '' });
  const [openAccount, setOpenAccount] = useState(false);
  const [modal, setModal] = useState(null);
  const [kycStatus, setKycStatus] = useState('Not started');
  const [upgradeStatus, setUpgradeStatus] = useState('Not started');

  const notify = (message) => {
    setToast(message);
    window.setTimeout(() => setToast(''), 3000);
  };
  const unreadCount = notifications.filter((notification) => !notification.read).length;
  const moveTo = (nextView) => {
    setView(nextView);
    setMobileNavOpen(false);
    setOpenAccount(false);
  };
  const updateAccountBalance = (accountId, amount) => setAccounts((old) => old.map((account) => account.id === accountId ? { ...account, balance: Math.max(0, account.balance + amount) } : account));
  const addTransaction = (record) => setTransactions((old) => [record, ...old]);
  const addBeneficiary = (beneficiary) => {
    const duplicate = beneficiaries.some((saved) => saved.bank === beneficiary.bank && saved.accountNumber === beneficiary.accountNumber);
    if (duplicate) {
      notify('This bank account is already saved as a beneficiary.');
      return false;
    }
    setBeneficiaries((old) => [...old, { ...beneficiary, name: beneficiary.name.trim(), id: `ben-${Date.now()}` }]);
    notify('Beneficiary saved for this demo session.');
    return true;
  };
  const removeBeneficiary = (beneficiary) => {
    setBeneficiaries((old) => old.filter((saved) => saved.id !== beneficiary.id));
    notify(`${beneficiary.name} removed from your beneficiaries.`);
  };
  const sendToBeneficiary = (beneficiary) => {
    setTransfer((old) => ({ ...old, type: 'Bank transfer', bank: beneficiary.bank, accountNumber: beneficiary.accountNumber, recipientName: '', note: '' }));
    moveTo('transfers');
  };
  const markNotificationRead = (id) => setNotifications((old) => old.map((notification) => notification.id === id ? { ...notification, read: true } : notification));
  const markAllNotificationsRead = () => setNotifications((old) => old.map((notification) => ({ ...notification, read: true })));
  const submitTransfer = (event) => {
    event.preventDefault();
    if (!transfer.recipientName) {
      notify('Verify the recipient account before continuing.');
      return;
    }
    if (Number(transfer.amount) > (accounts.find((account) => account.id === transfer.accountId)?.balance || 0)) {
      notify('The amount is greater than your available balance.');
      return;
    }
    moveTo('transfer-review');
  };
  const completeTransfer = () => {
    const amount = Number(transfer.amount) || 0;
    updateAccountBalance(transfer.accountId, -amount);
    addTransaction({ id: `TXN-${Math.floor(100000 + Math.random() * 900000)}`, title: `Transfer to ${transfer.recipientName || 'Adaeze Okafor'}`, subtitle: `${transfer.bank || 'Eli Digital Bank'} · Just now`, type: 'Transfer', amount: -amount, status: 'Success' });
    moveTo('transfer-status');
  };
  const submitDeposit = (event) => {
    event.preventDefault();
    const amount = Number(deposit.amount);
    if (amount <= 0) return notify('Enter a valid deposit amount.');
    updateAccountBalance(deposit.accountId, amount);
    addTransaction({ id: `DEP-${Math.floor(100000 + Math.random() * 900000)}`, title: 'Card top-up', subtitle: 'Demo card · Just now', type: 'Deposit', amount, status: 'Success' });
    notify('Demo deposit completed successfully.');
    setDeposit((old) => ({ ...old, amount: '' }));
    moveTo('transactions');
  };
  const submitWithdrawal = (event) => {
    event.preventDefault();
    if (!withdrawal.verified) return notify('Verify the destination account before continuing.');
    const source = accounts.find((account) => account.id === withdrawal.accountId);
    if (Number(withdrawal.amount) > (source?.balance || 0)) return notify('The amount is greater than your available balance.');
    setModal({ kind: 'withdrawal', amount: Number(withdrawal.amount) });
  };
  const confirmWithdrawal = () => {
    const amount = Number(withdrawal.amount);
    updateAccountBalance(withdrawal.accountId, -amount);
    addTransaction({ id: `WDL-${Math.floor(100000 + Math.random() * 900000)}`, title: `Withdrawal to ${withdrawal.recipient || 'bank account'}`, subtitle: `${withdrawal.bank} · Just now`, type: 'Withdrawal', amount: -amount, status: 'Pending' });
    setPayouts((old) => [{ id: `PO-${Math.floor(1000 + Math.random() * 9000)}`, name: 'Elijah Okafor', bank: `${withdrawal.bank} · ${withdrawal.accountNumber.slice(-4)}`, amount, risk: 'Review', requested: 'Just now' }, ...old]);
    setModal(null);
    notify('Withdrawal request submitted for review.');
    moveTo('transactions');
  };
  const decidePayout = (payout, status) => {
    setPayouts((old) => old.filter((item) => item.id !== payout.id));
    notify(`Payout ${payout.id} ${status.toLowerCase()} in this demo.`);
  };
  const createAccount = (type, openingBalance) => {
    const lastFour = String(Math.floor(1000 + Math.random() * 9000));
    setAccounts((old) => [...old, { id: `acc-${Date.now()}`, name: type === 'Savings' ? 'Goal savings' : 'New everyday account', lastFour, number: `369361${lastFour}`, type, balance: openingBalance, status: 'Active', accent: type === 'Savings' ? 'gold' : 'gold-muted' }]);
    setOpenAccount(false);
    notify('Your new demo account is ready.');
    moveTo('accounts');
  };

  if (['login', 'register', 'forgot-password', 'verify-email'].includes(view)) return <AuthPage view={view} setView={setView} onSignIn={() => moveTo('overview')} showToast={notify} />;

  const screen = (() => {
    switch (view) {
      case 'accounts': return <Accounts accounts={accounts} setView={moveTo} setOpenAccount={setOpenAccount} />;
      case 'transfers': return <TransferForm accounts={accounts} transfer={transfer} setTransfer={setTransfer} onSubmit={submitTransfer} />;
      case 'transfer-review': return <TransferReview transfer={transfer} accounts={accounts} onConfirm={() => setModal({ kind: 'pin' })} onBack={() => moveTo('transfers')} onFlag={() => moveTo('verify')} />;
      case 'add-money': return <AddMoney accounts={accounts} deposit={deposit} setDeposit={setDeposit} onSubmit={submitDeposit} />;
      case 'withdraw': return <Withdraw accounts={accounts} withdrawal={withdrawal} setWithdrawal={setWithdrawal} onSubmit={submitWithdrawal} />;
      case 'kyc': return <IdentityVerification status={kycStatus} setStatus={setKycStatus} showToast={notify} />;
      case 'account-upgrade': return <AccountUpgrade status={upgradeStatus} setStatus={setUpgradeStatus} showToast={notify} />;
      case 'transactions': return <Transactions transactions={transactions} setView={moveTo} onSelect={(tx) => setModal({ kind: 'transaction', transaction: tx })} />;
      case 'beneficiaries': return <Beneficiaries beneficiaries={beneficiaries} onAdd={addBeneficiary} onRemove={removeBeneficiary} onSend={sendToBeneficiary} />;
      case 'notifications': return <Notifications notifications={notifications} onMarkRead={markNotificationRead} onMarkAllRead={markAllNotificationsRead} setView={moveTo} />;
      case 'verify': return <VerifyTransfer transfer={transfer} onComplete={completeTransfer} onBack={() => moveTo('transfer-review')} />;
      case 'transfer-status': return <TransferStatus transfer={transfer} setView={moveTo} transactions={transactions} />;
      case 'settings': return <SettingsPage setView={moveTo} showToast={notify} />;
      case 'security': return <SecurityPage showToast={notify} />;
      case 'account-open': return <OpenAccount onCancel={() => moveTo('accounts')} onCreate={createAccount} />;
      case 'support': return <Support showToast={notify} />;
      case 'ops-dashboard': return <AdminDashboard setView={moveTo} payouts={payouts} />;
      case 'payouts': return <Payouts payouts={payouts} onDecision={decidePayout} />;
      case 'risk-alerts': return <RiskAlerts showToast={notify} />;
      case 'audit-log': return <AuditLog />;
      case 'customers': return <Customers showToast={notify} />;
      case 'reconciliation': return <Reconciliation showToast={notify} />;
      default: return <Overview accounts={accounts} transactions={transactions} setView={moveTo} />;
    }
  })();

  return (
    <div className="min-h-screen bg-canvas xl:flex">
      <Sidebar view={view} setView={moveTo} adminMode={adminMode} setAdminMode={setAdminMode} open={mobileNavOpen} onClose={() => setMobileNavOpen(false)} unreadCount={unreadCount} />
      <div className="min-w-0 flex-1">
        <TopBar setView={moveTo} onMenu={() => setMobileNavOpen(true)} adminMode={adminMode} notifications={notifications} unreadCount={unreadCount} />
        <main className="mx-auto max-w-[1500px] px-4 py-6 sm:px-7 sm:py-8 lg:px-9">
          {viewTitles[view] && <div className="mb-3 hidden text-[10px] font-semibold uppercase tracking-[0.13em] text-slate-400 sm:block">{adminMode ? 'Operations' : 'Workspace'} <span className="mx-1 text-slate-300">/</span> <span className="text-slate-500">{viewTitles[view]}</span></div>}
          {openAccount ? <OpenAccount onCancel={() => setOpenAccount(false)} onCreate={createAccount} /> : screen}
          <footer className="mt-10 flex flex-col gap-2 border-t border-slate-200/80 py-5 text-[10px] text-slate-400 sm:flex-row sm:items-center sm:justify-between"><span>© 2026 Eli Digital Bank · Banking made clear.</span><span className="inline-flex items-center gap-1"><ShieldCheck size={12} /> Your money is protected</span></footer>
        </main>
      </div>
      {toast && <div role="status" className="fixed bottom-5 left-1/2 z-[60] flex -translate-x-1/2 items-center gap-2 rounded-xl bg-ink px-4 py-3 text-xs font-medium text-white shadow-xl"><Check size={15} className="text-green-300" />{toast}</div>}
      {modal?.kind === 'pin' && <div className="fixed inset-0 z-50 grid place-items-center bg-slate-950/45 p-4"><Panel className="w-full max-w-sm p-6"><div className="mx-auto grid h-12 w-12 place-items-center rounded-xl bg-gold-50 text-gold-800"><Fingerprint size={22} /></div><h2 className="mt-4 text-center text-lg font-bold text-ink">Enter your transaction PIN</h2><p className="mt-1 text-center text-xs text-slate-500">Authorize this transfer securely.</p><form onSubmit={(e) => { e.preventDefault(); setModal(null); completeTransfer(); }}><input aria-label="Transaction PIN" type="password" inputMode="numeric" maxLength="4" placeholder="••••" className="form-control mt-5 text-center text-xl tracking-[0.5em]" required /><Button className="mt-3 w-full" type="submit">Confirm transfer <ArrowRight size={14} /></Button><button type="button" onClick={() => setModal(null)} className="mt-3 w-full text-xs font-semibold text-slate-400">Cancel</button></form></Panel></div>}
      {modal?.kind === 'withdrawal' && <div className="fixed inset-0 z-50 grid place-items-center bg-slate-950/45 p-4"><Panel className="w-full max-w-sm p-6"><h2 className="text-lg font-bold text-ink">Review withdrawal</h2><p className="mt-1 text-xs text-slate-500">Confirm this payout request before you continue.</p><div className="my-5 rounded-xl bg-slate-50 p-4"><p className="text-[10px] text-slate-400">Amount</p><p className="mt-1 text-2xl font-bold text-ink">{money(modal.amount)}</p><p className="mt-3 text-xs text-slate-600">{withdrawal.bank} · {withdrawal.accountNumber.slice(-4)}</p><p className="mt-1 text-xs text-slate-500">{withdrawal.recipient}</p></div><Button className="w-full" onClick={confirmWithdrawal}>Submit withdrawal <ArrowRight size={14} /></Button><button onClick={() => setModal(null)} className="mt-3 w-full text-xs font-semibold text-slate-400">Cancel</button></Panel></div>}
      {modal?.kind === 'transaction' && <div className="fixed inset-0 z-50 grid place-items-center bg-slate-950/45 p-4" onClick={() => setModal(null)}><Panel className="w-full max-w-md p-6" onClick={(e) => e.stopPropagation()}><div className="flex items-start justify-between"><div><p className="text-[10px] font-bold uppercase tracking-wider text-gold-800">Transaction details</p><h2 className="mt-1 text-lg font-bold text-ink">{modal.transaction.title}</h2></div><button onClick={() => setModal(null)} className="rounded-lg p-2 text-slate-400 hover:bg-slate-100"><X size={16} /></button></div><p className={`mt-5 text-[26px] font-bold ${modal.transaction.amount > 0 ? 'text-green-700' : 'text-ink'}`}>{modal.transaction.amount > 0 ? '+' : '−'}{money(Math.abs(modal.transaction.amount))}</p><div className="mt-4 space-y-3 border-y border-slate-100 py-4">{[['Reference', modal.transaction.id], ['Type', modal.transaction.type], ['Status', modal.transaction.status], ['Date', modal.transaction.subtitle]].map(([key, value]) => <div key={key} className="flex justify-between gap-3 text-xs"><span className="text-slate-500">{key}</span><span className="text-right font-semibold text-ink">{value}</span></div>)}</div><Button variant="secondary" className="mt-4 w-full" onClick={() => window.print()}><Download size={14} /> Download receipt</Button></Panel></div>}
    </div>
  );
}

export default App;
