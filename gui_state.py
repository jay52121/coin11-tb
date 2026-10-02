import base64
import hashlib
import json
import os
import shutil
import subprocess
import threading
import time
import urllib.request
from datetime import datetime
from pathlib import Path


BASE_DIR = Path(__file__).resolve().parent
RUNTIME_DIR = BASE_DIR / "runtime"
LOG_DIR = BASE_DIR / "logs"
CONTROL_PATH = RUNTIME_DIR / "control.json"
STATUS_PATH = RUNTIME_DIR / "status.json"
LOCAL_RULES_PATH = RUNTIME_DIR / "rules.json"
DEFAULT_SHARED_RULES_PATH = BASE_DIR.parent / "shared" / "rules.json"
RULES_PATH = Path(os.environ.get("TJB_RULES_PATH", DEFAULT_SHARED_RULES_PATH if DEFAULT_SHARED_RULES_PATH.exists() else LOCAL_RULES_PATH))
RUN_LOG_PATH = LOG_DIR / "run.log"
KEY_LOG_PATH = LOG_DIR / "key.log"
COIN_RECORD_PATH = RUNTIME_DIR / "淘金币记录.json"
EXCLUDE_POOL_CACHE_PATH = RUNTIME_DIR / "exclude-pool.remote.json"
EXCLUDE_POOL_SYNC_STATE_PATH = RUNTIME_DIR / "exclude-pool.sync.json"
REMOTE_EXCLUDE_POOL_URL = os.environ.get(
    "TJB_EXCLUDE_POOL_URL",
    "https://raw.githubusercontent.com/jay52121/coin11-tb/main/cloud/exclude-pool.json",
)
REMOTE_EXCLUDE_POOL_EDIT_URL = (
    "https://github.com/jay52121/coin11-tb/edit/main/cloud/exclude-pool.json"
)
REMOTE_EXCLUDE_POOL_KEYS = (
    "coin_exclude_tags",
    "energy_exclude_tags",
    "skip_task_extra_words",
)

DEFAULT_CONTROL = {
    "stop": False,
    "pause": False,
    "exclude_tags": ["下单", "快手", "评价", "助力"],
    "coin_exclude_tags": ["下单", "快手", "评价", "助力"],
    "energy_exclude_tags": ["下单", "快手", "评价", "助力", "分享", "每拉"],
    "android_user_id": "0",
    "run_all_users": False,
    "allow_daily_version_fallback": False,
    "enable_jump_energy": True,
}

DEFAULT_STATUS = {
    "running": False,
    "paused": False,
    "activity": "",
    "page_type": "unknown",
    "action": "idle",
    "current_task": "",
    "task_mode": "unknown",
    "version": "",
    "exclude_tags": [],
    "coin_exclude_tags": [],
    "energy_exclude_tags": [],
    "android_user_id": "0",
    "run_all_users": False,
    "allow_daily_version_fallback": False,
    "enable_jump_energy": True,
    "taojinbi_coin": None,
    "taojinbi_coin_record": None,
    "last_error": None,
    "updated_at": "",
}

