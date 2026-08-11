-- RC67 P1-3 幂等增强：防止进程在迁移中途 SIGKILL 后重跑抛 "table already exists"
-- 规则：CREATE TABLE IF NOT EXISTS / INSERT OR IGNORE / DROP TABLE IF EXISTS
CREATE TABLE IF NOT EXISTS ai_providers_new (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    type TEXT NOT NULL,
    apiKey TEXT NOT NULL,
    baseUrl TEXT NOT NULL,
    defaultModel TEXT NOT NULL,
    isActive INTEGER NOT NULL,
    models TEXT NOT NULL,
    selectedModel TEXT NOT NULL,
    isEnabled INTEGER NOT NULL,
    useFullUrl INTEGER NOT NULL DEFAULT 0,
    useResponseApi INTEGER NOT NULL
);

INSERT OR IGNORE INTO ai_providers_new (id, name, type, apiKey, baseUrl, defaultModel, isActive, models, selectedModel, isEnabled, useFullUrl, useResponseApi)
SELECT id, name, type, apiKey, baseUrl, defaultModel, isActive, models, selectedModel, isEnabled, useFullUrl, useResponseApi
FROM ai_providers;

DROP TABLE IF EXISTS ai_providers;
ALTER TABLE ai_providers_new RENAME TO ai_providers;
