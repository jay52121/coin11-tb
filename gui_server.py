import subprocess
import sys
import time
import os
import re
import threading
import platform
import shutil
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
    read_coin_records,
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
process_log_file = None
caffeinate_process = None
batch_users = []
batch_mode = "taojinbi"
batch_cancelled = False
process_lock = threading.RLock()
server_restarting = False
SUPPORTED_ANDROID_USERS = ("0", "999")

if WEB_DIR.exists():
    app.mount("/web", StaticFiles(directory=str(WEB_DIR)), name="web")


def process_running():
    return process is not None and process.poll() is None


def build_user_queue(selected_user, run_all):
    selected_user = str(selected_user)
    if selected_user not in SUPPORTED_ANDROID_USERS:
        raise ValueError(f"不支持的Android用户: {selected_user}")
    if not run_all:
        return [selected_user]
    selected_index = SUPPORTED_ANDROID_USERS.index(selected_user)
    return list(SUPPORTED_ANDROID_USERS[selected_index:])


def read_script_version():
    version = str(read_rules().get("app_version", "")).strip()
    if version:
        return version
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


def start_caffeinate():
    global caffeinate_process
    if platform.system() != "Darwin" or caffeinate_process is not None:
        return
    command = shutil.which("caffeinate")
    if not command:
        append_log("Mac防休眠不可用：未找到caffeinate")
        return
    caffeinate_process = subprocess.Popen([command, "-i", "-w", str(os.getpid())])
    append_log("Mac防休眠已开启")


def stop_caffeinate():
    global caffeinate_process
    current = caffeinate_process
    caffeinate_process = None
    if current is None or current.poll() is not None:
        return
    current.terminate()
    try:
        current.wait(timeout=3)
    except subprocess.TimeoutExpired:
        current.kill()
        current.wait(timeout=3)
    append_log("任务已结束，Mac防休眠已关闭")


def launch_user_process(source, mode, user_id, previous_user_id=None):
    global process, process_log_file
    control = read_control()
    rules = read_rules()
    update_status(android_user_id=user_id, action="starting")
    append_log(f"{source}启动{'做体力任务' if mode == 'energy' else '淘金币任务'} user {user_id}")
    append_key_log(f"开始运行 user {user_id}")
    RUN_LOG_PATH.parent.mkdir(exist_ok=True)
    process_log_file = RUN_LOG_PATH.open("a", encoding="utf-8", buffering=1)
    env = os.environ.copy()
    env["PYTHONIOENCODING"] = "utf-8"
    env["PYTHONUTF8"] = "1"
    env["TJB_TASK_MODE"] = mode
    env["TJB_ANDROID_USER_ID"] = user_id
    if previous_user_id is not None:
        env["TJB_PREVIOUS_ANDROID_USER_ID"] = str(previous_user_id)
    env["TJB_ALLOW_DAILY_VERSION_FALLBACK"] = "1" if rules.get("allow_daily_version_fallback", False) else "0"
    env["TJB_ENABLE_JUMP_ENERGY"] = "1" if rules.get("enable_jump_energy", True) else "0"
    process = subprocess.Popen(
        [sys.executable, "-u", str(SCRIPT_PATH)], cwd=str(BASE_DIR),
        stdout=process_log_file, stderr=subprocess.STDOUT, text=True,
        encoding="utf-8", errors="replace", env=env,
    )
    threading.Thread(target=watch_task_process, args=(process, user_id), daemon=True).start()


def watch_task_process(watched_process, user_id):
    global process, process_log_file
    exit_code = watched_process.wait()
    with process_lock:
        if process is not watched_process:
            return
        if process_log_file is not None:
            process_log_file.close()
            process_log_file = None
        process = None
        append_log(f"user {user_id} 运行结束，退出码 {exit_code}")
        if not batch_cancelled and batch_users:
            next_user = batch_users.pop(0)
            launch_user_process("连续运行：", batch_mode, next_user, previous_user_id=user_id)
            return
        update_status(running=False, paused=False, action="idle")
        stop_caffeinate()
        append_key_log("全部用户运行结束" if not batch_cancelled else "运行已停止")


def start_task_process(source="api", mode="taojinbi", run_all_users=None, user_id=None):
    global batch_users, batch_mode, batch_cancelled
    if process_running():
        return {"ok": True, "status": "running", "message": "任务已在运行"}

    clear_log()
    clear_key_log()
    reset_state()
    control = read_control()
    selected_user = str(user_id if user_id is not None else control.get("android_user_id", "0"))
    if selected_user not in SUPPORTED_ANDROID_USERS:
        return {"ok": False, "error": f"不支持的Android用户: {selected_user}"}
    run_all = bool(control.get("run_all_users", False)) if run_all_users is None else bool(run_all_users)
    control = write_control(android_user_id=selected_user, run_all_users=run_all)
    rules = read_rules()
    active_tags = rules.get("energy_exclude_tags", []) if mode == "energy" else rules.get("coin_exclude_tags", [])
    if not active_tags:
        active_tags = rules.get("exclude_tags", [])
    update_status(
        running=True,
        paused=False,
        action="starting",
        task_mode=mode,
        version=read_script_version(),
        exclude_tags=active_tags,
        coin_exclude_tags=rules.get("coin_exclude_tags", []),
        energy_exclude_tags=rules.get("energy_exclude_tags", []),
        android_user_id=selected_user,
        run_all_users=run_all,
        allow_daily_version_fallback=bool(rules.get("allow_daily_version_fallback", False)),
        enable_jump_energy=bool(rules.get("enable_jump_energy", True)),
        last_error=None,
    )
    users = build_user_queue(selected_user, run_all)
    batch_users = users[1:]
    batch_mode = mode
    batch_cancelled = False
    append_log(f"本次用户队列: {users}")
    append_key_log(f"本次用户队列: {' -> '.join(users)}")
    start_caffeinate()
    try:
        launch_user_process(source, mode, users[0])
    except Exception:
        batch_users = []
        batch_cancelled = True
        stop_caffeinate()
        update_status(running=False, paused=False, action="idle")
        raise
    return {"ok": True, "status": "running", "pid": process.pid}


