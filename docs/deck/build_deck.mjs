// Builds docs/Prism_Underwriting_Engine.pptx from the exported model card, figures and screenshots.
import { createRequire } from 'node:module'
import fs from 'node:fs'
const require = createRequire(import.meta.url)
const pptxgen = require('pptxgenjs')

import path from 'node:path'
import { fileURLToPath } from 'node:url'
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const card = JSON.parse(fs.readFileSync(`${ROOT}/backend/src/main/resources/model/model_card.json`, 'utf8'))
const scorecard = JSON.parse(fs.readFileSync(`${ROOT}/backend/src/main/resources/model/scorecard.json`, 'utf8'))
const FIG = `${ROOT}/docs/figures`
const SHOT = `${ROOT}/docs/screenshots/crops`
const REPO = 'github.com/anshbaldixit/prism-underwriting-engine'
const ROLL = process.env.PRISM_ROLL || ''            // set for a submission export; the committed deck stays neutral
const OUT = process.env.PRISM_DECK_OUT || `${ROOT}/docs/Prism_Underwriting_Engine.pptx`

// Palette: deep navy dominant on the bookends, white content slides, one accent (Prism blue) plus the
// chart series colours (orange = bureau-only, aqua = GBM) reused exactly as in the figures.
const C = { navy: '0F1F3D', navy2: '16294F', ink: '0B0B0B', muted: '52514E', faint: '8A8985', surface: 'F4F5F7', line: 'E3E2DE',
  blue: '2A78D6', aqua: '1BAF7A', amber: 'EDA100', orange: 'EB6834', red: 'E34948', white: 'FFFFFF', tintBlue: 'EAF2FC', tintAqua: 'E8F7F0', tintAmber: 'FDF4E1', tintRed: 'FCECEC' }
const FONT = 'Calibri'

const pres = new pptxgen()
pres.layout = 'LAYOUT_16x9' // 10 x 5.625 in
pres.author = 'Ansh Dixit'
pres.title = 'Prism - Real-Time Multi-Modal Underwriting Engine'

const pct = (v, d = 0) => `${(v * 100).toFixed(d)}%`
let slideNo = 0

function base(dark = false) {
  const s = pres.addSlide()
  s.background = { color: dark ? C.navy : C.white }
  slideNo++
  if (!dark) {
    s.addText(`${slideNo}`, { x: 9.3, y: 5.22, w: 0.4, h: 0.25, fontSize: 9, color: C.faint, fontFace: FONT, align: 'right', isTextBox: true, margin: 0 })
    s.addText('Prism · real-time multi-modal underwriting', { x: 0.5, y: 5.22, w: 4, h: 0.25, fontSize: 9, color: C.faint, fontFace: FONT, isTextBox: true, margin: 0 })
  }
  return s
}
function title(s, text, sub, dark = false) {
  s.addText(text, { x: 0.5, y: 0.35, w: 9, h: 0.6, fontSize: 28, bold: true, color: dark ? C.white : C.ink, fontFace: FONT, isTextBox: true, margin: 0 })
  if (sub) s.addText(sub, { x: 0.5, y: 0.92, w: 9, h: 0.35, fontSize: 13, color: dark ? 'CADCFC' : C.muted, fontFace: FONT, isTextBox: true, margin: 0 })
}
function prismMark(s, x, y, size = 0.5, colour = C.blue) {
  // The motif: a small prism glyph (triangle + refracted bands), repeated on every slide.
  s.addShape(pres.shapes.ISOSCELES_TRIANGLE, { x, y, w: size, h: size, fill: { color: colour }, line: { color: colour } })
  s.addShape(pres.shapes.RECTANGLE, { x: x + size * 0.95, y: y + size * 0.15, w: size * 0.55, h: size * 0.14, fill: { color: C.blue }, line: { color: C.blue } })
  s.addShape(pres.shapes.RECTANGLE, { x: x + size * 0.95, y: y + size * 0.43, w: size * 0.75, h: size * 0.14, fill: { color: C.aqua }, line: { color: C.aqua } })
  s.addShape(pres.shapes.RECTANGLE, { x: x + size * 0.95, y: y + size * 0.71, w: size * 0.45, h: size * 0.14, fill: { color: C.amber }, line: { color: C.amber } })
}
function card_(s, x, y, w, h, opts = {}) {
  s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w, h, rectRadius: 0.08, fill: { color: opts.fill ?? C.surface }, line: { color: opts.line ?? C.line, width: 0.75 },
    shadow: opts.shadow ? { type: 'outer', blur: 4, offset: 1, angle: 90, color: '000000', opacity: 0.08 } : undefined })
}
function stat(s, x, y, w, value, label, colour = C.blue, sub) {
  card_(s, x, y, w, 1.1)
  s.addText(value, { x: x + 0.15, y: y + 0.06, w: w - 0.3, h: 0.46, fontSize: 26, bold: true, color: colour, fontFace: FONT, isTextBox: true, margin: 0 })
  s.addText(label, { x: x + 0.15, y: y + 0.52, w: w - 0.3, h: 0.34, fontSize: 10, color: C.ink, fontFace: FONT, valign: 'top', isTextBox: true, margin: 0 })
  if (sub) s.addText(sub, { x: x + 0.15, y: y + 0.86, w: w - 0.3, h: 0.2, fontSize: 8.5, color: C.faint, fontFace: FONT, isTextBox: true, margin: 0 })
}
function chip(s, x, y, n, colour = C.blue) {
  s.addShape(pres.shapes.OVAL, { x, y, w: 0.34, h: 0.34, fill: { color: colour }, line: { color: colour } })
  s.addText(String(n), { x, y, w: 0.34, h: 0.34, fontSize: 12, bold: true, color: C.white, fontFace: FONT, align: 'center', valign: 'middle', isTextBox: true, margin: 0 })
}
function bullets(s, items, x, y, w, h, size = 12, colour = C.ink) {
  s.addText(items.map((t, i) => ({ text: t, options: { bullet: { indent: 12 }, breakLine: i < items.length - 1, paraSpaceAfter: 5 } })),
    { x, y, w, h, fontSize: size, color: colour, fontFace: FONT, valign: 'top', isTextBox: true, margin: 0 })
}
function para(s, text, x, y, w, h, size = 12, colour = C.ink, opts = {}) {
  s.addText(text, { x, y, w, h, fontSize: size, color: colour, fontFace: FONT, valign: 'top', isTextBox: true, margin: 0, ...opts })
}
function chartFrame(extra = {}) {
  return { fontFace: FONT, catAxisLabelColor: C.muted, valAxisLabelColor: C.muted, catAxisLabelFontSize: 10, valAxisLabelFontSize: 9,
    valGridLine: { color: C.line, size: 0.5 }, catGridLine: { style: 'none' }, valAxisLineShow: false, catAxisLineShow: false, dataLabelFontFace: FONT, dataLabelFontSize: 9, dataLabelColor: C.muted, ...extra }
}

