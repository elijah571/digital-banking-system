import { baseApi } from './baseApi';

export const cryptoApi = baseApi.injectEndpoints({
  endpoints: (build) => ({
    cryptoAssets: build.query({ query: () => '/api/v1/crypto/assets', providesTags: ['CryptoAsset'] }),
    cryptoNetworks: build.query({ query: () => '/api/v1/crypto/networks', providesTags: ['CryptoAsset'] }),
    cryptoBalances: build.query({ query: () => '/api/v1/crypto/balances', providesTags: ['CryptoBalance'] }),
    cryptoAddresses: build.query({ query: () => '/api/v1/crypto/deposit-addresses', providesTags: ['CryptoAddress'] }),
    createCryptoAddress: build.mutation({ query: (body) => ({ url: '/api/v1/crypto/deposit-addresses', method: 'POST', body }), invalidatesTags: ['CryptoAddress'] }),
    cryptoTransactions: build.query({ query: () => '/api/v1/crypto/transactions', providesTags: ['CryptoTransaction'] }),
    createCryptoWithdrawal: build.mutation({
      query: ({ body, idempotencyKey = crypto.randomUUID() }) => ({ url: '/api/v1/crypto/withdrawals', method: 'POST', body, headers: { 'Idempotency-Key': idempotencyKey } }),
      invalidatesTags: ['CryptoBalance', 'CryptoTransaction'],
    }),
    cancelCryptoWithdrawal: build.mutation({ query: (id) => ({ url: `/api/v1/crypto/withdrawals/${encodeURIComponent(id)}/cancel`, method: 'POST' }), invalidatesTags: ['CryptoBalance', 'CryptoTransaction'] }),
    adminCryptoAssets: build.query({ query: () => '/api/v1/crypto/admin/assets', providesTags: ['CryptoAsset'] }),
    cryptoReviews: build.query({ query: () => '/api/v1/crypto/admin/reviews', providesTags: ['CryptoReview'] }),
    decideCryptoReview: build.mutation({
      query: ({ id, approve, reason }) => ({ url: `/api/v1/crypto/admin/transactions/${encodeURIComponent(id)}/${approve ? 'approve' : 'reject'}`, method: 'POST', body: approve ? undefined : { reason } }),
      invalidatesTags: ['CryptoReview', 'CryptoTransaction', 'CryptoBalance'],
    }),
    upsertCryptoAsset: build.mutation({ query: (body) => ({ url: '/api/v1/crypto/admin/assets', method: 'PUT', body }), invalidatesTags: ['CryptoAsset'] }),
  }),
});

export const {
  useCryptoAssetsQuery, useCryptoNetworksQuery, useCryptoBalancesQuery, useCryptoAddressesQuery,
  useCreateCryptoAddressMutation, useCryptoTransactionsQuery, useCreateCryptoWithdrawalMutation,
  useCancelCryptoWithdrawalMutation, useAdminCryptoAssetsQuery, useCryptoReviewsQuery,
  useDecideCryptoReviewMutation, useUpsertCryptoAssetMutation,
} = cryptoApi;
