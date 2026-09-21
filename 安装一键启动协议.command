#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

APP_DIR="$PWD/runtime/TaojinbiGuiLauncher.app"
CONTENTS_DIR="$APP_DIR/Contents"
MACOS_DIR="$CONTENTS_DIR/MacOS"
EXECUTABLE="$MACOS_DIR/TaojinbiGuiLauncher"
SWIFT_FILE="$PWD/runtime/TaojinbiGuiLauncher.swift"
REGISTER_SWIFT_FILE="$PWD/runtime/RegisterTaojinbiScheme.swift"
REGISTER_BIN="$PWD/runtime/RegisterTaojinbiScheme"

rm -rf "$APP_DIR"
mkdir -p "$MACOS_DIR" runtime
chmod +x start_tjb_gui_window.command start_tjb_gui_background.command

cat > "$SWIFT_FILE" <<SWIFT
import AppKit
import Foundation

final class AppDelegate: NSObject, NSApplicationDelegate {
    private let baseDir = "$PWD"
    private var didLaunch = false

    override init() {
        super.init()
        NSAppleEventManager.shared().setEventHandler(
            self,
            andSelector: #selector(handleGetURLEvent(_:withReplyEvent:)),
            forEventClass: AEEventClass(kInternetEventClass),
            andEventID: AEEventID(kAEGetURL)
        )
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
            if !self.didLaunch {
                self.launchService()
            }
        }
    }

    @objc func handleGetURLEvent(_ event: NSAppleEventDescriptor, withReplyEvent replyEvent: NSAppleEventDescriptor) {
        launchService()
    }

    func application(_ application: NSApplication, open urls: [URL]) {
        launchService()
    }

    private func launchService() {
        if didLaunch {
            return
        }
        didLaunch = true
        let logPath = "\(baseDir)/logs/gui_launcher_app.log"
        let logLine = "launcher app invoked \\(Date())\\n"
        if let data = logLine.data(using: .utf8) {
            if FileManager.default.fileExists(atPath: logPath),
               let handle = try? FileHandle(forWritingTo: URL(fileURLWithPath: logPath)) {
                try? handle.seekToEnd()
                try? handle.write(contentsOf: data)
                try? handle.close()
            } else {
                try? data.write(to: URL(fileURLWithPath: logPath))
            }
        }

        let command = """
        open -n '\(baseDir.replacingOccurrences(of: "'", with: "'\\\\''"))/start_tjb_gui_window.command' && \\
        sleep 1 && \\
        open 'http://127.0.0.1:8765/?no_auto_start=1'
        """

        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/zsh")
        process.arguments = ["-lc", command]
        try? process.run()

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            NSApp.terminate(nil)
        }
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
SWIFT

swiftc "$SWIFT_FILE" -o "$EXECUTABLE" -framework AppKit

cat > "$CONTENTS_DIR/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key>
  <string>Taojinbi GUI Launcher</string>
  <key>CFBundleDisplayName</key>
  <string>Taojinbi GUI Launcher</string>
  <key>CFBundleIdentifier</key>
  <string>local.taojinbi.gui.launcher</string>
  <key>CFBundleVersion</key>
  <string>1.0</string>
  <key>CFBundleShortVersionString</key>
  <string>1.0</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleExecutable</key>
  <string>TaojinbiGuiLauncher</string>
  <key>LSUIElement</key>
  <true/>
  <key>CFBundleURLTypes</key>
  <array>
    <dict>
      <key>CFBundleTypeRole</key>
      <string>Viewer</string>
      <key>CFBundleURLName</key>
      <string>Taojinbi GUI Launcher</string>
      <key>CFBundleURLSchemes</key>
      <array>
        <string>tjb-gui</string>
      </array>
      <key>LSHandlerRank</key>
      <string>Owner</string>
    </dict>
  </array>
</dict>
</plist>
PLIST

/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$APP_DIR"

cat > "$REGISTER_SWIFT_FILE" <<'SWIFT'
import CoreServices
import Foundation

let status = LSSetDefaultHandlerForURLScheme("tjb-gui" as CFString, "local.taojinbi.gui.launcher" as CFString)
if status != noErr {
    fputs("LSSetDefaultHandlerForURLScheme failed: \(status)\n", stderr)
    exit(1)
}
SWIFT

swiftc "$REGISTER_SWIFT_FILE" -o "$REGISTER_BIN" -framework CoreServices
"$REGISTER_BIN"

echo "Done."
echo "Registered local protocol: tjb-gui://"
echo "App:"
echo "$APP_DIR"
echo
echo "You can now open:"
echo "tjb-gui://start"
