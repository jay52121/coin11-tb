# Codex task: Android v0.1 validation + legacy golden snapshots

## Context

We are migrating the stable Mac/Python Taobao coin automation to a native Android engine.

Work from:

- branch: `android/native-engine`
- Android v0.1 implementation commit: `f96a0a1e9c9eddc3601841e6476cbb4c93fa486f`
- CI build/upload commit: `5dddb7bbee0607aea8f68f7ef24acb90a76677f2`

The Android v0.1 build already passes `:app:assembleDebug` in GitHub Actions and produces a debug APK.

The current Mac/Python implementation is the behavioral oracle. It has been running reliably for about two months. Do not refactor or change its production behavior during this task.

## Scope

This task has two parts:

1. validate Android v0.1;
2. inspect and prepare a safe legacy Golden Snapshot capture path.

Do not start Android v0.2 page recognition.
Do not add Android OCR yet.
Do not add automatic click/swipe/back/task execution.
Do not add Shizuku, force-stop, user 999, or jump-energy.

---

## A. Validate Android v0.1

### Goal

Confirm the Android Observer project is structurally sound and suitable as the input layer for the next milestone.

### Required validation

- checkout `android/native-engine`;
- verify JDK 17 / Gradle 9.6.0 / AGP 9.4.0;
- run `:app:assembleDebug`;
- run cheap static checks that do not require architectural changes;
- inspect for concrete lifecycle or Accessibility bugs around:
  - AccessibilityService connection;
  - `rootInActiveWindow`;
  - event throttling;
  - conversion from live AccessibilityNodeInfo to immutable snapshots;
  - node traversal;
  - package filtering so opening the Observer app does not overwrite the last external snapshot;
  - max-node protection;
  - UI rendering;
- if an Android emulator/device is available, install and launch the APK;
- if Accessibility cannot be enabled unattended, report that rather than trying to bypass it;
- only fix concrete defects found by validation.

### v0.1 acceptance conditions

The observer must be able to represent:

- package name;
- windowId;
- text;
- contentDescription;
- viewId;
- class;
- bounds;
- clickable;
- scrollable;
- enabled.

There must be no automatic UI actions in v0.1.

---

## B. Legacy Mac Golden Snapshot capture

### Goal

Create or precisely specify a non-invasive recorder path so the stable Mac implementation can provide behavioral ground-truth samples for Android v0.2 and later replay tests.

### Critical safety rule

This GitHub repository is public.

Raw UI data may contain private account information.

Never commit raw:

- screenshots;
- XML;
- OCR output;
- usernames;
- addresses;
- order information;
- other account-specific UI data.

Raw capture output must stay local under:

`runtime/golden-captures/`

That path is gitignored.

Any fixture later committed to GitHub must first be manually curated/redacted.

### First inspect the existing stable code

Find the safest existing decision boundaries around:

- `dump_root()`;
- `get_page_texts()`;
- `classify_current_page()`;
- `page_signature()`;
- OCR calls;
- before an action;
- after an action;
- recovery entry;
- unknown-page handling.

Do not move or redesign those functions in this task.

### Desired metadata per capture

A metadata JSON should be able to contain fields like:

```json
{
  "schemaVersion": 1,
  "capturedAt": "2026-09-22T00:00:00+08:00",
  "gitCommit": "...",
  "runtimeVersion": "...",
  "androidUser": 0,
  "package": "com.taobao.taobao",
  "activity": "...",
  "legacyPageType": "daily_task_list",
  "pageSignature": "...",
  "rulesVersion": "...",
  "rulesSha256": "...",
  "ocrRequested": false,
  "reason": "classification"
}
```

### Associated page data

When available, preserve:

- raw uiautomator2 XML;
- normalized node list containing:
  - text;
  - content-desc;
  - resource-id;
  - class;
  - bounds;
  - clickable;
  - scrollable;
  - enabled;
- current package/activity;
- legacy `classify_current_page` result;
- current `page_signature`;
- OCR result only when OCR was actually used;
- screenshot only when useful, especially:
  - OCR cases;
  - unknown pages;
  - suspected misclassification;
- optionally:
  - current task candidate;
  - selected task;
  - action about to execute;
  - resulting page after action.

Optional action fields are useful later for TaskEngine replay but must not alter execution.

### Recorder requirements

The recorder must be:

- opt-in and disabled by default;
- unable to fail the production task loop;
- free of added sleeps;
- free of changed click timing;
- atomic when writing files;
- deduplicated by normalized signature where practical;
- able to include rules version/hash;
- local-only by default.

Recorder errors should be caught and logged without changing existing control flow.

### Page types to collect naturally

Prioritize these labels when they occur:

- `taobao_home`
- `coin_home`
- `daily_task_list`
- `taobao_browse_task`
- `task_done`
- `good_shop_page`
- `shop_subscribe_task`
- `quiz`
- `external_app`
- `unknown_taobao_page`

Also keep `energy_task_list` supported in the schema for later migration.

Do not treat jump-energy as a migration target.

### Platform-neutral fixture objective

We eventually want this shape:

```
Mac uiautomator2 XML
        ↓
NodeSnapshot JSON
        ↓
recognizer replay tests

Android AccessibilityNodeInfo
        ↓
NodeSnapshot JSON
        ↓
the same recognizer replay tests
```

Therefore the normalized node format must not expose Python/uiautomator2-specific object structures.

### Implementation decision

After inspecting the legacy code:

- if a tiny recorder can be inserted without affecting timing/behavior, implement it behind an explicit flag;
- otherwise do not force an implementation. Report the exact insertion points and minimal patch plan.

Prefer safety over completeness.

---

## Deliverables

Report back with:

1. Android v0.1 build result.
2. Any concrete defects found.
3. Exact fixes, if any.
4. Whether emulator/device launch validation was possible.
5. Golden Snapshot recorder design.
6. Exact legacy insertion points used or proposed.
7. Example sanitized fixture schema.
8. What captured information must remain local/private.
9. Whether v0.1 is ready for human phone testing.

Do not start v0.2.
