// Manages terminal authentication flow: password dialog, ticket acquisition, and connection state.
import { useState, useCallback } from 'react'
import { authorizeTerminal } from '../services/terminalService'

interface Deps {
  notify: (msg: string, severity: 'success' | 'error') => void
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- i18next TFunction has complex overloads
  t: (...args: any[]) => string
}

export function useTerminalAuth({ notify, t }: Deps) {
  const [ticket, setTicket] = useState('')
  const [containerName, setContainerName] = useState('')
  const [authDialogOpen, setAuthDialogOpen] = useState(false)
  const [pendingContainerId, setPendingContainerId] = useState('')
  const [pendingContainerName, setPendingContainerName] = useState('')

  const requestTerminal = useCallback((containerId: string, name: string, passwordRequired: boolean) => {
    if (passwordRequired) {
      setPendingContainerId(containerId)
      setPendingContainerName(name)
      setAuthDialogOpen(true)
    } else {
      authorizeTerminal(containerId, '').then((res) => {
        if (res.ticket) {
          setTicket(res.ticket)
          setContainerName(name)
        } else {
          notify(res.error || t('common.unexpectedError'), 'error')
        }
      }).catch(() => notify(t('common.unexpectedError'), 'error'))
    }
  }, [notify, t])

  const confirmAuth = useCallback(async (password: string) => {
    const res = await authorizeTerminal(pendingContainerId, password)
    if (res.ticket) {
      setTicket(res.ticket)
      setContainerName(pendingContainerName)
      setAuthDialogOpen(false)
    } else {
      throw new Error(res.error || t('common.unexpectedError'))
    }
  }, [pendingContainerId, pendingContainerName, t])

  const cancelAuth = useCallback(() => {
    setAuthDialogOpen(false)
    setPendingContainerId('')
    setPendingContainerName('')
  }, [])

  const closeTerminal = useCallback(() => {
    setTicket('')
    setContainerName('')
  }, [])

  return {
    ticket,
    containerName,
    authDialogOpen,
    requestTerminal,
    confirmAuth,
    cancelAuth,
    closeTerminal,
  }
}
