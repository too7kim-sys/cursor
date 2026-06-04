#!/usr/bin/env bash
#
# 03-import-models.sh
# 실행 위치: [폐쇄망 Linux 서버] (root 또는 sudo 권한 필요)
#
# 역할: 반입한 모델 tar.gz(manifests + blobs)를 Ollama 모델 경로에 적재하고
#       소유권/권한을 정리한 뒤 서비스를 재시작, 모델 목록을 확인한다.
#
# 사용법:
#   sudo ./03-import-models.sh [models.tar.gz경로]
#   sudo ./03-import-models.sh ./ollama-models.tar.gz
#
set -euo pipefail

MODELS_TAR="${1:-./ollama-models.tar.gz}"
OLLAMA_BASE="/usr/share/ollama/.ollama"

if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: root 권한으로 실행하세요 (sudo)." >&2
  exit 1
fi
if [ ! -f "$MODELS_TAR" ]; then
  echo "ERROR: 모델 tar 파일을 찾을 수 없습니다: $MODELS_TAR" >&2
  exit 1
fi

echo "==> [1/3] 모델 압축 해제"
mkdir -p "$OLLAMA_BASE"
# tar 내부에 'models/' 디렉터리가 포함되어 있음
tar -C "$OLLAMA_BASE" -xzf "$MODELS_TAR"

echo "==> [2/3] 소유권/권한 정리"
chown -R ollama:ollama "$OLLAMA_BASE"
systemctl restart ollama
sleep 1

echo "==> [3/3] 모델 목록 확인"
# OLLAMA_HOST 가 0.0.0.0:11434 이므로 client도 동일 포트로 조회
OLLAMA_HOST=127.0.0.1:11434 ollama list

echo "==> 완료. 테스트:"
echo "    OLLAMA_HOST=127.0.0.1:11434 ollama run qwen2.5-coder:7b \"hello\""
