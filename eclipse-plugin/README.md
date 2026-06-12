# eGov Ollama Assist — Eclipse 플러그인 (Continue 유사 기능)

폐쇄망 Ollama 서버에 직접 연결되는 **Eclipse용 AI 코딩 보조 플러그인**입니다.
Continue(VS Code) 의 핵심 기능 중 Eclipse에서 구현 가능한 부분을 직접 만들었습니다.

- 외부 라이브러리 의존성 **없음** (JDK + Eclipse SDK API만 사용) → 폐쇄망에서 PDE로 그대로 빌드/배포 가능
- Ollama 네이티브 API(`/api/chat`, 스트리밍) 사용

## 제공 기능

| 기능 | 지원 | 설명 |
|------|------|------|
| AI 채팅 (스트리밍) | ✅ | 전용 뷰에서 질문/답변, 실시간 스트리밍 출력 |
| **Agent (파일 자동 탐색·수정)** | ✅ | 뷰의 "Agent 모드" 체크 → AI가 `list_files`/`read_file`/`write_file` 도구로 프로젝트를 직접 수정(수정 전 확인 다이얼로그) |
| 선택 코드 설명 | ✅ | 편집기에서 코드 선택 → 우클릭 → Ollama Assist > 코드 설명 |
| 선택 코드 리팩터링 제안 | ✅ | 우클릭 → Ollama Assist > 리팩터링 제안 |
| 한국어 응답 강제 | ✅ | 시스템 프롬프트 기본 내장(Preferences에서 변경) |
| 서버/모델 설정 | ✅ | Window > Preferences > Ollama Assist |

> **Agent 모드는 도구 호출(tool_use) 지원 모델이 필요합니다.** `qwen3-coder:30b` 처럼 tool 지원 모델을 쓰세요.
> 모델·서버가 `tool_calls` 를 구조화해 반환하는지 확인: `curl http://서버:11434/api/chat -d '{"model":"qwen3-coder:30b","messages":[{"role":"user","content":"list files"}],"tools":[{"type":"function","function":{"name":"ls","parameters":{"type":"object","properties":{}}}}],"stream":false}'`

### Agent 동작 방식
1. 모델에 도구(list_files/read_file/write_file)를 제공하고 사용자 요청 전달
2. 모델이 `tool_calls` 반환 → 플러그인이 **프로젝트 루트 내에서만** 실행 (경로 이탈 차단)
3. `write_file` 은 **확인 다이얼로그**(새 내용 미리보기) 후 적용 → 워크스페이스 자동 새로고침
4. 모델이 더 호출할 도구가 없을 때까지 반복(최대 12회) 후 한국어 요약

### 이번 버전에서 제외 (로드맵)
- 인라인 자동완성(FIM) — Eclipse content-assist 깊은 연동 필요
- 부분 편집(diff/patch) — 현재 write_file 은 파일 전체 덮어쓰기 방식
- @codebase 임베딩 검색 — 인덱서 필요

> 자동완성·Agent·코드베이스 검색까지 필요하면 **VS Code + Continue** 를 병행하세요
> (저장소의 `docs/local-dev-setup-windows.md`).

---

## 프로젝트 구조

```
eclipse-plugin/com.egov.ollama.assist/
├── META-INF/MANIFEST.MF        # OSGi 번들 정의 / 의존성
├── plugin.xml                  # 뷰/명령/핸들러/메뉴/설정 확장점
├── build.properties
├── .project / .classpath       # PDE 프로젝트 메타
└── src/com/egov/ollama/assist/
    ├── Activator.java
    ├── JsonUtil.java           # 의존성 없는 최소 JSON 처리
    ├── OllamaClient.java       # /api/chat 스트리밍 호출
    ├── ui/ChatView.java        # 채팅 뷰(SWT)
    ├── handlers/               # 코드 설명/리팩터링 핸들러
    └── preferences/            # 서버/모델/시스템프롬프트 설정
```

---

## 빌드 & 설치 (폐쇄망, Eclipse PDE)

인터넷 없이 **개발 중인 Eclipse 자체를 타깃 플랫폼**으로 빌드합니다.

### A. 바로 실행해 보기 (개발/테스트)
1. Eclipse(PDE 포함, "Eclipse for RCP/RAP" 또는 eGovFrame IDE)에서
   **File > Import > General > Existing Projects into Workspace** →
   `eclipse-plugin/com.egov.ollama.assist` 선택 → Import
2. 프로젝트 우클릭 → **Run As > Eclipse Application**
   → 새 Eclipse 인스턴스가 뜨고 플러그인이 로드됩니다.

### B. 설치용 jar 로 내보내기 (배포)
1. 프로젝트 우클릭 → **Export > Plug-in Development > Deployable plug-ins and fragments**
2. 대상 폴더 지정 → Finish → `plugins/com.egov.ollama.assist_0.1.0.jar` 생성
3. 이 jar 를 Eclipse 설치 폴더의 **`dropins/`** 에 복사 → Eclipse 재시작
   - 또는 다른 PC에 배포 시에도 `dropins/` 에 넣으면 됩니다(동일 폐쇄망 내 복사).

> 📦 **수동 설치/삭제 상세 + 자동 스크립트**: [INSTALL.md](INSTALL.md), [install.bat](install.bat), [uninstall.bat](uninstall.bat)

> JDK 17 이상이 필요합니다. eGov 환경이면 `eclipse.ini` 의 `-vm` 에
> `C:\eGovFrameDev-5.0.0\bin\jdk-17\bin` 을 지정하세요.

---

## 사용법

1. **서버 설정**: Window > Preferences > **Ollama Assist**
   - Ollama 서버 URL: `http://192.168.45.214:11434` (← 실제 서버 IP)
   - 모델 이름: `qwen3-coder:30b` (서버 `ollama list` 와 일치)
   - 시스템 프롬프트: (기본값에 한국어 응답 지시 포함)
2. **채팅 뷰 열기**: Window > Show View > Other > **Ollama Assist > Ollama Assist**
3. **채팅**: 입력창에 질문 → [보내기] 또는 `Ctrl+Enter`
4. **코드 설명/리팩터링**: 편집기에서 코드 선택 → 우클릭 → **Ollama Assist** 메뉴

---

## 동작 확인 / 문제 해결

- 연결 점검(cmd): `curl http://192.168.45.214:11434/api/tags`
- 응답이 안 오면: 서버 `OLLAMA_HOST=0.0.0.0:11434`, 방화벽 11434, 모델명 일치 확인
- 빌드/임포트 에러: PDE(Plug-in Development Environment)가 설치된 Eclipse인지 확인
- 한글 깨짐(콘솔): 이 플러그인은 SWT 위젯에 출력하므로 콘솔 코드페이지와 무관합니다

---

## 라이선스 / 확장

내부 사용 목적의 예시 구현입니다. 자유롭게 수정해 사용하세요.
스트리밍 콜백(`OllamaClient.ChunkConsumer`)과 핸들러(`AbstractCodeActionHandler`) 구조라
새 기능(테스트 생성, 주석 추가 등)은 핸들러 한 개 추가로 쉽게 확장할 수 있습니다.
