# eGov Ollama Assist — 수동 설치 / 삭제 가이드

플러그인 jar 를 Eclipse 에 **수동으로 설치하고 삭제**하는 방법입니다. 폐쇄망에 적합합니다.

> 먼저 jar 가 있어야 합니다. 없으면 [jar 만들기](#0-jar-만들기) 를 보세요.
> 자동 스크립트: [`install.bat`](install.bat) / [`uninstall.bat`](uninstall.bat)

---

## 0. jar 만들기 (최초 1회, 개발 PC)

PDE 가 있는 Eclipse 에서:
1. 프로젝트 임포트: File > Import > General > **Existing Projects into Workspace** → `com.egov.ollama.assist`
2. 프로젝트 우클릭 → **Export > Plug-in Development > Deployable plug-ins and fragments**
3. 대상 폴더 지정 → Finish
4. `<대상폴더>/plugins/com.egov.ollama.assist_0.1.0.jar` 생성됨

이 jar 하나만 있으면 어느 폐쇄망 PC에도 복사해 설치할 수 있습니다.

---

## 1. 설치 (dropins 방식 — 가장 간단, 권장)

1. Eclipse 를 **완전히 종료**
2. 플러그인 jar 를 Eclipse 설치 폴더의 **`dropins`** 폴더에 복사
   ```
   <Eclipse설치폴더>\dropins\com.egov.ollama.assist_0.1.0.jar
   ```
   - eGov 예: `C:\eGovFrameDev-5.0.0\eclipse\dropins\` (실제 eclipse.exe 가 있는 폴더 하위)
3. Eclipse 실행 → Window > Show View > Other > **Ollama Assist** 로 확인

> dropins 는 Eclipse 가 시작 시 자동 인식합니다. 별도 설치 절차가 필요 없습니다.

### 대안: Install New Software (GUI)
1. Help → **Install New Software** → **Add** → **Archive...** → (update-site zip 인 경우) 선택
2. 항목 체크 → Next/Finish → 재시작
   - 단일 jar 만 있으면 dropins 방식이 더 간단합니다.

---

## 2. 삭제 (제거)

### dropins 로 설치한 경우
1. Eclipse 완전 종료
2. `dropins` 폴더에서 해당 jar 삭제
   ```
   del "<Eclipse설치폴더>\dropins\com.egov.ollama.assist_*.jar"
   ```
3. Eclipse 재시작

### Install New Software 로 설치한 경우
1. Help → **About Eclipse** → **Installation Details**
2. **Installed Software** 탭에서 "eGov Ollama Assist" 선택 → **Uninstall...** → 재시작

### 깔끔히 지워지지 않을 때 (캐시 정리)
플러그인이 캐시에 남아 계속 보이면 **`-clean`** 옵션으로 1회 실행:
```cmd
<Eclipse설치폴더>\eclipse.exe -clean
```
또는 `eclipse.ini` 의 `osgi` 캐시(`configuration/org.eclipse.osgi`)를 지운 뒤 재시작.

---

## 3. 업데이트 (새 버전 교체)

1. Eclipse 종료
2. dropins 의 기존 jar 삭제 → 새 버전 jar 복사
3. `eclipse.exe -clean` 으로 1회 실행(캐시 갱신)

> 버전 번호(`_0.1.0`)가 바뀌면 `-clean` 없이도 인식되지만, 같은 버전을 덮어쓸 땐 `-clean` 권장.

---

## 4. 설치 확인 / 문제 해결

| 확인 | 방법 |
|------|------|
| 설치됨? | Help > About Eclipse > Installation Details, 또는 Window > Show View > Other 에 "Ollama Assist" |
| 뷰가 안 보임 | Eclipse `-clean` 재시작, Error Log 뷰 확인 |
| JDK 오류로 안 뜸 | `eclipse.ini` 의 `-vm` 에 JDK 17 경로 지정 |
| 설치했는데 메뉴 없음 | dropins 위치가 맞는지(eclipse.exe 와 같은 폴더의 dropins) 확인 |

---

## 5. 자동 스크립트 (Windows)

이 폴더의 스크립트로 복사/삭제를 자동화할 수 있습니다.

```cmd
:: 설치 (jar 를 dropins 로 복사)
install.bat "C:\eGovFrameDev-5.0.0\eclipse"

:: 삭제 (dropins 에서 제거)
uninstall.bat "C:\eGovFrameDev-5.0.0\eclipse"
```
인자를 생략하면 스크립트 안의 기본 ECLIPSE_HOME 을 사용합니다. 실행 후 Eclipse 를 재시작하세요.
