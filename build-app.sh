#!/bin/zsh
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
BUILD_DIR="$PROJECT_DIR/build"
DIST_DIR="$PROJECT_DIR/dist"
INPUT_DIR="$BUILD_DIR/app-input"
SOURCE_DIR="$PROJECT_DIR/music"
JDK_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"

if [[ ! -x "$JDK_HOME/bin/jpackage" ]]; then
  print -u2 "Không tìm thấy JDK 21/jpackage tại: $JDK_HOME"
  exit 1
fi
if [[ ! -d "$SOURCE_DIR" ]]; then
  print -u2 "Không tìm thấy thư mục nguồn nhạc: $SOURCE_DIR"
  exit 1
fi

export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"

rm -rf "$INPUT_DIR" "$DIST_DIR/HVL.app" "$DIST_DIR/HVL Player.app" "$DIST_DIR/dmg" \
  "$DIST_DIR/HVL-1.0.0.dmg" "$DIST_DIR/HVL Player-1.0.0.dmg"
mkdir -p "$INPUT_DIR/music" "$DIST_DIR"

print "Đang biên dịch Java…"
mvn -q clean package
cp "$PROJECT_DIR/target/hvl-player.jar" "$INPUT_DIR/HVLPlayer.jar"
xcrun clang -dynamiclib -fobjc-arc -framework Foundation -framework MediaPlayer \
  -I"$JDK_HOME/include" -I"$JDK_HOME/include/darwin" \
  -o "$INPUT_DIR/libhvlmediakeys.dylib" "$PROJECT_DIR/src/main/native/MacMediaKeys.m"
ditto "$SOURCE_DIR/cover.jpg" "$INPUT_DIR/music/cover.jpg"
for file in "$SOURCE_DIR"/*.flac; do
  [[ -f "$file" ]] || continue
  ditto "$file" "$INPUT_DIR/music/$(basename "$file")"
done
xattr -rc "$INPUT_DIR"

ICON_PATH="$BUILD_DIR/HVL.icns"
ICON_SOURCE="$PROJECT_DIR/music/cover.jpg"
rm -rf "$BUILD_DIR/HVL.iconset" "$ICON_PATH"
if [[ -f "$ICON_SOURCE" ]]; then
  ICONSET="$BUILD_DIR/HVL.iconset"
  mkdir -p "$ICONSET"
  for size in 16 32 128 256 512; do
    sips -s format png -z "$size" "$size" "$ICON_SOURCE" --out "$ICONSET/icon_${size}x${size}.png" >/dev/null
    double=$((size * 2))
    sips -s format png -z "$double" "$double" "$ICON_SOURCE" --out "$ICONSET/icon_${size}x${size}@2x.png" >/dev/null
  done
  iconutil -c icns -o "$ICON_PATH" "$ICONSET"
fi

JPACKAGE_ARGS=(
  --type app-image
  --input "$INPUT_DIR"
  --name "HVL"
  --main-jar "HVLPlayer.jar"
  --main-class "com.hvlplayer.HvlPlayer"
  --app-version "1.0.0"
  --vendor "HVL"
  --description "Trình nghe nhạc nhỏ gọn cho album HVL"
  --mac-package-identifier "local.hvl"
  --mac-package-name "HVL"
  --java-options "-Dfile.encoding=UTF-8"
)
if [[ -f "$ICON_PATH" ]]; then
  JPACKAGE_ARGS+=(--icon "$ICON_PATH")
fi

JPACKAGE_OUTPUT="$(mktemp -d /tmp/hvl-player-output.XXXXXX)"
JPACKAGE_TEMP="$(mktemp -d /tmp/hvl-player-temp.XXXXXX)"
DMG_DIR="$(mktemp -d /tmp/hvl-player-dmg.XXXXXX)"
trap 'rm -rf "$JPACKAGE_OUTPUT" "$JPACKAGE_TEMP" "$DMG_DIR"' EXIT

JPACKAGE_ARGS+=(--dest "$JPACKAGE_OUTPUT" --temp "$JPACKAGE_TEMP")

print "Đang tạo HVL.app…"
jpackage "${JPACKAGE_ARGS[@]}"

mkdir -p "$DMG_DIR"
ditto --norsrc --noextattr "$JPACKAGE_OUTPUT/HVL.app" "$DMG_DIR/HVL.app"
ln -s /Applications "$DMG_DIR/Applications"
cp "$PROJECT_DIR/Install-HVL-Player.command" "$DMG_DIR/Install-HVL-Player.command"
cp "$PROJECT_DIR/Uninstall-HVL-Player.command" "$DMG_DIR/Uninstall-HVL-Player.command"
cp "$PROJECT_DIR/README.md" "$DMG_DIR/README.md"
chmod +x "$DMG_DIR/Install-HVL-Player.command" "$DMG_DIR/Uninstall-HVL-Player.command"

print "Đang tạo bộ cài DMG…"
hdiutil create -volname "HVL" -srcfolder "$DMG_DIR" -ov -format UDZO "$DIST_DIR/HVL-1.0.0.dmg" >/dev/null

rm -rf "$INPUT_DIR"
rm -rf "$BUILD_DIR/HVL.iconset"
print "Hoàn tất: $DIST_DIR/HVL-1.0.0.dmg"
