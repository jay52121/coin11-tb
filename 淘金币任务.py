import random
import re
import time
import xml.etree.ElementTree as ET

import uiautomator2 as u2

from utils import check_chars_exist, other_app, get_current_app, select_device, check_verify, TB_APP

COIN_HOME_URL = "https://pages-fast.m.taobao.com/wow/z/tmtjb/town/home?utparam=%7B%22ranger_buckets_native%22%3A%22tsp6443_32421_standardVersion%22%7D&spm=a2141.1.iconsv5.5&miniappSourceChannel=homepage&scm=1007.home_icon.lingjb.d&x-ssr=true&disableNav=YES&x-sec=wua&pha_h5=true&pha_nav=true&uniapp_id=1011525&uniapp_page=home&hd_from=tbHome"
VERSION = "coin-row-xml-log-20260525-0005"
ACTION_CLASS = r"android.widget.Button|android.widget.TextView|android.view.View"
ACTION_TEXT_PATTERN = r"去完成|去逛逛|去浏览|逛一逛|立即领|去领取|去看看|搜一下|玩一把|捐一笔|逛一下|点击去逛|领取奖励|立即领取|点击得"
BROWSE_TASK_DURATION = 30
BACK_RESTART_LIMIT = 4
CROSS_APP_BACK_LIMIT = 3

have_clicked = {}
invalid_click_keys = set()
finish_count = 0
start_time_all = time.time()

print(f"淘金币任务脚本版本: {VERSION}")
selected_device = select_device()
d = u2.connect(selected_device)
print(f"已成功连接设备：{selected_device}")
screen_width, screen_height = d.window_size()

ctx = d.watch_context()
ctx.when("O1CN012qVB9n1tvZ8ATEQGu_!!6000000005964-2-tps-144-144").click()
ctx.when("O1CN01sORayC1hBVsDQRZoO_!!6000000004239-2-tps-426-128.png_").click()
ctx.when("领取今日奖励").click()
ctx.when("确认").click()
ctx.when("确定").click()
ctx.when("刷新").click()
ctx.when("点击刷新").click()
ctx.when(xpath="//android.app.Dialog//android.widget.Button[contains(text(), '-tps-')]").click()
ctx.when(xpath="//android.app.Dialog//android.widget.Button[@text='关闭']").click()
ctx.when(xpath="//android.widget.FrameLayout[@resource-id='com.taobao.taobao:id/poplayer_native_state_center_layout_frame_id']//android.widget.ImageView[@content-desc='关闭按钮']").click()
ctx.start()


def parse_bounds(bounds_text):
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds_text or "")
    if not match:
        return None
    return tuple(map(int, match.groups()))


