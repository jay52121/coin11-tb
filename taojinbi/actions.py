import random
import time


ACTION_LOG_PREFIX = "[页面操作]"


def clamp(value, low, high):
    return max(low, min(high, value))


def center(bounds):
    return ((bounds[0] + bounds[2]) // 2, (bounds[1] + bounds[3]) // 2)


class ActionExecutor:
    def __init__(self, device, screen_width, screen_height, logger=print):
        self.device = device
        self.screen_width = screen_width
        self.screen_height = screen_height
        self.logger = logger

    def log(self, message):
        self.logger(f"{ACTION_LOG_PREFIX} {message}")

    def jitter_point(self, x, y, radius=8):
        return (
            clamp(x + random.randint(-radius, radius), 1, self.screen_width - 1),
            clamp(y + random.randint(-radius, radius), 1, self.screen_height - 1),
        )

    def click(self, x, y, radius=8, hold=None, reason="模拟点击"):
        x, y = self.jitter_point(int(x), int(y), radius)
        hold = hold if hold is not None else random.uniform(0.08, 0.18)
        self.log(f"{reason} {x} {y} {hold:.2f}S")
        self.device.long_click(x, y, hold)
        time.sleep(random.uniform(0.12, 0.28))

    def click_bounds(self, bounds, radius=8, reason="模拟点击"):
        x, y = center(bounds)
        self.click(x, y, radius=radius, reason=reason)

    def long_press_bounds(self, bounds, hold=3.0, radius=8, reason="模拟长按"):
        x, y = center(bounds)
        x, y = self.jitter_point(x, y, radius)
        self.log(f"{reason} {x} {y} {hold:.2f}S")
        self.device.long_click(x, y, hold)
        time.sleep(random.uniform(0.18, 0.35))

    def swipe(self, x1, y1, x2, y2, duration=0.45, wiggle=24, reason="模拟滑动轨迹"):
        x1, y1 = self.jitter_point(x1, y1, wiggle)
        x2, y2 = self.jitter_point(x2, y2, wiggle)
        mid_x = clamp((x1 + x2) // 2 + random.randint(-wiggle, wiggle), 1, self.screen_width - 1)
        mid_y = clamp((y1 + y2) // 2 + random.randint(-wiggle, wiggle), 1, self.screen_height - 1)
        duration = max(0.18, duration * random.uniform(0.85, 1.35))
        self.log(f"{reason} ({x1}, {y1}) ({mid_x}, {mid_y}) ({x2}, {y2}) {duration:.2f}S")
        self.device.swipe_points([(x1, y1), (mid_x, mid_y), (x2, y2)], duration)
        time.sleep(random.uniform(0.18, 0.42))

    def back(self, reason="模拟返回"):
        time.sleep(random.uniform(0.08, 0.22))
        self.log(reason)
        self.device.press("back")
        time.sleep(random.uniform(0.25, 0.55))
