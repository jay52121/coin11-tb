# Android v0.5 / Mac parity audit

Mac oracle: `refactor/task-engine` + `runtime/rules.json` + `runtime/control.json`.

## v0.5 must match

| Area | Mac behavior | Android status at audit | v0.5 action |
|---|---|---|---|
| Coin entry | sign -> 8s retry -> earn-more/earn -> optional daily fallback -> OCR task-list confirm | Implemented | Keep |
| Task exclusion | `coin_exclude_tags` + `skip_task_extra_words`; browse-step exception for 下单 | Implemented; add observed 头条 family | Keep rule-driven |
| Done detection | done words + exclusions + progress x/y where x>=y | Missing progress completion | Implement |
| Task key | progress label preferred, otherwise normalized row text | Partial | Match |
| Click retry | per task key click count, limit = total+1 clamped 2..12 | Missing; one-and-done set used | Implement |
| Invalid click | click returns/stays on task list -> mark click target invalid, continue | Missing | Implement |
| Reward | rule pattern, stale/top guard, max 2 clicks | Partial | Implement equivalent safe checks |
| XML candidate | action-pattern rows, row context, exclusions/done/click limits | Partial hardcoded action list | Generalize from rules |
| OCR candidate | OCR right-side action fallback with same task policy | Missing | Implement |
| Task-list bottom | XML bottom words + OCR bottom words | Missing | Implement |
| Expand more | expand once at bottom, XML then OCR | Missing | Implement |
| Scroll exhaustion | <=8 scrolls; if still not bottom, restart current-user coin entry and clear click state | Android currently ends | Implement |
| Browse done | OCR after 8s every 2s; done targets; ignore generic 已得 on 淘宝购物清单 | Partial | Match |
| Search browse | click first history/search-discovery item before browsing | Missing | Implement |
| Next task hop | XML/OCR 下个任务/下一任务, max 8, reset browse timer | Missing | Implement |
| External app | scoped session, scroll, force-stop launched package, recover current user | Implemented | Keep/harden |
| Return | normal return must converge; watchdog is Android equivalent safety | Implemented | Keep |
| Fresh snapshot | after page-changing action, wait for new Observation before next task decision | Partial | Enforce |

## v0.6 intentionally deferred

- quiz / 趣味课堂
- good-shop workflow
- shop-subscribe workflow
- game-coin workflow
- generic permission/dialog handling beyond existing safe path
- main-loop stall 6/9 recovery
- force-restart Taobao / full `back_to_task(force_recovery=True)` recovery tree
- energy mainline
- production multi-user SystemBridge (replacing Shizuku feasibility reflection)

No v0.5 item should be deferred merely because real-device testing has not exposed it yet.
