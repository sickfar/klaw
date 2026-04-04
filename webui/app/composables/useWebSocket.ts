import type { ChatFrame } from '~/types/chat'

type FrameCallback = (frame: ChatFrame) => void

// Module-level singleton state — shared across all useWebSocket() callers
let ws: WebSocket | null = null
let reconnectTimer: ReturnType<typeof setTimeout> | null = null
let reconnectDelay = 1000
let intentionalClose = false

export function useWebSocket() {
  const status = useState<'connecting' | 'connected' | 'disconnected'>('wsStatus', () => 'disconnected')
  const callbacks = useState<FrameCallback[]>('wsCallbacks', () => [])

  function getWsUrl(agentId: string = 'default'): string {
    if (import.meta.client) {
      const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      const base = `${proto}//${window.location.host}/ws/chat/${agentId}`
      const savedToken = localStorage.getItem('klaw_token')
      return savedToken ? `${base}?token=${encodeURIComponent(savedToken)}` : base
    }
    return `ws://localhost/ws/chat/${agentId}`
  }

  function connect() {
    if (!import.meta.client) return
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return

    intentionalClose = false
    status.value = 'connecting'

    ws = new WebSocket(getWsUrl())

    ws.onopen = () => {
      status.value = 'connected'
      reconnectDelay = 1000
    }

    ws.onmessage = (event) => {
      try {
        const frame = JSON.parse(event.data) as ChatFrame
        for (const cb of callbacks.value) {
          try {
            cb(frame)
          }
          catch {
            // isolate callback errors to prevent poisoning other callbacks
          }
        }
      }
      catch {
        // ignore malformed frames
      }
    }

    ws.onclose = () => {
      status.value = 'disconnected'
      ws = null
      if (!intentionalClose) {
        scheduleReconnect()
      }
    }

    ws.onerror = () => {
      // onclose will fire after onerror
    }
  }

  function scheduleReconnect() {
    if (reconnectTimer) clearTimeout(reconnectTimer)
    reconnectTimer = setTimeout(() => {
      reconnectDelay = Math.min(reconnectDelay * 2, 30000)
      connect()
    }, reconnectDelay)
  }

  function disconnect() {
    intentionalClose = true
    reconnectDelay = 1000
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (ws) {
      ws.close()
      ws = null
    }
    status.value = 'disconnected'
  }

  function send(frame: ChatFrame): boolean {
    if (ws && ws.readyState === WebSocket.OPEN) {
      try {
        ws.send(JSON.stringify(frame))
        return true
      }
      catch {
        return false
      }
    }
    return false
  }

  function onFrame(callback: FrameCallback) {
    callbacks.value.push(callback)
  }

  function offFrame(callback: FrameCallback) {
    const idx = callbacks.value.indexOf(callback)
    if (idx >= 0) callbacks.value.splice(idx, 1)
  }

  function reconnect() {
    disconnect()
    connect()
  }

  return {
    status,
    connect,
    disconnect,
    reconnect,
    send,
    onFrame,
    offFrame,
  }
}
