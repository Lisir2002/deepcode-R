#!/usr/bin/env python3
"""
R-DeepCode 实时 CI/CD 监控器
实时轮询 GitHub Actions，展示构建进度、失败告警、产物信息。

用法:
  python3 ci_monitor.py                    # 监控最新一次 run
  python3 ci_monitor.py --run <run_id>     # 监控指定 run
  python3 ci_monitor.py --watch            # 持续监控（按 Ctrl+C 退出）
  python3 ci_monitor.py --release          # 监控最新 release 构建
"""

import argparse
import json
import os
import sys
import time
from datetime import datetime

try:
    import requests
except ImportError:
    print("需要安装 requests: pip install requests")
    sys.exit(1)

# ============ 配置 ============
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "")
REPO_OWNER = "Lisir2002"
REPO_NAME = "deepcode-R"
API_BASE = f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}"

POLL_INTERVAL = 15  # 秒

# ANSI 颜色
COLORS = {
    "reset": "\033[0m",
    "bold": "\033[1m",
    "dim": "\033[2m",
    "red": "\033[91m",
    "green": "\033[92m",
    "yellow": "\033[93m",
    "blue": "\033[94m",
    "cyan": "\033[96m",
    "gray": "\033[90m",
}


def c(text, color):
    return f"{COLORS[color]}{text}{COLORS['reset']}"


def gh_get(path):
    """GitHub API GET 请求"""
    headers = {
        "Accept": "application/vnd.github.v3+json",
    }
    if GITHUB_TOKEN:
        headers["Authorization"] = f"token {GITHUB_TOKEN}"
    resp = requests.get(f"{API_BASE}{path}", headers=headers)
    if resp.status_code == 403 and "rate limit" in resp.text.lower():
        print(c("\n⚠️  GitHub API 速率限制，请稍后再试或配置 GITHUB_TOKEN", "red"))
        sys.exit(1)
    resp.raise_for_status()
    return resp.json()


def get_latest_run(workflow=None):
    """获取最新一次 workflow run"""
    if workflow:
        path = f"/actions/workflows/{workflow}/runs?per_page=1"
    else:
        path = "/actions/runs?per_page=1"
    data = gh_get(path)
    runs = data.get("workflow_runs", [])
    return runs[0] if runs else None


def get_run_jobs(run_id):
    """获取 run 的所有 job 及其步骤"""
    data = gh_get(f"/actions/runs/{run_id}/jobs")
    return data.get("jobs", [])


def status_icon(status, conclusion=None):
    """状态图标"""
    if status == "completed":
        if conclusion == "success":
            return c("✓", "green")
        elif conclusion == "failure":
            return c("✗", "red")
        elif conclusion == "cancelled":
            return c("⊘", "yellow")
        else:
            return c("○", "gray")
    elif status == "in_progress":
        return c("▶", "cyan")
    elif status == "queued":
        return c("⏳", "yellow")
    else:
        return c("○", "gray")