// ------------------------------------------------------------------ 1. Title
{
  const s = base(true)
  prismMark(s, 0.6, 0.7, 0.9)
  s.addText('Prism', { x: 0.6, y: 1.9, w: 8.8, h: 0.9, fontSize: 54, bold: true, color: C.white, fontFace: FONT, isTextBox: true, margin: 0 })
  s.addText('A real-time, multi-modal underwriting engine for new-to-credit and thin-file customers', { x: 0.6, y: 2.8, w: 8.6, h: 0.8, fontSize: 20, color: 'CADCFC', fontFace: FONT, isTextBox: true, margin: 0 })
  s.addText('Cash-flow and behavioural signals · an interpretable scorecard · exact reason codes · guardrailed AI explanations', { x: 0.6, y: 3.75, w: 8.6, h: 0.35, fontSize: 12, color: '8FA6D0', fontFace: FONT, italic: true, isTextBox: true, margin: 0 })
  s.addText(`Ansh Dixit${ROLL ? ' · ' + ROLL : ''} · September 2026 · ${REPO}`, { x: 0.6, y: 4.75, w: 8.6, h: 0.35, fontSize: 12, color: 'CADCFC', fontFace: FONT, isTextBox: true, margin: 0 })
}

// ------------------------------------------------------------------ 2. The problem
{
  const s = base()
  title(s, 'Creditworthy, but unscoreable', 'Traditional models rely on static bureau history - which millions of good borrowers simply do not have yet')
  prismMark(s, 9.05, 0.38, 0.32)
  stat(s, 0.5, 1.45, 2.2, pct(card.training_data.file_type_mix.ntc), 'of applicants have no bureau file', C.orange, 'thin file: another ' + pct(card.training_data.file_type_mix.thin))
  stat(s, 2.85, 1.45, 2.2, '0%', 'NTC approval under a bureau-only model', C.orange, '"no hit" = auto-decline')
  stat(s, 5.2, 1.45, 2.2, pct(card.policy_simulation.models.bureau_only.approval_rate_overall, 1), 'overall approval, bureau-only', C.orange, 'at a 6% portfolio bad rate')
  stat(s, 7.55, 1.45, 1.95, '0.45', 'age adverse-impact ratio', C.red, 'fails the 4/5ths rule')
  card_(s, 0.5, 2.75, 4.35, 2.3, { fill: C.tintBlue, line: C.tintBlue })
  para(s, 'What it has to do', 0.7, 2.9, 4, 0.3, 13, C.ink, { bold: true })
  bullets(s, ['Expand access for New-to-Credit and thin-file customers with alternative data and real-time behavioural signals',
    'Mitigate fraud at the same time - not as an afterthought',
    'Regulatory transparency: every decision explainable and defensible',
    'Shift from reactive scoring to proactive, contextual decisioning'], 0.7, 3.25, 4.0, 1.75, 11)
  card_(s, 5.15, 2.75, 4.35, 2.3)
  para(s, 'Why it is hard', 5.35, 2.9, 4, 0.3, 13, C.ink, { bold: true })
  bullets(s, ['No file means no signal - unless you bring a modality the applicant actually has: their cash flow',
    'Signals that catch fraud must never leak into price (fair lending)',
    'Reg B requires specific principal reasons; a black box cannot supply them and an LLM must not invent them',
    'Real-time: the answer is expected before the applicant leaves the page'], 5.35, 3.25, 4.0, 1.75, 11)
}

