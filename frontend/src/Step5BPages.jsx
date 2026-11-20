import { useEffect, useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ArrowRight, Clipboard, LoaderCircle, Mail, Plus, RefreshCw, Save, Users, Wrench } from 'lucide-react';
import { reload as reloadFirebaseUser, sendEmailVerification } from 'firebase/auth';
import api, { apiErrorMessage } from './api.js';
import { useAuth } from './AuthContext.jsx';
import ConfirmDialog from './ConfirmDialog.jsx';
import Toast from './Toast.jsx';
import { rememberInvitationReturn, takeInvitationReturn } from './invitationReturn.js';
import { shouldRedirectEmptyInbox } from './invitationInbox.js';

function Heading({ eyebrow, title, description, action }) {
  return <div className="mb-7 flex flex-wrap items-end justify-between gap-4"><div><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">{eyebrow}</p><h1 className="mt-2 text-2xl font-semibold tracking-tight text-ink sm:text-3xl">{title}</h1>{description && <p className="mt-2 max-w-2xl text-sm leading-6 text-slate-500">{description}</p>}</div>{action}</div>;
}
function Loading({ label = 'Loading…' }) {
  return <div role="status" className="flex min-h-40 items-center justify-center gap-3 text-sm text-slate-500"><LoaderCircle size={20} className="animate-spin text-brand" />{label}</div>;
}
function ErrorBox({ message, onRetry }) {
  if (!message) return null;
  return <div role="alert" className="mb-5 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-800"><span>{message}</span>{onRetry && <button type="button" onClick={onRetry} className="min-h-9 rounded-lg px-3 font-semibold hover:bg-rose-100">Try again</button>}</div>;
}
function Empty({ icon: Icon = Wrench, title, children }) {
  return <div className="rounded-2xl border border-dashed border-slate-200 bg-white px-6 py-10 text-center"><Icon size={23} className="mx-auto text-brand" /><h3 className="mt-3 font-semibold text-ink">{title}</h3>{children && <p className="mx-auto mt-2 max-w-lg text-sm leading-6 text-slate-500">{children}</p>}</div>;
}
function Panel({ title, count, children }) {
  return <section className="rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-6"><div className="mb-4 flex items-center justify-between gap-3"><h2 className="font-semibold text-ink">{title}</h2>{count !== undefined && <span className="text-xs text-slate-400">{count}</span>}</div>{children}</section>;
}
function dateTime(value) {
  if (!value) return 'Not recorded';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? 'Not recorded' : date.toLocaleString();
}
function money(value) {
  const amount = Number(value);
  return Number.isFinite(amount) ? amount.toFixed(2) : '—';
}

