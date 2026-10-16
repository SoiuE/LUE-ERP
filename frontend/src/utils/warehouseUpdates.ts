import { onMounted, onUnmounted } from 'vue'

const updateKey = 'lue-warehouse-updated'
const updateEvent = 'lue:warehouse-updated'

export function notifyWarehouseChange() {
  window.dispatchEvent(new Event(updateEvent))
  try { localStorage.setItem(updateKey, crypto.randomUUID()) } catch { /* Periodic refresh still works without storage. */ }
}

// Polling also covers changes submitted by a different phone or browser.
export function useWarehouseRefresh(refresh: () => Promise<unknown>) {
  let timer: ReturnType<typeof setInterval> | undefined
  let running = false
  async function reload() {
    if (document.visibilityState !== 'visible' || running) return
    running = true
    try { await refresh() } catch { /* Retry on the next refresh; manual queries still report errors. */ } finally { running = false }
  }
  function stored(event: StorageEvent) { if (event.key === updateKey) void reload() }
  onMounted(() => {
    timer = setInterval(() => { void reload() }, 15000)
    window.addEventListener('focus', reload)
    window.addEventListener(updateEvent, reload)
    window.addEventListener('storage', stored)
    document.addEventListener('visibilitychange', reload)
  })
  onUnmounted(() => {
    clearInterval(timer)
    window.removeEventListener('focus', reload)
    window.removeEventListener(updateEvent, reload)
    window.removeEventListener('storage', stored)
    document.removeEventListener('visibilitychange', reload)
  })
}
