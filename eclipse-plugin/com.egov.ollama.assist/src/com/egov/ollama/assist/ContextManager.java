package com.egov.ollama.assist;

import java.util.List;
import java.util.Map;

// 메시지 항목은 role/content 를 가진 Map 이다(호출부는 List<Object> 로 보관).

/**
 * 에이전트 대화 컨텍스트 관리(SWT/Eclipse 비의존, 테스트 가능).
 * 로컬 모델의 컨텍스트 한계를 넘지 않도록, 반복이 길어지면 오래된 도구 결과(role=tool)의
 * 본문을 잘라 전체 대화 크기를 억제한다. 최근 도구 결과와 사용자/시스템 메시지는 보존한다.
 */
public final class ContextManager {

	private static final String ELLIPSIS = "\n…(오래된 도구 결과 생략)";

	private ContextManager() {
	}

	/** content 문자 수 합으로 대화 크기를 어림한다. */
	public static int estimateChars(List<?> messages) {
		if (messages == null) {
			return 0;
		}
		int total = 0;
		for (Object o : messages) {
			if (o instanceof Map) {
				Object c = ((Map<?, ?>) o).get("content");
				if (c instanceof String) {
					total += ((String) c).length();
				}
			}
		}
		return total;
	}

	/**
	 * 끝에서부터 {@code keepRecentTool} 개의 도구 결과는 그대로 두고, 그보다 오래된 도구 결과의
	 * 본문을 {@code maxToolChars} 길이로 줄인다.
	 *
	 * @return 줄여서 절약한 총 문자 수
	 */
	@SuppressWarnings("unchecked")
	public static int compactToolOutputs(List<?> messages, int keepRecentTool, int maxToolChars) {
		if (messages == null || maxToolChars < 0) {
			return 0;
		}
		int seen = 0;
		int saved = 0;
		for (int i = messages.size() - 1; i >= 0; i--) {
			Object o = messages.get(i);
			if (!(o instanceof Map)) {
				continue;
			}
			Map<String, Object> m = (Map<String, Object>) o;
			if (!"tool".equals(String.valueOf(m.get("role")))) {
				continue;
			}
			seen++;
			if (seen <= keepRecentTool) {
				continue;
			}
			Object c = m.get("content");
			if (c instanceof String) {
				String s = (String) c;
				if (s.length() > maxToolChars + ELLIPSIS.length()) {
					m.put("content", s.substring(0, maxToolChars) + ELLIPSIS);
					saved += s.length() - maxToolChars - ELLIPSIS.length();
				}
			}
		}
		return saved;
	}

	/**
	 * 1회성 힌트(예: 시작 시 주입한 프로젝트 구조)처럼 매 턴 비용만 차지하는 system 메시지를 줄인다.
	 * {@code prefix} 로 시작하는 system 메시지 본문을 {@code maxChars} 로 자른다. 도구 결과 압축만으로
	 * 예산을 못 맞출 때(고정 오버헤드가 큰 경우) 보조로 사용. 반환: 절약한 문자 수.
	 */
	@SuppressWarnings("unchecked")
	public static int compactStaleHints(List<?> messages, String prefix, int maxChars) {
		if (messages == null || prefix == null || maxChars < 0) {
			return 0;
		}
		int saved = 0;
		for (Object o : messages) {
			if (!(o instanceof Map)) {
				continue;
			}
			Map<String, Object> m = (Map<String, Object>) o;
			if (!"system".equals(String.valueOf(m.get("role")))) {
				continue;
			}
			Object c = m.get("content");
			if (c instanceof String) {
				String s = (String) c;
				if (s.startsWith(prefix) && s.length() > maxChars + ELLIPSIS.length()) {
					m.put("content", s.substring(0, maxChars) + ELLIPSIS);
					saved += s.length() - maxChars - ELLIPSIS.length();
				}
			}
		}
		return saved;
	}
}
