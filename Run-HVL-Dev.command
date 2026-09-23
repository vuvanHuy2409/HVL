#!/bin/zsh
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
JDK_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"

cd "$PROJECT_DIR"
export JAVA_HOME="$JDK_HOME"
export PATH="$JDK_HOME/bin:/opt/homebrew/bin:$PATH"
MUSIC_DIR="$PROJECT_DIR/music"
if [[ ! -f "$MUSIC_DIR/cover.jpg" ]] || \
   [[ "$(find "$MUSIC_DIR" -maxdepth 1 -type f -name '*.flac' | wc -l | tr -d ' ')" != 30 ]]; then
  print -u2 "Thư viện nhạc chưa đầy đủ. Hãy tải FLAC bằng: git lfs pull --include='music/*.flac'"
  exit 1
fi

mvn -q package
xcrun clang -dynamiclib -fobjc-arc -framework Foundation -framework MediaPlayer \
  -I"$JDK_HOME/include" -I"$JDK_HOME/include/darwin" \
  -o "$PROJECT_DIR/target/libhvlmediakeys.dylib" \
  "$PROJECT_DIR/src/main/native/MacMediaKeys.m"

exec "$JDK_HOME/bin/java" \
  -Dapple.awt.application.name="HVL Dev" \
  -Dapple.awt.UIElement=false \
  -Dhvl.music.dir="$MUSIC_DIR" \
  -jar "$PROJECT_DIR/target/hvl-player.jar"
