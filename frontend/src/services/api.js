import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react';

const gatewayUrl = import.meta.env.VITE_API_URL || 'http://localhost:8080';

const rawBaseQuery = fetchBaseQuery({
  baseUrl: gatewayUrl,
  prepareHeaders: (headers) => {
    const token = localStorage.getItem('accessToken');
    if (token) headers.set('authorization', `Bearer ${token}`);
    if (!headers.has('x-request-id')) headers.set('x-request-id', crypto.randomUUID());
    return headers;
  },
});

const baseQueryWithReauth = async (args, api, extraOptions) => {
  let result = await rawBaseQuery(args, api, extraOptions);
  if (result.error?.status === 401 && !String(typeof args === 'string' ? args : args.url).endsWith('/auth/refresh')) {
    const refreshToken = localStorage.getItem('refreshToken');
    if (!refreshToken) {
      localStorage.removeItem('accessToken');
      return result;
    }
    const refreshed = await rawBaseQuery({ url: '/api/v1/auth/refresh', method: 'POST', body: { refreshToken } }, api, extraOptions);
    if (refreshed.data?.accessToken) {
      localStorage.setItem('accessToken', refreshed.data.accessToken);
      if (refreshed.data.refreshToken) localStorage.setItem('refreshToken', refreshed.data.refreshToken);
      result = await rawBaseQuery(args, api, extraOptions);
    } else {
      localStorage.removeItem('accessToken');
      localStorage.removeItem('refreshToken');
    }
  }
  return result;
};

