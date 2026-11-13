import { configureStore } from '@reduxjs/toolkit';
import { baseApi } from './api/baseApi';
import uiReducer from './slices/uiSlice';

export const store = configureStore({
  reducer: {
    [baseApi.reducerPath]: baseApi.reducer,
    ui: uiReducer,
  },
  middleware: (getDefaultMiddleware) =>
    getDefaultMiddleware().concat(baseApi.middleware),
});

export * from './slices/uiSlice';
export * from './api';
