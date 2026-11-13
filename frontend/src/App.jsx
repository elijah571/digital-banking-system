import React, { useEffect, useState } from 'react';
import {
  Sidebar,
  TopBar,
  Footer,
  Toast,
  ActionFeedbackBanner,
  PinModal,
  WithdrawalModal,
  TransactionDetailsModal,
} from './components';
import {
  OverviewPage,
  AccountsPage,
  OpenAccountPage,
  TransfersPage,
  TransferReviewPage,
  VerifyTransferPage,
  TransferStatusPage,
  AddMoneyPage,
  WithdrawPage,
  IdentityVerificationPage,
  AccountUpgradePage,
  TransactionsPage,
  BeneficiariesPage,
  NotificationsPage,
  SettingsPage,
  SecurityPage,
  SupportPage,
  OpsDashboardPage,
  PayoutsPage,
  RiskAlertsPage,
  AuditLogPage,
  CustomersPage,
  ReconciliationPage,
  AuthPage,
} from './pages';
import {
  useAccountStatementQuery,
  useAdminAuditQuery,
  useAdjustAccountMutation,
  useAllAccountsQuery,
  useAllTransactionsQuery,
  useApprovePayoutMutation,
  useBanksQuery,
  useBlockAccountMutation,
  useCreateDepositMutation,
  useCreateWithdrawalMutation,
  useCurrentUserQuery,
  useLazyAccountQuery,
  useLogoutMutation,
  useMyAccountsQuery,
  useNameEnquiryMutation,
  useOpenAccountMutation,
  usePendingPayoutsQuery,
  useReconciliationQuery,
  useRejectPayoutMutation,
  useTransactionHistoryQuery,
  useTransferMutation,
  useUpdateUserMutation,
  useUsersQuery,
  useVerificationStatusQuery,
  useVerifyTransferMutation,
} from './store/api';

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

