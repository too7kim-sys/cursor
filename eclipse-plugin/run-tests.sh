#!/usr/bin/env bash
# 폐쇄망/무의존 단위 테스트 실행(JDK만 필요, JUnit/네트워크 불필요).
# 순수 로직(SWT/Eclipse 비의존)만 검증한다. UI/에디터 코드는 Eclipse PDE 빌드에서 컴파일된다.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/com.egov.ollama.assist/src"
OUT="$(mktemp -d)"
javac -Xlint:all -d "$OUT" \
  "$SRC/com/egov/ollama/assist/JsonUtil.java" \
  "$SRC/com/egov/ollama/assist/Json.java" \
  "$SRC/com/egov/ollama/assist/MarkdownScanner.java" \
  "$SRC/com/egov/ollama/assist/CodeEdit.java" \
  "$SRC/com/egov/ollama/assist/Mentions.java" \
  "$SRC/com/egov/ollama/assist/FileProposals.java" \
  "$SRC/com/egov/ollama/assist/SessionStore.java" \
  "$SRC/com/egov/ollama/assist/SymbolIndex.java" \
  "$SRC/com/egov/ollama/assist/Problems.java" \
  "$SRC/com/egov/ollama/assist/ChangeParser.java"
javac -cp "$OUT" -d "$OUT" "$HERE/tests/TestRunner.java"
java -cp "$OUT" TestRunner
