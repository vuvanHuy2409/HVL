#!/bin/zsh
set -euo pipefail

APP_DEST="$HOME/Applications/HVL.app"
PLIST="$HOME/Library/LaunchAgents/local.hvl.plist"
USER_ID="$(id -u)"

launchctl bootout "gui/$USER_ID/local.hvl" >/dev/null 2>&1 || true
rm -f "$PLIST"
if [[ -d "$APP_DEST" ]]; then
  rm -rf "$APP_DEST"
fi

osascript -e 'display dialog "Đã gỡ HVL và chế độ chạy nền khỏi máy." with title "HVL" buttons {"OK"} default button "OK"'
