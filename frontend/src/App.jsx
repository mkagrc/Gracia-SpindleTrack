import { useEffect, useState } from 'react';
import { Link, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import {
  ArrowRight,
  Check,
  Droplets,
  LoaderCircle,
  LogOut,
  ShieldCheck,
  Sparkles,
  Store,
} from 'lucide-react';
import {
  createUserWithEmailAndPassword,
  sendEmailVerification,
  signOut as firebaseSignOut,
} from 'firebase/auth';
import { auth } from './firebase.js';
import { apiErrorMessage } from './api.js';
import api from './api.js';
import { useAuth } from './AuthContext.jsx';

const setupItems = [
  'Google and email/password sign-in',
  'Verified Firebase account sessions',
  'Shop registration with admin approval',
];

function LoadingState({ label = 'Loading…' }) {
  return (
    <div className="flex min-h-56 flex-1 items-center justify-center px-5" role="status">
      <LoaderCircle className="mr-3 animate-spin text-brand" size={22} aria-hidden="true" />
      <span className="text-sm text-slate-600">{label}</span>
    </div>
  );
}

function HomePage() {
  const { session } = useAuth();
  const registerPath = session?.role === 'OWNER' ? '/shop-status' : '/register-shop';
  return (
    <main className="mx-auto grid w-full max-w-6xl flex-1 items-center gap-12 px-5 py-12 sm:px-8 lg:grid-cols-[1.1fr_0.9fr] lg:py-20">
      <section>
        <div className="mb-6 inline-flex items-center gap-2 rounded-full border border-teal-100 bg-white px-3 py-1.5 text-sm font-medium text-teal-800 shadow-sm">
          <Sparkles size={15} aria-hidden="true" />
          Laundry operations, made simpler
        </div>
        <h1 className="max-w-2xl text-4xl font-semibold tracking-tight text-ink sm:text-5xl lg:text-6xl">
          Good laundry days start with <span className="text-brand">SpindleTrack.</span>
        </h1>
        <p className="mt-5 max-w-xl text-lg leading-8 text-slate-600">
          Register your laundry shop and submit it for platform review before you begin operating.
        </p>
        <div className="mt-8 flex flex-wrap items-center gap-3">
          <Link to={registerPath} className="inline-flex min-h-12 items-center justify-center gap-2 rounded-xl bg-brand px-5 py-3 font-semibold text-white shadow-sm transition hover:bg-teal-700 focus:outline-none focus:ring-4 focus:ring-teal-100">
            Register your laundry shop <ArrowRight size={18} aria-hidden="true" />
          </Link>
          {!session && <Link to="/sign-in" className="inline-flex min-h-12 items-center justify-center rounded-xl border border-slate-200 bg-white px-5 py-3 font-semibold text-slate-700 transition hover:bg-slate-50">Sign in</Link>}
        </div>
      </section>

      <section aria-label="Account and registration status" className="rounded-3xl border border-slate-100 bg-white p-6 shadow-soft sm:p-8">
        <div className="flex items-start justify-between gap-4">
          <div>
            <p className="text-sm font-semibold uppercase tracking-[0.14em] text-slate-500">Getting started</p>
            <h2 className="mt-2 text-2xl font-semibold text-ink">Verified accounts, reviewed shops</h2>
          </div>
          <div className="grid h-12 w-12 shrink-0 place-items-center rounded-2xl bg-teal-50 text-brand"><Store size={23} aria-hidden="true" /></div>
        </div>
        <ul className="mt-7 space-y-4">
          {setupItems.map((item) => <li key={item} className="flex items-center gap-3 text-slate-700"><span className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-emerald-50 text-emerald-700"><Check size={16} aria-hidden="true" /></span>{item}</li>)}
        </ul>
        <div className="mt-8 flex items-start gap-3 rounded-2xl bg-slate-50 p-4 text-sm leading-6 text-slate-600">
          <ShieldCheck size={19} className="mt-0.5 shrink-0 text-slate-500" aria-hidden="true" />
          A shop stays pending until a platform administrator reviews it. Sign in with a verified email to submit a registration.
        </div>
      </section>
    </main>
  );
}

function SignInPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { signInGoogle, signInEmail, createEmailAccount, sessionError, clearSessionError } = useAuth();
  const [mode, setMode] = useState('signin');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const nextPath = location.state?.from?.pathname || '/';

  const presentError = (reason) => {
    const code = reason?.code || '';
    if (code.includes('popup-closed-by-user')) return 'The Google sign-in window was closed before finishing.';
    if (code.includes('invalid-credential') || code.includes('wrong-password') || code.includes('user-not-found')) return 'Email or password was not recognized.';
    if (code.includes('email-already-in-use')) return 'An account already uses this email. Sign in instead.';
    if (code.includes('weak-password')) return 'Use a password with at least 6 characters.';
    return reason?.message || 'Sign-in could not be completed. Please try again.';
  };

  const handleGoogle = async () => {
    setBusy(true); setError(''); setNotice(''); clearSessionError();
    try { await signInGoogle(); navigate(nextPath, { replace: true }); }
    catch (reason) { setError(presentError(reason)); }
    finally { setBusy(false); }
  };

  const handleSubmit = async (event) => {
    event.preventDefault(); setBusy(true); setError(''); setNotice(''); clearSessionError();
    try {
      if (mode === 'create') {
        if (password !== confirmPassword) throw new Error('Passwords do not match.');
        const credential = await createUserWithEmailAndPassword(auth, email.trim(), password);
        await sendEmailVerification(credential.user);
        await firebaseSignOut(auth);
        setMode('signin');
        setPassword(''); setConfirmPassword('');
        setNotice(`Verification email sent to ${email.trim()}. Verify your email, then sign in.`);
      } else {
        await signInEmail(email.trim(), password);
        navigate(nextPath, { replace: true });
      }
    } catch (reason) { setError(presentError(reason)); }
    finally { setBusy(false); }
  };

  return (
    <main className="mx-auto flex w-full max-w-xl flex-1 items-center px-5 py-12">
      <section className="w-full rounded-3xl border border-slate-100 bg-white p-7 shadow-soft sm:p-10">
        <div className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-teal-50 text-brand"><Droplets size={28} aria-hidden="true" /></div>
        <h1 className="mt-5 text-center text-2xl font-semibold text-ink">{mode === 'create' ? 'Create your account' : 'Welcome back'}</h1>
        <p className="mt-2 text-center text-sm leading-6 text-slate-600">
          Use Google or email and password. Email/password accounts must verify their email before continuing.
        </p>

        {(error || sessionError) && <div role="alert" className="mt-6 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">{error || sessionError}</div>}
        {notice && <div role="status" className="mt-6 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-800">{notice}</div>}

        <button type="button" onClick={handleGoogle} disabled={busy} className="mt-7 inline-flex min-h-12 w-full items-center justify-center gap-3 rounded-xl border border-slate-200 bg-white px-4 font-semibold text-slate-700 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-60">
          {busy ? <LoaderCircle className="animate-spin" size={18} /> : <span className="grid h-6 w-6 place-items-center rounded-full border border-slate-200 text-sm font-bold text-blue-600">G</span>}
          Continue with Google
        </button>

        <div className="my-6 flex items-center gap-3 text-xs uppercase tracking-wide text-slate-400"><span className="h-px flex-1 bg-slate-200" />or email and password<span className="h-px flex-1 bg-slate-200" /></div>
        <form onSubmit={handleSubmit} className="space-y-4">
          <label className="block text-sm font-medium text-slate-700">Email address
            <input required type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} className="mt-1.5 min-h-12 w-full rounded-xl border border-slate-300 px-3.5 outline-none focus:border-brand focus:ring-4 focus:ring-teal-50" />
          </label>
          <label className="block text-sm font-medium text-slate-700">Password
            <input required type="password" minLength={6} autoComplete={mode === 'create' ? 'new-password' : 'current-password'} value={password} onChange={(event) => setPassword(event.target.value)} className="mt-1.5 min-h-12 w-full rounded-xl border border-slate-300 px-3.5 outline-none focus:border-brand focus:ring-4 focus:ring-teal-50" />
          </label>
          {mode === 'create' && <label className="block text-sm font-medium text-slate-700">Confirm password
            <input required type="password" minLength={6} autoComplete="new-password" value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} className="mt-1.5 min-h-12 w-full rounded-xl border border-slate-300 px-3.5 outline-none focus:border-brand focus:ring-4 focus:ring-teal-50" />
          </label>}
          <button disabled={busy} className="inline-flex min-h-12 w-full items-center justify-center rounded-xl bg-brand px-5 py-3 font-semibold text-white shadow-sm transition hover:bg-teal-700 disabled:cursor-not-allowed disabled:opacity-60">
            {busy ? 'Please wait…' : mode === 'create' ? 'Create account and verify email' : 'Sign in'}
          </button>
        </form>

        <p className="mt-5 text-center text-sm text-slate-600">
          {mode === 'create' ? 'Already have an account?' : 'New to SpindleTrack?'}{' '}
          <button type="button" onClick={() => { setMode(mode === 'create' ? 'signin' : 'create'); setError(''); setNotice(''); }} className="min-h-11 px-1 font-semibold text-brand hover:text-teal-800">
            {mode === 'create' ? 'Sign in' : 'Create an account'}
          </button>
        </p>
        <p className="mt-2 text-center text-xs leading-5 text-slate-500">A verified email is required. New accounts start as customers and can submit one shop registration.</p>
        <div className="mt-5 text-center"><Link to="/" className="inline-flex min-h-11 items-center rounded-xl px-4 font-semibold text-brand hover:bg-teal-50">Back to home</Link></div>
      </section>
    </main>
  );
}

