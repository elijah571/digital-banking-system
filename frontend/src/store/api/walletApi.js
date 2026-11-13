import { baseApi } from './baseApi';

export const walletApi = baseApi.injectEndpoints({
  endpoints: (build) => ({
    createDeposit: build.mutation({
      query: ({ body, idempotencyKey = crypto.randomUUID() }) => ({
        url: '/api/v1/wallet/deposits',
        method: 'POST',
        body,
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
      invalidatesTags: ['Deposit', 'Account'],
    }),
    deposit: build.query({
      query: (id) => `/api/v1/wallet/deposits/${encodeURIComponent(id)}`,
      providesTags: ['Deposit'],
    }),
    createWithdrawal: build.mutation({
      query: ({ body, idempotencyKey = crypto.randomUUID() }) => ({
        url: '/api/v1/wallet/withdrawals',
        method: 'POST',
        body,
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
      invalidatesTags: ['Account', 'Withdrawal', 'Payout'],
    }),
    withdrawal: build.query({
      query: (id) => `/api/v1/wallet/withdrawals/${encodeURIComponent(id)}`,
      providesTags: ['Withdrawal'],
    }),
    payout: build.query({
      query: (id) => `/api/v1/wallet/payouts/${encodeURIComponent(id)}`,
      providesTags: ['Payout', 'Withdrawal'],
    }),
    cancelWithdrawal: build.mutation({
      query: (id) => ({
        url: `/api/v1/wallet/withdrawals/${encodeURIComponent(id)}/cancel`,
        method: 'POST',
      }),
      invalidatesTags: ['Account', 'Withdrawal', 'Payout'],
    }),
    requestPinCode: build.mutation({
      query: () => ({ url: '/api/v1/wallet/pin/otp', method: 'POST' }),
    }),
    changePin: build.mutation({
      query: (body) => ({ url: '/api/v1/wallet/pin', method: 'PUT', body }),
    }),
  }),
  overrideExisting: false,
});

export const {
  useCreateDepositMutation,
  useDepositQuery,
  useLazyDepositQuery,
  useCreateWithdrawalMutation,
  useWithdrawalQuery,
  useLazyWithdrawalQuery,
  usePayoutQuery,
  useLazyPayoutQuery,
  useCancelWithdrawalMutation,
  useRequestPinCodeMutation,
  useChangePinMutation,
} = walletApi;
