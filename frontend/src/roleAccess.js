export function roleMayAccess(role, allowedRoles) {
  return Array.isArray(allowedRoles) && allowedRoles.includes(role);
}
