-- RC74 SCHEMA 40：新增 skill_state 表（技能运行时状态）
-- RC90 修复：移除与 @Entity(SkillStateEntity) 不一致的 DEFAULT 与索引。
--   · 实体定义没有 @ColumnInfo(defaultValue)，Room 期望列无 SQL DEFAULT；
--   · 实体定义没有 @Index，Room 期望无索引。
-- 此前带 DEFAULT/索引的建表会导致 Room TableInfo 校验失败（Migration didn't properly handle）。
--
-- skill_state：技能运行时状态，与技能文件（SKILL.md）分离存储。
--   · id = 技能目录名（唯一标识，稳定可匹配，支持更新覆盖）
--   · enabled = 是否启用（运行时状态，不写回技能文件）
--   · version = 安装/记录时的技能版本（semver，用于更新比对）
--   · source = 来源类型（BUILTIN/LOCAL）
--   · installedAtMs = 安装时间（毫秒）
CREATE TABLE IF NOT EXISTS skill_state (
    id TEXT NOT NULL PRIMARY KEY,
    enabled INTEGER NOT NULL,
    version TEXT NOT NULL,
    source TEXT NOT NULL,
    installedAtMs INTEGER NOT NULL
);
