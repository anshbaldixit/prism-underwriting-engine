import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react'
import { configureApi, post } from '../api/client'
import type { Session } from '../api/types'

interface AuthState {
  session: Session | null
  login: (username: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthState | null>(null)
const STORAGE_KEY = 'prism.session'

function load(): Session | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const s = JSON.parse(raw) as Session
    return new Date(s.expiresAt).getTime() > Date.now() ? s : null
  } catch {
    return null
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(load)
  const sessionRef = useRef(session)
  sessionRef.current = session

  const logout = useCallback(() => {
    setSession(null)
    try {
      sessionStorage.removeItem(STORAGE_KEY)
    } catch {
      /* storage unavailable */
    }
  }, [])

  // Wired synchronously during render (not in an effect): child pages fetch in their own effects, which run
  // before a parent's effects would, so the token provider must already be in place by then.
  configureApi(() => sessionRef.current?.token ?? null, logout)

  const login = useCallback(async (username: string, password: string) => {
    const s = await post<Session>('/api/auth/login', { username, password })
    setSession(s)
    try {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(s))
    } catch {
      /* storage unavailable: session lives in memory only */
    }
  }, [])

  const value = useMemo(() => ({ session, login, logout }), [session, login, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth outside AuthProvider')
  return ctx
}
