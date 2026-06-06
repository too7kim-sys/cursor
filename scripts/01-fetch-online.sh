#!/usr/bin/env bash
#
# 01-fetch-online.sh
# 실행 위치: [인터넷이 되는 Linux PC] (서버와 동일 아키텍처 권장, 보통 amd64)
#
# 역할: 폐쇄망 서버로 반입할 자산을 준비한다.
#   1) Ollama Linux 바이너리(tgz) 다운로드
#   2) 코딩용 모델 pull (채팅/자동완성/임베딩)
#   3) 모델 저장 디렉터리를 tar.gz로 패키징
#   4) 각 산출물의 sha256 체크섬 생성
#
# 사용법:
#   ./01-fetch-online.sh [출력디렉터리]
#   ARCH=arm64 ./01-fetch-online.sh       # arm64 서버용
#
set -euo pipefail

ARCH="${ARCH:-amd64}"                       # amd64 | arm64
OUT_DIR="${1:-./ollama-airgap-out}"
TGZ="ollama-linux-${ARCH}.tgz"
MODELS_TAR="ollama-models.tar.gz"

# 반입할 모델 목록 (용도별). 필요에 따라 수정하세요.
MODELS=(
  "qwen3-coder:30b"          # 채팅/편집/자동수정(Agent) — 도구 호출 지원
  "qwen2.5-coder:1.5b-base"  # 자동완성
  "nomic-embed-text"         # 임베딩(@codebase)
)

mkdir -p "$OUT_DIR"
cd "$OUT_DIR"

echo "==> [1/4] Ollama 바이너리 다운로드 ($ARCH)"
curl -fL "https://ollama.com/download/${TGZ}" -o "$TGZ"
sha256sum "$TGZ" > "${TGZ}.sha256"

echo "==> [2/4] 모델 pull"
if ! command -v ollama >/dev/null 2>&1; then
  echo "    ollama 미설치 → 임시 설치를 시도합니다."
  curl -fsSL https://ollama.com/install.sh | sh
fi
for m in "${MODELS[@]}"; do
  echo "    pull: $m"
  ollama pull "$m"
done

echo "==> [3/4] 모델 디렉터리 패키징"
# 표준 설치 경로 우선, 없으면 사용자 홈 경로 사용
if [ -d /usr/share/ollama/.ollama/models ]; then
  MODELS_BASE=/usr/share/ollama/.ollama
elif [ -d "$HOME/.ollama/models" ]; then
  MODELS_BASE="$HOME/.ollama"
else
  echo "ERROR: 모델 디렉터리를 찾을 수 없습니다 (.ollama/models)" >&2
  exit 1
fi
echo "    모델 경로: ${MODELS_BASE}/models"
tar -C "$MODELS_BASE" -czf "$MODELS_TAR" models
sha256sum "$MODELS_TAR" > "${MODELS_TAR}.sha256"

echo "==> [4/4] 완료. 아래 파일을 폐쇄망 서버로 반입하세요:"
ls -lh "$TGZ" "${TGZ}.sha256" "$MODELS_TAR" "${MODELS_TAR}.sha256"
