package com.egov.ollama.assist;

import java.util.HashMap;
import java.util.Map;

/**
 * 동일 도구 호출 반복(루프) 감지(SWT 비의존, 테스트 가능). 약한 모델이 같은 도구를 같은 인자로
 * 끝없이 호출하는 낭비를 막기 위해, 호출 서명별 누적 횟수를 센다.
 */
public final class RepeatTracker {

	private final Map<String, Integer> counts = new HashMap<>();

	/** 호출 서명(도구명 + 정규화 인자). null 안전. */
	public static String signature(String tool, String argsBrief) {
		return (tool == null ? "" : tool) + "|" + (argsBrief == null ? "" : argsBrief.trim());
	}

	/** 서명을 기록하고 누적 횟수를 반환(첫 호출이면 1). */
	public int record(String signature) {
		int c = counts.getOrDefault(signature, 0) + 1;
		counts.put(signature, c);
		return c;
	}

	/** 지금까지의 누적 횟수(기록하지 않고 조회). */
	public int count(String signature) {
		return counts.getOrDefault(signature, 0);
	}
}
