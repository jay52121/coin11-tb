import os
import random
import re
import threading
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import uiautomator2 as u2

from phone_alert import notify_phone
from screen_ocr import get_reader as warmup_ocr_reader, image_has_text, normalize_text, read_ocr_results
from gui_state import append_key_log, read_control, read_rules, update_status as write_gui_status
from utils import check_chars_exist, other_app, get_current_app, select_device, check_verify, TB_APP

COIN_HOME_URL = "https://pages-fast.m.taobao.com/wow/z/tmtjb/town/home?utparam=%7B%22ranger_buckets_native%22%3A%22tsp6443_32421_standardVersion%22%7D&spm=a2141.1.iconsv5.5&miniappSourceChannel=homepage&scm=1007.home_icon.lingjb.d&x-ssr=true&disableNav=YES&x-sec=wua&pha_h5=true&pha_nav=true&uniapp_id=1011525&uniapp_page=home&hd_from=tbHome"
VERSION = "coin-row-xml-log-20260530-0211"
RUN_MODE = os.environ.get("TJB_TASK_MODE", "taojinbi")
ACTION_CLASS = r"android.widget.Button|android.widget.TextView|android.view.View"
BROWSE_TASK_DURATION = 30
BACK_RESTART_LIMIT = 4
CROSS_APP_BACK_LIMIT = 4

have_clicked = {}
invalid_click_keys = set()
expanded_more_tasks = False
finish_count = 0
good_shop_entry_clicks = {}
good_shop_failed_entry_keys = set()
start_time_all = time.time()
ocr_done_event = threading.Event()
ocr_check_running = False

print(f"淘金币任务脚本版本: {VERSION}")
selected_device = select_device()
d = u2.connect(selected_device)
print(f"已成功连接设备：{selected_device}")
screen_width, screen_height = d.window_size()
BASE_DIR = Path(__file__).resolve().parent
GOOD_SHOP_TRACE_LOG = BASE_DIR / "logs" / "good_shop_trace.log"


def warmup_ocr_async():
    def worker():
        print("后台初始化OCR")
        try:
            warmup_ocr_reader(gpu=True)
            print("OCR初始化完成")
        except Exception as exc:
            print("OCR初始化失败，后续跳过OCR提前完成判断", exc)

    threading.Thread(target=worker, daemon=True).start()


warmup_ocr_async()

ctx = d.watch_context()


def watcher_click_log(name):
    def action(selector):
        print("Watcher点击", name)
        try:
            human_click_bounds(selector.bounds())
        except Exception:
            selector.click()

    return action


ctx.when("O1CN012qVB9n1tvZ8ATEQGu_!!6000000005964-2-tps-144-144").call(watcher_click_log("图片关闭1"))
ctx.when("O1CN01sORayC1hBVsDQRZoO_!!6000000004239-2-tps-426-128.png_").call(watcher_click_log("图片关闭2"))
# Generic text watchers can silently click normal page controls, so keep only narrow close-button rules here.
ctx.when(xpath="//android.app.Dialog//android.widget.Button[contains(text(), '-tps-')]").call(watcher_click_log("tps弹窗按钮"))
ctx.when(xpath="//android.app.Dialog//android.widget.Button[@text='关闭']").call(watcher_click_log("弹窗关闭"))
ctx.when(xpath="//android.widget.FrameLayout[@resource-id='com.taobao.taobao:id/poplayer_native_state_center_layout_frame_id']//android.widget.ImageView[@content-desc='关闭按钮']").call(watcher_click_log("poplayer关闭按钮"))
ctx.start()


def update_status(**kwargs):
    kwargs.setdefault("version", VERSION)
    write_gui_status(**kwargs)


def append_good_shop_trace(message):
    try:
        GOOD_SHOP_TRACE_LOG.parent.mkdir(exist_ok=True)
        with GOOD_SHOP_TRACE_LOG.open("a", encoding="utf-8") as f:
            f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {message}\n")
    except Exception as exc:
        print("写入逛好店诊断日志失败", exc)


def set_action(action, **extra):
    update_status(action=action, **extra)


def set_page(page_type, **extra):
    update_status(page_type=page_type, **extra)


def should_stop():
    return bool(read_control().get("stop", False))


def get_exclude_tags():
    tags = read_control().get("exclude_tags", [])
    if isinstance(tags, list):
        return [str(tag).strip() for tag in tags if str(tag).strip()]
    return []


def rule_value(name, default=None):
    return read_rules().get(name, default)


def rule_list(name, default=None):
    value = rule_value(name, default or [])
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    if isinstance(value, str):
        return [item.strip() for item in re.split(r"[,，\n]+", value) if item.strip()]
    return default or []


def rule_text(name, default=""):
    value = rule_value(name, default)
    if isinstance(value, list):
        return "|".join(re.escape(str(item)) for item in value if str(item))
    return str(value or default)


def action_text_pattern():
    return rule_text("action_text_pattern")


def wait_if_paused():
    while read_control().get("pause", False):
        update_status(running=True, paused=True, action="paused")
        time.sleep(1)
        if should_stop():
            break


def parse_bounds(bounds_text):
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds_text or "")
    if not match:
        return None
    return tuple(map(int, match.groups()))


