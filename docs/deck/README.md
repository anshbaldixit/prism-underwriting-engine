# Deck generator

`build_deck.mjs` builds the deck (committed as `docs/Prism_Underwriting_Engine.pdf`) from the exported model card
(`backend/src/main/resources/model/model_card.json`), the figures in `docs/figures` and the screenshot
crops in `docs/screenshots/crops`, so every number on a slide traces back to the ML export.

```bash
cd docs/deck && npm init -y >/dev/null && npm install pptxgenjs && node build_deck.mjs
```
