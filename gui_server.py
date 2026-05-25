import subprocess
import sys
import time
import os
import re
from pathlib import Path

from fastapi import FastAPI, Query
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from gui_state import (
    BASE_DIR,
    RUN_LOG_PATH,
    append_log,
    clear_log,
    read_control,
    read_logs,
    read_rules,
    read_status,
    reset_state,
    update_status,
    write_control,
    write_rules,
)


app = FastAPI()
WEB_DIR = BASE_DIR / "web"
SCRIPT_PATH = BASE_DIR / "淘金币任务.py"
process = None

if WEB_DIR.exists():
    app.mount("/web", StaticFiles(directory=str(WEB_DIR)), name="web")


def process_running():
    return process is not None and process.poll() is None


def read_script_version():
    try:
        text = SCRIPT_PATH.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""
    match = re.search(r'^VERSION\s*=\s*"([^"]+)"', text, re.MULTILINE)
    return match.group(1) if match else ""


@app.get("/")
def index():
    return FileResponse(WEB_DIR / "index.html")


@app.post("/api/start")
def start_task():
    global process
    if process_running():
        return {"ok": True, "status": "running", "message": "任务已在运行"}

    reset_state()
    control = read_control()
    update_status(
        running=True,
        paused=False,
        action="starting",
        version=read_script_version(),
        exclude_tags=control.get("exclude_tags", []),
        last_error=None,
    )
    append_log("启动淘金币任务")
    RUN_LOG_PATH.parent.mkdir(exist_ok=True)
    log_file = RUN_LOG_PATH.open("a", encoding="utf-8", buffering=1)
    env = os.environ.copy()
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUTF8"] = "1"
    process = subprocess.Popen(
        [sys.executable, "-u", str(SCRIPT_PATH)],
        cwd=str(BASE_DIR),
        stdout=log_file,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=env,
    )
    return {"ok": True, "status": "running", "pid": process.pid}


@app.post("/api/stop")
def stop_task():
    global process
    write_control(stop=True)
    update_status(action="stopping")
    append_log("请求停止任务")
    if process_running():
        deadline = time.time() + 5
        while time.time() < deadline:
            if not process_running():
                break
            time.sleep(0.2)
        if process_running():
            append_log("进程未及时退出，强制结束")
            process.kill()
            process.wait(timeout=5)
    update_status(running=False, paused=False, action="idle")
    return {"ok": True, "status": "stopped"}


@app.post("/api/pause")
def pause_task():
    write_control(pause=True)
    update_status(paused=True, action="paused")
    append_log("请求暂停任务")
    return {"ok": True, "status": "paused"}


@app.post("/api/resume")
def resume_task():
    write_control(pause=False)
    update_status(paused=False, action="idle")
    append_log("请求继续任务")
    return {"ok": True, "status": "running" if process_running() else "stopped"}


@app.get("/api/status")
def status():
    data = read_status()
    control = read_control()
    data["running"] = process_running()
    data["version"] = data.get("version") or read_script_version()
    data["exclude_tags"] = control.get("exclude_tags", [])
    return data


@app.get("/api/logs")
def logs(limit: int = Query(default=100, ge=1, le=1000)):
    return {"logs": read_logs(limit)}


@app.post("/api/logs/clear")
def clear_logs():
    clear_log()
    append_log("日志已清除")
    return {"ok": True}


@app.get("/api/control")
def control():
    return read_control()


@app.post("/api/exclude-tags")
def update_exclude_tags(payload: dict):
    raw_tags = payload.get("exclude_tags", [])
    if isinstance(raw_tags, str):
        tags = [item.strip() for item in re.split(r"[,，\s]+", raw_tags) if item.strip()]
    elif isinstance(raw_tags, list):
        tags = [str(item).strip() for item in raw_tags if str(item).strip()]
    else:
        tags = []
    write_control(exclude_tags=tags)
    update_status(exclude_tags=tags)
    append_log(f"更新排除任务标签: {', '.join(tags) if tags else '无'}")
    return {"ok": True, "exclude_tags": tags}


@app.get("/api/rules")
def rules():
    return read_rules()


@app.post("/api/rules")
def update_rules(payload: dict):
    rules = write_rules(payload)
    if "skip_task_words" in rules:
        write_control(exclude_tags=rules.get("skip_task_words", []))
        update_status(exclude_tags=rules.get("skip_task_words", []))
    append_log("更新文字匹配规则")
    return {"ok": True, "rules": rules}
