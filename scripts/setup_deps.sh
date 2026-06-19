#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NCNN_VERSION="${NCNN_VERSION:-20260526}"
NCNN_ZIP="ncnn-${NCNN_VERSION}-android.zip"
NCNN_URL="https://github.com/Tencent/ncnn/releases/download/${NCNN_VERSION}/${NCNN_ZIP}"
NCNN_DST="${ROOT_DIR}/app/src/main/jni/ncnn-${NCNN_VERSION}-android"
ASSETS_DIR="${ROOT_DIR}/app/src/main/assets"
MODEL_PARAM_URL="https://raw.githubusercontent.com/nihui/ncnn-android-yolov8/master/app/src/main/assets/yolov8n.ncnn.param"
MODEL_BIN_URL="https://github.com/nihui/ncnn-android-yolov8/raw/refs/heads/master/app/src/main/assets/yolov8n.ncnn.bin"

mkdir -p "${ROOT_DIR}/app/src/main/jni" "${ASSETS_DIR}" "${ROOT_DIR}/.cache"

if [ ! -d "${NCNN_DST}" ]; then
  echo "[setup] downloading ${NCNN_ZIP}"
  curl -L --retry 3 --retry-delay 3 -o "${ROOT_DIR}/.cache/${NCNN_ZIP}" "${NCNN_URL}"
  echo "[setup] extracting ncnn"
  unzip -q "${ROOT_DIR}/.cache/${NCNN_ZIP}" -d "${ROOT_DIR}/app/src/main/jni"
else
  echo "[setup] ncnn already exists: ${NCNN_DST}"
fi

if [ ! -f "${ASSETS_DIR}/yolov8n.ncnn.param" ]; then
  echo "[setup] downloading model param"
  curl -L --retry 3 --retry-delay 3 -o "${ASSETS_DIR}/yolov8n.ncnn.param" "${MODEL_PARAM_URL}"
fi

if [ ! -f "${ASSETS_DIR}/yolov8n.ncnn.bin" ]; then
  echo "[setup] downloading model bin"
  curl -L --retry 3 --retry-delay 3 -o "${ASSETS_DIR}/yolov8n.ncnn.bin" "${MODEL_BIN_URL}"
fi

ls -lh "${ASSETS_DIR}"/yolov8n.ncnn.*
