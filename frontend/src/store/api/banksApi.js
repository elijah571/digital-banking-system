import { baseApi } from './baseApi';

export const banksApi = baseApi.injectEndpoints({
  endpoints: (build) => ({
    banks: build.query({
      query: () => '/api/v1/banks',
      providesTags: ['Banks'],
    }),
    nameEnquiry: build.mutation({
      query: (body) => ({
        url: '/api/v1/transfers/name-enquiry',
        method: 'POST',
        body,
      }),
    }),
  }),
  overrideExisting: false,
});

export const {
  useBanksQuery,
  useLazyBanksQuery,
  useNameEnquiryMutation,
} = banksApi;
