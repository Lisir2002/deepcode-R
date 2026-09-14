#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
release-log.py — MiniMe-core 三层发版日志自动生成器。

依据 AGENTS.md「版本日志（发版必做 · 三层写法规约）」，从同一份 Conventional Commits
来源（git log <prev_tag>..<cur_tag>）按受众渲染成三层不同语体：

  --layer user   用户层：叙事、价值导向、无内部术语（供 GitHub Release 正文）
  --layer dev    开发者层：Keep a Changelog 六类（Added/Changed/Deprecated/Removed/Fixed/Security）
  --layer ai     大模型层：结构化、机器可解析，聚焦 AI 工作流影响（工具/prompt/schema/接口）

用法（仓库根执行）：
  python3 scripts/gitops/release-log.py --layer user            # 用默认 cur=HEAD，prev=最近 tag
  python3 scripts/gitops/release-log.py --layer dev --prev v0.0.0.1 --cur v0.0.0.2
  python3 scripts/gitops/release-log.py --layer ai --date 2026-09-14

可通过 PATH 环境变量 `GITOPS_REPO` 覆盖仓库根（默认 git rev-parse --show-toplevel）。

设计约束：
  - 仅为开发者层辅助草稿，永不替代人工复核：Breaking 判定、文案价值化润色仍需人工完成。
  - 用户层/大模型层的字体措辞由脚本按规则生成，可被人工在发布说明/AGENTS.md 中修订。
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from datetime import date
from pathlib import Path

# ---- Conventional Commits ----
TYPE_REGEX = re.compile(
    r"^(?P<type>[a-zA-Z]+)(?:\((?P<scope>[A-Za-z0-9._-]+)\))?"
    r"(?P<breaking>!)?:\s*(?P<subject>.+)$"
)

# Conventional type -> 开发者层 Keep a Changelog 分类
DEV_MAP = {
    "feat": "Added",
    "fix": "Fixed",
    "perf": "Changed",
    "refactor": "Changed",
    "chore": "Changed",
    "build": "Changed",
    "deps": "Changed",
    "docs": "Changed",
    "ci": None,          # 纯 CI 噪音，不进开发者层
    "test": None,        # 纯测试噪音
    "style": None,       # 纯格式噪音
}

# 用户层会更细：feat->新功能，fix->修复，perf->改进，breaking 提到亮点
USER_EXCLUDE = {"ci", "test", "style", "docs", "chore", "build"}

# AI 层：与 AI 工作流强相关，重点标注
AI_RELEVANT_TYPES = {"feat", "fix", "refactor", "perf"}
BREAKING_MARKERS = ("BREAKING CHANGE", "BREAKING-CHANGE", "breaking change")
AI_HINT_PATTERNS = (
    "tool", "工具", "prompt", "提示词", "schema", "mcp", "协议",
    "interface", "接口", "参数", "迁移", "database", "数据库",
)


def run(cmd, repo):
    """在仓库根执行命令并返回 stdout，异常时抛错。"""
    return subprocess.run(
        cmd, cwd=str(repo), capture_output=True, text=True, check=True
    ).stdout.strip()


def get_commits(repo, prev, cur):
    """取 <prev>..<cur> 之间的提交，解析为结构体列表。"""
    rng = f"{prev}..{cur}"
    raw = run(
        ["git", "log", rng, "--pretty=format:%H%x1f%s%x1f%b%x1e"], repo
    )
    commits = []
    if not raw:
        return commits
    for sha, subject, body in _parse_raw(raw):
        m = TYPE_REGEX.match(subject)
        if not m:
            commits.append({
                "sha": sha[:8], "type": None, "scope": None,
                "subject": subject, "body": body, "breaking": False,
            })
            continue
        type_ = m.group("type").lower()
        body_lower = body.lower()
        breaking = bool(m.group("breaking")) or any(
            mk.lower() in body_lower for mk in BREAKING_MARKERS
        )
        commits.append({
            "sha": sha[:8], "type": type_, "scope": m.group("scope"),
            "subject": m.group("subject").strip(), "body": body,
            "breaking": breaking,
        })
    return commits


def _parse_raw(raw):
    """把 '%H%x1f%s%x1f%b%x1e' 输出切成 (sha, subject, body) 元组。"""
    for block in raw.split("\x1e"):
        block = block.strip("\n")
        if not block:
            continue
        parts = block.split("\x1f")
        sha = parts[0].strip()
        if len(parts) >= 3:
            yield sha[:8], parts[1], parts[2]
        elif len(parts) == 2:
            yield sha[:8], parts[1], ""
        else:
            yield sha[:8], parts[0] if len(parts) == 1 else "", ""


