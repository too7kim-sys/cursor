# 폐쇄망(에어갭) Ollama 서버 구축 + 로컬 VS Code 코딩 환경 가이드

인터넷이 차단된 폐쇄망에서 **Linux 서버에 Ollama를 설치**하고, 내부망의 **로컬 PC에서 VS Code + Continue 확장**으로 연결해 AI 코딩(채팅·편집·자동완성·코드베이스 질의)을 하는 방법을 단계별로 정리한 가이드입니다.

> **요약**: "VS Code + Ollama 연결"만으로 폐쇄망에서도 GitHub Copilot과 유사한 코딩 보조가 가능합니다. 체감 품질은 **올리는 모델**과 **서버 하드웨어(특히 GPU)** 에 좌우됩니다.

## 목차

1. [아키텍처 개요](#1-아키텍처-개요)
2. [하드웨어 요구사항](#2-하드웨어-요구사항)
3. [모델 선택 가이드](#3-모델-선택-가이드)
4. [인터넷 PC에서 자산 준비](#4-인터넷-pc에서-자산-준비)
5. [폐쇄망 서버로 반입](#5-폐쇄망-서버로-반입)
6. [폐쇄망 서버에 Ollama 설치](#6-폐쇄망-서버에-ollama-설치)
7. [모델 적재](#7-모델-적재)
8. [로컬 PC: VS Code + Continue 연결](#8-로컬-pc-vs-code--continue-연결)
9. [동작 확인](#9-동작-확인)
10. [문제 해결 (FAQ)](#10-문제-해결-faq)
11. [참고 링크](#11-참고-링크)

---

## 1. 아키텍처 개요

```
[인터넷 PC]                      [폐쇄망]
  ollama pull                ┌─────────────────────────┐
  바이너리 다운로드   ──USB──▶│  Ollama 서버 (Linux)     │
  모델 tar 패키징            │  0.0.0.0:11434           │
                            └───────────▲─────────────┘
                                        │ 내부망 HTTP
                            ┌───────────┴─────────────┐
                            │ 로컬 PC (VS Code+Continue)│
                            │ 로컬 PC (VS Code+Continue)│
                            └─────────────────────────┘
```

- 서버는 인터넷이 없으므로, 인터넷 PC에서 **Ollama 바이너리 + 모델**을 받아 USB/내부 전송으로 **오프라인 반입**합니다.
- 서버의 Ollama는 `OLLAMA_HOST=0.0.0.0:11434`로 내부망에 노출하고, 로컬 PC들이 HTTP로 접속합니다.
- 모든 추론은 사내 서버에서 일어나므로 코드가 외부로 나가지 않습니다.

---

## 2. 하드웨어 요구사항

서버 사양에 따라 사용할 모델 규모가 결정됩니다.

| 모델 규모 | CPU 전용 | GPU(VRAM) | 비고 |
|-----------|----------|-----------|------|
| 1.5~3B (자동완성용) | RAM 8GB+ | 4GB+ | CPU도 가능하나 GPU 권장 |
| 7B (채팅/편집용) | RAM 16GB+ (느림) | 8GB+ | 실사용은 GPU 권장 |
| 14~16B | RAM 32GB+ (매우 느림) | 16GB+ | GPU 사실상 필수 |

> **중요**: CPU만으로도 동작은 하지만, 자동완성처럼 빠른 응답이 필요한 기능은 GPU가 없으면 답답할 수 있습니다. GPU가 없다면 작은 모델(1.5~7B)을 사용하세요. GPU(NVIDIA) 가속을 쓰려면 드라이버/CUDA를 별도로 오프라인 설치해야 합니다([FAQ](#10-문제-해결-faq) 참고).

---

## 3. 모델 선택 가이드

폐쇄망에 반입할 모델은 **용도별**로 고릅니다.

| 용도 | 권장 모델 | 비고 |
|------|-----------|------|
| 채팅 / 편집 | `qwen2.5-coder:7b` (여유 시 `:14b`, `deepseek-coder-v2:16b`) | 코딩 특화, 품질 우선 |
| 자동완성(Autocomplete) | `qwen2.5-coder:1.5b` (또는 `:3b`) | 빠른 응답 우선, 작은 모델 |
| 임베딩(@codebase 검색) | `nomic-embed-text` | 코드베이스 질의 시 필수, 함께 반입 |

> 일반 채팅 모델보다 **코딩 특화 모델**(qwen2.5-coder, deepseek-coder-v2 등)을 쓰는 것이 코드 품질에 유리합니다.

---

## 4. 인터넷 PC에서 자산 준비

인터넷이 되는 리눅스 PC(서버와 같은 아키텍처, 보통 amd64)에서 진행합니다.

자동화 스크립트(`scripts/01-fetch-online.sh`)를 쓰거나, 아래를 수동으로 진행하세요.

### 4.1 Ollama 바이너리 다운로드

```bash
# amd64(x86_64) 기준. arm64면 파일명을 ollama-linux-arm64.tgz 로 교체
curl -L https://ollama.com/download/ollama-linux-amd64.tgz -o ollama-linux-amd64.tgz
sha256sum ollama-linux-amd64.tgz > ollama-linux-amd64.tgz.sha256
```

### 4.2 모델 받아서 패키징

인터넷 PC에 Ollama를 임시 설치한 뒤 모델을 받고, 모델 저장 디렉터리를 통째로 tar로 묶습니다.

```bash
# (인터넷 PC에 ollama가 없다면) 임시 설치
curl -fsSL https://ollama.com/install.sh | sh

# 용도별 모델 받기
ollama pull qwen2.5-coder:7b      # 채팅/편집
ollama pull qwen2.5-coder:1.5b    # 자동완성
ollama pull nomic-embed-text      # 임베딩(@codebase)

# 모델 저장 위치(manifests + blobs)를 통째로 패키징
#  - 표준 설치 시: /usr/share/ollama/.ollama/models
#  - 사용자 설치 시: ~/.ollama/models
tar -C /usr/share/ollama/.ollama -czf ollama-models.tar.gz models
sha256sum ollama-models.tar.gz > ollama-models.tar.gz.sha256
```

> 결과물: `ollama-linux-amd64.tgz`, `ollama-models.tar.gz`, 각 `.sha256`.

---

## 5. 폐쇄망 서버로 반입

USB 또는 허용된 내부 전송 경로로 위 파일들을 서버의 작업 디렉터리(예: `/opt/ollama-airgap`)로 복사합니다.

복사 후 **무결성 검증**:

```bash
cd /opt/ollama-airgap
sha256sum -c ollama-linux-amd64.tgz.sha256
sha256sum -c ollama-models.tar.gz.sha256
```

`OK`가 출력되면 정상입니다.

---

## 6. 폐쇄망 서버에 Ollama 설치

`scripts/02-install-server.sh`로 자동화하거나 아래를 수동으로 진행합니다. (root/sudo 권한 필요)

### 6.1 바이너리 설치

```bash
sudo tar -C /usr -xzf ollama-linux-amd64.tgz
# → /usr/bin/ollama, /usr/lib/ollama 설치됨
ollama --version
```

### 6.2 전용 사용자 생성

```bash
sudo useradd -r -s /bin/false -U -m -d /usr/share/ollama ollama
# 현재 로그인 사용자도 ollama 그룹에 추가(선택)
sudo usermod -aG ollama "$(whoami)"
```

### 6.3 systemd 서비스 등록

`/etc/systemd/system/ollama.service` 파일을 만듭니다.

```ini
[Unit]
Description=Ollama Service
After=network-online.target

[Service]
ExecStart=/usr/bin/ollama serve
User=ollama
Group=ollama
Restart=always
RestartSec=3
# 내부망의 다른 PC에서 접속 가능하도록 0.0.0.0 으로 바인딩
Environment="OLLAMA_HOST=0.0.0.0:11434"
Environment="OLLAMA_MODELS=/usr/share/ollama/.ollama/models"

[Install]
WantedBy=multi-user.target
```

> **핵심**: `OLLAMA_HOST=0.0.0.0:11434` 가 없으면 기본값(127.0.0.1)이라 **로컬 PC에서 접속이 안 됩니다.**

서비스 시작:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now ollama
sudo systemctl status ollama
```

### 6.4 방화벽 개방 (내부 서브넷만)

**ufw (Ubuntu/Debian):**

```bash
# 내부망 대역만 허용 (예: 192.168.0.0/24)
sudo ufw allow from 192.168.0.0/24 to any port 11434 proto tcp
```

**firewalld (RHEL/CentOS/Rocky):**

```bash
sudo firewall-cmd --permanent --add-rich-rule='rule family="ipv4" source address="192.168.0.0/24" port port="11434" protocol="tcp" accept'
sudo firewall-cmd --reload
```

---

## 7. 모델 적재

`scripts/03-import-models.sh`로 자동화하거나 아래를 수동으로 진행합니다.

```bash
# 모델 디렉터리에 압축 해제
sudo mkdir -p /usr/share/ollama/.ollama
sudo tar -C /usr/share/ollama/.ollama -xzf /opt/ollama-airgap/ollama-models.tar.gz

# 소유권/권한 정리 (ollama 서비스 사용자)
sudo chown -R ollama:ollama /usr/share/ollama/.ollama
sudo systemctl restart ollama

# 확인
ollama list
ollama run qwen2.5-coder:7b "Write a hello world in Python"
```

`ollama list`에 반입한 모델 3종이 보이면 성공입니다.

> **참고 — GGUF 단일 파일만 있을 때**: manifests 없이 `.gguf` 파일만 있다면 `Modelfile`을 만들어 등록할 수 있습니다.
> ```bash
> printf 'FROM /opt/models/qwen2.5-coder-7b.gguf\n' > Modelfile
> ollama create qwen2.5-coder:7b -f Modelfile
> ```

---

## 8. 로컬 PC: VS Code + Continue 연결

### 8.1 Continue 확장 설치

- 인터넷이 되면: VS Code 확장 마켓에서 **Continue** 검색 후 설치.
- 폐쇄망이면: 인터넷 PC에서 Continue `.vsix`를 받아 반입 후
  - VS Code → 확장 → `...` 메뉴 → **Install from VSIX...**, 또는
  - `code --install-extension continue.continue-<버전>.vsix`

### 8.2 Continue 설정

`~/.continue/config.yaml`(Windows: `%USERPROFILE%\.continue\config.yaml`)을 작성합니다. 이 저장소의 [`config/continue-config.yaml`](../config/continue-config.yaml) 예시를 복사하고 **서버 IP만 수정**하세요.

```yaml
name: 폐쇄망 Ollama
version: 0.0.1
schema: v1
models:
  - name: Qwen2.5 Coder (chat)
    provider: ollama
    model: qwen2.5-coder:7b
    apiBase: http://192.168.0.10:11434   # ← 서버 IP로 변경
    roles: [chat, edit, apply]

  - name: Qwen2.5 Coder (autocomplete)
    provider: ollama
    model: qwen2.5-coder:1.5b
    apiBase: http://192.168.0.10:11434   # ← 서버 IP로 변경
    roles: [autocomplete]

  - name: Nomic Embed
    provider: ollama
    model: nomic-embed-text
    apiBase: http://192.168.0.10:11434   # ← 서버 IP로 변경
    roles: [embed]
```

### 8.3 사용법

- **채팅**: `Ctrl+L` (Mac `Cmd+L`)
- **인라인 편집**: 코드 선택 후 `Ctrl+I`
- **자동완성**: 코딩 중 자동 제안 → `Tab`으로 수락
- **코드베이스 질의**: 채팅에 `@codebase 로그인 처리 어디서 해?` 처럼 입력 (임베딩 모델 필요)

---

## 9. 동작 확인

**서버에서:**

```bash
curl http://localhost:11434/api/tags          # 설치된 모델 목록(JSON)
```

**로컬 PC에서 (서버 연결 확인):**

```bash
curl http://192.168.0.10:11434/api/tags        # 서버 IP로
```

모델 목록 JSON이 돌아오면 네트워크 연결 정상입니다. 이후 VS Code에서 Continue 채팅에 질문해 응답이 오면 끝입니다.

---

## 10. 문제 해결 (FAQ)

**Q. 로컬 PC에서 서버에 연결이 안 됩니다.**
A. ① 서버 `ollama.service`에 `OLLAMA_HOST=0.0.0.0:11434`가 있는지, ② 방화벽 11434 포트가 내부망에 열렸는지, ③ `curl http://<서버IP>:11434/api/tags`가 되는지 순서대로 확인하세요.

**Q. `ollama list`에 모델이 안 보입니다.**
A. 모델 경로(`/usr/share/ollama/.ollama/models`) 안에 `manifests`와 `blobs` 폴더가 모두 있는지, 소유권이 `ollama:ollama`인지 확인 후 `sudo systemctl restart ollama`.

**Q. 응답이 너무 느립니다.**
A. CPU 전용 환경이면 모델 크기를 낮추세요(7b→3b/1.5b). 가능하면 GPU 서버를 사용하세요.

**Q. GPU를 쓰고 싶습니다.**
A. NVIDIA 드라이버 + CUDA를 **별도로 오프라인 설치**해야 합니다(이 가이드 범위 밖). 설치 후 Ollama가 자동으로 GPU를 인식하며, `ollama ps`에서 GPU 사용 여부를 확인할 수 있습니다.

**Q. 메모리 부족(OOM) 오류가 납니다.**
A. 더 작은 모델을 쓰거나, 동시에 로드되는 모델 수를 줄이세요. `OLLAMA_MAX_LOADED_MODELS` 환경 변수로 제한할 수 있습니다.

**Q. 아키텍처가 arm64입니다.**
A. 4.1의 tgz 파일명을 `ollama-linux-arm64.tgz`로 바꾸고, 모델 패키징은 동일하게 진행하면 됩니다(모델은 아키텍처 무관).

---

## 11. 참고 링크

> 폐쇄망에서는 접속이 안 될 수 있습니다. 인터넷 PC에서 참고하세요.

- Ollama 공식: https://ollama.com
- Ollama Linux 수동 설치 문서: https://github.com/ollama/ollama/blob/main/docs/linux.md
- Ollama 모델 라이브러리: https://ollama.com/library
- Continue 공식 문서: https://docs.continue.dev
- Continue + Ollama 설정: https://docs.continue.dev/customize/model-providers/ollama
