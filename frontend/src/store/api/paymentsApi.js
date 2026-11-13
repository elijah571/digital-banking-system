import { baseApi } from './baseApi';

export const paymentsApi = baseApi.injectEndpoints({
  endpoints: (build) => ({
    createPaymentOrder: build.mutation({
      query: (body) => ({
        url: '/api/v1/payments/create-order',
        method: 'POST',
        body,
      }),
    }),
  }),
  overrideExisting: false,
});

export const {
  useCreatePaymentOrderMutation,
} = paymentsApi;
