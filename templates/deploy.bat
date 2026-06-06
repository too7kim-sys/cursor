@echo off
:: ===========================================================================
:: eGov(Maven WAR) 프로젝트 → Tomcat 빌드+배포+기동 한 번에
:: 사용법: VS Code 통합 터미널에서 .\deploy.bat  또는  Ctrl+Shift+B (tasks.json)
:: 이 파일은 프로젝트 루트(pom.xml 옆)에 두세요.
:: ===========================================================================
setlocal
:: VS Code 통합 터미널은 UTF-8 기준으로 렌더링하므로 65001 권장(한글 깨짐 방지)
chcp 65001 >nul

:: ===== 경로 설정 (본인 환경에 맞게 수정) =====
set "JAVA_HOME=C:\eGovFrameDev-5.0.0\bin\jdk-17"
set "CATALINA_HOME=C:\eGovFrameDev-5.0.0\bin\apache-tomcat-9.0.117"
set "MVN=C:\eGovFrameDev-5.0.0\bin\apache-maven\bin\mvn.cmd"
:: 배포 이름: ROOT = http://localhost:8080/  |  예: groupware = /groupware
set "APP=ROOT"
:: ============================================

:: 번들 Maven이 없으면 PATH의 mvn 사용
if not exist "%MVN%" set "MVN=mvn"

echo [1/5] Tomcat 종료(실행중이면)...
call "%CATALINA_HOME%\bin\shutdown.bat" 2>nul
timeout /t 3 /nobreak >nul

echo [2/5] 기존 배포 제거...
del /q "%CATALINA_HOME%\webapps\%APP%.war" 2>nul
rmdir /s /q "%CATALINA_HOME%\webapps\%APP%" 2>nul

echo [3/5] 빌드 (mvn clean package)...
call "%MVN%" -f "%~dp0pom.xml" clean package
if errorlevel 1 goto :buildfail

echo [4/5] WAR 배포...
set "FOUND="
for %%f in ("%~dp0target\*.war") do (
  copy /y "%%f" "%CATALINA_HOME%\webapps\%APP%.war"
  set "FOUND=1"
)
if not defined FOUND goto :nowar

echo [5/5] Tomcat 기동...
call "%CATALINA_HOME%\bin\startup.bat"
echo.
echo ====== 완료! http://localhost:8080/ ======
goto :eof

:buildfail
echo *** 빌드 실패 ***
exit /b 1

:nowar
echo *** target 폴더에 WAR 파일이 없습니다. pom.xml packaging 을 war 로 확인하세요 ***
exit /b 1
