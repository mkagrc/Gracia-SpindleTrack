import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import {
  createUserWithEmailAndPassword,
  onAuthStateChanged,
  reload as reloadFirebaseUser,
  sendEmailVerification,
  signInWithEmailAndPassword,
  signInWithPopup,
  signOut as firebaseSignOut,
} from 'firebase/auth';
import { onDisconnect, ref, serverTimestamp, set } from 'firebase/database';
import api, { apiErrorMessage } from './api.js';
import { auth, database, firebaseConfigReady, googleProvider } from './firebase.js';
import { createPresenceHeartbeatManager, startPresenceHeartbeat } from './presenceHeartbeat.js';
import {
  VERIFY_BEFORE_SIGNIN_MESSAGE,
  createEmailAccountWithVerification,
  isPasswordProviderUser,
  resendVerificationForPasswordUser,
  signInEmailWithVerification,
} from './emailVerificationAuth.js';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [firebaseUser, setFirebaseUser] = useState(null);
  const [session, setSession] = useState(null);
  const [loading, setLoading] = useState(true);
  const [sessionError, setSessionError] = useState('');
  const inFlight = useRef(new Map());
  const sessionUid = useRef(null);
  const authActionInFlight = useRef(0);
  const presenceHeartbeat = useRef(null);
  if (!presenceHeartbeat.current) presenceHeartbeat.current = createPresenceHeartbeatManager(startPresenceHeartbeat);

  const syncSession = useCallback((user) => {
    if (!user) return Promise.reject(new Error('Sign in before continuing.'));
    if (!user.emailVerified) {
      const error = new Error(VERIFY_BEFORE_SIGNIN_MESSAGE);
      error.code = 'auth/email-not-verified';
      setSession(null);
      setSessionError(error.message);
      return Promise.reject(error);
    }

    const existing = inFlight.current.get(user.uid);
    if (existing) return existing;

    const request = api.post('/api/auth/session')
      .then(({ data }) => {
        sessionUid.current = user.uid;
        setSession(data);
        setSessionError('');
        return data;
      })
      .catch((error) => {
        sessionUid.current = null;
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
    if (!user || sessionUid.current !== user.uid) {
      sessionUid.current = null;
      setSession(null);
    }
    if (!user) {
      setSessionError('');
      setLoading(false);
      return;
    }
    if (!user.emailVerified) {
      setSession(null);
      if (authActionInFlight.current === 0) {
        setSessionError(VERIFY_BEFORE_SIGNIN_MESSAGE);
        if (isPasswordProviderUser(user)) {
          void firebaseSignOut(auth);
        }
      }
      setLoading(false);
      return;
    }
    setLoading(true);
    syncSession(user).catch(() => {}).finally(() => setLoading(false));
  }), [syncSession]);

  useEffect(() => {
    if (!firebaseUser || !session || sessionUid.current !== firebaseUser.uid) return undefined;
    const options = {
      database,
      user: firebaseUser,
      session,
      ref,
      onDisconnect,
      serverTimestamp,
      set,
    };
    void presenceHeartbeat.current.switchTo(options);
    return () => {
      void presenceHeartbeat.current.stop();
    };
  }, [firebaseUser, session]);

  const withAuthAction = async (work) => {
    authActionInFlight.current += 1;
    try {
      return await work();
    } finally {
      authActionInFlight.current = Math.max(0, authActionInFlight.current - 1);
    }
  };

  const signInGoogle = async () => {
    if (!firebaseConfigReady) throw new Error('Firebase frontend configuration is incomplete.');
    const result = await signInWithPopup(auth, googleProvider);
    return syncSession(result.user);
  };

  const signInEmail = async (email, password) => {
    if (!firebaseConfigReady) throw new Error('Firebase frontend configuration is incomplete.');
    return withAuthAction(() => signInEmailWithVerification({
      email,
      password,
      signIn: (addr, pass) => signInWithEmailAndPassword(auth, addr, pass),
      signOutUser: () => firebaseSignOut(auth),
      syncSession,
    }));
  };

  const createEmailAccount = async (email, password) => {
    if (!firebaseConfigReady) throw new Error('Firebase frontend configuration is incomplete.');
    return withAuthAction(() => createEmailAccountWithVerification({
      email,
      password,
      createUser: (addr, pass) => createUserWithEmailAndPassword(auth, addr, pass),
      sendVerification: (user) => sendEmailVerification(user),
      signOutUser: () => firebaseSignOut(auth),
    }));
  };

  const resendEmailVerification = async (email, password) => {
    if (!firebaseConfigReady) throw new Error('Firebase frontend configuration is incomplete.');
    return withAuthAction(() => resendVerificationForPasswordUser({
      email,
      password,
      signIn: (addr, pass) => signInWithEmailAndPassword(auth, addr, pass),
      reloadUser: (user) => reloadFirebaseUser(user),
      sendVerification: (user) => sendEmailVerification(user),
      signOutUser: () => firebaseSignOut(auth),
    }));
  };

  const signOut = async () => {
    if (presenceHeartbeat.current) await presenceHeartbeat.current.stop();
    await firebaseSignOut(auth);
  };
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
      resendEmailVerification,
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
