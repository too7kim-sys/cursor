package com.egov.ollama.assist;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 채팅 답변에서 "파일이 지정된 코드블록"들을 추출해 멀티파일 변경 목록으로 만든다.
 * 지원 형식: 코드펜스 정보줄의 경로(```java src/Foo.java```), 직전 줄의 "파일:/File: 경로",
 * 굵게/백틱/평문 경로 줄(**src/Foo.java**, `src/Foo.java`, src/Foo.java). SWT 비의존이라 테스트 가능.
 */
public final class ChangeParser {

	public static final class Change {
		public final String path;
		public final String content;

		public Change(String path, String content) {
			this.path = path;
			this.content = content;
		}
	}

	private static final Pattern LABEL = Pattern.compile("(?:파일|File|file)\\s*[:：]\\s*`?([^\\s`]+)`?");

	private ChangeParser() {
	}

	public static List<Change> parse(String text) {
		List<Change> out = new ArrayList<>();
		if (text == null || text.isEmpty()) {
			return out;
		}
		String[] lines = text.split("\n", -1);
		String pendingPath = null;
		int i = 0;
		while (i < lines.length) {
			String trimmed = lines[i].trim();
			if (trimmed.startsWith("```")) {
				String info = trimmed.length() > 3 ? trimmed.substring(3).trim() : "";
				String path = pathFromInfo(info);
				StringBuilder sb = new StringBuilder();
				int j = i + 1;
				while (j < lines.length && !lines[j].trim().startsWith("```")) {
					sb.append(lines[j]).append('\n');
					j++;
				}
				if (path == null) {
					path = pendingPath;
				}
				if (path != null) {
					out.add(new Change(path, stripTrailingNewline(sb.toString())));
				}
				pendingPath = null;
				i = j + 1;
				continue;
			}
			String p = pathFromLine(lines[i]);
			if (p != null) {
				pendingPath = p;
			} else if (!trimmed.isEmpty()) {
				pendingPath = null; // 일반 설명 줄은 보류 경로를 지움(빈 줄은 유지)
			}
			i++;
		}
		return out;
	}

	private static String pathFromInfo(String info) {
		if (info.isEmpty()) {
			return null;
		}
		for (String tok : info.split("\\s+")) {
			if (looksLikePath(tok)) {
				return stripQuotes(tok);
			}
		}
		return null;
	}

	private static String pathFromLine(String line) {
		String t = line.trim();
		Matcher m = LABEL.matcher(t);
		if (m.find()) {
			return stripQuotes(m.group(1));
		}
		String c = t.replaceAll("^[#>*`\\s/]+", "").replaceAll("[*`\\s]+$", "");
		if (!c.isEmpty() && !c.contains(" ") && looksLikePath(c)) {
			return c;
		}
		return null;
	}

	private static boolean looksLikePath(String tok) {
		String t = stripQuotes(tok);
		return t.contains("/") || t.matches(".*\\.[A-Za-z0-9]+");
	}

	private static String stripQuotes(String s) {
		return s.replaceAll("^[`\"']+", "").replaceAll("[`\"']+$", "");
	}

	private static String stripTrailingNewline(String s) {
		int end = s.length();
		while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
			end--;
		}
		return s.substring(0, end);
	}
}
