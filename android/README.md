# Android native engine

This directory contains the native Android migration of the Taobao coin task engine.

## v0.1 scope

v0.1 is intentionally observer-only.

It does:

- start an Android AccessibilityService;
- capture the active accessibility node tree;
- immediately convert live AccessibilityNodeInfo objects into immutable snapshots;
- keep the last snapshot from an external app when the Observer UI is opened;
- show package name, window id, node count, text, content description, view id, bounds and basic flags;
- declare screenshot capability for the OCR milestone that follows.

It does not:

- click;
- swipe;
- press Back;
- run tasks;
- use OCR;
- use Shizuku;
- use Android user 999;
- force-stop third-party apps;
- implement jump-energy.

## Build requirements

- Android Studio with JDK 17.
- Android SDK 36.
- Android Gradle Plugin 9.4.0.
- Gradle 9.6.0 if building from command line.

AGP 9.x has built-in Kotlin support, so this project deliberately does not apply the legacy Kotlin Android plugin.

If the checkout does not yet contain a Gradle wrapper, either open android/ in Android Studio or generate one locally:

    cd android
    gradle wrapper --gradle-version 9.6.0

Then:

    ./gradlew :app:assembleDebug

## Manual v0.1 acceptance test

1. Install the debug APK.
2. Open 淘金币 Android Observer.
3. Tap 打开无障碍设置.
4. Enable 淘金币页面观察器.
5. Switch to Taobao and visit several different pages.
6. Return to the Observer app.
7. Confirm that:
   - Accessibility shows 已连接;
   - package is normally com.taobao.taobao;
   - node count is non-zero;
   - visible Taobao text appears in the node list;
   - bounds look plausible;
   - some nodes expose viewId;
   - opening the Observer app does not overwrite the last Taobao snapshot.

v0.1 passes when node capture is stable enough to serve as the input for the v0.2 page recognizer.