export const bankingApi = createApi({
  reducerPath: 'bankingApi',
  baseQuery: baseQueryWithReauth,
  tagTypes: ['User', 'Account', 'Transaction', 'Deposit', 'Withdrawal', 'Banks', 'Audit', 'Reconciliation'],
  endpoints: (build) => ({
    register: build.mutation({ query: (body) => ({ url: '/api/v1/auth/register', method: 'POST', body }) }),
    login: build.mutation({ query: (body) => ({ url: '/api/v1/auth/login', method: 'POST', body }) }),
    refreshToken: build.mutation({ query: (body) => ({ url: '/api/v1/auth/refresh', method: 'POST', body }) }),
    logout: build.mutation({ query: (body) => ({ url: '/api/v1/auth/logout', method: 'POST', body }) }),
    currentUser: build.query({ query: () => '/api/v1/auth/me', providesTags: ['User'] }),
    resendVerification: build.mutation({ query: (body) => ({ url: '/api/v1/auth/resend-verification', method: 'POST', body }) }),
    forgotPassword: build.mutation({ query: (body) => ({ url: '/api/v1/auth/forgot-password', method: 'POST', body }) }),
    resetPassword: build.mutation({ query: (body) => ({ url: '/api/v1/auth/reset-password', method: 'POST', body }) }),
    changePassword: build.mutation({ query: (body) => ({ url: '/api/v1/auth/change-password', method: 'POST', body }) }),
    myAccounts: build.query({ query: () => '/api/v1/accounts/me', providesTags: ['Account'] }),
    account: build.query({ query: (number) => `/api/v1/accounts/${encodeURIComponent(number)}`, providesTags: ['Account'] }),
    accountBalance: build.query({ query: (number) => `/api/v1/accounts/${encodeURIComponent(number)}/balance`, providesTags: ['Account'] }),
    allAccounts: build.query({ query: () => '/api/v1/accounts', providesTags: ['Account'] }),
    accountStatement: build.query({ query: ({ accountNumber, afterLineId, size = 20 }) => ({ url: `/api/v1/accounts/${encodeURIComponent(accountNumber)}/statement`, params: { afterLineId, size } }) }),
    openAccount: build.mutation({ query: (body) => ({ url: '/api/v1/accounts/create', method: 'POST', body }), invalidatesTags: ['Account'] }),
    verificationStatus: build.query({ query: () => '/api/v1/accounts/verification', providesTags: ['Account'] }),
    verifyIdentity: build.mutation({ query: (body) => ({ url: '/api/v1/accounts/verification/identity', method: 'POST', body }), invalidatesTags: ['Account'] }),
    upgradeAccount: build.mutation({ query: (body) => ({ url: '/api/v1/accounts/verification/upgrade', method: 'POST', body }), invalidatesTags: ['Account'] }),
    blockAccount: build.mutation({ query: (number) => ({ url: `/api/v1/accounts/${encodeURIComponent(number)}/block`, method: 'PUT' }), invalidatesTags: ['Account'] }),
    transfer: build.mutation({ query: ({ body, idempotencyKey }) => ({ url: '/api/v1/transactions', method: 'POST', body, headers: { 'Idempotency-Key': idempotencyKey } }), invalidatesTags: ['Account', 'Transaction'] }),
    verifyTransfer: build.mutation({ query: ({ transactionId, otp }) => ({ url: `/api/v1/transactions/${encodeURIComponent(transactionId)}/verify`, params: { otp }, method: 'POST' }), invalidatesTags: ['Account', 'Transaction'] }),
    transaction: build.query({ query: (id) => `/api/v1/transactions/${encodeURIComponent(id)}`, providesTags: ['Transaction'] }),
    allTransactions: build.query({ query: () => '/api/v1/transactions', providesTags: ['Transaction'] }),
    transactionHistory: build.query({ query: (accountNumber) => `/api/v1/transactions/account/${encodeURIComponent(accountNumber)}`, providesTags: ['Transaction'] }),
    banks: build.query({ query: () => '/api/v1/banks', providesTags: ['Banks'] }),
    nameEnquiry: build.mutation({ query: (body) => ({ url: '/api/v1/transfers/name-enquiry', method: 'POST', body }) }),
    createDeposit: build.mutation({ query: ({ body, idempotencyKey }) => ({ url: '/api/v1/wallet/deposits', method: 'POST', body, headers: { 'Idempotency-Key': idempotencyKey } }), invalidatesTags: ['Deposit'] }),
    deposit: build.query({ query: (id) => `/api/v1/wallet/deposits/${encodeURIComponent(id)}`, providesTags: ['Deposit'] }),
    createWithdrawal: build.mutation({ query: ({ body, idempotencyKey }) => ({ url: '/api/v1/wallet/withdrawals', method: 'POST', body, headers: { 'Idempotency-Key': idempotencyKey } }), invalidatesTags: ['Account', 'Withdrawal'] }),
    withdrawal: build.query({ query: (id) => `/api/v1/wallet/withdrawals/${encodeURIComponent(id)}`, providesTags: ['Withdrawal'] }),
    payout: build.query({ query: (id) => `/api/v1/wallet/payouts/${encodeURIComponent(id)}`, providesTags: ['Withdrawal'] }),
    cancelWithdrawal: build.mutation({ query: (id) => ({ url: `/api/v1/wallet/withdrawals/${encodeURIComponent(id)}/cancel`, method: 'POST' }), invalidatesTags: ['Account', 'Withdrawal'] }),
    requestPinCode: build.mutation({ query: () => ({ url: '/api/v1/wallet/pin/otp', method: 'POST' }) }),
    changePin: build.mutation({ query: (body) => ({ url: '/api/v1/wallet/pin', method: 'PUT', body }) }),
    createPaymentOrder: build.mutation({ query: (body) => ({ url: '/api/v1/payments/create-order', method: 'POST', body }) }),
    users: build.query({ query: (params) => ({ url: '/api/v1/users', params }), providesTags: ['User'] }),
    pendingPayouts: build.query({ query: (params) => ({ url: '/api/v1/admin/payouts', params }), providesTags: ['Withdrawal'] }),
    user: build.query({ query: (id) => `/api/v1/users/${encodeURIComponent(id)}`, providesTags: ['User'] }),
    updateUser: build.mutation({ query: ({ id, body }) => ({ url: `/api/v1/users/${encodeURIComponent(id)}`, method: 'PATCH', body }), invalidatesTags: ['User'] }),
    adminAudit: build.query({ query: (params) => ({ url: '/api/v1/admin/audit', params }), providesTags: ['Audit'] }),
    reconciliation: build.query({ query: () => '/api/v1/admin/ledger/reconciliation', providesTags: ['Reconciliation'] }),
    adjustAccount: build.mutation({ query: ({ accountNumber, action, amount, reason, idempotencyKey }) => ({ url: `/api/v1/admin/accounts/${encodeURIComponent(accountNumber)}/${action}`, method: 'PUT', params: { amount, reason }, headers: { 'Idempotency-Key': idempotencyKey } }), invalidatesTags: ['Account', 'Reconciliation'] }),
    approvePayout: build.mutation({ query: ({ id }) => ({ url: `/api/v1/wallet/withdrawals/${encodeURIComponent(id)}/approve`, method: 'POST' }), invalidatesTags: ['Withdrawal', 'Audit'] }),
    rejectPayout: build.mutation({ query: ({ id, reason }) => ({ url: `/api/v1/wallet/withdrawals/${encodeURIComponent(id)}/reject`, method: 'POST', body: { reason } }), invalidatesTags: ['Withdrawal', 'Audit'] }),
  }),
});

export const {
  useRegisterMutation, useLoginMutation, useRefreshTokenMutation, useLogoutMutation, useCurrentUserQuery,
  useResendVerificationMutation, useForgotPasswordMutation, useResetPasswordMutation, useChangePasswordMutation,
  useMyAccountsQuery, useAccountQuery, useLazyAccountQuery, useAccountBalanceQuery, useAccountStatementQuery, useOpenAccountMutation,
  useAllAccountsQuery, useBlockAccountMutation, useVerificationStatusQuery, useVerifyIdentityMutation, useUpgradeAccountMutation,
  useTransferMutation, useVerifyTransferMutation, useTransactionQuery, useAllTransactionsQuery, useTransactionHistoryQuery,
  useBanksQuery, useNameEnquiryMutation, useCreateDepositMutation, useDepositQuery, useCreateWithdrawalMutation,
  useWithdrawalQuery, usePayoutQuery, useCancelWithdrawalMutation, useRequestPinCodeMutation, useChangePinMutation,
  useCreatePaymentOrderMutation, useUsersQuery, usePendingPayoutsQuery, useUserQuery, useUpdateUserMutation, useAdminAuditQuery, useReconciliationQuery,
  useAdjustAccountMutation, useApprovePayoutMutation, useRejectPayoutMutation,
} = bankingApi;
