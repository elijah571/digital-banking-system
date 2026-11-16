import React, { useState } from 'react';
import { Search } from 'lucide-react';
import { PageHeader } from '../../components/layout/PageHeader';
import { Panel } from '../../components/common/Panel';
import { StatusBadge } from '../../components/feedback/StatusBadge';
import { AdjustBalanceModal } from '../../components/modals/AdjustBalanceModal';
import { money } from '../../data';
import { hasPermission, isSuperAdmin } from '../../utils/permissions';

const staffRoles = [
  ['USER', 'Customer'],
  ['ROLE_SUPER_ADMIN', 'Super admin'],
  ['ROLE_COMPLIANCE_OFFICER', 'Compliance officer'],
  ['ROLE_TREASURY_APPROVER', 'Treasury approver'],
  ['ROLE_FINANCE_RECON_ADMIN', 'Finance reconciliation'],
  ['ROLE_RISK_ANALYST', 'Risk analyst'],
  ['ROLE_CUSTOMER_SUPPORT', 'Customer support'],
  ['ROLE_AUDITOR', 'Auditor'],
];

export function CustomersPage({
  users = [],
  accounts = [],
  onBlock,
  onAdjust,
  onUserStatus,
  onRoleChange,
  currentUser,
}) {
  const [query, setQuery] = useState('');
  const [adjustingAccount, setAdjustingAccount] = useState(null);
  const [adjustError, setAdjustError] = useState('');
  const [adjustBusy, setAdjustBusy] = useState(false);
  const [userActionBusy, setUserActionBusy] = useState('');
  const [blockActionBusy, setBlockActionBusy] = useState('');
  const [roleDrafts, setRoleDrafts] = useState({});
  const [roleActionBusy, setRoleActionBusy] = useState('');
  const canManageCustomers = hasPermission(currentUser, 'customers.manage');
  const canFreezeAccounts = hasPermission(currentUser, 'accounts.freeze');
  const canManageStaff = hasPermission(currentUser, 'admin.users.manage');
  const canAdjustLedger = isSuperAdmin(currentUser);

  const results = users.filter((user) =>
    `${user.fullName} ${user.email}`.toLowerCase().includes(query.toLowerCase())
  );

  const handleAdjustSubmit = async ({ account, action, amount, reason }) => {
    setAdjustError('');
    setAdjustBusy(true);
    try {
      await onAdjust?.(account, action, amount, reason);
      setAdjustingAccount(null);
    } catch (failure) {
      setAdjustError(
        failure?.data?.detail ||
          failure?.data?.message ||
          failure?.message ||
          'The balance adjustment could not be recorded.'
      );
    } finally {
      setAdjustBusy(false);
    }
  };

  const handleBlock = async (account) => {
    if (!window.confirm(`Are you sure you want to block account ending in ${account.accountNumber.slice(-4)}?`)) {
      return;
    }
    setBlockActionBusy(account.accountNumber);
    try {
      await onBlock?.(account);
    } finally {
      setBlockActionBusy('');
    }
  };

  const handleToggleUser = async (user) => {
    setUserActionBusy(user.id);
    try {
      await onUserStatus?.(user, !user.enabled);
    } catch {
      // handled by parent toast
    } finally {
      setUserActionBusy('');
    }
  };

  const handleRoleChange = async (user) => {
    const existingRole = user.role === 'ADMIN' ? 'ROLE_SUPER_ADMIN' : user.role || 'USER';
    const role = roleDrafts[user.id] || existingRole;
    if (role === existingRole) return;
    setRoleActionBusy(user.id);
    try {
      await onRoleChange?.(user, role);
      setRoleDrafts((drafts) => { const next = { ...drafts }; delete next[user.id]; return next; });
    } catch {
      // Parent reports the API failure.
    } finally {
      setRoleActionBusy('');
    }
  };

  return (
    <div className="fade-in">
      <PageHeader
        title="Customers"
        description="Search customer profiles, inspect accounts, and record audited double-entry balance adjustments."
      />

      <div className="mb-4">
        <label className="relative block w-full max-w-sm">
          <Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            className="form-control pl-9"
            placeholder="Search name or email..."
          />
        </label>
      </div>

      <Panel className="overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full min-w-[1050px] text-left">
            <thead>
              <tr className="border-b border-slate-100 text-[10px] font-bold uppercase tracking-wider text-slate-400">
                <th className="px-5 py-3">Customer</th>
                <th className="px-4 py-3">Account</th>
                <th className="px-4 py-3">Access role</th>
                <th className="px-4 py-3">Status</th>
                <th className="px-4 py-3">Balance</th>
                <th className="px-5 py-3 text-right">Operations</th>
              </tr>
            </thead>
            <tbody>
              {results.map((user) => {
                const account = accounts.find((row) => row.ownerId === user.id);
                const currentRole = user.role === 'ADMIN' ? 'ROLE_SUPER_ADMIN' : user.role || 'USER';
                const canEditRole = canManageStaff && currentUser?.id !== user.id;
                const isUserBusy = userActionBusy === user.id;
                const isBlockBusy = blockActionBusy === account?.accountNumber;

                return (
                  <tr
                    key={user.id}
                    className="border-b border-slate-50 last:border-0 hover:bg-slate-50/70 transition"
                  >
                    <td className="px-5 py-3.5">
                      <div className="text-xs font-semibold text-ink">{user.fullName}</div>
                      <div className="mt-0.5 text-[10px] text-slate-400">{user.email}</div>
                    </td>
                    <td className="px-4 py-3.5 text-xs text-slate-600 font-mono">
                      {account ? `•••• ${account.accountNumber.slice(-4)}` : 'No account'}
                    </td>
                    <td className="px-4 py-3.5">
                      {canEditRole ? <div className="flex items-center gap-2"><select aria-label={`Role for ${user.fullName}`} value={roleDrafts[user.id] ?? currentRole} onChange={(event) => setRoleDrafts((drafts) => ({ ...drafts, [user.id]: event.target.value }))} className="max-w-44 rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-[11px]"><option value="USER">Customer</option>{staffRoles.filter(([role]) => role !== 'USER').map(([role, label]) => <option key={role} value={role}>{label}</option>)}</select>{(roleDrafts[user.id] ?? currentRole) !== currentRole && <button disabled={roleActionBusy === user.id} onClick={() => handleRoleChange(user)} className="text-[11px] font-semibold text-gold-800 disabled:opacity-50">{roleActionBusy === user.id ? 'Saving…' : 'Save'}</button>}</div> : <StatusBadge tone="slate">{user.role?.replace(/^ROLE_/, '').replaceAll('_', ' ') || 'USER'}</StatusBadge>}
                    </td>
                    <td className="px-4 py-3.5">
                      <StatusBadge tone={user.enabled ? 'green' : 'red'}>
                        {user.enabled ? 'Active' : 'Disabled'}
                      </StatusBadge>
                    </td>
                    <td className="px-4 py-3.5 text-xs font-semibold text-ink">
                      {account ? money(Number(account.balance)) : '—'}
                    </td>
                    <td className="px-5 py-3.5 text-right">
                      <div className="flex justify-end gap-3 items-center">
                        {canManageCustomers && currentUser?.id !== user.id && (
                          <button
                            disabled={isUserBusy}
                            onClick={() => handleToggleUser(user)}
                            className={`text-xs font-semibold transition ${
                              user.enabled
                                ? 'text-rose-700 hover:text-rose-800'
                                : 'text-green-700 hover:text-green-800'
                            } disabled:opacity-50`}
                          >
                            {isUserBusy
                              ? 'Saving…'
                              : user.enabled
                              ? 'Disable user'
                              : 'Enable user'}
                          </button>
                        )}
                        {account?.status === 'ACTIVE' && (canAdjustLedger || canFreezeAccounts) && (
                          <>
                            {canAdjustLedger && <button
                              onClick={() => {
                                setAdjustError('');
                                setAdjustingAccount(account);
                              }}
                              className="text-xs font-semibold text-gold-800 hover:text-gold-900 transition"
                            >
                              Adjust balance
                            </button>}
                            {canFreezeAccounts && <button
                              disabled={isBlockBusy}
                              onClick={() => handleBlock(account)}
                              className="text-xs font-semibold text-rose-700 hover:text-rose-800 transition disabled:opacity-50"
                            >
                              {isBlockBusy ? 'Blocking…' : 'Block account'}
                            </button>}
                          </>
                        )}
                        {account?.status === 'BLOCKED' && (
                          <span className="text-[11px] font-semibold text-rose-600">
                            Blocked
                          </span>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>

        {results.length === 0 && (
          <p className="p-8 text-center text-xs text-slate-400">
            No customer records found matching your search.
          </p>
        )}
      </Panel>

      {adjustingAccount && (
        <AdjustBalanceModal
          isOpen={Boolean(adjustingAccount)}
          onClose={() => setAdjustingAccount(null)}
          account={adjustingAccount}
          onSubmit={handleAdjustSubmit}
          isLoading={adjustBusy}
          error={adjustError}
        />
      )}
    </div>
  );
}

export default CustomersPage;
