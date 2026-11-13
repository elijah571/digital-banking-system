import { baseApi } from './baseApi';

export const adminApi = baseApi.injectEndpoints({
  endpoints: (build) => ({
    users: build.query({
      query: (params) => ({ url: '/api/v1/users', params }),
      providesTags: ['User'],
    }),
    user: build.query({
      query: (id) => `/api/v1/users/${encodeURIComponent(id)}`,
      providesTags: ['User'],
    }),
    updateUser: build.mutation({
      query: ({ id, body }) => ({
        url: `/api/v1/users/${encodeURIComponent(id)}`,
        method: 'PATCH',
        body,
      }),
      invalidatesTags: ['User'],
    }),
    pendingPayouts: build.query({
      query: (params) => ({ url: '/api/v1/admin/payouts', params }),
      providesTags: ['Payout', 'Withdrawal'],
    }),
    adminAudit: build.query({
      query: (params) => ({ url: '/api/v1/admin/audit', params }),
      providesTags: ['Audit'],
    }),
    reconciliation: build.query({
      query: () => '/api/v1/admin/ledger/reconciliation',
      providesTags: ['Reconciliation'],
    }),
    adjustAccount: build.mutation({
      query: ({ accountNumber, action, amount, reason, idempotencyKey = crypto.randomUUID() }) => ({
        url: `/api/v1/admin/accounts/${encodeURIComponent(accountNumber)}/${action}`,
        method: 'PUT',
        params: { amount, reason },
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
      invalidatesTags: ['Account', 'Reconciliation', 'Audit'],
    }),
    approvePayout: build.mutation({
      query: ({ id }) => ({
        url: `/api/v1/wallet/withdrawals/${encodeURIComponent(id)}/approve`,
        method: 'POST',
      }),
      invalidatesTags: ['Payout', 'Withdrawal', 'Audit'],
    }),
    rejectPayout: build.mutation({
      query: ({ id, reason = 'Rejected by operations review' }) => ({
        url: `/api/v1/wallet/withdrawals/${encodeURIComponent(id)}/reject`,
        method: 'POST',
        body: { reason },
      }),
      invalidatesTags: ['Payout', 'Withdrawal', 'Audit'],
    }),
  }),
  overrideExisting: false,
});

export const {
  useUsersQuery,
  useLazyUsersQuery,
  useUserQuery,
  useLazyUserQuery,
  useUpdateUserMutation,
  usePendingPayoutsQuery,
  useLazyPendingPayoutsQuery,
  useAdminAuditQuery,
  useLazyAdminAuditQuery,
  useReconciliationQuery,
  useLazyReconciliationQuery,
  useAdjustAccountMutation,
  useApprovePayoutMutation,
  useRejectPayoutMutation,
} = adminApi;
