package com.egov.ollama.assist;

import java.util.ArrayList;
import java.util.List;

/**
 * 채팅 출력의 경량 마크다운 스캔(코드펜스/굵게/헤더 영역 탐지). 순수 JDK라 단위 테스트가 가능하며,
 * 실제 색/폰트 적용은 UI 계층에서 {@link Span} 목록으로 수행한다.
 */
public final class MarkdownScanner {

	public enum Kind {
		CODE, BOLD, HEADER
	}

	public static final class Span {
		public final int start;
		public final int length;
		public final Kind kind;

		public Span(int start, int length, Kind kind) {
			this.start = start;
			this.length = length;
			this.kind = kind;
		}
	}

	private MarkdownScanner() {
	}

	public static List<Span> scan(String text) {
		List<Span> spans = new ArrayList<>();
		if (text == null || text.isEmpty()) {
			return spans;
		}
		String[] lines = text.split("\n", -1);
		int offset = 0;
		boolean inCode = false;
		int codeStart = -1;
		for (String line : lines) {
			int lineStart = offset;
			int lineEnd = lineStart + line.length();
			String trimmed = line.trim();
			if (trimmed.startsWith("```")) {
				if (!inCode) {
					inCode = true;
					codeStart = lineStart;
				} else {
					inCode = false;
					spans.add(new Span(codeStart, lineEnd - codeStart, Kind.CODE));
				}
			} else if (!inCode) {
				if (line.startsWith("# ") || line.startsWith("## ") || line.startsWith("### ")) {
					spans.add(new Span(lineStart, line.length(), Kind.HEADER));
				} else {
					scanBold(line, lineStart, spans);
				}
			}
			offset = lineEnd + 1; // 줄바꿈 포함
		}
		if (inCode && codeStart >= 0) {
			spans.add(new Span(codeStart, text.length() - codeStart, Kind.CODE)); // 닫히지 않은 코드블록
		}
		return spans;
	}

	private static void scanBold(String line, int base, List<Span> spans) {
		int i = 0;
		while (true) {
			int s = line.indexOf("**", i);
			if (s < 0) {
				return;
			}
			int e = line.indexOf("**", s + 2);
			if (e < 0) {
				return;
			}
			spans.add(new Span(base + s, (e + 2) - s, Kind.BOLD));
			i = e + 2;
		}
	}

	/** 마지막 코드블록의 내부 코드(펜스 줄 제외)를 반환. 없으면 null. */
	public static String lastCodeBlock(String text) {
		if (text == null) {
			return null;
		}
		String[] lines = text.split("\n", -1);
		int lastOpen = -1;
		int lastClose = -1;
		boolean inCode = false;
		int open = -1;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].trim().startsWith("```")) {
				if (!inCode) {
					inCode = true;
					open = i;
				} else {
					inCode = false;
					lastOpen = open;
					lastClose = i;
				}
			}
		}
		if (lastOpen < 0 || lastClose <= lastOpen) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = lastOpen + 1; i < lastClose; i++) {
			sb.append(lines[i]);
			if (i < lastClose - 1) {
				sb.append('\n');
			}
		}
		return sb.toString();
	}
}