function ProtectedRoute({ children, ownerOnly = false }) {
  const { loading, session } = useAuth();
  const location = useLocation();
  if (loading) return <LoadingState label="Checking your verified account…" />;
  if (!session) return <Navigate to="/sign-in" replace state={{ from: location }} />;
  if (ownerOnly && session.role !== 'OWNER') return <EligibilityMessage />;
  if (!ownerOnly && session.role === 'OWNER') return <Navigate to="/shop-status" replace />;
  if (!ownerOnly && session.role !== 'CUSTOMER') return <EligibilityMessage />;
  return children;
}

function EligibilityMessage() {
  return (
    <main className="mx-auto flex w-full max-w-xl flex-1 items-center px-5 py-14">
      <section className="w-full rounded-3xl border border-slate-100 bg-white p-8 text-center shadow-soft">
        <ShieldCheck className="mx-auto text-brand" size={30} aria-hidden="true" />
        <h1 className="mt-4 text-2xl font-semibold text-ink">Please use another account</h1>
        <p className="mt-3 text-slate-600">This account cannot register another laundry shop. Sign in with an eligible customer account to continue.</p>
        <Link to="/" className="mt-6 inline-flex min-h-11 items-center rounded-xl px-4 font-semibold text-brand hover:bg-teal-50">Back to home</Link>
      </section>
    </main>
  );
}

