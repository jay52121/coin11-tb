#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

echo "Mac no longer starts the GUI service invisibly."
echo "Opening the visible service window instead."
echo "Stop the service from that window with Ctrl+C."

open -n "$PWD/start_tjb_gui_window.command"
