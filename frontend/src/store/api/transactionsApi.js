import { baseApi } from './baseApi';

export const transactionsApi = baseApi.injectEndpoints({
  endpoints: (build) => ({
    transfer: build.mutation({
      query: ({ body, idempotencyKey = crypto.randomUUID() }) => ({
        url: '/api/v1/transactions',
        method: 'POST',
        body,
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
      invalidatesTags: ['Account', 'Transaction'],
    }),
    verifyTransfer: build.mutation({
      query: ({ transactionId, otp }) => ({
        url: `/api/v1/transactions/${encodeURIComponent(transactionId)}/verify`,
        params: { otp },
        method: 'POST',
      }),
      invalidatesTags: ['Account', 'Transaction'],
    }),
    transaction: build.query({
      query: (id) => `/api/v1/transactions/${encodeURIComponent(id)}`,
      providesTags: ['Transaction'],
    }),
    allTransactions: build.query({
      query: () => '/api/v1/transactions',
      providesTags: ['Transaction'],
    }),
    transactionHistory: build.query({
      query: (accountNumber) => `/api/v1/transactions/account/${encodeURIComponent(accountNumber)}`,
      providesTags: ['Transaction'],
    }),
  }),
  overrideExisting: false,
});

export const {
  useTransferMutation,
  useVerifyTransferMutation,
  useTransactionQuery,
  useLazyTransactionQuery,
  useAllTransactionsQuery,
  useLazyAllTransactionsQuery,
  useTransactionHistoryQuery,
  useLazyTransactionHistoryQuery,
} = transactionsApi;
