import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { get, post } from '../api/client'
import type { ApplicationDetail, Persona } from '../api/types'
import { ErrorBox, money } from '../components/ui'

type FileType = 'thick' | 'thin' | 'ntc'

interface FormState {
  fullName: string; email: string; phone: string; nationalId: string; employmentType: string; statedAnnualIncome: string
  requestedAmount: string; purpose: string; consentBankData: boolean; consentAltData: boolean
  fileType: FileType; bureauScore: string; monthsOnFile: string; tradelines: string; inquiries6m: string; delinquencies24m: string
}

const EMPTY: FormState = {
  fullName: '', email: '', phone: '', nationalId: '', employmentType: 'FULL_TIME', statedAnnualIncome: '', requestedAmount: '2000', purpose: '',
  consentBankData: true, consentAltData: true, fileType: 'ntc', bureauScore: '', monthsOnFile: '', tradelines: '', inquiries6m: '', delinquencies24m: '',
}

/** Stable per-browser identifier so device velocity is computed from what this system really sees. */
function deviceId(): string {
  try {
    let id = localStorage.getItem('prism.device')
    if (!id) {
      id = 'dev-' + crypto.randomUUID().slice(0, 12)
      localStorage.setItem('prism.device', id)
    }
    return id
  } catch {
    return 'dev-ephemeral'
  }
}

