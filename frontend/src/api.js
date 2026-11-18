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
  return config;
});

export function apiErrorMessage(error, fallback = 'The request could not be completed. Please try again.') {
  return error?.response?.data?.message || error?.message || fallback;
}

export default api;
