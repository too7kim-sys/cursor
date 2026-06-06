# 로컬 PC(Windows) 개발 환경 설정 — VS Code + Continue + eGov/Tomcat

폐쇄망 Ollama 서버가 준비된 뒤, **개발자 로컬 PC(Windows)** 에서 VS Code로 코딩 AI를 사용하고
eGov(전자정부 표준프레임워크) 프로젝트를 Tomcat으로 기동하기까지의 설정을 정리한 문서입니다.

서버 구축은 OS에 따라 아래 문서를 먼저 진행하세요.
- Linux 서버: [ollama-airgap-linux.md](ollama-airgap-linux.md)
- Windows 서버: [ollama-airgap-windows.md](ollama-airgap-windows.md)

## 목차
1. [VS Code + Continue 설치](#1-vs-code--continue-설치)
2. [Continue 설정 (서버 연결)](#2-continue-설정-서버-연결)
3. [Continue 모드 이해 (Chat / Edit / Agent)](#3-continue-모드-이해-chat--edit--agent)
4. [자동 수정(Agent) 동작 조건](#4-자동-수정agent-동작-조건)
5. [JDK 설정 (eGov JDK 17)](#5-jdk-설정-egov-jdk-17)
6. [Tomcat 빌드+배포+기동 한 번에](#6-tomcat-빌드배포기동-한-번에)
7. [터미널 한글 깨짐 해결](#7-터미널-한글-깨짐-해결)
8. [문제 해결 (FAQ)](#8-문제-해결-faq)

---

## 1. VS Code + Continue 설치

- **VS Code**: 폐쇄망이면 인터넷 PC에서 설치 파일(.exe)을 받아 반입 후 설치.
- **Continue 확장**: 폐쇄망이면 인터넷 PC에서 `.vsix`를 받아 반입.
  - 받는 곳: Open VSX(`https://open-vsx.org/extension/Continue/continue`) 또는 VS Code Marketplace의 Continue 페이지 → "Download Extension"
  - 설치: 확장 패널(`Ctrl+Shift+X`) → `...` → **Install from VSIX...**

> ⚠️ **Continue는 가능한 최신 버전을 쓰세요.** 구버전은 Ollama에 도구(tools)를 네이티브로 넘기지 않아, Agent 모드에서 `<function=ls>...` 같은 텍스트가 새고 멈출 수 있습니다.

---

## 2. Continue 설정 (서버 연결)

`%USERPROFILE%\.continue\config.yaml` 을 작성합니다. 이 저장소의
[`config/continue-config.yaml`](../config/continue-config.yaml) 을 복사하고 **서버 IP만 수정**하세요.

```yaml
name: 폐쇄망 Ollama
version: 0.0.1
schema: v1

rules:
  - 모든 답변과 설명은 반드시 한국어로 작성하세요.
  - 코드 주석도 한국어로 작성하세요.
  - 절대 중국어로 답변하지 마세요.

models:
  - name: Qwen3 Coder 30B (chat)
    provider: ollama
    model: qwen3-coder:30b
    apiBase: http://192.168.45.214:11434   # ← 서버 IP
    roles: [chat, edit, apply]
    capabilities:
      - tool_use

  - name: Qwen2.5 Coder 1.5B (autocomplete)
    provider: ollama
    model: qwen2.5-coder:1.5b-base
    apiBase: http://192.168.45.214:11434
    roles: [autocomplete]

  - name: Embed
    provider: ollama
    model: nomic-embed-text
    apiBase: http://192.168.45.214:11434
    roles: [embed]
```

저장 후 `Ctrl+Shift+P` → **"Continue: Reload"**.

**연결 확인** (cmd):
```cmd
curl http://192.168.45.214:11434/api/tags
```
모델 목록 JSON이 오면 정상입니다.

> 💡 **한국어로 답하게 하기**: qwen 계열은 중국어가 섞일 수 있습니다. 위 `rules` 가 모든 요청에 시스템 프롬프트로 들어가 한국어 답변을 강제합니다.

---

## 3. Continue 모드 이해 (Chat / Edit / Agent)

채팅창 아래 **모드 드롭다운**에서 선택합니다. 용도에 맞게 쓰는 것이 중요합니다.

| 모드 | 하는 일 | 코드 수정 | 도구 호출 | 비고 |
|------|---------|-----------|-----------|------|
| **Chat** | 질문/설명/분석 | 제안만 (Apply 클릭 시 반영) | 없음 | 가장 안정적 |
| **Edit** (`Ctrl+I`) | 선택 영역 직접 수정 | 즉시 수정 | 없음 | 단일 파일 수정에 최적 |
| **Agent** | 파일 탐색 + 여러 파일 자동 수정 + 명령 실행 | 자동 수정 | 사용 | tool_use 모델 필요 |

- **소스 분석/설명** → **Chat** 모드 + `@파일`/`@폴더`/`@codebase` 로 컨텍스트 첨부
  ```
  @MailAttachment.java 이 클래스 분석해줘
  @src/main/java 이 패키지 구조 설명해줘
  ```
- **특정 코드 수정** → 코드 선택 후 **`Ctrl+I`** (Edit)
- **여러 파일 자동 수정** → **Agent** 모드 (아래 조건 충족 시)

> ❗ Chat 모드에서는 모델이 파일을 스스로 못 뒤집니다. 반드시 `@`로 파일을 첨부하세요.

---

## 4. 자동 수정(Agent) 동작 조건

Agent 모드에서 `<function=ls> ... </tool_call>` 같은 텍스트가 새고 멈춘다면, **도구 호출 파싱**이 안 되는 것입니다. 다음을 점검하세요.

1. **서버 Ollama가 도구 호출을 지원하는지** — 서버에서:
   ```bash
   ollama show qwen3-coder:30b      # Capabilities 에 'tools' 가 있어야 함
   ```
   직접 테스트(로컬 PC, cmd):
   ```cmd
   curl http://192.168.45.214:11434/api/chat -d "{\"model\":\"qwen3-coder:30b\",\"messages\":[{\"role\":\"user\",\"content\":\"list files\"}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"ls\",\"description\":\"list dir\",\"parameters\":{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}}}],\"stream\":false}"
   ```
   응답에 `"tool_calls":[...]` 가 구조화되어 나오면 **서버는 정상**입니다.
2. **서버 정상인데 Continue에서만 깨지면 → Continue 확장이 구버전**입니다. 최신 `.vsix`로 업데이트하세요. (대부분 이 경우)
3. config 의 chat 모델에 `capabilities: [tool_use]` 가 있는지 확인.
4. 모드를 **Agent** 로 선택했는지 확인.

> 정리: **모델·서버(tool_calls 정상) + 최신 Continue + tool_use + Agent 모드** → 자동 수정 동작.
> 자동 수정이 계속 불안정하면, 단일 파일은 **Edit(`Ctrl+I`)** 로 처리하는 것이 빠르고 확실합니다.

---

## 5. JDK 설정 (eGov JDK 17)

eGov 번들 JDK 경로(예: `C:\eGovFrameDev-5.0.0\bin\jdk-17`)를 VS Code에 지정합니다.

`Ctrl+Shift+P` → **"Preferences: Open User Settings (JSON)"** → 아래 추가:

```json
{
    "java.jdt.ls.java.home": "C:\\eGovFrameDev-5.0.0\\bin\\jdk-17",
    "java.configuration.runtimes": [
        {
            "name": "JavaSE-17",
            "path": "C:\\eGovFrameDev-5.0.0\\bin\\jdk-17",
            "default": true
        }
    ]
}
```

- `java.jdt.ls.java.home` = 자바 **언어 서버 구동**용 (JDK 17+ 필수)
- `java.configuration.runtimes` = 프로젝트 **컴파일/실행**용
  → `"Please download and install a JDK ..."` 메시지는 이 항목이 없을 때 발생합니다.
- 경로는 역슬래시 `\\`, 끝에 `\bin` 붙이지 않습니다.

> ⚠️ **JSON 주의**: settings.json 은 최상위 `{ }` 가 **딱 하나**여야 합니다. `{ } { }` 처럼 블록이 둘이면 파일 전체가 깨져 모든 설정이 무시됩니다. 기존 항목 끝에 **쉼표 `,`** 를 붙여 한 객체 안에 합치세요.

적용 후 `Ctrl+Shift+P` → **"Java: Clean Java Language Server Workspace"** → Restart.

> 참고: 최신 Red Hat Java 확장은 **언어 서버 구동에 JDK 21**을 요구하기도 합니다. 로그(출력 패널 → "Language Support for Java")에 `UnsupportedClassVersion`/`requires JDK 21` 이 보이면, JDK 21을 별도로 두고 `java.jdt.ls.java.home` 만 21로 지정하고 `java.configuration.runtimes` 는 17로 유지하세요.

---

## 6. Tomcat 빌드+배포+기동 한 번에

> 폐쇄망에서 VS Code 서버 확장(Community Server Connector/RSP)은 백엔드 런타임을 인터넷에서 받아야 해서
> "unable to contact the rsp server" 등으로 실패하기 쉽습니다. **통합 터미널 + 배치 스크립트**가 가장 안정적입니다.

1. 이 저장소의 [`templates/deploy.bat`](../templates/deploy.bat) 을 **프로젝트 루트(pom.xml 옆)** 에 복사하고, 상단 경로를 본인 환경에 맞게 수정.
2. [`templates/tasks.json`](../templates/tasks.json) 을 **프로젝트의 `.vscode/tasks.json`** 으로 복사.
3. 실행:
   - **빌드+배포+기동**: `Ctrl+Shift+B`
   - **정지**: `Ctrl+Shift+P` → "Tasks: Run Task" → "Tomcat: 정지"
   - 접속: `http://localhost:8080/`

핵심 포인트:

| 항목 | 내용 |
|------|------|
| 컨텍스트 경로 | `deploy.bat` 의 `set "APP=ROOT"` → 루트(`/`). `groupware` 로 바꾸면 `/groupware` |
| 빌드 도구 | Maven 기준. Gradle이면 빌드 명령과 산출물 경로(`build\libs\*.war`)로 변경 |
| pom packaging | `<packaging>war</packaging>` 여야 WAR 생성됨 |
| 로그 | startup이 띄운 별도 콘솔, 또는 `...\apache-tomcat-9.0.117\logs\catalina.*.log` |
| 포트 충돌 | `...\conf\server.xml` 의 `<Connector port="8080" ...>` 변경 |

> 💡 cmd/배치에서 `if (...)` 블록 안 `echo` 에 괄호 `( )` 를 넣으면 `*** was unexpected at this time.` 오류가 납니다. 본 템플릿은 이를 `goto` 라벨로 피했습니다.

---

## 7. 터미널 한글 깨짐 해결

한국어가 한자처럼 깨져 보이는 것은 **중국어가 아니라 인코딩 불일치**입니다.
**VS Code 통합 터미널은 출력 바이트를 UTF-8로 해석**하므로, 코드페이지를 **65001(UTF-8)** 로 맞추는 것이 정답입니다. (옛날 cmd 창 기준의 `chcp 949` 는 VS Code에서 오히려 깨집니다.)

User settings.json 에 추가:

```json
{
    "terminal.integrated.defaultProfile.windows": "Command Prompt",
    "terminal.integrated.profiles.windows": {
        "Command Prompt": {
            "path": "C:\\Windows\\System32\\cmd.exe",
            "args": ["/k", "chcp 65001>nul"]
        }
    }
}
```

적용 후 **새 터미널**을 열고 확인:
```cmd
chcp
```
→ "활성 코드 페이지: 65001" 이면 정상.

그래도 깨지면 Windows 시스템 로캘을 UTF-8로:
제어판 → 국가 또는 지역 → 관리자 옵션 → 시스템 로캘 변경 → **"세계 언어 지원을 위한 Unicode UTF-8 사용(베타)"** 체크 → 재부팅.

---

## 8. 문제 해결 (FAQ)

**Q. Agent에서 `<function=...>` 텍스트만 나오고 멈춰요.**
A. [4번](#4-자동-수정agent-동작-조건) 참고. 대개 Continue 구버전 → 최신 vsix로 업데이트. 분석만 할 거면 Chat 모드 + `@파일`을 쓰세요.

**Q. 답변이 중국어로 나와요.**
A. config 에 `rules`(한국어로 답하기)를 추가하고 Reload 하세요. 터미널 글자가 깨지는 거라면 [7번](#7-터미널-한글-깨짐-해결) 인코딩 문제입니다.

**Q. "Please download and install a JDK ..." 가 계속 떠요.**
A. `java.configuration.runtimes` 가 비었거나 settings.json JSON 이 깨졌을 수 있습니다. [5번](#5-jdk-설정-egov-jdk-17) 참고. cmd 에서 `java -version` 이 되는데 VS Code만 안 되면, VS Code를 작업관리자로 완전 종료 후 재시작하거나 설정으로 경로를 직접 지정하세요.

**Q. Tomcat 서버 확장(RSP)이 "unable to contact the rsp server" 로 실패해요.**
A. 폐쇄망에서 RSP 백엔드 다운로드가 막힌 것입니다. 확장 대신 [6번](#6-tomcat-빌드배포기동-한-번에) 의 터미널/배치 방식을 쓰세요.

**Q. `ps`, `grep`, `lsof` 가 안 돼요.**
A. 리눅스 명령입니다. Windows 대응: 프로세스 `tasklist | findstr java`, 포트 `netstat -ano | findstr :8080`, 종료 `taskkill /f /pid <PID>` (또는 `taskkill /f /im java.exe`).
