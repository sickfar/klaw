import type { ChatFrame } from '~/types/chat'

type FrameCallback = (frame: ChatFrame) => void

export function useWebSocket() {
  const status = useState<'connecting' | 'connected' | 'disconnected'>('wsStatus', () => 'disconnected')
  const callbacks = useState<FrameCallback[]>('wsCallbacks', () => [])

  let ws: WebSocket | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let reconnectDelay = 1000
  let intentionalClose = false

  function getWsUrl(): string {
    if (import.meta.client) {
      const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
      return `${proto}//${window.location.host}/ws/chat`
    }
    return 'ws://localhost/ws/chat'
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
          cb(frame)
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
      ws.send(JSON.stringify(frame))
      return true
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

  return {
    status,
    connect,
    disconnect,
    send,
    onFrame,
    offFrame,
  }
}
