import { baseApi } from './baseApi';

export const accountsApi = baseApi.injectEndpoints({
  endpoints: (build) => ({
    myAccounts: build.query({
      query: () => '/api/v1/accounts/me',
      providesTags: ['Account'],
    }),
    account: build.query({
      query: (number) => `/api/v1/accounts/${encodeURIComponent(number)}`,
      providesTags: ['Account'],
    }),
    accountBalance: build.query({
      query: (number) => `/api/v1/accounts/${encodeURIComponent(number)}/balance`,
      providesTags: ['Account'],
    }),
    allAccounts: build.query({
      query: () => '/api/v1/accounts',
      providesTags: ['Account'],
    }),
    accountStatement: build.query({
      query: ({ accountNumber, afterLineId, size = 20 }) => ({
        url: `/api/v1/accounts/${encodeURIComponent(accountNumber)}/statement`,
        params: { afterLineId, size },
      }),
    }),
    openAccount: build.mutation({
      query: (body) => ({
        url: '/api/v1/accounts/create',
        method: 'POST',
        body,
      }),
      invalidatesTags: ['Account'],
    }),
    verificationStatus: build.query({
      query: () => '/api/v1/accounts/verification',
      providesTags: ['Account'],
    }),
    verifyIdentity: build.mutation({
      query: (body) => ({
        url: '/api/v1/accounts/verification/identity',
        method: 'POST',
        body,
      }),
      invalidatesTags: ['Account'],
    }),
    upgradeAccount: build.mutation({
      query: (body) => ({
        url: '/api/v1/accounts/verification/upgrade',
        method: 'POST',
        body,
      }),
      invalidatesTags: ['Account'],
    }),
    blockAccount: build.mutation({
      query: (number) => ({
        url: `/api/v1/accounts/${encodeURIComponent(number)}/block`,
        method: 'PUT',
      }),
      invalidatesTags: ['Account'],
    }),
  }),
  overrideExisting: false,
});

export const {
  useMyAccountsQuery,
  useLazyMyAccountsQuery,
  useAccountQuery,
  useLazyAccountQuery,
  useAccountBalanceQuery,
  useLazyAccountBalanceQuery,
  useAllAccountsQuery,
  useLazyAllAccountsQuery,
  useAccountStatementQuery,
  useLazyAccountStatementQuery,
  useOpenAccountMutation,
  useVerificationStatusQuery,
  useVerifyIdentityMutation,
  useUpgradeAccountMutation,
  useBlockAccountMutation,
} = accountsApi;