def stop_task_process_for_service_restart():
    global process, batch_cancelled, batch_users
    batch_cancelled = True
    batch_users = []
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
    stop_caffeinate()


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
        update_status(running=False, paused=False, action="idle", version=read_script_version())
        return
    start_task_process("服务启动后自动")


@app.post("/api/start")
def start_task(run_all_users: bool = Query(default=False), android_user_id: str = Query(default="0")):
    return start_task_process("手动", run_all_users=run_all_users, user_id=android_user_id)


@app.post("/api/start-energy")
def start_energy_task(run_all_users: bool = Query(default=False), android_user_id: str = Query(default="0")):
    return start_task_process("手动", mode="energy", run_all_users=run_all_users, user_id=android_user_id)


@app.post("/api/stop")
def stop_task():
    global process, batch_cancelled, batch_users
    batch_cancelled = True
    batch_users = []
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
    stop_caffeinate()
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
    rules = read_rules()
    running = process_running()
    data["running"] = running
    running_version = str(data.get("version") or "").strip()
    rule_version = read_script_version()
    data["rule_version"] = rule_version
    data["version"] = running_version or rule_version
    data["restart_required"] = bool(running_version and rule_version and running_version != rule_version)
    data["restart_hint"] = f"待启动版本 {rule_version}" if data["restart_required"] else ""
    data["task_mode"] = data.get("task_mode") or "unknown"
    data["coin_exclude_tags"] = rules.get("coin_exclude_tags", [])
    data["energy_exclude_tags"] = rules.get("energy_exclude_tags", [])
    if not running:
        data["android_user_id"] = str(control.get("android_user_id", "0"))
    data["run_all_users"] = bool(control.get("run_all_users", False))
    data["remaining_users"] = list(batch_users) if running else []
    data["allow_daily_version_fallback"] = bool(rules.get("allow_daily_version_fallback", False))
    data["enable_jump_energy"] = bool(rules.get("enable_jump_energy", True))
    data["exclude_tags"] = data.get("exclude_tags") or rules.get("coin_exclude_tags", []) or rules.get("exclude_tags", [])
    return data


@app.get("/api/logs")
def logs(limit: int = Query(default=100, ge=1, le=1000), mode: str = Query(default="detail")):
    if mode == "key":
        return {"logs": read_key_logs(limit)}
    return {"logs": read_logs(limit)}


@app.get("/api/taojinbi-records")
def taojinbi_records():
    return read_coin_records()


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
    if "run_all_users" in payload:
        updates["run_all_users"] = bool(payload.get("run_all_users"))
    control = write_control(**updates)
    rule_updates = {}
    if "allow_daily_version_fallback" in payload:
        rule_updates["allow_daily_version_fallback"] = bool(payload.get("allow_daily_version_fallback"))
    if "enable_jump_energy" in payload:
        rule_updates["enable_jump_energy"] = bool(payload.get("enable_jump_energy"))
    rules = write_rules({**read_rules(), **rule_updates}) if rule_updates else read_rules()
    update_status(
        android_user_id=str(control.get("android_user_id", "0")),
        run_all_users=bool(control.get("run_all_users", False)),
        allow_daily_version_fallback=bool(rules.get("allow_daily_version_fallback", False)),
        enable_jump_energy=bool(rules.get("enable_jump_energy", True)),
    )
    if "android_user_id" in updates:
        append_log(f"更新Android用户: {control.get('android_user_id', '0')}")
    if "run_all_users" in updates:
        append_log(f"更新连续运行全部用户: {'开启' if control.get('run_all_users') else '关闭'}")
    if "allow_daily_version_fallback" in rule_updates:
        append_log(f"更新回日常版兜底: {'开启' if rules.get('allow_daily_version_fallback', False) else '关闭'}")
    if "enable_jump_energy" in rule_updates:
        append_log(f"更新跳一跳: {'开启' if rules.get('enable_jump_energy', True) else '关闭'}")
    return {
        "ok": True,
        "android_user_id": str(control.get("android_user_id", "0")),
        "run_all_users": bool(control.get("run_all_users", False)),
        "allow_daily_version_fallback": bool(rules.get("allow_daily_version_fallback", False)),
        "enable_jump_energy": bool(rules.get("enable_jump_energy", True)),
    }


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
    rules = write_rules({**read_rules(), **updates})
    update_status(
        exclude_tags=rules.get("coin_exclude_tags", []),
        coin_exclude_tags=rules.get("coin_exclude_tags", []),
        energy_exclude_tags=rules.get("energy_exclude_tags", []),
    )
    append_log(
        "更新排除任务标签: "
        f"金币={', '.join(rules.get('coin_exclude_tags', [])) or '无'}; "
        f"体力={', '.join(rules.get('energy_exclude_tags', [])) or '无'}"
    )
    return {
        "ok": True,
        "coin_exclude_tags": rules.get("coin_exclude_tags", []),
        "energy_exclude_tags": rules.get("energy_exclude_tags", []),
    }


@app.get("/api/rules")
def rules():
    return read_rules()


@app.post("/api/rules")
def update_rules(payload: dict):
    rules = write_rules(payload)
    append_log("更新文字匹配规则")
    return {"ok": True, "rules": rules}
