import { useAuth } from '../components/AuthProvider'

/** True when the current user holds the permission (always true when RBAC is disabled). */
export default function usePermission(permission: string): boolean {
  const { hasPermission } = useAuth()
  return hasPermission(permission)
}
