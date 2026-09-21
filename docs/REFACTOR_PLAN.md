# 淘金币任务引擎重构计划

## 背景

当前项目已经能在 Windows 和 Mac 上跑通淘金币主线，但核心逻辑仍集中在 `淘金币任务.py` 里。页面识别、动作执行、任务分支、OCR、恢复逻辑、日志和规则读取混在一起，后续继续增加规则、平台适配或迁移到 AutoJS 会越来越难维护。

本分支目标是做架构重构，不改变当前已经验证可用的业务行为。

## 当前行为基线

- 两个主任务类型：
  - 金币任务：`TJB_TASK_MODE=taojinbi`
  - 体力任务：`TJB_TASK_MODE=energy`
- GUI 通过 `gui_server.py` 启动任务子进程。
- 页面控制通过 `uiautomator2`。
- Mac OCR 优先走 Apple Vision CLI，EasyOCR 仅作为可选 fallback。
- “回日常版”只作为可配置兜底，不是默认入口。
- Mac GUI 服务必须通过可见窗口启动，不能无感后台启动。

## 重构原则

1. 保持单仓库。
2. Windows 和 Mac 共用核心任务逻辑与规则。
3. 平台差异限制在设备、OCR、启动脚本和依赖层。
4. 不把 `runtime/`、`logs/`、调试截图、XML dump 当作规则真源。
5. 每一步重构后都要保持可运行，可回退，可验证。
6. 优先抽接口和边界，不急于重写全部业务分支。

## 目标结构

```text
coin11-tb/
  taojinbi/
    actions.py           # 页面动作统一入口
    device.py            # 设备抽象，先封装 uiautomator2
    pages.py             # PageType / PageSnapshot / PageSignals
    page_classifier.py   # 页面识别逻辑
    ocr_backend.py       # OCRBackend 接口
    runners/
      coin.py            # 金币任务主循环
      energy.py          # 体力任务主循环
      recovery.py        # 返回、恢复、重开入口
    rules.py             # 规则读取与默认规则合并

  config/
    rules.default.json   # 共同维护的默认规则
    rules.mac.json       # 可选，Mac 覆盖
    rules.windows.json   # 可选，Windows 覆盖

  runtime/
    rules.json           # 本地运行规则，不作为唯一真源
```

## 核心抽象

### PageSnapshot

统一承载一次页面观察结果：

```python
PageSnapshot(
    package="com.taobao.taobao",
    activity="...",
    texts=[...],
    xml="...",
    ocr_items=[...],
    captured_at=...
)
```

### PageClassification

页面识别不只返回字符串，还要返回依据：

```python
PageClassification(
    page_type="taobao_browse_task",
    confidence=0.85,
    signals=["任务浮标源码正式页", "浏览5秒", "返回图标"]
)
```

### PageAction

所有可能改变页面的动作都走统一入口：

```python
actions.click(bounds, reason="点击任务按钮", task=task_name)
actions.back(reason="返回任务列表")
actions.swipe(start, end, reason="任务列表下翻")
actions.long_press(bounds, reason="跳一跳拿钱")
```

统一动作入口负责：

- 真实点击/返回/滑动。
- 蓝色动作日志。
- 可选的动作前后页面快照。
- 后续失败恢复和诊断。

### OCRBackend

```python
class OCRBackend:
    def read(self, image) -> tuple[list[OCRItem], OCRTiming]:
        ...
```

实现：

- `AppleVisionOCR`
- `EasyOCRBackend`
- `NoopOCR`

未来 AutoJS 可以提供自己的 OCR backend。

## 分阶段计划

### Phase 0: 冻结当前可运行基线

- 保留当前 `淘金币任务.py` 行为。
- 记录 Mac 当前依赖、启动方式、OCR backend。
- 明确运行产物不提交。

### Phase 1: 抽动作层

优先收益最大，风险相对可控。

- 新增 `taojinbi/actions.py`。
- 封装：
  - click
  - long_press
  - swipe
  - back
  - open_coin_home
  - stop_app
- 所有页面动作统一产生 `[页面操作]` 日志。
- 前端只需要识别 `[页面操作]`，减少文本猜测。

### Phase 2: 抽 OCR backend

- 将 `screen_ocr.py` 改成后端选择器。
- Apple Vision 和 EasyOCR 都实现统一接口。
- 任务代码只依赖 `read_ocr_results()` 或新接口，不关心具体 OCR 实现。

### Phase 3: 抽页面识别层

- 新增 `PageSnapshot`。
- 新增 `PageClassification`。
- 从 `classify_current_page()` 拆出独立分类器。
- 每个页面类型记录判断依据，方便 debug。

### Phase 4: 抽规则层

- 新增 `config/rules.default.json`。
- `runtime/rules.json` 只作为本地覆盖。
- GUI 继续编辑 runtime 规则。
- 后续可加“导出当前规则为默认规则”的脚本或按钮。

### Phase 5: 拆 Runner

- 金币任务和体力任务拆成独立 runner。
- 共用动作层、页面识别层、OCR 层、恢复层。
- 小任务类型逐步注册成 handler。

### Phase 6: 准备 AutoJS 迁移

- 把 `uiautomator2` 依赖限制到 device adapter。
- 定义 AutoJS 需要实现的最小能力：
  - snapshot
  - click
  - swipe
  - back
  - screenshot
  - notify/log

## 非目标

- 不在本分支全量整理旧活动脚本。
- 不重写所有业务规则。
- 不把 GUI 规则编辑直接写入 Git 默认规则。
- 不把 Windows/Mac 拆成两个仓库。

## 验证清单

每个阶段至少验证：

- GUI 能打开。
- ADB 能连接设备。
- uiautomator2 能 dump UI。
- Apple Vision OCR 能返回 items。
- `TJB_TASK_MODE=energy` 能进入体力主线。
- `TJB_TASK_MODE=taojinbi` 能进入金币主线。
- 日志中页面动作有清晰边界。
