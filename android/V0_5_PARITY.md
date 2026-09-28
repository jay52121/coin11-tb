# Android v0.5 / Mac parity audit

Mac oracle: `refactor/task-engine` + `runtime/rules.json` + `runtime/control.json`.

Scope rule: v0.5 is the Mac **ordinary 淘金币 mainline control path**. Special task workflows and abnormal/global recovery stay in v0.6. A v0.5 item is not deferred merely because real-device testing has not exposed it.

## v0.5 control-path parity

| Area | Mac behavior | Android v0.5.14 |
|---|---|---|
| Coin entry | sign -> 8s retry -> earn-more/earn -> optional daily fallback -> OCR task-list confirm | Implemented. Separate entry states, XML first, OCR fallback; daily-version fallback remains disabled by default like Mac. |
| Task exclusion | `coin_exclude_tags` + `skip_task_extra_words`; browse-step exception for 下单 | Implemented and rule-driven. Android adds observed unsafe family `头条` to coin exclusions. |
| Done detection | done words + exclusions + progress x/y where x>=y | Implemented for XML/OCR candidates. |
| Task key | progress label preferred, otherwise normalized row text | Implemented. Progress label remains the stable key across button-text changes. |
| Click retry | per task key count; limit = total+1 clamped 2..12, otherwise 2 | Implemented; no one-and-done blacklist. |
| Invalid click | click returns/stays on task list -> invalidate that click target and continue | Implemented with source/key/bounds click keys. |
| Reward | rule pattern; reject top-area/stale candidates; max 2 click budget | Implemented. Immutable source Observation must still be latest+valid immediately before tap. |
| XML candidate | action-pattern candidates; row context; exclusions/done/click limits; clickable ancestor | Implemented. Android resolves the smallest clickable bounds containing the action node. |
| Clickable row fallback | clickable task row with action/reward marker when action-node path is insufficient | Implemented after normal XML action candidates, matching Mac ordering. |
| OCR candidate | right-side OCR action fallback with the same task policy | Implemented and guarded by OCR source Observation validity. |
| Task-list confirmation | before blind scrolling, re-confirm page is actually a task list | Implemented via OCR composite task-list recognition; non-task-list goes to current-user coin-entry recovery instead of scrolling. |
| Task-list bottom | XML bottom words + OCR bottom words | Implemented, including Mac OCR footer markers. |
| Expand more | expand once at bottom; XML/clickable-container first, OCR fallback | Implemented. |
| Scroll exhaustion | <=8 scrolls; if still not bottom, clear click state and `open_coin_home_direct(stop=True)` | Implemented: clear click state, reset expand/scroll state, Shizuku force-stop target-user Taobao, then reopen coin entry behind a fresh-Observation gate. |
| Browse done | first OCR after 8s, then every 2s; rule done targets; ignore generic 已得 on 淘宝购物清单 | Implemented. |
| Browse swipe | human-like varying coordinates/duration/interval | Implemented with randomized Android gestures matching Mac ranges. |
| Search browse | click first history/search-discovery item before browse timing | Implemented node-first; uses clickable containing target when available. |
| Next-task hop | XML/OCR 下个任务/下一任务; max 8; reset timer/search discovery | Implemented. XML tap follows Mac left-edge tab position. |
| External app | scoped session; external swipe; force-stop only launched package/current user; recover task list/coin home | Implemented with Shizuku user context. Unrelated packages are never force-stopped by the session path. |
| Return | normal return must converge; no dependence on new Accessibility events | Implemented with watchdog; returns to list or explicit failure. |
| Task transition | after click, actively classify rather than depend on a new event | Implemented with task-transition watchdog and minimum settle window. |
| Fresh snapshot | every page-changing action invalidates Observation; next decision/tap must use a newer/current source | Implemented. Candidate source Observation must still be latest+valid at tap time; stale XML/OCR candidates are rejected. |

## Intentional implementation differences

- Android has no production `uiautomator2`; node snapshots + Accessibility gestures replace it.
- Observation does not expose parent pointers. Clickable-ancestor behavior is reproduced by selecting the smallest clickable snapshot bounds that contain the action node.
- Android's watchdogs replace Mac's blocking `sleep -> classify` loops so the service remains event-driven while preserving wait/retry semantics.
- Android debug v0.5 carries target user 999 explicitly through the run context. Production multi-user abstraction remains v0.8.

## v0.6 intentionally deferred

- quiz / 趣味课堂
- good-shop workflow
- shop-subscribe workflow
- game-coin workflow
- generic permission/dialog handling beyond the existing safe path
- main-loop stall 6/9 recovery
- force-restart Taobao / full `back_to_task(force_recovery=True)` recovery tree
- energy mainline

## Tracked but not a v0.5 control-path gate

These exist in the mature Mac runtime and must not be forgotten, but they do not decide ordinary coin-task control flow:

- coin min/max/final balance telemetry
- pause/stop/status control surface
- phone notifications
- final run cleanup/reporting beyond external-session cleanup

Real-device acceptance is still required before v0.5 is closed.
