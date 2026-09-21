import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { get, post } from '../api/client'
import type { ApplicationDetail, CopilotAnswer, UnderwriterSummary } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { ContributionBars, DecisionBadge, ErrorBox, FileTypeBadge, FraudBadge, ProviderTag, ScoreGauge, money, num, pct, providerLabel } from '../components/ui'

export default function DecisionPage() {
  const { id } = useParams()
  const { session } = useAuth()
  const staff = session?.role === 'UNDERWRITER' || session?.role === 'ADMIN'
  const [d, setD] = useState<ApplicationDetail | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (id) get<ApplicationDetail>(`/api/applications/${id}`).then(setD).catch((e) => setError(e.message))
  }, [id])

  if (error) return <ErrorBox error={error} />
  if (!d) return <p className="muted">Loading…</p>
  const dec = d.decision
  const basisText: Record<string, string> = {
    CREDIT_POLICY: 'scorecard PD against the calibrated policy bands',
    FRAUD_BLOCK: 'identity / verification gate blocked the application before pricing',
    VERIFICATION_REQUIRED: 'verification gate requires evidence before a credit decision',
    INSUFFICIENT_EVIDENCE: 'no bureau file and no linked bank data - referred, not declined',
  }

  return (
    <div className="stack" style={{ gap: 16 }}>
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <div>
          <h1>{d.applicant.fullName} <span className="muted" style={{ fontWeight: 400 }}>· {money(d.loan.requestedAmount)} {d.loan.purpose ? `· ${d.loan.purpose}` : ''}</span></h1>
          <div className="row" style={{ gap: 8 }}>
            <FileTypeBadge fileType={d.fileType} />
            <span className={`badge ${d.bankLinked ? 'info' : 'neutral'}`}>{d.bankLinked ? 'bank linked' : 'no bank data'}</span>
            <span className="badge neutral">{d.status.toLowerCase()}</span>
            <span className="tiny">{new Date(d.createdAt).toLocaleString()} · {d.id.slice(0, 8)}</span>
          </div>
        </div>
        <Link to="/applications" className="btn sm">← {staff ? 'Queue' : 'My applications'}</Link>
      </div>

      {dec && (
        <div className="card">
          <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
            <div className="stack">
              <div className="row"><h2 style={{ margin: 0 }}>Decision</h2><DecisionBadge decision={dec.decision} />{staff && dec.fraud && <FraudBadge outcome={dec.fraud.outcome} />}</div>
              <p className="muted small">{staff && dec.basis ? `Basis: ${basisText[dec.basis] ?? dec.basis}. ` : ''}Scored in {dec.scoringLatencyMs} ms by {dec.modelId} v{dec.modelVersion}.</p>
              {staff && dec.policyNotes.map((n, i) => <p key={i} className="small">{n}</p>)}
              {staff && dec.verificationItems.length > 0 && (
                <div className="alert warn"><strong>Before a decision can be finalised:</strong><ul style={{ margin: '4px 0 0 18px' }}>{dec.verificationItems.map((v, i) => <li key={i}>{v}</li>)}</ul></div>
              )}
            </div>
            <div className="row">
              <ScoreGauge score={dec.score} pd={dec.pd} />
              {dec.decision === 'APPROVE' && (
                <>
                  <div className="kpi"><span className="label">Credit line</span><span className="value">{money(dec.creditLimit)}</span><span className="sub">of {money(d.loan.requestedAmount)} requested</span></div>
                  <div className="kpi"><span className="label">APR</span><span className="value">{dec.apr?.toFixed(2)}%</span><span className="sub">score tier</span></div>
                </>
              )}
            </div>
          </div>
        </div>
      )}

      <div className="grid two">
        {dec && (
          <div className="card stack">
            <h2>Why - principal reasons</h2>
            <p className="tiny">Exact, additive contributions from the scorecard. Adverse-action reasons are the largest risk-increasing factors, at most four (Reg B).</p>
            {dec.reasonCodes.length === 0 ? <p className="muted small">No risk-increasing factors above the noise floor.</p> : (
              <ol style={{ margin: '0 0 8px 18px', padding: 0 }}>
                {dec.reasonCodes.map((r) => <li key={r.code}><strong>{r.code}</strong> {r.text} <span className="tiny">(+{r.contribution.toFixed(2)})</span></li>)}
              </ol>
            )}
            <ContributionBars contributions={dec.contributions} />
          </div>
        )}

        {dec && (
          <div className="card stack">
            <h2>Applicant notice</h2>
            <ProviderTag provider={dec.noticeProvider} validated={dec.noticeValidated} fallback={dec.noticeFallbackUsed} />
            {dec.notice ? (
              <div className="notice">
                <p>{dec.notice.summary}</p>
                {dec.notice.principal_reasons.length > 0 && (
                  <>
                    <strong className="small">Principal reasons</strong>
                    <ol>{dec.notice.principal_reasons.map((r) => <li key={r.code}><span className="tiny">{r.code}</span> {r.explanation}</li>)}</ol>
                  </>
                )}
                {dec.notice.improvement_tips.length > 0 && (
                  <>
                    <strong className="small">What can help</strong>
                    <ul style={{ margin: '4px 0 6px 18px' }}>{dec.notice.improvement_tips.map((t, i) => <li key={i} className="small">{t}</li>)}</ul>
                  </>
                )}
                <p className="tiny">{dec.notice.disclaimer}</p>
              </div>
            ) : <p className="muted">No notice generated.</p>}
            <p className="tiny">The language model only phrases the decision; it cannot change it. Every reason above was checked against the model's actual reason codes before display.</p>
          </div>
        )}
      </div>

      {staff && dec && dec.fraud && d.behaviour && (
        <div className="card">
          <h2>Fraud &amp; verification gate</h2>
          <div className="row">
            <div className="kpi"><span className="label">Outcome</span><span className="value">{dec.fraud.outcome}</span><span className="sub">{dec.fraud.points} points · rules v{dec.fraud.rulesVersion}</span></div>
            <div className="kpi"><span className="label">Session</span><span className="value">{d.behaviour.sessionSeconds ?? '—'}s</span></div>
            <div className="kpi"><span className="label">Device apps (30d)</span><span className="value">{d.behaviour.deviceApps30d ?? '—'}</span></div>
            <div className="kpi"><span className="label">Income edits</span><span className="value">{d.behaviour.incomeFieldEdits ?? '—'}</span></div>
            <div className="kpi"><span className="label">ID pasted</span><span className="value">{d.behaviour.pasteSsn == null ? '—' : d.behaviour.pasteSsn ? 'yes' : 'no'}</span></div>
          </div>
          {dec.fraud.firedRules.length > 0 ? (
            <ul style={{ margin: '10px 0 0 18px' }}>{dec.fraud.firedRules.map((r) => <li key={r.id}><strong>{r.id}</strong> {r.name} <span className="tiny">+{r.points} · {r.signal}</span></li>)}</ul>
          ) : <p className="muted small" style={{ marginTop: 8 }}>No rules fired. Behavioural signals never affect price or limit.</p>}
        </div>
      )}

      {d.cashflow && (
        <div className="card stack">
          <h2>Cash-flow evidence <span className="tiny">categorised by semantic similarity (pgvector) from raw statement lines</span></h2>
          <div className="row">
            <div className="kpi"><span className="label">Verified income</span><span className="value">{money(d.cashflow.features.monthlyIncome as number)}/mo</span><span className="sub">{String(d.cashflow.features.monthsObserved)} months observed</span></div>
            <div className="kpi"><span className="label">Income volatility</span><span className="value">{num(d.cashflow.features.incomeCv as number)}</span><span className="sub">coefficient of variation</span></div>
            <div className="kpi"><span className="label">Balance trend</span><span className="value">{num(d.cashflow.features.balanceTrend as number)}</span><span className="sub">per month, ÷ income</span></div>
            <div className="kpi"><span className="label">Overdrafts</span><span className="value">{String(d.cashflow.features.nsfCount)}</span></div>
            <div className="kpi"><span className="label">Rent / utility / phone</span><span className="value">{pct(d.cashflow.features.rentOntimeRatio as number, 0)} / {pct(d.cashflow.features.utilityOntimeRatio as number, 0)} / {pct(d.cashflow.features.telcoOntimeRatio as number, 0)}</span><span className="sub">months paid</span></div>
            <div className="kpi"><span className="label">Obligations</span><span className="value">{pct(d.cashflow.features.obligationRatio as number, 0)}</span><span className="sub">of income</span></div>
            <div className="kpi"><span className="label">Stated ÷ verified income</span><span className="value">{num(d.cashflow.features.incomeInflationRatio as number)}</span></div>
          </div>
          <details>
            <summary>{d.cashflow.transactions.length} categorised transactions</summary>
            <div className="table-wrap" style={{ maxHeight: 320, overflow: 'auto', marginTop: 8 }}>
              <table><thead><tr><th>Date</th><th>Description</th><th>Category</th><th className="num">Match</th><th className="num">Amount</th><th className="num">Balance</th></tr></thead>
                <tbody>{d.cashflow.transactions.map((t, i) => (
                  <tr key={i}><td>{t.date}</td><td className="mono">{t.description}</td><td><span className="txn-cat">{t.category}</span></td><td className="num tiny">{t.confidence == null ? '' : pct(t.confidence, 0)}</td><td className="num">{money(t.amount, 2)}</td><td className="num">{money(t.balanceAfter, 2)}</td></tr>
                ))}</tbody></table>
            </div>
          </details>
        </div>
      )}

      {staff && d.similar && d.similar.size > 0 && (
        <div className="card stack">
          <h2>Applicants like this one <span className="tiny">advisory only - never an input to the score</span></h2>
          <div className="row">
            <div className="kpi"><span className="label">Cohort</span><span className="value">{d.similar.size}</span><span className="sub">nearest historical applicants</span></div>
            <div className="kpi"><span className="label">Approved</span><span className="value">{pct(d.similar.approvalRate, 0)}</span></div>
            <div className="kpi"><span className="label">Defaulted (12m)</span><span className="value">{pct(d.similar.defaultRate)}</span></div>
          </div>
          <div className="table-wrap"><table><thead><tr><th>Profile</th><th>File</th><th className="num">Score</th><th className="num">PD</th><th>Decision</th><th>Outcome</th><th className="num">Similarity</th></tr></thead>
            <tbody>{d.similar.top.map((n) => (
              <tr key={n.id}><td className="small">{n.profileText}</td><td>{n.fileType}</td><td className="num">{n.score}</td><td className="num">{pct(n.pd)}</td><td><DecisionBadge decision={n.decision} /></td><td>{n.defaulted ? <span className="badge decline">defaulted</span> : <span className="badge approve">repaid</span>}</td><td className="num">{pct(n.similarity, 0)}</td></tr>
            ))}</tbody></table></div>
        </div>
      )}

      {staff && dec && <UnderwriterTools d={d} onChange={setD} />}
    </div>
  )
}

