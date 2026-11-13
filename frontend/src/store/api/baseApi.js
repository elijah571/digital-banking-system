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
    const refreshed = await rawBaseQuery(
      { url: '/api/v1/auth/refresh', method: 'POST', body: { refreshToken } },
      api,
      extraOptions
    );
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

export const baseApi = createApi({
  reducerPath: 'bankingApi',
  baseQuery: baseQueryWithReauth,
  tagTypes: [
    'User',
    'Account',
    'Transaction',
    'Deposit',
    'Withdrawal',
    'Payout',
    'Banks',
    'Audit',
    'Reconciliation',
  ],
  endpoints: () => ({}),
});
