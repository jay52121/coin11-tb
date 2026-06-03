# Mac 迁移说明

本项目建议用 GitHub 迁移代码，不迁移 Windows 的 `.venv`、`runtime`、`logs` 和调试截图。

## 1. 拉取代码

```bash
git clone <你的 GitHub 仓库地址>
cd coin11-tb
```

如果已经 clone：

```bash
git pull
```

## 2. 安装系统依赖

建议先装 Homebrew，然后安装 Android 平台工具：

```bash
brew install android-platform-tools
adb version
adb devices
```

手机需要打开 USB 调试，并在手机上确认授权。

## 3. 创建 Python 环境

建议 Python 3.10 或 3.11。

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip setuptools wheel
python -m pip install -r requirements-mac.txt
```

如果 `opencv-python` 或 `easyocr` 安装失败，先升级 pip/wheel 后重试。

## 4. 初始化 uiautomator2

```bash
python -m uiautomator2 init
adb devices
```

然后跑环境检查：

```bash
python check_env.py --skip-ocr
```

首次完整检查 OCR：

```bash
python check_env.py
```

## 5. 启动 GUI

Mac 上不使用 Windows 的 `.bat` / `.vbs`。

```bash
source .venv/bin/activate
TJB_DISABLE_AUTO_START=1 python -m uvicorn gui_server:app --host 127.0.0.1 --port 8765
```

浏览器访问：

```text
http://127.0.0.1:8765/
```

## 6. 直接运行脚本

淘金币任务：

```bash
source .venv/bin/activate
TJB_TASK_MODE=taojinbi python 淘金币任务.py
```

做体力任务：

```bash
source .venv/bin/activate
TJB_TASK_MODE=energy python 淘金币任务.py
```

指定 Android 用户：

```bash
TJB_ANDROID_USER_ID=0 TJB_TASK_MODE=energy python 淘金币任务.py
TJB_ANDROID_USER_ID=999 TJB_TASK_MODE=energy python 淘金币任务.py
```

## 7. OCR / GPU 说明

当前 OCR 路线是：

- `uiautomator2` 截图
- `opencv-python` 按屏幕宽高缩小到 0.5 倍
- `easyocr` 识别中文

Windows 当前主要靠 CUDA。Mac 上第一版建议先跑 CPU，稳定后再评估 MPS。

`check_env.py` 会输出：

- `torch MPS available`
- `torch CUDA available`
- EasyOCR 初始化耗时

即使 MPS 可用，EasyOCR 实际是否稳定吃 MPS 仍要实测，不要直接假设。

## 8. 不应该提交的文件

不要提交：

- `.venv/`
- `runtime/`
- `logs/`
- `*.xml`
- `debug_*.png`
- `ocr_*.png`
- `widget_*.txt`

这些都是本机运行产物或调试文件。

## 9. 最小验收顺序

1. `python check_env.py --skip-ocr` 通过。
2. `python check_env.py` 能初始化 OCR。
3. GUI 能打开 `http://127.0.0.1:8765/`。
4. GUI 能显示版本号、Android 用户、当前任务类型。
5. 手机当前页面能被 `uiautomator2 dump_hierarchy` 读取。
6. 先手动点 GUI 的“做体力”，确认不会误判淘金币首页。
7. 再测试“启动”淘金币任务。

## 10. 常见问题

### adb 找不到设备

```bash
adb kill-server
adb start-server
adb devices
```

手机上重新确认 USB 调试授权。

### uiautomator2 dump 失败

```bash
python -m uiautomator2 init
adb shell pm list packages | grep uiautomator
```

必要时重启手机上的 atx/uiautomator 服务。

### OCR 很慢

先确认是否真的需要 OCR：

```bash
python check_env.py --skip-ocr
```

再完整测试：

```bash
python check_env.py
```

当前项目 OCR 只应放在关键兜底路径，不能把每一步都改成 OCR。