// ------------------------------------------------------------------ 3. Approach
{
  const s = base()
  title(s, 'The approach in one line', 'A scorecard and rules make the decision. The language model only explains and retrieves - and is checked before it speaks.')
  const stages = [
    ['Categorise', 'raw statement lines → categories by semantic similarity (pgvector)', C.aqua],
    ['Cash-flow', 'verified income, volatility, rent / utility / phone regularity, obligations, overdrafts', C.blue],
    ['Fraud gate', 'behavioural + veracity signals → pass / step-up / block, before pricing', C.orange],
    ['Scorecard', 'WoE logistic model - exact additive contributions, principal reasons', C.blue],
    ['Policy', 'calibrated PD bands, limit from verified income, APR tier, NTC refer path', C.blue],
    ['Explain', 'guardrailed notice, underwriter summary, cited copilot - validated against the model', '4A3AA7'],
  ]
  stages.forEach(([h, t, col], i) => {
    const x = 0.5 + i * 1.52
    card_(s, x, 1.5, 1.42, 2.05, { fill: C.white, line: col, shadow: true })
    chip(s, x + 0.12, 1.62, i + 1, col)
    para(s, h, x + 0.12, 2.05, 1.2, 0.3, 13, C.ink, { bold: true })
    para(s, t, x + 0.12, 2.38, 1.2, 1.1, 9.5, C.muted)
    if (i < 5) s.addShape(pres.shapes.RIGHT_ARROW, { x: x + 1.43, y: 2.42, w: 0.1, h: 0.2, fill: { color: C.faint }, line: { color: C.faint } })
  })
  para(s, '≈ 100 ms end to end before the LLM step, on one request', 0.5, 3.65, 9, 0.3, 11, C.faint, { italic: true })
  card_(s, 0.5, 4.0, 9, 1.05, { fill: C.tintBlue, line: C.tintBlue })
  para(s, 'Three modalities, one scorecard, one invariant', 0.7, 4.1, 8.6, 0.3, 12.5, C.ink, { bold: true })
  para(s, 'Bureau data when it exists · consented bank cash-flow data · behavioural signals captured live in the form. An NTC applicant with a linked account scores on the same card as everyone else; without one they are referred, never auto-declined. Behavioural signals feed the fraud gate only - they never change a legitimate applicant\'s price.',
    0.7, 4.4, 8.6, 0.6, 11, C.muted)
}

// ------------------------------------------------------------------ 4. Insight: information value
{
  const s = base()
  title(s, 'Insight 1 - cash flow is as informative as the bureau', 'Information value per feature on the hold-out set (higher = more predictive of 12-month delinquency)')
  const feats = [...scorecard.features].sort((a, b) => a.information_value - b.information_value)
  const labels = feats.map((f) => f.label.replace(' (month-to-month volatility)', '').replace(' relative to income', ' / income'))
  s.addChart(pres.charts.BAR, [{ name: 'Information value', labels, values: feats.map((f) => +f.information_value.toFixed(3)) }], {
    x: 0.4, y: 1.3, w: 5.6, h: 3.85, barDir: 'bar', ...chartFrame(),
    chartColors: feats.map((f) => ({ bureau: C.orange, cashflow: C.blue, application: C.aqua }[f.modality])),
    showValue: true, dataLabelPosition: 'outEnd', dataLabelFormatCode: '0.00', showLegend: false, valAxisHidden: true, catAxisLabelFontSize: 8.5, dataLabelFontSize: 8,
  })
  s.addShape(pres.shapes.RECTANGLE, { x: 6.3, y: 1.35, w: 0.18, h: 0.18, fill: { color: C.blue }, line: { color: C.blue } })
  para(s, 'Cash-flow (open banking)', 6.55, 1.32, 2.5, 0.25, 10.5, C.muted)
  s.addShape(pres.shapes.RECTANGLE, { x: 6.3, y: 1.62, w: 0.18, h: 0.18, fill: { color: C.orange }, line: { color: C.orange } })
  para(s, 'Bureau', 6.55, 1.59, 2.5, 0.25, 10.5, C.muted)
  s.addShape(pres.shapes.RECTANGLE, { x: 6.3, y: 1.89, w: 0.18, h: 0.18, fill: { color: C.aqua }, line: { color: C.aqua } })
  para(s, 'Application', 6.55, 1.86, 2.5, 0.25, 10.5, C.muted)
  card_(s, 6.3, 2.3, 3.2, 2.85)
  bullets(s, ['Income stability and the minimum-balance buffer are individually as informative as the bureau score itself.',
    'Rent, utility and phone regularity - things an NTC applicant has been doing for years - each carry IV ≈ 0.7.',
    'Every feature is a plain, explainable statistic of the statement, so each contribution can be traced to transactions.',
    'Behavioural signals are absent from this chart on purpose: they are fraud signals, not credit factors.'], 6.5, 2.45, 2.85, 2.6, 10.5)
}

// ------------------------------------------------------------------ 5. Insight: approval lift
{
  const s = base()
  const p = card.policy_simulation
  title(s, 'Insight 2 - more approvals at exactly the same risk', `Approval rate by file type when every model is cut off at a ${pct(p.target_portfolio_bad_rate)} realised bad rate on the approved book (hold-out)`)
  const segs = [['thick', 'Thick file'], ['thin', 'Thin file'], ['ntc', 'New-to-credit']]
  s.addChart(pres.charts.BAR, [
    { name: 'Bureau-only baseline', labels: segs.map((x) => x[1]), values: segs.map(([k]) => +(p.models.bureau_only.by_file_type[k].approval_rate * 100).toFixed(1)) },
    { name: 'Prism scorecard', labels: segs.map((x) => x[1]), values: segs.map(([k]) => +(p.models.prism_scorecard.by_file_type[k].approval_rate * 100).toFixed(1)) },
    { name: 'GBM benchmark', labels: segs.map((x) => x[1]), values: segs.map(([k]) => +(p.models.gbm_benchmark.by_file_type[k].approval_rate * 100).toFixed(1)) },
  ], { x: 0.4, y: 1.3, w: 5.5, h: 3.8, barDir: 'col', barGapWidthPct: 60, ...chartFrame(), chartColors: [C.orange, C.blue, C.aqua],
    showValue: true, dataLabelPosition: 'outEnd', dataLabelFormatCode: '0"%"', showLegend: true, legendPos: 'b', legendFontSize: 10, legendColor: C.muted, valAxisMaxVal: 90, valAxisLabelFormatCode: '0"%"' })
  stat(s, 6.2, 1.3, 3.3, `${pct(p.models.bureau_only.approval_rate_overall, 1)} → ${pct(p.models.prism_scorecard.approval_rate_overall, 1)}`, 'overall approval rate, same 6% bad rate', C.blue)
  stat(s, 6.2, 2.5, 3.3, `0% → ${pct(p.models.prism_scorecard.by_file_type.ntc.approval_rate)}`, 'New-to-credit approvals', C.blue, `realised bad rate ${pct(p.models.prism_scorecard.by_file_type.ntc.realised_bad_rate_approved, 1)}`)
  stat(s, 6.2, 3.7, 3.3, pct(p.swap_in_set.share_of_applicants, 1), 'of applicants newly approved ("swap-in")', C.aqua, `they default at ${pct(p.swap_in_set.realised_bad_rate, 1)} · ${pct(p.swap_in_set.share_ntc)} are NTC`)
}