function ShopRegistrationPage() {
  const navigate = useNavigate();
  const { firebaseUser, syncSession } = useAuth();
  const [form, setForm] = useState({ shopName: '', address: '', contactNumber: '' });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const update = (field) => (event) => setForm((current) => ({ ...current, [field]: event.target.value }));
  const submit = async (event) => {
    event.preventDefault(); setBusy(true); setError('');
    try {
      await api.post('/api/shops/register', form);
      await syncSession(firebaseUser);
      navigate('/shop-status', { replace: true });
    } catch (reason) { setError(apiErrorMessage(reason, 'Shop registration could not be submitted.')); }
    finally { setBusy(false); }
  };
  return (
    <main className="mx-auto w-full max-w-3xl flex-1 px-5 py-10 sm:px-8 sm:py-14">
      <section className="rounded-3xl border border-slate-100 bg-white p-6 shadow-soft sm:p-9">
        <div className="flex items-start gap-4">
          <div className="grid h-12 w-12 shrink-0 place-items-center rounded-2xl bg-teal-50 text-brand"><Store size={24} aria-hidden="true" /></div>
          <div><p className="text-sm font-semibold uppercase tracking-[0.14em] text-slate-500">Owner registration</p><h1 className="mt-1 text-2xl font-semibold text-ink sm:text-3xl">Register your laundry shop</h1><p className="mt-2 text-sm leading-6 text-slate-600">Your shop will remain pending until a platform administrator reviews it.</p></div>
        </div>
        {error && <div role="alert" className="mt-6 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">{error}</div>}
        <form onSubmit={submit} className="mt-8 space-y-5">
          <label className="block text-sm font-medium text-slate-700">Shop name
            <input required maxLength={160} value={form.shopName} onChange={update('shopName')} autoComplete="organization" className="mt-1.5 min-h-12 w-full rounded-xl border border-slate-300 px-3.5 outline-none focus:border-brand focus:ring-4 focus:ring-teal-50" placeholder="e.g. Fresh Fold Laundry" />
          </label>
          <label className="block text-sm font-medium text-slate-700">Shop address
            <textarea required maxLength={500} rows={3} value={form.address} onChange={update('address')} autoComplete="street-address" className="mt-1.5 w-full rounded-xl border border-slate-300 px-3.5 py-3 outline-none focus:border-brand focus:ring-4 focus:ring-teal-50" placeholder="Street, barangay, city, and province" />
          </label>
          <label className="block text-sm font-medium text-slate-700">Contact number
            <input required type="tel" minLength={7} maxLength={32} value={form.contactNumber} onChange={update('contactNumber')} autoComplete="tel" className="mt-1.5 min-h-12 w-full rounded-xl border border-slate-300 px-3.5 outline-none focus:border-brand focus:ring-4 focus:ring-teal-50" placeholder="e.g. +63 917 000 0000" />
          </label>
          <div className="rounded-2xl bg-slate-50 p-4 text-sm leading-6 text-slate-600">Business details only are collected in this step. Proof-of-business document upload will be added in the next step.</div>
          <button disabled={busy} className="inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-xl bg-brand px-5 py-3 font-semibold text-white shadow-sm transition hover:bg-teal-700 disabled:cursor-not-allowed disabled:opacity-60 sm:w-auto">
            {busy ? <><LoaderCircle className="animate-spin" size={18} />Submitting…</> : <>Submit for review <ArrowRight size={18} /></>}
          </button>
        </form>
      </section>
    </main>
  );
}

