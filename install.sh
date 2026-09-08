#!/usr/bin/env bash
set -euo pipefail
PLUGIN_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST_DIR="${RUNELITE_HOME:-$HOME/.runelite}/sideloaded-plugins"
JAR="$PLUGIN_DIR/build/libs/betterpartydefence-1.1.0.jar"

if [[ ! -f "$JAR" ]]; then
  echo "No built jar found. Building it now..."
  "$PLUGIN_DIR/gradlew" jar
fi

if [[ ! -f "$JAR" ]]; then
  echo "ERROR: build completed but $JAR was not found." >&2
  exit 1
fi

mkdir -p "$DEST_DIR"
cp -f "$JAR" "$DEST_DIR/BetterPartyDefence.jar"
echo "Installed developer sideload: $DEST_DIR/BetterPartyDefence.jar"
echo "Start RuneLite in true developer mode to load sideloaded plugins."