DEFAULT_RULES = {
    "app_version": "mac-refactor-20260605-033156",
    "exclude_tags": ["下单", "快手", "评价", "助力"],
    "coin_exclude_tags": ["下单", "快手", "评价", "助力"],
    "energy_exclude_tags": ["下单", "快手", "评价", "助力", "分享", "每拉"],
    "allow_daily_version_fallback": False,
    "enable_jump_energy": True,
    "action_text_pattern": "去完成|去逛逛|去浏览|逛一逛|立即领|去领取|去看看|搜一下|玩一把|捐一笔|逛一下|点击去逛|领取奖励|立即领取|点击得|爱心捐|去兑换",
    "skip_task_extra_words": [
        "拉好友", "抢红包", "搜索兴趣商品下单", "买精选商品", "全场3元3件", "固定入口",
        "农场小游戏", "砸蛋", "大众点评", "蚂蚁新村", "消消乐", "3元抢3件包邮到家",
        "拍一拍", "1元抢爆款好货", "拉1人助力", "玩消消乐", "下单即得",
        "添加签到神器", "下单得肥料", "88VIP", "邀请好友", "好货限时直降",
        "连连消", "拍立淘", "玩任意游戏", "首页回访", "百亿外卖",
        "玩趣味游戏得大额体力", "头条刷热点", "一淘签到",
        "每拉", "闪购拿大额补贴", "开心消消乐过1关", "通关", "购买商品",
        "去闪购领红包点外卖", "冒险大作战", "斗地主", "买限时折扣好物",
        "趣头条", "(1000/3500)", "任意下单", "农场对对碰匹配", "任意充值",
        "闯关", "消一消", "点击商品领优惠红包", "发评价得金币",
    ],
    "done_words": ["已完成", "已领取", "已得", "任务已完成", "记得明天再来"],
    "search_browse_words": ["搜索后浏览立得奖励", "搜索有福利", "淘宝精选", "搜索发现", "历史搜索"],
    "coin_home_words": ["淘金币首页", "淘金币标题", "可抵", "购物车", "赚金币抵钱", "赚更多金币"],
    "coin_home_task_words": ["今日速赚", "快速赚", "完成下方任务", "更多金币等你赚", "任务到访得金币", "每日来任务面板"],
    "browse_page_words": ["浏览", "浏览25秒", "已得", "累计已得", "累积已得", "直播攒红包", "热销", "爆款", "抵扣", "金币热卖价", "近七天卖出", "已售"],
    "daily_fast_words": ["今日速赚", "快速赚", "今日快速赚奖励已拿完", "记得明天再来"],
    "daily_task_area_words": ["完成下方任务", "更多金币等你赚", "展开", "任务到访得金币", "每日来任务面板", "逛清单", "淘金币趣味课堂", "浏览15秒"],
    "task_list_words": [
        "今日速赚", "快速赚", "今日快速赚奖励已拿完", "记得明天再来",
        "完成下方任务", "更多金币等你赚", "展开", "任务到访得金币",
        "每日来任务面板", "逛清单", "淘金币趣味课堂", "领取奖励", "去完成",
        "去逛逛", "点击去逛",
    ],
    "task_list_bottom_words": ["收起更多任务"],
    "task_done_page_words": ["任务已完成", "已得", "已成功领取奖励", "回到主页"],
    "task_done_exclude_words": ["累计已得", "累积已得"],
    "quiz_words": ["淘金币趣味答题", "我选好了"],
    "shop_subscribe_words": ["订阅+", "已关注", "取消关注", "最多还可以领", "立即领"],
    "shop_subscribe_action_pairs": [
        "最多还可以领.*=>最多还可以领",
        "立即领.*=>立即领",
        "订阅\\s*\\+\\s*\\d+.*=>订阅",
        "进店.*=>进店",
        "已关注.*=>已关注",
        "取消关注.*=>取消关注",
    ],
    "daily_version_words": ["回日常版"],
    "earn_more_words": ["赚更多金币"],
    "earn_words": ["赚金币"],
    "expand_words": ["展开"],
    "next_task_words": ["下个任务", "下一任务"],
    "reward_button_pattern": "领取奖励|立即领取|点击得",
    "ocr_done_text": "任务已完成",
    "ocr_done_extra_words": ["继续逛逛吧"],
}


def ensure_dirs():
    RUNTIME_DIR.mkdir(exist_ok=True)
    LOG_DIR.mkdir(exist_ok=True)


def now_text():
    return time.strftime("%Y-%m-%d %H:%M:%S")


