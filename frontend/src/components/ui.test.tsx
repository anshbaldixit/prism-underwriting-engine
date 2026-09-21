import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { FeatureContribution } from '../api/types'
import { ContributionBars, DecisionBadge, FraudBadge, ProviderTag, ScoreGauge, money, pct, providerLabel } from './ui'

describe('formatting helpers', () => {
  it('formats money, percentages and missing values consistently', () => {
    expect(money(2500)).toBe('$2,500')
    expect(money(null)).toBe('—')
    expect(pct(0.4251)).toBe('42.5%')
    expect(pct(0.7, 0)).toBe('70%')
    expect(pct(undefined)).toBe('—')
  })
})

describe('provenance labels', () => {
  it('splits "provider · model" and maps known providers to readable names', () => {
    expect(providerLabel('groq · openai/gpt-oss-120b')).toEqual({ name: 'Groq', model: 'openai/gpt-oss-120b' })
    expect(providerLabel('bedrock · us.anthropic.claude-haiku-4-5-20251001-v1:0')).toEqual({ name: 'Amazon Bedrock', model: 'us.anthropic.claude-haiku-4-5-20251001-v1:0' })
    expect(providerLabel('offline-template')).toEqual({ name: 'Offline template', model: null })
    expect(providerLabel(null)).toEqual({ name: 'n/a', model: null })
  })

  it('renders validation state next to the provider', () => {
    render(<ProviderTag provider="groq · openai/gpt-oss-120b" validated={true} fallback={false} />)
    expect(screen.getByText('Groq')).toBeInTheDocument()
    expect(screen.getByText('validated against model factors')).toBeInTheDocument()
    expect(screen.queryByText('fallback used')).not.toBeInTheDocument()
  })

  it('flags a fallback and a failed validation', () => {
    render(<ProviderTag provider="offline-template" validated={false} fallback={true} />)
    expect(screen.getByText('validation failed')).toBeInTheDocument()
    expect(screen.getByText('fallback used')).toBeInTheDocument()
  })
})

describe('decision components', () => {
  it('badges carry the decision and gate outcome as text, not only colour', () => {
    render(<><DecisionBadge decision="DECLINE" /><FraudBadge outcome="STEP_UP" /></>)
    expect(screen.getByText('DECLINE')).toHaveClass('decline')
    expect(screen.getByText('Gate: step-up')).toHaveClass('refer')
  })

  it('score gauge exposes the score to assistive tech', () => {
    render(<ScoreGauge score={636} pd={0.009} />)
    expect(screen.getByRole('img', { name: 'Score 636' })).toBeInTheDocument()
    expect(screen.getByText('0.9%')).toBeInTheDocument()
  })

  it('contribution bars sort by magnitude and mark unobserved features', () => {
    const c = (feature: string, label: string, contribution: number, missing = false): FeatureContribution =>
      ({ feature, label, modality: 'cashflow', reasonCode: 'R00', value: missing ? null : 1, missing, woe: 0, contribution })
    render(<ContributionBars contributions={[c('a', 'Small', 0.05), c('b', 'Large adverse', 0.9), c('c', 'Big protective', -0.7), c('d', 'Not seen', 0.02, true)]} />)
    const rows = screen.getAllByText(/Small|Large adverse|Big protective|Not seen/).map((el) => el.textContent)
    expect(rows[0]).toContain('Large adverse')
    expect(rows[1]).toContain('Big protective')
    expect(screen.getByText(/not observed/)).toBeInTheDocument()
    expect(screen.getByText('+0.90')).toBeInTheDocument()
    expect(screen.getByText('-0.70')).toBeInTheDocument()
  })
})