// ------------------------------------------------------------------ 6. Insight: explainability tax
{
  const s = base()
  const pf = card.performance
  title(s, 'Insight 3 - the interpretable model costs nothing here', 'ROC on the hold-out set: the WoE scorecard vs the bureau-only baseline vs a gradient-boosting benchmark')
  s.addImage({ path: `${FIG}/roc_curves.png`, altText: 'ROC curves on hold-out: Prism scorecard, bureau-only baseline, GBM benchmark', x: 0.4, y: 1.3, w: 5.6, h: 2.46 })
  const rows = [['Segment', 'Bureau-only', 'Prism scorecard', 'GBM benchmark'],
    ...[['overall', 'All applicants'], ['thick', 'Thick file'], ['thin', 'Thin file'], ['ntc', 'New-to-credit']].map(([k, l]) => [l, pf.bureau_only[k].auc ?? 'cannot score', String(pf.prism_scorecard[k].auc), String(pf.gbm_benchmark[k].auc)])]
  s.addTable(rows.map((r, i) => r.map((c, j) => ({ text: String(c), options: { bold: i === 0 || j === 2, color: i === 0 ? C.muted : C.ink, fontSize: 10, fontFace: FONT, fill: { color: i === 0 ? C.surface : C.white }, align: j === 0 ? 'left' : 'right' } }))),
    { x: 0.4, y: 3.88, w: 5.9, colW: [1.9, 1.3, 1.4, 1.3], border: { type: 'solid', color: C.line, pt: 0.5 }, rowH: 0.22 })
  card_(s, 6.6, 1.3, 2.9, 3.85)
  para(s, 'Why a scorecard', 6.8, 1.45, 2.5, 0.3, 13, C.ink, { bold: true })
  bullets(s, ['Additive by construction: logit = intercept + Σ contributions. Reason codes are exact, not a SHAP approximation.',
    'Regulators, model-risk teams and underwriters already know how to validate it.',
    'The GBM benchmark quantifies what interpretability costs - here AUC 0.809 vs 0.808.',
    'Honest caveat: the synthetic generator is additive in its latent factor, which flatters linear models. On real data expect a 0.01–0.03 AUC gap; the measurement, not the number, is the point.'], 6.8, 1.8, 2.55, 3.3, 10)
}

// ------------------------------------------------------------------ 7. Insight: fairness
{
  const s = base()
  const fa = card.fairness.attributes.age_band
  title(s, 'Insight 4 - inclusion is measurable, so measure it', 'Approval rate by age band. Age is never a model input; it is used only to test the model.')
  const bands = ['18-24', '25-34', '35-44', '45-54', '55+']
  s.addChart(pres.charts.BAR, [
    { name: `Bureau-only  (AIR ${fa.bureau_only.adverse_impact_ratio.toFixed(2)} - fails 4/5ths)`, labels: bands, values: bands.map((b) => +(fa.bureau_only.approval_rate[b] * 100).toFixed(0)) },
    { name: `Prism  (AIR ${fa.prism_scorecard.adverse_impact_ratio.toFixed(2)} - passes)`, labels: bands, values: bands.map((b) => +(fa.prism_scorecard.approval_rate[b] * 100).toFixed(0)) },
  ], { x: 0.4, y: 1.3, w: 5.6, h: 3.8, barDir: 'col', barGapWidthPct: 70, ...chartFrame(), chartColors: [C.orange, C.blue], showValue: true, dataLabelPosition: 'outEnd', dataLabelFormatCode: '0"%"',
    showLegend: true, legendPos: 'b', legendFontSize: 10, legendColor: C.muted, valAxisMaxVal: 90, valAxisLabelFormatCode: '0"%"' })
  stat(s, 6.3, 1.3, 3.2, `${fa.bureau_only.adverse_impact_ratio.toFixed(2)} → ${fa.prism_scorecard.adverse_impact_ratio.toFixed(2)}`, 'adverse-impact ratio (age)', C.aqua, '4/5ths rule threshold = 0.80')
  stat(s, 6.3, 2.5, 3.2, `${pct(fa.bureau_only.equal_opportunity_gap)} → ${pct(fa.prism_scorecard.equal_opportunity_gap)}`, 'equal-opportunity gap', C.aqua, 'max − min TPR among good payers')
  card_(s, 6.3, 3.7, 3.2, 1.45)
  para(s, 'Why it moves: young applicants are disproportionately NTC. A bureau-only lender turns "no history yet" into "declined"; cash-flow evidence gives them a way to prove themselves. Sex and region AIR ≥ 0.95 under both models.', 6.5, 3.8, 2.85, 1.3, 10, C.muted)
}

