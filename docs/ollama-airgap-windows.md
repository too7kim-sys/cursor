# 폐쇄망(에어갭) Ollama 서버 구축 (Windows 서버) + 로컬 VS Code 코딩 환경

인터넷이 차단된 폐쇄망에서 **Windows 서버에 Ollama를 설치**하고, 내부망의 **로컬 PC에서 VS Code + Continue 확장**으로
연결해 AI 코딩(채팅·편집·자동완성·자동수정·코드베이스 질의)을 하는 방법입니다.

> 서버가 **Linux**라면 → [ollama-airgap-linux.md](ollama-airgap-linux.md)
> 로컬 PC(Windows) 개발환경 설정은 공통입니다 → [local-dev-setup-windows.md](local-dev-setup-windows.md)

## 목차
1. [아키텍처 개요](#1-아키텍처-개요)
2. [하드웨어 요구사항](#2-하드웨어-요구사항)
3. [모델 선택 가이드](#3-모델-선택-가이드)
4. [인터넷 PC에서 자산 준비](#4-인터넷-pc에서-자산-준비)
5. [폐쇄망 서버로 반입](#5-폐쇄망-서버로-반입)
6. [Windows 서버에 Ollama 설치](#6-windows-서버에-ollama-설치)
7. [내부망 노출 + 방화벽](#7-내부망-노출--방화벽)
8. [모델 적재](#8-모델-적재)
9. [동작 확인](#9-동작-확인)
10. [로컬 PC 연결](#10-로컬-pc-연결)
11. [문제 해결 (FAQ)](#11-문제-해결-faq)

---

## 1. 아키텍처 개요

```
[인터넷 PC]                        [폐쇄망]
  ollama pull                  ┌──────────────────────────┐
  설치파일/모델 준비   ──USB──▶ │  Ollama 서버 (Windows)    │
                              │  0.0.0.0:11434            │
                              └────────────▲─────────────┘
                                           │ 내부망 HTTP
                              ┌────────────┴─────────────┐
                              │ 로컬 PC (VS Code+Continue) │
                              └──────────────────────────┘
```

- 서버는 인터넷이 없으므로, 인터넷 PC에서 **Ollama 설치 파일 + 모델**을 받아 USB/내부 전송으로 반입합니다.
- 서버 Ollama를 `OLLAMA_HOST=0.0.0.0` 로 내부망에 노출하고, 로컬 PC들이 HTTP(11434)로 접속합니다.
- 모든 추론이 사내 서버에서 일어나 코드가 외부로 나가지 않습니다.

---

## 2. 하드웨어 요구사항

| 모델 규모 | CPU 전용 | GPU(VRAM) | 비고 |
|-----------|----------|-----------|------|
| 1.5~3B (자동완성) | RAM 8GB+ | 4GB+ | CPU 가능, GPU 권장 |
| 7B (채팅/편집) | RAM 16GB+ (느림) | 8GB+ | 실사용은 GPU 권장 |
| 14~32B | RAM 32GB+ (매우 느림) | 16GB+ | GPU 사실상 필수 |

> CPU만으로도 동작하지만 자동완성처럼 빠른 응답이 필요하면 GPU가 유리합니다. NVIDIA GPU를 쓰려면 최신 그래픽 드라이버를 설치하면 Ollama가 자동 인식합니다(폐쇄망은 드라이버도 오프라인 설치 필요).

---

## 3. 모델 선택 가이드

| 용도 | 권장 모델 | 비고 |
|------|-----------|------|
| 채팅 / 편집 / **자동수정(Agent)** | `qwen3-coder:30b` (가벼우면 `qwen2.5-coder:7b`/`14b`) | 코딩 특화 + **도구 호출 지원** |
| 자동완성(Autocomplete) | `qwen2.5-coder:1.5b-base` (또는 `:3b-base`) | 빠른 응답, FIM 지원 작은 모델 |
| 임베딩(@codebase) | `nomic-embed-text` 또는 `bge-m3` | 코드베이스 질의 시 필수 |

> **Agent(자동 수정)** 를 쓰려면 chat 모델이 **도구 호출(tool_use)** 을 지원해야 합니다. `qwen3-coder` 가 이에 해당합니다.

---

## 4. 인터넷 PC에서 자산 준비

인터넷이 되는 **Windows PC** 에서 진행합니다.

### 4.1 Ollama 설치 파일 받기
- Windows 설치 파일: `OllamaSetup.exe` (https://ollama.com/download/windows)
- 또는 무인 설치/압축본을 원하면 GitHub 릴리스의 `ollama-windows-amd64.zip` 사용.

### 4.2 모델 받아서 준비
인터넷 PC에 Ollama를 설치(또는 압축 해제)한 뒤 모델을 받습니다.

```powershell
ollama pull qwen3-coder:30b          # 채팅/편집/자동수정
ollama pull qwen2.5-coder:1.5b-base  # 자동완성
ollama pull nomic-embed-text         # 임베딩
```

받은 모델은 기본적으로 다음 위치에 저장됩니다(manifests + blobs):
```
%USERPROFILE%\.ollama\models
```
이 `models` 폴더 전체를 압축합니다(PowerShell):
```powershell
Compress-Archive -Path "$env:USERPROFILE\.ollama\models" -DestinationPath "ollama-models.zip"
```

> 결과물: `OllamaSetup.exe`(또는 zip), `ollama-models.zip`. 가능하면 `Get-FileHash` 로 체크섬도 같이 보관하세요.
> ```powershell
> Get-FileHash ollama-models.zip -Algorithm SHA256
> ```

---

## 5. 폐쇄망 서버로 반입

USB/허용된 내부 전송으로 위 파일을 서버의 작업 폴더(예: `C:\ollama-airgap`)로 복사합니다.
체크섬을 보관했다면 서버에서 검증:
```powershell
Get-FileHash C:\ollama-airgap\ollama-models.zip -Algorithm SHA256
```

---

## 6. Windows 서버에 Ollama 설치

### 6.1 설치
- `OllamaSetup.exe` 실행 → 설치. 설치되면 트레이에 Ollama가 뜨고, `ollama` 명령이 PATH에 등록됩니다.
- (zip 버전 사용 시) 원하는 폴더에 압축 해제 후 그 폴더를 PATH에 추가.

확인(새 PowerShell):
```powershell
ollama --version
```

---

## 7. 내부망 노출 + 방화벽

기본값은 `127.0.0.1` 이라 다른 PC에서 접속이 안 됩니다. **`OLLAMA_HOST=0.0.0.0` 시스템 환경변수**를 설정해야 합니다.

### 7.1 OLLAMA_HOST 설정 (시스템 환경변수, 관리자 PowerShell)
```powershell
setx OLLAMA_HOST "0.0.0.0:11434" /M
```
> `/M` 은 시스템 전역 설정. 설정 후 **Ollama를 재시작**해야 적용됩니다.
> 트레이의 Ollama 종료 → 다시 실행, 또는 서비스로 등록했다면 서비스 재시작.

(선택) 모델 저장 경로를 바꾸려면:
```powershell
setx OLLAMA_MODELS "D:\ollama\models" /M
```

### 7.2 방화벽 개방 (내부 서브넷만, 관리자 PowerShell)
```powershell
New-NetFirewallRule -DisplayName "Ollama 11434" -Direction Inbound -Action Allow `
  -Protocol TCP -LocalPort 11434 -RemoteAddress 192.168.45.0/24
```
> `RemoteAddress` 를 실제 내부망 대역으로 바꾸세요. 전체 허용은 보안상 비권장.

### 7.3 (선택) 부팅 시 자동 실행 — 서비스화
설치본은 로그인 사용자 세션에서 트레이로 뜹니다. 서버에서 **로그인 없이 상시 구동**하려면
`nssm`(Non-Sucking Service Manager) 같은 도구로 `ollama serve` 를 Windows 서비스로 등록하는 방법이 일반적입니다. (폐쇄망은 nssm도 오프라인 반입)

---

## 8. 모델 적재

반입한 `ollama-models.zip` 을 서버의 모델 폴더에 풉니다.

```powershell
# 기본 경로: %USERPROFILE%\.ollama\models
# (OLLAMA_MODELS 를 바꿨다면 그 경로로)
Expand-Archive -Path C:\ollama-airgap\ollama-models.zip -DestinationPath "$env:USERPROFILE\.ollama" -Force
```

> 압축 안에 `models` 폴더가 통째로 들어있어야 하며, 풀고 나면
> `...\.ollama\models\manifests` 와 `...\.ollama\models\blobs` 가 보여야 합니다.

Ollama 재시작 후 확인:
```powershell
ollama list
ollama run qwen3-coder:30b "Write hello world in Java"
```
반입한 모델들이 보이면 성공입니다.

---

## 9. 동작 확인

**서버에서:**
```powershell
curl http://localhost:11434/api/tags
```

**도구 호출(Agent용) 확인 — 핵심:**
```powershell
curl http://localhost:11434/api/chat -d '{\"model\":\"qwen3-coder:30b\",\"messages\":[{\"role\":\"user\",\"content\":\"list files\"}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"ls\",\"description\":\"list dir\",\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}}}],\"stream\":false}'
```
응답에 `"tool_calls":[...]` 가 구조화되어 나오면 자동 수정(Agent)이 가능한 상태입니다.

---

## 10. 로컬 PC 연결

로컬 PC(Windows)에서 VS Code + Continue 설정, JDK/Tomcat, 인코딩까지의 설정은
**[local-dev-setup-windows.md](local-dev-setup-windows.md)** 를 따르세요.

로컬 PC에서 서버 연결 확인:
```cmd
curl http://192.168.45.214:11434/api/tags
```

---

## 11. 문제 해결 (FAQ)

**Q. 로컬 PC에서 서버에 연결이 안 됩니다.**
A. ① 서버에 `OLLAMA_HOST=0.0.0.0:11434` 가 설정되고 Ollama가 **재시작**됐는지, ② 방화벽 11434가 내부망에 열렸는지, ③ 서버에서 `curl http://localhost:11434/api/tags` 가 되는지 순서대로 확인하세요.

**Q. `setx OLLAMA_HOST` 했는데 그대로예요.**
A. `setx` 는 새 프로세스부터 적용됩니다. Ollama(트레이/서비스)를 **완전히 종료 후 재실행**하세요. `echo %OLLAMA_HOST%` 를 새 창에서 확인.

**Q. `ollama list` 에 모델이 안 보입니다.**
A. 모델 경로 안에 `manifests` 와 `blobs` 가 모두 있는지 확인하세요. 압축을 `.ollama` 상위에 풀어 `models` 폴더 구조가 유지돼야 합니다.

**Q. 응답이 너무 느립니다.**
A. CPU 전용이면 모델을 낮추세요(30b→7b/3b). GPU 서버 권장.

**Q. Agent에서 `<function=...>` 텍스트만 나와요.**
A. 9번 curl로 서버의 `tool_calls` 출력을 먼저 확인하세요. 서버가 정상이면 원인은 로컬 Continue(구버전) 입니다 → [local-dev-setup-windows.md 4번](local-dev-setup-windows.md#4-자동-수정agent-동작-조건) 참고.

---

## 참고 링크
> 폐쇄망에서는 접속이 안 될 수 있습니다. 인터넷 PC에서 참고하세요.

- Ollama 공식: https://ollama.com
- Ollama Windows 문서: https://github.com/ollama/ollama/blob/main/docs/windows.md
- Ollama 모델 라이브러리: https://ollama.com/library
- Continue 공식 문서: https://docs.continue.dev
