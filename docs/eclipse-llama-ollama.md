# Eclipse(EclipseLlama)에서 폐쇄망 Ollama 연결 가이드

eGov 등 Eclipse 기반 IDE에서 **EclipseLlama** 플러그인으로 폐쇄망 Ollama 서버에 연결해
AI 코딩 보조(채팅·코드 설명·리팩터링)를 사용하는 방법입니다.

> 서버 구축이 먼저입니다 → [Linux 서버](ollama-airgap-linux.md) / [Windows 서버](ollama-airgap-windows.md)
> 자동완성·Agent 자동수정·@codebase 검색까지 풀로 쓰려면 VS Code + Continue 가 우위입니다 →
> [local-dev-setup-windows.md](local-dev-setup-windows.md)

## 목차
1. [EclipseLlama란 / 가능한 것](#1-eclipsellama란--가능한-것)
2. [폐쇄망 오프라인 설치](#2-폐쇄망-오프라인-설치)
3. [Ollama 서버 연결 설정](#3-ollama-서버-연결-설정)
4. [EclipseLlama 창(뷰) 열기](#4-eclipsellama-창뷰-열기)
5. [사용법](#5-사용법)
6. [문제 해결 (FAQ)](#6-문제-해결-faq)

---

## 1. EclipseLlama란 / 가능한 것

EclipseLlama는 Eclipse에서 **Ollama API에 직접 연결**되는 AI 코딩 보조 플러그인입니다.
OpenAI 호환 변환 없이 Ollama 네이티브 API(`http://서버:11434`)를 그대로 사용하므로 폐쇄망에 적합합니다.

| 기능 | Eclipse + EclipseLlama | 참고: VS Code + Continue |
|------|------------------------|--------------------------|
| AI 채팅 / 코드 설명 | ✅ | ✅ |
| 선택 영역 리팩터링/생성 | ✅ (버전에 따라) | ✅ |
| 인라인 자동완성(FIM) | ⚠️ 제한적/미지원 | ✅ |
| 여러 파일 자동수정(Agent) | ❌ | ✅ |
| @codebase 임베딩 검색 | ❌ | ✅ |

> Eclipse에서는 **채팅·설명·리팩터링 위주**로 사용하게 됩니다.

---

## 2. 폐쇄망 오프라인 설치

EclipseLlama는 기본적으로 마켓플레이스 온라인 설치라, 폐쇄망에서는 오프라인 반입이 필요합니다.

1. **인터넷 PC**에서 EclipseLlama의 **업데이트 사이트(zip)** 또는 `.jar` 확보
   - 마켓플레이스 페이지 / GitHub 릴리스에서 update-site 아카이브 다운로드
2. zip을 USB로 폐쇄망 반입
3. Eclipse → **Help → Install New Software → Add → Archive...** → 받은 zip 선택
   → 항목 체크 → Next/Finish → Eclipse 재시작
   - (대안) `.jar`을 Eclipse 설치 폴더의 **`dropins/`** 에 넣고 재시작

> ⚠️ 설치 중 `requires ... not found` 가 나오면 **의존 플러그인**이 빠진 것입니다.
> 의존성까지 포함된 update-site zip을 받아 함께 반입하세요.

설치 확인: **Help → About Eclipse → Installation Details → Installed Software** 목록에 EclipseLlama가 있는지 확인.

---

## 3. Ollama 서버 연결 설정

**Window → Preferences** → `Llama` 또는 `Ollama` 검색 → 설정 화면에서:

| 항목 | 값 |
|------|-----|
| Ollama 서버 URL / Host | `http://192.168.45.214:11434` (← 서버 IP) |
| 모델 (Model) | `qwen3-coder:30b` (서버 `ollama list` 와 글자 단위로 일치) |
| (있으면) 임베딩 모델 | `nomic-embed-text` |

> 네이티브 Ollama API이므로 URL은 `/v1` 없이 **`http://서버IP:11434`** 입니다.

**연결 전 점검** (Eclipse가 있는 PC, cmd):
```cmd
curl http://192.168.45.214:11434/api/tags
```
모델 목록 JSON이 오면 네트워크 정상입니다.

---

## 4. EclipseLlama 창(뷰) 열기

1. 상단 메뉴 **Window → Show View → Other...** (단축키 `Alt+Shift+Q`, `Q`)
2. 검색창에 **`Llama`** (또는 `EclipseLlama`, `Ollama`) 입력
3. 나오는 뷰를 선택 → **Open** → 보통 우측/하단에 채팅 패널이 열립니다.

검색해도 안 나오면:
- 플러그인 미설치/비활성 → [2번](#2-폐쇄망-오프라인-설치) 으로 설치 확인 후 Eclipse 재시작
- **Window → Show View → Error Log** 에서 플러그인 로딩 에러 확인
- 툴바 아이콘이나 편집기 **우클릭 메뉴**에도 진입점이 있을 수 있습니다.

---

## 5. 사용법

- **채팅**: 뷰에 질문 입력 → 응답 확인
- **코드 설명/리팩터링**: 편집기에서 코드 선택 → 우클릭 메뉴 또는 뷰에서 요청
- **한국어 답변**: Eclipse 플러그인은 Continue의 전역 `rules` 같은 기능이 없을 수 있으니,
  프롬프트에 **"한국어로 답해줘"** 를 붙이면 확실합니다. (qwen 계열은 중국어가 섞일 수 있음)

---

## 6. 문제 해결 (FAQ)

**Q. 설치 시 "requires X not found".**
A. 의존 플러그인이 빠진 것. 의존성 포함 update-site zip을 반입해 함께 설치하세요.

**Q. 서버 연결이 안 됩니다.**
A. ① 서버 `OLLAMA_HOST=0.0.0.0:11434`, ② 방화벽 11434(내부망 허용), ③ `curl http://서버IP:11434/api/tags` 순으로 확인.

**Q. 모델을 못 찾습니다.**
A. 모델명을 서버 `ollama list` 의 태그와 **글자 하나까지** 동일하게 입력하세요.

**Q. 답변이 중국어로 나옵니다.**
A. 프롬프트에 "한국어로 답해줘"를 추가하세요. (전역 시스템 프롬프트 설정이 있으면 거기에 넣어도 됩니다.)

**Q. 자동완성/자동수정이 안 됩니다.**
A. EclipseLlama는 채팅·설명·리팩터링 중심입니다. 자동완성(FIM)·Agent 자동수정·@codebase 검색이 필요하면 VS Code + Continue 를 병행하세요([가이드](local-dev-setup-windows.md)).

**Q. Eclipse가 JDK로 안 뜹니다.**
A. `eclipse.ini` 의 `-vm` 항목에 JDK 17 경로(`C:\eGovFrameDev-5.0.0\bin\jdk-17\bin`)를 지정하세요.

---

## 참고 링크
> 폐쇄망에서는 접속이 안 될 수 있습니다. 인터넷 PC에서 참고하세요.

- Ollama 공식: https://ollama.com
- Ollama 모델 라이브러리: https://ollama.com/library
- Eclipse 플러그인 오프라인 설치(Install New Software): Eclipse 공식 문서 참고
