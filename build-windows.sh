#!/bin/zsh
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
SOURCE_DIR="$PROJECT_DIR/music"
DIST_DIR="$PROJECT_DIR/dist"
JDK_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
MAKENSIS="${MAKENSIS:-/opt/homebrew/bin/makensis}"
JRE_URL="${WINDOWS_JRE_URL:-https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse}"

if [[ ! -x "$JDK_HOME/bin/java" ]]; then
  print -u2 "Không tìm thấy JDK 21 tại: $JDK_HOME"
  exit 1
fi
if [[ ! -x "$MAKENSIS" ]]; then
  print -u2 "Không tìm thấy makensis. Cài bằng: brew install makensis"
  exit 1
fi
if [[ ! -d "$SOURCE_DIR" ]]; then
  print -u2 "Không tìm thấy thư mục nguồn nhạc: $SOURCE_DIR"
  exit 1
fi
if [[ ! -f "$SOURCE_DIR/cover.jpg" ]]; then
  print -u2 "Không tìm thấy ảnh icon: $SOURCE_DIR/cover.jpg"
  exit 1
fi

export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"

mkdir -p "$DIST_DIR"
print "Đang biên dịch Java dùng chung cho macOS/Windows…"
mvn -q clean package

STAGE_DIR="$(mktemp -d /tmp/hvl-windows-stage.XXXXXX)"
JRE_ZIP="$(mktemp /tmp/hvl-windows-jre.XXXXXX)"
JRE_EXTRACT="$(mktemp -d /tmp/hvl-windows-jre-extract.XXXXXX)"
trap 'rm -rf "$STAGE_DIR" "$JRE_ZIP" "$JRE_EXTRACT"' EXIT

mkdir -p "$STAGE_DIR/app/music"
cp "$PROJECT_DIR/target/hvl-player.jar" "$STAGE_DIR/app/HVLPlayer.jar"
cp "$SOURCE_DIR/cover.jpg" "$STAGE_DIR/app/music/cover.jpg"
for file in "$SOURCE_DIR"/*.flac; do
  [[ -f "$file" ]] || continue
  cp "$file" "$STAGE_DIR/app/music/$(basename "$file")"
done

print "Đang tải JRE Windows x64 đi kèm…"
curl -fL --retry 3 --retry-delay 2 --output "$JRE_ZIP" "$JRE_URL"
/usr/bin/ditto -x -k "$JRE_ZIP" "$JRE_EXTRACT"
WINDOWS_JAVA="$(find "$JRE_EXTRACT" -type f -name javaw.exe -print -quit)"
if [[ -z "$WINDOWS_JAVA" ]]; then
  print -u2 "JRE tải về không có javaw.exe"
  exit 1
fi
WINDOWS_JRE_ROOT="$(cd -- "$(dirname -- "$WINDOWS_JAVA")/.." && pwd)"
/usr/bin/ditto "$WINDOWS_JRE_ROOT" "$STAGE_DIR/runtime"

print "Đang tạo icon Windows từ cover.jpg…"
magick "$SOURCE_DIR/cover.jpg" -define icon:auto-resize=256,128,64,48,32,16 "$STAGE_DIR/HVL.ico"

OUTPUT_FILE="$DIST_DIR/HVL-1.0.0-Windows.exe"
rm -f "$OUTPUT_FILE"
print "Đang tạo bộ cài Windows x64 (có thể mất vài phút vì gồm 30 FLAC)…"
"$MAKENSIS" \
  -DAPP_NAME=HVL \
  -DAPP_VERSION=1.0.0 \
  -DSTAGE_DIR="$STAGE_DIR" \
  -DOUTPUT_FILE="$OUTPUT_FILE" \
  "$PROJECT_DIR/build-windows.nsi"

print "Hoàn tất: $OUTPUT_FILE"
