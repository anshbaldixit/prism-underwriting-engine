import type { FeatureContribution } from '../api/types'

export function DecisionBadge({ decision }: { decision: string | null | undefined }) {
  const cls = decision === 'APPROVE' ? 'approve' : decision === 'REFER' ? 'refer' : decision === 'DECLINE' ? 'decline' : 'neutral'
  return <span className={`badge ${cls}`}>{decision ?? '—'}</span>
}

export function FraudBadge({ outcome }: { outcome: string | null | undefined }) {
  const cls = outcome === 'PASS' ? 'approve' : outcome === 'STEP_UP' ? 'refer' : outcome === 'BLOCK' ? 'decline' : 'neutral'
  const label = outcome === 'PASS' ? 'Gate: pass' : outcome === 'STEP_UP' ? 'Gate: step-up' : outcome === 'BLOCK' ? 'Gate: block' : '—'
  return <span className={`badge ${cls}`}>{label}</span>
}

export function FileTypeBadge({ fileType }: { fileType: string }) {
  const label = fileType === 'ntc' ? 'New-to-credit' : fileType === 'thin' ? 'Thin file' : 'Thick file'
  return <span className="badge info">{label}</span>
}

export const money = (v: number | null | undefined, digits = 0) =>
  v == null ? '—' : v.toLocaleString(undefined, { style: 'currency', currency: 'USD', maximumFractionDigits: digits, minimumFractionDigits: digits })
export const pct = (v: number | null | undefined, digits = 1) => (v == null ? '—' : `${(v * 100).toFixed(digits)}%`)
export const num = (v: number | null | undefined, digits = 2) => (v == null ? '—' : v.toFixed(digits))

/** Semi-circular score gauge; 600 = 30:1 odds, 20 points doubles the odds (see scorecard scaling). */
export function ScoreGauge({ score, pd }: { score: number; pd: number }) {
  const min = 420, max = 740
  const t = Math.max(0, Math.min(1, (score - min) / (max - min)))
  const r = 54, cx = 70, cy = 70
  const angle = Math.PI * (1 - t)
  const x = cx + r * Math.cos(angle), y = cy - r * Math.sin(angle)
  const arc = (from: number, to: number) => {
    const a1 = Math.PI * (1 - from), a2 = Math.PI * (1 - to)
    const x1 = cx + r * Math.cos(a1), y1 = cy - r * Math.sin(a1)
    const x2 = cx + r * Math.cos(a2), y2 = cy - r * Math.sin(a2)
    return `M ${x1} ${y1} A ${r} ${r} 0 0 1 ${x2} ${y2}`
  }
  return (
    <div className="gauge">
      <svg width="140" height="82" viewBox="0 0 140 82" role="img" aria-label={`Score ${score}`}>
        <path d={arc(0, 1)} stroke="var(--surface-2)" strokeWidth="12" fill="none" strokeLinecap="round" />
        <path d={arc(0, t || 0.001)} stroke="var(--accent)" strokeWidth="12" fill="none" strokeLinecap="round" />
        <circle cx={x} cy={y} r="5" fill="var(--surface-1)" stroke="var(--accent)" strokeWidth="3" />
        <text x={cx} y={cy - 6} textAnchor="middle" fontSize="24" fontWeight="650" fill="var(--text)">{score}</text>
        <text x={cx} y={cy + 12} textAnchor="middle" fontSize="11" fill="var(--text-2)">score</text>
      </svg>
      <div className="stack" style={{ gap: 4 }}>
        <div className="kpi" style={{ minWidth: 120 }}>
          <span className="label">12-month PD</span>
          <span className="value">{pct(pd)}</span>
          <span className="sub">probability of serious delinquency</span>
        </div>
      </div>
    </div>
  )
}

/** Diverging bars of additive contributions: red pushes risk up, blue pulls it down. Always shows a legend. */
export function ContributionBars({ contributions, limit = 12 }: { contributions: FeatureContribution[]; limit?: number }) {
  const sorted = [...contributions].sort((a, b) => Math.abs(b.contribution) - Math.abs(a.contribution)).slice(0, limit)
  const maxAbs = Math.max(0.05, ...sorted.map((c) => Math.abs(c.contribution)))
  return (
    <div className="stack">
      <div className="legend">
        <span><i style={{ background: 'var(--risk-up)' }} />increases risk</span>
        <span><i style={{ background: 'var(--risk-down)' }} />reduces risk</span>
        <span className="tiny">points = log-odds contribution; sum + intercept = PD</span>
      </div>
      <div className="bars">
        {sorted.map((c) => {
          const w = (Math.abs(c.contribution) / maxAbs) * 50
          return (
            <div className="bar-row" key={c.feature} title={`${c.label}: value ${c.missing ? 'not observed' : c.value}, WoE ${c.woe.toFixed(3)}`}>
              <span>
                {c.label}
                <span className="tiny"> {c.missing ? '· not observed' : ''} · {c.modality}</span>
              </span>
              <div className="bar-track">
                <div className="mid" />
                <div className={`bar-fill ${c.contribution >= 0 ? 'up' : 'down'}`} style={{ width: `${w}%` }} />
              </div>
              <span className="bar-val">{c.contribution >= 0 ? '+' : ''}{c.contribution.toFixed(2)}</span>
            </div>
          )
        })}
      </div>
    </div>
  )
}

/** Human label for a provenance string of the form "provider · model". */
export function providerLabel(provider: string | null | undefined): { name: string; model: string | null } {
  if (!provider) return { name: 'n/a', model: null }
  const [p, ...rest] = provider.split(' · ')
  const model = rest.length ? rest.join(' · ') : null
  const name = p === 'bedrock' ? 'Amazon Bedrock' : p === 'anthropic' ? 'Anthropic API' : p === 'groq' ? 'Groq' : p === 'ollama' ? 'Ollama (local)'
    : p === 'openai-compatible' ? 'OpenAI-compatible endpoint' : p.startsWith('offline') ? 'Offline template' : p
  return { name, model }
}

export function ProviderTag({ provider, validated, fallback }: { provider: string | null; validated: boolean | null; fallback: boolean | null }) {
  const { name, model } = providerLabel(provider)
  return (
    <span className="row" style={{ gap: 6 }}>
      <span className="badge neutral">{name}{model ? <span className="tiny mono" style={{ fontWeight: 400 }}> {model}</span> : null}</span>
      {validated ? <span className="badge approve">validated against model factors</span> : <span className="badge decline">validation failed</span>}
      {fallback ? <span className="badge refer">fallback used</span> : null}
    </span>
  )
}

export function ErrorBox({ error }: { error: string | null }) {
  return error ? <div className="alert error">{error}</div> : null
}
