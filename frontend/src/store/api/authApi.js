import { baseApi } from './baseApi';

export const authApi = baseApi.injectEndpoints({
  endpoints: (build) => ({
    register: build.mutation({
      query: (body) => ({ url: '/api/v1/auth/register', method: 'POST', body }),
    }),
    login: build.mutation({
      query: (body) => ({ url: '/api/v1/auth/login', method: 'POST', body }),
      invalidatesTags: ['User', 'Account'],
    }),
    refreshToken: build.mutation({
      query: (body) => ({ url: '/api/v1/auth/refresh', method: 'POST', body }),
    }),
    logout: build.mutation({
      query: (body) => ({ url: '/api/v1/auth/logout', method: 'POST', body }),
      invalidatesTags: ['User', 'Account'],
    }),
    currentUser: build.query({
      query: () => '/api/v1/auth/me',
      providesTags: ['User'],
    }),
    resendVerification: build.mutation({
      query: (body) => ({ url: '/api/v1/auth/resend-verification', method: 'POST', body }),
    }),
    forgotPassword: build.mutation({
      query: (body) => ({ url: '/api/v1/auth/forgot-password', method: 'POST', body }),
    }),
    resetPassword: build.mutation({
      query: (body) => ({ url: '/api/v1/auth/reset-password', method: 'POST', body }),
    }),
    changePassword: build.mutation({
      query: (body) => ({ url: '/api/v1/auth/change-password', method: 'POST', body }),
    }),
    verifyEmail: build.query({
      query: (token) => `/api/v1/auth/verify-email?token=${encodeURIComponent(token)}`,
    }),
  }),
  overrideExisting: false,
});

export const {
  useRegisterMutation,
  useLoginMutation,
  useRefreshTokenMutation,
  useLogoutMutation,
  useCurrentUserQuery,
  useLazyCurrentUserQuery,
  useResendVerificationMutation,
  useForgotPasswordMutation,
  useResetPasswordMutation,
  useChangePasswordMutation,
  useVerifyEmailQuery,
  useLazyVerifyEmailQuery,
} = authApi;
