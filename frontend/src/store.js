import { configureStore } from '@reduxjs/toolkit';
import { bankingApi } from './services/api';

export const store = configureStore({
  reducer: { [bankingApi.reducerPath]: bankingApi.reducer },
  middleware: (getDefaultMiddleware) => getDefaultMiddleware().concat(bankingApi.middleware),
});
