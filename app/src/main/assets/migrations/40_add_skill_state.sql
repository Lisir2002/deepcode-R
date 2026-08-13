-- RC74 SCHEMA 40：新增 skill_state 表（技能运行时状态）
-- 严格对齐 RC68 幂等约定：CREATE TABLE IF NOT EXISTS / 布尔列 INTEGER + CHECK(IN (0,1))
-- 时间列统一毫秒单位。新增表不涉及老数据回拷，直接建表即可。
--
-- skill_state：技能运行时状态，与技能文件（SKILL.md）分离存储。
--   · id = 技能目录名（唯一标识，稳定可匹配，支持更新覆盖）
--   · enabled = 是否启用（运行时状态，不写回技能文件）
--   · version = 安装/记录时的技能版本（semver，用于更新比对）
--   · source = 来源类型（BUILTIN/LOCAL）
--   · installedAtMs = 安装时间（毫秒）
CREATE TABLE IF NOT EXISTS skill_state (
    id TEXT NOT NULL PRIMARY KEY,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0,1)),
    version TEXT NOT NULL DEFAULT '0.0.0',
    source TEXT NOT NULL DEFAULT 'LOCAL',
    installedAtMs INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS index_skill_state_source ON skill_state (source);
