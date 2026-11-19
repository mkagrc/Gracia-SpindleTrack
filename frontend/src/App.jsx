import { useEffect, useMemo, useState } from 'react';
import { Link, NavLink, Navigate, Route, Routes, useLocation, useNavigate, useParams } from 'react-router-dom';
import {
  Activity, ArrowDown, ArrowLeft, ArrowRight, BadgeCheck, Check, ChevronDown, ChevronLeft,
  ChevronRight, CircleHelp, ClipboardList, Download, Droplets, FileCheck2, FileText,
  LayoutDashboard, LoaderCircle, LogOut, Menu, Search, ShieldCheck, Store, Upload,
  UserRound, Users, AlertTriangle, Clock3, MapPin, Phone, Mail, Building2, Plus,
} from 'lucide-react';
import { createUserWithEmailAndPassword, sendEmailVerification, signOut as firebaseSignOut } from 'firebase/auth';
import { auth } from './firebase.js';
import api, { apiErrorMessage } from './api.js';
import { useAuth } from './AuthContext.jsx';
import ConfirmDialog from './ConfirmDialog.jsx';
import Toast from './Toast.jsx';
import { AcceptInvitationPage, OwnerServicesPage, OwnerStaffPage, StaffServicesPanel } from './Step5BPages.jsx';

const ROLE_HOME = { PLATFORM_ADMIN: '/admin/shops', OWNER: '/owner', STAFF: '/staff', CUSTOMER: '/customer/orders' };
const ROLE_LABEL = { PLATFORM_ADMIN: 'Platform Admin', OWNER: 'Owner', STAFF: 'Staff', CUSTOMER: 'Customer' };
const FILE_LIMIT = 5 * 1024 * 1024;
const ACCEPTED_MIME = new Set(['image/jpeg', 'image/png', 'application/pdf']);
const ACCEPTED_EXT = /\.(jpe?g|png|pdf)$/i;

function homeFor(session) { return ROLE_HOME[session?.role] || '/'; }
function humanRole(role) { return ROLE_LABEL[role] || role?.replaceAll('_', ' ') || 'Account'; }
function dateLabel(value) {
  if (!value) return '—';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}
function lastSeenLabel(value) {
  if (value === null || value === undefined || value === '') return 'No presence recorded';
  const parsed = new Date(Number(value));
  return Number.isNaN(parsed.getTime()) ? 'No presence recorded' : parsed.toLocaleString();
}
function orderCountLabel(value) { return Number(value) > 0 ? Number(value).toLocaleString() : 'Not tracked yet'; }
function prettyStatus(value) { return (value || 'UNKNOWN').replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase()); }

function LoadingState({ label = 'Loading…', compact = false }) {
  return <div className={`flex items-center justify-center gap-3 text-sm text-slate-500 ${compact ? 'py-8' : 'min-h-64 flex-1'}`} role="status"><LoaderCircle size={20} className="animate-spin text-brand" aria-hidden="true" />{label}</div>;
}
function ErrorBanner({ error, onRetry }) {
  if (!error) return null;
  return <div role="alert" className="flex flex-wrap items-start justify-between gap-3 rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800"><span>{error}</span>{onRetry && <button type="button" onClick={onRetry} className="font-semibold underline underline-offset-2">Try again</button>}</div>;
}
function EmptyState({ icon: Icon = ClipboardList, title, children, action }) {
  return <div className="rounded-2xl border border-dashed border-slate-200 bg-white px-6 py-12 text-center"><span className="mx-auto grid h-12 w-12 place-items-center rounded-2xl bg-teal-50 text-brand"><Icon size={23} aria-hidden="true" /></span><h3 className="mt-4 text-base font-semibold text-ink">{title}</h3>{children && <p className="mx-auto mt-2 max-w-md text-sm leading-6 text-slate-500">{children}</p>}{action && <div className="mt-5">{action}</div>}</div>;
}
function PageHeading({ eyebrow, title, description, action }) {
  return <div className="mb-7 flex flex-wrap items-end justify-between gap-4"><div><p className="text-xs font-bold uppercase tracking-[0.16em] text-brand">{eyebrow}</p><h1 className="mt-2 text-2xl font-semibold tracking-tight text-ink sm:text-3xl">{title}</h1>{description && <p className="mt-2 max-w-2xl text-sm leading-6 text-slate-500">{description}</p>}</div>{action}</div>;
}
function StatusPill({ status }) {
  const kind = status === 'ACTIVE' ? 'bg-emerald-50 text-emerald-800 ring-emerald-200' : status === 'PENDING' ? 'bg-amber-50 text-amber-800 ring-amber-200' : status === 'REJECTED' || status === 'DEACTIVATED' ? 'bg-rose-50 text-rose-800 ring-rose-200' : 'bg-slate-100 text-slate-700 ring-slate-200';
  return <span className={`inline-flex items-center rounded-full px-2.5 py-1 text-xs font-semibold ring-1 ring-inset ${kind}`}>{prettyStatus(status)}</span>;
}
function TextField({ label, value, onChange, ...props }) {
  return <label className="block text-sm font-medium text-slate-700">{label}<input value={value} onChange={onChange} {...props} className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-300 bg-white px-3.5 text-sm outline-none transition placeholder:text-slate-400 focus:border-brand focus:ring-4 focus:ring-teal-50" /></label>;
}
function TextAreaField({ label, value, onChange, ...props }) {
  return <label className="block text-sm font-medium text-slate-700">{label}<textarea value={value} onChange={onChange} {...props} className="mt-1.5 w-full rounded-xl border border-slate-300 bg-white px-3.5 py-3 text-sm outline-none transition placeholder:text-slate-400 focus:border-brand focus:ring-4 focus:ring-teal-50" /></label>;
}

const MENU = {
  CUSTOMER: [
    { label: 'My orders', to: '/customer/orders', icon: ClipboardList },
    { label: 'Register your shop', to: '/register-shop', icon: Store },
  ],
  OWNER: [
    { label: 'Dashboard', to: '/owner', icon: LayoutDashboard },
    { label: 'Registration status', to: '/shop-registration', icon: FileCheck2 },
    { label: 'Orders', to: '/owner/orders', icon: ClipboardList, soon: true },
    { label: 'Services', to: '/owner/services', icon: Activity },
    { label: 'Staff', to: '/owner/staff', icon: Users },
    { label: 'Unclaimed', to: '/owner/unclaimed', icon: CircleHelp, soon: true },
    { label: 'Reports', to: '/owner/reports', icon: FileCheck2, soon: true },
    { label: 'Shop profile', to: '/owner/profile', icon: Store },
  ],
  STAFF: [
    { label: 'Dashboard', to: '/staff', icon: LayoutDashboard },
    { label: 'New order', to: '/staff/new-order', icon: Plus, soon: true },
    { label: 'Orders', to: '/staff/orders', icon: ClipboardList, soon: true },
    { label: 'Unclaimed', to: '/staff/unclaimed', icon: CircleHelp, soon: true },
  ],
  PLATFORM_ADMIN: [
    { label: 'Shops', to: '/admin/shops', icon: Building2 },
    { label: 'Users', to: '/admin/users', icon: Users },
  ],
};

