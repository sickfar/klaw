export function useAuth() {
  const { api, token, setToken } = useApi()
  const authRequired = useState<boolean | null>('authRequired', () => null)
  const authenticated = useState<boolean>('authenticated', () => false)
  const checking = useState<boolean>('authChecking', () => false)

  async function checkAuth(): Promise<void> {
    checking.value = true
    try {
      const res = await api<{ authenticated: boolean, authRequired: boolean }>('/auth/check')
      authRequired.value = res.authRequired
      authenticated.value = res.authenticated
    }
    catch {
      // Network error or server down — stay in unknown state, don't grant access
      authRequired.value = null
      authenticated.value = false
    }
    finally {
      checking.value = false
    }
  }

  async function login(newToken: string): Promise<boolean> {
    setToken(newToken)
    try {
      const res = await api<{ authenticated: boolean }>('/auth/check')
      if (res.authenticated) {
        authenticated.value = true
        // Reconnect WebSocket with new token
        const ws = useWebSocket()
        ws.reconnect()
        return true
      }
      setToken(null)
      authenticated.value = false
      return false
    }
    catch {
      setToken(null)
      authenticated.value = false
      return false
    }
  }

  function logout() {
    setToken(null)
    authenticated.value = false
    authRequired.value = true
  }

  return { authRequired, authenticated, checking, checkAuth, login, logout, token }
}