def center(bounds):
    return ((bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2)


def clamp(value, low, high):
    return max(low, min(high, value))


def jitter_point(x, y, radius=8):
    return (
        clamp(x + random.randint(-radius, radius), 1, screen_width - 1),
        clamp(y + random.randint(-radius, radius), 1, screen_height - 1),
    )


def human_click(x, y, radius=8, hold=None):
    x, y = jitter_point(int(x), int(y), radius)
    hold = hold if hold is not None else random.uniform(0.08, 0.18)
    print("模拟点击", x, y, f"{hold:.2f}S")
    d.long_click(x, y, hold)
    time.sleep(random.uniform(0.12, 0.28))


def human_click_bounds(bounds, radius=8):
    x, y = center(bounds)
    human_click(x, y, radius=radius)


def human_swipe(x1, y1, x2, y2, duration=0.45, wiggle=24):
    x1, y1 = jitter_point(x1, y1, wiggle)
    x2, y2 = jitter_point(x2, y2, wiggle)
    mid_x = clamp((x1 + x2) // 2 + random.randint(-wiggle, wiggle), 1, screen_width - 1)
    mid_y = clamp((y1 + y2) // 2 + random.randint(-wiggle, wiggle), 1, screen_height - 1)
    duration = max(0.18, duration * random.uniform(0.85, 1.35))
    print("模拟滑动轨迹", (x1, y1), (mid_x, mid_y), (x2, y2), f"{duration:.2f}S")
    d.swipe_points([(x1, y1), (mid_x, mid_y), (x2, y2)], duration)
    time.sleep(random.uniform(0.18, 0.42))


def human_back():
    time.sleep(random.uniform(0.08, 0.22))
    d.press("back")
    time.sleep(random.uniform(0.25, 0.55))


def safe_obj_bounds(obj, label="控件"):
    try:
        bounds = obj.bounds()
        if bounds and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
            return bounds
    except Exception as exc:
        print("读取控件bounds失败", label, exc)
    return None


def safe_obj_text(obj, label="控件"):
    try:
        return obj.get_text() or ""
    except Exception as exc:
        print("读取控件文本失败", label, exc)
        return ""


def contains_bounds(outer, inner):
    return outer and inner and outer[0] <= inner[0] and outer[1] <= inner[1] and outer[2] >= inner[2] and outer[3] >= inner[3]


class XmlClickTarget:
    def __init__(self, bounds):
        self._bounds = bounds

    def bounds(self):
        return self._bounds

    def click(self):
        human_click_bounds(self._bounds)


def dump_root():
    try:
        return ET.fromstring(d.dump_hierarchy(compressed=False))
    except Exception as exc:
        print("读取XML失败", exc)
        return None


def get_page_texts(limit=120):
    root = dump_root()
    if root is None:
        return []
    texts = []
    for node in root.iter("node"):
        text = node.attrib.get("text") or ""
        if text:
            texts.append(text)
        if limit is not None and len(texts) >= limit:
            break
    return texts


def has_any(texts, keys):
    return any(key in text for text in texts for key in keys)


def node_text_value(node):
    return (
        node.attrib.get("text")
        or node.attrib.get("content-desc")
        or node.attrib.get("resource-id")
        or ""
    )


def task_click_key(task_name):
    text = re.sub(r"\s+", "", task_name or "")
    match = re.search(r"([^，。；;（）()]{2,40})[（(]0/\d+[）)]", text)
    if match:
        return match.group(0)
    return text[:80]


def skip_task_name(task_name):
    if not task_name:
        return False
    skip_words = rule_list("skip_task_words", get_exclude_tags() or ["下单", "快手", "评价", "助力"])
    extra_words = rule_list("skip_task_extra_words", [])
    update_status(exclude_tags=skip_words)
    compact_task = normalize_text(task_name).replace(" ", "").lower()
    if "uc" in [word.lower() for word in skip_words + extra_words] and re.search(r"去逛0[6g]送红包福利", compact_task):
        print("任务命中跳过词", "UC", task_name)
        return True
    for word in skip_words + extra_words:
        compact_word = normalize_text(word).replace(" ", "").lower()
        if compact_word and compact_word in compact_task:
            print("任务命中跳过词", word, task_name)
            append_key_log(f"跳过任务: {task_name}；命中: {word}")
            return True
    return False


def task_is_done_text(task_name):
    if not task_name:
        return False
    done_words = rule_list("done_words", ["已完成", "已领取", "已得", "任务已完成", "记得明天再来"])
    exclude_words = rule_list("task_done_exclude_words", ["累计已得", "累积已得"])
    count_match = re.search(r"[（(](\d+)/(\d+)[）)]", task_name)
    count_done = bool(count_match and int(count_match.group(1)) >= int(count_match.group(2)))
    return count_done or (any(word in task_name for word in done_words) and not any(word in task_name for word in exclude_words))


def task_click_limit(task_name):
    if RUN_MODE == "energy":
        count_match = re.search(r"[（(](\d+)/(\d+)[）)]", task_name or "")
        if count_match:
            total = int(count_match.group(2))
            return min(max(total + 1, 2), 12)
    return 2


def has_task_done_text(texts):
    done_words = rule_list("task_done_page_words", ["任务已完成", "已得"])
    exclude_words = rule_list("task_done_exclude_words", ["累计已得", "累积已得"])
    if has_any(texts, ["淘宝购物清单"]):
        done_words = [word for word in done_words if word != "已得"]
    return any(any(word in text for word in done_words) and not any(word in text for word in exclude_words) for text in texts)


def task_done_text_hits(texts):
    done_words = rule_list("task_done_page_words", ["任务已完成", "已得"])
    exclude_words = rule_list("task_done_exclude_words", ["累计已得", "累积已得"])
    if has_any(texts, ["淘宝购物清单"]):
        done_words = [word for word in done_words if word != "已得"]
    return [text for text in texts if any(word in text for word in done_words) and not any(word in text for word in exclude_words)]


def ocr_task_done(screenshot, screenshot_time=0, ignore_targets=None):
    try:
        ignore_targets = set(ignore_targets or [])
        targets = rule_list("ocr_done_text", ["任务已完成"]) + rule_list("ocr_done_extra_words", ["继续逛逛吧"])
        targets = [target for target in targets if target not in ignore_targets]
        all_hits = []
        last_timings = {}
        for target in targets:
            ok, hits, timings = image_has_text(screenshot, target, max_width=900, gpu=True, min_confidence=0.2)
            timings["screenshot"] = screenshot_time
            timings["total"] += screenshot_time
            last_timings = timings
            if hits:
                all_hits.extend(hits)
            if ok:
                print("OCR检查任务完成", True, target, hits[:2], {k: round(v, 3) if isinstance(v, float) else v for k, v in timings.items()})
                append_key_log(f"检测到任务完成: {target}")
                return True
        print("OCR检查任务完成", False, all_hits[:2], {k: round(v, 3) if isinstance(v, float) else v for k, v in last_timings.items()})
        return False
    except Exception as exc:
        print("OCR检查任务完成失败", exc)
        return False


def start_ocr_done_check_async(screenshot, screenshot_time=0, ignore_targets=None):
    global ocr_check_running
    if ocr_check_running:
        return
    ocr_check_running = True

    def worker():
        global ocr_check_running
        try:
            if ocr_task_done(screenshot, screenshot_time, ignore_targets=ignore_targets):
                ocr_done_event.set()
        finally:
            ocr_check_running = False

    threading.Thread(target=worker, daemon=True).start()


def looks_like_search_browse_page(texts):
    return has_any(texts, rule_list("search_browse_words"))


def looks_like_coin_home_page(texts=None):
    if texts is None:
        texts = get_page_texts()
    has_home = has_any(texts, rule_list("coin_home_words"))
    has_task = has_any(texts, rule_list("coin_home_task_words"))
    return has_home and not has_task and not looks_like_search_browse_page(texts)


def looks_like_browse_task_page(texts=None, activity_name=""):
    if texts is None:
        texts = get_page_texts()
    if "NewDetailActivity" in activity_name:
        return True
    if looks_like_search_browse_page(texts):
        return True
    if looks_like_task_list_page(texts):
        return False
    return has_any(texts, rule_list("browse_page_words"))


def looks_like_daily_task_list_by_xml():
    root = dump_root()
    if root is None:
        return False
    texts = [node.attrib.get("text") or "" for node in root.iter("node")]
    texts = [text for text in texts if text]
    if looks_like_search_browse_page(texts):
        return False
    has_fast = has_any(texts, rule_list("daily_fast_words"))
    has_task_area = has_any(texts, rule_list("daily_task_area_words"))
    action_count = sum(1 for text in texts if re.search(action_text_pattern(), text or ""))
    if (has_fast and has_task_area) or (has_task_area and action_count > 0):
        print("XML确认日常任务列表", {"今日速赚": has_fast, "任务区": has_task_area, "动作文本": action_count})
        return True
    return False


def looks_like_task_list_page(texts=None):
    if texts is None:
        texts = get_page_texts()
    if looks_like_search_browse_page(texts):
        return False
    return has_any(texts, rule_list("task_list_words")) or looks_like_daily_task_list_by_xml()


def task_list_is_at_bottom(texts):
    return has_any(texts, rule_list("task_list_bottom_words", ["收起更多任务"]))


def looks_like_shop_subscribe_task(texts):
    if has_any(texts, ["淘金币首页", "淘金币标题", "购物车", "可抵"]):
        return False
    words = rule_list("shop_subscribe_words", ["订阅+", "已关注", "取消关注", "最多还可以领", "立即领"])
    if not has_any(texts, words):
        return False
    return any(re.search(r"订阅\s*\+\s*\d+", text) for text in texts) or has_any(texts, ["取消关注", "最多还可以领", "立即领"])


def task_is_good_shop_task(task_name):
    return "逛好店赚一大波金币" in (task_name or "")


def looks_like_good_shop_page(texts):
    return any(re.search(r"今日推荐\d+家好店", text) for text in texts)


def looks_like_energy_task_list(texts):
    return has_any(texts, ["做任务赚体力"]) and has_any(texts, ["赚体力", "体力"])


def looks_like_good_shop_child_page(texts):
    return has_any(texts, ["淘金币-逛店铺任务SSR", "滑动浏览"]) or (has_any(texts, ["已领取"]) and has_any(texts, ["进店"]))


def good_shop_store_key(texts, fallback="unknown"):
    blocked = ["O1CN", "wAAA", "JRU5", "b_", "今日推荐", "淘金币", "人关注", "逛店铺", "再逛逛", "最多还可领", "+", "KB/S"]
    for index, text in enumerate(texts):
        if "人关注" not in text:
            continue
        for prev in reversed(texts[max(0, index - 8):index]):
            item = str(prev).strip()
            if not item or any(word in item for word in blocked):
                continue
            if re.fullmatch(r"[\d.万]+", item):
                continue
            return item[:40]
    return fallback


def good_shop_entry_key_from_xml(root, action_bounds):
    action_y = (action_bounds[1] + action_bounds[3]) // 2
    row_items = []
    for node in root.iter("node"):
        text = (node.attrib.get("text") or node.attrib.get("content-desc") or "").strip()
        bounds = parse_bounds(node.attrib.get("bounds"))
        if not text or not bounds:
            continue
        y_center = (bounds[1] + bounds[3]) // 2
        if abs(y_center - action_y) > 170 or bounds[0] >= action_bounds[0]:
            continue
        row_items.append((bounds[1], bounds[0], text))
    blocked = ["O1CN", "wAAA", "JRU5", "b_", "今日推荐", "淘金币", "人关注", "逛店铺", "再逛逛", "最多还可领", "+", "KB/S"]
    for _, _, text in sorted(row_items):
        item = text.strip()
        if not item or any(word in item for word in blocked):
            continue
        if re.fullmatch(r"[\d.万]+", item):
            continue
        return item[:40]
    return f"entry:{action_bounds}"


def find_good_shop_entry_candidates():
    root = dump_root()
    if root is None:
        return []
    entries = []
    seen = set()
    words = ["逛店铺", "再逛逛", "最多还可领", "最多还可以领"]
    for node in root.iter("node"):
        text = (node.attrib.get("text") or node.attrib.get("content-desc") or "").strip()
        if not text or not any(word in text for word in words):
            continue
        bounds = parse_bounds(node.attrib.get("bounds"))
        if not bounds or bounds[1] < 120 or bounds[2] <= bounds[0] or bounds[3] <= bounds[1]:
            continue
        if bounds in seen:
            continue
        seen.add(bounds)
        key = good_shop_entry_key_from_xml(root, bounds)
        entries.append((bounds[1], bounds[0], bounds, text, key))
    return [(bounds, text, key) for _, _, bounds, text, key in sorted(entries)]


def good_shop_entry_key_from_ocr(items, action_bounds):
    row_text = ocr_row_text(items, action_bounds)
    blocked = ["O1CN", "wAAA", "JRU5", "b_", "今日推荐", "淘金币", "人关注", "逛店铺", "再逛逛", "最多还可领", "+", "KB/S"]
    parts = [part.strip() for part in row_text.split() if part.strip()]
    for part in reversed(parts):
        if any(word in part for word in blocked):
            continue
        if re.fullmatch(r"[\d.万]+", part):
            continue
        return part[:40]
    return f"ocr-entry:{action_bounds}"


def find_good_shop_entry_candidates_by_ocr():
    items = scan_ocr_once("逛好店入口")
    entries = []
    words = ["逛店铺", "再逛逛", "最多还可领", "最多还可以领"]
    for item in items:
        bounds = item["bounds"]
        x_center = (bounds[0] + bounds[2]) // 2
        if x_center < int(screen_width * 0.55):
            continue
        if not ocr_text_contains(item["text"], words):
            continue
        key = good_shop_entry_key_from_ocr(items, bounds)
        entries.append((bounds[1], bounds[0], bounds, item["text"], key))
    return [(bounds, text, key) for _, _, bounds, text, key in sorted(entries)]


def looks_like_shop_browse_task(task_name, texts):
    source = " ".join([task_name or ""] + list(texts or []))
    return any(word in source for word in ["浏览店铺", "逛店铺", "逛好店"])


def classify_current_page():
    package_name, activity_name = get_current_app(d)
    texts = get_page_texts(120)
    if looks_like_energy_task_list(texts):
        page_type = "energy_task_list"
        set_page(page_type, activity=activity_name or "", running=True, paused=False)
        return page_type, package_name, activity_name, texts
    if looks_like_good_shop_page(texts):
        page_type = "good_shop_page"
        set_page(page_type, activity=activity_name or "", running=True, paused=False)
        return page_type, package_name, activity_name, texts
    if package_name != TB_APP:
        page_type = "external_app"
        set_page(page_type, activity=activity_name or "", running=True, paused=False)
        return page_type, package_name, activity_name, texts
    if has_task_done_text(texts):
        page_type = "task_done"
        set_page(page_type, activity=activity_name or "", running=True, paused=False)
        return page_type, package_name, activity_name, texts
    if has_any(texts, rule_list("quiz_words", ["淘金币趣味答题", "我选好了"])):
        page_type = "quiz"
        set_page(page_type, activity=activity_name or "", running=True, paused=False)
        return page_type, package_name, activity_name, texts
    if looks_like_task_list_page(texts):
        page_type = "daily_task_list"
        set_page(page_type, activity=activity_name or "", running=True, paused=False)
        return page_type, package_name, activity_name, texts
    if looks_like_coin_home_page(texts):
        page_type = "coin_home"
        set_page(page_type, activity=activity_name or "", running=True, paused=False)
        return page_type, package_name, activity_name, texts
    if looks_like_shop_subscribe_task(texts):
        page_type = "shop_subscribe_task"
        set_page(page_type, activity=activity_name or "", running=True, paused=False)
        return page_type, package_name, activity_name, texts
    if looks_like_browse_task_page(texts, activity_name or ""):
        page_type = "taobao_browse_task"
        set_page(page_type, activity=activity_name or "", running=True, paused=False)
        return page_type, package_name, activity_name, texts
    page_type = "unknown_taobao_page"
    set_page(page_type, activity=activity_name or "", running=True, paused=False)
    return page_type, package_name, activity_name, texts


def open_coin_home_direct(stop=True):
    global expanded_more_tasks
    expanded_more_tasks = False
    set_action("finding_entry")
    if stop:
        print("强制关闭淘宝后立即启动淘金币入口")
        d.app_stop(TB_APP)
    print("启动淘金币入口")
    d.shell(f"am start -a android.intent.action.VIEW -d '{COIN_HOME_URL}' {TB_APP}")
    time.sleep(4)


def stop_known_external_apps():
    for package in ["com.tmall.wireless"]:
        try:
            print("关闭外部App", package)
            d.app_stop(package)
        except Exception as exc:
            print("关闭外部App失败", package, exc)


def click_daily_version_if_exists():
    set_action("switching_daily")
    daily_btn = d(classNameMatches=ACTION_CLASS, textMatches=rule_text("daily_version_words", "回日常版"))
    if daily_btn.exists(timeout=0.8):
        bounds = safe_obj_bounds(daily_btn, "回日常版")
        if not bounds:
            return False
        print("点击回日常版", safe_obj_text(daily_btn, "回日常版"), bounds)
        human_click_bounds(bounds)
        print("已点击回日常版，等待日常版动画完成")
        time.sleep(3)
        return True
    return False


def click_earn_more_if_exists(require_click=False):
    earn_more_btn = d(classNameMatches=ACTION_CLASS, textMatches=rule_text("earn_more_words", "赚更多金币"))
    if earn_more_btn.exists(timeout=0.5):
        bounds = safe_obj_bounds(earn_more_btn, "赚更多金币")
        if bounds[2] > bounds[0] and bounds[3] > bounds[1]:
            print("点击赚更多金币进入任务列表", safe_obj_text(earn_more_btn, "赚更多金币"), bounds)
            human_click_bounds(bounds)
            time.sleep(2)
            return True
    if looks_like_task_list_page():
        if earn_more_btn.exists(timeout=0.1):
            print("检测到赚更多金币但不可见，不点击", safe_obj_bounds(earn_more_btn, "赚更多金币"))
        if require_click:
            print("要求点击赚更多金币，但当前没有可点击的赚更多金币")
            return False
        return True
    earn_btn = d(classNameMatches=ACTION_CLASS, textMatches=rule_text("earn_words", "赚金币"))
    if earn_btn.exists(timeout=1):
        bounds = safe_obj_bounds(earn_btn, "赚金币")
        if not bounds:
            return False
        print("点击赚金币进入任务列表", safe_obj_text(earn_btn, "赚金币"), bounds)
        human_click_bounds(bounds)
        time.sleep(2)
        return True
    return False


def wait_and_click_earn_more_after_daily(max_wait=8):
    print("回日常版后等待并查找赚更多金币")
    deadline = time.time() + max_wait
    while time.time() < deadline:
        wait_if_paused()
        if should_stop():
            return False
        if click_earn_more_if_exists(require_click=True):
            return True
        time.sleep(1)
    print("回日常版后未找到可点击的赚更多金币")
    return False


def enter_task_list_from_coin_home():
    global expanded_more_tasks
    expanded_more_tasks = False
    if click_daily_version_if_exists():
        return wait_and_click_earn_more_after_daily()
    if looks_like_task_list_page():
        return True
    if click_earn_more_if_exists():
        return True
    print("未找到赚金币入口，不再盲点首页固定区域")
    return False


def enter_energy_task_list_from_coin_home(max_wait=8):
    print("查找赚体力入口，返回做任务赚体力列表")
    deadline = time.time() + max_wait
    while time.time() < deadline:
        wait_if_paused()
        if should_stop():
            return False
        page_type, package_name, activity_name, texts = classify_current_page()
        if page_type == "energy_task_list":
            return True
        energy_btn = d(classNameMatches=ACTION_CLASS, textMatches="赚体力")
        if energy_btn.exists(timeout=0.6):
            bounds = safe_obj_bounds(energy_btn, "赚体力")
            if bounds:
                print("点击赚体力进入体力任务列表", safe_obj_text(energy_btn, "赚体力"), bounds)
                human_click_bounds(bounds)
                time.sleep(2)
                continue
        time.sleep(1)
    print("未找到赚体力入口，不能切到赚金币")
    return False


def wait_for_task_list_after_entry(max_wait=12):
    print("启动入口后等待页面稳定并查找日常任务入口")
    deadline = time.time() + max_wait
    while time.time() < deadline:
        wait_if_paused()
        if should_stop():
            return False
        page_type, package_name, activity_name, texts = classify_current_page()
        print("入口后页面判定", {"page": page_type, "package": package_name, "activity": activity_name, "texts": texts[:8]})
        if page_type == "external_app":
            print("启动入口落到外部App，关闭外部App并重开淘金币入口", package_name)
            stop_known_external_apps()
            open_coin_home_direct(stop=True)
            continue
        if click_daily_version_if_exists():
            return wait_and_click_earn_more_after_daily()
        if page_type == "daily_task_list" or looks_like_task_list_page(texts):
            return True
        if page_type == "coin_home" and enter_task_list_from_coin_home():
            return True
        if click_earn_more_if_exists(require_click=True):
            return True
        time.sleep(1)
    print("启动入口后仍未进入任务列表")
    return False


def expand_more_coin_tasks():
    global expanded_more_tasks
    if expanded_more_tasks:
        return False
    set_action("finding_task")
    expand_btn = d(classNameMatches=ACTION_CLASS, textMatches=rule_text("expand_words", "展开"))
    if expand_btn.exists(timeout=0.5):
        bounds = safe_obj_bounds(expand_btn, "展开")
        if not bounds:
            return False
        print("点击展开更多金币任务", bounds)
        human_click_bounds(bounds)
        expanded_more_tasks = True
        time.sleep(1)
        return True
    root = dump_root()
    if root is not None:
        parent = {}
        for item in root.iter("node"):
            for child in item:
                parent[child] = item
        for node in root.iter("node"):
            if not re.search(rule_text("expand_words", "展开"), node.attrib.get("text") or ""):
                continue
            target = node
            while target is not None and target.attrib.get("clickable") != "true":
                target = parent.get(target)
            if target is None:
                continue
            bounds = parse_bounds(target.attrib.get("bounds"))
            if bounds and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                print("XML兜底点击展开父节点", bounds)
                human_click_bounds(bounds)
                expanded_more_tasks = True
                time.sleep(1)
                return True
    print("未找到展开按钮")
    return False


def scroll_task_list_once():
    set_action("scrolling_task_list")
    x = max(30, int(screen_width * 0.06))
    y1 = int(screen_height * 0.84)
    y2 = int(screen_height * 0.32)
    print("任务列表左侧下翻一屏", x, y1, x, y2)
    human_swipe(x, y1, x, y2, 0.45, wiggle=18)
    time.sleep(0.45)
    page_type, package_name, activity_name, texts = classify_current_page()
    print("任务列表翻页后页面判定", {"page": page_type, "package": package_name, "activity": activity_name, "texts": texts[:8]})


def do_one_external_swipe():
    set_action("doing_scroll_task")
    texts = get_page_texts(80)
    if has_any(texts, ["正在离开手机淘宝"]) and has_any(texts, ["取消"]):
        print("检测到离开淘宝确认弹窗，点击取消")
        cancel_btn = d(classNameMatches=ACTION_CLASS, text="取消")
        if cancel_btn.exists(timeout=0.5):
            bounds = safe_obj_bounds(cancel_btn, "取消")
            if bounds:
                human_click_bounds(bounds)
                time.sleep(1)
                return
        human_back()
        return
    for index in range(3):
        print("外部/未知任务页滚动", index + 1)
        human_swipe(screen_width // 2, int(screen_height * 0.78), screen_width // 2, int(screen_height * 0.38), 0.35)
        time.sleep(1)


def click_search_discovery_if_exists():
    set_action("doing_search_task")
    texts = get_page_texts(120)
    if not has_any(texts, rule_list("search_browse_words", ["搜索后浏览立得奖励"])):
        return False
    history_item = d.xpath('(//android.widget.TextView[@text="历史搜索"]/following-sibling::android.widget.ListView)/android.view.View[1]')
    if history_item.exists:
        print("点击历史搜索第一个内容块")
        human_click_bounds(history_item.get(timeout=0.2).bounds)
        time.sleep(2)
        return True
    root = dump_root()
    if root is None:
        return False
    discovery_bounds = None
    candidates = []
    for node in root.iter("node"):
        text = node.attrib.get("text") or ""
        bounds = parse_bounds(node.attrib.get("bounds"))
        if not bounds:
            continue
        if "搜索发现" in text:
            discovery_bounds = bounds
        elif discovery_bounds and bounds[1] > discovery_bounds[3] and bounds[2] - bounds[0] > 80 and bounds[3] - bounds[1] > 40:
            candidates.append(bounds)
    if candidates:
        target = sorted(candidates, key=lambda item: (item[1], item[0]))[0]
        print("点击搜索发现第一个内容块中心", target)
        human_click_bounds(target)
        time.sleep(2)
        return True
    return False


def browse_task_loop(duration=BROWSE_TASK_DURATION):
    set_action("doing_scroll_task")
    click_search_discovery_if_exists()
    start_time = time.time()
    last_ocr_check = 0
    ocr_done_event.clear()
    print("开始做任务。。。")
    while time.time() - start_time < duration:
        if should_stop():
            return
        wait_if_paused()
        if ocr_done_event.is_set():
            print("OCR检测到任务已完成，提前返回")
            break
        start_x = random.randint(screen_width // 5, screen_width // 2)
        start_y = random.randint(int(screen_height * 0.62), int(screen_height * 0.86))
        end_x = random.randint(max(1, start_x - 100), min(screen_width - 1, start_x + 20))
        end_y = random.randint(int(screen_height * 0.16), int(screen_height * 0.52))
        swipe_time = random.uniform(0.25, 0.5)
        elapsed = int(time.time() - start_time)
        print(f"模拟滑动 {elapsed}S")
        human_swipe(start_x, start_y, end_x, end_y, swipe_time)
        time.sleep(random.uniform(0.5, 0.9))
        now = time.time()
        if now - last_ocr_check >= 5:
            last_ocr_check = now
            try:
                screenshot_started = time.perf_counter()
                screenshot = d.screenshot(format="opencv")
                screenshot_time = time.perf_counter() - screenshot_started
                ignore_targets = ["已得"] if has_any(get_page_texts(30), ["淘宝购物清单"]) else []
                start_ocr_done_check_async(screenshot, screenshot_time, ignore_targets=ignore_targets)
            except Exception as exc:
                print("OCR截图失败", exc)
    back_to_task()


def handle_quiz_answer():
    set_action("doing_quiz_task")
    texts = get_page_texts(80)
    if not has_any(texts, rule_list("quiz_words", ["淘金币趣味答题", "我选好了"])):
        return False
    notify_phone(d, "趣味课堂需要答题")
    print("趣味课堂等待人工提交，最多30秒")
    wait_start = time.time()
    while time.time() - wait_start < 30:
        if should_stop():
            return True
        wait_if_paused()
        texts = get_page_texts(80)
        submit_btn = d(classNameMatches=ACTION_CLASS, text="我选好了")
        if not has_any(texts, rule_list("quiz_words", ["淘金币趣味答题", "我选好了"])) or not submit_btn.exists(timeout=0.2):
            print("检测到趣味课堂已人工提交，继续后续流程")
            time.sleep(1)
            return True
        time.sleep(1)
    print("趣味课堂等待人工提交超时，按默认逻辑选择A")
    option_a = d(className="android.widget.TextView", text="A")
    if option_a.exists(timeout=1):
        bounds = safe_obj_bounds(option_a, "选项A")
        if not bounds:
            return False
        print("趣味课堂选择A", bounds)
        human_click(screen_width // 2, (bounds[1] + bounds[3]) // 2)
        time.sleep(1)
    submit_btn = d(classNameMatches=ACTION_CLASS, text="我选好了")
    if submit_btn.exists(timeout=2):
        bounds = safe_obj_bounds(submit_btn, "我选好了")
        if not bounds:
            return False
        print("趣味课堂点击我选好了", bounds)
        human_click_bounds(bounds)
        time.sleep(2)
    return True


def shop_subscribe_swipe_check():
    for _ in range(2):
        human_swipe(screen_width // 2, int(screen_height * 0.72), screen_width // 2, int(screen_height * 0.36), 0.25)
        time.sleep(0.4)
        human_swipe(screen_width // 2, int(screen_height * 0.36), screen_width // 2, int(screen_height * 0.72), 0.25)
        time.sleep(0.4)


def click_first_text(pattern, label, timeout=0.6):
    target = d(classNameMatches=ACTION_CLASS, textMatches=pattern)
    if target.exists(timeout=timeout):
        bounds = safe_obj_bounds(target, label)
        if not bounds:
            return False
        print("点击店铺订阅任务按钮", label, safe_obj_text(target, label), bounds)
        human_click_bounds(bounds)
        time.sleep(1.2)
        return True
    return False


def shop_subscribe_action_pairs():
    defaults = [
        r"最多还可以领.*=>最多还可以领",
        r"立即领.*=>立即领",
        r"订阅\s*\+\s*\d+.*=>订阅",
        r"进店.*=>进店",
        r"已关注.*=>已关注",
        r"取消关注.*=>取消关注",
    ]
    pairs = []
    for item in rule_list("shop_subscribe_action_pairs", defaults):
        if "=>" in item:
            pattern, label = item.split("=>", 1)
        else:
            pattern, label = item, item
        pattern = pattern.strip()
        label = label.strip() or pattern
        if pattern:
            pairs.append((pattern, label))
    return pairs


def handle_shop_subscribe_task():
    set_action("doing_shop_subscribe_task")
    print("开始处理店铺订阅任务")
    idle_count = 0
    loop_count = 0
    while idle_count < 3 and loop_count < 40:
        loop_count += 1
        if should_stop():
            return
        wait_if_paused()
        texts = get_page_texts(80)
        print("店铺订阅任务页面文本", texts[:12])
        clicked = False
        for pattern, label in shop_subscribe_action_pairs():
            if click_first_text(pattern, label):
                idle_count = 0
                clicked = True
                if label not in ["最多还可以领", "已关注"]:
                    shop_subscribe_swipe_check()
                break
        if clicked:
            continue
        if looks_like_shop_subscribe_task(texts):
            print("店铺订阅任务仍有标识文字，继续滑动查找按钮")
            idle_count = 0
            shop_subscribe_swipe_check()
            continue
        idle_count += 1
        print("店铺订阅任务未找到标识文字", idle_count)
        time.sleep(1)
    back_to_task()


def find_task_action_button():
    root = dump_root()
    if root is None:
        return None, None
    parent = {child: node for node in root.iter("node") for child in node}
    candidates = []
    for node in root.iter("node"):
        bounds = parse_bounds(node.attrib.get("bounds"))
        fields = [
            node.attrib.get("text") or "",
            node.attrib.get("content-desc") or "",
            node.attrib.get("resource-id") or "",
            node.attrib.get("class") or "",
        ]
        visible_text = fields[0] or fields[1] or fields[2] or fields[3]
        if not bounds or bounds[1] < 140:
            continue
        if not any(re.search(action_text_pattern(), field) for field in fields if field):
            continue
        if bounds[0] < int(screen_width * 0.68):
            print("跳过非右侧动作文本", visible_text, bounds)
            continue
        target = node
        target_bounds = bounds
        while target is not None and target.attrib.get("clickable") != "true":
            target = parent.get(target)
            target_bounds = parse_bounds(target.attrib.get("bounds")) if target is not None else None
        if target is None or not target_bounds:
            continue
        candidates.append((node, target_bounds, bounds, visible_text))

    print(f"任务动作按钮XML匹配到{len(candidates)}个")
    seen = set()
    for index, (action_node, target_bounds, text_bounds, button_text) in enumerate(sorted(candidates, key=lambda item: (item[1][1], item[1][0]))):
        if target_bounds in seen:
            continue
        seen.add(target_bounds)
        row_bounds = find_action_row_bounds(action_node, parent, text_bounds)
        task_name = collect_row_text(root, row_bounds, fallback=button_text)
        print("任务动作按钮候选", index, task_name, "button", target_bounds, "row", row_bounds)
        if skip_task_name(task_name):
            print("跳过任务，不点击动作按钮", task_name)
            continue
        if task_is_done_text(task_name):
            print("跳过已完成动作按钮", task_name)
            continue
        click_key = f"action:{target_bounds}"
        if click_key in invalid_click_keys:
            print("跳过刚才点击无效的动作按钮", task_name, target_bounds)
            continue
        clicked_key = task_click_key(task_name)
        click_limit = task_click_limit(task_name)
        if have_clicked.get(clicked_key, 0) >= click_limit:
            print("跳过已点击多次任务", task_name, clicked_key, have_clicked[clicked_key], "上限", click_limit)
            continue
        return XmlClickTarget(target_bounds), task_name
    return None, None


def find_action_row_bounds(action_node, parent, action_bounds):
    target = parent.get(action_node)
    best_bounds = None
    while target is not None:
        bounds = parse_bounds(target.attrib.get("bounds"))
        if bounds and contains_bounds(bounds, action_bounds):
            width = bounds[2] - bounds[0]
            height = bounds[3] - bounds[1]
            if bounds[0] <= 120 and width >= screen_width * 0.55 and 80 <= height <= 360:
                best_bounds = bounds
                break
        target = parent.get(target)
    return best_bounds or action_bounds


def collect_row_text(root, row_bounds, fallback=""):
    items = []
    for node in root.iter("node"):
        text = node_text_value(node)
        bounds = parse_bounds(node.attrib.get("bounds"))
        if not text or not bounds or not contains_bounds(row_bounds, bounds):
            continue
        if text.startswith("O1CN"):
            continue
        items.append((bounds[1], bounds[0], text))
    items.sort(key=lambda item: (item[0], item[1]))
    combined = " ".join(text for _, _, text in items)
    return combined or fallback


def find_coin_row_buttons():
    rows = []
    root = dump_root()
    if root is None:
        return rows
    seen = set()
    for node in root.iter("node"):
        node_class = node.attrib.get("class") or ""
        if node_class not in {"android.view.View", "android.widget.FrameLayout", "android.widget.LinearLayout"}:
            continue
        if node.attrib.get("clickable") != "true":
            continue
        row_bounds = parse_bounds(node.attrib.get("bounds"))
        if not row_bounds:
            continue
        left, top, right, bottom = row_bounds
        height = bottom - top
        width = right - left
        if top < 180 or height < 55 or height > 560 or width < screen_width * 0.45:
            continue
        child_texts = []
        has_reward = False
        has_right_action = False
        for child in node.iter("node"):
            text = child.attrib.get("text") or ""
            child_bounds = parse_bounds(child.attrib.get("bounds"))
            if not text or not child_bounds:
                continue
            if child_bounds[1] < top or child_bounds[3] > bottom:
                continue
            child_texts.append((child_bounds[1], child_bounds[0], text))
            if re.match(r"^\+\d+$", text):
                has_reward = True
            if child_bounds[0] >= int(screen_width * 0.68) and re.search(action_text_pattern(), text):
                has_right_action = True
        if not child_texts or not (has_reward or has_right_action):
            continue
        combined = " ".join(text for _, _, text in child_texts)
        if task_is_done_text(combined):
            print("跳过已完成金币任务行", combined)
            continue
        if skip_task_name(combined):
            print("跳过整行任务", combined)
            continue
        task_candidates = [item for item in child_texts if not re.match(r"^\+\d+$", item[2]) and not item[2].startswith("O1CN")]
        if not task_candidates:
            continue
        task_candidates.sort(key=lambda item: (item[0], item[1]))
        task_name = task_candidates[0][2]
        key = (row_bounds, task_name)
        if key in seen:
            continue
        seen.add(key)
        rows.append((row_bounds, task_name, combined))
    rows.sort(key=lambda item: (item[0][1], item[0][0]))
    print("XML金币任务行识别", [(bounds, name) for bounds, name, _ in rows[:8]])
    return rows[:8]


def ocr_action_words():
    return ["去完成", "逛一逛", "立即领", "领取奖励", "去逛逛", "爱心捐"]


def ocr_text_contains(text, words):
    compact = normalize_text(text)
    return any(normalize_text(word) in compact for word in words)


def ocr_row_text(items, action_bounds):
    action_y = (action_bounds[1] + action_bounds[3]) // 2
    row_items = []
    for item in items:
        bounds = item["bounds"]
        x_center = (bounds[0] + bounds[2]) // 2
        y_center = (bounds[1] + bounds[3]) // 2
        if x_center >= int(screen_width * 0.78):
            continue
        if abs(y_center - action_y) <= 115:
            row_items.append((bounds[1], bounds[0], item["text"]))
    row_items.sort(key=lambda value: (value[0], value[1]))
    return " ".join(text for _, _, text in row_items).strip()


def find_ocr_task_action_buttons():
    started = time.perf_counter()
    screenshot = d.screenshot(format="opencv")
    screenshot_time = time.perf_counter() - started
    items, timings = read_ocr_results(screenshot, max_width=900, gpu=True, min_confidence=0.25)
    timings["screenshot"] = screenshot_time
    timings["total"] += screenshot_time
    print("OCR任务按钮扫描", len(items), {k: round(v, 3) if isinstance(v, float) else v for k, v in timings.items()})

    action_items = []
    for item in items:
        bounds = item["bounds"]
        x_center = (bounds[0] + bounds[2]) // 2
        if x_center < int(screen_width * 0.65):
            continue
        if not ocr_text_contains(item["text"], ocr_action_words()):
            continue
        task_name = ocr_row_text(items, bounds) or item["text"]
        action_items.append((bounds[1], bounds[0], bounds, item["text"], task_name))

    action_items.sort(key=lambda value: (value[0], value[1]))
    print("OCR任务按钮候选", [(bounds, text, task_name) for _, _, bounds, text, task_name in action_items[:8]])
    return [(bounds, text, task_name) for _, _, bounds, text, task_name in action_items[:8]]


def ocr_task_list_is_at_bottom():
    screenshot = d.screenshot(format="opencv")
    items, timings = read_ocr_results(screenshot, max_width=900, gpu=True, min_confidence=0.25)
    bottom_words = ["收起更多任务", "注：以上金币额"]
    hits = [item for item in items if ocr_text_contains(item["text"], bottom_words)]
    print("OCR底部判断", bool(hits), [(item["text"], item["bounds"]) for item in hits[:3]], {k: round(v, 3) if isinstance(v, float) else v for k, v in timings.items()})
    return bool(hits)


def scan_ocr_once(label):
    screenshot = d.screenshot(format="opencv")
    items, timings = read_ocr_results(screenshot, max_width=900, gpu=True, min_confidence=0.25)
    print("OCR扫描", label, len(items), {k: round(v, 3) if isinstance(v, float) else v for k, v in timings.items()})
    return items


def click_from_ocr_items(items, words, label):
    hits = [item for item in items if ocr_text_contains(item["text"], words)]
    print("OCR查找", label, [(item["text"], item["bounds"]) for item in hits[:5]])
    if not hits:
        return False
    bounds = sorted(hits, key=lambda item: (item["bounds"][1], item["bounds"][0]))[0]["bounds"]
    print("OCR点击", label, bounds)
    human_click_bounds(bounds)
    time.sleep(1.5)
    return True


def click_text_by_xml_or_ocr(words, label, timeout=0.6, ocr_items=None):
    pattern = "|".join(re.escape(word) for word in words)
    target = d(classNameMatches=ACTION_CLASS, textMatches=pattern)
    if target.exists(timeout=timeout):
        bounds = safe_obj_bounds(target, label)
        if not bounds:
            return False
        print("XML点击", label, safe_obj_text(target, label), bounds)
        human_click_bounds(bounds)
        time.sleep(1.5)
        return True
    items = ocr_items if ocr_items is not None else scan_ocr_once(label)
    return click_from_ocr_items(items, words, label)


def handle_good_shop_child_task():
    print("进入逛好店子任务，循环处理订阅/立即领")
    idle_count = 0
    loop_count = 0
    while idle_count < 2 and loop_count < 12:
        loop_count += 1
        if should_stop():
            return
        wait_if_paused()
        texts = get_page_texts(100)
        print("逛好店子任务文本", texts[:16])
        ocr_items = None
        if not d(classNameMatches=ACTION_CLASS, textMatches="订阅|立即领").exists(timeout=0.2):
            ocr_items = scan_ocr_once("逛好店子任务")
        if click_text_by_xml_or_ocr(["订阅"], "订阅", ocr_items=ocr_items):
            idle_count = 0
            time.sleep(1)
            continue
        if click_text_by_xml_or_ocr(["立即领"], "立即领", ocr_items=ocr_items):
            idle_count = 0
            human_back()
            time.sleep(1.5)
            continue
        idle_count += 1
        time.sleep(0.8)
    print("逛好店子任务未再找到订阅/立即领，返回今日推荐页")
    human_back()
    time.sleep(2)


def click_good_shop_entry_once(texts):
    candidates = find_good_shop_entry_candidates()
    if not candidates:
        candidates = find_good_shop_entry_candidates_by_ocr()
    print("逛好店入口候选", [(text, key, bounds) for bounds, text, key in candidates[:8]])
    for bounds, text, key in candidates:
        if key in good_shop_failed_entry_keys:
            print("跳过刚才点击无效的逛好店入口", key)
            continue
        if good_shop_entry_clicks.get(key, 0) >= 5:
            print("跳过同一店铺入口，已点击5次", key)
            continue
        break
    else:
        key = good_shop_store_key(texts)
        return False, key
    package_name, activity_name = get_current_app(d)
    append_good_shop_trace(f"点击前 key={key} package={package_name} activity={activity_name} texts={texts[:30]}")
    try:
        xml_path = GOOD_SHOP_TRACE_LOG.parent / f"good_shop_before_{int(time.time())}.xml"
        xml_path.write_text(d.dump_hierarchy(compressed=False, pretty=True), encoding="utf-8")
        append_good_shop_trace(f"点击前XML={xml_path.name}")
    except Exception as exc:
        append_good_shop_trace(f"点击前XML保存失败={exc}")
    print("XML点击", "逛好店入口", text, bounds)
    human_click_bounds(bounds)
    good_shop_entry_clicks[key] = good_shop_entry_clicks.get(key, 0) + 1
    print("记录逛好店入口点击", key, good_shop_entry_clicks[key])
    time.sleep(1.5)
    after_package, after_activity = get_current_app(d)
    after_texts = get_page_texts(60)
    append_good_shop_trace(f"点击后 key={key} count={good_shop_entry_clicks[key]} package={after_package} activity={after_activity} texts={after_texts[:30]}")
    return True, key


def handle_good_shop_task():
    set_action("doing_scroll_task", current_task="逛好店赚一大波金币")
    print("处理逛好店赚一大波金币：最多翻5页查找逛店铺/最多还可领")
    swipe_count = 0
    loop_count = 0
    while swipe_count < 5 and loop_count < 20:
        loop_count += 1
        if should_stop():
            return
        wait_if_paused()
        texts = get_page_texts(120)
        print("逛好店页面文本", {"循环": loop_count, "已下翻": swipe_count}, texts[:20])
        clicked, entry_key = click_good_shop_entry_once(texts)
        if clicked:
            after_texts = get_page_texts(120)
            print("逛好店入口点击后页面文本", entry_key, after_texts[:20])
            if looks_like_good_shop_child_page(after_texts):
                handle_good_shop_child_task()
            else:
                print("逛好店入口点击后未进入子任务，继续查找", entry_key)
                good_shop_failed_entry_keys.add(entry_key)
            continue
        swipe_count += 1
        print("本页未找到逛好店入口，执行下翻", swipe_count)
        human_swipe(screen_width // 2, int(screen_height * 0.78), screen_width // 2, int(screen_height * 0.35), 0.35)
        time.sleep(1.2)
    print("逛好店页面实际下翻5次仍未找到入口，返回任务列表")
    back_to_task()


def handle_after_task_click(task_name, click_key=None):
    set_action("clicking_task", current_task=task_name)
    append_key_log(f"开始任务: {task_name}")
    time.sleep(2)
    page_type, package_name, activity_name, texts = classify_current_page()
    print("任务点击后页面判定", {"task": task_name, "page": page_type, "package": package_name, "activity": activity_name, "texts": texts[:12]})
    if looks_like_shop_browse_task(task_name, texts):
        notify_phone(d, "浏览店铺任务")
    if task_is_good_shop_task(task_name) and looks_like_good_shop_page(texts):
        handle_good_shop_task()
        return
    if page_type in ["daily_task_list", "energy_task_list"]:
        print("点击后仍在任务列表，记录无效点击并继续")
        append_key_log(f"任务未进入: {task_name}")
        if click_key:
            invalid_click_keys.add(click_key)
        return
    if page_type == "quiz":
        handle_quiz_answer()
        back_to_task()
        return
    if page_type == "shop_subscribe_task":
        handle_shop_subscribe_task()
        return
    if page_type == "task_done":
        append_key_log(f"任务完成并返回: {task_name}")
        back_to_task()
        return
    if page_type == "taobao_browse_task":
        browse_task_loop()
        return
    if page_type == "external_app":
        do_one_external_swipe()
        back_to_task()
        return
    if page_type == "coin_home":
        if RUN_MODE == "energy":
            enter_energy_task_list_from_coin_home()
            return
        enter_task_list_from_coin_home()
        return
    do_one_external_swipe()
    back_to_task()


def back_to_task():
    set_action("returning_to_task_list")
    print("开始返回任务页面")
    back_count = 0
    cross_app_count = 0
    cross_app_switch_count = 0
    browse_back_count = 0
    loop_count = 0
    while True:
        if should_stop():
            return
        wait_if_paused()
        loop_count += 1
        if loop_count > 30:
            if RUN_MODE == "energy":
                print("返回体力任务页循环过多，停止本次返回，不切到赚金币")
                return
            print("返回任务页循环过多，使用小插件入口恢复")
            open_coin_home_direct()
            return
        page_type, package_name, activity_name, texts = classify_current_page()
        print("返回中页面判定", {"page": page_type, "package": package_name, "activity": activity_name, "texts": texts[:8]})
        if page_type in ["daily_task_list", "energy_task_list"]:
            print("当前是任务列表画面，停止返回")
            return
        if page_type == "task_done":
            browse_back_count = 0
            human_back()
            time.sleep(1.5)
            continue
        if page_type == "taobao_browse_task":
            browse_back_count += 1
            if browse_back_count > 3:
                print("连续3次仍在浏览任务页，强制重启淘宝并打开淘金币入口")
                open_coin_home_direct()
                return
            print("返回任务页时仍在浏览任务页，先后退回任务列表", browse_back_count)
            human_back()
            time.sleep(1.5)
            continue
        browse_back_count = 0
        if page_type == "shop_subscribe_task":
            print("返回任务页时仍在店铺订阅任务页，先后退回任务列表")
            human_back()
            time.sleep(1.5)
            continue
        if page_type == "coin_home":
            if RUN_MODE == "energy":
                if enter_energy_task_list_from_coin_home():
                    return
                print("体力模式未能从首页回到赚体力列表，继续后退")
                human_back()
                time.sleep(1.5)
                continue
            if enter_task_list_from_coin_home():
                return
        if package_name != TB_APP:
            cross_app_count += 1
            if package_name == "com.android.launcher":
                print("当前已到桌面，切回淘宝而不关闭淘宝")
                d.app_start(TB_APP, stop=False, use_monkey=False)
                time.sleep(3)
                continue
            if cross_app_count <= CROSS_APP_BACK_LIMIT:
                print("当前不在淘宝，先尝试返回上一层", cross_app_count)
                human_back()
                time.sleep(2)
                continue
            cross_app_switch_count += 1
            if cross_app_switch_count <= 2:
                print("外部App连续返回4次仍未回淘宝，先切回淘宝", cross_app_switch_count)
                d.app_start(TB_APP, stop=False, use_monkey=False)
                time.sleep(3)
                continue
            print("外部App多次切回仍未回淘宝，强制重启淘宝并打开淘金币入口")
            open_coin_home_direct(stop=True)
            return
        if click_daily_version_if_exists():
            wait_and_click_earn_more_after_daily()
            continue
        back_count += 1
        if back_count > BACK_RESTART_LIMIT:
            if RUN_MODE == "energy":
                print("体力模式未知页连续后退4次仍无法回到体力列表，停止本次返回")
                return
            print("淘宝未知页连续后退4次仍无法回到任务页，强制重启淘宝并打开淘金币入口")
            open_coin_home_direct(stop=True)
            return
        print("点击后退", back_count)
        human_back()
        time.sleep(2)


def ensure_task_list_at_start():
    set_action("finding_entry")
    for attempt in range(2):
        page_type, package_name, activity_name, texts = classify_current_page()
        print("启动前页面判定", {"attempt": attempt + 1, "page": page_type, "package": package_name, "activity": activity_name, "texts": texts[:10]})
        if page_type == "daily_task_list":
            return True
        if page_type == "good_shop_page":
            print("启动时已在逛好店页面，直接继续处理")
            handle_good_shop_task()
            return True
        if page_type == "quiz":
            handle_quiz_answer()
            back_to_task()
            return looks_like_task_list_page()
        if page_type == "taobao_browse_task":
            print("启动时已在浏览任务页，先继续浏览并返回任务列表")
            browse_task_loop()
            return looks_like_task_list_page()
        if page_type == "coin_home":
            return enter_task_list_from_coin_home()
        print("当前不是日常任务列表，使用小插件跳转入口")
        open_coin_home_direct()
        if wait_for_task_list_after_entry():
            return True
        if attempt == 0:
            print("启动入口仍未找到，先返回一次再重试")
            human_back()
            time.sleep(2)
    return False


def ensure_energy_task_list_at_start():
    set_action("finding_entry")
    page_type, package_name, activity_name, texts = classify_current_page()
    print("做体力启动前页面判定", {"page": page_type, "package": package_name, "activity": activity_name, "texts": texts[:12]})
    if page_type == "energy_task_list":
        return True
    message = "做体力模式只处理当前已打开的做任务赚体力页面，不自动寻找入口"
    print(message)
    append_key_log(message)
    update_status(last_error=message, action="idle")
    return False


def energy_task_loop():
    global finish_count
    no_task_scroll_count = 0
    if not ensure_energy_task_list_at_start():
        return False
    print("进入做体力任务执行循环")
    while True:
        try:
            if should_stop():
                print("收到停止请求，退出做体力循环")
                return False
            wait_if_paused()
            time.sleep(1)
            page_type, package_name, activity_name, texts = classify_current_page()
            print("做体力操作前页面判定", {"page": page_type, "package": package_name, "activity": activity_name, "texts": texts[:10]})
            if page_type == "energy_task_list":
                check_verify(d)
                set_action("finding_task")
                action_view, task_name = find_task_action_button()
                if action_view:
                    print("做体力点击按钮", task_name)
                    set_action("clicking_task", current_task=task_name)
                    clicked_key = task_click_key(task_name)
                    have_clicked[clicked_key] = have_clicked.get(clicked_key, 0) + 1
                    print("记录任务点击次数", clicked_key, have_clicked[clicked_key])
                    bounds = action_view.bounds()
                    action_view.click()
                    handle_after_task_click(task_name, f"energy:{bounds}")
                    no_task_scroll_count = 0
                    continue
                no_task_scroll_count += 1
                print("做体力未找到可点击按钮，继续下翻", no_task_scroll_count)
                if no_task_scroll_count <= 8:
                    scroll_task_list_once()
                    continue
                print("做体力连续下翻仍未找到可点击按钮，认为体力任务已全部完成，准备切换赚金币")
                append_key_log("做体力任务已完成，切换淘金币任务")
                have_clicked.clear()
                invalid_click_keys.clear()
                return True
            if page_type in ["quiz", "shop_subscribe_task", "task_done", "taobao_browse_task", "external_app", "coin_home", "unknown_taobao_page"]:
                handle_after_task_click("做体力任务页外处理")
                continue
            print("做体力模式离开任务列表，结束本轮", page_type)
            return False
        except Exception as exc:
            print("做体力循环异常", exc)
            update_status(last_error=str(exc), action="error")
            back_to_task()
    return False


def main_loop():
    global finish_count, RUN_MODE
    if RUN_MODE == "energy":
        if not energy_task_loop():
            return
        RUN_MODE = "taojinbi"
        print("体力任务完成，开始执行淘金币任务")
    no_task_scroll_count = 0
    coin_home_fail_count = 0
    update_status(running=True, paused=False, action="starting", exclude_tags=get_exclude_tags(), last_error=None)
    if not ensure_task_list_at_start():
        started = False
        for retry in range(2):
            print("启动阶段未找到任务列表，硬重启重试", retry + 1)
            stop_known_external_apps()
            open_coin_home_direct(stop=True)
            if wait_for_task_list_after_entry(max_wait=15):
                started = True
                break
        if not started:
            message = "启动阶段多次未进入任务列表，正常结束"
            print(message)
            append_key_log(message)
            update_status(running=False, paused=False, action="idle", last_error=message)
            return
    print("进入淘金币任务执行循环")
    while True:
        try:
            if should_stop():
                print("收到停止请求，退出主循环")
                break
            wait_if_paused()
            time.sleep(1)
            page_type, package_name, activity_name, texts = classify_current_page()
            print("操作前页面判定", {"page": page_type, "package": package_name, "activity": activity_name, "texts": texts[:8]})
            if page_type == "good_shop_page":
                handle_good_shop_task()
                continue
            if page_type == "quiz":
                handle_quiz_answer()
                back_to_task()
                continue
            if page_type == "shop_subscribe_task":
                handle_shop_subscribe_task()
                continue
            if page_type == "task_done":
                back_to_task()
                continue
            if page_type == "taobao_browse_task":
                browse_task_loop()
                continue
            if page_type == "external_app":
                do_one_external_swipe()
                back_to_task()
                continue
            if page_type == "coin_home":
                if enter_task_list_from_coin_home():
                    coin_home_fail_count = 0
                else:
                    coin_home_fail_count += 1
                    print("淘金币首页连续未找到任务入口", coin_home_fail_count)
                    if coin_home_fail_count >= 3:
                        print("淘金币首页连续3次无法进入任务列表，强制重启淘宝并打开淘金币入口")
                        open_coin_home_direct(stop=True)
                        wait_for_task_list_after_entry(max_wait=15)
                        coin_home_fail_count = 0
                continue
            if page_type != "daily_task_list":
                coin_home_fail_count = 0
                do_one_external_swipe()
                back_to_task()
                continue

            coin_home_fail_count = 0
            check_verify(d)
            set_action("finding_task")
            if click_daily_version_if_exists():
                wait_and_click_earn_more_after_daily()
                no_task_scroll_count = 0
                continue

            action_view, task_name = find_task_action_button()
            if action_view:
                print("点击按钮", task_name)
                set_action("clicking_task", current_task=task_name)
                clicked_key = task_click_key(task_name)
                have_clicked[clicked_key] = have_clicked.get(clicked_key, 0) + 1
                print("记录任务点击次数", clicked_key, have_clicked[clicked_key])
                bounds = action_view.bounds()
                action_view.click()
                handle_after_task_click(task_name, f"action:{bounds}")
                no_task_scroll_count = 0
                continue

            reward_btn = d(classNameMatches=ACTION_CLASS, textMatches=rule_text("reward_button_pattern", "领取奖励|立即领取|点击得"))
            if reward_btn.exists(timeout=0.2):
                bounds = safe_obj_bounds(reward_btn, "奖励按钮")
                if not bounds:
                    continue
                text = safe_obj_text(reward_btn, "奖励按钮") or "领取奖励"
                print("点击奖励按钮", text, bounds)
                append_key_log(f"领取奖励: {text}")
                set_action("clicking_task", current_task=text)
                human_click_bounds(bounds)
                finish_count += 1
                no_task_scroll_count = 0
                time.sleep(2)
                continue

            if expand_more_coin_tasks():
                no_task_scroll_count = 0
                continue

            print("原文字按钮未找到，开始查找金币任务行")
            set_action("finding_task")
            coin_rows = find_coin_row_buttons()
            clicked_row = False
            for row_bounds, row_task_name, row_combined in coin_rows:
                click_key = f"row:{row_bounds}"
                if click_key in invalid_click_keys:
                    print("跳过刚才点击无效的金币任务行", row_task_name, row_bounds, row_combined)
                    continue
                clicked_key = task_click_key(row_task_name)
                click_limit = task_click_limit(row_task_name)
                if have_clicked.get(clicked_key, 0) >= click_limit:
                    print("跳过已点击多次金币任务行", row_task_name, clicked_key, have_clicked[clicked_key], "上限", click_limit)
                    continue
                if row_bounds[3] >= screen_height - 20:
                    print("金币任务行贴近屏幕底部，交给OCR右侧按钮兜底", row_task_name, row_bounds, row_combined)
                    continue
                print("点击金币任务行", row_task_name, row_bounds, row_combined)
                set_action("clicking_task", current_task=row_task_name)
                have_clicked[clicked_key] = have_clicked.get(clicked_key, 0) + 1
                print("记录任务点击次数", clicked_key, have_clicked[clicked_key])
                human_click_bounds(row_bounds)
                handle_after_task_click(row_task_name, click_key)
                clicked_row = True
                no_task_scroll_count = 0
                break
            if clicked_row:
                continue

            ocr_clicked = False
            for bounds, action_text, ocr_task_name in find_ocr_task_action_buttons():
                if skip_task_name(ocr_task_name):
                    print("OCR跳过任务", action_text, ocr_task_name, bounds)
                    continue
                if task_is_done_text(ocr_task_name):
                    print("OCR跳过已完成任务", action_text, ocr_task_name, bounds)
                    continue
                clicked_key = task_click_key(ocr_task_name)
                click_limit = task_click_limit(ocr_task_name)
                if have_clicked.get(clicked_key, 0) >= click_limit:
                    print("OCR跳过已点击多次任务", action_text, ocr_task_name, clicked_key, have_clicked[clicked_key], "上限", click_limit)
                    continue
                click_key = f"ocr:{clicked_key}:{bounds}"
                if click_key in invalid_click_keys:
                    print("OCR跳过刚才点击无效任务", action_text, ocr_task_name, bounds)
                    continue
                print("OCR点击右侧任务按钮", action_text, ocr_task_name, bounds)
                set_action("clicking_task", current_task=ocr_task_name)
                have_clicked[clicked_key] = have_clicked.get(clicked_key, 0) + 1
                print("记录任务点击次数", clicked_key, have_clicked[clicked_key])
                human_click_bounds(bounds)
                handle_after_task_click(ocr_task_name, click_key)
                ocr_clicked = True
                no_task_scroll_count = 0
                break
            if ocr_clicked:
                continue

            debug_texts = get_page_texts(None)
            print("当前页面前20个文本", debug_texts[:20])
            if task_list_is_at_bottom(debug_texts) or ocr_task_list_is_at_bottom():
                print("已到任务列表底部，未找到可点击任务，结束本轮")
                break
            no_task_scroll_count += 1
            print("未找到可点击按钮，继续下翻", no_task_scroll_count)
            if no_task_scroll_count <= 8:
                scroll_task_list_once()
                continue
            print("连续下翻后仍未找到可点击按钮，关闭淘宝并重新打开淘金币入口")
            have_clicked.clear()
            invalid_click_keys.clear()
            open_coin_home_direct(stop=True)
            if not enter_task_list_from_coin_home():
                raise Exception("重启后仍未进入淘金币任务入口")
            no_task_scroll_count = 0
            continue
        except Exception as exc:
            print("主循环异常", exc)
            update_status(last_error=str(exc), action="error")
            back_to_task()


try:
    main_loop()
finally:
    update_status(running=False, paused=False, action="idle")
    ctx.close()
    print(f"共自动化完成{finish_count}个任务")
    d.shell("settings put system accelerometer_rotation 0")
    print("关闭手机自动旋转")
    minutes, seconds = divmod(int(time.time() - start_time_all), 60)
    print(f"共耗时: {minutes} 分钟 {seconds} 秒")
