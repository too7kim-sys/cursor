@echo off
:: ===========================================================================
:: eGov Ollama Assist 플러그인 수동 설치 (dropins 복사)
:: 사용법: install.bat ["Eclipse 설치폴더"]
::   예) install.bat "C:\eGovFrameDev-5.0.0\eclipse"
:: 인자 생략 시 아래 기본값 사용. 실행 후 Eclipse 를 재시작하세요.
:: ===========================================================================
setlocal
chcp 65001 >nul

:: 기본 Eclipse 설치 폴더 (환경에 맞게 수정)
set "ECLIPSE_HOME=C:\eGovFrameDev-5.0.0\eclipse"
if not "%~1"=="" set "ECLIPSE_HOME=%~1"

:: 이 배치 파일과 같은 폴더에서 플러그인 jar 찾기
set "JAR="
for %%f in ("%~dp0com.egov.ollama.assist_*.jar") do set "JAR=%%f"
if not defined JAR (
    echo [오류] 플러그인 jar 를 찾을 수 없습니다: %~dp0com.egov.ollama.assist_*.jar
    echo        먼저 Eclipse 에서 Export ^> Deployable plug-ins 로 jar 를 만들어 이 폴더에 두세요.
    exit /b 1
)

if not exist "%ECLIPSE_HOME%\dropins" (
    echo [오류] dropins 폴더가 없습니다: %ECLIPSE_HOME%\dropins
    echo        ECLIPSE_HOME 경로가 맞는지 확인하세요(eclipse.exe 가 있는 폴더).
    exit /b 1
)

echo 설치 대상: %ECLIPSE_HOME%\dropins
echo 복사할 jar: %JAR%
copy /y "%JAR%" "%ECLIPSE_HOME%\dropins\" >nul
if errorlevel 1 (
    echo [오류] 복사 실패. Eclipse 가 실행 중이면 종료 후 다시 시도하세요.
    exit /b 1
)
echo.
echo ====== 설치 완료 ======
echo Eclipse 를 (종료 후) 다시 실행하세요.
echo 확인: Window ^> Show View ^> Other ^> Ollama Assist
endlocal