def format_duration(started_at, completed_at=None):
    """格式化耗时"""
    try:
        start = datetime.fromisoformat(started_at.replace("Z", "+00:00"))
        if completed_at:
            end = datetime.fromisoformat(completed_at.replace("Z", "+00:00"))
        else:
            end = datetime.now(start.tzinfo)
        delta = end - start
        mins = int(delta.total_seconds() // 60)
        secs = int(delta.total_seconds() % 60)
        if mins > 0:
            return f"{mins}m{secs:02d}s"
        return f"{secs}s"
    except Exception:
        return "-"


def display_run(run, show_steps=True):
    """展示 run 状态"""
    status = run["status"]
    conclusion = run.get("conclusion", "")
    name = run["name"]
    num = run["run_number"]
    url = run["html_url"]
    branch = run.get("head_branch", "?")

    # 标题行
    icon = status_icon(status, conclusion)
    status_text = conclusion if status == "completed" else status
    status_color = (
        "green" if conclusion == "success"
        else "red" if conclusion == "failure"
        else "yellow" if status == "in_progress"
        else "gray"
    )

    print()
    print(c(f"═══ {name} #{num} ═══", "bold"))
    print(f"  状态: {icon} {c(status_text.upper(), status_color)}")
    print(f"  分支: {branch}")
    print(f"  链接: {c(url, 'blue')}")

    # Jobs & steps
    if show_steps:
        jobs = get_run_jobs(run["id"])
        for job in jobs:
            j_icon = status_icon(job["status"], job.get("conclusion"))
            j_name = job["name"]
            j_status = job["status"]
            j_conc = job.get("conclusion", "")
            steps = job.get("steps", [])
            done = sum(1 for s in steps if s["status"] == "completed")
            total = len(steps)

            dur = format_duration(
                job.get("started_at", ""),
                job.get("completed_at", "")
            )

            print()
            print(f"  {j_icon} {j_name}  [{done}/{total} steps, {dur}]")

            for step in steps:
                s_icon = status_icon(step["status"], step.get("conclusion"))
                s_name = step["name"]
                s_num = step["number"]

                # 当前运行的步骤高亮
                if step["status"] == "in_progress":
                    print(f"      {s_icon} {c(s_name, 'cyan')}")
                elif step["status"] == "completed" and step.get("conclusion") == "failure":
                    print(f"      {s_icon} {c(s_name, 'red')}")
                else:
                    print(f"      {s_icon} {s_name}")

    # 如果完成了，展示产物信息
    if status == "completed" and conclusion == "success":
        print()
        print(c("  📦 产物信息", "bold"))
        # 尝试获取 artifacts
        try:
            artifacts = gh_get(f"/actions/runs/{run['id']}/artifacts")
            for a in artifacts.get("artifacts", []):
                size_mb = a["size_in_bytes"] / 1024 / 1024
                print(f"    • {a['name']} ({size_mb:.1f} MB)")
        except Exception:
            pass

        # 如果是 release 构建，查 release 信息
        if "release" in name.lower():
            try:
                tag = run.get("head_branch", "")
                if tag.startswith("v"):
                    release = gh_get(f"/releases/tags/{tag}")
                    print(f"    🏷️  Release: {release.get('name', tag)}")
                    for asset in release.get("assets", []):
                        size_mb = asset["size"] / 1024 / 1024
                        print(f"    📥 {asset['name']} ({size_mb:.1f} MB)")
                        print(f"       {c(asset['browser_download_url'], 'blue')}")
            except Exception:
                pass

    # 如果失败，展示失败的 step
    if status == "completed" and conclusion == "failure":
        print()
        print(c("  ❌ 失败步骤", "red"))
        jobs = get_run_jobs(run["id"])
        for job in jobs:
            if job.get("conclusion") == "failure":
                for step in job.get("steps", []):
                    if step.get("conclusion") == "failure":
                        print(f"    • {job['name']} → {step['name']}")
                        # 尝试获取日志链接
                        print(f"      日志: {c(run['html_url'], 'blue')}")

    print()


def watch_mode(workflow=None):
    """持续监控模式"""
    print(c("🔍 实时监控模式（按 Ctrl+C 退出）", "bold"))
    print(f"   仓库: {REPO_OWNER}/{REPO_NAME}")
    print(f"   轮询间隔: {POLL_INTERVAL}s")
    print()

    last_status = None
    last_run_id = None

    try:
        while True:
            run = get_latest_run(workflow)
            if not run:
                print(c("  未找到运行中的 workflow", "yellow"))
                time.sleep(POLL_INTERVAL)
                continue

            run_id = run["id"]
            status = run["status"]
            conclusion = run.get("conclusion", "")

            # 状态变化或新 run 时刷新显示
            key = f"{run_id}-{status}-{conclusion}"
            if key != last_status:
                # 清屏
                os.system("clear" if os.name != "nt" else "cls")
                print(c("🔍 实时监控模式（按 Ctrl+C 退出）", "bold"))
                print(f"   最后更新: {datetime.now().strftime('%H:%M:%S')}")
                display_run(run)
                last_status = key

            # 完成后退出
            if status == "completed":
                print(c(f"✅ 构建完成: {conclusion}", "green" if conclusion == "success" else "red"))
                break

            time.sleep(POLL_INTERVAL)

    except KeyboardInterrupt:
        print()
        print(c("👋 监控已停止", "yellow"))


def main():
    parser = argparse.ArgumentParser(description="R-DeepCode CI/CD 实时监控")
    parser.add_argument("--run", type=str, help="监控指定 run ID")
    parser.add_argument("--watch", action="store_true", help="持续监控模式")
    parser.add_argument("--release", action="store_true", help="监控最新 release 构建")
    parser.add_argument("--ci", action="store_true", help="监控最新 CI 构建")
    parser.add_argument("--list", type=int, nargs="?", const=5, help="列出最近 N 次 run")
    args = parser.parse_args()

    if not GITHUB_TOKEN:
        print(c("⚠️  未设置 GITHUB_TOKEN，API 速率受限（60次/小时）", "yellow"))
        print("   建议: export GITHUB_TOKEN=your_token")
        print()

    # 列出最近 run
    if args.list is not None:
        print(c(f"📋 最近 {args.list} 次运行", "bold"))
        data = gh_get(f"/actions/runs?per_page={args.list}")
        for r in data.get("workflow_runs", []):
            icon = status_icon(r["status"], r.get("conclusion"))
            name = r.get("name") or "?"
            num = r.get("run_number") or 0
            branch = r.get("head_branch") or "?"
            conc = r.get("conclusion") or r.get("status") or "?"
            t = (r.get("created_at") or "")[:16].replace("T", " ")
            print(f"  {icon} {name:20s} #{num:<4d} {branch:15s} {conc:12s} {t}")
        return

    # 确定监控哪个 workflow
    workflow = None
    if args.release:
        workflow = "android-release.yml"
    elif args.ci:
        workflow = "ci.yml"

    # 指定 run ID
    if args.run:
        run = gh_get(f"/actions/runs/{args.run}")
        if args.watch:
            # 用指定 run 持续监控
            print(c("🔍 实时监控指定 Run（按 Ctrl+C 退出）", "bold"))
            print(f"   Run ID: {args.run}")
            print()
            last_status = None
            try:
                while True:
                    run = gh_get(f"/actions/runs/{args.run}")
                    status = run["status"]
                    conclusion = run.get("conclusion", "")
                    key = f"{status}-{conclusion}"
                    if key != last_status:
                        os.system("clear" if os.name != "nt" else "cls")
                        print(c("🔍 实时监控指定 Run（按 Ctrl+C 退出）", "bold"))
                        print(f"   最后更新: {datetime.now().strftime('%H:%M:%S')}")
                        display_run(run)
                        last_status = key
                    if status == "completed":
                        break
                    time.sleep(POLL_INTERVAL)
            except KeyboardInterrupt:
                print(c("\n👋 监控已停止", "yellow"))
        else:
            display_run(run)
        return

    # 获取最新 run
    if args.watch:
        watch_mode(workflow)
    else:
        run = get_latest_run(workflow)
        if run:
            display_run(run)
        else:
            print("未找到 workflow run")


if __name__ == "__main__":
    main()