def atomic_write_json(path, data):
    ensure_dirs()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_name(f".{path.name}.{os.getpid()}.{threading.get_ident()}.{time.time_ns()}.tmp")
    with tmp_path.open("w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    os.replace(tmp_path, path)


def _normalize_string_list(value):
    if not isinstance(value, list):
        raise ValueError("exclude pool field must be a list")
    result = []
    seen = set()
    for item in value:
        text = str(item).strip()
        if text and text not in seen:
            result.append(text)
            seen.add(text)
    return result


def _validate_remote_exclude_pool(payload):
    if not isinstance(payload, dict):
        raise ValueError("exclude pool root must be an object")
    if int(payload.get("schema_version", 0)) != 1:
        raise ValueError("unsupported exclude pool schema_version")

    normalized = {
        "schema_version": 1,
        "revision": str(payload.get("revision", "")).strip(),
        "updated_at": str(payload.get("updated_at", "")).strip(),
    }
    for key in REMOTE_EXCLUDE_POOL_KEYS:
        if key in payload:
            normalized[key] = _normalize_string_list(payload.get(key))
    if "coin_exclude_tags" not in normalized:
        raise ValueError("coin_exclude_tags is required")
    if "skip_task_extra_words" not in normalized:
        raise ValueError("skip_task_extra_words is required")
    return normalized


def read_exclude_pool_sync_state():
    state = read_json(EXCLUDE_POOL_SYNC_STATE_PATH, {})
    state.setdefault("source_url", REMOTE_EXCLUDE_POOL_URL)
    state.setdefault("edit_url", REMOTE_EXCLUDE_POOL_EDIT_URL)
    state["cache_exists"] = EXCLUDE_POOL_CACHE_PATH.exists()
    return state


def sync_remote_exclude_pool(force=False, timeout=3.0):
    ensure_dirs()
    checked_at = now_text()
    previous = read_json(EXCLUDE_POOL_SYNC_STATE_PATH, {})
    try:
        request = urllib.request.Request(
            REMOTE_EXCLUDE_POOL_URL,
            headers={
                "User-Agent": "coin11-tb-mac-rule-sync/1",
                "Cache-Control": "no-cache",
            },
        )
        with urllib.request.urlopen(request, timeout=float(timeout)) as response:
            raw = response.read(262144)
        payload = json.loads(raw.decode("utf-8"))
        pool = _validate_remote_exclude_pool(payload)
        digest = hashlib.sha256(raw).hexdigest()
        changed = digest != previous.get("sha256")
        applied = bool(force or changed or not RULES_PATH.exists())

        atomic_write_json(EXCLUDE_POOL_CACHE_PATH, pool)
        if applied:
            current_rules = read_json(RULES_PATH, DEFAULT_RULES)
            for key in REMOTE_EXCLUDE_POOL_KEYS:
                if key in pool:
                    current_rules[key] = pool[key]
            current_rules["exclude_tags"] = list(
                pool.get("coin_exclude_tags", current_rules.get("exclude_tags", []))
            )
            atomic_write_json(RULES_PATH, current_rules)

        state = {
            "ok": True,
            "source_url": REMOTE_EXCLUDE_POOL_URL,
            "edit_url": REMOTE_EXCLUDE_POOL_EDIT_URL,
            "revision": pool.get("revision", ""),
            "remote_updated_at": pool.get("updated_at", ""),
            "sha256": digest,
            "last_checked_at": checked_at,
            "last_applied_at": checked_at if applied else previous.get("last_applied_at", ""),
            "applied": applied,
            "changed": changed,
            "last_error": None,
        }
        atomic_write_json(EXCLUDE_POOL_SYNC_STATE_PATH, state)
        return state
    except Exception as exc:
        state = dict(previous)
        state.update(
            {
                "ok": False,
                "source_url": REMOTE_EXCLUDE_POOL_URL,
                "edit_url": REMOTE_EXCLUDE_POOL_EDIT_URL,
                "last_checked_at": checked_at,
                "applied": False,
                "changed": False,
                "last_error": f"{exc.__class__.__name__}: {exc}",
            }
        )
        atomic_write_json(EXCLUDE_POOL_SYNC_STATE_PATH, state)
        return state


def _github_write_token():
    for name in ("TJB_GITHUB_TOKEN", "GH_TOKEN", "GITHUB_TOKEN"):
        token = os.environ.get(name, "").strip()
        if token:
            return token, f"env:{name}"

    if shutil.which("gh"):
        try:
            result = subprocess.run(
                ["gh", "auth", "token"],
                capture_output=True,
                text=True,
                timeout=5,
                check=True,
            )
            token = result.stdout.strip()
            if token:
                return token, "gh auth token"
        except Exception:
            pass
    return "", ""


def push_local_exclude_pool_to_cloud(timeout=8.0):
    token, token_source = _github_write_token()
    if not token:
        return {
            "ok": False,
            "error": (
                "未找到GitHub写入凭据；请设置TJB_GITHUB_TOKEN/GH_TOKEN，"
                "或先执行 gh auth login"
            ),
            "edit_url": REMOTE_EXCLUDE_POOL_EDIT_URL,
        }

    rules = read_rules()
    pool = {
        "schema_version": 1,
        "revision": datetime.now().astimezone().strftime("%Y-%m-%d-%H%M%S"),
        "updated_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "coin_exclude_tags": _normalize_string_list(
            rules.get("coin_exclude_tags", [])
        ),
        "energy_exclude_tags": _normalize_string_list(
            rules.get("energy_exclude_tags", [])
        ),
        "skip_task_extra_words": _normalize_string_list(
            rules.get("skip_task_extra_words", [])
        ),
        "notes": (
            "Cloud source for exclusion rules shared by Mac and Android. "
            "Only exclusion-related fields are remote-managed."
        ),
    }
    raw = (
        json.dumps(pool, ensure_ascii=False, indent=2) + "\n"
    ).encode("utf-8")
    api_url = (
        "https://api.github.com/repos/jay52121/coin11-tb/"
        "contents/cloud/exclude-pool.json"
    )
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "User-Agent": "coin11-tb-mac-rule-push/1",
        "X-GitHub-Api-Version": "2022-11-28",
    }

    try:
        get_request = urllib.request.Request(
            api_url + "?ref=main",
            headers=headers,
        )
        with urllib.request.urlopen(
            get_request,
            timeout=float(timeout),
        ) as response:
            current = json.loads(response.read().decode("utf-8"))
        current_sha = str(current.get("sha", "")).strip()
        if not current_sha:
            raise ValueError("GitHub未返回cloud/exclude-pool.json的sha")

        request_body = json.dumps(
            {
                "message": (
                    "chore: sync exclusion pool from Mac "
                    + pool["revision"]
                ),
                "content": base64.b64encode(raw).decode("ascii"),
                "sha": current_sha,
                "branch": "main",
            }
        ).encode("utf-8")
        put_request = urllib.request.Request(
            api_url,
            data=request_body,
            headers={
                **headers,
                "Content-Type": "application/json",
            },
            method="PUT",
        )
        with urllib.request.urlopen(
            put_request,
            timeout=float(timeout),
        ) as response:
            result = json.loads(response.read().decode("utf-8"))

        digest = hashlib.sha256(raw).hexdigest()
        atomic_write_json(EXCLUDE_POOL_CACHE_PATH, pool)
        state = {
            "ok": True,
            "source_url": REMOTE_EXCLUDE_POOL_URL,
            "edit_url": REMOTE_EXCLUDE_POOL_EDIT_URL,
            "revision": pool["revision"],
            "remote_updated_at": pool["updated_at"],
            "sha256": digest,
            "last_checked_at": now_text(),
            "last_applied_at": now_text(),
            "last_pushed_at": now_text(),
            "applied": True,
            "changed": True,
            "last_error": None,
            "push_auth": token_source,
            "commit_sha": (
                result.get("commit", {}).get("sha", "")
                if isinstance(result, dict)
                else ""
            ),
        }
        atomic_write_json(EXCLUDE_POOL_SYNC_STATE_PATH, state)
        return state
    except Exception as exc:
        return {
            "ok": False,
            "error": f"{exc.__class__.__name__}: {exc}",
            "edit_url": REMOTE_EXCLUDE_POOL_EDIT_URL,
            "push_auth": token_source,
        }