function UnderwriterTools({ d, onChange }: { d: ApplicationDetail; onChange: (d: ApplicationDetail) => void }) {
  const [summary, setSummary] = useState<UnderwriterSummary | null>(d.decision?.summary ?? null)
  const [summaryBusy, setSummaryBusy] = useState(false)
  const [action, setAction] = useState('CONFIRM')
  const [reason, setReason] = useState('')
  const [actionBusy, setActionBusy] = useState(false)
  const [question, setQuestion] = useState('')
  const [chat, setChat] = useState<{ q: string; a: CopilotAnswer }[]>([])
  const [chatBusy, setChatBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const chatEnd = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    chatEnd.current?.scrollIntoView({ block: 'end' })
  }, [chat])

  async function loadSummary() {
    setSummaryBusy(true)
    setError(null)
    try {
      setSummary(await get<UnderwriterSummary>(`/api/applications/${d.id}/summary`))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'failed')
    } finally {
      setSummaryBusy(false)
    }
  }

  async function submitAction(e: FormEvent) {
    e.preventDefault()
    setActionBusy(true)
    setError(null)
    try {
      onChange(await post<ApplicationDetail>(`/api/applications/${d.id}/actions`, { action, reason }))
      setReason('')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'failed')
    } finally {
      setActionBusy(false)
    }
  }

  async function ask(e: FormEvent) {
    e.preventDefault()
    const q = question.trim()
    if (!q) return
    setChatBusy(true)
    setError(null)
    try {
      const a = await post<CopilotAnswer>('/api/copilot/ask', { applicationId: d.id, question: q })
      setChat((c) => [...c, { q, a }])
      setQuestion('')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'failed')
    } finally {
      setChatBusy(false)
    }
  }

  return (
    <div className="grid two">
      <div className="card stack">
        <h2>Underwriter summary</h2>
        {summary ? (
          <div className="notice">
            <p><strong>{summary.headline}</strong> <span className="badge neutral">recommends {summary.recommendation}</span></p>
            {summary.strengths.length > 0 && <><strong className="small">Strengths</strong><ul style={{ margin: '4px 0 6px 18px' }}>{summary.strengths.map((s, i) => <li key={i} className="small">{s}</li>)}</ul></>}
            {summary.risks.length > 0 && <><strong className="small">Risks</strong><ul style={{ margin: '4px 0 6px 18px' }}>{summary.risks.map((s, i) => <li key={i} className="small">{s}</li>)}</ul></>}
            {summary.verification_items.length > 0 && <><strong className="small">Verification</strong><ul style={{ margin: '4px 0 6px 18px' }}>{summary.verification_items.map((s, i) => <li key={i} className="small">{s}</li>)}</ul></>}
            <p className="tiny">cites {summary.cited_factors.join(', ') || 'no factors'}</p>
          </div>
        ) : (
          <div className="row"><button type="button" className="btn" onClick={loadSummary} disabled={summaryBusy}>{summaryBusy ? 'Generating…' : 'Generate AI summary'}</button><span className="tiny">Generated on demand and cached - model spend only when a human opens the case.</span></div>
        )}

        <h2 style={{ marginTop: 8 }}>Record action</h2>
        <form onSubmit={submitAction} className="stack">
          <div className="row">
            <select value={action} onChange={(e) => setAction(e.target.value)} className="btn">
              <option value="CONFIRM">Confirm engine decision</option>
              <option value="REQUEST_DOCS">Request documents</option>
              <option value="APPROVE_OVERRIDE">Override: approve</option>
              <option value="DECLINE_OVERRIDE">Override: decline</option>
            </select>
          </div>
          <label className="field">Reason (min 20 characters, logged and reviewed weekly)<textarea value={reason} onChange={(e) => setReason(e.target.value)} minLength={20} maxLength={600} rows={2} required /></label>
          <button className="btn primary" disabled={actionBusy}>{actionBusy ? 'Saving…' : 'Save action'}</button>
        </form>
        {d.actions.length > 0 && (
          <ul style={{ margin: '4px 0 0 18px' }}>{d.actions.map((a, i) => <li key={i} className="small"><strong>{a.action}</strong> by {a.username} · {new Date(a.createdAt).toLocaleString()} - {a.reason}</li>)}</ul>
        )}
        <ErrorBox error={error} />
      </div>

      <div className="card stack">
        <h2>Underwriting copilot</h2>
        <p className="tiny">Answers only from this application's facts and the internal policy documents (retrieved by vector search); every policy statement is cited. It does not make decisions.</p>
        <div className="chat">
          {chat.map((m, i) => (
            <div key={i} className="stack" style={{ gap: 4 }}>
              <div className="msg q">{m.q}</div>
              <div className="msg a">
                {m.a.answer.answer}
                <span className="cite">
                  {m.a.answer.citations.map((c) => `${c.doc} › ${c.section}`).join(' · ') || 'no citations'} · confidence {m.a.answer.confidence} · {providerLabel(m.a.provider).name}{m.a.model ? ` (${m.a.model})` : ''}{m.a.fallbackUsed ? ' · fallback' : ''} · {m.a.latencyMs} ms
                </span>
              </div>
            </div>
          ))}
          <div ref={chatEnd} />
        </div>
        <form onSubmit={ask} className="row">
          <input style={{ flex: 1, padding: '8px 10px', border: '1px solid var(--border)', borderRadius: 8, background: 'var(--surface-1)' }} value={question} onChange={(e) => setQuestion(e.target.value)} placeholder="e.g. Why was this referred, and what does policy say about no-file applicants?" maxLength={600} />
          <button className="btn" disabled={chatBusy}>{chatBusy ? '…' : 'Ask'}</button>
        </form>
      </div>
    </div>
  )
}
