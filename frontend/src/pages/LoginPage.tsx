import { useState, type FormEvent } from 'react'
import { useAuth } from '../auth/AuthContext'
import { ErrorBox } from '../components/ui'

export default function LoginPage() {
  const { login } = useAuth()
  const [username, setUsername] = useState('applicant')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login(username, password)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login card">
      <div className="brand" style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}><span className="logo" style={{ width: 22, height: 22, borderRadius: 6, background: 'linear-gradient(135deg,#2a78d6,#1baf7a 60%,#eda100)' }} /><strong>Prism Underwriting</strong></div>
      <p className="muted small">Real-time, multi-modal credit decisioning prototype. Sign in as <code>applicant</code>, <code>underwriter</code> or <code>admin</code> with the seeded password (see <code>PRISM_SEED_PASSWORD</code>).</p>
      <form onSubmit={submit} className="stack">
        <label className="field">Username<input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required /></label>
        <label className="field">Password<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required /></label>
        <ErrorBox error={error} />
        <button className="btn primary" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
      </form>
    </div>
  )
}