// ------------------------------------------------------------------ 8. Fraud gate
{
  const s = base()
  const fg = card.fraud_gate
  title(s, 'The fraud & verification gate runs before pricing', 'Additive rule points on behavioural, device and veracity signals. Two thresholds. A block needs at least two strong signals.')
  const rules = [['F01', 'Stated income > 1.35× bank-verified', 40], ['F02', 'Identity number pasted into the form', 25], ['F03', 'Device used for ≥ 3 applications / 30 days', 35], ['F04', 'Email address younger than 60 days', 15],
    ['F05', 'VoIP phone number', 10], ['F06', 'Application completed in < 2 minutes', 15], ['F07', 'Income field edited ≥ 3 times', 10], ['F08', 'Income unverifiable - no linked account', 20]]
  s.addTable([[{ text: 'Rule', options: { bold: true, color: C.muted } }, { text: 'Signal', options: { bold: true, color: C.muted } }, { text: 'Points', options: { bold: true, color: C.muted, align: 'right' } }],
    ...rules.map(([id, t, p]) => [{ text: id, options: { bold: true } }, { text: t }, { text: String(p), options: { align: 'right' } }])].map((r) => r.map((c) => ({ ...c, options: { fontSize: 10, fontFace: FONT, color: C.ink, fill: { color: C.white }, ...c.options } }))),
    { x: 0.5, y: 1.3, w: 4.9, colW: [0.6, 3.6, 0.7], border: { type: 'solid', color: C.line, pt: 0.5 }, rowH: 0.27 })
  para(s, '≥ 60 points → BLOCK (decline, fraud review) · 35–59 → STEP-UP (verify identity / income first) · < 35 → PASS', 0.5, 3.85, 4.9, 0.5, 10, C.muted)
  s.addChart(pres.charts.BAR, [{ name: 'Flagged', labels: ['Synthetic identity', 'Income inflation', 'Device ring', 'Legit: step-up', 'Legit: blocked'],
    values: [fg.recall_by_fraud_type.synthetic_identity, fg.recall_by_fraud_type.income_inflation, fg.recall_by_fraud_type.device_ring, fg.friction_on_legitimate.step_up_rate, fg.friction_on_legitimate.block_rate].map((v) => +(v * 100).toFixed(1)) }],
    { x: 5.6, y: 1.25, w: 3.9, h: 2.4, barDir: 'col', barGapWidthPct: 50, ...chartFrame(), chartColors: [C.blue, C.blue, C.blue, C.amber, C.orange], showValue: true, dataLabelPosition: 'outEnd', dataLabelFormatCode: '0.0"%"', showLegend: false, valAxisMinVal: 0, valAxisMaxVal: 120, valAxisLabelFormatCode: '0"%"', catAxisLabelFontSize: 8.5, showTitle: true, title: '% of each population flagged (step-up or block)', titleFontSize: 10, titleColor: C.muted })
  stat(s, 5.6, 3.8, 1.85, pct(fg.block.precision, 1), 'block precision', C.blue, `recall ${pct(fg.block.recall)}`)
  stat(s, 7.55, 3.8, 1.95, pct(fg.friction_on_legitimate.step_up_rate, 2), 'friction on legitimate', C.aqua, 'step-up rate')
}

// ------------------------------------------------------------------ 9. Explainability & Reg B
{
  const s = base()
  title(s, 'Explainability that survives a regulator', 'Exact reasons from the model; a notice the language model may phrase but cannot bend')
  s.addImage({ path: `${SHOT}/decision-decline.png`, altText: 'Decision page for a declined application showing principal reasons and the applicant notice', x: 0.4, y: 1.3, w: 5.75, h: 3.7 })
  card_(s, 6.4, 1.3, 3.1, 3.85)
  chip(s, 6.55, 1.45, 1); para(s, 'Contributions → principal reasons', 6.98, 1.47, 2.45, 0.35, 11, C.ink, { bold: true })
  para(s, 'Largest risk-increasing factors, at most four, aggregated by reason code. R01–R18 map to Reg B principal-reason language.', 6.55, 1.83, 2.8, 0.6, 9.5, C.muted)
  chip(s, 6.55, 2.5, 2); para(s, 'Absence of evidence ≠ bad evidence', 6.98, 2.52, 2.45, 0.35, 11, C.ink, { bold: true })
  para(s, 'A no-file applicant gets R19 "no credit bureau file", never "score below threshold". Missing data has its own codes.', 6.55, 2.88, 2.8, 0.6, 9.5, C.muted)
  chip(s, 6.55, 3.55, 3); para(s, 'The LLM is checked before it speaks', 6.98, 3.57, 2.45, 0.35, 11, C.ink, { bold: true })
  para(s, 'Its notice must restate the decision and exactly the model\'s codes, with no protected-class or promissory language - or it is rejected and a deterministic template is issued.', 6.55, 3.93, 2.8, 1.2, 9.5, C.muted)
}

// ------------------------------------------------------------------ 10. Architecture
{
  const s = base()
  title(s, 'Architecture', 'React · Spring Boot 4 · PostgreSQL 16 + pgvector · Amazon Bedrock (or Anthropic API / offline) · AWS ECS + RDS')
  s.addImage({ path: `${FIG}/architecture.png`, altText: 'Prism architecture diagram: frontend, backend pipeline, guardrail service, data layer, AI layer, ML pipeline', x: 0.35, y: 1.28, w: 9.3, h: 3.85 })
}

