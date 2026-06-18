package com.egov.ollama.assist;

/** 고스트 완성 관련 순수 판정 로직. SWT 비의존이라 단위 테스트가 가능하다. */
public final class GhostText {

	private GhostText() {
	}

	/** offset 이 줄 끝인가(뒤에 공백만 있고 줄바꿈/문서끝이면 true) — 줄 중간 오버레이 방지용. */
	public static boolean atLineEnd(String text, int offset) {
		if (text == null || offset < 0 || offset > text.length()) {
			return false;
		}
		for (int i = offset; i < text.length(); i++) {
			char ch = text.charAt(i);
			if (ch == '\n' || ch == '\r') {
				return true;
			}
			if (!Character.isWhitespace(ch)) {
				return false;
			}
		}
		return true; // 문서 끝까지 공백뿐
	}
}