def format_entry(c):
    """开发者层单条：`- [scope] subject (short-sha)`，breaking 加 ⚠️。"""
    scope = f"`[{c['scope']}]` " if c["scope"] else ""
    marker = "⚠️ " if c["breaking"] else ""
    return f"- {marker}{scope}{c['subject']} ({c['sha']})"


def dev_layer(commits, version, date_str, repo):
    """第 2 层：Keep a Changelog 六类。"""
    buckets = {k: [] for k in DEV_MAP.values() if k}
    other = []
    for c in commits:
        cat = DEV_MAP.get(c["type"])
        if c["type"] is None:
            other.append(format_entry(c))
        elif cat is None:
            continue  # 过滤纯 CI/test/style 噪音
        else:
            buckets[cat].append(format_entry(c))

    lines = [f"## [{version}] - {date_str}", ""]
    order = ["Added", "Changed", "Deprecated", "Removed", "Fixed", "Security"]
    for cat in order:
        entry = buckets.get(cat, [])
        if entry:
            lines.append(f"### {cat}")
            lines.append("")
            lines.extend(entry)
            lines.append("")
    if other:
        lines.append("### Unclassified（待归类）")
        lines.append("")
        lines.extend(other)
        lines.append("")
    lines.append(
        f"**Full Changelog**: {_compare_url(repo, version)}"
    )
    return "\n".join(lines)


def user_layer(commits, version, date_str):
    """第 1 层：给用户看的价值导向文案。"""
    by_type = {}
    breaking = []
    for c in commits:
        if c["breaking"]:
            breaking.append(c)
        if c["type"]:
            by_type.setdefault(c["type"], []).append(c)

    lines = [f"# MiniMe-core {version}（{date_str}）", ""]
    if breaking:
        lines.append("## 亮点 Highlights")
        lines.append("")
        lines.extend(f"- ⚠️ {c['subject']}" for c in breaking)
        lines.append("")
    for cat, label in (("feat", "新功能 New Features"), ("perf", "改进 Improvements"),
                       ("fix", "修复 Fixes"), ("other", "其他 Other")):
        items = by_type.get(cat, [])
        if not items and cat != "other":
            continue
        if cat == "other":
            used = {"feat", "perf", "fix", *USER_EXCLUDE}
            items = [c for c in commits if c["type"] and c["type"] not in used]
        if not items:
            continue
        lines.append(f"## {label}")
        lines.append("")
        lines.extend(f"- {c['subject']}" for c in items)
        lines.append("")
    lines.append("## 已知问题 Known Issues")
    lines.append("")
    lines.append("_（如需，可在发版前补充已知问题列表）_")
    return "\n".join(lines)


def _changed_paths(repo, prev, cur):
    """返回 <prev>..<cur> 变更的文件路径（用于 AI 层推断影响面）。"""
    try:
        out = run(["git", "diff", "--name-only", f"{prev}..{cur}"], repo)
    except Exception:
        return ""
    return out


def ai_layer(commits, version, date_str, changed_paths):
    """第 3 层：给大模型看的结构化、机器可解析摘要。"""
    relevant = [
        c for c in commits
        if c["type"] in AI_RELEVANT_TYPES or c["breaking"]
    ]
    ai_rel = [c for c in relevant if any(p in c["subject"] for p in AI_HINT_PATTERNS)]
    schema_like = [
        p for p in changed_paths.splitlines()
        if any(s in p for s in
               (".sq", "sqldelight", "datalayer", "migration", "database"))
    ]
    tools_like = [
        p for p in changed_paths.splitlines()
        if any(s in p for s in ("prompts/", "Tool", "tool/", "mcp"))
    ]

    lines = [f"## {version}（{date_str}）", ""]
    lines.append("### TYPE")
    lines.append("")
    lines.append(f"- 发布范围: {len(commits)} 提交（含 breaking {sum(1 for c in commits if c['breaking'])}）")
    lines.append("")
    lines.append("### DATA / SCHEMA")
    lines.append("")
    if schema_like:
        lines.append("- ⚠️ 涉及数据/schema 变更，需检查迁移链与 `V1toV2FullMigrator`：")
        lines.extend(f"  - `{p}`" for p in schema_like)
    else:
        lines.append("- 无 schema / 数据层变更（显式声明）。")
    lines.append("")
    lines.append("### TOOLS & PROMPTS")
    lines.append("")
    if tools_like:
        lines.append("- ⚠️ 涉及工具/提示词变更，需同步 `app/src/main/assets/prompts/`：")
        lines.extend(f"  - `{p}`" for p in tools_like)
    elif ai_rel:
        lines.append("- 未命中提示词/工具路径，但存在 AI 工作流相关主题提交，建议人工复核：")
        lines.extend(f"  - {format_entry(c)}" for c in ai_rel)
    else:
        lines.append("- 无工具/提示词变更。")
    lines.append("")
    lines.append("### COMPATIBILITY")
    lines.append("")
    if any(c["breaking"] for c in commits):
        lines.append("- ⚠️ 含 Breaking Change，须在开发者层标注迁移说明。")
    else:
        lines.append("- 无 Breaking Change。")
    lines.append("")
    lines.append("### AI ACTION")
    lines.append("")
    lines.append("- 发版前核对 schema/tool 变更是否已同步 assets/prompts 与迁移链；")
    lines.append("- 无 schema 变化时保持本条显式声明，防误判版本号触发迁移。")
    return "\n".join(lines)


