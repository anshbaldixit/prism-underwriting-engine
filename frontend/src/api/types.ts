// Mirrors the backend read models (ApplicationDetail, ApplicationSummary, ...). Kept hand-written and small
// so the UI has one place that documents what the API returns.

export type Role = 'APPLICANT' | 'UNDERWRITER' | 'ADMIN'

export interface Session {
  token: string
  username: string
  displayName: string
  role: Role
  expiresAt: string
}

export interface ReasonCode {
  code: string
  text: string
  feature: string
  contribution: number
}

export interface FeatureContribution {
  feature: string
  label: string
  modality: 'bureau' | 'cashflow' | 'application'
  reasonCode: string
  value: number | null
  missing: boolean
  woe: number
  contribution: number
}

export interface FraudView {
  outcome: 'PASS' | 'STEP_UP' | 'BLOCK'
  points: number
  firedRules: { id: string; name: string; signal: string; points: number }[]
  rulesVersion: string
}

export interface Notice {
  decision: string
  summary: string
  principal_reasons: { code: string; explanation: string }[]
  improvement_tips: string[]
  disclaimer: string
}

export interface UnderwriterSummary {
  headline: string
  strengths: string[]
  risks: string[]
  verification_items: string[]
  recommendation: string
  cited_factors: string[]
}

export interface DecisionView {
  id: string
  createdAt: string
  decision: 'APPROVE' | 'REFER' | 'DECLINE'
  basis: string
  score: number
  pd: number
  creditLimit: number | null
  apr: number | null
  reasonCodes: ReasonCode[]
  contributions: FeatureContribution[]
  verificationItems: string[]
  policyNotes: string[]
  fraud: FraudView
  notice: Notice | null
  noticeProvider: string | null
  noticeValidated: boolean | null
  noticeFallbackUsed: boolean | null
  summary: UnderwriterSummary | null
  summaryProvider: string | null
  modelId: string
  modelVersion: string
  scoringLatencyMs: number
}

export interface TransactionView {
  date: string
  description: string
  amount: number
  balanceAfter: number | null
  category: string
  confidence: number | null
}

export interface Neighbour {
  id: string
  fileType: string
  score: number
  pd: number
  decision: string
  defaulted: boolean
  reasonCodes: string[]
  profileText: string
  similarity: number
}

export interface Cohort {
  size: number
  defaultRate: number
  approvalRate: number
  top: Neighbour[]
}

export interface ApplicationDetail {
  id: string
  createdAt: string
  status: string
  personaId: string | null
  applicant: { fullName: string; email: string; phone: string | null; employmentType: string; statedAnnualIncome: number; nationalIdOnFile: boolean }
  loan: { requestedAmount: number; purpose: string | null }
  fileType: 'thick' | 'thin' | 'ntc'
  bankLinked: boolean
  bureau: { bureauScore: number | null; monthsOnFile: number | null; tradelines: number | null; inquiries6m: number | null; delinquencies24m: number | null }
  behaviour: { sessionSeconds: number | null; incomeFieldEdits: number | null; pasteSsn: boolean | null; pasteIncome: boolean | null; emailAgeDays: number | null; voipPhone: boolean | null; deviceId: string | null; deviceApps30d: number | null }
  decision: DecisionView | null
  cashflow: { features: Record<string, number | boolean | null>; transactions: TransactionView[]; spendByCategory: Record<string, number> } | null
  similar: Cohort | null
  actions: { createdAt: string; username: string; action: string; reason: string }[]
}

export interface ApplicationSummary {
  id: string
  createdAt: string
  status: string
  personaId: string | null
  applicant: string
  fileType: string
  bankLinked: boolean
  requestedAmount: number
  decision: string | null
  basis: string | null
  score: number | null
  pd: number | null
  fraudOutcome: string | null
}

export interface Persona {
  id: string
  name: string
  story: string
  expected: string
  form: { fullName: string; email: string; phone: string; employmentType: string; statedAnnualIncome: number; requestedAmount: number; loanPurpose: string; consentBankData: boolean; consentAltData: boolean }
  bureau: { fileType: 'thick' | 'thin' | 'ntc'; bureauScore?: number | null; monthsOnFile?: number; tradelines?: number; inquiries6m?: number; delinquencies24m?: number }
  behaviour: { sessionSeconds: number; incomeFieldEdits: number; pasteSsn: boolean; pasteIncome: boolean; emailAgeDays: number; voipPhone: boolean; deviceId: string; deviceAppsLast30dSeed?: number }
  transactions: { date: string; description: string; amount: number; balance_after: number }[]
}

export interface CopilotAnswer {
  answer: { answer: string; citations: { doc: string; section: string }[]; confidence: string }
  provider: string
  fallbackUsed: boolean
  retrieved: { doc: string; section: string; content: string; similarity: number }[]
  latencyMs: number
}
