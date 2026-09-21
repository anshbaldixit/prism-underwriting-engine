import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, configureApi, get, post } from './client'

function mockFetch(status: number, body: unknown, statusText = '') {
  return vi.fn(async () => ({
    ok: status >= 200 && status < 300,
    status,
    statusText,
    json: async () => body,
  })) as unknown as typeof fetch
}

describe('api client', () => {
  const originalFetch = globalThis.fetch
  beforeEach(() => configureApi(() => 'tok-123', () => {}))
  afterEach(() => {
    globalThis.fetch = originalFetch
  })

  it('attaches the bearer token and JSON headers', async () => {
    globalThis.fetch = mockFetch(200, { ok: true })
    await post('/api/x', { a: 1 })
    const [, init] = (globalThis.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0] as [string, RequestInit]
    const headers = init.headers as Headers
    expect(headers.get('Authorization')).toBe('Bearer tok-123')
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(init.method).toBe('POST')
    expect(init.body).toBe(JSON.stringify({ a: 1 }))
  })

  it('turns a problem-detail response into a readable ApiError', async () => {
    globalThis.fetch = mockFetch(400, { status: 400, detail: 'Bank transaction data cannot be processed without explicit consent' })
    await expect(get('/api/applications')).rejects.toMatchObject({ status: 400, message: expect.stringContaining('explicit consent') })
  })

  it('flattens Bean Validation field errors', async () => {
    globalThis.fetch = mockFetch(400, { title: 'Bad Request', errors: [{ field: 'loan.requestedAmount', defaultMessage: 'must be greater than or equal to 300' }] })
    await expect(post('/api/applications', {})).rejects.toMatchObject({ message: expect.stringContaining('loan.requestedAmount must be greater than or equal to 300') })
  })

  it('signals session expiry on 401 and clears the caller', async () => {
    const onUnauthorized = vi.fn()
    configureApi(() => 'stale', onUnauthorized)
    globalThis.fetch = mockFetch(401, {})
    await expect(get('/api/applications')).rejects.toBeInstanceOf(ApiError)
    expect(onUnauthorized).toHaveBeenCalledOnce()
  })

  it('sends no Authorization header when there is no session', async () => {
    configureApi(() => null, () => {})
    globalThis.fetch = mockFetch(200, [])
    await get('/api/demo/personas')
    const [, init] = (globalThis.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls[0] as [string, RequestInit]
    expect((init.headers as Headers).get('Authorization')).toBeNull()
  })
})
