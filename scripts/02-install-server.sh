#!/usr/bin/env bash
#
# 02-install-server.sh
# 실행 위치: [폐쇄망 Linux 서버] (root 또는 sudo 권한 필요)
#
# 역할: 반입한 Ollama 바이너리(tgz)로 오프라인 설치하고,
#       ollama 전용 사용자와 systemd 서비스를 구성한다.
#       내부망 접속을 위해 0.0.0.0:11434 로 바인딩한다.
#
# 사용법:
#   sudo ./02-install-server.sh [tgz경로] [허용서브넷]
#   sudo ./02-install-server.sh ./ollama-linux-amd64.tgz 192.168.0.0/24
#
set -euo pipefail

TGZ="${1:-./ollama-linux-amd64.tgz}"
ALLOW_SUBNET="${2:-}"                       # 비우면 방화벽 설정 건너뜀
MODELS_DIR="/usr/share/ollama/.ollama/models"
SERVICE="/etc/systemd/system/ollama.service"

if [ "$(id -u)" -ne 0 ]; then
  echo "ERROR: root 권한으로 실행하세요 (sudo)." >&2
  exit 1
fi
if [ ! -f "$TGZ" ]; then
  echo "ERROR: tgz 파일을 찾을 수 없습니다: $TGZ" >&2
  exit 1
fi

echo "==> [1/4] 바이너리 설치"
tar -C /usr -xzf "$TGZ"
/usr/bin/ollama --version || true

echo "==> [2/4] ollama 사용자 생성"
if ! id ollama >/dev/null 2>&1; then
  useradd -r -s /bin/false -U -m -d /usr/share/ollama ollama
  echo "    사용자 'ollama' 생성됨"
else
  echo "    사용자 'ollama' 이미 존재 → 건너뜀"
fi
mkdir -p "$MODELS_DIR"
chown -R ollama:ollama /usr/share/ollama

echo "==> [3/4] systemd 서비스 등록"
cat > "$SERVICE" <<'UNIT'
[Unit]
Description=Ollama Service
After=network-online.target

[Service]
ExecStart=/usr/bin/ollama serve
User=ollama
Group=ollama
Restart=always
RestartSec=3
Environment="OLLAMA_HOST=0.0.0.0:11434"
Environment="OLLAMA_MODELS=/usr/share/ollama/.ollama/models"

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now ollama
sleep 1
systemctl --no-pager --full status ollama || true

echo "==> [4/4] 방화벽 설정"
if [ -n "$ALLOW_SUBNET" ]; then
  if command -v ufw >/dev/null 2>&1; then
    ufw allow from "$ALLOW_SUBNET" to any port 11434 proto tcp
    echo "    ufw: $ALLOW_SUBNET → 11434/tcp 허용"
  elif command -v firewall-cmd >/dev/null 2>&1; then
    firewall-cmd --permanent --add-rich-rule="rule family=\"ipv4\" source address=\"$ALLOW_SUBNET\" port port=\"11434\" protocol=\"tcp\" accept"
    firewall-cmd --reload
    echo "    firewalld: $ALLOW_SUBNET → 11434/tcp 허용"
  else
    echo "    ufw/firewalld 없음 → 방화벽 수동 설정 필요"
  fi
else
  echo "    허용 서브넷 미지정 → 방화벽 설정 건너뜀 (2번째 인자로 전달 가능)"
fi

echo "==> 설치 완료. 다음으로 03-import-models.sh 를 실행해 모델을 적재하세요."
