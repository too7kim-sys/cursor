# 폐쇄망/무의존 단위 테스트 실행(JDK만 필요). 순수 로직만 검증.
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$src  = Join-Path $here "com.egov.ollama.assist\src"
$out  = Join-Path ([System.IO.Path]::GetTempPath()) ("ollama-tests-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $out | Out-Null
$pure = @("JsonUtil","Json","MarkdownScanner","CodeEdit","Mentions","FileProposals","SessionStore","SymbolIndex","Problems","ChangeParser","GhostText","EditMatch","GlobMatcher","TextSearch","ContextManager","PlanRenderer","SymbolReader","LineEdit","RepeatTracker","VerifyReport","EditHistory","FileChange","AgentEditController","OllamaClient","TextDiff","OllamaAgent") |
  ForEach-Object { Join-Path $src "com\egov\ollama\assist\$_.java" }
& javac -Xlint:all -d $out @pure
& javac -cp $out -d $out (Join-Path $here "tests\TestRunner.java")
& java -cp $out TestRunner