def center(bounds):
    return ((bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2)


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
        if len(texts) >= limit:
            break
    return texts


def has_any(texts, keys):
    return any(key in text for text in texts for key in keys)


def skip_task_name(task_name):
    if not task_name:
        return False
    skip_words = ["下单", "快手", "评价", "助力"]
    return check_chars_exist(task_name) or any(word in task_name for word in skip_words)


def task_is_done_text(task_name):
    if not task_name:
        return False
    done_words = ["已完成", "已领取", "已得", "任务已完成", "记得明天再来"]
    return "(1/1)" in task_name or any(word in task_name for word in done_words)


def looks_like_search_browse_page(texts):
    return has_any(texts, ["搜索后浏览立得奖励", "搜索有福利", "淘宝精选", "搜索发现", "历史搜索"])


def looks_like_coin_home_page(texts=None):
    if texts is None:
        texts = get_page_texts()
    has_home = has_any(texts, ["淘金币首页", "淘金币标题", "可抵", "购物车", "赚金币抵钱", "赚更多金币"])
    has_task = has_any(texts, ["今日速赚", "快速赚", "完成下方任务", "更多金币等你赚", "任务到访得金币", "每日来任务面板"])
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
    browse_keys = ["浏览", "浏览25秒", "已得", "累计已得", "热销", "爆款", "抵扣", "金币热卖价", "近七天卖出", "已售"]
    return has_any(texts, browse_keys)


def looks_like_daily_task_list_by_xml():
    root = dump_root()
    if root is None:
        return False
    texts = [node.attrib.get("text") or "" for node in root.iter("node")]
    texts = [text for text in texts if text]
    if looks_like_search_browse_page(texts):
        return False
    has_fast = has_any(texts, ["今日速赚", "快速赚", "今日快速赚奖励已拿完", "记得明天再来"])
    has_task_area = has_any(texts, ["完成下方任务", "更多金币等你赚", "展开", "任务到访得金币", "每日来任务面板", "逛清单", "淘金币趣味课堂", "浏览15秒"])
    action_count = sum(1 for text in texts if re.search(ACTION_TEXT_PATTERN, text or ""))
    if (has_fast and has_task_area) or (has_task_area and action_count > 0):
        print("XML确认日常任务列表", {"今日速赚": has_fast, "任务区": has_task_area, "动作文本": action_count})
        return True
    return False


def looks_like_task_list_page(texts=None):
    if texts is None:
        texts = get_page_texts()
    if looks_like_search_browse_page(texts):
        return False
    task_keys = [
        "今日速赚", "快速赚", "今日快速赚奖励已拿完", "记得明天再来",
        "完成下方任务", "更多金币等你赚", "展开", "任务到访得金币",
        "每日来任务面板", "逛清单", "淘金币趣味课堂", "领取奖励", "去完成", "去逛逛", "点击去逛",
    ]
    return has_any(texts, task_keys) or looks_like_daily_task_list_by_xml()


def classify_current_page():
    package_name, activity_name = get_current_app(d)
    texts = get_page_texts(120)
    if package_name != TB_APP:
        return "external_app", package_name, activity_name, texts
    if has_any(texts, ["任务已完成", "已得"]):
        return "task_done", package_name, activity_name, texts
    if has_any(texts, ["淘金币趣味答题", "我选好了"]):
        return "quiz", package_name, activity_name, texts
    if looks_like_task_list_page(texts):
        return "daily_task_list", package_name, activity_name, texts
    if looks_like_browse_task_page(texts, activity_name or ""):
        return "taobao_browse_task", package_name, activity_name, texts
    if looks_like_coin_home_page(texts):
        return "coin_home", package_name, activity_name, texts
    return "unknown_taobao_page", package_name, activity_name, texts


def open_coin_home_direct(stop=False):
    if stop:
        print("强制重启淘金币流程")
        d.app_stop(TB_APP)
        time.sleep(1)
    print("使用小插件跳转入口启动淘金币首页")
    d.shell(f"am start -a android.intent.action.VIEW -d '{COIN_HOME_URL}' {TB_APP}")
    time.sleep(4)


def click_daily_version_if_exists():
    daily_btn = d(classNameMatches=ACTION_CLASS, textContains="回日常版")
    if daily_btn.exists(timeout=0.8):
        print("点击回日常版", daily_btn.get_text(), daily_btn.bounds())
        daily_btn.click()
        time.sleep(1.5)
        return True
    return False


def click_earn_more_if_exists():
    if looks_like_task_list_page():
        earn_btn = d(classNameMatches=ACTION_CLASS, textContains="赚更多金币")
        if earn_btn.exists(timeout=0.2):
            print("当前已是日常版任务列表，不点击赚更多金币", earn_btn.bounds())
        return True
    earn_btn = d(classNameMatches=ACTION_CLASS, textContains="赚金币")
    if not earn_btn.exists(timeout=0.5):
        earn_btn = d(classNameMatches=ACTION_CLASS, textContains="赚更多金币")
    if earn_btn.exists(timeout=1):
        print("点击赚金币进入任务列表", earn_btn.get_text(), earn_btn.bounds())
        earn_btn.click()
        time.sleep(2)
        return True
    return False


def enter_task_list_from_coin_home():
    click_daily_version_if_exists()
    if looks_like_task_list_page():
        return True
    if click_earn_more_if_exists():
        return True
    print("未找到赚金币入口，尝试点击首页常见任务入口区域")
    d.click(int(screen_width * 0.12), int(screen_height * 0.39))
    time.sleep(2)
    return looks_like_task_list_page() or click_earn_more_if_exists()


def expand_more_coin_tasks():
    expand_btn = d(classNameMatches=ACTION_CLASS, text="展开")
    if expand_btn.exists(timeout=0.5):
        print("点击展开更多金币任务", expand_btn.bounds())
        expand_btn.click()
        time.sleep(1)
        return True
    return False


def scroll_task_list_once():
    print("开始查找按钮前下翻任务列表")
    d.swipe(screen_width // 2, int(screen_height * 0.82), screen_width // 2, int(screen_height * 0.42), 0.25)
    time.sleep(0.8)


def do_one_external_swipe():
    print("外部/未知任务页只滚动一次")
    d.swipe(screen_width // 2, int(screen_height * 0.78), screen_width // 2, int(screen_height * 0.38), 0.35)
    time.sleep(2)


def click_search_discovery_if_exists():
    texts = get_page_texts(120)
    if not has_any(texts, ["搜索后浏览立得奖励"]):
        return False
    history_item = d.xpath('(//android.widget.TextView[@text="历史搜索"]/following-sibling::android.widget.ListView)/android.view.View[1]')
    if history_item.exists:
        print("点击历史搜索第一个内容块")
        history_item.click()
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
        d.click(*center(target))
        time.sleep(2)
        return True
    return False


def browse_task_loop(duration=BROWSE_TASK_DURATION):
    click_search_discovery_if_exists()
    start_time = time.time()
    print("开始做任务。。。")
    while time.time() - start_time < duration:
        texts = get_page_texts(80)
        if has_any(texts, ["任务已完成", "已得"]):
            print("检测到任务完成提示，结束浏览并返回", [text for text in texts if "已得" in text or "任务已完成" in text][:3])
            break
        start_x = random.randint(screen_width // 5, screen_width // 2)
        start_y = random.randint(int(screen_height * 0.62), int(screen_height * 0.86))
        end_x = random.randint(max(1, start_x - 100), min(screen_width - 1, start_x + 20))
        end_y = random.randint(int(screen_height * 0.16), int(screen_height * 0.52))
        swipe_time = random.uniform(0.25, 0.5)
        print("模拟滑动", start_x, start_y, end_x, end_y, swipe_time)
        d.swipe(start_x, start_y, end_x, end_y, swipe_time)
        time.sleep(random.uniform(1.0, 1.8))
    back_to_task()


def handle_quiz_answer():
    texts = get_page_texts(80)
    if not has_any(texts, ["淘金币趣味答题", "我选好了"]):
        return False
    option_a = d(className="android.widget.TextView", text="A")
    if option_a.exists(timeout=1):
        bounds = option_a.bounds()
        print("趣味课堂选择A", bounds)
        d.click(screen_width // 2, (bounds[1] + bounds[3]) // 2)
        time.sleep(1)
    submit_btn = d(classNameMatches=ACTION_CLASS, text="我选好了")
    if submit_btn.exists(timeout=2):
        print("趣味课堂点击我选好了", submit_btn.bounds())
        submit_btn.click()
        time.sleep(2)
    return True


def find_task_action_button():
    buttons = d(classNameMatches=ACTION_CLASS, textMatches=ACTION_TEXT_PATTERN)
    if not buttons.exists:
        return None, None
    print(f"任务动作按钮匹配到{len(buttons)}个")
    for index, view in enumerate(buttons):
        try:
            button_text = view.get_text() or ""
            bounds = view.bounds()
        except Exception as exc:
            print("读取任务按钮失败，跳过", exc)
            continue
        task_name = button_text
        try:
            sibling = view.sibling(className="android.view.View", instance=0).child(className="android.widget.TextView", instance=0)
            if sibling.exists:
                sibling_text = sibling.get_text()
                if sibling_text:
                    task_name = f"{button_text} {sibling_text}".strip()
        except Exception:
            pass
        print("任务动作按钮候选", index, task_name, bounds)
        if skip_task_name(task_name):
            print("跳过任务，不点击动作按钮", task_name)
            continue
        if task_is_done_text(task_name):
            print("跳过已完成动作按钮", task_name)
            continue
        click_key = f"action:{bounds}"
        if click_key in invalid_click_keys:
            print("跳过刚才点击无效的动作按钮", task_name, bounds)
            continue
        if have_clicked.get(task_name, 0) >= 2:
            print("跳过已点击多次任务", task_name, have_clicked[task_name])
            continue
        return view, task_name
    return None, None


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
        has_action = False
        for child in node.iter("node"):
            text = child.attrib.get("text") or ""
            child_bounds = parse_bounds(child.attrib.get("bounds"))
            if not text or not child_bounds:
                continue
            child_texts.append((child_bounds[1], child_bounds[0], text))
            if re.match(r"^\+\d+$", text):
                has_reward = True
            if re.search(ACTION_TEXT_PATTERN, text):
                has_action = True
        if not child_texts or not (has_reward or has_action):
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
        rows.append((row_bounds, task_name))
    rows.sort(key=lambda item: (item[0][1], item[0][0]))
    print("XML金币任务行识别", [(bounds, name) for bounds, name in rows[:8]])
    return rows[:8]


def handle_after_task_click(task_name, click_key=None):
    time.sleep(2)
    page_type, package_name, activity_name, texts = classify_current_page()
    print("任务点击后页面判定", {"task": task_name, "page": page_type, "package": package_name, "activity": activity_name, "texts": texts[:12]})
    if page_type == "daily_task_list":
        print("点击后仍在日常任务列表，记录无效点击并继续")
        if click_key:
            invalid_click_keys.add(click_key)
        return
    if page_type == "quiz":
        handle_quiz_answer()
        back_to_task()
        return
    if page_type == "task_done":
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
        enter_task_list_from_coin_home()
        return
    do_one_external_swipe()
    back_to_task()


def back_to_task():
    print("开始返回任务页面")
    back_count = 0
    cross_app_count = 0
    loop_count = 0
    while True:
        loop_count += 1
        if loop_count > 30:
            print("返回任务页循环过多，使用小插件入口恢复")
            open_coin_home_direct(stop=False)
            return
        page_type, package_name, activity_name, texts = classify_current_page()
        print("返回中页面判定", {"page": page_type, "package": package_name, "activity": activity_name, "texts": texts[:8]})
        if page_type == "daily_task_list":
            print("当前是任务列表画面，停止返回")
            return
        if page_type == "task_done":
            d.press("back")
            time.sleep(1.5)
            continue
        if page_type == "taobao_browse_task":
            print("当前仍是浏览任务页，补足浏览而不是后退")
            browse_task_loop()
            return
        if page_type == "coin_home":
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
                d.press("back")
                time.sleep(2)
                continue
            print("外部App返回多次仍未回淘宝，切回淘宝而不关闭淘宝")
            d.app_start(TB_APP, stop=False, use_monkey=False)
            time.sleep(3)
            continue
        if click_daily_version_if_exists():
            continue
        back_count += 1
        if back_count > BACK_RESTART_LIMIT:
            print("多次无法回到任务页，使用小插件入口恢复")
            open_coin_home_direct(stop=False)
            return
        print("点击后退", back_count)
        d.press("back")
        time.sleep(2)


def ensure_task_list_at_start():
    page_type, package_name, activity_name, texts = classify_current_page()
    print("启动前页面判定", {"page": page_type, "package": package_name, "activity": activity_name, "texts": texts[:10]})
    if page_type == "daily_task_list":
        return True
    if page_type == "quiz":
        handle_quiz_answer()
        back_to_task()
        return looks_like_task_list_page()
    if page_type == "coin_home":
        return enter_task_list_from_coin_home()
    print("当前不是日常任务列表，使用小插件跳转入口")
    open_coin_home_direct(stop=False)
    click_daily_version_if_exists()
    page_type, _, _, texts = classify_current_page()
    if page_type == "daily_task_list":
        return True
    if page_type == "coin_home":
        return enter_task_list_from_coin_home()
    return click_earn_more_if_exists() or looks_like_task_list_page()


def main_loop():
    global finish_count
    no_task_scroll_count = 0
    if not ensure_task_list_at_start():
        raise Exception("没有找到赚金币任务按钮或任务列表")
    print("点击开始做任务")
    while True:
        try:
            time.sleep(1)
            page_type, package_name, activity_name, texts = classify_current_page()
            print("操作前页面判定", {"page": page_type, "package": package_name, "activity": activity_name, "texts": texts[:8]})
            if page_type == "quiz":
                handle_quiz_answer()
                back_to_task()
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
                enter_task_list_from_coin_home()
                continue
            if page_type != "daily_task_list":
                do_one_external_swipe()
                back_to_task()
                continue

            check_verify(d)
            if has_any(texts, ["记得明天再来", "今日快速赚奖励已拿完"]):
                if expand_more_coin_tasks():
                    no_task_scroll_count = 0
                    continue
            click_daily_version_if_exists()
            if expand_more_coin_tasks():
                no_task_scroll_count = 0
                continue

            reward_btn = d(classNameMatches=ACTION_CLASS, textMatches=r"领取奖励|立即领取|点击得")
            if reward_btn.exists(timeout=0.5):
                print("点击奖励按钮", reward_btn.get_text(), reward_btn.bounds())
                reward_btn.click()
                finish_count += 1
                no_task_scroll_count = 0
                time.sleep(2)
                continue

            action_view, task_name = find_task_action_button()
            if action_view:
                print("点击按钮", task_name)
                have_clicked[task_name] = have_clicked.get(task_name, 0) + 1
                bounds = action_view.bounds()
                action_view.click()
                handle_after_task_click(task_name, f"action:{bounds}")
                no_task_scroll_count = 0
                continue

            print("原文字按钮未找到，开始查找金币任务行")
            coin_rows = find_coin_row_buttons()
            clicked_row = False
            for row_bounds, row_task_name in coin_rows:
                click_key = f"row:{row_bounds}"
                if click_key in invalid_click_keys:
                    print("跳过刚才点击无效的金币任务行", row_task_name, row_bounds)
                    continue
                if have_clicked.get(row_task_name, 0) >= 2:
                    print("跳过已点击多次金币任务行", row_task_name, have_clicked[row_task_name])
                    continue
                print("点击金币任务行", row_task_name, row_bounds)
                have_clicked[row_task_name] = have_clicked.get(row_task_name, 0) + 1
                d.click(*center(row_bounds))
                handle_after_task_click(row_task_name, click_key)
                clicked_row = True
                no_task_scroll_count = 0
                break
            if clicked_row:
                continue

            debug_texts = get_page_texts(80)
            print("当前页面前20个文本", debug_texts[:20])
            if looks_like_browse_task_page(debug_texts, activity_name or ""):
                browse_task_loop()
                continue
            no_task_scroll_count += 1
            print("未找到可点击按钮，继续下翻", no_task_scroll_count)
            if no_task_scroll_count <= 8:
                scroll_task_list_once()
                continue
            print("连续下翻后仍未找到可点击按钮，结束")
            break
        except Exception as exc:
            print("主循环异常", exc)
            back_to_task()


try:
    main_loop()
finally:
    ctx.close()
    print(f"共自动化完成{finish_count}个任务")
    d.shell("settings put system accelerometer_rotation 0")
    print("关闭手机自动旋转")
    minutes, seconds = divmod(int(time.time() - start_time_all), 60)
    print(f"共耗时: {minutes} 分钟 {seconds} 秒")
