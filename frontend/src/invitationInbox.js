export const INVITATION_PROMPT_PREFIX = 'spindletrack.invitationPromptDismissed.';

export function hasPendingInvitations(invitations) {
  return Array.isArray(invitations) && invitations.length > 0;
}

export function customerMenuForInvitations(menu, invitations) {
  return (menu || []).filter((item) => !item.invitationBadge || hasPendingInvitations(invitations));
}

export function undismissedInvitations(invitations, storage = globalThis.sessionStorage) {
  if (!Array.isArray(invitations)) return [];
  return invitations.filter((invitation) => {
    const id = invitation?.invitationId;
    return Boolean(id) && !storage?.getItem(`${INVITATION_PROMPT_PREFIX}${id}`);
  });
}

export function dismissInvitationPrompt(invitationId, storage = globalThis.sessionStorage) {
  if (!invitationId || !storage) return;
  storage.setItem(`${INVITATION_PROMPT_PREFIX}${invitationId}`, '1');
}

export function shouldRedirectEmptyInbox({ loading, error, invitations }) {
  return !loading && !error && Array.isArray(invitations) && invitations.length === 0;
}
