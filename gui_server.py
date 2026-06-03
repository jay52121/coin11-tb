import subprocess
import sys
import time
import os
import re
import threading
from pathlib import Path

from fastapi import BackgroundTasks, FastAPI, Query
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from gui_state import (
    BASE_DIR,
    RUN_LOG_PATH,
    append_key_log,
    append_log,
    clear_key_log,
    clear_log,
    read_control,
    read_logs,
    read_key_logs,
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
server_restarting = False

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
    return FileResponse(WEB_DIR / "index.html", headers={"Cache-Control": "no-store, max-age=0"})


@app.middleware("http")
async def no_cache_static(request, call_next):
    response = await call_next(request)
    if request.url.path == "/" or request.url.path.startswith("/web/"):
        response.headers["Cache-Control"] = "no-store, max-age=0"
    return response


def start_task_process(source="api", mode="taojinbi"):
    global process
    if process_running():
        return {"ok": True, "status": "running", "message": "任务已在运行"}

    reset_state()
    control = read_control()
    active_tags = control.get("energy_exclude_tags", []) if mode == "energy" else control.get("coin_exclude_tags", [])
    if not active_tags:
        active_tags = control.get("exclude_tags", [])
    update_status(
        running=True,
        paused=False,
        action="starting",
        task_mode=mode,
        version=read_script_version(),
        exclude_tags=active_tags,
        coin_exclude_tags=control.get("coin_exclude_tags", []),
        energy_exclude_tags=control.get("energy_exclude_tags", []),
        android_user_id=str(control.get("android_user_id", "0")),
        last_error=None,
    )
    task_name = "做体力任务" if mode == "energy" else "淘金币任务"
    append_log(f"{source}启动{task_name}")
    append_key_log(f"{source}启动{task_name}")
    RUN_LOG_PATH.parent.mkdir(exist_ok=True)
    log_file = RUN_LOG_PATH.open("a", encoding="utf-8", buffering=1)
    env = os.environ.copy()
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUTF8"] = "1"
    env["TJB_TASK_MODE"] = mode
    env["TJB_ANDROID_USER_ID"] = str(control.get("android_user_id", "0"))
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


def stop_task_process_for_service_restart():
    global process
    write_control(stop=True)
    update_status(action="stopping")
    append_log("服务重启：请求停止任务")
    append_key_log("服务重启：请求停止任务")
    if process_running():
        deadline = time.time() + 4
        while time.time() < deadline:
            if not process_running():
                break
            time.sleep(0.2)
        if process_running():
            append_log("服务重启：任务未及时退出，强制结束")
            process.kill()
            process.wait(timeout=5)
    update_status(running=False, paused=False, action="idle")


def restart_service_worker():
    time.sleep(0.4)
    try:
        stop_task_process_for_service_restart()
    except Exception as exc:
        append_log(f"服务重启：停止任务失败 {exc}")
    append_log("服务重启：退出当前服务，等待启动脚本重新拉起")
    os._exit(23)


@app.on_event("startup")
def auto_start_task():
    if os.environ.get("TJB_DISABLE_AUTO_START") == "1":
        append_log("服务启动：跳过自动启动任务")
        update_status(running=False, paused=False, action="idle")
        return
    start_task_process("服务启动后自动")


@app.post("/api/start")
def start_task():
    return start_task_process("手动")


@app.post("/api/start-energy")
def start_energy_task():
    return start_task_process("手动", mode="energy")


@app.post("/api/stop")
def stop_task():
    global process
    write_control(stop=True)
    update_status(action="stopping")
    append_log("请求停止任务")
    append_key_log("请求停止任务")
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


@app.post("/api/service/restart")
def restart_service(background_tasks: BackgroundTasks):
    global server_restarting
    if server_restarting:
        return {"ok": True, "status": "restarting"}
    server_restarting = True
    append_log("请求重启GUI服务")
    append_key_log("请求重启GUI服务")
    background_tasks.add_task(restart_service_worker)
    return {"ok": True, "status": "restarting"}


@app.get("/api/status")
def status():
    data = read_status()
    control = read_control()
    data["running"] = process_running()
    script_version = read_script_version()
    data["version"] = script_version
    data["task_mode"] = data.get("task_mode") or "unknown"
    data["coin_exclude_tags"] = control.get("coin_exclude_tags", [])
    data["energy_exclude_tags"] = control.get("energy_exclude_tags", [])
    data["android_user_id"] = str(control.get("android_user_id", "0"))
    data["exclude_tags"] = data.get("exclude_tags") or control.get("coin_exclude_tags", []) or control.get("exclude_tags", [])
    if script_version:
        update_status(version=script_version)
    return data


@app.get("/api/logs")
def logs(limit: int = Query(default=100, ge=1, le=1000), mode: str = Query(default="detail")):
    if mode == "key":
        return {"logs": read_key_logs(limit)}
    return {"logs": read_logs(limit)}


@app.post("/api/logs/clear")
def clear_logs(mode: str = Query(default="detail")):
    if mode == "key":
        clear_key_log()
        append_key_log("关键日志已清除")
    else:
        clear_log()
        append_log("日志已清除")
    return {"ok": True}


@app.get("/api/control")
def control():
    return read_control()


@app.post("/api/control")
def update_control(payload: dict):
    updates = {}
    if "android_user_id" in payload:
        user_id = str(payload.get("android_user_id", "0")).strip() or "0"
        if user_id not in {"0", "999"}:
            return {"ok": False, "error": "android_user_id must be 0 or 999"}
        updates["android_user_id"] = user_id
    control = write_control(**updates)
    update_status(android_user_id=str(control.get("android_user_id", "0")))
    append_log(f"更新Android用户: {control.get('android_user_id', '0')}")
    return {"ok": True, "android_user_id": str(control.get("android_user_id", "0"))}


@app.post("/api/exclude-tags")
def update_exclude_tags(payload: dict):
    def parse_tags(value):
        if isinstance(value, str):
            return [item.strip() for item in re.split(r"[,，\s]+", value) if item.strip()]
        if isinstance(value, list):
            return [str(item).strip() for item in value if str(item).strip()]
        return []

    coin_tags = parse_tags(payload.get("coin_exclude_tags", payload.get("exclude_tags", [])))
    energy_tags = parse_tags(payload.get("energy_exclude_tags", []))
    updates = {}
    if "coin_exclude_tags" in payload or "exclude_tags" in payload:
        updates["coin_exclude_tags"] = coin_tags
        updates["exclude_tags"] = coin_tags
    if "energy_exclude_tags" in payload:
        updates["energy_exclude_tags"] = energy_tags
    control = write_control(**updates)
    update_status(
        exclude_tags=control.get("coin_exclude_tags", []),
        coin_exclude_tags=control.get("coin_exclude_tags", []),
        energy_exclude_tags=control.get("energy_exclude_tags", []),
    )
    append_log(
        "更新排除任务标签: "
        f"金币={', '.join(control.get('coin_exclude_tags', [])) or '无'}; "
        f"体力={', '.join(control.get('energy_exclude_tags', [])) or '无'}"
    )
    return {
        "ok": True,
        "coin_exclude_tags": control.get("coin_exclude_tags", []),
        "energy_exclude_tags": control.get("energy_exclude_tags", []),
    }


@app.get("/api/rules")
def rules():
    return read_rules()


@app.post("/api/rules")
def update_rules(payload: dict):
    rules = write_rules(payload)
    append_log("更新文字匹配规则")
    return {"ok": True, "rules": rules}
