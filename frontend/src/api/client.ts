// Thin fetch wrapper: attaches the bearer token, surfaces RFC 9457 problem details as readable errors,
// and never logs request bodies (they can contain applicant data).

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

let tokenProvider: () => string | null = () => null
let onUnauthorized: () => void = () => {}

export function configureApi(getToken: () => string | null, unauthorized: () => void) {
  tokenProvider = getToken
  onUnauthorized = unauthorized
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (init.body) headers.set('Content-Type', 'application/json')
  const token = tokenProvider()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const res = await fetch(path, { ...init, headers })
  if (res.status === 401) {
    onUnauthorized()
    throw new ApiError(401, 'Session expired - please sign in again')
  }
  if (!res.ok) {
    let detail = res.statusText
    try {
      const body = await res.json()
      detail = body.detail ?? body.title ?? detail
      if (body.errors && Array.isArray(body.errors)) {
        detail += ': ' + body.errors.map((e: { field?: string; defaultMessage?: string }) => `${e.field ?? ''} ${e.defaultMessage ?? ''}`.trim()).join('; ')
      }
    } catch {
      /* non-JSON error body */
    }
    throw new ApiError(res.status, detail)
  }
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

export const get = <T>(path: string) => api<T>(path)
export const post = <T>(path: string, body: unknown) => api<T>(path, { method: 'POST', body: JSON.stringify(body) })
