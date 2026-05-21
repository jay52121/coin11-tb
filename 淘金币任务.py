import time
import re
import xml.etree.ElementTree as ET

import uiautomator2 as u2

from utils import check_chars_exist, other_app, get_current_app, select_device, task_loop, check_verify, start_app, TB_APP

COIN_HOME_URL = "https://pages-fast.m.taobao.com/wow/z/tmtjb/town/home?utparam=%7B%22ranger_buckets_native%22%3A%22tsp6443_32421_standardVersion%22%7D&spm=a2141.1.iconsv5.5&miniappSourceChannel=homepage&scm=1007.home_icon.lingjb.d&x-ssr=true&disableNav=YES&x-sec=wua&pha_h5=true&pha_nav=true&uniapp_id=1011525&uniapp_page=home&hd_from=tbHome"

unclick_btn = []
have_clicked = dict()
is_end = False
error_count = 0
no_task_scroll_count = 0
in_other_app = False
time1 = time.time()
print("淘金币任务脚本版本: coin-row-xml-log-20260518-0609")
selected_device = select_device()
d = u2.connect(selected_device)
print(f"已成功连接设备：{selected_device}")
print("使用direct URL启动淘金币首页")
d.shell(f"am start -a android.intent.action.VIEW -d '{COIN_HOME_URL}' {TB_APP}")
time.sleep(6)
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
# ctx.when(xpath="//android.widget.TextView[@package='com.eg.android.AlipayGphone']").click()
ctx.start()
time.sleep(3)


def recover_and_continue(reason):
    global error_count
    error_count += 1
    print(reason, error_count)
    debug_texts = get_page_texts(20)
    if looks_like_browse_task_page(debug_texts):
        print("当前像浏览任务页，不后退，继续浏览", debug_texts)
        task_loop(d, back_to_task)
        return True
    if error_count <= 5:
        print("尝试返回任务页后继续")
        back_to_task()
        return True
    print("连续恢复失败次数过多，结束任务")
    return False


def get_coin_row_task_name(row):
    reward = row.child(className="android.widget.TextView", textMatches=r"^\+\d+$")
    if not reward.exists:
        return None
    reward_left = reward.bounds()[0]
    for text_view in row.child(className="android.widget.TextView"):
        text = text_view.get_text()
        if text and not re.match(r"^\+\d+$", text) and text_view.bounds()[0] < reward_left:
            return text
    return None


def parse_bounds(bounds_text):
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds_text or "")
    if not match:
        return None
    return tuple(map(int, match.groups()))


def find_coin_row_buttons():
    rows = []
    xml = d.dump_hierarchy(compressed=False)
    root = ET.fromstring(xml)
    for node in root.iter("node"):
        if node.attrib.get("class") != "android.view.View" or node.attrib.get("clickable") != "true":
            continue
        row_bounds = parse_bounds(node.attrib.get("bounds"))
        if not row_bounds:
            continue
        left, top, right, bottom = row_bounds
        if top < 650 or bottom - top > 520 or right < screen_width - 80:
            continue
        texts = []
        has_reward = False
        for child in node.iter("node"):
            text = child.attrib.get("text") or ""
            child_bounds = parse_bounds(child.attrib.get("bounds"))
            if not text or not child_bounds:
                continue
            if re.match(r"^\+\d+$", text) and child_bounds[0] > screen_width * 0.7:
                has_reward = True
                continue
            if child_bounds[0] < screen_width * 0.7:
                texts.append((child_bounds[1], text))
        if has_reward and texts:
            task_name = sorted(texts)[0][1]
            rows.append(((left, top, right, bottom), task_name))
    return rows


def get_page_texts(limit=30):
    texts = []
    for text_view in d(className="android.widget.TextView"):
        try:
            text = text_view.get_text()
        except Exception as e:
            print("读取页面文本失败，跳过节点", e)
            continue
        if text:
            texts.append(text)
        if len(texts) >= limit:
            break
    return texts


def looks_like_browse_task_page(texts):
    if looks_like_task_list_page(texts):
        return False
    browse_keys = ["已得", "近七天卖出", "送礼指南", "文具精选", "精选大礼", "已售", "热销", "爆款", "抵扣"]
    return any(key in text for text in texts for key in browse_keys)


def looks_like_task_list_page(texts=None):
    if texts is None:
        texts = get_page_texts(40)
    task_keys = [
        "完成下方任务得额外金币",
        "更多金币等你赚",
        "赚更多金币",
        "赚金币抵钱",
        "今日累计奖励",
        "展开",
        "领取奖励",
        "去完成",
        "去逛逛",
        "去浏览",
    ]
    if any(key in text for text in texts for key in task_keys):
        return True
    return bool(find_coin_row_buttons())


