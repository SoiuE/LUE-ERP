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

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.body instanceof FormData) headers.delete('Content-Type')
  else if (options.body !== undefined) headers.set('Content-Type', 'application/json')
  const token = getToken()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const response = await fetch(path, { ...options, headers })
  const responseText = await response.text()
  let body: { success?: boolean; message?: string; data?: T } | undefined
  if (responseText.trim()) {
    try {
      body = JSON.parse(responseText)
    } catch {
      throw new Error(`${path} 返回了无法解析的响应（HTTP ${response.status}）`)
    }
  }
  if (!response.ok || !body?.success) {
    throw new Error(body?.message || `${path} 请求失败（HTTP ${response.status}${response.statusText ? ` ${response.statusText}` : ''}${responseText.trim() ? '' : '，响应为空'}）`)
  }
  return body.data as T
}
