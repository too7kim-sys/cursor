package com.egov.ollama.assist;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 소스에서 심볼(클래스/인터페이스/메서드/함수)명을 추출하는 경량 휴리스틱. SWT 비의존이라 테스트 가능.
 * 정확한 파서가 아니라 @심볼 멘션 자동완성/정의 위치 안내용이다.
 */
public final class SymbolIndex {

	private static final Pattern[] PATTERNS = {
			Pattern.compile("\\b(?:class|interface|enum|trait|struct)\\s+([A-Za-z_][A-Za-z0-9_]*)"),
			Pattern.compile("\\b(?:def|fn|func|function)\\s+([A-Za-z_][A-Za-z0-9_]*)"),
			// 자바/유사 언어 메서드: 한정자/반환형 뒤 식별자(
			Pattern.compile(
					"\\b(?:public|private|protected|static|final|synchronized|\\s)+[\\w<>\\[\\],.\\s]+?\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\("),
			// const/let/var name = (자바스크립트류)
			Pattern.compile("\\b(?:const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=") };

	private static final Set<String> KEYWORDS = new LinkedHashSet<>(java.util.Arrays.asList("if", "for", "while",
			"switch", "catch", "return", "new", "synchronized", "super", "this", "else", "do", "try", "function"));

	private SymbolIndex() {
	}

	/** content 에서 심볼명 집합(중복 제거, 등장 순서)을 추출. */
	public static List<String> extractSymbols(String content) {
		Set<String> out = new LinkedHashSet<>();
		if (content == null || content.isEmpty()) {
			return new ArrayList<>(out);
		}
		for (Pattern p : PATTERNS) {
			Matcher m = p.matcher(content);
			while (m.find()) {
				String name = m.group(1);
				if (name != null && name.length() > 1 && !KEYWORDS.contains(name)) {
					out.add(name);
				}
			}
		}
		return new ArrayList<>(out);
	}

	/** symbol 의 정의로 보이는 첫 줄 번호(0-based). 없으면 -1. */
	public static int defLine(String content, String symbol) {
		if (content == null || symbol == null || symbol.isEmpty()) {
			return -1;
		}
		Pattern def = Pattern.compile(
				"\\b(?:class|interface|enum|trait|struct|def|fn|func|function|const|let|var)\\b[^\\n]*\\b"
						+ Pattern.quote(symbol) + "\\b");
		Pattern call = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\s*\\(");
		String[] lines = content.split("\n", -1);
		int firstCall = -1;
		for (int i = 0; i < lines.length; i++) {
			if (def.matcher(lines[i]).find()) {
				return i;
			}
			if (firstCall < 0 && call.matcher(lines[i]).find()) {
				firstCall = i;
			}
		}
		return firstCall;
	}

	/** line(0-based) 주변 radius 줄을 잘라낸 스니펫. */
	public static String snippet(String content, int line, int radius) {
		if (content == null || line < 0) {
			return "";
		}
		String[] lines = content.split("\n", -1);
		int start = Math.max(0, line - radius);
		int end = Math.min(lines.length - 1, line + radius);
		StringBuilder sb = new StringBuilder();
		for (int i = start; i <= end; i++) {
			sb.append(lines[i]);
			if (i < end) {
				sb.append('\n');
			}
		}
		return sb.toString();
	}
}
