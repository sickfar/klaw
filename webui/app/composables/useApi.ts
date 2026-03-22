export function useApi() {
  const token = useState<string | null>('apiToken', () => {
    if (import.meta.client) return localStorage.getItem('klaw_token')
    return null
  })

  function setToken(value: string | null) {
    token.value = value
    if (import.meta.client) {
      if (value) {
        localStorage.setItem('klaw_token', value)
      }
      else {
        localStorage.removeItem('klaw_token')
      }
    }
  }

  async function api<T>(path: string, options?: Parameters<typeof $fetch>[1]): Promise<T> {
    return $fetch<T>(`/api/v1${path}`, {
      ...options,
      headers: {
        ...((options as Record<string, unknown>)?.headers as Record<string, string> || {}),
        ...(token.value ? { Authorization: `Bearer ${token.value}` } : {}),
      },
    })
  }

  return { api, token, setToken }
}