def _compare_url(repo, version):
    try:
        remote = run(["git", "remote", "get-url", "origin"], repo)
    except Exception:
        return "（remote 不可用）"
    # 归一化 git@github.com:owner/repo.git 或 https://github.com/owner/repo
    github = re.search(r"github\.com[:/]([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?$", remote)
    return f"https://github.com/{github.group(1)}/compare/{version}"


def main():
    parser = argparse.ArgumentParser(
        description="MiniMe-core 三层发版日志生成器（见 AGENTS.md「版本日志」）"
    )
    parser.add_argument("--layer", choices=["user", "dev", "ai"], required=True,
                        help="输出哪一层：user=GitHub Release / dev=CHANGELOG / ai=AGENTS 结构化")
    parser.add_argument("--prev", default=None, help="起始 tag（默认取最近一个 tag 或 HEAD 之外）")
    parser.add_argument("--cur", default="HEAD", help="结束 tag/提交（默认 HEAD）")
    parser.add_argument("--date", default=None, help="发布日期 YYYY-MM-DD（默认今天）")
    parser.add_argument("--version", default=None, help="版本号（默认 cur tag 去 v 前缀）")
    parser.add_argument("--repo", default=None, help="仓库根（默认 git 自动探测）")
    args = parser.parse_args()

    repo = Path(args.repo) if args.repo else \
        Path(run(["git", "rev-parse", "--show-toplevel"], Path.cwd()))

    # 解析 prev=最近 tag（若未给）
    prev = args.prev
    if not prev:
        tags = run(["git", "tag", "--sort=-creatordate"], repo).splitlines()
        tags = [t for t in tags if t != args.cur]
        prev = tags[0] if tags else None

    # 解析版本号
    version = args.version.lstrip("v") if args.version else None
    if not version:
        if args.cur != "HEAD":
            version = str(args.cur).lstrip("v")
        else:
            version = "Unreleased"

    date_str = args.date or date.today().isoformat()

    changed_paths = ""
    if prev is None:
        # 无任何历史 tag：只能列出 HEAD 最近的若干提交
        commits = _recent(repo, 30)
    else:
        commits = get_commits(repo, prev, args.cur)
        changed_paths = _changed_paths(repo, prev, args.cur)

    if args.layer == "user":
        print(user_layer(commits, version, date_str))
    elif args.layer == "dev":
        print(dev_layer(commits, version, date_str, repo))
    else:
        print(ai_layer(commits, version, date_str, changed_paths))


def _recent(repo, n):
    """无历史 tag 时的兜底：取最近 n 条提交。"""
    raw = run(
        ["git", "log", "--max-count", str(n), "--pretty=format:%H%x1f%s%x1f%b%x1e"],
        repo,
    )
    commits = []
    for sha, subject, body in _parse_raw(raw):
        m = TYPE_REGEX.match(subject)
        commits.append({
            "sha": sha[:8],
            "type": m.group("type").lower() if m else None,
            "scope": m.group("scope") if m else None,
            "subject": (m.group("subject").strip() if m else subject),
            "body": body,
            "breaking": bool(m.group("breaking")) if m else False,
        })
    return commits


if __name__ == "__main__":
    main()