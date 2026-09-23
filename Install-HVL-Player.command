#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
APP_SOURCE="$SCRIPT_DIR/HVL.app"
APP_DIR="$HOME/Applications"
APP_DEST="$APP_DIR/HVL.app"
AGENT_DIR="$HOME/Library/LaunchAgents"
PLIST="$AGENT_DIR/local.hvl.plist"
USER_ID="$(id -u)"

if [[ ! -d "$APP_SOURCE" ]]; then
  osascript -e 'display dialog "Không tìm thấy HVL.app trong thư mục này." with title "HVL" buttons {"OK"} default button "OK"'
  exit 1
fi

mkdir -p "$APP_DIR" "$AGENT_DIR"
if [[ -d "$APP_DEST" ]]; then
  rm -rf "$APP_DEST"
fi
ditto "$APP_SOURCE" "$APP_DEST"

cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>local.hvl</string>
    <key>ProgramArguments</key>
    <array>
        <string>$APP_DEST/Contents/MacOS/HVL</string>
        <string>--background</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>LimitLoadToSessionType</key>
    <string>Aqua</string>
</dict>
</plist>
EOF
chmod 644 "$PLIST"

launchctl bootout "gui/$USER_ID/local.hvl" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$USER_ID" "$PLIST"
open "$APP_DEST"

osascript -e 'display dialog "HVL đã được cài vào ~/Applications và chạy nền cùng macOS.\n\nBấm icon HVL trên thanh menu để mở bảng điều khiển nhanh. Các phím media lùi, phát/tạm dừng và tiến trên bàn phím cũng điều khiển HVL sau khi bắt đầu phát một bài.\n\nHVL không cần quyền Accessibility hoặc Input Monitoring." with title "HVL đã sẵn sàng" buttons {"OK"} default button "OK"'
