import type { ReactElement } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from './AuthProvider'

/** Route guard: renders children only when the user holds the permission (no-op when RBAC is disabled). */
export default function RequirePermission({
  permission,
  children,
}: {
  permission: string
  children: ReactElement
}) {
  const { hasPermission } = useAuth()
  return hasPermission(permission) ? children : <Navigate to="/" replace />
}