def handle_quiz_answer():
    if not d(className="android.webkit.WebView", text="淘金币趣味答题").exists:
        return False
    option_a = d(className="android.widget.TextView", text="A")
    if option_a.exists:
        bounds = option_a.bounds()
        print("趣味课堂选择A", bounds)
        d.click(screen_width // 2, (bounds[1] + bounds[3]) // 2)
        time.sleep(1)
    submit_btn = d(className="android.widget.Button", text="我选好了")
    if submit_btn.exists(timeout=3):
        print("趣味课堂点击我选好了", submit_btn.bounds())
        submit_btn.click()
        time.sleep(3)
        return True
    print("趣味课堂未找到我选好了按钮")
    return True


def expand_more_coin_tasks():
    expand_btn = d(classNameMatches=r"android.widget.TextView|android.widget.Button|android.view.View", text="展开")
    if expand_btn.exists:
        print("点击展开更多金币任务", expand_btn.bounds())
        expand_btn.click()
        time.sleep(1)
        return True
    return False


def scroll_task_list_once():
    print("开始查找按钮前下翻任务列表")
    d.swipe(screen_width // 2, int(screen_height * 0.82), screen_width // 2, int(screen_height * 0.42), 0.25)
    time.sleep(0.8)


def skip_task_name(task_name):
    return check_chars_exist(task_name) or "下单" in task_name


def open_coin_home_direct():
    print("使用direct URL重新打开淘金币首页")
    d.shell(f"am start -a android.intent.action.VIEW -d '{COIN_HOME_URL}' {TB_APP}")
    time.sleep(6)


def click_daily_version_if_exists():
    daily_btn = d(classNameMatches=r"android.widget.TextView|android.widget.Button|android.view.View", textContains="回日常版")
    if daily_btn.exists(timeout=2):
        print("点击回日常版", daily_btn.get_text(), daily_btn.bounds())
        daily_btn.click()
        time.sleep(5)
        return True
    return False


def check_in_task():
    package_name, activity_name = get_current_app(d)
    if package_name == "com.taobao.taobao" and "com.taobao.themis.container.app.TMSActivity" in activity_name:
        coin_view = d(className="android.webkit.WebView", text="淘金币首页")
        if coin_view.exists:
            earn_btn1 = d(className="android.widget.TextView", text="赚金币抵钱")
            earn_btn2 = d(className="android.widget.TextView", text="今日累计奖励")
            if earn_btn1.exists or earn_btn2.exists:
                return True
            else:
                earn_btn3 = d(className="android.widget.TextView", textContains="赚更多金币")
                if earn_btn3.exists:
                    print("check_in_task 点击赚更多金币")
                    earn_btn3.click()
                    time.sleep(3)
                    return True
                eva_canvas = d(className="android.widget.Image", resourceId="eva-canvas")
                if eva_canvas.exists:
                    print("check_in_task 点击eva-canvas返回任务入口")
                    d.click(eva_canvas.bounds()[0] + 150, eva_canvas.bounds()[3] - 150)
                    time.sleep(3)
                    return True
    return False


def back_to_task():
    print("开始返回任务页面")
    back_count = 0
    while True:
        temp_package, temp_activity = get_current_app(d)
        if temp_package is None or temp_activity is None or "Ext2ContainerActivity" in temp_activity:
            continue
        print(f"{temp_package}--{temp_activity}")
        if TB_APP not in temp_package:
            print(f"回到原始APP,{TB_APP}")
            start_app(d, TB_APP)
            jump_btn = d(resourceId="com.taobao.taobao:id/tv_close", text="跳过")
            if jump_btn.exists:
                print("点击跳过")
                jump_btn.click()
                time.sleep(2)
        else:
            if check_in_task():
                print("当前是任务列表画面，不能继续返回")
                break
            else:
                if click_daily_version_if_exists():
                    continue
                debug_texts = get_page_texts(80)
                if looks_like_task_list_page(debug_texts):
                    print("当前是任务列表画面，停止后退", debug_texts)
                    break
                if looks_like_browse_task_page(debug_texts):
                    back_count += 1
                    print("当前仍像浏览任务页，点击后退回任务页", back_count, debug_texts)
                    if back_count > 3:
                        print("浏览页后退次数过多，使用direct URL重新进淘金币入口")
                        open_coin_home_direct()
                        find_coin_btn()
                        back_count = 0
                        continue
                    d.press("back")
                    time.sleep(2)
                    continue
                close_btn1 = d.xpath("//android.widget.FrameLayout[@resource-id='com.alipay.multiplatform.phone.xriver_integration:id/frameLayout_rightButton1']/android.widget.LinearLayout/android.widget.RelativeLayout/android.widget.RelativeLayout/android.widget.FrameLayout[2]")
                if close_btn1.exists:
                    print("点击关闭小程序按钮")
                    close_btn1.click()
                    time.sleep(1)
                    continue
                task_view = d.xpath('//android.widget.TextView[contains(@text, "限时下单任务")]')
                if task_view.exists:
                    close_btn2 = d.xpath('//android.widget.TextView[contains(@text, "限时下单任务")]/preceding-sibling::android.view.View[1]')
                    if close_btn2.exists:
                        print("点击关闭限时下单任务按钮")
                        close_btn2.click()
                        time.sleep(1)
                        continue
                earn_btn = d(classNameMatches=r"android.widget.TextView|android.widget.Button|android.view.View",
                             textMatches="赚金币|赚更多金币")
                if earn_btn.exists:
                    print("已在淘金币首页，点击赚金币返回任务页", earn_btn.get_text(), earn_btn.bounds())
                    earn_btn.click()
                    time.sleep(2)
                    continue
                back_count += 1
                if back_count > 3:
                    print("后退次数过多，使用direct URL重新进淘金币入口")
                    open_coin_home_direct()
                    find_coin_btn()
                    back_count = 0
                    continue
                print("点击后退", back_count)
                d.press("back")
                time.sleep(2)


def find_coin_btn():
    if d(className="android.webkit.WebView", text="淘金币首页").exists:
        print("已通过direct URL进入淘金币首页")
        return
    coin_btn = d(classNameMatches=r"android.widget.FrameLayout|android.view.View", description="领淘金币")
    if coin_btn.exists:
        print("点击首页领淘金币入口", coin_btn[0].center())
        d.double_click(coin_btn[0].center()[0], coin_btn[0].center()[1])
        time.sleep(5)
    else:
        print("未找到首页领淘金币入口，点击搜索栏")
        d(className="android.view.View", description="搜索栏").click()
        d(resourceId="com.taobao.taobao:id/searchEdit").send_keys("淘金币")
        time.sleep(3)
        print("点击搜索结果淘金币")
        d(className="android.view.View", descriptionContains="淘金币").click()
        time.sleep(5)


ctx.wait_stable()
close_btn = d(className="android.widget.ImageView", description="关闭按钮")
if close_btn and close_btn.exists:
    print("点击关闭按钮")
    close_btn.click()
    time.sleep(3)
find_coin_btn()

click_daily_version_if_exists()

earn_btn = d(className="android.widget.TextView", textMatches="签到领金币|点击签到")
if earn_btn.exists(timeout=4):
    print("点击签到领金币/点击签到")
    earn_btn.click()
    time.sleep(5)
earn_btn = d(classNameMatches=r"android.widget.TextView|android.widget.Button|android.view.View", textContains="赚金币")
if not earn_btn.exists(timeout=2):
    earn_btn = d(classNameMatches=r"android.widget.TextView|android.widget.Button|android.view.View", textContains="赚更多金币")
if earn_btn.exists(timeout=4):
    print("点击赚金币进入任务列表", earn_btn.get_text(), earn_btn.bounds())
    earn_btn.click()
    time.sleep(3)
elif looks_like_task_list_page():
    print("已在日常版任务列表，跳过赚金币入口")
else:
    raise Exception("没有找到赚金币任务按钮")
print("点击开始做任务")
finish_count = 0
while True:
    try:
        in_other_app = False
        time.sleep(1)
        if handle_quiz_answer():
            back_to_task()
            continue
        check_verify(d)
        earn_btn = d(className="android.widget.TextView", text="赚更多金币")
        if earn_btn.exists and not d(className="android.widget.TextView", text="赚金币抵钱").exists:
            if looks_like_task_list_page():
                print("当前已是日常版任务列表，不点击赚更多金币", earn_btn.bounds())
            else:
                print("循环中点击赚更多金币", earn_btn.bounds())
                earn_btn.click()
                time.sleep(2)
                continue
        draw_down_btn = d(className="android.widget.Button", text="立即领取")
        if draw_down_btn.exists:
            print("点击立即领取")
            draw_down_btn.click()
            time.sleep(2)
        scroll_task_list_once()
        expand_more_coin_tasks()
        print("开始查找按钮。。。")
        get_btn = d(className="android.widget.Button", text="领取奖励")
        if get_btn.exists:
            get_btn.click()
            print("点击领取奖励")
            error_count = 0
            no_task_scroll_count = 0
            time.sleep(2)
            finish_count = finish_count + 1
            # if finish_count % 20 == 0:
            #     d.swipe_ext("up", scale=0.2)
            #     time.sleep(4)
            continue
        de_btn = d(className="android.widget.Button", text="点击得")
        if de_btn.exists:
            de_btn.click()
            print("点击点击得")
            error_count = 0
            no_task_scroll_count = 0
            time.sleep(4)
            continue
        to_btn = d(className="android.widget.Button", textMatches="去完成|去逛逛|去浏览|逛一逛|立即领|去领取|去看看|搜一下|玩一把|捐一笔|逛一下")
        if to_btn.exists:
            print(f"原文字按钮匹配到{len(to_btn)}个")
            need_click_view = None
            need_click_index = 0
            task_name = None
            for index, view in enumerate(to_btn):
                text_div = view.sibling(className="android.view.View", instance=0).child(className="android.widget.TextView", instance=0)
                if text_div.exists:
                    task_name = text_div.get_text()
                    print("原文字按钮候选", index, task_name)
                    if skip_task_name(task_name):
                        print("跳过任务", task_name)
                        if view not in unclick_btn:
                            unclick_btn.append(view)
                        continue
                    if task_name in have_clicked:
                        if have_clicked[task_name] >= 2:
                            print("跳过已点击多次任务", task_name, have_clicked[task_name])
                            continue
                    need_click_index = index
                    need_click_view = view
                    break
            if need_click_view:
                print("点击按钮", task_name)
                error_count = 0
                no_task_scroll_count = 0
                if have_clicked.get(task_name) is None:
                    have_clicked[task_name] = 1
                else:
                    have_clicked[task_name] += 1
                if check_chars_exist(task_name, other_app):
                    in_other_app = True
                need_click_view.click()
                time.sleep(3.5)
                if handle_quiz_answer():
                    back_to_task()
                else:
                    task_loop(d, back_to_task)
            else:
                no_task_scroll_count += 1
                print("原文字按钮存在，但未找到可点击任务，继续下翻", no_task_scroll_count)
                if no_task_scroll_count <= 8:
                    scroll_task_list_once()
                    continue
                if not recover_and_continue("连续下翻后仍未找到可点击任务"):
                    break
        else:
            print("原文字按钮未找到，开始查找金币任务行")
            need_click_view = None
            task_name = None
            coin_rows = find_coin_row_buttons()
            print(f"金币任务行候选{len(coin_rows)}个", [name for _, name in coin_rows])
            if not coin_rows:
                debug_texts = get_page_texts(80)
                print("当前页面前20个文本", debug_texts)
                if looks_like_browse_task_page(debug_texts):
                    print("当前像浏览任务页，开始执行下拉浏览")
                    task_loop(d, back_to_task)
                    continue
            for row_bounds, row_task_name in coin_rows:
                if skip_task_name(row_task_name):
                    print("跳过金币任务行", row_task_name)
                    continue
                if row_task_name in have_clicked and have_clicked[row_task_name] >= 2:
                    print("跳过已点击多次金币任务行", row_task_name, have_clicked[row_task_name])
                    continue
                need_click_view = row_bounds
                task_name = row_task_name
                break
            if need_click_view:
                print("原版没找到文字按钮，新版找到金币任务行，点击", task_name)
                error_count = 0
                no_task_scroll_count = 0
                have_clicked[task_name] = have_clicked.get(task_name, 0) + 1
                if check_chars_exist(task_name, other_app):
                    in_other_app = True
                d.click((need_click_view[0] + need_click_view[2]) // 2, (need_click_view[1] + need_click_view[3]) // 2)
                time.sleep(3.5)
                if handle_quiz_answer():
                    back_to_task()
                else:
                    task_loop(d, back_to_task)
            else:
                no_task_scroll_count += 1
                print("未找到可点击按钮，继续下翻", no_task_scroll_count)
                if no_task_scroll_count <= 8:
                    scroll_task_list_once()
                    continue
                if not recover_and_continue("连续下翻后仍未找到可点击按钮"):
                    break
    except Exception as e:
        print(e)
        continue
ctx.close()
print(f"共自动化完成{finish_count}个任务")
d.shell("settings put system accelerometer_rotation 0")
print("关闭手机自动旋转")
time2 = time.time()
minutes, seconds = divmod(int(time2 - time1), 60)  # 同时计算分钟和秒
print(f"共耗时: {minutes} 分钟 {seconds} 秒")
