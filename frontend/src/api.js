import axios from 'axios';
import { auth } from './firebase.js';

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
  headers: { 'Content-Type': 'application/json' },
});

api.interceptors.request.use(async (config) => {
  const user = auth.currentUser;
  if (user) {
    config.headers = config.headers || {};
    config.headers.Authorization = `Bearer ${await user.getIdToken()}`;
  }
  if (typeof FormData !== 'undefined' && config.data instanceof FormData) {
    if (typeof config.headers?.delete === 'function') {
      config.headers.delete('Content-Type');
    } else if (config.headers) {
      delete config.headers['Content-Type'];
      delete config.headers['content-type'];
    }
  }
  return config;
});

export function apiErrorMessage(error, fallback = 'The request could not be completed. Please try again.') {
  const status = error?.response?.status;
  const data = error?.response?.data;
  const backendMessage = typeof data?.message === 'string' ? data.message : '';
  if (data?.code === 'SHOP_NOT_ACTIVE' || error?.code === 'SHOP_NOT_ACTIVE') {
    return backendMessage || 'This shop is not active yet. Shop operations are currently blocked.';
  }
  if (status === 401) return 'Your sign-in session has expired or is invalid. Sign in again to continue.';
  if (status === 403) return backendMessage || 'You do not have permission to do this, or your shop is not active.';
  if (status === 409) return backendMessage || 'The shop status changed before this action completed. Refresh and try again.';
  return backendMessage || error?.message || fallback;
}

export default api;