def read_json(path, default):
    try:
        with path.open("r", encoding="utf-8") as f:
            data = json.load(f)
        if not isinstance(data, dict):
            return default.copy()
        merged = default.copy()
        merged.update(data)
        return merged
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return default.copy()


def read_control():
    return read_json(CONTROL_PATH, DEFAULT_CONTROL)


def write_control(**kwargs):
    data = read_control()
    data.update(kwargs)
    atomic_write_json(CONTROL_PATH, data)
    return data


def read_status():
    return read_json(STATUS_PATH, DEFAULT_STATUS)


def read_coin_records():
    return read_json(COIN_RECORD_PATH, {"users": {}})


def record_taojinbi_coin(user_id, coin, source="ocr"):
    try:
        coin = int(coin)
    except (TypeError, ValueError):
        return None
    if coin <= 0:
        return None

    data = read_coin_records()
    users = data.setdefault("users", {})
    user_key = str(user_id or "0")
    days = users.setdefault(user_key, {})
    today = time.strftime("%Y-%m-%d")
    now = now_text()
    record = days.get(today)
    if not isinstance(record, dict):
        record = {
            "date": today,
            "first": coin,
            "min": coin,
            "max": coin,
            "last": coin,
            "first_seen": now,
            "last_seen": now,
            "count": 1,
            "source": source,
        }
    else:
        record["min"] = min(int(record.get("min", coin)), coin)
        record["max"] = max(int(record.get("max", coin)), coin)
        record["last"] = coin
        record["last_seen"] = now
        record["count"] = int(record.get("count", 0)) + 1
        record["source"] = source
        record.setdefault("first", coin)
        record.setdefault("first_seen", now)
    if source == "final":
        record["final"] = coin
        record["final_seen"] = now
    days[today] = record
    atomic_write_json(COIN_RECORD_PATH, data)
    return record


