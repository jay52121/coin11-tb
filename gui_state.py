import json
import os
import time
from pathlib import Path


BASE_DIR = Path(__file__).resolve().parent
RUNTIME_DIR = BASE_DIR / "runtime"
LOG_DIR = BASE_DIR / "logs"
CONTROL_PATH = RUNTIME_DIR / "control.json"
STATUS_PATH = RUNTIME_DIR / "status.json"
RULES_PATH = RUNTIME_DIR / "rules.json"
RUN_LOG_PATH = LOG_DIR / "run.log"

DEFAULT_CONTROL = {
    "stop": False,
    "pause": False,
    "exclude_tags": ["下单", "快手", "评价", "助力"],
}

DEFAULT_STATUS = {
    "running": False,
    "paused": False,
    "activity": "",
    "page_type": "unknown",
    "action": "idle",
    "current_task": "",
    "version": "",
    "exclude_tags": [],
    "last_error": None,
    "updated_at": "",
}

DEFAULT_RULES = {
    "action_text_pattern": "去完成|去逛逛|去浏览|逛一逛|立即领|去领取|去看看|搜一下|玩一把|捐一笔|逛一下|点击去逛|领取奖励|立即领取|点击得",
    "skip_task_words": ["下单", "快手", "评价", "助力"],
    "skip_task_extra_words": [
        "拉好友", "抢红包", "搜索兴趣商品下单", "买精选商品", "全场3元3件", "固定入口",
        "农场小游戏", "砸蛋", "大众点评", "蚂蚁新村", "消消乐", "3元抢3件包邮到家",
        "拍一拍", "1元抢爆款好货", "拉1人助力", "玩消消乐", "下单即得",
        "添加签到神器", "下单得肥料", "88VIP", "邀请好友", "好货限时直降",
        "连连消", "拍立淘", "玩任意游戏", "首页回访", "百亿外卖",
        "玩趣味游戏得大额体力", "天猫积分换体力", "头条刷热点", "一淘签到",
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
    "task_done_page_words": ["任务已完成", "已得"],
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
    tmp_path = path.with_name(f"{path.name}.tmp")
    with tmp_path.open("w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    os.replace(tmp_path, path)


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


def read_rules():
    return read_json(RULES_PATH, DEFAULT_RULES)


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
    atomic_write_json(CONTROL_PATH, DEFAULT_CONTROL.copy())
    if not RULES_PATH.exists():
        atomic_write_json(RULES_PATH, DEFAULT_RULES.copy())
    status = DEFAULT_STATUS.copy()
    status["updated_at"] = now_text()
    atomic_write_json(STATUS_PATH, status)


def append_log(message):
    ensure_dirs()
    with RUN_LOG_PATH.open("a", encoding="utf-8") as f:
        f.write(f"{now_text()} {message}\n")


def clear_log():
    ensure_dirs()
    RUN_LOG_PATH.write_text("", encoding="utf-8")


def read_logs(limit=100):
    try:
        lines = RUN_LOG_PATH.read_text(encoding="utf-8", errors="replace").splitlines()
    except FileNotFoundError:
        return []
    if limit <= 0:
        return []
    return lines[-limit:]