function Brand({ compact = false }) {
  return <Link to="/" className={`inline-flex items-center gap-3 rounded-xl text-ink focus:outline-none focus:ring-4 focus:ring-teal-100 ${compact ? '' : 'px-1'}`} aria-label="SpindleTrack home"><span className="grid h-10 w-10 place-items-center rounded-2xl bg-brand text-white shadow-sm"><Droplets size={22} aria-hidden="true" /></span><span><span className="block text-base font-bold tracking-tight">SpindleTrack</span>{!compact && <span className="block text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-400">Laundry operations</span>}</span></Link>;
}
function AppShell({ children }) {
  const { session, signOut } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [mobileOpen, setMobileOpen] = useState(false);
  const role = session?.role || 'CUSTOMER';
  const menu = MENU[role] || [];
  useEffect(() => setMobileOpen(false), [location.pathname]);
  const leave = async () => { await signOut(); navigate('/sign-in', { replace: true }); };
  const shopLabel = session?.shop?.name || (role === 'PLATFORM_ADMIN' ? 'Platform workspace' : 'Shop workspace');
  return <div className="min-h-screen bg-canvas text-ink">
    {mobileOpen && <button type="button" aria-label="Close navigation" className="fixed inset-0 z-30 bg-slate-950/40 lg:hidden" onClick={() => setMobileOpen(false)} />}
    <aside className={`fixed inset-y-0 left-0 z-40 flex w-[268px] flex-col border-r border-slate-200 bg-white px-4 py-5 transition-transform lg:translate-x-0 ${mobileOpen ? 'translate-x-0' : '-translate-x-full'}`}>
      <div className="px-2"><Brand /></div>
      <div className="mt-8 rounded-2xl bg-slate-50 p-3"><div className="flex items-center gap-3"><span className="grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-white text-brand shadow-sm"><UserRound size={19} /></span><div className="min-w-0"><p className="truncate text-sm font-semibold text-ink">{session?.fullName || 'Signed-in user'}</p><p className="truncate text-xs text-slate-500">{humanRole(role)}</p></div></div><p className="mt-3 truncate border-t border-slate-200 pt-3 text-xs text-slate-500">{shopLabel}</p></div>
      <p className="mb-2 mt-8 px-3 text-[10px] font-bold uppercase tracking-[0.18em] text-slate-400">Workspace</p>
      <nav aria-label="Main navigation" className="space-y-1">{menu.map((item) => { const Icon = item.icon; return <NavLink key={item.to} to={item.to} end={item.to === '/owner' || item.to === '/staff'} className={({ isActive }) => `flex min-h-11 items-center gap-3 rounded-xl px-3 text-sm font-medium transition ${isActive ? 'bg-teal-50 text-teal-800' : 'text-slate-600 hover:bg-slate-50 hover:text-ink'}`}><Icon size={18} aria-hidden="true" /><span className="flex-1">{item.label}</span>{item.soon && <span className="rounded-md bg-slate-100 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide text-slate-500">Soon</span>}</NavLink>; })}</nav>
      <div className="mt-auto rounded-2xl border border-teal-100 bg-teal-50/70 p-3 text-xs leading-5 text-teal-900"><div className="flex items-center gap-2 font-semibold"><ShieldCheck size={16} />Private by design</div><p className="mt-1 text-teal-800/80">Your access is limited to the role and shop assigned to your account.</p></div>
      <button type="button" onClick={leave} className="mt-3 flex min-h-11 items-center gap-3 rounded-xl px-3 text-sm font-semibold text-slate-600 transition hover:bg-red-50 hover:text-red-700"><LogOut size={18} />Sign out</button>
    </aside>
    <div className="min-h-screen lg:pl-[268px]">
      <header className="sticky top-0 z-20 flex min-h-[68px] items-center justify-between border-b border-slate-200 bg-white/95 px-4 backdrop-blur sm:px-7"><div className="flex items-center gap-3"><button type="button" aria-label="Open navigation" className="grid h-10 w-10 place-items-center rounded-xl text-slate-600 hover:bg-slate-100 lg:hidden" onClick={() => setMobileOpen(true)}><Menu size={21} /></button><div><p className="text-xs text-slate-400">{humanRole(role)} workspace</p><p className="max-w-[55vw] truncate text-sm font-semibold text-ink">{shopLabel}</p></div></div><div className="flex items-center gap-2"><span className="hidden rounded-full bg-teal-50 px-3 py-1.5 text-xs font-semibold text-teal-800 sm:inline-flex">{humanRole(role)}</span><button type="button" onClick={leave} className="grid h-10 w-10 place-items-center rounded-xl text-slate-500 hover:bg-slate-100 lg:hidden" aria-label="Sign out"><LogOut size={18} /></button></div></header>
      <main className="mx-auto w-full max-w-[1440px] px-4 py-7 sm:px-7 sm:py-9">{children}</main>
      <footer className="border-t border-slate-200 bg-white px-5 py-4 text-xs text-slate-400 sm:px-7">SpindleTrack · Multi-shop laundry workspace</footer>
    </div>
  </div>;
}

function PublicFrame({ children }) {
  return <div className="flex min-h-screen flex-col bg-canvas"><header className="border-b border-slate-200 bg-white"><div className="mx-auto flex min-h-[68px] max-w-7xl items-center justify-between gap-4 px-5 sm:px-8"><Brand /><Link to="/sign-in" className="inline-flex min-h-10 items-center rounded-xl px-4 text-sm font-semibold text-slate-700 hover:bg-slate-100">Sign in</Link></div></header>{children}<footer className="border-t border-slate-200 bg-white px-5 py-5 text-center text-xs text-slate-400">SpindleTrack · Laundry operations, made simpler</footer></div>;
}
function HomePage() {
  const { session } = useAuth();
  if (session) return <Navigate to={homeFor(session)} replace />;
  return <PublicFrame><main className="mx-auto grid w-full max-w-7xl flex-1 items-center gap-12 px-5 py-12 sm:px-8 lg:grid-cols-[1.08fr_.92fr] lg:py-20"><section><span className="inline-flex items-center gap-2 rounded-full border border-teal-100 bg-white px-3 py-1.5 text-xs font-semibold text-teal-800"><Droplets size={15} />Laundry operations, made simpler</span><h1 className="mt-6 max-w-3xl text-4xl font-semibold leading-tight tracking-tight text-ink sm:text-5xl lg:text-6xl">A calmer way to run your <span className="text-brand">laundry shop.</span></h1><p className="mt-5 max-w-xl text-base leading-7 text-slate-600">SpindleTrack gives each shop a secure workspace, with platform review before operations begin.</p><div className="mt-8 flex flex-wrap gap-3"><Link to="/sign-in" className="inline-flex min-h-12 items-center gap-2 rounded-xl bg-brand px-5 font-semibold text-white shadow-sm hover:bg-teal-700">Get started <ArrowRight size={17} /></Link></div></section><section className="rounded-3xl border border-slate-100 bg-white p-7 shadow-soft sm:p-9"><div className="grid h-12 w-12 place-items-center rounded-2xl bg-teal-50 text-brand"><Store size={23} /></div><p className="mt-6 text-xs font-bold uppercase tracking-[.16em] text-slate-400">Getting started</p><h2 className="mt-2 text-2xl font-semibold">Verified accounts. Reviewed shops.</h2><ul className="mt-6 space-y-4 text-sm text-slate-600">{['Sign in securely with Google or email.', 'Submit your shop profile and documents.', 'Start using shop tools after approval.'].map((line) => <li key={line} className="flex items-start gap-3"><Check size={17} className="mt-0.5 shrink-0 text-emerald-600" />{line}</li>)}</ul><div className="mt-7 rounded-2xl bg-slate-50 p-4 text-sm leading-6 text-slate-600">One owner account is linked to one shop. Each workspace is scoped to its shop.</div></section></main></PublicFrame>;
}
function SignInPage() {
  const navigate = useNavigate(); const location = useLocation();
  const { signInGoogle, signInEmail, sessionError, clearSessionError } = useAuth();
  const [mode, setMode] = useState('signin'); const [email, setEmail] = useState(''); const [password, setPassword] = useState(''); const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('');
  const go = (session) => {
    const destination = location.state?.from;
    const returnPath = destination ? `${destination.pathname || '/'}${destination.search || ''}${destination.hash || ''}` : homeFor(session);
    navigate(returnPath, { replace: true });
  };
  const friendly = (reason) => {
    const code = reason?.code || '';
    if (code.includes('popup-closed-by-user')) return 'The Google sign-in window was closed before finishing.';
    if (['invalid-credential', 'wrong-password', 'user-not-found'].some((item) => code.includes(item))) return 'Email or password was not recognized.';
    if (code.includes('email-already-in-use')) return 'An account already uses this email. Sign in instead.';
    if (code.includes('weak-password')) return 'Use a password with at least 6 characters.';
    return apiErrorMessage(reason, reason?.message || 'Sign-in could not be completed. Please try again.');
  };
  const doGoogle = async () => { setBusy(true); setError(''); clearSessionError(); try { go(await signInGoogle()); } catch (e) { setError(friendly(e)); } finally { setBusy(false); } };
  const doSubmit = async (event) => {
    event.preventDefault(); setBusy(true); setError(''); setNotice(''); clearSessionError();
    try {
      if (mode === 'create') {
        if (password !== confirm) throw new Error('Passwords do not match.');
        const credential = await createUserWithEmailAndPassword(auth, email.trim(), password);
        await sendEmailVerification(credential.user); await firebaseSignOut(auth); setMode('signin'); setPassword(''); setConfirm('');
        setNotice(`Verification email sent to ${email.trim()}. Verify your email, then sign in.`);
      } else go(await signInEmail(email.trim(), password));
    } catch (e) { setError(friendly(e)); } finally { setBusy(false); }
  };
  return <PublicFrame><main className="mx-auto flex w-full max-w-xl flex-1 items-center px-5 py-10"><section className="w-full rounded-3xl border border-slate-100 bg-white p-7 shadow-soft sm:p-10"><div className="mx-auto grid h-14 w-14 place-items-center rounded-2xl bg-teal-50 text-brand"><Droplets size={28} /></div><h1 className="mt-5 text-center text-2xl font-semibold text-ink">{mode === 'create' ? 'Create your account' : 'Welcome back'}</h1><p className="mt-2 text-center text-sm leading-6 text-slate-500">Sign in to your secure shop workspace. Email/password accounts must verify their email.</p><ErrorBanner error={error || sessionError} />{notice && <p role="status" className="mt-4 rounded-xl bg-emerald-50 p-3 text-sm text-emerald-800">{notice}</p>}<button type="button" onClick={doGoogle} disabled={busy} className="mt-6 inline-flex min-h-12 w-full items-center justify-center gap-3 rounded-xl border border-slate-200 font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-60">{busy ? <LoaderCircle size={18} className="animate-spin" /> : <span className="font-bold text-blue-600">G</span>}Continue with Google</button><div className="my-6 flex items-center gap-3 text-[10px] uppercase tracking-widest text-slate-400"><span className="h-px flex-1 bg-slate-200" />or email and password<span className="h-px flex-1 bg-slate-200" /></div><form className="space-y-4" onSubmit={doSubmit}><TextField label="Email address" type="email" required autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} /><TextField label="Password" type="password" required minLength={6} autoComplete={mode === 'create' ? 'new-password' : 'current-password'} value={password} onChange={(e) => setPassword(e.target.value)} />{mode === 'create' && <TextField label="Confirm password" type="password" required minLength={6} autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />}<button disabled={busy} className="min-h-12 w-full rounded-xl bg-brand px-5 font-semibold text-white hover:bg-teal-700 disabled:opacity-60">{busy ? 'Please wait…' : mode === 'create' ? 'Create account and verify email' : 'Sign in'}</button></form><p className="mt-5 text-center text-sm text-slate-600">{mode === 'create' ? 'Already have an account?' : 'New to SpindleTrack?'} <button type="button" onClick={() => { setMode(mode === 'create' ? 'signin' : 'create'); setError(''); setNotice(''); }} className="font-semibold text-brand">{mode === 'create' ? 'Sign in' : 'Create an account'}</button></p><p className="mt-3 text-center text-xs leading-5 text-slate-400">New accounts start as customers. A verified email is required to continue.</p></section></main></PublicFrame>;
}

