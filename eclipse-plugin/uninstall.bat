@echo off
:: ===========================================================================
:: eGov Ollama Assist 플러그인 수동 삭제 (dropins 제거)
:: 사용법: uninstall.bat ["Eclipse 설치폴더"]
::   예) uninstall.bat "C:\eGovFrameDev-5.0.0\eclipse"
:: 인자 생략 시 아래 기본값 사용. 실행 후 Eclipse 를 재시작하세요.
:: ===========================================================================
setlocal
chcp 65001 >nul

set "ECLIPSE_HOME=C:\eGovFrameDev-5.0.0\eclipse"
if not "%~1"=="" set "ECLIPSE_HOME=%~1"

set "TARGET=%ECLIPSE_HOME%\dropins\com.egov.ollama.assist_*.jar"

if not exist %TARGET% (
    echo [안내] dropins 에 설치된 플러그인 jar 가 없습니다: %TARGET%
    echo        Install New Software 로 설치했다면 Help ^> About ^> Installation Details 에서 Uninstall 하세요.
    exit /b 0
)

echo 삭제 대상: %TARGET%
del /q %TARGET%
if errorlevel 1 (
    echo [오류] 삭제 실패. Eclipse 가 실행 중이면 종료 후 다시 시도하세요.
    exit /b 1
)
echo.
echo ====== 삭제 완료 ======
echo 캐시가 남아 계속 보이면 한 번만: "%ECLIPSE_HOME%\eclipse.exe" -clean
echo Eclipse 를 다시 실행하세요.
endlocal
