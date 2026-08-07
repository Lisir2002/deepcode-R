ALTER TABLE ai_providers ADD COLUMN encryptedApiKey TEXT NOT NULL DEFAULT '';
ALTER TABLE git_credentials ADD COLUMN encryptedToken TEXT NOT NULL DEFAULT '';