export function OwnerServicesPage() {
  const [services, setServices] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  const [busy, setBusy] = useState(false);
  const [editingId, setEditingId] = useState(null);
  const [confirmTarget, setConfirmTarget] = useState(null);
  const [toast, setToast] = useState(null);
  const [form, setForm] = useState({ name: '', pricePerUnit: '', unit: 'kg' });

  useEffect(() => {
    let active = true;
    setLoading(true); setError('');
    api.get('/api/shops/services').then(({ data }) => { if (active) setServices(Array.isArray(data) ? data : []); })
      .catch((reason) => { if (active) setError(apiErrorMessage(reason)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [reload]);

  const updateForm = (key) => (event) => setForm((current) => ({ ...current, [key]: event.target.value }));
  const resetForm = () => { setEditingId(null); setForm({ name: '', pricePerUnit: '', unit: 'kg' }); };
  const editService = (service) => {
    setEditingId(service.id);
    setForm({ name: service.name || '', pricePerUnit: String(service.pricePerUnit ?? ''), unit: service.unit || 'kg' });
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };
  const saveService = async (event) => {
    event.preventDefault(); setBusy(true); setError('');
    const payload = { name: form.name.trim(), pricePerUnit: Number(form.pricePerUnit), unit: form.unit };
    try {
      if (editingId) await api.put(`/api/shops/services/${editingId}`, payload);
      else await api.post('/api/shops/services', payload);
      setToast({ type: 'success', message: editingId ? 'Service updated.' : 'Service created.' });
      resetForm(); setReload((value) => value + 1);
    } catch (reason) {
      const message = apiErrorMessage(reason);
      setError(message); setToast({ type: 'error', message });
    } finally { setBusy(false); }
  };
  const saveActiveState = async () => {
    setBusy(true); setError('');
    try {
      const action = confirmTarget.active ? 'deactivate' : 'activate';
      await api.post(`/api/shops/services/${confirmTarget.id}/${action}`);
      setToast({ type: 'success', message: `Service “${confirmTarget.name}” ${confirmTarget.active ? 'deactivated' : 'activated'}.` });
      setConfirmTarget(null); setReload((value) => value + 1);
    } catch (reason) {
      const message = apiErrorMessage(reason);
      setToast({ type: 'error', message });
      throw new Error(message);
    } finally { setBusy(false); }
  };

  return <>
    <Toast type={toast?.type} message={toast?.message} onDismiss={() => setToast(null)} />
    <Heading eyebrow="Owner workspace" title="Services" description="Set the services and per-unit prices available to staff in your shop." />
    <ErrorBox message={error} onRetry={() => setReload((value) => value + 1)} />
    <div className="grid items-start gap-5 xl:grid-cols-[minmax(280px,.8fr)_minmax(0,1.2fr)]">
      <Panel title={editingId ? 'Edit service' : 'Add a service'}>
        <form onSubmit={saveService} className="space-y-4">
          <label className="block text-sm font-medium text-slate-700">Service name<input required maxLength={80} value={form.name} onChange={updateForm('name')} placeholder="e.g. Wash and fold" className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-300 px-3.5 text-sm outline-none focus:border-brand focus:ring-4 focus:ring-teal-50" /></label>
          <label className="block text-sm font-medium text-slate-700">Price per unit<input required type="number" min="0.01" step="0.01" value={form.pricePerUnit} onChange={updateForm('pricePerUnit')} placeholder="0.00" className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-300 px-3.5 text-sm outline-none focus:border-brand focus:ring-4 focus:ring-teal-50" /></label>
          <label className="block text-sm font-medium text-slate-700">Unit<select value={form.unit} onChange={updateForm('unit')} className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-300 bg-white px-3.5 text-sm outline-none focus:border-brand focus:ring-4 focus:ring-teal-50"><option value="kg">Kilogram (kg)</option><option value="piece">Piece</option><option value="load">Load</option></select></label>
          <div className="flex flex-wrap gap-2"><button disabled={busy} className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand px-4 text-sm font-semibold text-white hover:bg-teal-700 disabled:opacity-50">{busy ? <LoaderCircle size={16} className="animate-spin" /> : editingId ? <Save size={16} /> : <Plus size={16} />}{editingId ? 'Save changes' : 'Add service'}</button>{editingId && <button type="button" disabled={busy} onClick={resetForm} className="min-h-11 rounded-xl border border-slate-200 px-4 text-sm font-semibold text-slate-600">Cancel edit</button>}</div>
        </form>
      </Panel>
      <Panel title="Shop services" count={services?.length ?? '…'}>
        {loading ? <Loading label="Loading services…" /> : error && !services ? null : !services?.length ? <Empty title="No services yet">Add your first service above. Staff will see active services in their read-only workspace.</Empty> : <div className="space-y-3">{services.map((service) => <article key={service.id} className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-100 p-4"><div className="min-w-0"><div className="flex flex-wrap items-center gap-2"><h3 className="font-semibold text-ink">{service.name}</h3><span className={`rounded-full px-2.5 py-1 text-[11px] font-semibold ${service.active ? 'bg-emerald-50 text-emerald-800' : 'bg-slate-100 text-slate-600'}`}>{service.active ? 'Active' : 'Inactive'}</span></div><p className="mt-1 text-sm text-slate-500">{money(service.pricePerUnit)} per {service.unit}</p></div><div className="flex flex-wrap gap-2"><button type="button" onClick={() => editService(service)} className="min-h-9 rounded-lg border border-slate-200 px-3 text-xs font-semibold text-slate-700 hover:bg-slate-50">Edit</button><button type="button" onClick={() => setConfirmTarget(service)} className={`min-h-9 rounded-lg px-3 text-xs font-semibold ${service.active ? 'border border-rose-200 text-rose-700 hover:bg-rose-50' : 'border border-emerald-200 text-emerald-700 hover:bg-emerald-50'}`}>{service.active ? 'Deactivate' : 'Reactivate'}</button></div></article>)}</div>}
      </Panel>
    </div>
    {confirmTarget && <ConfirmDialog title={confirmTarget.active ? 'Deactivate service?' : 'Reactivate service?'} message={`${confirmTarget.active ? 'Deactivate' : 'Reactivate'} “${confirmTarget.name}” for this shop?`} confirmLabel={confirmTarget.active ? 'Deactivate' : 'Reactivate'} cancelLabel="Cancel" tone={confirmTarget.active ? 'danger' : 'default'} loading={busy} onConfirm={saveActiveState} onCancel={() => setConfirmTarget(null)} formatError={apiErrorMessage} />}
  </>;
}

export function OwnerStaffPage() {
  const [staff, setStaff] = useState(null);
  const [invitations, setInvitations] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  const [busy, setBusy] = useState(false);
  const [email, setEmail] = useState('');
  const [inviteLinks, setInviteLinks] = useState({});
  const [confirmTarget, setConfirmTarget] = useState(null);
  const [toast, setToast] = useState(null);

  useEffect(() => {
    let active = true;
    setLoading(true); setError('');
    Promise.all([api.get('/api/shops/mine/staff'), api.get('/api/shops/mine/staff/invitations')])
      .then(([staffResult, invitationResult]) => {
        if (!active) return;
        setStaff(Array.isArray(staffResult.data) ? staffResult.data : []);
        setInvitations(Array.isArray(invitationResult.data) ? invitationResult.data : []);
      })
      .catch((reason) => { if (active) setError(apiErrorMessage(reason)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [reload]);

  const invite = async (event) => {
    event.preventDefault(); setBusy(true); setError('');
    try {
      const { data } = await api.post('/api/shops/mine/staff/invitations', { email: email.trim() });
      setInviteLinks((current) => ({ ...current, [data.invitation.id]: data.inviteLink }));
      setToast({ type: 'success', message: `Invitation link created for ${data.invitation.email}. Copy and share it with the invitee; no email was sent.` });
      setEmail(''); setReload((value) => value + 1);
    } catch (reason) {
      const message = apiErrorMessage(reason); setError(message); setToast({ type: 'error', message });
    } finally { setBusy(false); }
  };
  const copyLink = async (invitation) => {
    let link = inviteLinks[invitation.id];
    if (!link) {
      setBusy(true);
      try {
        const { data } = await api.post(`/api/shops/mine/staff/invitations/${invitation.id}/resend`);
        link = data.inviteLink;
        setInviteLinks((current) => ({ ...current, [invitation.id]: link }));
        setReload((value) => value + 1);
      } catch (reason) {
        const message = apiErrorMessage(reason); setError(message); setToast({ type: 'error', message }); return;
      } finally { setBusy(false); }
    }
    try {
      await navigator.clipboard.writeText(link);
      setToast({ type: 'success', message: `Invitation link copied for ${invitation.email}.` });
    } catch {
      setToast({ type: 'error', message: 'Clipboard access is unavailable. Select and copy the invitation link shown below.' });
    }
  };
  const confirmAction = async () => {
    setBusy(true); setError('');
    const { type, row } = confirmTarget;
    const path = type === 'revoke'
      ? `/api/shops/mine/staff/invitations/${row.id}/revoke`
      : `/api/shops/mine/staff/${row.id}/${type}`;
    try {
      await api.post(path);
      const description = type === 'revoke' ? 'invitation revoked' : type === 'deactivate' ? 'staff account deactivated' : 'staff member removed from this shop';
      setToast({ type: 'success', message: `${row.email || row.name || 'Staff member'}: ${description}.` });
      setConfirmTarget(null); setReload((value) => value + 1);
    } catch (reason) {
      const message = apiErrorMessage(reason); setToast({ type: 'error', message }); throw new Error(message);
    } finally { setBusy(false); }
  };
  const confirmCopy = confirmTarget?.type === 'revoke'
    ? { title: 'Revoke invitation?', message: `Revoke the pending staff invitation for ${confirmTarget.row.email}? Its link will stop working.`, confirmLabel: 'Revoke', tone: 'danger' }
    : confirmTarget?.type === 'deactivate'
      ? { title: 'Deactivate staff account?', message: `Deactivate ${confirmTarget.row.name || confirmTarget.row.email}? They will lose access immediately.`, confirmLabel: 'Deactivate', tone: 'danger' }
      : confirmTarget ? { title: 'Remove staff member?', message: `Remove ${confirmTarget.row.name || confirmTarget.row.email} from this shop? Their staff role will be revoked.`, confirmLabel: 'Remove', tone: 'danger' } : null;

  return <>
    <Toast type={toast?.type} message={toast?.message} onDismiss={() => setToast(null)} />
    <Heading eyebrow="Owner workspace" title="Staff" description="Invite verified accounts and manage staff assigned to your shop." />
    <ErrorBox message={error} onRetry={() => setReload((value) => value + 1)} />
    <div className="grid items-start gap-5 xl:grid-cols-[minmax(280px,.75fr)_minmax(0,1.25fr)]">
      <Panel title="Invite staff">
        <form onSubmit={invite} className="space-y-3"><label className="block text-sm font-medium text-slate-700">Verified email address<input required type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} placeholder="staff@example.com" className="mt-1.5 min-h-11 w-full rounded-xl border border-slate-300 px-3.5 text-sm outline-none focus:border-brand focus:ring-4 focus:ring-teal-50" /></label><p className="text-xs leading-5 text-slate-500">No email is sent. You’ll receive a one-time invitation link to copy and share. It expires after seven days.</p><button disabled={busy} className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand px-4 text-sm font-semibold text-white hover:bg-teal-700 disabled:opacity-50">{busy ? <LoaderCircle size={16} className="animate-spin" /> : <Mail size={16} />}Create invitation</button></form>
      </Panel>
      <Panel title="Staff invitations" count={invitations?.length ?? '…'}>
        {loading ? <Loading label="Loading invitations…" /> : !invitations?.length ? <Empty icon={Mail} title="No invitations yet">Invitations you create will appear here. A raw link is shown only when generated; use “Copy / new link” to issue a fresh link later.</Empty> : <div className="space-y-3">{invitations.map((invitation) => <article key={invitation.id} className="rounded-2xl border border-slate-100 p-4"><div className="flex flex-wrap items-center justify-between gap-2"><div className="min-w-0"><p className="break-all text-sm font-semibold text-ink">{invitation.email}</p><p className="mt-1 text-xs text-slate-500">Expires {dateTime(invitation.expiresAt)}</p></div><span className={`rounded-full px-2.5 py-1 text-[11px] font-semibold ${invitation.status === 'PENDING' ? 'bg-amber-50 text-amber-800' : invitation.status === 'ACCEPTED' ? 'bg-emerald-50 text-emerald-800' : 'bg-slate-100 text-slate-600'}`}>{invitation.status}</span></div>{invitation.status === 'PENDING' && <div className="mt-3 flex flex-wrap gap-2"><button type="button" disabled={busy} onClick={() => copyLink(invitation)} className="inline-flex min-h-9 items-center gap-2 rounded-lg border border-slate-200 px-3 text-xs font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50"><Clipboard size={14} />{inviteLinks[invitation.id] ? 'Copy link' : 'Copy / new link'}</button><button type="button" disabled={busy} onClick={() => setConfirmTarget({ type: 'revoke', row: invitation })} className="min-h-9 rounded-lg border border-rose-200 px-3 text-xs font-semibold text-rose-700 hover:bg-rose-50">Revoke</button></div>}{inviteLinks[invitation.id] && <p className="mt-3 break-all rounded-lg bg-slate-50 p-2 text-xs text-slate-600">{inviteLinks[invitation.id]}</p>}</article>)}</div>}
      </Panel>
      <div className="xl:col-span-2"><Panel title="Shop staff" count={staff?.length ?? '…'}>
        {loading ? <Loading label="Loading staff…" /> : !staff?.length ? <Empty icon={Users} title="No staff members yet">When an invite is accepted, the account will be listed here with its online status.</Empty> : <div className="space-y-3">{staff.map((member) => <article key={member.id} className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-100 p-4"><div className="flex min-w-0 items-center gap-3"><span className={`h-2.5 w-2.5 shrink-0 rounded-full ${member.online ? 'bg-emerald-500' : 'bg-slate-300'}`} aria-hidden="true" /><div className="min-w-0"><p className="font-semibold text-ink">{member.name || 'Staff member'}</p><p className="break-all text-xs text-slate-500">{member.email}</p><p className="mt-1 text-xs text-slate-500">{member.online ? 'Online now' : `Offline · last seen ${dateTime(member.lastSeen)}`}</p><p className="mt-1 text-xs text-slate-500">Account {member.active ? 'active' : 'deactivated'}</p></div></div><div className="flex flex-wrap gap-2">{member.active && <button type="button" disabled={busy} onClick={() => setConfirmTarget({ type: 'deactivate', row: member })} className="min-h-9 rounded-lg border border-rose-200 px-3 text-xs font-semibold text-rose-700 hover:bg-rose-50 disabled:opacity-50">Deactivate</button>}<button type="button" disabled={busy} onClick={() => setConfirmTarget({ type: 'remove', row: member })} className="min-h-9 rounded-lg border border-slate-200 px-3 text-xs font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50">Remove</button></div></article>)}</div>}
      </Panel></div>
    </div>
    {confirmTarget && confirmCopy && <ConfirmDialog title={confirmCopy.title} message={confirmCopy.message} confirmLabel={confirmCopy.confirmLabel} cancelLabel="Cancel" tone={confirmCopy.tone} loading={busy} onConfirm={confirmAction} onCancel={() => setConfirmTarget(null)} formatError={apiErrorMessage} />}
  </>;
}

export function StaffServicesPanel() {
  const [services, setServices] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  useEffect(() => {
    let active = true;
    api.get('/api/shops/services').then(({ data }) => { if (active) setServices(Array.isArray(data) ? data : []); })
      .catch((reason) => { if (active) setError(apiErrorMessage(reason)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [reload]);
  return <section className="mt-6 rounded-3xl border border-slate-100 bg-white p-5 shadow-soft sm:p-6"><div className="mb-4 flex items-center justify-between gap-3"><div><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Read only</p><h2 className="mt-1 text-lg font-semibold">Active shop services</h2></div><button type="button" disabled={loading} onClick={() => { setLoading(true); setReload((value) => value + 1); }} className="inline-flex min-h-9 items-center gap-2 rounded-lg border border-slate-200 px-3 text-xs font-semibold text-slate-700 disabled:opacity-50"><RefreshCw size={14} />Refresh</button></div>{error && <ErrorBox message={error} onRetry={() => { setLoading(true); setReload((value) => value + 1); }} />}{loading ? <Loading label="Loading active services…" /> : !services?.length ? <Empty title="No active services">Ask the shop owner to add or activate services.</Empty> : <ul className="divide-y divide-slate-100">{services.map((service) => <li key={service.id} className="flex items-center justify-between gap-3 py-3"><span className="font-medium text-ink">{service.name}</span><span className="text-sm text-slate-600">{money(service.pricePerUnit)} per {service.unit}</span></li>)}</ul>}</section>;
}

function InvitationVerificationState({ user }) {
  const { syncSession } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const resend = async () => {
    setBusy(true); setError(''); setNotice('');
    try { await sendEmailVerification(user, { url: window.location.href }); setNotice('Verification email sent. Open its link, then return here and check your status.'); }
    catch (reason) { setError(apiErrorMessage(reason, 'The verification email could not be resent.')); }
    finally { setBusy(false); }
  };
  const check = async () => {
    setBusy(true); setError(''); setNotice('');
    try {
      await reloadFirebaseUser(user);
      if (!user.emailVerified) { setError('Your email is still waiting for verification. Open the verification link, then try again.'); return; }
      await user.getIdToken(true);
      await syncSession(user);
    } catch (reason) { setError(apiErrorMessage(reason, 'Your verified session could not be refreshed.')); }
    finally { setBusy(false); }
  };
  return <main className="mx-auto flex w-full max-w-2xl flex-1 items-center px-5 py-12"><section className="w-full rounded-3xl border border-slate-100 bg-white p-7 shadow-soft sm:p-9"><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Staff invitation</p><h1 className="mt-2 text-2xl font-semibold text-ink">Waiting for email verification</h1><p className="mt-3 text-sm leading-6 text-slate-500">Verify the email address the shop owner invited. After verification, return here and refresh your verified session.</p><ErrorBox message={error} />{notice && <p role="status" className="mt-3 rounded-xl bg-emerald-50 p-3 text-sm text-emerald-800">{notice}</p>}<div className="mt-5 flex flex-wrap gap-3"><button type="button" disabled={busy} onClick={resend} className="inline-flex min-h-11 items-center gap-2 rounded-xl border border-slate-200 px-4 text-sm font-semibold text-slate-700 disabled:opacity-50">{busy ? <LoaderCircle size={16} className="animate-spin" /> : <Mail size={16} />}Resend verification email</button><button type="button" disabled={busy} onClick={check} className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand px-4 text-sm font-semibold text-white disabled:opacity-50">I’ve verified my email</button></div></section></main>;
}

export function AcceptInvitationPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const { firebaseUser, session, syncSession } = useAuth();
  const token = new URLSearchParams(location.search).get('token') || '';
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => { if (session) takeInvitationReturn(); else rememberInvitationReturn(location); }, [location.pathname, location.search, session]);
  if (!session && firebaseUser && !firebaseUser.emailVerified) return <InvitationVerificationState user={firebaseUser} />;

  const invitationError = (reason) => {
    const message = apiErrorMessage(reason, 'The invitation could not be accepted.');
    const lower = message.toLowerCase();
    if (lower.includes('different email') || lower.includes('does not match')) return 'This invitation was sent to a different email address. Sign in with the invited email; the invited address is not displayed here.';
    if (lower.includes('verified') || lower.includes('verify')) return 'Verify your email address before accepting this invitation, then sign in again.';
    if (lower.includes('expired')) return 'This invitation has expired. Ask the shop owner to send a new invitation.';
    if (lower.includes('revoked') || lower.includes('no longer available')) return 'This invitation was revoked or is no longer available. Ask the shop owner for a new link.';
    if (lower.includes('already been used') || lower.includes('already accepted')) return 'This invitation has already been used.';
    if (lower.includes('cannot join another shop') || lower.includes('only an unassigned customer')) return 'This account is an owner, admin, or is already assigned to another shop and cannot accept this invitation.';
    if (lower.includes('invalid or no longer current')) return 'This invitation link is invalid or no longer current. Ask the shop owner for a new link.';
    return message;
  };

  const accept = async () => {
    if (!token) { setError('This invitation link is missing its token. Ask the shop owner to create a new one.'); return; }
    setBusy(true); setError('');
    try {
      const { data } = await api.post('/api/staff/invitations/accept', { token });
      await firebaseUser.getIdToken(true);
      await syncSession(firebaseUser);
      takeInvitationReturn();
      navigate('/staff', { replace: true, state: { toast: `You joined ${data.shopName || 'the shop'} as staff.` } });
    } catch (reason) { setError(invitationError(reason)); }
    finally { setBusy(false); }
  };

  if (!session) return <div className="mx-auto flex w-full max-w-2xl flex-1 items-center px-5 py-12"><section className="w-full rounded-3xl border border-slate-100 bg-white p-7 shadow-soft sm:p-9"><p className="text-xs font-bold uppercase tracking-[.16em] text-brand">Staff invitation</p><h1 className="mt-2 text-2xl font-semibold text-ink">You’ve been invited to join a shop</h1><p className="mt-3 text-sm leading-6 text-slate-500">Sign in or create an account using the verified email address the shop owner invited. You’ll return to this invitation after authentication.</p>{!token && <ErrorBox message="This invitation link is missing its token. Ask the shop owner for a new link." />}<div className="mt-5 flex flex-wrap gap-3"><Link to="/sign-in" state={{ from: { pathname: location.pathname, search: location.search } }} className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand px-4 text-sm font-semibold text-white hover:bg-teal-700">Sign in <ArrowRight size={16} /></Link><Link to="/sign-in?mode=create" state={{ from: { pathname: location.pathname, search: location.search } }} className="inline-flex min-h-11 items-center gap-2 rounded-xl border border-slate-200 px-4 text-sm font-semibold text-slate-700 hover:bg-slate-50">Create account</Link></div></section></div>;

  return <>
    <Heading eyebrow="Staff invitation" title="Accept invitation" description="Only the verified email address invited by the owner can accept this link." />
    <section className="max-w-2xl rounded-3xl border border-slate-100 bg-white p-6 shadow-soft sm:p-8"><ErrorBox message={error} />{!token && <ErrorBox message="This invitation link is missing its token. Ask the shop owner for a new link." />}<p className="text-sm leading-6 text-slate-600">Signed in as <strong className="break-all">{session.email}</strong>. Check that this is the invited account, then explicitly accept the invitation.</p><button type="button" disabled={busy || !token} onClick={accept} className="mt-5 inline-flex min-h-11 items-center gap-2 rounded-xl bg-brand px-4 text-sm font-semibold text-white hover:bg-teal-700 disabled:opacity-50">{busy ? <LoaderCircle size={16} className="animate-spin" /> : <ArrowRight size={16} />}Accept invitation</button></section>
  </>;
}

export function CustomerInvitationsPage() {
  const { firebaseUser, syncSession } = useAuth();
  const navigate = useNavigate();
  const [invitations, setInvitations] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  const [busy, setBusy] = useState(false);
  const [confirmTarget, setConfirmTarget] = useState(null);
  const [toast, setToast] = useState(null);

  useEffect(() => {
    let active = true;
    setLoading(true); setError('');
    api.get('/api/me/invitations')
      .then(({ data }) => { if (active) setInvitations(Array.isArray(data) ? data : []); })
      .catch((reason) => { if (active) setError(apiErrorMessage(reason)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [reload]);

  const performAction = async () => {
    const { action, invitation } = confirmTarget;
    setBusy(true); setError('');
    try {
      const { data } = await api.post(`/api/me/invitations/${encodeURIComponent(invitation.invitationId)}/${action}`);
      if (action === 'accept') {
        await firebaseUser.getIdToken(true);
        await syncSession(firebaseUser);
        setConfirmTarget(null);
        window.dispatchEvent(new Event('spindletrack:invitations-changed'));
        navigate('/staff', { replace: true, state: { toast: `Welcome to ${data?.shopName || invitation.shopName || 'the shop'} as staff.` } });
      } else {
        setConfirmTarget(null);
        setToast({ type: 'success', message: `Invitation to ${invitation.shopName} declined.` });
        window.dispatchEvent(new Event('spindletrack:invitations-changed'));
        setReload((value) => value + 1);
      }
    } catch (reason) {
      const message = apiErrorMessage(reason, `The invitation could not be ${action}ed.`);
      setToast({ type: 'error', message });
      throw new Error(message);
    } finally { setBusy(false); }
  };

  if (shouldRedirectEmptyInbox({ loading, error, invitations })) return <Navigate to="/customer/orders" replace />;

  return <>
    <Toast type={toast?.type} message={toast?.message} onDismiss={() => setToast(null)} />
    <Heading eyebrow="Customer" title="Invitations" description="Review shop invitations sent to your verified email address." />
    <ErrorBox message={error} onRetry={() => setReload((value) => value + 1)} />
    {loading ? <Loading label="Loading your invitations…" /> : !invitations?.length ? <Empty icon={Mail} title="No pending invitations">New invitations sent to your verified email will appear here.</Empty> : <div className="space-y-3">{invitations.map((invitation) => <article key={invitation.invitationId} className="flex flex-wrap items-center justify-between gap-4 rounded-2xl border border-slate-100 bg-white p-5 shadow-soft"><div><p className="text-xs font-bold uppercase tracking-[.14em] text-brand">Shop invitation</p><h2 className="mt-1 text-lg font-semibold text-ink">{invitation.shopName}</h2><p className="mt-1 text-xs text-slate-500">Invited {dateTime(invitation.invitedAt)} · Expires {dateTime(invitation.expiresAt)}</p></div><div className="flex flex-wrap gap-2"><button type="button" disabled={busy} onClick={() => setConfirmTarget({ action: 'accept', invitation })} className="min-h-10 rounded-xl bg-brand px-4 text-sm font-semibold text-white hover:bg-teal-700 disabled:opacity-50">Accept</button><button type="button" disabled={busy} onClick={() => setConfirmTarget({ action: 'decline', invitation })} className="min-h-10 rounded-xl border border-rose-200 px-4 text-sm font-semibold text-rose-700 hover:bg-rose-50 disabled:opacity-50">Decline</button></div></article>)}</div>}
    {confirmTarget && <ConfirmDialog title={confirmTarget.action === 'accept' ? 'Accept shop invitation?' : 'Decline shop invitation?'} message={`${confirmTarget.action === 'accept' ? 'Join' : 'Decline the invitation to'} “${confirmTarget.invitation.shopName}”? ${confirmTarget.action === 'accept' ? 'Your account will become staff for this shop.' : 'The invitation will be removed from your pending inbox.'}`} confirmLabel={confirmTarget.action === 'accept' ? 'Accept invitation' : 'Decline invitation'} cancelLabel="Cancel" tone={confirmTarget.action === 'decline' ? 'danger' : 'default'} loading={busy} onConfirm={performAction} onCancel={() => setConfirmTarget(null)} formatError={apiErrorMessage} />}
  </>;
}
