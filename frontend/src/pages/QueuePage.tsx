import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { get } from '../api/client'
import type { ApplicationSummary } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { DecisionBadge, ErrorBox, FileTypeBadge, FraudBadge, money, pct } from '../components/ui'

const FILTERS = ['ALL', 'REFERRED', 'DECIDED', 'OVERRIDDEN'] as const

export default function QueuePage() {
  const nav = useNavigate()
  const { session } = useAuth()
  const staff = session?.role !== 'APPLICANT'
  const [rows, setRows] = useState<ApplicationSummary[]>([])
  const [filter, setFilter] = useState<(typeof FILTERS)[number]>(staff ? 'REFERRED' : 'ALL')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const q = filter === 'ALL' ? '' : `?status=${filter}`
    get<ApplicationSummary[]>(`/api/applications${q}`).then(setRows).catch((e) => setError(e.message))
  }, [filter])

  return (
    <div className="stack" style={{ gap: 16 }}>
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <h1>{staff ? 'Underwriting queue' : 'My applications'}</h1>
        <div className="row" style={{ gap: 6 }}>
          {FILTERS.map((f) => <button key={f} className={`btn sm ${filter === f ? 'primary' : ''}`} onClick={() => setFilter(f)}>{f === 'ALL' ? 'All' : f.toLowerCase()}</button>)}
        </div>
      </div>
      <ErrorBox error={error} />
      <div className="card table-wrap">
        <table>
          <thead><tr><th>Submitted</th><th>Applicant</th><th>File</th><th className="num">Requested</th><th>Decision</th><th>Gate</th><th className="num">Score</th><th className="num">PD</th><th>Status</th></tr></thead>
          <tbody>
            {rows.length === 0 && <tr><td colSpan={9} className="muted">Nothing here yet{filter !== 'ALL' ? ' for this filter' : ''}.</td></tr>}
            {rows.map((r) => (
              <tr key={r.id} className="clickable" onClick={() => nav(`/applications/${r.id}`)}>
                <td className="small">{new Date(r.createdAt).toLocaleString()}</td>
                <td>{r.applicant}{r.personaId ? <span className="tiny"> · {r.personaId}</span> : null}</td>
                <td><FileTypeBadge fileType={r.fileType} /></td>
                <td className="num">{money(r.requestedAmount)}</td>
                <td><DecisionBadge decision={r.decision} /></td>
                <td><FraudBadge outcome={r.fraudOutcome} /></td>
                <td className="num">{r.score ?? '—'}</td>
                <td className="num">{pct(r.pd)}</td>
                <td className="small">{r.status.toLowerCase()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
