package com.egov.ollama.assist;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 문제(Problems) 마커 요약을 만드는 순수 로직. 줄 번호 오름차순 정렬 + 중복 제거 후 사람이 읽을 형태로
 * 합친다. SWT/Eclipse 비의존이라 단위 테스트가 가능하다.
 */
public final class Problems {

	public static final class Item {
		public final int line;
		public final String kind;
		public final String message;

		public Item(int line, String kind, String message) {
			this.line = line;
			this.kind = kind == null ? "" : kind;
			this.message = message == null ? "" : message;
		}
	}

	private Problems() {
	}

	/** 줄 오름차순 정렬·중복 제거 후 "줄 N [종류] 메시지" 형태로 합친 문자열. */
	public static String format(List<Item> items) {
		if (items == null || items.isEmpty()) {
			return "";
		}
		List<Item> sorted = new ArrayList<>(items);
		sorted.sort((a, b) -> Integer.compare(a.line, b.line));
		Set<String> seen = new LinkedHashSet<>();
		for (Item it : sorted) {
			seen.add("줄 " + it.line + " [" + it.kind + "] " + it.message);
		}
		return String.join("\n", seen);
	}

	public static int errorCount(List<Item> items) {
		int n = 0;
		if (items != null) {
			for (Item it : items) {
				if ("오류".equals(it.kind)) {
					n++;
				}
			}
		}
		return n;
	}
}