// ------------------------------------------------------------------ 11. AI layer
{
  const s = base()
  title(s, 'AI layer - one guarded path to any model', 'Provider is an environment variable. Prompts are versioned files. Every call is validated and audited.')
  const steps = [['PII redaction', 'ids, emails, phones, cards masked before anything leaves'], ['Managed guardrail', 'Bedrock ApplyGuardrail on input & output (PII, denied topics, prompt attacks)'], ['Model call', 'Groq / any OpenAI-compatible · Bedrock Converse · Anthropic · offline, hard timeout'],
    ['Output validator', 'JSON schema · reason codes ⊆ model\'s · decision restated · no protected-class terms'], ['Retry then fallback', 'one corrective retry, then the deterministic template'], ['Audit row', 'provider, model, latency, tokens, validation, fallback, prompt hash']]
  steps.forEach(([h, t], i) => {
    const x = 0.5 + i * 1.52
    card_(s, x, 1.35, 1.42, 1.55, { fill: 'F1EEFB', line: 'F1EEFB' })
    chip(s, x + 0.12, 1.45, i + 1, '4A3AA7')
    para(s, h, x + 0.12, 1.85, 1.25, 0.3, 11, C.ink, { bold: true })
    para(s, t, x + 0.12, 2.15, 1.22, 0.75, 8.5, C.muted)
  })
  card_(s, 0.5, 3.1, 2.9, 2.05)
  para(s, 'Providers', 0.7, 3.2, 2.6, 0.3, 12, C.ink, { bold: true })
  bullets(s, ['Any OpenAI-compatible endpoint - the demo runs on the Groq free tier (gpt-oss-120b); a local Ollama or in-VPC vLLM is a base-URL change', 'Amazon Bedrock: Converse, Titan Embeddings v2, Guardrails - wired, not exercised (no AWS account available)', 'Anthropic API adapter; offline template + hashed embeddings need no credentials'], 0.7, 3.5, 2.6, 1.6, 9)
  card_(s, 3.55, 3.1, 2.9, 2.05)
  para(s, 'Three closed tasks', 3.75, 3.2, 2.6, 0.3, 12, C.ink, { bold: true })
  bullets(s, ['ADVERSE_ACTION_NOTICE - plain-language, Reg B wording', 'UNDERWRITER_SUMMARY - strengths / risks citing factor codes', 'COPILOT_ANSWER - retrieval-orchestrated, cites only retrieved policy sections'], 3.75, 3.5, 2.6, 1.6, 9.5)
  card_(s, 6.6, 3.1, 2.9, 2.05, { fill: C.tintBlue, line: C.tintBlue })
  para(s, 'What the model never does', 6.8, 3.2, 2.6, 0.3, 12, C.ink, { bold: true })
  bullets(s, ['Decide, price, rank or override', 'See a name, contact detail or identifier', 'Cite policy it was not given', 'Reach a customer unvalidated'], 6.8, 3.5, 2.6, 1.6, 9.5)
}

// ------------------------------------------------------------------ 12. Data layer
{
  const s = base()
  title(s, 'Data layer - PostgreSQL 16 + pgvector', 'One database for structured decision records and three vector stores, schema managed by Flyway')
  card_(s, 0.5, 1.35, 4.35, 3.8)
  para(s, 'Structured', 0.7, 1.45, 4, 0.3, 13, C.ink, { bold: true })
  bullets(s, ['applications - identity minimised; the national ID is stored only as a keyed HMAC for duplicate / synthetic-identity checks',
    'bank_transactions - each line with its assigned category and match confidence',
    'decisions - append-only: model version, feature snapshot, every contribution, reason codes, notice provenance, latency',
    'underwriter_actions - confirm / request docs / override, with reason',
    'ai_invocations - the audit trail of every model call',
    'users - bcrypt hashes, three roles'], 0.7, 1.8, 4.0, 3.3, 10.5)
  card_(s, 5.15, 1.35, 4.35, 3.8, { fill: C.tintAqua, line: C.tintAqua })
  para(s, 'Vector (1024-d, cosine, HNSW index)', 5.35, 1.45, 4, 0.3, 13, C.ink, { bold: true })
  bullets(s, ['category_exemplars - 104 phrases across 21 categories; a raw description takes its nearest exemplar\'s category',
    'historical_applicants - 800 decided cases with known outcomes; "applicants like this one" for the underwriter (advisory only)',
    'policy_chunks - internal policy documents split by section; the copilot may cite only what it retrieved',
    'applications.profile_embedding - every live case becomes searchable',
    'Same schema for Titan v2 and the offline embedder; stores re-seed automatically when the provider changes'], 5.35, 1.8, 4.0, 3.3, 10.5)
}

// ------------------------------------------------------------------ 13. Security
{
  const s = base()
  title(s, 'Security layer', 'Basics done properly, because a credit system is a target')
  const items = [['Authentication', 'HS256 JWT issued at login, verified by Spring Security resource-server support; stateless, 8-hour expiry'],
    ['Authorisation', 'Method-level roles: applicant (own cases), underwriter, admin. Applicants cannot read others\' cases or the AI summary'],
    ['Input validation', 'Bean Validation on every field with bounds; consent enforced before bank data is processed'],
    ['Secure API usage', 'Rate limiting on login / submit / copilot, CORS allow-list, CSP, RFC 9457 errors that never leak internals'],
    ['No hardcoded secrets', 'JWT secret and DB password from environment only; start-up fails without them; no default credential anywhere'],
    ['Responsible data handling', 'SSN stored as keyed HMAC; PII redacted before any prompt; prompts logged by hash only; AWS access via IAM roles']]
  items.forEach(([h, t], i) => {
    const col = i % 2, row = Math.floor(i / 2)
    const x = 0.5 + col * 4.65, y = 1.35 + row * 1.28
    card_(s, x, y, 4.35, 1.15)
    s.addShape(pres.shapes.OVAL, { x: x + 0.15, y: y + 0.18, w: 0.36, h: 0.36, fill: { color: C.orange }, line: { color: C.orange } })
    s.addText('✓', { x: x + 0.15, y: y + 0.18, w: 0.36, h: 0.36, fontSize: 14, bold: true, color: C.white, fontFace: FONT, align: 'center', valign: 'middle', isTextBox: true, margin: 0 })
    para(s, h, x + 0.65, y + 0.14, 3.5, 0.3, 12, C.ink, { bold: true })
    para(s, t, x + 0.65, y + 0.44, 3.55, 0.7, 9.5, C.muted)
  })
}

