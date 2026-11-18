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
  return error?.response?.data?.message || error?.message || fallback;
}

export default api;
