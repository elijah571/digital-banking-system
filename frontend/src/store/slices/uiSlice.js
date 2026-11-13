import { createSlice } from '@reduxjs/toolkit';

const initialState = {
  view: typeof window !== 'undefined' && window.location.pathname === '/reset-password'
    ? 'reset-password'
    : (typeof localStorage !== 'undefined' && localStorage.getItem('accessToken') ? 'overview' : 'login'),
  adminMode: false,
  sidebarOpen: false,
  modal: null, // { kind: 'pin' | 'withdrawal' | 'transaction' | 'adjustment' | 'payoutReview' | null, data: any }
  toast: null, // { message: string, type: 'info' | 'success' | 'error' | 'warning', id: number }
  actionStatus: {
    // Allows any component/page to track an in-flight operation with full lifecycle
    status: 'idle', // 'idle' | 'loading' | 'success' | 'error'
    title: '',
    message: '',
    id: null,
  },
};

export const uiSlice = createSlice({
  name: 'ui',
  initialState,
  reducers: {
    setView: (state, action) => {
      state.view = action.payload;
      state.sidebarOpen = false;
    },
    setAdminMode: (state, action) => {
      state.adminMode = action.payload;
    },
    toggleAdminMode: (state) => {
      state.adminMode = !state.adminMode;
    },
    setSidebarOpen: (state, action) => {
      state.sidebarOpen = action.payload;
    },
    openModal: (state, action) => {
      state.modal = action.payload; // e.g. { kind: 'pin', data: {...} }
    },
    closeModal: (state) => {
      state.modal = null;
    },
    setToast: (state, action) => {
      state.toast = action.payload
        ? {
            message: typeof action.payload === 'string' ? action.payload : action.payload.message,
            type: action.payload?.type || 'info',
            id: Date.now(),
          }
        : null;
    },
    clearToast: (state) => {
      state.toast = null;
    },
    setActionLoading: (state, action) => {
      state.actionStatus = {
        status: 'loading',
        title: action.payload?.title || 'Processing...',
        message: action.payload?.message || 'Please wait while we complete your request.',
        id: action.payload?.id || Date.now().toString(),
      };
    },
    setActionSuccess: (state, action) => {
      state.actionStatus = {
        status: 'success',
        title: action.payload?.title || 'Completed',
        message: action.payload?.message || 'Action finished successfully.',
        id: state.actionStatus.id,
      };
    },
    setActionError: (state, action) => {
      state.actionStatus = {
        status: 'error',
        title: action.payload?.title || 'Request Failed',
        message: action.payload?.message || 'Something went wrong. Please try again.',
        id: state.actionStatus.id,
      };
    },
    clearActionStatus: (state) => {
      state.actionStatus = {
        status: 'idle',
        title: '',
        message: '',
        id: null,
      };
    },
  },
});

export const {
  setView,
  setAdminMode,
  toggleAdminMode,
  setSidebarOpen,
  openModal,
  closeModal,
  setToast,
  clearToast,
  setActionLoading,
  setActionSuccess,
  setActionError,
  clearActionStatus,
} = uiSlice.actions;

export default uiSlice.reducer;
