import { notifyWarehouseChange } from './utils/warehouseUpdates'

export interface AuthUser {
  id: number
  username: string
  displayName: string
  roles: string[]
  permissions: string[]
}

const tokenKey = 'lue-auth-token'
export const getToken = () => localStorage.getItem(tokenKey)
export const saveToken = (token: string) => localStorage.setItem(tokenKey, token)
export const clearToken = () => localStorage.removeItem(tokenKey)

async function request<T>(path: string, options: RequestInit, token: string | null, retryWithCurrentToken: boolean): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.body instanceof FormData) headers.delete('Content-Type')
  else if (options.body !== undefined) headers.set('Content-Type', 'application/json')
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const response = await fetch(path, { ...options, headers })

  // Another in-flight request or a login in another tab may have installed a newer token.
  // A 401 is rejected before the API handler runs, so retrying once with that token cannot
  // duplicate a business write.
  if (response.status === 401 && path !== '/api/auth/login' && retryWithCurrentToken) {
    const currentToken = getToken()
    if (currentToken && currentToken !== token) return request<T>(path, options, currentToken, false)
  }

  const responseText = await response.text()
  let body: { success?: boolean; message?: string; data?: T } | undefined
  if (responseText.trim()) {
    try {
      body = JSON.parse(responseText)
    } catch {
      throw new Error(`${path} 返回了无法解析的响应（HTTP ${response.status}）`)
    }
  }
  if (response.status === 401 && path !== '/api/auth/login') {
    let identity: 'valid' | 'invalid' | 'unknown' = token ? 'unknown' : 'invalid'
    if (token) {
      try {
        const check = await fetch('/api/auth/me', { headers: { Authorization: `Bearer ${token}` } })
        if (check.ok) identity = 'valid'
        else if (check.status === 401) identity = 'invalid'
      } catch { /* A network error cannot prove that the token is invalid. */ }
    }
    if (identity === 'valid') {
      throw new Error(`${path} 返回 401，但当前登录仍有效；本次操作未提交。服务端信息：${body?.message || '未提供原因'}`)
    }
    if (identity === 'invalid') {
      // A concurrent login may have installed another token while the check was running.
      if (getToken() === token) clearToken()
      throw new Error('登录凭证无效或已过期，本次操作未提交；请重新登录后重试。')
    }
    throw new Error(`${path} 返回 401，且暂时无法核对登录状态；本次操作未提交。服务端信息：${body?.message || '未提供原因'}`)
  }
  if (!response.ok || !body?.success) {
    throw new Error(body?.message || `${path} 请求失败（HTTP ${response.status}${response.statusText ? ` ${response.statusText}` : ''}${responseText.trim() ? '' : '，响应为空'}）`)
  }
  return body.data as T
}

export function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  return request<T>(path, options, getToken(), true).then(result => {
    if (!['GET', 'HEAD'].includes((options.method || 'GET').toUpperCase()) && /^\/api\/(goods-procurement|warehouse-locations)(\/|\?|$)/.test(path)) notifyWarehouseChange()
    return result
  })
}