// ------------------------------------------------------------------ 14. Demo
{
  const s = base()
  title(s, 'The prototype, end to end', 'Six synthetic personas exercise every path: approve, refer, decline, step-up, block, and the no-file inclusion path')
  const shots = [['u-apply.png', 'Apply - persona loader, consent, simulated bureau pull and bank feed, live behavioural capture'],
    ['u-decision-ntc.png', 'Decision - Priya (no file, gig income) approved in ~100 ms; notice written by gpt-oss-120b via Groq and validated; reason R19 = "no bureau file"'],
    ['u-decision-block.png', 'Block - a synthetic identity with a spotless statement is stopped before pricing'],
    ['u-queue.png', 'Queue - underwriter view with decision, gate outcome, score and PD per case'],
    ['u-underwriter.png', 'Underwriter - AI summary, logged override, copilot that cites policy and refuses a decline "because the applicant is young"'],
    ['u-model.png', 'Model & fairness - performance by segment, policy simulation, AIR by group, live monitoring']]
  shots.forEach(([f, cap], i) => {
    const col = i % 3, row = Math.floor(i / 3)
    const x = 0.4 + col * 3.1, y = 1.28 + row * 1.98
    s.addImage({ path: `${SHOT}/${f}`, altText: cap, x, y, w: 2.95, h: 1.5 })
    s.addShape(pres.shapes.RECTANGLE, { x, y, w: 2.95, h: 1.5, fill: { type: 'none' }, line: { color: C.line, width: 0.75 } })
    para(s, cap, x, y + 1.55, 2.95, 0.42, 8, C.muted)
  })
}

// ------------------------------------------------------------------ 15. Code
{
  const s = base()
  title(s, 'Code walkthrough', 'Clean, modular packages; the scoring engine is a pure function, parity-tested against the Python export')
  card_(s, 0.5, 1.3, 4.3, 3.85, { fill: '1E1E1E', line: '1E1E1E' })
  s.addText([
    { text: 'ScorecardEngine.score(features)', options: { color: 'DCDCAA', bold: true, breakLine: true } },
    { text: 'double logit = scorecard.intercept();', options: { breakLine: true } },
    { text: 'for (Feature f : scorecard.features()) {', options: { breakLine: true } },
    { text: '  Double v = features.get(f.name());', options: { breakLine: true } },
    { text: '  boolean missing = v == null || v.isNaN();', options: { breakLine: true } },
    { text: '  double woe = f.woeFor(v);   // missing bin', options: { breakLine: true } },
    { text: '  double c = f.coefficient() * woe;', options: { breakLine: true } },
    { text: '  logit += c;', options: { breakLine: true } },
    { text: '  contributions.add(new FeatureContribution(', options: { breakLine: true } },
    { text: '      f.name(), f.label(), f.modality(), f.reasonCode(),', options: { breakLine: true } },
    { text: '      missing ? null : v, missing, woe, c));', options: { breakLine: true } },
    { text: '}', options: { breakLine: true } },
    { text: 'double pd = 1 / (1 + Math.exp(-logit));', options: { breakLine: true } },
    { text: 'int score = toScore(logit);  // 600 = 30:1 odds', options: { breakLine: true } },
    { text: 'return new ScoreResult(..., principalReasons(contribs));', options: { breakLine: true } },
    { text: '', options: { breakLine: true } },
    { text: '// principalReasons: aggregate c > 0 by reason code,', options: { color: '6A9955', breakLine: true } },
    { text: '// unobserved features → missing-data code (R19/R20),', options: { color: '6A9955', breakLine: true } },
    { text: '// drop noise below the floor, keep the top four.', options: { color: '6A9955' } },
  ], { x: 0.65, y: 1.4, w: 4.05, h: 3.65, fontSize: 9, fontFace: 'Courier New', color: 'D4D4D4', valign: 'top', isTextBox: true, margin: 0 })
  card_(s, 5.1, 1.3, 4.4, 1.85)
  para(s, 'backend/ packages', 5.3, 1.4, 4, 0.3, 12, C.ink, { bold: true })
  para(s, 'scoring · fraud · cashflow · ai (bedrock, anthropic, offline, validator, guardrail, audit) · explain · similar · knowledge · copilot · application · auth · model · monitoring · common', 5.3, 1.7, 4.0, 0.7, 10, C.muted)
  para(s, 'Records for immutable values, constructor injection, no Lombok, interfaces at every provider boundary (LlmClient, EmbeddingClient, TextGuardrail, TransactionCategorizer).', 5.3, 2.4, 4.0, 0.7, 10, C.muted)
  card_(s, 5.1, 3.3, 4.4, 1.85, { fill: C.tintAqua, line: C.tintAqua })
  para(s, 'Tests', 5.3, 3.4, 4, 0.3, 12, C.ink, { bold: true })
  bullets(s, ['43 backend tests + 12 frontend tests: engine, policy, fraud gate, cash-flow arithmetic, validator, redaction, LLM adapter, UI components',
    'Parity: Java reproduces Python\'s score, PD and reason codes on 300 hold-out rows',
    'Testcontainers flow on real pgvector: login → submit → decision → summary → copilot → action; CI runs all of it plus an ML reproducibility check'], 5.3, 3.7, 4.0, 1.4, 9.5)
}