function App() {
  const [view, setView] = useState(() =>
    typeof window !== 'undefined' && window.location.pathname === '/reset-password'
      ? 'reset-password'
      : typeof localStorage !== 'undefined' && localStorage.getItem('accessToken')
      ? 'overview'
      : 'login'
  );

  const [accounts, setAccounts] = useState([]);
  const [transactions, setTransactions] = useState([]);
  const [payouts, setPayouts] = useState([]);
  const [beneficiaries, setBeneficiaries] = useState([]);
  const [notifications, setNotifications] = useState([]);
  const [adminMode, setAdminMode] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const [toast, setToast] = useState(null);

  // Global processing action banner state
  const [actionStatus, setActionStatus] = useState({
    status: 'idle', // 'idle' | 'loading' | 'success' | 'error'
    title: '',
    message: '',
  });

  const [transfer, setTransfer] = useState({
    type: 'Bank transfer',
    bank: '',
    bankCode: '',
    nameEnquiryRef: '',
    accountNumber: '',
    accountId: '',
    amount: '',
    recipientName: '',
    note: '',
    transactionId: '',
  });

  const [deposit, setDeposit] = useState({ accountId: '', amount: '' });
  const [withdrawal, setWithdrawal] = useState({
    accountId: '',
    bank: '',
    bankCode: '',
    accountNumber: '',
    amount: '',
    verified: false,
    recipient: '',
    nameEnquiryRef: '',
  });

  const [openAccount, setOpenAccount] = useState(false);
  const [modal, setModal] = useState(null);
  const [kycStatus, setKycStatus] = useState('Not started');
  const [upgradeStatus, setUpgradeStatus] = useState('Not started');
  const [pinValue, setPinValue] = useState('');
  const [activeDeposit, setActiveDeposit] = useState(null);
  const [actionBusy, setActionBusy] = useState(false);

  const hasSession = Boolean(typeof localStorage !== 'undefined' && localStorage.getItem('accessToken'));

  // RTK Query API connections
  const { data: currentUser, isError: currentUserFailed } = useCurrentUserQuery(undefined, {
    skip: !hasSession,
  });
  const isAdmin = currentUser?.role === 'ADMIN';

  const { data: accountRows = [] } = useMyAccountsQuery(undefined, {
    skip: !hasSession,
  });

  const primaryAccountNumber = accounts[0]?.number;
  const { data: transactionRows = [] } = useTransactionHistoryQuery(primaryAccountNumber, {
    skip: !hasSession || !primaryAccountNumber,
  });

  const { data: statementData } = useAccountStatementQuery(
    { accountNumber: primaryAccountNumber, size: 200 },
    { skip: !hasSession || !primaryAccountNumber }
  );

  const { data: bankRows = [] } = useBanksQuery(undefined, { skip: !hasSession });
  const { data: verificationStatus } = useVerificationStatusQuery(undefined, {
    skip: !hasSession,
  });

  // Admin RTK Query connections
  const { data: reconciliationData } = useReconciliationQuery(undefined, {
    skip: !hasSession || !adminMode,
  });
  const { data: auditData } = useAdminAuditQuery(
    { limit: 100 },
    { skip: !hasSession || !adminMode || view !== 'audit-log' }
  );
  const { data: adminUsers = [] } = useUsersQuery(undefined, {
    skip: !hasSession || !adminMode,
  });
  const { data: adminAccounts = [] } = useAllAccountsQuery(undefined, {
    skip: !hasSession || !adminMode,
  });
  const { data: adminTransactions = [] } = useAllTransactionsQuery(undefined, {
    skip: !hasSession || !adminMode,
  });
  const { data: payoutRows = [], refetch: refetchPayouts, isFetching: payoutsFetching } = usePendingPayoutsQuery(
    { state: 'PENDING_APPROVAL' },
    { skip: !hasSession || !adminMode }
  );

  // Mutations
  const [openAccountRequest, openAccountState] = useOpenAccountMutation();
  const [submitTransferRequest, transferState] = useTransferMutation();
  const [verifyTransferRequest, verifyState] = useVerifyTransferMutation();
  const [submitDepositRequest, depositState] = useCreateDepositMutation();
  const [submitWithdrawalRequest, withdrawalState] = useCreateWithdrawalMutation();
  const [logoutRequest] = useLogoutMutation();
  const [nameEnquiryRequest] = useNameEnquiryMutation();
  const [fetchAccount] = useLazyAccountQuery();
  const [blockAccountRequest] = useBlockAccountMutation();
  const [adjustAccountRequest] = useAdjustAccountMutation();
  const [updateUserRequest] = useUpdateUserMutation();
  const [approvePayoutRequest] = useApprovePayoutMutation();
  const [rejectPayoutRequest] = useRejectPayoutMutation();

  useEffect(() => {
    if (!accountRows.length) return;
    setAccounts(
      accountRows.map((account) => ({
        id: account.id,
        number: account.accountNumber,
        name: account.accountType === 'CURRENT' ? 'Everyday account' : 'Savings account',
        lastFour: account.accountNumber.slice(-4),
        type: account.accountType,
        balance: Number(account.balance || 0),
        status: account.status,
        accent: 'gold',
      }))
    );
  }, [accountRows]);

  useEffect(() => {
    if (!transactionRows.length) {
      setTransactions([]);
      return;
    }
    setTransactions(
      transactionRows.map((tx) => ({
        id: tx.id,
        title:
          tx.description ||
          `${tx.type === 'TRANSFER' ? 'Transfer' : tx.type} ${
            tx.receiverAccountNumber ? `to ${tx.receiverAccountNumber}` : ''
          }`,
        subtitle: tx.createdAt ? new Date(tx.createdAt).toLocaleString() : tx.status,
        type: tx.type,
        amount:
          tx.senderAccountNumber === primaryAccountNumber
            ? -Number(tx.amount)
            : Number(tx.amount),
        status: tx.status,
      }))
    );
  }, [transactionRows, primaryAccountNumber]);

  useEffect(() => {
    setPayouts(
      payoutRows.map((payout) => ({
        id: String(payout.payoutId),
        payoutId: payout.payoutId,
        name: payout.destinationAccountName || 'Bank customer',
        bank: `${payout.bankCode} · ${payout.destinationAccountNumber?.slice(-4) || ''}`,
        amount: Number(payout.amount),
        risk: payout.status,
        requested: payout.status,
      }))
    );
  }, [payoutRows]);

  useEffect(() => {
    if (currentUserFailed) {
      localStorage.removeItem('accessToken');
      localStorage.removeItem('refreshToken');
      setView('login');
    }
  }, [currentUserFailed]);

  useEffect(() => {
    if (!isAdmin && adminMode) {
      setAdminMode(false);
      setView('overview');
    }
  }, [isAdmin, adminMode]);

  useEffect(() => {
    if (verificationStatus?.identityStatus) {
      setKycStatus(verificationStatus.identityStatus);
    }
    if (verificationStatus?.accountUpgradeStatus) {
      setUpgradeStatus(verificationStatus.accountUpgradeStatus);
    }
  }, [verificationStatus]);

  const notify = (message, type = 'info') => {
    setToast({ message, type });
  };

  const moveTo = (nextView) => {
    setView(nextView);
    setMobileNavOpen(false);
    setOpenAccount(false);
  };

  const signOut = async () => {
    const refreshToken = localStorage.getItem('refreshToken');
    try {
      if (refreshToken) await logoutRequest({ refreshToken }).unwrap();
    } catch {
      // Local sign-out proceeds regardless
    }
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    setAdminMode(false);
    setAccounts([]);
    setTransactions([]);
    moveTo('login');
  };

  const addBeneficiary = (beneficiary) => {
    const duplicate = beneficiaries.some(
      (saved) =>
        saved.bank === beneficiary.bank && saved.accountNumber === beneficiary.accountNumber
    );
    if (duplicate) {
      notify('This bank account is already saved as a beneficiary.', 'error');
      return false;
    }
    setBeneficiaries((old) => [
      ...old,
      { ...beneficiary, name: beneficiary.name.trim(), id: `ben-${Date.now()}` },
    ]);
    notify('Beneficiary saved successfully.', 'success');
    return true;
  };

  const removeBeneficiary = (beneficiary) => {
    setBeneficiaries((old) => old.filter((saved) => saved.id !== beneficiary.id));
    notify(`${beneficiary.name} removed from your beneficiaries.`, 'info');
  };

  const sendToBeneficiary = (beneficiary) => {
    setTransfer((old) => ({
      ...old,
      type: 'Bank transfer',
      bank: beneficiary.bank,
      accountNumber: beneficiary.accountNumber,
      recipientName: '',
      note: '',
    }));
    moveTo('transfers');
  };

  const submitTransfer = (event) => {
    event.preventDefault();
    if (!transfer.recipientName) {
      notify('Verify the recipient account before continuing.', 'error');
      return;
    }
    const source = accounts.find((account) => account.id === transfer.accountId) || accounts[0];
    if (Number(transfer.amount) > (source?.balance || 0)) {
      notify('The amount is greater than your available balance.', 'error');
      return;
    }
    moveTo('transfer-review');
  };

  const completeTransfer = async (pin = pinValue) => {
    const source = accounts.find((account) => account.id === transfer.accountId) || accounts[0];
    setActionStatus({
      status: 'loading',
      title: 'Processing transfer',
      message: `Sending ${transfer.amount} NGN to ${transfer.recipientName}...`,
    });
    setActionBusy(true);

    try {
      const result = await submitTransferRequest({
        idempotencyKey: crypto.randomUUID(),
        body: {
          senderAccountNumber: source.number,
          receiverAccountNumber: transfer.accountNumber,
          amount: Number(transfer.amount),
          description: transfer.note || `Transfer to ${transfer.recipientName}`,
          type: 'TRANSFER',
          pin,
          ...(transfer.type === 'Bank transfer'
            ? { bankCode: transfer.bankCode, nameEnquiryRef: transfer.nameEnquiryRef }
            : {}),
        },
      }).unwrap();

      const id = result.transactionId || result.id || result.payoutId;
      setTransfer((old) => ({
        ...old,
        transactionId: String(id || ''),
        status: result.status || 'PROCESSING',
        isWithdrawal: false,
        isPayout: Boolean(result.payoutId),
      }));
      setPinValue('');
      setModal(null);

      setActionStatus({
        status: 'success',
        title: 'Transfer initiated',
        message: 'Your transfer is being processed by the ledger engine.',
      });
      notify('Transfer submitted successfully.', 'success');
      moveTo(result.status === 'PENDING_VERIFICATION' ? 'verify' : 'transfer-status');
    } catch (failure) {
      const errorMsg =
        failure?.data?.detail || failure?.data?.message || 'Transfer could not be submitted. Please check your PIN and try again.';
      setActionStatus({
        status: 'error',
        title: 'Transfer failed',
        message: errorMsg,
      });
      notify(errorMsg, 'error');
    } finally {
      setActionBusy(false);
    }
  };

  const submitDeposit = async (event) => {
    event.preventDefault();
    const amount = Number(deposit.amount);
    if (amount <= 0) return notify('Enter a valid deposit amount.', 'error');
    const account = accounts.find((item) => item.id === deposit.accountId) || accounts[0];

    setActionStatus({
      status: 'loading',
      title: 'Preparing deposit',
      message: 'Creating payment intent with secure card processor...',
    });

    try {
      const result = await submitDepositRequest({
        idempotencyKey: crypto.randomUUID(),
        body: { accountNumber: account.number, amount: amount.toFixed(2) },
      }).unwrap();

      setActiveDeposit(result);
      setActionStatus({
        status: 'success',
        title: 'Deposit order ready',
        message: 'Complete the secure card payment below to credit your account.',
      });
      notify('Deposit order initialized. Enter your card details below.', 'info');
    } catch (failure) {
      const err = failure?.data?.detail || failure?.data?.message || 'Deposit could not be started.';
      setActionStatus({
        status: 'error',
        title: 'Deposit error',
        message: err,
      });
      notify(err, 'error');
    }
  };

  const submitWithdrawal = (event) => {
    event.preventDefault();
    if (!withdrawal.verified) {
      notify('Verify the destination account before continuing.', 'error');
      return;
    }
    const source = accounts.find((account) => account.id === withdrawal.accountId) || accounts[0];
    if (Number(withdrawal.amount) > (source?.balance || 0)) {
      notify('The amount is greater than your available balance.', 'error');
      return;
    }
    setPinValue('');
    setModal({ kind: 'withdrawal', amount: Number(withdrawal.amount) });
  };

  const confirmWithdrawal = async (pin = pinValue) => {
    const source = accounts.find((item) => item.id === withdrawal.accountId) || accounts[0];
    setActionStatus({
      status: 'loading',
      title: 'Authorizing withdrawal',
      message: 'Posting debit and submitting outbound clearing request...',
    });
    setActionBusy(true);

    try {
      const result = await submitWithdrawalRequest({
        idempotencyKey: crypto.randomUUID(),
        body: {
          accountNumber: source.number,
          amount: Number(withdrawal.amount).toFixed(2),
          bankCode: withdrawal.bankCode,
          destinationAccountNumber: withdrawal.accountNumber,
          nameEnquiryRef: withdrawal.nameEnquiryRef,
          narration: 'Customer withdrawal',
          pin,
        },
      }).unwrap();

      setPayouts((old) => [result, ...old]);
      setTransfer((old) => ({
        ...old,
        transactionId: String(result.payoutId),
        status: result.status,
        isWithdrawal: true,
        isPayout: false,
      }));
      setPinValue('');
      setModal(null);

      setActionStatus({
        status: 'success',
        title: 'Withdrawal submitted',
        message: 'Your payout request is being cleared.',
      });
      notify('Withdrawal request submitted.', 'success');
      moveTo('transfer-status');
    } catch (failure) {
      const err = failure?.data?.detail || failure?.data?.message || 'Withdrawal could not be submitted.';
      setActionStatus({
        status: 'error',
        title: 'Withdrawal failed',
        message: err,
      });
      notify(err, 'error');
    } finally {
      setActionBusy(false);
    }
  };

  const decidePayout = async (payout, status, reason) => {
    setActionStatus({
      status: 'loading',
      title: `Processing payout ${status.toLowerCase()}`,
      message: `Recording operations decision on payout ${payout.id}...`,
    });

    try {
      if (status === 'Approved') {
        await approvePayoutRequest({ id: payout.payoutId }).unwrap();
      } else {
        await rejectPayoutRequest({
          id: payout.payoutId,
          reason: reason || 'Rejected by operations review',
        }).unwrap();
      }
      setActionStatus({
        status: 'success',
        title: `Payout ${status.toLowerCase()}`,
        message: `Payout ${payout.id} was marked as ${status.toLowerCase()}.`,
      });
      notify(`Payout ${payout.id} was ${status.toLowerCase()}.`, 'success');
    } catch (failure) {
      const err = failure?.data?.detail || failure?.data?.message || 'The payout decision could not be saved.';
      setActionStatus({
        status: 'error',
        title: 'Decision failed',
        message: err,
      });
      notify(err, 'error');
    }
  };

  const adjustAccount = async (account, action, amount, reason) => {
    setActionStatus({
      status: 'loading',
      title: 'Posting ledger adjustment',
      message: `Recording ${action} of ₦${amount} for account ending ${account.accountNumber.slice(-4)}...`,
    });

    try {
      await adjustAccountRequest({
        accountNumber: account.accountNumber,
        action,
        amount,
        reason,
        idempotencyKey: crypto.randomUUID(),
      }).unwrap();

      setActionStatus({
        status: 'success',
        title: 'Adjustment posted',
        message: 'Audited ledger entry recorded and balance updated.',
      });
      notify(`Account adjustment recorded for account ending ${account.accountNumber.slice(-4)}.`, 'success');
    } catch (failure) {
      const err = failure?.data?.detail || failure?.data?.message || 'The adjustment could not be recorded.';
      setActionStatus({
        status: 'error',
        title: 'Adjustment error',
        message: err,
      });
      notify(err, 'error');
      throw failure;
    }
  };

  const setUserEnabled = async (user, enabled) => {
    try {
      await updateUserRequest({ id: user.id, body: { enabled } }).unwrap();
      notify(`${user.fullName} was ${enabled ? 'enabled' : 'disabled'}.`, 'success');
    } catch (failure) {
      const err = failure?.data?.detail || 'User status could not be updated.';
      notify(err, 'error');
      throw failure;
    }
  };

  const createAccount = async (type, openingBalance, accountHolderName, phone) => {
    setActionStatus({
      status: 'loading',
      title: 'Opening new account',
      message: `Generating NUBAN account and setting up ${type.toLowerCase()} ledger...`,
    });

    try {
      await openAccountRequest({
        accountHolderName,
        phone,
        accountType: type.toUpperCase(),
        initialDeposit: openingBalance,
      }).unwrap();

      setOpenAccount(false);
      setActionStatus({
        status: 'success',
        title: 'Account created',
        message: 'Your new digital bank account is active.',
      });
      notify('Your account has been opened.', 'success');
      moveTo('accounts');
    } catch (failure) {
      const err = failure?.data?.detail || failure?.data?.message || 'Account could not be opened.';
      setActionStatus({
        status: 'error',
        title: 'Account creation failed',
        message: err,
      });
      notify(err, 'error');
    }
  };

  // Auth pages router
  if (['login', 'register', 'forgot-password', 'reset-password', 'verify-email'].includes(view)) {
    return (
      <AuthPage
        view={view}
        setView={setView}
        onSignIn={() => moveTo('overview')}
        showToast={notify}
      />
    );
  }

  // Active Screen Rendering
  const screen = (() => {
    switch (view) {
      case 'accounts':
        return (
          <AccountsPage
            accounts={accounts}
            setView={moveTo}
            setOpenAccount={setOpenAccount}
            currentUser={currentUser}
          />
        );
      case 'transfers':
        return (
          <TransfersPage
            accounts={accounts}
            transfer={transfer}
            setTransfer={setTransfer}
            onSubmit={submitTransfer}
            bankOptions={bankRows}
            onEnquiry={async () => {
              if (transfer.type === 'Internal transfer') {
                const acc = await fetchAccount(transfer.accountNumber).unwrap();
                return { accountName: acc.accountHolderName };
              }
              const result = await nameEnquiryRequest({
                bankCode: transfer.bankCode,
                accountNumber: transfer.accountNumber,
              }).unwrap();
              setTransfer((old) => ({
                ...old,
                recipientName: result.accountName,
                nameEnquiryRef: result.nameEnquiryRef,
              }));
              return result;
            }}
          />
        );
      case 'transfer-review':
        return (
          <TransferReviewPage
            transfer={transfer}
            accounts={accounts}
            onConfirm={() => setModal({ kind: 'pin' })}
            onBack={() => moveTo('transfers')}
            onFlag={() => moveTo('verify')}
            isLoading={actionBusy}
          />
        );
      case 'add-money':
        return (
          <AddMoneyPage
            accounts={accounts}
            deposit={deposit}
            setDeposit={setDeposit}
            onSubmit={submitDeposit}
            activeDeposit={activeDeposit}
            onClearDeposit={() => setActiveDeposit(null)}
            isLoading={depositState.isLoading}
          />
        );
      case 'withdraw':
        return (
          <WithdrawPage
            accounts={accounts}
            withdrawal={withdrawal}
            setWithdrawal={setWithdrawal}
            onSubmit={submitWithdrawal}
            bankOptions={bankRows}
            onEnquiry={async () => {
              const result = await nameEnquiryRequest({
                bankCode: withdrawal.bankCode,
                accountNumber: withdrawal.accountNumber,
              }).unwrap();
              setWithdrawal((old) => ({
                ...old,
                recipient: result.accountName,
                nameEnquiryRef: result.nameEnquiryRef,
              }));
              return result;
            }}
          />
        );
      case 'kyc':
        return (
          <IdentityVerificationPage
            status={kycStatus}
            setStatus={setKycStatus}
            showToast={notify}
          />
        );
      case 'account-upgrade':
        return (
          <AccountUpgradePage
            status={upgradeStatus}
            setStatus={setUpgradeStatus}
            showToast={notify}
          />
        );
      case 'transactions':
        return (
          <TransactionsPage
            transactions={transactions}
            setView={moveTo}
            statement={statementData}
            onSelect={(tx) => setModal({ kind: 'transaction', transaction: tx })}
          />
        );
      case 'beneficiaries':
        return (
          <BeneficiariesPage
            beneficiaries={beneficiaries}
            onAdd={addBeneficiary}
            onRemove={removeBeneficiary}
            onSend={sendToBeneficiary}
          />
        );
      case 'notifications':
        return <NotificationsPage setView={moveTo} currentUser={currentUser} />;
      case 'verify':
        return (
          <VerifyTransferPage
            transfer={transfer}
            isLoading={verifyState.isLoading}
            onComplete={async (code) => {
              try {
                await verifyTransferRequest({
                  transactionId: transfer.transactionId,
                  otp: code,
                }).unwrap();
                moveTo('transfer-status');
              } catch (failure) {
                notify(
                  failure?.data?.detail || 'That verification code was not accepted.',
                  'error'
                );
              }
            }}
            onBack={() => moveTo('transfer-review')}
          />
        );
      case 'transfer-status':
        return <TransferStatusPage transfer={transfer} setView={moveTo} />;
      case 'settings':
        return <SettingsPage setView={moveTo} currentUser={currentUser} />;
      case 'security':
        return <SecurityPage showToast={notify} />;
      case 'account-open':
        return (
          <OpenAccountPage
            onCancel={() => moveTo('accounts')}
            onCreate={createAccount}
            isLoading={openAccountState.isLoading}
          />
        );
      case 'support':
        return <SupportPage setView={moveTo} />;
      case 'ops-dashboard':
        return (
          <OpsDashboardPage
            setView={moveTo}
            payouts={payouts}
            users={adminUsers}
            accounts={adminAccounts}
            transactions={adminTransactions}
            reconciliation={reconciliationData}
          />
        );
      case 'payouts':
        return (
          <PayoutsPage
            payouts={payouts}
            onDecision={decidePayout}
            onRefresh={refetchPayouts}
            isRefreshing={payoutsFetching}
          />
        );
      case 'risk-alerts':
        return <RiskAlertsPage transactions={adminTransactions} />;
      case 'audit-log':
        return <AuditLogPage entries={auditData?.entries || []} />;
      case 'customers':
        return (
          <CustomersPage
            users={adminUsers}
            accounts={adminAccounts}
            onAdjust={adjustAccount}
            onUserStatus={setUserEnabled}
            onBlock={async (account) => {
              try {
                await blockAccountRequest(account.accountNumber).unwrap();
                notify(
                  `Account ending in ${account.accountNumber.slice(-4)} has been blocked.`,
                  'success'
                );
              } catch (failure) {
                notify(
                  failure?.data?.detail || 'Account could not be blocked.',
                  'error'
                );
              }
            }}
          />
        );
      case 'reconciliation':
        return <ReconciliationPage data={reconciliationData} />;
      default:
        return (
          <OverviewPage
            accounts={accounts}
            transactions={transactions}
            setView={moveTo}
          />
        );
    }
  })();

  const unreadCount = notifications.filter((notification) => !notification.read).length;

  return (
    <div className="min-h-screen bg-canvas xl:flex">
      <Sidebar
        view={view}
        setView={moveTo}
        adminMode={adminMode}
        setAdminMode={setAdminMode}
        open={mobileNavOpen}
        onClose={() => setMobileNavOpen(false)}
        unreadCount={unreadCount}
        currentUser={currentUser}
        isAdmin={isAdmin}
        onLogout={signOut}
      />

      <div className="min-w-0 flex-1">
        <TopBar
          setView={moveTo}
          onMenu={() => setMobileNavOpen(true)}
          adminMode={adminMode}
          unreadCount={unreadCount}
          currentUser={currentUser}
        />

        <main className="mx-auto max-w-[1500px] px-4 py-6 sm:px-7 sm:py-8 lg:px-9">
          {viewTitles[view] && (
            <div className="mb-3 hidden text-[10px] font-semibold uppercase tracking-[0.13em] text-slate-400 sm:block">
              {adminMode ? 'Operations' : 'Workspace'}{' '}
              <span className="mx-1 text-slate-300">/</span>{' '}
              <span className="text-slate-500">{viewTitles[view]}</span>
            </div>
          )}

          {/* Action Processing Lifecycle Banner */}
          <ActionFeedbackBanner
            actionStatus={actionStatus}
            onDismiss={() => setActionStatus({ status: 'idle', title: '', message: '' })}
          />

          {openAccount ? (
            <OpenAccountPage
              onCancel={() => setOpenAccount(false)}
              onCreate={createAccount}
              isLoading={openAccountState.isLoading}
            />
          ) : (
            screen
          )}

          <Footer />
        </main>
      </div>

      {/* Floating Toast Notification */}
      <Toast toast={toast} onClose={() => setToast(null)} />

      {/* PIN Confirmation Modal */}
      {modal?.kind === 'pin' && (
        <PinModal
          isOpen={true}
          onClose={() => setModal(null)}
          onConfirm={(pin) => completeTransfer(pin)}
          isLoading={actionBusy || transferState.isLoading}
        />
      )}

      {/* Withdrawal Confirmation Modal */}
      {modal?.kind === 'withdrawal' && (
        <WithdrawalModal
          isOpen={true}
          onClose={() => setModal(null)}
          withdrawal={withdrawal}
          amount={modal.amount}
          onConfirm={(pin) => confirmWithdrawal(pin)}
          isLoading={actionBusy || withdrawalState.isLoading}
        />
      )}

      {/* Transaction Details Modal */}
      {modal?.kind === 'transaction' && (
        <TransactionDetailsModal
          isOpen={true}
          onClose={() => setModal(null)}
          transaction={modal.transaction}
        />
      )}
    </div>
  );
}

export default App;