function RoleGuard({ roles, children }) {
  const { session, loading } = useAuth(); const location = useLocation();
  if (loading) return <LoadingState label="Checking your account…" />;
  if (!session) return <Navigate to="/sign-in" replace state={{ from: location }} />;
  if (!roles.includes(session.role)) return <Navigate to={homeFor(session)} replace />;
  return children;
}
function SoonPage({ title }) {
  return <><PageHeading eyebrow="Coming soon" title={title} description="This feature is not available yet. SpindleTrack will show it here when the supporting backend workflow is ready." /><EmptyState icon={Clock3} title={`${title} is not built yet`}>No placeholder records are displayed. For now, use the available shop status and profile features.</EmptyState></>;
}
function CustomerOrdersPage() {
  return <><PageHeading eyebrow="Customer" title="My orders" description="Your personal laundry orders will appear here when the order endpoints are available." /><EmptyState title="No orders to show" icon={ClipboardList}>Order tracking is not available yet. This page intentionally does not display sample or placeholder orders.</EmptyState></>;
}
function StaffDashboard() {
  const { session } = useAuth();
  return <><PageHeading eyebrow="Staff workspace" title={`Welcome${session?.fullName ? `, ${session.fullName}` : ''}`} description="Your shop workspace is ready." /><section className="rounded-3xl border border-slate-100 bg-white p-6 shadow-soft sm:p-8"><div className="flex flex-wrap items-start gap-4"><span className="grid h-12 w-12 place-items-center rounded-2xl bg-teal-50 text-brand"><Store size={23} /></span><div><p className="text-xs font-bold uppercase tracking-[.16em] text-slate-400">Assigned shop</p><h2 className="mt-1 text-xl font-semibold">{session?.shop?.name || 'Shop name unavailable'}</h2><div className="mt-2 flex items-center gap-2"><StatusPill status={session?.shop?.status || session?.shopStatus} /></div></div></div><p className="mt-6 max-w-2xl text-sm leading-6 text-slate-500">Staff order workflows are marked “Coming soon” until their backend endpoints are available. This screen does not create sample orders.</p></section></>;
}
function OwnerStaffPresenceCard() {
  const { session } = useAuth();
  const [staff, setStaff] = useState(null); const [loading, setLoading] = useState(true); const [error, setError] = useState(''); const [reload, setReload] = useState(0);
  const shopIsActive = session?.shopStatus === 'ACTIVE' || session?.shop?.status === 'ACTIVE';
  useEffect(() => {
    if (!shopIsActive) return undefined;
    let active = true; setLoading(true); setError('');
    api.get('/api/shops/mine/staff-presence').then(({ data }) => { if (active) setStaff(Array.isArray(data) ? data : []); })
      .catch((reason) => { if (active) setError(apiErrorMessage(reason)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [shopIsActive, reload]);
  if (!shopIsActive) return null;
  return <section className="mt-6 rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-6"><div className="flex flex-wrap items-center justify-between gap-3"><div><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Staff presence</p><h2 className="mt-1 text-lg font-semibold">Your staff</h2><p className="mt-1 text-sm text-slate-500">Presence is scoped to your shop. Refresh to check the latest status.</p></div><button type="button" disabled={loading} onClick={() => setReload((value) => value + 1)} className="min-h-10 rounded-xl border border-slate-200 px-3 text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50">Refresh</button></div>
    {error && <div className="mt-4"><ErrorBanner error={error} onRetry={() => setReload((value) => value + 1)} /></div>}
    {loading ? <LoadingState label="Loading staff presence…" compact /> : error ? null : !staff?.length ? <div className="mt-4"><EmptyState icon={Users} title="No staff accounts yet">Staff presence will appear here after staff accounts are assigned to your shop.</EmptyState></div> : <ul className="mt-4 divide-y divide-slate-100 rounded-2xl border border-slate-100">{staff.map((person, index) => <li key={`${person.name || 'staff'}-${index}`} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3"><div className="flex items-center gap-3"><span className={`h-2.5 w-2.5 rounded-full ${person.online ? 'bg-emerald-500' : 'bg-slate-300'}`} aria-hidden="true" /><div><p className="text-sm font-semibold text-ink">{person.name || 'Staff member'}</p><p className="mt-0.5 text-xs text-slate-500">Last seen: {lastSeenLabel(person.lastSeen)}</p></div></div><span role="status" className={`rounded-full px-2.5 py-1 text-xs font-semibold ${person.online ? 'bg-emerald-50 text-emerald-800' : 'bg-slate-100 text-slate-600'}`}>{person.online ? 'Online' : 'Offline'}</span></li>)}</ul>}
  </section>;
}
function OwnerDashboard() {
  const { session } = useAuth(); const [registration, setRegistration] = useState(null); const [loading, setLoading] = useState(true); const [error, setError] = useState(''); const [reload, setReload] = useState(0);
  useEffect(() => { let active = true; setLoading(true); setError(''); api.get('/api/shops/mine/registration').then(({ data }) => { if (active) setRegistration(data); }).catch((e) => { if (active) setError(apiErrorMessage(e)); }).finally(() => { if (active) setLoading(false); }); return () => { active = false; }; }, [reload]);
  if (loading) return <LoadingState label="Loading your shop status…" />;
  if (error) return <><PageHeading eyebrow="Owner workspace" title="Shop dashboard" /><ErrorBanner error={error} onRetry={() => setReload((v) => v + 1)} /></>;
  if (!registration) return <><PageHeading eyebrow="Owner workspace" title="Shop dashboard" /><EmptyState title="No shop registration found">Ask the platform administrator to check the shop linked to your account.</EmptyState></>;
  const status = registration.status;
  const copy = {
    PENDING: ['Waiting for approval', 'Your registration has been received. Shop operations become available after platform review.'],
    REJECTED: ['Changes requested', registration.rejectionReason || 'Review the administrator’s note, update your details or documents, and resubmit.'],
    DEACTIVATED: ['Shop deactivated', 'Your shop is deactivated. Shop operations are blocked; contact the platform administrator for assistance.'],
    ACTIVE: ['Shop is active', 'Your shop profile is ready. Operational features without backend endpoints are marked Coming soon.'],
  }[status] || ['Shop status', 'Your current shop status is shown below.'];
  return <><PageHeading eyebrow="Owner workspace" title="Shop dashboard" description={registration.shopName} /><section className="rounded-3xl border border-slate-100 bg-white p-6 shadow-soft sm:p-8"><div className="flex flex-wrap items-start justify-between gap-4"><div className="flex items-start gap-4"><span className="grid h-12 w-12 place-items-center rounded-2xl bg-teal-50 text-brand"><Store size={23} /></span><div><p className="text-xs font-bold uppercase tracking-[.16em] text-slate-400">Shop status</p><h2 className="mt-1 text-xl font-semibold">{registration.shopName}</h2></div></div><StatusPill status={status} /></div><div className={`mt-6 rounded-2xl p-5 ${status === 'PENDING' ? 'bg-amber-50' : status === 'REJECTED' || status === 'DEACTIVATED' ? 'bg-rose-50' : 'bg-emerald-50'}`}><h3 className="font-semibold text-ink">{copy[0]}</h3><p className="mt-2 text-sm leading-6 text-slate-600">{copy[1]}</p></div>{status === 'REJECTED' && <div className="mt-5"><Link to="/shop-registration" className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand px-4 text-sm font-semibold text-white hover:bg-teal-700">Edit and resubmit <ArrowRight size={16} /></Link></div>}{status === 'ACTIVE' && <p className="mt-5 text-sm text-slate-500">Shop profile is available in the sidebar. Services and staff are available. Orders, unclaimed items, and reports are marked Coming soon.</p>}</section></>;
}
function validateFiles(files, currentCount = 0) {
  if (files.length < 1) return 'Choose at least one JPG, PNG, or PDF file.';
  if (currentCount + files.length > 5) return 'A registration can have no more than five documents total.';
  for (const file of files) {
    if (file.size > FILE_LIMIT) return `${file.name} is larger than 5 MB.`;
    const extOk = ACCEPTED_EXT.test(file.name);
    const mimeOk = !file.type || ACCEPTED_MIME.has(file.type);
    if (!extOk || !mimeOk) return `${file.name} is not a supported JPG, PNG, or PDF file.`;
  }
  return '';
}
function DocumentUploader({ files, onChange, existingCount = 0, multiple = true, title = 'Verification documents' }) {
  return <label className="block text-sm font-medium text-slate-700">{title}<span className="mt-1.5 flex min-h-12 items-center gap-3 rounded-xl border border-dashed border-slate-300 bg-slate-50 px-3.5"><Upload size={18} className="shrink-0 text-brand" /><input type="file" multiple={multiple} accept=".jpg,.jpeg,.png,.pdf,image/jpeg,image/png,application/pdf" onChange={onChange} className="w-full text-sm text-slate-600 file:mr-3 file:rounded-lg file:border-0 file:bg-white file:px-3 file:py-2 file:font-semibold file:text-slate-700" /></span><span className="mt-2 block text-xs font-normal leading-5 text-slate-500">1–5 JPG, PNG, or PDF files total · max 5 MB each · {existingCount} already attached</span>{files.length > 0 && <ul className="mt-3 space-y-1.5">{files.map((file) => <li key={`${file.name}-${file.lastModified}`} className="flex items-center gap-2 text-xs text-slate-600"><FileText size={14} className="text-brand" />{file.name} · {(file.size / (1024 * 1024)).toFixed(2)} MB</li>)}</ul>}</label>;
}
function ShopRegistrationPage() {
  const { firebaseUser, syncSession } = useAuth(); const navigate = useNavigate();
  const [form, setForm] = useState({ shopName: '', address: '', contactNumber: '' }); const [files, setFiles] = useState([]); const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('');
  const update = (key) => (e) => setForm((prev) => ({ ...prev, [key]: e.target.value }));
  const selectFiles = (e) => { const list = Array.from(e.target.files || []); const problem = list.length > 5 ? 'Choose no more than five documents.' : validateFiles(list); if (problem) { setError(problem); setFiles([]); e.target.value = ''; return; } setError(''); setFiles(list); };
  const submit = async (e) => { e.preventDefault(); setBusy(true); setError(''); setNotice(''); try { const problem = validateFiles(files); if (problem) throw new Error(problem); const payload = new FormData(); payload.append('shop', new Blob([JSON.stringify(form)], { type: 'application/json' })); files.forEach((file) => payload.append('documents', file)); await api.post('/api/shops/register', payload); const next = await syncSession(firebaseUser); setNotice('Your shop registration was submitted for review.'); navigate(homeFor(next), { replace: true }); } catch (reason) { setError(apiErrorMessage(reason, 'Shop registration could not be submitted.')); } finally { setBusy(false); } };
  return <><PageHeading eyebrow="Customer" title="Register your shop" description="Submit your shop profile and verification documents for platform review." /><section className="max-w-3xl rounded-3xl border border-slate-100 bg-white p-6 shadow-soft sm:p-8"><div className="mb-6 flex items-start gap-3 rounded-2xl bg-teal-50 p-4 text-sm leading-6 text-teal-900"><ShieldCheck size={19} className="mt-0.5 shrink-0" /><p>One registration per eligible customer account. Once submitted, your account becomes the shop owner account.</p></div><ErrorBanner error={error} />{notice && <p role="status" className="mt-4 rounded-xl bg-emerald-50 p-3 text-sm text-emerald-800">{notice}</p>}<form onSubmit={submit} className="mt-6 space-y-5"><TextField label="Shop name" required maxLength={160} autoComplete="organization" placeholder="e.g. Fresh Fold Laundry" value={form.shopName} onChange={update('shopName')} /><TextAreaField label="Shop address" required maxLength={500} rows={3} autoComplete="street-address" placeholder="Street, barangay, city, and province" value={form.address} onChange={update('address')} /><TextField label="Contact number" required type="tel" minLength={7} maxLength={32} autoComplete="tel" placeholder="e.g. +63 917 000 0000" value={form.contactNumber} onChange={update('contactNumber')} /><DocumentUploader files={files} onChange={selectFiles} /><button disabled={busy} className="inline-flex min-h-12 items-center gap-2 rounded-xl bg-brand px-5 font-semibold text-white hover:bg-teal-700 disabled:opacity-60">{busy ? <><LoaderCircle size={18} className="animate-spin" />Submitting…</> : <>Submit for review <ArrowRight size={17} /></>}</button></form></section></>;
}
async function openPrivateDocument(document) {
  const { data } = await api.get(`/api/shops/documents/${document.id}`, { responseType: 'blob' });
  const localUrl = URL.createObjectURL(data); const link = window.document.createElement('a');
  link.href = localUrl; link.download = document.originalFilename || 'verification-document';
  window.document.body.appendChild(link); link.click(); link.remove(); window.setTimeout(() => URL.revokeObjectURL(localUrl), 1500);
}
function RegistrationStatusPage() {
  const [registration, setRegistration] = useState(null); const [form, setForm] = useState({ shopName: '', address: '', contactNumber: '' });
  const [files, setFiles] = useState([]); const [loading, setLoading] = useState(true); const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState(''); const [reload, setReload] = useState(0); const [pendingRemoval, setPendingRemoval] = useState(null); const [toast, setToast] = useState(null);
  useEffect(() => { let active = true; setLoading(true); setError(''); api.get('/api/shops/mine/registration').then(({ data }) => { if (active) { setRegistration(data); setForm({ shopName: data.shopName || '', address: data.address || '', contactNumber: data.contactNumber || '' }); } }).catch((e) => { if (active) setError(apiErrorMessage(e)); }).finally(() => { if (active) setLoading(false); }); return () => { active = false; }; }, [reload]);
  const existing = registration?.documents || []; const update = (key) => (e) => setForm((prev) => ({ ...prev, [key]: e.target.value }));
  const selectFiles = (e) => { const list = Array.from(e.target.files || []); const problem = validateFiles(list, existing.length); if (problem) { setError(problem); setFiles([]); e.target.value = ''; return; } setError(''); setFiles(list); };
  const download = async (doc) => { setError(''); try { await openPrivateDocument(doc); } catch (e) { setError(apiErrorMessage(e, 'The private document could not be opened.')); } };
  const remove = async (doc) => {
    setBusy(true); setError(''); setNotice('');
    try {
      await api.delete(`/api/shops/mine/registration/documents/${doc.id}`);
      setPendingRemoval(null);
      setToast({ type: 'success', message: `Removed ${doc.originalFilename} from ${registration.shopName}.` });
      setReload((value) => value + 1);
    } catch (reason) {
      const message = apiErrorMessage(reason);
      setToast({ type: 'error', message });
      throw new Error(message);
    } finally { setBusy(false); }
  };
  const resubmit = async (e) => { e.preventDefault(); setBusy(true); setError(''); setNotice(''); try { if (existing.length + files.length < 1) throw new Error('A resubmission must retain or include at least one verification document.'); if (existing.length + files.length > 5) throw new Error('A registration can have no more than five documents total.'); const payload = new FormData(); payload.append('shop', new Blob([JSON.stringify(form)], { type: 'application/json' })); files.forEach((file) => payload.append('documents', file)); await api.put('/api/shops/mine/registration', payload); setFiles([]); setNotice('Your updated registration has been resubmitted for review.'); setReload((v) => v + 1); } catch (e2) { setError(apiErrorMessage(e2, 'The registration could not be resubmitted.')); } finally { setBusy(false); } };
  if (loading) return <LoadingState label="Loading registration status…" />;
  return <><Toast type={toast?.type} message={toast?.message} onDismiss={() => setToast(null)} /><PageHeading eyebrow="Owner workspace" title="Shop registration" description="Review your status, documents, and any requested changes." /><ErrorBanner error={error} onRetry={() => setReload((v) => v + 1)} />{notice && <p role="status" className="mb-4 rounded-xl bg-emerald-50 p-3 text-sm text-emerald-800">{notice}</p>}{!registration ? <EmptyState title="Registration not found" icon={Store}>No shop registration is attached to this owner account.</EmptyState> : <div className="grid gap-6 xl:grid-cols-[.9fr_1.1fr]"><section className="rounded-3xl border border-slate-100 bg-white p-6 shadow-soft"><div className="flex items-start justify-between gap-3"><div><p className="text-xs font-bold uppercase tracking-[.16em] text-slate-400">Current status</p><h2 className="mt-2 text-xl font-semibold">{registration.shopName}</h2></div><StatusPill status={registration.status} /></div><div className={`mt-5 rounded-2xl p-4 text-sm leading-6 ${registration.status === 'PENDING' ? 'bg-amber-50 text-amber-900' : registration.status === 'REJECTED' || registration.status === 'DEACTIVATED' ? 'bg-rose-50 text-rose-900' : 'bg-emerald-50 text-emerald-900'}`}>{registration.status === 'PENDING' ? 'Your registration is waiting for platform review.' : registration.status === 'REJECTED' ? registration.rejectionReason || 'Please update your registration and resubmit.' : registration.status === 'DEACTIVATED' ? 'This shop is deactivated. Shop operations are blocked; contact the platform administrator.' : 'Your shop registration is active.'}</div><dl className="mt-5 space-y-4 text-sm"><div><dt className="text-xs font-semibold uppercase tracking-wide text-slate-400">Address</dt><dd className="mt-1 text-slate-700">{registration.address}</dd></div><div><dt className="text-xs font-semibold uppercase tracking-wide text-slate-400">Contact</dt><dd className="mt-1 text-slate-700">{registration.contactNumber}</dd></div><div><dt className="text-xs font-semibold uppercase tracking-wide text-slate-400">Registered</dt><dd className="mt-1 text-slate-700">{dateLabel(registration.registeredAt)}</dd></div></dl></section><section className="rounded-3xl border border-slate-100 bg-white p-6 shadow-soft"><div className="flex items-center justify-between gap-3"><div><p className="text-xs font-bold uppercase tracking-[.16em] text-slate-400">Private files</p><h2 className="mt-1 text-lg font-semibold">Verification documents</h2></div><span className="rounded-lg bg-slate-100 px-2 py-1 text-xs font-semibold text-slate-600">{existing.length}/5</span></div>{existing.length === 0 ? <p className="mt-4 rounded-xl bg-slate-50 p-4 text-sm text-slate-500">No documents are attached.</p> : <ul className="mt-4 divide-y divide-slate-100 rounded-2xl border border-slate-100">{existing.map((doc) => <li key={doc.id} className="flex flex-wrap items-center justify-between gap-2 p-3"><div className="flex min-w-0 items-center gap-3"><FileText size={18} className="shrink-0 text-brand" /><div className="min-w-0"><p className="truncate text-sm font-medium">{doc.originalFilename}</p><p className="text-xs text-slate-500">{doc.contentType} · {(doc.sizeBytes / (1024 * 1024)).toFixed(2)} MB</p></div></div><div className="flex gap-1"><button type="button" onClick={() => download(doc)} className="inline-flex min-h-9 items-center gap-1 rounded-lg px-2 text-xs font-semibold text-brand hover:bg-teal-50"><Download size={14} />Open</button>{registration.status === 'REJECTED' && <button type="button" disabled={busy} onClick={() => setPendingRemoval(doc)} className="min-h-9 rounded-lg px-2 text-xs font-semibold text-rose-700 hover:bg-rose-50">Remove</button>}</div></li>)}</ul>}
    <p className="mt-3 text-xs leading-5 text-slate-400">Documents are downloaded through the authenticated API; no public file URL is used.</p>
    {registration.status === 'REJECTED' && <form onSubmit={resubmit} className="mt-6 space-y-4 border-t border-slate-100 pt-5"><div><h3 className="font-semibold">Edit and resubmit</h3><p className="mt-1 text-xs leading-5 text-slate-500">Update the shop details and/or documents requested by the administrator.</p></div><TextField label="Shop name" required maxLength={160} value={form.shopName} onChange={update('shopName')} /><TextAreaField label="Shop address" required maxLength={500} rows={3} value={form.address} onChange={update('address')} /><TextField label="Contact number" required type="tel" minLength={7} maxLength={32} value={form.contactNumber} onChange={update('contactNumber')} /><DocumentUploader files={files} onChange={selectFiles} existingCount={existing.length} title="Add verification documents" /><button disabled={busy} className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand px-4 text-sm font-semibold text-white hover:bg-teal-700 disabled:opacity-60">{busy ? 'Resubmitting…' : 'Resubmit for review'}</button></form>}</section></div>}{pendingRemoval && <ConfirmDialog
    title="Remove verification document?"
    message={`Remove "${pendingRemoval.originalFilename}" from ${registration.shopName}? This cannot be undone.`}
    confirmLabel="Remove"
    cancelLabel="Cancel"
    tone="danger"
    loading={busy}
    onConfirm={() => remove(pendingRemoval)}
    onCancel={() => setPendingRemoval(null)}
    formatError={apiErrorMessage}
  />}</>;
}
function ShopProfilePage() {
  const [profile, setProfile] = useState(null); const [form, setForm] = useState({ name: '', address: '', contactNumber: '' }); const [loading, setLoading] = useState(true); const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState(''); const [reload, setReload] = useState(0);
  useEffect(() => { let active = true; setLoading(true); setError(''); api.get('/api/shops/mine').then(({ data }) => { if (active) { setProfile(data); setForm({ name: data.name || '', address: data.address || '', contactNumber: data.contactNumber || '' }); } }).catch((e) => { if (active) setError(apiErrorMessage(e)); }).finally(() => { if (active) setLoading(false); }); return () => { active = false; }; }, [reload]);
  const update = (key) => (e) => setForm((prev) => ({ ...prev, [key]: e.target.value }));
  const save = async (e) => { e.preventDefault(); setBusy(true); setError(''); setNotice(''); try { const { data } = await api.put('/api/shops/mine', form); setProfile(data); setForm({ name: data.name, address: data.address, contactNumber: data.contactNumber }); setNotice('Shop profile updated.'); } catch (e2) { setError(apiErrorMessage(e2)); } finally { setBusy(false); } };
  if (loading) return <LoadingState label="Loading shop profile…" />;
  return <><PageHeading eyebrow="Owner workspace" title="Shop profile" description="Edit the name, address, and contact number for your shop." />{error && <ErrorBanner error={error} onRetry={() => setReload((v) => v + 1)} />}{!profile ? !error && <EmptyState icon={Store} title="Shop profile unavailable">No profile is available for this account.</EmptyState> : <section className="max-w-3xl rounded-3xl border border-slate-100 bg-white p-6 shadow-soft sm:p-8"><div className="mb-6 flex items-center justify-between gap-3"><div className="flex items-center gap-3"><span className="grid h-11 w-11 place-items-center rounded-xl bg-teal-50 text-brand"><Store size={21} /></span><div><h2 className="font-semibold">Profile details</h2><p className="text-xs text-slate-500">Changes are limited to your assigned shop.</p></div></div><StatusPill status={profile.status} /></div>{notice && <p role="status" className="mb-4 rounded-xl bg-emerald-50 p-3 text-sm text-emerald-800">{notice}</p>}<form onSubmit={save} className="space-y-5"><TextField label="Shop name" required maxLength={160} value={form.name} onChange={update('name')} /><TextAreaField label="Shop address" required maxLength={500} rows={3} value={form.address} onChange={update('address')} /><TextField label="Contact number" required type="tel" minLength={7} maxLength={32} value={form.contactNumber} onChange={update('contactNumber')} /><button disabled={busy} className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand px-5 text-sm font-semibold text-white hover:bg-teal-700 disabled:opacity-60">{busy ? 'Saving…' : 'Save changes'}</button></form></section>}</>;
}

function AdminShopsPage() {
  const [statusFilter, setStatusFilter] = useState(''); const [query, setQuery] = useState(''); const [submittedQuery, setSubmittedQuery] = useState(''); const [page, setPage] = useState(0); const [data, setData] = useState(null); const [loading, setLoading] = useState(true); const [error, setError] = useState(''); const [reload, setReload] = useState(0);
  useEffect(() => { let active = true; setLoading(true); setError(''); api.get('/api/admin/shops', { params: { ...(statusFilter ? { status: statusFilter } : {}), ...(submittedQuery ? { search: submittedQuery } : {}), page } }).then(({ data: result }) => { if (active) setData(result); }).catch((e) => { if (active) setError(apiErrorMessage(e)); }).finally(() => { if (active) setLoading(false); }); return () => { active = false; }; }, [statusFilter, submittedQuery, page, reload]);
  const doSearch = (e) => { e.preventDefault(); setPage(0); setSubmittedQuery(query.trim()); };
  const rows = data?.content || []; const size = data?.size || 20; const total = data?.totalElements || 0; const pages = Math.max(1, Math.ceil(total / size));
  return <><PageHeading eyebrow="Platform administration" title="Shops" description="Review shop profiles, owners, staff, documents, and aggregate counts." /><section className="rounded-3xl border border-slate-100 bg-white p-4 shadow-soft sm:p-5"><form onSubmit={doSearch} className="grid gap-3 sm:grid-cols-[minmax(220px,1fr)_190px_auto]"><label className="relative block"><span className="sr-only">Search shops</span><Search size={17} className="absolute left-3 top-3.5 text-slate-400" /><input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search shop, owner, or email" className="min-h-11 w-full rounded-xl border border-slate-200 pl-10 pr-3 text-sm outline-none focus:border-brand focus:ring-4 focus:ring-teal-50" /></label><label><span className="sr-only">Filter by status</span><select value={statusFilter} onChange={(e) => { setPage(0); setStatusFilter(e.target.value); }} className="min-h-11 w-full rounded-xl border border-slate-200 bg-white px-3 text-sm text-slate-700 outline-none focus:border-brand focus:ring-4 focus:ring-teal-50"><option value="">All statuses · Pending first</option>{['PENDING', 'ACTIVE', 'REJECTED', 'DEACTIVATED'].map((s) => <option key={s} value={s}>{prettyStatus(s)}</option>)}</select></label><button className="inline-flex min-h-11 items-center justify-center gap-2 rounded-xl bg-brand px-4 text-sm font-semibold text-white hover:bg-teal-700"><Search size={16} />Search</button></form></section><div className="mt-5">{error && <ErrorBanner error={error} onRetry={() => setReload((v) => v + 1)} />}{loading ? <LoadingState label="Loading shops…" /> : !rows.length ? <div className="mt-4"><EmptyState icon={Store} title="No shops found">Try changing the search or status filter.</EmptyState></div> : <><div className="mt-4 overflow-hidden rounded-3xl border border-slate-200 bg-white shadow-soft"><div className="flex items-center justify-between border-b border-slate-100 px-5 py-4"><div><h2 className="font-semibold">Shop registrations</h2><p className="mt-0.5 text-xs text-slate-500">{total} total</p></div><span className="text-xs text-slate-500">Page {page + 1} of {pages}</span></div><div className="overflow-x-auto"><table className="w-full min-w-[850px] text-left text-sm"><thead className="bg-slate-50 text-[10px] uppercase tracking-[.13em] text-slate-400"><tr><th className="px-5 py-3 font-bold">Shop</th><th className="px-4 py-3 font-bold">Owner</th><th className="px-4 py-3 font-bold">Email</th><th className="px-4 py-3 font-bold">Registered</th><th className="px-4 py-3 font-bold">Status</th><th className="px-4 py-3 text-right font-bold">Staff</th><th className="px-4 py-3 text-right font-bold">Orders</th><th className="px-5 py-3" /></tr></thead><tbody className="divide-y divide-slate-100">{rows.map((shop) => <tr key={shop.id} className="hover:bg-slate-50/70"><td className="px-5 py-4"><Link className="font-semibold text-ink hover:text-brand" to={`/admin/shops/${shop.id}`}>{shop.name}</Link><p className="mt-1 text-xs text-slate-400">ID {shop.id}</p></td><td className="px-4 py-4 text-slate-700">{shop.ownerName || '—'}</td><td className="px-4 py-4 text-slate-500">{shop.ownerEmail || '—'}</td><td className="px-4 py-4 whitespace-nowrap text-slate-500">{dateLabel(shop.registrationDate)}</td><td className="px-4 py-4"><StatusPill status={shop.status} /></td><td className="px-4 py-4 text-right tabular-nums">{shop.staffCount}</td><td className="px-4 py-4 text-right tabular-nums">{orderCountLabel(shop.orderCount)}</td><td className="px-5 py-4"><Link to={`/admin/shops/${shop.id}`} className="inline-flex items-center gap-1 whitespace-nowrap text-xs font-semibold text-brand">Details <ArrowRight size={14} /></Link></td></tr>)}</tbody></table></div><div className="flex items-center justify-between gap-3 border-t border-slate-100 px-4 py-3"><p className="text-xs text-slate-500">Showing {rows.length} of {total}</p><div className="flex gap-2"><button type="button" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))} className="inline-flex min-h-9 items-center gap-1 rounded-lg border border-slate-200 px-3 text-xs font-semibold disabled:opacity-40"><ChevronLeft size={15} />Previous</button><button type="button" disabled={page + 1 >= pages} onClick={() => setPage((p) => Math.min(pages - 1, p + 1))} className="inline-flex min-h-9 items-center gap-1 rounded-lg border border-slate-200 px-3 text-xs font-semibold disabled:opacity-40">Next<ChevronRight size={15} /></button></div></div></div></>}</div></>;
}
function AdminShopDetailPage() {
  const { id } = useParams();
  const [shop, setShop] = useState(null); const [loading, setLoading] = useState(true); const [busy, setBusy] = useState(false);
  const [error, setError] = useState(''); const [reload, setReload] = useState(0); const [confirmAction, setConfirmAction] = useState('');
  const [rejectReason, setRejectReason] = useState(''); const [toast, setToast] = useState(null);
  useEffect(() => {
    let active = true; setLoading(true); setError('');
    api.get(`/api/admin/shops/${id}`).then(({ data }) => { if (active) setShop(data); })
      .catch((reason) => { if (active) setError(apiErrorMessage(reason)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [id, reload]);
  const beginAction = (action) => { setRejectReason(''); setConfirmAction(action); };
  const doAction = async (action, body) => {
    setBusy(true); setError('');
    try {
      await api.post(`/api/admin/shops/${id}/${action}`, body || undefined);
      setConfirmAction(''); setRejectReason('');
      const result = action === 'approve' ? 'approved' : action === 'reject' ? 'rejected' : action === 'deactivate' ? 'deactivated' : 'activated';
      setToast({ type: 'success', message: `Shop "${shop.name}" ${result}.${action === 'reject' ? ' The owner can now view the reason and resubmit.' : ''}` });
      setReload((value) => value + 1);
    } catch (reason) {
      const message = reason?.response?.status === 409
        ? (reason?.response?.data?.message || 'The shop status changed before this action completed. Refresh the page and review the current status.')
        : apiErrorMessage(reason);
      setToast({ type: 'error', message });
      throw new Error(message);
    } finally { setBusy(false); }
  };
  const download = async (doc) => { setError(''); try { await openPrivateDocument(doc); } catch (reason) { setError(apiErrorMessage(reason, 'The private verification document could not be opened.')); } };
  const dialog = confirmAction ? {
    approve: { title: 'Approve shop?', message: `Approve "${shop?.name}"? The owner will be able to use the shop right away.`, confirmLabel: 'Approve', tone: 'default' },
    reject: { title: 'Reject shop?', message: `Reject "${shop?.name}"? Add a reason of up to 500 characters; the owner will see it and may resubmit.`, confirmLabel: 'Reject', tone: 'danger', reasonRequired: true },
    deactivate: { title: 'Deactivate shop?', message: `Deactivate "${shop?.name}"? The owner and staff will lose access to shop operations.`, confirmLabel: 'Deactivate', tone: 'danger' },
    activate: { title: 'Activate shop?', message: `Activate "${shop?.name}"? Shop operations will be restored.`, confirmLabel: 'Activate', tone: 'default' },
  }[confirmAction] : null;
  if (loading) return <LoadingState label="Loading shop details…" />;
  if (!shop) return <><Link to="/admin/shops" className="mb-5 inline-flex items-center gap-2 text-sm font-semibold text-brand"><ArrowLeft size={16} />Back to shops</Link><ErrorBanner error={error || 'Shop details are unavailable.'} onRetry={() => setReload((value) => value + 1)} /></>;
  return <>
    <Toast type={toast?.type} message={toast?.message} onDismiss={() => setToast(null)} />
    <Link to="/admin/shops" className="mb-5 inline-flex items-center gap-2 text-sm font-semibold text-brand hover:text-teal-800"><ArrowLeft size={16} />Back to shops</Link>
    <PageHeading eyebrow={`Shop ID ${shop.id}`} title={shop.name} description="Administrative shop profile and verification details." action={<StatusPill status={shop.status} />} />
    {error && <div className="mb-5"><ErrorBanner error={error} onRetry={() => setReload((value) => value + 1)} /></div>}
    <div className="mb-6 flex flex-wrap gap-2">
      {shop.status === 'PENDING' && <>
        <button disabled={busy} onClick={() => beginAction('approve')} className="inline-flex min-h-10 items-center gap-2 rounded-xl bg-emerald-600 px-4 text-sm font-semibold text-white hover:bg-emerald-700 disabled:opacity-50"><BadgeCheck size={16} />Approve</button>
        <button disabled={busy} onClick={() => beginAction('reject')} className="inline-flex min-h-10 items-center gap-2 rounded-xl border border-rose-200 px-4 text-sm font-semibold text-rose-700 hover:bg-rose-50 disabled:opacity-50">Reject</button>
      </>}
      {shop.status === 'ACTIVE' && <button disabled={busy} onClick={() => beginAction('deactivate')} className="inline-flex min-h-10 items-center gap-2 rounded-xl border border-rose-200 px-4 text-sm font-semibold text-rose-700 hover:bg-rose-50 disabled:opacity-50"><AlertTriangle size={16} />Deactivate</button>}
      {shop.status === 'DEACTIVATED' && <button disabled={busy} onClick={() => beginAction('activate')} className="inline-flex min-h-10 items-center gap-2 rounded-xl bg-emerald-600 px-4 text-sm font-semibold text-white hover:bg-emerald-700 disabled:opacity-50"><BadgeCheck size={16} />Activate</button>}
    </div>
    <div className="grid gap-5 xl:grid-cols-3">
      <section className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft xl:col-span-2"><div className="flex items-center gap-2"><Store size={18} className="text-brand" /><h2 className="font-semibold">Shop profile</h2></div><dl className="mt-5 grid gap-5 sm:grid-cols-2"><div><dt className="text-xs font-semibold uppercase tracking-wide text-slate-400">Name</dt><dd className="mt-1 text-sm font-medium">{shop.name}</dd></div><div><dt className="text-xs font-semibold uppercase tracking-wide text-slate-400">Registered</dt><dd className="mt-1 text-sm">{dateLabel(shop.registrationDate)}</dd></div><div><dt className="text-xs font-semibold uppercase tracking-wide text-slate-400">Address</dt><dd className="mt-1 flex items-start gap-2 text-sm text-slate-700"><MapPin size={15} className="mt-0.5 shrink-0 text-slate-400" />{shop.address}</dd></div><div><dt className="text-xs font-semibold uppercase tracking-wide text-slate-400">Contact</dt><dd className="mt-1 flex items-center gap-2 text-sm text-slate-700"><Phone size={15} className="text-slate-400" />{shop.contactNumber}</dd></div></dl>{shop.rejectionReason && <div className="mt-5 rounded-2xl bg-rose-50 p-4"><p className="text-xs font-bold uppercase tracking-wide text-rose-700">Rejection reason</p><p className="mt-1 text-sm leading-6 text-rose-900">{shop.rejectionReason}</p></div>}</section>
      <section className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft"><h2 className="font-semibold">Shop counts</h2><div className="mt-4 grid grid-cols-2 gap-3"><div className="rounded-2xl bg-slate-50 p-4"><p className="text-xs text-slate-500">Staff</p><p className="mt-1 text-2xl font-semibold">{shop.staffCount}</p></div><div className="rounded-2xl bg-slate-50 p-4"><p className="text-xs text-slate-500">Orders</p><p className="mt-1 text-2xl font-semibold">{orderCountLabel(shop.orderCount)}</p></div></div><p className="mt-3 text-xs leading-5 text-slate-400">Counts only; no customer or order details are displayed.</p></section>
      <section className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft"><div className="flex items-center gap-2"><UserRound size={18} className="text-brand" /><h2 className="font-semibold">Owner</h2></div>{shop.owner ? <div className="mt-4 space-y-3"><p className="text-sm font-semibold">{shop.owner.name}</p><p className="flex items-center gap-2 break-all text-sm text-slate-500"><Mail size={15} className="shrink-0" />{shop.owner.email}</p></div> : <p className="mt-4 text-sm text-slate-500">No owner record is attached.</p>}</section>
      <section className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft"><div className="flex items-center justify-between"><div className="flex items-center gap-2"><Users size={18} className="text-brand" /><h2 className="font-semibold">Staff</h2></div><span className="text-xs text-slate-400">{shop.staff?.length || 0}</span></div>{!shop.staff?.length ? <p className="mt-4 text-sm text-slate-500">No staff accounts.</p> : <ul className="mt-3 divide-y divide-slate-100">{shop.staff.map((person) => <li key={person.id} className="py-3"><div className="flex items-center justify-between gap-2"><p className="text-sm font-medium">{person.name}</p><StatusPill status={person.status} /></div><p className="mt-1 break-all text-xs text-slate-500">{person.email}</p></li>)}</ul>}</section>
      <section className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft xl:col-span-1"><div className="flex items-center gap-2"><FileCheck2 size={18} className="text-brand" /><h2 className="font-semibold">Documents</h2></div>{!shop.documents?.length ? <p className="mt-4 text-sm text-slate-500">No verification documents attached.</p> : <ul className="mt-3 divide-y divide-slate-100">{shop.documents.map((doc) => <li key={doc.id} className="flex items-center justify-between gap-2 py-3"><div className="min-w-0"><p className="truncate text-sm font-medium">{doc.originalFilename}</p><p className="text-xs text-slate-500">{doc.contentType} · {(doc.sizeBytes / (1024 * 1024)).toFixed(2)} MB</p></div><button type="button" onClick={() => download(doc)} aria-label={`Open ${doc.originalFilename} through authenticated API`} className="grid h-9 w-9 shrink-0 place-items-center rounded-lg text-brand hover:bg-teal-50"><Download size={16} /></button></li>)}</ul>}<p className="mt-3 text-xs leading-5 text-slate-400">Opened via authenticated API; no public URLs.</p></section>
    </div>
    {dialog && <ConfirmDialog
      title={dialog.title}
      message={dialog.message}
      confirmLabel={dialog.confirmLabel}
      cancelLabel="Cancel"
      tone={dialog.tone}
      loading={busy}
      onConfirm={() => doAction(confirmAction, confirmAction === 'reject' ? { reason: rejectReason.trim() } : undefined)}
      onCancel={() => setConfirmAction('')}
      formatError={apiErrorMessage}
      reasonRequired={dialog.reasonRequired || false}
      reasonValue={rejectReason}
      onReasonChange={(event) => setRejectReason(event.target.value)}
    />}
  </>;
}
function AdminUsersPage() {
  const [role, setRole] = useState(''); const [page, setPage] = useState(0); const [data, setData] = useState(null); const [loading, setLoading] = useState(true); const [error, setError] = useState(''); const [reload, setReload] = useState(0);
  useEffect(() => {
    let active = true; setLoading(true); setError('');
    api.get('/api/admin/users', { params: { ...(role ? { role } : {}), page } })
      .then(({ data: result }) => { if (active) setData(result); })
      .catch((reason) => { if (active) setError(apiErrorMessage(reason)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [role, page, reload]);
  const rows = data?.content || []; const size = data?.size || 20; const total = data?.totalElements || 0; const pages = Math.max(1, Math.ceil(total / size));
  return <><PageHeading eyebrow="Platform administration" title="Users" description="Review account roles and current presence. Customer email addresses are not included." /><section className="rounded-3xl border border-slate-100 bg-white p-4 shadow-soft sm:p-5"><label className="block max-w-sm text-sm font-medium text-slate-700">Filter by role<select value={role} onChange={(event) => { setRole(event.target.value); setPage(0); }} className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-200 bg-white px-3 text-sm outline-none focus:border-brand focus:ring-4 focus:ring-teal-50"><option value="">All roles</option>{['PLATFORM_ADMIN', 'OWNER', 'STAFF', 'CUSTOMER'].map((value) => <option key={value} value={value}>{humanRole(value)}</option>)}</select></label></section><div className="mt-5">{error && <ErrorBanner error={error} onRetry={() => setReload((value) => value + 1)} />}{loading ? <LoadingState label="Loading users…" /> : error ? null : !rows.length ? <EmptyState icon={Users} title="No users found">No accounts match this role filter.</EmptyState> : <div className="overflow-hidden rounded-3xl border border-slate-200 bg-white shadow-soft"><div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 px-5 py-4"><div><h2 className="font-semibold">Account directory</h2><p className="mt-0.5 text-xs text-slate-500">{total} total accounts · {size} per page</p></div><span className="text-xs text-slate-500">Page {page + 1} of {pages}</span></div><div className="overflow-x-auto"><table className="w-full min-w-[760px] text-left text-sm"><thead className="bg-slate-50 text-[10px] uppercase tracking-[.13em] text-slate-400"><tr><th className="px-5 py-3 font-bold">Name</th><th className="px-4 py-3 font-bold">Email</th><th className="px-4 py-3 font-bold">Role</th><th className="px-4 py-3 font-bold">Presence</th><th className="px-5 py-3 font-bold">Last seen</th></tr></thead><tbody className="divide-y divide-slate-100">{rows.map((user, index) => <tr key={`${user.role}-${user.name || 'user'}-${index}`}><td className="px-5 py-4 font-medium text-ink">{user.name || '—'}</td><td className="px-4 py-4 text-slate-500">{user.email || (user.role === 'CUSTOMER' ? 'Not displayed' : '—')}</td><td className="px-4 py-4"><span className="rounded-full bg-slate-100 px-2.5 py-1 text-xs font-semibold text-slate-700">{humanRole(user.role)}</span></td><td className="px-4 py-4"><span className={`inline-flex items-center gap-2 rounded-full px-2.5 py-1 text-xs font-semibold ${user.online ? 'bg-emerald-50 text-emerald-800' : 'bg-slate-100 text-slate-600'}`}><span className={`h-2 w-2 rounded-full ${user.online ? 'bg-emerald-500' : 'bg-slate-400'}`} aria-hidden="true" />{user.online ? 'Online' : 'Offline'}</span></td><td className="px-5 py-4 text-xs text-slate-500">{lastSeenLabel(user.lastSeen)}</td></tr>)}</tbody></table></div><div className="flex items-center justify-between gap-3 border-t border-slate-100 px-4 py-3"><p className="text-xs text-slate-500">Showing {rows.length} of {total}</p><div className="flex gap-2"><button type="button" disabled={page === 0} onClick={() => setPage((value) => Math.max(0, value - 1))} className="inline-flex min-h-9 items-center gap-1 rounded-lg border border-slate-200 px-3 text-xs font-semibold disabled:opacity-40"><ChevronLeft size={15} />Previous</button><button type="button" disabled={page + 1 >= pages} onClick={() => setPage((value) => Math.min(pages - 1, value + 1))} className="inline-flex min-h-9 items-center gap-1 rounded-lg border border-slate-200 px-3 text-xs font-semibold disabled:opacity-40">Next<ChevronRight size={15} /></button></div></div></div>}</div></>;
}

function AppRoutes() {
  const { session, loading } = useAuth();
  if (loading) return <LoadingState label="Checking your account…" />;
  if (!session) return <Routes><Route path="/accept-invitation" element={<AcceptInvitationPage />} /><Route path="/sign-in" element={<SignInPage />} /><Route path="*" element={<HomePage />} /></Routes>;
  return <AppShell><Routes>
    <Route path="/" element={<Navigate to={homeFor(session)} replace />} />
    <Route path="/customer/orders" element={<RoleGuard roles={['CUSTOMER']}><CustomerOrdersPage /></RoleGuard>} />
    <Route path="/register-shop" element={<RoleGuard roles={['CUSTOMER']}><ShopRegistrationPage /></RoleGuard>} />
    <Route path="/owner" element={<RoleGuard roles={['OWNER']}><><OwnerDashboard /><OwnerStaffPresenceCard /></></RoleGuard>} />
    <Route path="/shop-status" element={<Navigate to="/owner" replace />} />
    <Route path="/owner/profile" element={<RoleGuard roles={['OWNER']}><ShopProfilePage /></RoleGuard>} />
    <Route path="/shop-registration" element={<RoleGuard roles={['OWNER']}><RegistrationStatusPage /></RoleGuard>} />
    <Route path="/owner/orders" element={<RoleGuard roles={['OWNER']}><SoonPage title="Orders" /></RoleGuard>} />
    <Route path="/owner/services" element={<RoleGuard roles={['OWNER']}><OwnerServicesPage /></RoleGuard>} />
    <Route path="/owner/staff" element={<RoleGuard roles={['OWNER']}><OwnerStaffPage /></RoleGuard>} />
    <Route path="/owner/unclaimed" element={<RoleGuard roles={['OWNER']}><SoonPage title="Unclaimed items" /></RoleGuard>} />
    <Route path="/owner/reports" element={<RoleGuard roles={['OWNER']}><SoonPage title="Reports" /></RoleGuard>} />
    <Route path="/staff" element={<RoleGuard roles={['STAFF']}><><StaffDashboard /><StaffServicesPanel /></></RoleGuard>} />
    <Route path="/staff/new-order" element={<RoleGuard roles={['STAFF']}><SoonPage title="New order" /></RoleGuard>} />
    <Route path="/staff/orders" element={<RoleGuard roles={['STAFF']}><SoonPage title="Orders" /></RoleGuard>} />
    <Route path="/staff/unclaimed" element={<RoleGuard roles={['STAFF']}><SoonPage title="Unclaimed items" /></RoleGuard>} />
    <Route path="/admin/shops" element={<RoleGuard roles={['PLATFORM_ADMIN']}><AdminShopsPage /></RoleGuard>} />
    <Route path="/admin/shops/:id" element={<RoleGuard roles={['PLATFORM_ADMIN']}><AdminShopDetailPage /></RoleGuard>} />
    <Route path="/admin/users" element={<RoleGuard roles={['PLATFORM_ADMIN']}><AdminUsersPage /></RoleGuard>} />
    <Route path="/accept-invitation" element={<AcceptInvitationPage />} />
    <Route path="/sign-in" element={<Navigate to={homeFor(session)} replace />} />
    <Route path="*" element={<Navigate to={homeFor(session)} replace />} />
  </Routes></AppShell>;
}
export default function App() { return <AppRoutes />; }
