# Windows 환경에서 코딩 AI 시작하기

Windows에서 AI 코딩 도구(Claude Code, Cursor 등)를 설치하고 설정하는 방법을 단계별로 정리한 가이드입니다. 초보자도 따라 할 수 있도록 사전 준비부터 도구별 설정, 자주 묻는 문제 해결까지 다룹니다.

## 목차

1. [사전 준비](#1-사전-준비)
2. [필수 개발 환경 설치](#2-필수-개발-환경-설치)
3. [코딩 AI 도구별 설치 가이드](#3-코딩-ai-도구별-설치-가이드)
   - [Claude Code (CLI)](#claude-code-cli)
   - [Cursor (에디터)](#cursor-에디터)
   - [GitHub Copilot (VS Code 확장)](#github-copilot-vs-code-확장)
4. [API 키 발급 및 설정](#4-api-키-발급-및-설정)
5. [문제 해결 (FAQ)](#5-문제-해결-faq)
6. [참고 링크](#6-참고-링크)

---

## 1. 사전 준비

코딩 AI 도구를 쓰기 전에 다음을 확인하세요.

| 항목 | 권장 사양 |
|------|-----------|
| OS | Windows 10 (2004 이상) 또는 Windows 11 |
| 메모리 | 8GB 이상 (16GB 권장) |
| 디스크 | 여유 공간 10GB 이상 |
| 네트워크 | 안정적인 인터넷 연결 (AI 도구는 클라우드 API 사용) |

> **팁**: Windows 11 또는 최신 Windows 10에서는 **WSL2(Windows Subsystem for Linux)** 를 쓰면 리눅스 기반 도구를 훨씬 매끄럽게 사용할 수 있습니다. Claude Code처럼 CLI 중심 도구는 WSL2 환경을 강력히 권장합니다. ([WSL2 설치는 아래 참고](#wsl2-선택-권장))

---

## 2. 필수 개발 환경 설치

### 2.1 패키지 관리자: winget

Windows 10/11에는 기본적으로 `winget`(Windows 패키지 관리자)이 설치되어 있습니다. PowerShell을 열고 확인하세요.

```powershell
winget --version
```

버전이 출력되지 않으면 Microsoft Store에서 **앱 설치 관리자(App Installer)** 를 업데이트하세요.

### 2.2 Git 설치

```powershell
winget install --id Git.Git -e --source winget
```

설치 후 PowerShell을 **새로 열어** 확인합니다.

```powershell
git --version
```

설치 직후 사용자 정보를 등록하세요.

```powershell
git config --global user.name "이름"
git config --global user.email "you@example.com"
```

### 2.3 Node.js 설치 (LTS)

많은 코딩 AI CLI 도구가 Node.js 기반입니다.

```powershell
winget install --id OpenJS.NodeJS.LTS -e --source winget
```

확인:

```powershell
node --version
npm --version
```

### 2.4 Python 설치 (선택)

Python 기반 도구나 스크립트를 쓸 경우:

```powershell
winget install --id Python.Python.3.12 -e --source winget
```

확인:

```powershell
python --version
pip --version
```

### 2.5 Windows Terminal (권장)

기본 명령 프롬프트보다 편리한 터미널입니다.

```powershell
winget install --id Microsoft.WindowsTerminal -e --source winget
```

### WSL2 (선택, 권장)

리눅스 기반 워크플로를 선호한다면 관리자 권한 PowerShell에서:

```powershell
wsl --install
```

설치 후 재부팅하면 Ubuntu가 자동으로 설정됩니다. 이후 Node.js, Git 등을 WSL2(Ubuntu) 안에서 다시 설치해 사용하면 됩니다.

---

## 3. 코딩 AI 도구별 설치 가이드

### Claude Code (CLI)

Anthropic의 공식 터미널 기반 코딩 AI입니다.

**설치 (npm 사용):**

```powershell
npm install -g @anthropic-ai/claude-code
```

**실행:**

프로젝트 폴더로 이동한 뒤 실행합니다.

```powershell
cd C:\projects\my-app
claude
```

최초 실행 시 브라우저로 로그인하거나 API 키를 입력하라는 안내가 나옵니다. ([API 키 설정 참고](#4-api-키-발급-및-설정))

> **권장**: Windows에서는 WSL2(Ubuntu) 안에서 Claude Code를 실행하는 것이 가장 안정적입니다. WSL2 터미널에서 동일하게 `npm install -g @anthropic-ai/claude-code` 후 사용하세요.

---

### Cursor (에디터)

AI 기능이 내장된 VS Code 기반 에디터입니다.

1. https://www.cursor.com 에서 Windows용 설치 파일을 내려받습니다.
2. 또는 winget으로 설치:

   ```powershell
   winget install --id Anysphere.Cursor -e --source winget
   ```

3. 설치 후 실행하고, 처음 화면에서 로그인 또는 계정 생성을 진행합니다.
4. `Ctrl + L` 로 AI 채팅, `Ctrl + K` 로 인라인 편집을 사용할 수 있습니다.

> 기존 VS Code 설정/확장/단축키를 가져오는 마법사가 처음 실행 시 제공됩니다.

---

### GitHub Copilot (VS Code 확장)

이미 VS Code를 쓰고 있다면 확장으로 간편하게 추가할 수 있습니다.

1. VS Code 설치(없다면):

   ```powershell
   winget install --id Microsoft.VisualStudioCode -e --source winget
   ```

2. VS Code 실행 → 확장(Extensions, `Ctrl+Shift+X`) → **GitHub Copilot** 검색 후 설치.
3. **GitHub Copilot Chat** 확장도 함께 설치.
4. 설치 후 GitHub 계정으로 로그인하면 활성화됩니다. (Copilot 구독 또는 무료 플랜 필요)

---

## 4. API 키 발급 및 설정

### Anthropic API 키 (Claude Code 용)

1. https://console.anthropic.com 에 가입/로그인합니다.
2. **API Keys** 메뉴에서 새 키를 생성합니다 (`sk-ant-...` 형식).
3. 키를 안전한 곳에 복사해 둡니다. (다시 표시되지 않음)

**환경 변수로 설정 (PowerShell, 영구 적용):**

```powershell
setx ANTHROPIC_API_KEY "sk-ant-여기에-키-입력"
```

설정 후 터미널을 **새로 열어야** 적용됩니다. 확인:

```powershell
echo $env:ANTHROPIC_API_KEY
```

> **보안 주의**: API 키는 비밀번호와 같습니다. 코드나 Git 저장소에 직접 넣지 말고 반드시 환경 변수나 `.env`(gitignore 처리)로 관리하세요.

---

## 5. 문제 해결 (FAQ)

**Q. `winget`을 찾을 수 없다고 나옵니다.**
A. Microsoft Store에서 "앱 설치 관리자(App Installer)"를 설치/업데이트하세요. Windows 업데이트도 최신으로 맞추세요.

**Q. `npm install -g` 실행 시 권한 오류가 납니다.**
A. PowerShell을 **관리자 권한**으로 실행하거나, WSL2(Ubuntu) 환경에서 설치하세요.

**Q. 명령어를 설치했는데 "인식할 수 없는 명령"이라고 나옵니다.**
A. 설치 후 PATH가 갱신되도록 터미널을 완전히 닫고 새로 여세요. 그래도 안 되면 PC를 재부팅하세요.

**Q. PowerShell에서 스크립트 실행이 차단됩니다.**
A. 다음으로 실행 정책을 완화할 수 있습니다(현재 사용자 한정):

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

**Q. 회사 네트워크/프록시 때문에 API 연결이 안 됩니다.**
A. 방화벽/프록시 설정에서 `api.anthropic.com`, `github.com` 등 도메인 허용이 필요합니다. 네트워크 관리자에게 문의하세요.

---

## 6. 참고 링크

- Claude Code 공식 문서: https://docs.claude.com/en/docs/claude-code
- Anthropic Console: https://console.anthropic.com
- Cursor: https://www.cursor.com
- GitHub Copilot: https://github.com/features/copilot
- WSL 공식 문서: https://learn.microsoft.com/windows/wsl/
- winget 문서: https://learn.microsoft.com/windows/package-manager/winget/

---

> 이 문서는 Windows 환경에서 코딩 AI를 처음 시작하는 분들을 위한 가이드입니다. 도구의 버전이나 설치 방법은 시간이 지나면 바뀔 수 있으니, 공식 문서를 함께 확인하세요.
