-- Provenance labels now carry "provider · model" (e.g. "groq · openai/gpt-oss-120b"), which needs more room.
ALTER TABLE decisions ALTER COLUMN notice_provider TYPE VARCHAR(120);
ALTER TABLE decisions ALTER COLUMN summary_provider TYPE VARCHAR(120);
