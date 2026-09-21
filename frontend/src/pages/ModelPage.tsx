import { useEffect, useState } from 'react'
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { get } from '../api/client'
import { ErrorBox, pct } from '../components/ui'

// Fixed series colours (data-viz reference palette): Prism = blue, bureau-only = orange, GBM = aqua.
const C = { prism: '#2a78d6', base: '#eb6834', gbm: '#1baf7a', bureau: '#eb6834', cashflow: '#2a78d6', application: '#1baf7a' }

type Json = Record<string, any>

export default function ModelPage() {
  const [card, setCard] = useState<Json | null>(null)
  const [scorecard, setScorecard] = useState<Json | null>(null)
  const [monitoring, setMonitoring] = useState<Json | null>(null)
  const [aiConfig, setAiConfig] = useState<Json | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    Promise.all([get<Json>('/api/model/card'), get<Json>('/api/model/scorecard'), get<Json>('/api/monitoring/summary'), get<Json>('/api/model/ai-config')])
      .then(([c, s, m, a]) => { setCard(c); setScorecard(s); setMonitoring(m); setAiConfig(a) })
      .catch((e) => setError(e.message))
  }, [])

  if (error) return <ErrorBox error={error} />
  if (!card || !scorecard || !monitoring) return <p className="muted">Loading…</p>

  const perf = card.performance
  const policy = card.policy_simulation
  const segs = [['thick', 'Thick file'], ['thin', 'Thin file'], ['ntc', 'New-to-credit']] as const
  const lift = segs.map(([k, label]) => ({
    segment: label,
    'Bureau-only': +(policy.models.bureau_only.by_file_type[k].approval_rate * 100).toFixed(1),
    'Prism scorecard': +(policy.models.prism_scorecard.by_file_type[k].approval_rate * 100).toFixed(1),
    'GBM benchmark': +(policy.models.gbm_benchmark.by_file_type[k].approval_rate * 100).toFixed(1),
  }))
  const iv = [...scorecard.features].sort((a: Json, b: Json) => b.informationValue - a.informationValue).map((f: Json) => ({ label: f.label, iv: +f.informationValue.toFixed(3), modality: f.modality }))
  const fairness = card.fairness.attributes
  const bands = Object.entries(monitoring.scoreBands as Record<string, number>).map(([band, n]) => ({ band, n }))

  return (
    <div className="stack" style={{ gap: 16 }}>
      <div>
        <h1>{card.model_name} <span className="badge neutral">v{card.version}</span></h1>
        <p className="muted small">{card.intended_use}</p>
      </div>

      <div className="grid three">
        <div className="card">
          <h3>Discrimination (hold-out AUC)</h3>
          <table><thead><tr><th>Segment</th><th className="num">Bureau-only</th><th className="num">Prism</th><th className="num">GBM</th></tr></thead>
            <tbody>{[['overall', 'All'], ...segs].map(([k, label]) => (
              <tr key={k}><td>{label}</td><td className="num">{perf.bureau_only[k].auc ?? 'n/a'}</td><td className="num"><strong>{perf.prism_scorecard[k].auc}</strong></td><td className="num">{perf.gbm_benchmark[k].auc}</td></tr>
            ))}</tbody></table>
          <p className="tiny">Bureau-only cannot score no-file applicants at all. The interpretable scorecard matches the GBM ceiling on this data.</p>
        </div>
        <div className="card">
          <h3>Policy simulation</h3>
          <div className="row">
            <div className="kpi"><span className="label">Target bad rate</span><span className="value">{pct(policy.target_portfolio_bad_rate, 0)}</span><span className="sub">approved book, 12 months</span></div>
            <div className="kpi"><span className="label">Approval (bureau-only)</span><span className="value">{pct(policy.models.bureau_only.approval_rate_overall, 1)}</span></div>
            <div className="kpi"><span className="label">Approval (Prism)</span><span className="value">{pct(policy.models.prism_scorecard.approval_rate_overall, 1)}</span></div>
            <div className="kpi"><span className="label">Swap-in set</span><span className="value">{pct(policy.swap_in_set.share_of_applicants, 1)}</span><span className="sub">bad rate {pct(policy.swap_in_set.realised_bad_rate, 1)} · {pct(policy.swap_in_set.share_ntc, 0)} NTC</span></div>
          </div>
        </div>
        <div className="card">
          <h3>Decision bands (calibrated)</h3>
          <p className="small">Approve if PD ≤ <strong>{pct(scorecard.decisionPolicy.approveMaxPd)}</strong>; refer if ≤ <strong>{pct(scorecard.decisionPolicy.referMaxPd)}</strong>; decline above.</p>
          <p className="tiny">{scorecard.decisionPolicy.notes}</p>
          <p className="tiny">{scorecard.decisionPolicy.minEvidence}</p>
        </div>
      </div>

      <div className="grid two">
        <div className="card">
          <h2>Approval rate at the same portfolio bad rate</h2>
          <ResponsiveContainer width="100%" height={260}>
            <BarChart data={lift} margin={{ top: 8, right: 8, left: -12, bottom: 0 }}>
              <CartesianGrid stroke="var(--border)" vertical={false} />
              <XAxis dataKey="segment" tick={{ fill: 'var(--text-2)', fontSize: 12 }} axisLine={false} tickLine={false} />
              <YAxis unit="%" tick={{ fill: 'var(--text-2)', fontSize: 12 }} axisLine={false} tickLine={false} />
              <Tooltip contentStyle={{ background: 'var(--surface-1)', border: '1px solid var(--border)', borderRadius: 8 }} />
              <Legend wrapperStyle={{ fontSize: 12 }} />
              <Bar dataKey="Bureau-only" fill={C.base} radius={[4, 4, 0, 0]} />
              <Bar dataKey="Prism scorecard" fill={C.prism} radius={[4, 4, 0, 0]} />
              <Bar dataKey="GBM benchmark" fill={C.gbm} radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
        <div className="card">
          <h2>Information value by feature</h2>
          <div className="legend" style={{ marginBottom: 6 }}><span><i style={{ background: C.bureau }} />bureau</span><span><i style={{ background: C.cashflow }} />cash-flow</span><span><i style={{ background: C.application }} />application</span></div>
          <div className="bars">
            {iv.map((f: Json) => (
              <div className="bar-row" key={f.label} style={{ gridTemplateColumns: 'minmax(180px,1.4fr) 1fr 50px' }}>
                <span className="small">{f.label}</span>
                <div className="bar-track"><div className="bar-fill" style={{ left: 0, width: `${(f.iv / iv[0].iv) * 100}%`, background: C[f.modality as keyof typeof C] }} /></div>
                <span className="bar-val">{f.iv}</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="card">
        <h2>Fairness harness <span className="tiny">attributes below are used for testing only and are never model inputs</span></h2>
        <div className="grid three">
          {Object.entries(fairness).map(([attr, models]: [string, any]) => (
            <div key={attr}>
              <h3>{attr.replace('_', ' ')}</h3>
              <table><thead><tr><th>Group</th><th className="num">Bureau-only</th><th className="num">Prism</th></tr></thead>
                <tbody>
                  {Object.keys(models.prism_scorecard.approval_rate).map((g) => (
                    <tr key={g}><td>{g}</td><td className="num">{pct(models.bureau_only.approval_rate[g], 0)}</td><td className="num">{pct(models.prism_scorecard.approval_rate[g], 0)}</td></tr>
                  ))}
                  <tr><td><strong>Adverse impact ratio</strong></td>
                    <td className="num">{models.bureau_only.adverse_impact_ratio} {models.bureau_only.passes_four_fifths ? <span className="badge approve">pass</span> : <span className="badge decline">fail</span>}</td>
                    <td className="num">{models.prism_scorecard.adverse_impact_ratio} {models.prism_scorecard.passes_four_fifths ? <span className="badge approve">pass</span> : <span className="badge decline">fail</span>}</td></tr>
                  <tr><td>Equal-opportunity gap</td><td className="num">{pct(models.bureau_only.equal_opportunity_gap)}</td><td className="num">{pct(models.prism_scorecard.equal_opportunity_gap)}</td></tr>
                </tbody></table>
            </div>
          ))}
        </div>
        <p className="tiny">{card.fairness.note}</p>
      </div>

      <div className="grid two">
        <div className="card">
          <h2>Fraud gate (labelled synthetic fraud)</h2>
          <div className="row">
            <div className="kpi"><span className="label">Block precision</span><span className="value">{pct(card.fraud_gate.block.precision)}</span><span className="sub">recall {pct(card.fraud_gate.block.recall)}</span></div>
            <div className="kpi"><span className="label">Any flag recall</span><span className="value">{pct(card.fraud_gate.flagged_any.recall)}</span><span className="sub">precision {pct(card.fraud_gate.flagged_any.precision)}</span></div>
            <div className="kpi"><span className="label">Friction on legitimate</span><span className="value">{pct(card.fraud_gate.friction_on_legitimate.step_up_rate, 2)}</span><span className="sub">step-up · blocked {pct(card.fraud_gate.friction_on_legitimate.block_rate, 2)}</span></div>
          </div>
        </div>
        <div className="card">
          <h2>Live monitoring (this instance)</h2>
          <div className="row">
            <div className="kpi"><span className="label">Decisions</span><span className="value">{monitoring.decisions}</span><span className="sub">{Object.entries(monitoring.byDecision as Record<string, number>).map(([k, v]) => `${k.toLowerCase()} ${v}`).join(' · ') || '—'}</span></div>
            <div className="kpi"><span className="label">Mean scoring latency</span><span className="value">{monitoring.meanScoringLatencyMs == null ? '—' : `${Math.round(monitoring.meanScoringLatencyMs)} ms`}</span></div>
            <div className="kpi"><span className="label">Override rate</span><span className="value">{pct(monitoring.overrideRate, 1)}</span></div>
            <div className="kpi"><span className="label">AI provider</span><span className="value" style={{ fontSize: 15 }}>{aiConfig?.primaryProvider}</span><span className="sub">fallback {aiConfig?.fallbackProvider} · managed guardrail {aiConfig?.managedGuardrail ? 'on' : 'off'}</span></div>
          </div>
          {bands.length > 0 && (
            <ResponsiveContainer width="100%" height={160}>
              <BarChart data={bands} margin={{ top: 12, right: 8, left: -20, bottom: 0 }}>
                <CartesianGrid stroke="var(--border)" vertical={false} />
                <XAxis dataKey="band" tick={{ fill: 'var(--text-2)', fontSize: 11 }} axisLine={false} tickLine={false} />
                <YAxis allowDecimals={false} tick={{ fill: 'var(--text-2)', fontSize: 11 }} axisLine={false} tickLine={false} />
                <Tooltip contentStyle={{ background: 'var(--surface-1)', border: '1px solid var(--border)', borderRadius: 8 }} />
                <Bar dataKey="n" name="decisions" fill={C.prism} radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          )}
          {(monitoring.aiInvocations as Json[]).length > 0 && (
            <table style={{ marginTop: 8 }}><thead><tr><th>Provider</th><th>Task</th><th className="num">Calls</th><th className="num">Avg ms</th><th className="num">Valid</th><th className="num">Fallback</th></tr></thead>
              <tbody>{(monitoring.aiInvocations as Json[]).map((r, i) => <tr key={i}><td>{r.provider}</td><td className="small">{r.task}</td><td className="num">{r.calls}</td><td className="num">{Math.round(r.avgLatencyMs ?? 0)}</td><td className="num">{pct(r.validRate, 0)}</td><td className="num">{pct(r.fallbackRate, 0)}</td></tr>)}</tbody></table>
          )}
        </div>
      </div>

      <div className="card">
        <h2>Model card</h2>
        <div className="grid three">
          <div><h3>Training data</h3><p className="small">{card.training_data.source}. {card.training_data.rows_total.toLocaleString()} rows; {card.training_data.rows_credit_model.toLocaleString()} for the credit model after excluding labelled fraud; {pct(card.training_data.holdout_share, 0)} hold-out.</p></div>
          <div><h3>Not intended for</h3><ul style={{ margin: '0 0 0 18px' }}>{card.not_intended_for.map((s: string, i: number) => <li key={i} className="small">{s}</li>)}</ul></div>
          <div><h3>Limitations</h3><ul style={{ margin: '0 0 0 18px' }}>{card.limitations.map((s: string, i: number) => <li key={i} className="small">{s}</li>)}</ul></div>
        </div>
        <h3 style={{ marginTop: 12 }}>Monitoring plan</h3>
        <ul style={{ margin: '0 0 0 18px' }}>{card.monitoring.map((s: string, i: number) => <li key={i} className="small">{s}</li>)}</ul>
      </div>
    </div>
  )
}