// ------------------------------------------------------------------ 16. Engineering practices
{
  const s = base()
  title(s, 'Engineering practices', 'What a reviewer can verify in the repository')
  const items = [['API-first', 'docs/api/openapi.yaml; every endpoint validated and role-guarded; RFC 9457 errors'],
    ['Version control', '60+ scoped commits on GitHub with CI on every push; .gitattributes, .gitignore, .env.example - the secret file is never committed'],
    ['README & run', 'One env file, docker compose for pgvector, mvnw and npm scripts; full stack also runs as containers'],
    ['Architecture diagram', 'docs/architecture.svg (source) + PNG; deployment topology in infra/aws'],
    ['Testing', '43 backend + 12 frontend tests, Python-parity and Testcontainers integration; GitHub Actions runs them and re-derives the model artefacts'],
    ['Credentials', 'Environment only; start-up fails fast without a 32-char JWT secret; AWS via IAM roles; Secrets Manager in deployment'],
    ['Configuration', 'Typed @ConfigurationProperties with validation; provider swaps are one variable'],
    ['Reproducibility', 'Seeded synthetic data, exported artefacts versioned with the service, model version on every decision row']]
  items.forEach(([h, t], i) => {
    const col = i % 2, row = Math.floor(i / 2)
    const x = 0.5 + col * 4.65, y = 1.35 + row * 0.96
    chip(s, x, y + 0.05, i + 1, C.blue)
    para(s, h, x + 0.45, y, 3.9, 0.3, 12, C.ink, { bold: true })
    para(s, t, x + 0.45, y + 0.3, 3.9, 0.62, 9.5, C.muted)
  })
}

// ------------------------------------------------------------------ 17. Responsible AI
{
  const s = base()
  title(s, 'Responsible AI, and what is not yet proven', 'The model card ships with the service (/api/model/card); its limitations are part of the deliverable')
  card_(s, 0.5, 1.35, 4.35, 3.8, { fill: C.tintBlue, line: C.tintBlue })
  para(s, 'Built in', 0.7, 1.45, 4, 0.3, 13, C.ink, { bold: true })
  bullets(s, ['Protected characteristics are never inputs; fairness (AIR, equal opportunity) tested before release and reported in the card',
    'Every decision reproducible from its stored record without re-running the model',
    'The LLM explains only; its output is validated against the model\'s reason codes; fallback is deterministic',
    'Referred cases always reach a person; overrides are recorded with reasons and feed retraining',
    'Data minimisation: hashed identifiers, redacted prompts, hash-only logging',
    'Monitoring plan: score stability, realised vs predicted bad rate, AIR by group, override rate, LLM validation / fallback rates'], 0.7, 1.8, 4.0, 3.3, 10)
  card_(s, 5.15, 1.35, 4.35, 3.8, { fill: C.tintAmber, line: C.tintAmber })
  para(s, 'Limitations, stated plainly', 5.35, 1.45, 4, 0.3, 13, C.ink, { bold: true })
  bullets(s, ['Synthetic data: the method is real, the absolute numbers are not production performance',
    'Bureau pull and bank connection are simulated behind adapter interfaces',
    'Fairness tested on three proxies; production needs BISG-style analysis and ongoing monitoring',
    'The scorecard is linear in WoE space; interactions are not modelled (the GBM benchmark measures the cost)',
    'The Bedrock adapter is complete but unexercised (no AWS account); the live demo runs on the Groq free tier through the same validator',
    'Cash-flow features need consented bank linking; unlinked applicants are referred, which adds friction'], 5.35, 1.8, 4.0, 3.3, 10)
}

// ------------------------------------------------------------------ 18. Close
{
  const s = base(true)
  prismMark(s, 0.6, 0.6, 0.6)
  s.addText('From reactive scoring to proactive, contextual decisioning', { x: 0.6, y: 1.5, w: 8.8, h: 0.8, fontSize: 30, bold: true, color: C.white, fontFace: FONT, isTextBox: true, margin: 0 })
  const cols = [['Approval', `${pct(card.policy_simulation.models.bureau_only.approval_rate_overall)} → ${pct(card.policy_simulation.models.prism_scorecard.approval_rate_overall)}`, 'at the same 6% bad rate'],
    ['New-to-credit', `0% → ${pct(card.policy_simulation.models.prism_scorecard.by_file_type.ntc.approval_rate)}`, 'scored on the same card'],
    ['Age AIR', `${card.fairness.attributes.age_band.bureau_only.adverse_impact_ratio.toFixed(2)} → ${card.fairness.attributes.age_band.prism_scorecard.adverse_impact_ratio.toFixed(2)}`, 'passes the 4/5ths rule'],
    ['Explainability', 'exact', 'reasons validated before a customer sees them']]
  cols.forEach(([h, v, t], i) => {
    const x = 0.6 + i * 2.25
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y: 2.6, w: 2.05, h: 1.5, rectRadius: 0.08, fill: { color: C.navy2 }, line: { color: '2A3F6B', width: 0.75 } })
    s.addText(v, { x: x + 0.15, y: 2.7, w: 1.8, h: 0.55, fontSize: 24, bold: true, color: C.white, fontFace: FONT, isTextBox: true, margin: 0 })
    s.addText(h, { x: x + 0.15, y: 3.25, w: 1.8, h: 0.3, fontSize: 12, bold: true, color: 'CADCFC', fontFace: FONT, isTextBox: true, margin: 0 })
    s.addText(t, { x: x + 0.15, y: 3.55, w: 1.8, h: 0.5, fontSize: 10, color: '8FA6D0', fontFace: FONT, isTextBox: true, margin: 0 })
  })
  s.addText('Next: server-side bureau and open-banking adapters · customer IdP · model registry with the card attached · BISG fairness monitoring · Bedrock in production with the managed guardrail', { x: 0.6, y: 4.3, w: 8.8, h: 0.5, fontSize: 11, color: 'CADCFC', fontFace: FONT, isTextBox: true, margin: 0 })
  s.addText(`${REPO} - README with setup, tests and deployment · Ansh Dixit${ROLL ? ' · ' + ROLL : ''} · anshbaldixit@gmail.com`, { x: 0.6, y: 4.95, w: 8.8, h: 0.3, fontSize: 11, color: '8FA6D0', fontFace: FONT, isTextBox: true, margin: 0 })
}

await pres.writeFile({ fileName: OUT })
console.log('wrote', OUT, 'slides:', slideNo, ROLL ? '(submission export)' : '(neutral)')
