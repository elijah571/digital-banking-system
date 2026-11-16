import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react';

const gatewayUrl = import.meta.env.VITE_API_URL || 'http://localhost:8080';

const isPublicAuthRequest = (args) => {
  const url = typeof args === 'string' ? args : args?.url || '';
  return /^\/?api\/v1\/auth\/(register|verify-email|resend-verification|login|refresh|logout|forgot-password|reset-password)(?:[/?]|$)/.test(url);
};

const rawBaseQuery = fetchBaseQuery({
  baseUrl: gatewayUrl,
  prepareHeaders: (headers, { endpoint }) => {
    const skipAuth = headers.has('x-skip-auth');
    headers.delete('x-skip-auth');
    const publicAuthEndpoints = [
      'login', 'register', 'verifyEmail', 'resendVerification', 'refreshToken',
      'logout', 'forgotPassword', 'resetPassword',
    ];
    const token = skipAuth || publicAuthEndpoints.includes(endpoint)
      ? null
      : localStorage.getItem('accessToken');
    if (token) headers.set('authorization', `Bearer ${token}`);
    if (!headers.has('x-request-id')) headers.set('x-request-id', crypto.randomUUID());
    return headers;
  },
});

const baseQueryWithReauth = async (args, api, extraOptions) => {
  let result = await rawBaseQuery(args, api, extraOptions);
  if (result.error?.status === 401 && !isPublicAuthRequest(args)) {
    const refreshToken = localStorage.getItem('refreshToken');
    if (!refreshToken) {
      localStorage.removeItem('accessToken');
      return result;
    }
    const refreshed = await rawBaseQuery(
      { url: '/api/v1/auth/refresh', method: 'POST', body: { refreshToken }, headers: { 'x-skip-auth': 'true' } },
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
    'KycReview',
    'CryptoAsset',
    'CryptoBalance',
    'CryptoAddress',
    'CryptoTransaction',
    'CryptoReview',
  ],
  endpoints: () => ({}),
});
