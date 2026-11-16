export function hasPermission(user, permission) {
  if (!user || !permission) return false;
  const roles = user.roles || [];
  if (roles.includes('ROLE_SUPER_ADMIN') || user.role === 'ADMIN') return true;
  return (user.authorities || []).includes(permission);
}

export function isStaffUser(user) {
  return Boolean(user && (user.role === 'ADMIN' || user.roles?.length || user.role?.startsWith('ROLE_')));
}

export function isSuperAdmin(user) {
  return Boolean(user && (user.role === 'ADMIN' || user.roles?.includes('ROLE_SUPER_ADMIN')));
}
