#!/bin/bash
# LifeLens Deployment Script
# Deploys APK + model files to an Android device via adb
#
# Usage:
#   ./deploy.sh              # Install APK + push model files
#   ./deploy.sh --model-only # Only push model files (APK already installed)
#   ./deploy.sh --apk-only   # Only install APK (model already on device)

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_PATH="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
MODEL_DIR="$SCRIPT_DIR/model_deploy/OmniNeural-4B-mobile"
DEVICE_MODEL_DIR="/sdcard/Android/data/com.example.lifelens/files/models/OmniNeural-4B-mobile"
PACKAGE="com.example.lifelens"

# Parse args
MODE="all"
if [ "$1" = "--model-only" ]; then MODE="model"; fi
if [ "$1" = "--apk-only" ]; then MODE="apk"; fi

# Check adb
if ! command -v adb &> /dev/null; then
    echo "Error: adb not found. Install Android SDK platform-tools."
    exit 1
fi

# Check device
if ! adb devices | grep -q "device$"; then
    echo "Error: No Android device connected."
    echo "Connect via USB or set ADB_HOST for remote device."
    exit 1
fi

echo "=== LifeLens Deployment ==="
echo ""

# Install APK
if [ "$MODE" = "all" ] || [ "$MODE" = "apk" ]; then
    if [ ! -f "$APK_PATH" ]; then
        echo "APK not found at: $APK_PATH"
        echo "Building APK..."
        "$SCRIPT_DIR/gradlew" assembleDebug
    fi
    echo "[1/2] Installing APK..."
    adb install -r "$APK_PATH"
    echo "APK installed."
    echo ""
fi

# Push model files
if [ "$MODE" = "all" ] || [ "$MODE" = "model" ]; then
    if [ ! -d "$MODEL_DIR" ]; then
        echo "Error: Model files not found at: $MODEL_DIR"
        echo "Download them first from HuggingFace: NexaAI/OmniNeural-4B-mobile"
        exit 1
    fi

    echo "[2/2] Pushing model files to device..."
    echo "Target: $DEVICE_MODEL_DIR"
    echo ""

    adb shell mkdir -p "$DEVICE_MODEL_DIR"

    for f in "$MODEL_DIR"/*; do
        fname=$(basename "$f")
        fsize=$(du -h "$f" | cut -f1)
        echo "  Pushing $fname ($fsize)..."
        adb push "$f" "$DEVICE_MODEL_DIR/$fname"
    done

    echo ""
    echo "Model files deployed."
fi

echo ""
echo "=== Deployment complete ==="
echo ""
echo "To launch the app:"
echo "  adb shell am start -n $PACKAGE/.MainActivity"
