package com.egov.ollama.assist;

import java.util.List;
import java.util.Map;

/**
 * 에이전트 작업 계획(todo) 렌더링(SWT/Eclipse 비의존, 테스트 가능).
 * 모델이 update_plan 도구로 보낸 단계 목록을 사용자에게 보일 체크리스트 문자열로 만든다.
 * 항목은 문자열이거나 {@code {step|title, status}} 맵일 수 있다.
 * status: done/completed=☑, in_progress/doing=▶, 그 외=☐.
 */
public final class PlanRenderer {

	private PlanRenderer() {
	}

	public static String render(List<?> steps) {
		if (steps == null || steps.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder("\n📋 작업 계획\n");
		for (Object o : steps) {
			String text;
			String status = null;
			if (o instanceof Map) {
				Map<?, ?> m = (Map<?, ?>) o;
				text = str(m.get("step"));
				if (text == null) {
					text = str(m.get("title"));
				}
				if (text == null) {
					text = str(m.get("name"));
				}
				status = str(m.get("status"));
			} else {
				text = String.valueOf(o);
			}
			if (text == null || text.trim().isEmpty()) {
				continue;
			}
			sb.append("  ").append(box(status)).append(' ').append(text.trim()).append('\n');
		}
		return sb.toString();
	}

	private static String box(String status) {
		if (status == null) {
			return "☐";
		}
		String s = status.trim().toLowerCase();
		if (s.equals("done") || s.equals("completed") || s.equals("complete") || s.equals("finished")) {
			return "☑";
		}
		if (s.equals("in_progress") || s.equals("in-progress") || s.equals("doing") || s.equals("active")
				|| s.equals("running")) {
			return "▶";
		}
		return "☐";
	}

	private static String str(Object o) {
		return o == null ? null : o.toString();
	}
}