export default function ApplyPage() {
  const nav = useNavigate()
  const [form, setForm] = useState<FormState>(EMPTY)
  const [personas, setPersonas] = useState<Persona[]>([])
  const [persona, setPersona] = useState<Persona | null>(null)
  const [useSimulatedSignals, setUseSimulatedSignals] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // ---- live behavioural capture (what a real form would send to the fraud gate)
  const startedAt = useRef(Date.now())
  const [pasteSsn, setPasteSsn] = useState(false)
  const [pasteIncome, setPasteIncome] = useState(false)
  const [incomeEdits, setIncomeEdits] = useState(0)
  const lastIncome = useRef('')
  const [elapsed, setElapsed] = useState(0)

  useEffect(() => {
    const t = setInterval(() => setElapsed(Math.round((Date.now() - startedAt.current) / 1000)), 1000)
    return () => clearInterval(t)
  }, [])

  useEffect(() => {
    get<Persona[]>('/api/demo/personas').then(setPersonas).catch((e) => setError(e.message))
  }, [])

  function loadPersona(p: Persona) {
    setPersona(p)
    setUseSimulatedSignals(true)
    setForm({
      fullName: p.form.fullName, email: p.form.email, phone: p.form.phone, nationalId: '', employmentType: p.form.employmentType,
      statedAnnualIncome: String(p.form.statedAnnualIncome), requestedAmount: String(p.form.requestedAmount), purpose: p.form.loanPurpose,
      consentBankData: p.form.consentBankData, consentAltData: p.form.consentAltData, fileType: p.bureau.fileType,
      bureauScore: p.bureau.bureauScore == null ? '' : String(p.bureau.bureauScore), monthsOnFile: p.bureau.monthsOnFile == null ? '' : String(p.bureau.monthsOnFile),
      tradelines: p.bureau.tradelines == null ? '' : String(p.bureau.tradelines), inquiries6m: p.bureau.inquiries6m == null ? '' : String(p.bureau.inquiries6m),
      delinquencies24m: p.bureau.delinquencies24m == null ? '' : String(p.bureau.delinquencies24m),
    })
  }

  const transactions = useMemo(() => (persona && form.consentBankData ? persona.transactions : []), [persona, form.consentBankData])
  const set = (k: keyof FormState) => (e: { target: { value: string } }) => setForm((f) => ({ ...f, [k]: e.target.value }))
  const num = (s: string) => (s.trim() === '' ? null : Number(s))

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    const live = { sessionSeconds: Math.round((Date.now() - startedAt.current) / 1000), incomeFieldEdits: incomeEdits, pasteSsn, pasteIncome, emailAgeDays: null, voipPhone: null, deviceId: deviceId(), deviceAppsLast30dSeed: 0 }
    // Persona device IDs get a per-submission suffix so repeated demos do not accumulate real velocity;
    // the device-ring persona still blocks through its seeded prior applications.
    const behaviour = persona && useSimulatedSignals
      ? { ...persona.behaviour, deviceId: `${persona.behaviour.deviceId}-${Math.random().toString(36).slice(2, 8)}`, deviceAppsLast30dSeed: persona.behaviour.deviceAppsLast30dSeed ?? 0 }
      : live
    const body = {
      applicant: { fullName: form.fullName, email: form.email, phone: form.phone, nationalId: form.nationalId, employmentType: form.employmentType, statedAnnualIncome: num(form.statedAnnualIncome) },
      loan: { requestedAmount: num(form.requestedAmount), purpose: form.purpose },
      consent: { bankData: form.consentBankData, altData: form.consentAltData },
      bureau: form.fileType === 'ntc' ? { fileType: 'ntc' } : { fileType: form.fileType, bureauScore: num(form.bureauScore), monthsOnFile: num(form.monthsOnFile), tradelines: num(form.tradelines), inquiries6m: num(form.inquiries6m), delinquencies24m: num(form.delinquencies24m) },
      behaviour,
      transactions: transactions.map((t) => ({ date: t.date, description: t.description, amount: t.amount, balanceAfter: t.balance_after })),
      personaId: persona?.id ?? null,
    }
    try {
      const d = await post<ApplicationDetail>('/api/applications', body)
      nav(`/applications/${d.id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Submission failed')
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit} className="stack" style={{ gap: 16 }}>
      <div className="card">
        <h2>Start with a demo persona</h2>
        <p className="muted small">Six synthetic applicants, each with a three-month bank statement, that exercise every path of the engine. Nothing here is real customer data.</p>
        <div className="persona-list">
          {personas.map((p) => (
            <button type="button" key={p.id} className={`persona ${persona?.id === p.id ? 'active' : ''}`} onClick={() => loadPersona(p)}>
              <div className="name">{p.name}</div>
              <div className="story">{p.story}</div>
              <div className="tiny" style={{ marginTop: 6 }}>Expected: {p.expected}</div>
            </button>
          ))}
        </div>
      </div>

      <div className="grid two">
        <div className="card stack">
          <h2>Applicant</h2>
          <div className="form-grid">
            <label className="field">Full name<input value={form.fullName} onChange={set('fullName')} required maxLength={120} /></label>
            <label className="field">Email<input type="email" value={form.email} onChange={set('email')} required maxLength={160} /></label>
            <label className="field">Phone<input value={form.phone} onChange={set('phone')} maxLength={40} /></label>
            <label className="field">National ID (SSN) <span className="tiny">stored only as a keyed hash</span>
              <input value={form.nationalId} onChange={set('nationalId')} onPaste={() => setPasteSsn(true)} placeholder="123-45-6789" pattern="^$|\d{3}-\d{2}-\d{4}|\d{9}|\d{12}" />
            </label>
            <label className="field">Employment
              <select value={form.employmentType} onChange={set('employmentType')}>
                {['FULL_TIME', 'PART_TIME', 'SELF_EMPLOYED', 'STUDENT', 'UNEMPLOYED', 'RETIRED'].map((t) => <option key={t}>{t}</option>)}
              </select>
            </label>
            <label className="field">Stated annual income (USD)
              <input type="number" min={0} max={10000000} value={form.statedAnnualIncome} onChange={set('statedAnnualIncome')} onPaste={() => setPasteIncome(true)}
                onBlur={(e) => { if (lastIncome.current !== '' && lastIncome.current !== e.target.value) setIncomeEdits((n) => n + 1); lastIncome.current = e.target.value }} required />
            </label>
            <label className="field">Amount requested (USD 300 - 20,000)<input type="number" min={300} max={20000} value={form.requestedAmount} onChange={set('requestedAmount')} required /></label>
            <label className="field">Purpose<input value={form.purpose} onChange={set('purpose')} maxLength={300} /></label>
          </div>
          <h3>Consent</h3>
          <label className="check"><input type="checkbox" checked={form.consentBankData} onChange={(e) => setForm((f) => ({ ...f, consentBankData: e.target.checked }))} />
            <span>I consent to Prism reading the linked bank account's transactions for the last 3–12 months to assess affordability. Without this, a no-file application is referred rather than declined.</span></label>
          <label className="check"><input type="checkbox" checked={form.consentAltData} onChange={(e) => setForm((f) => ({ ...f, consentAltData: e.target.checked }))} />
            <span>I consent to the use of alternative data (rent, utility and phone payment regularity) derived from that account.</span></label>
        </div>

        <div className="card stack">
          <h2>Bureau pull <span className="tiny">(simulated - fetched server-side in production)</span></h2>
          <div className="form-grid">
            <label className="field">File type
              <select value={form.fileType} onChange={set('fileType')}>
                <option value="ntc">New-to-credit (no file)</option>
                <option value="thin">Thin file</option>
                <option value="thick">Thick file</option>
              </select>
            </label>
            {form.fileType !== 'ntc' && (
              <>
                <label className="field">Bureau score<input type="number" min={300} max={850} value={form.bureauScore} onChange={set('bureauScore')} placeholder="blank = no score" /></label>
                <label className="field">Months on file<input type="number" min={0} max={600} value={form.monthsOnFile} onChange={set('monthsOnFile')} /></label>
                <label className="field">Tradelines<input type="number" min={0} max={100} value={form.tradelines} onChange={set('tradelines')} /></label>
                <label className="field">Inquiries (6m)<input type="number" min={0} max={100} value={form.inquiries6m} onChange={set('inquiries6m')} /></label>
                <label className="field">Delinquencies (24m)<input type="number" min={0} max={100} value={form.delinquencies24m} onChange={set('delinquencies24m')} /></label>
              </>
            )}
          </div>

          <h2 style={{ marginTop: 8 }}>Linked account <span className="tiny">(simulated open-banking feed)</span></h2>
          {transactions.length === 0 ? (
            <p className="muted small">{form.consentBankData ? 'No account linked - pick a persona to attach a statement.' : 'Consent withheld - no bank data will be sent or processed.'}</p>
          ) : (
            <details>
              <summary>{transactions.length} transactions over 3 months · closing balance {money(transactions[transactions.length - 1].balance_after)}</summary>
              <div className="table-wrap" style={{ maxHeight: 220, overflow: 'auto', marginTop: 8 }}>
                <table><thead><tr><th>Date</th><th>Description</th><th className="num">Amount</th></tr></thead>
                  <tbody>{transactions.map((t, i) => <tr key={i}><td>{t.date}</td><td className="mono">{t.description}</td><td className="num">{money(t.amount, 2)}</td></tr>)}</tbody></table>
              </div>
            </details>
          )}

          <h2 style={{ marginTop: 8 }}>Behavioural signals</h2>
          <p className="tiny">Captured live while you fill the form. They feed the fraud/verification gate only - never the price.</p>
          <div>
            <span className="signal">session {elapsed}s</span>
            <span className={`signal ${pasteSsn ? 'on' : ''}`}>ID pasted: {pasteSsn ? 'yes' : 'no'}</span>
            <span className={`signal ${pasteIncome ? 'on' : ''}`}>income pasted: {pasteIncome ? 'yes' : 'no'}</span>
            <span className={`signal ${incomeEdits >= 3 ? 'on' : ''}`}>income edits: {incomeEdits}</span>
            <span className="signal mono">device {deviceId()}</span>
          </div>
          {persona && (
            <label className="check"><input type="checkbox" checked={useSimulatedSignals} onChange={(e) => setUseSimulatedSignals(e.target.checked)} />
              <span>Use the persona's simulated signals instead of mine (session {persona.behaviour.sessionSeconds}s, ID pasted {persona.behaviour.pasteSsn ? 'yes' : 'no'}, email age {persona.behaviour.emailAgeDays}d, VoIP {persona.behaviour.voipPhone ? 'yes' : 'no'}, device apps {persona.behaviour.deviceAppsLast30dSeed ?? 0}) so the story reproduces.</span></label>
          )}
        </div>
      </div>

      <ErrorBox error={error} />
      <div className="row">
        <button className="btn primary" disabled={busy}>{busy ? 'Scoring…' : 'Submit application'}</button>
        <span className="tiny">Decision, reasons and notice are generated in real time on submit.</span>
      </div>
    </form>
  )
}
