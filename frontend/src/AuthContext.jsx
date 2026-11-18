import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import {
  createUserWithEmailAndPassword,
  onAuthStateChanged,
  sendEmailVerification,
  signInWithEmailAndPassword,
  signInWithPopup,
  signOut as firebaseSignOut,
} from 'firebase/auth';
import api, { apiErrorMessage } from './api.js';
import { auth, firebaseConfigReady, googleProvider } from './firebase.js';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [firebaseUser, setFirebaseUser] = useState(null);
  const [session, setSession] = useState(null);
  const [loading, setLoading] = useState(true);
  const [sessionError, setSessionError] = useState('');
  const inFlight = useRef(new Map());

  const syncSession = useCallback((user) => {
    if (!user) return Promise.reject(new Error('Sign in before continuing.'));
    if (!user.emailVerified) {
      const error = new Error('Please verify your email address before signing in.');
      setSession(null);
      setSessionError(error.message);
      return Promise.reject(error);
    }

    const existing = inFlight.current.get(user.uid);
    if (existing) return existing;

    const request = api.post('/api/auth/session')
      .then(({ data }) => {
        setSession(data);
        setSessionError('');
        return data;
      })
      .catch((error) => {
        const message = apiErrorMessage(error, 'Could not establish your account session.');
        setSession(null);
        setSessionError(message);
        throw new Error(message);
      })
      .finally(() => inFlight.current.delete(user.uid));

    inFlight.current.set(user.uid, request);
    return request;
  }, []);

  useEffect(() => onAuthStateChanged(auth, (user) => {
    setFirebaseUser(user);
    if (!user) {
      setSession(null);
      setSessionError('');
      setLoading(false);
      return;
    }
    if (!user.emailVerified) {
      setSession(null);
      setSessionError('Please verify your email address before signing in.');
      setLoading(false);
      return;
    }
    setLoading(true);
    syncSession(user).catch(() => {}).finally(() => setLoading(false));
  }), [syncSession]);

  const signInGoogle = async () => {
    if (!firebaseConfigReady) throw new Error('Firebase frontend configuration is incomplete.');
    const result = await signInWithPopup(auth, googleProvider);
    return syncSession(result.user);
  };

  const signInEmail = async (email, password) => {
    if (!firebaseConfigReady) throw new Error('Firebase frontend configuration is incomplete.');
    const result = await signInWithEmailAndPassword(auth, email, password);
    return syncSession(result.user);
  };

  const createEmailAccount = async (email, password) => {
    if (!firebaseConfigReady) throw new Error('Firebase frontend configuration is incomplete.');
    const result = await createUserWithEmailAndPassword(auth, email, password);
    await sendEmailVerification(result.user);
    await firebaseSignOut(auth);
    return result.user.email;
  };

  const signOut = () => firebaseSignOut(auth);
  const clearSessionError = () => setSessionError('');

  return (
    <AuthContext.Provider value={{
      firebaseUser,
      session,
      loading,
      sessionError,
      signInGoogle,
      signInEmail,
      createEmailAccount,
      signOut,
      syncSession,
      clearSessionError,
    }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used within AuthProvider.');
  return context;
}
