# ============================================================================
# eGov Ollama Assist 플러그인 빌드 스크립트 (PDE Export 없이 jar 생성 + dropins 배포)
#
# eGov Eclipse 의 plugins 폴더를 컴파일 클래스패스로 사용해 javac 로 컴파일하고,
# MANIFEST/plugin.xml 과 함께 jar 로 패키징한 뒤 dropins 에 복사한다.
# Bundle-Version 의 .qualifier 를 타임스탬프로 치환해 매번 고유 버전이 되도록 한다(-clean 불필요).
#
# 사용법 (PowerShell):
#   ./build.ps1                      # 기본 경로 사용
#   ./build.ps1 -EclipseHome "C:\eGovFrameDev-5.0.0\eclipse" -Jdk "C:\eGovFrameDev-5.0.0\bin\jdk-17"
#   ./build.ps1 -NoDeploy            # jar 만 만들고 dropins 복사 안 함
# ============================================================================
param(
    [string]$EclipseHome = "C:\eGovFrameDev-5.0.0\eclipse",
    [string]$Jdk = "C:\eGovFrameDev-5.0.0\bin\jdk-17",
    [switch]$NoDeploy
)

$ErrorActionPreference = "Stop"
$proj = Join-Path $PSScriptRoot "com.egov.ollama.assist"
$src = Join-Path $proj "src"
$bin = Join-Path $proj "bin"
$pluginsDir = Join-Path $EclipseHome "plugins"
$javac = Join-Path $Jdk "bin\javac.exe"
$jar = Join-Path $Jdk "bin\jar.exe"

if (-not (Test-Path $pluginsDir)) { throw "Eclipse plugins 폴더 없음: $pluginsDir (EclipseHome 확인)" }
if (-not (Test-Path $javac))      { throw "javac 없음: $javac (Jdk 확인)" }

Write-Host "[1/5] 출력 폴더 초기화" -ForegroundColor Cyan
if (Test-Path $bin) { Remove-Item $bin -Recurse -Force }
New-Item -ItemType Directory -Path $bin | Out-Null

Write-Host "[2/5] 소스 컴파일 (classpath = Eclipse plugins)" -ForegroundColor Cyan
$sources = Get-ChildItem -Path $src -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$cp = Join-Path $pluginsDir "*"
& $javac -encoding UTF-8 -cp $cp -d $bin $sources
if ($LASTEXITCODE -ne 0) { throw "컴파일 실패" }

Write-Host "[3/5] 버전 타임스탬프 생성 (.qualifier 치환)" -ForegroundColor Cyan
$stamp = Get-Date -Format "yyyyMMddHHmm"
$mfPath = Join-Path $proj "META-INF\MANIFEST.MF"
$tmpMf = Join-Path $env:TEMP "MANIFEST_$stamp.MF"
(Get-Content $mfPath -Raw) -replace "0\.1\.0\.qualifier", "0.1.0.$stamp" | Set-Content -NoNewline -Encoding UTF8 $tmpMf

Write-Host "[4/5] jar 패키징" -ForegroundColor Cyan
$out = Join-Path $proj "out"
if (-not (Test-Path $out)) { New-Item -ItemType Directory -Path $out | Out-Null }
$jarName = "com.egov.ollama.assist_0.1.0.$stamp.jar"
$jarPath = Join-Path $out $jarName
Push-Location $proj
try {
    & $jar cfm $jarPath $tmpMf -C bin . plugin.xml
    if ($LASTEXITCODE -ne 0) { throw "jar 패키징 실패" }
} finally { Pop-Location }
Write-Host "  생성: $jarPath"

if ($NoDeploy) {
    Write-Host "[5/5] 배포 건너뜀(-NoDeploy)" -ForegroundColor Yellow
} else {
    Write-Host "[5/5] dropins 배포" -ForegroundColor Cyan
    $dropins = Join-Path $EclipseHome "dropins"
    if (-not (Test-Path $dropins)) { throw "dropins 폴더 없음: $dropins" }
    Get-ChildItem $dropins -Filter "com.egov.ollama.assist_*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
    Copy-Item $jarPath $dropins
    Write-Host "  배포: $dropins\$jarName"
}

Write-Host "`n완료! Eclipse 를 재시작하세요 (버전이 매번 달라 -clean 불필요)." -ForegroundColor Green