def read_rules():
    rules = read_json(RULES_PATH, DEFAULT_RULES)
    legacy_control = read_json(CONTROL_PATH, DEFAULT_CONTROL)
    try:
        with RULES_PATH.open("r", encoding="utf-8") as f:
            raw_rules = json.load(f)
        if not isinstance(raw_rules, dict):
            raw_rules = {}
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        raw_rules = {}
    migrated = False
    for key in ("exclude_tags", "coin_exclude_tags", "energy_exclude_tags", "allow_daily_version_fallback", "enable_jump_energy"):
        if key not in raw_rules and key in legacy_control:
            rules[key] = legacy_control[key]
            migrated = True
    if migrated:
        atomic_write_json(RULES_PATH, rules)
    return rules


def write_rules(data):
    rules = DEFAULT_RULES.copy()
    if isinstance(data, dict):
        rules.update(data)
    atomic_write_json(RULES_PATH, rules)
    return rules


def update_status(**kwargs):
    data = read_status()
    data.update(kwargs)
    data["updated_at"] = now_text()
    atomic_write_json(STATUS_PATH, data)
    return data


def reset_state():
    control = read_control()
    control["stop"] = False
    control["pause"] = False
    atomic_write_json(CONTROL_PATH, control)
    if not RULES_PATH.exists():
        atomic_write_json(RULES_PATH, DEFAULT_RULES.copy())
    status = DEFAULT_STATUS.copy()
    status["updated_at"] = now_text()
    atomic_write_json(STATUS_PATH, status)


def append_log(message):
    ensure_dirs()
    with RUN_LOG_PATH.open("a", encoding="utf-8") as f:
        f.write(f"{now_text()} {message}\n")


def append_action_log(message):
    append_log(f"[页面操作] {message}")


def append_key_log(message):
    ensure_dirs()
    with KEY_LOG_PATH.open("a", encoding="utf-8") as f:
        f.write(f"{now_text()} {message}\n")


def clear_log():
    ensure_dirs()
    RUN_LOG_PATH.write_text("", encoding="utf-8")


def clear_key_log():
    ensure_dirs()
    KEY_LOG_PATH.write_text("", encoding="utf-8")


def clean_log_line(line):
    return "".join(ch for ch in line if ch == "\t" or ch >= " ")


def read_log_file(path, limit=100):
    try:
        text = path.read_text(encoding="utf-8", errors="replace").replace("\x00", "")
        lines = [clean_log_line(line) for line in text.splitlines()]
    except FileNotFoundError:
        return []
    if limit <= 0:
        return []
    return [line for line in lines if line][-limit:]


def read_logs(limit=100):
    return read_log_file(RUN_LOG_PATH, limit)


def read_key_logs(limit=100):
    return read_log_file(KEY_LOG_PATH, limit)