function ShopStatusPage() {
  const [registration, setRegistration] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    api.get('/api/shops/mine/registration')
      .then(({ data }) => { if (active) setRegistration(data); })
      .catch((reason) => { if (active) setError(apiErrorMessage(reason)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);
  if (loading) return <LoadingState label="Loading shop status…" />;
  return (
    <main className="mx-auto w-full max-w-3xl flex-1 px-5 py-10 sm:px-8 sm:py-14">
      <section className="rounded-3xl border border-slate-100 bg-white p-6 shadow-soft sm:p-9">
        {error ? <div role="alert" className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">{error}</div> : registration && <>
          <div className="flex items-start gap-4"><div className="grid h-12 w-12 shrink-0 place-items-center rounded-2xl bg-teal-50 text-brand"><Store size={24} aria-hidden="true" /></div><div><p className="text-sm font-semibold uppercase tracking-[0.14em] text-slate-500">Shop registration</p><h1 className="mt-1 text-2xl font-semibold text-ink sm:text-3xl">{registration.shopName}</h1></div></div>
          <div className="mt-7 inline-flex rounded-full bg-amber-50 px-3.5 py-1.5 text-sm font-semibold text-amber-800">{registration.status}</div>
          <h2 className="mt-5 text-xl font-semibold text-ink">{registration.status === 'PENDING' ? 'Waiting for approval' : registration.status === 'ACTIVE' ? 'Your shop is approved' : registration.status === 'REJECTED' ? 'Registration needs changes' : 'Shop deactivated'}</h2>
          <p className="mt-2 leading-7 text-slate-600">{registration.status === 'PENDING' ? 'Your registration has been received. Shop operations will be available after an administrator approves it.' : registration.status === 'REJECTED' ? (registration.rejectionReason || 'Contact the platform administrator for more information.') : registration.status === 'DEACTIVATED' ? 'Your shop is currently deactivated. Contact the platform administrator for assistance.' : 'Your shop registration is active.'}</p>
          <dl className="mt-7 grid gap-4 rounded-2xl bg-slate-50 p-5 text-sm sm:grid-cols-2"><div><dt className="font-medium text-slate-500">Address</dt><dd className="mt-1 text-slate-800">{registration.address}</dd></div><div><dt className="font-medium text-slate-500">Contact</dt><dd className="mt-1 text-slate-800">{registration.contactNumber}</dd></div></dl>
        </>}
        <Link to="/" className="mt-7 inline-flex min-h-11 items-center rounded-xl px-4 font-semibold text-brand hover:bg-teal-50">Back to home</Link>
      </section>
    </main>
  );
}

function AppHeader() {
  const navigate = useNavigate();
  const { session, signOut } = useAuth();
  const doSignOut = async () => { await signOut(); navigate('/'); };
  return (
    <header className="border-b border-slate-200/80 bg-white/90">
      <div className="mx-auto flex min-h-16 max-w-6xl items-center justify-between gap-3 px-5 sm:px-8">
        <Link to="/" className="flex items-center gap-2.5 rounded-lg text-ink focus:outline-none focus:ring-4 focus:ring-teal-100" aria-label="SpindleTrack home">
          <span className="grid h-9 w-9 place-items-center rounded-xl bg-brand text-white"><Droplets size={20} aria-hidden="true" /></span><span className="text-lg font-semibold tracking-tight">SpindleTrack</span>
        </Link>
        <nav className="flex items-center gap-2">
          {session ? <>
            <span className="hidden rounded-full bg-teal-50 px-3 py-1.5 text-xs font-semibold text-teal-800 sm:inline">{session.role.replaceAll('_', ' ')}</span>
            {session.role === 'OWNER' ? <Link to="/shop-status" className="inline-flex min-h-11 items-center rounded-xl px-3 text-sm font-semibold text-slate-700 hover:bg-slate-100">Shop status</Link> : session.role === 'CUSTOMER' ? <Link to="/register-shop" className="inline-flex min-h-11 items-center rounded-xl px-3 text-sm font-semibold text-slate-700 hover:bg-slate-100">Register shop</Link> : null}
            <button type="button" onClick={doSignOut} className="inline-flex min-h-11 items-center gap-2 rounded-xl px-3 text-sm font-semibold text-slate-700 hover:bg-slate-100"><LogOut size={16} aria-hidden="true" /><span className="hidden sm:inline">Sign out</span></button>
          </> : <Link to="/sign-in" className="inline-flex min-h-11 items-center rounded-xl px-4 text-sm font-semibold text-slate-700 transition hover:bg-slate-100 focus:outline-none focus:ring-4 focus:ring-teal-100">Sign in</Link>}
        </nav>
      </div>
    </header>
  );
}

export default function App() {
  const { loading } = useAuth();
  return (
    <div className="flex min-h-screen flex-col bg-canvas">
      <AppHeader />
      {loading ? <LoadingState label="Checking your account…" /> : <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/sign-in" element={<SignInPage />} />
        <Route path="/register-shop" element={<ProtectedRoute><ShopRegistrationPage /></ProtectedRoute>} />
        <Route path="/shop-status" element={<ProtectedRoute ownerOnly><ShopStatusPage /></ProtectedRoute>} />
        <Route path="*" element={<HomePage />} />
      </Routes>}
      <footer className="border-t border-slate-200/80 bg-white">
        <div className="mx-auto flex max-w-6xl flex-col gap-1 px-5 py-5 text-xs text-slate-500 sm:flex-row sm:items-center sm:justify-between sm:px-8"><span>SpindleTrack · Laundry shop operations</span><span>Owner registration · Step 2</span></div>
      </footer>
    </div>
  );
}
