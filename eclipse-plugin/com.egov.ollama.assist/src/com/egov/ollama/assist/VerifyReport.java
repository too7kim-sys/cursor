package com.egov.ollama.assist;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 자동 검증 피드백 정제. Problems 텍스트에서 오류(ERROR)만 추려 모델이 고치기 쉽게 정리한다. */
public final class VerifyReport {

	private VerifyReport() {
	}

	/**
	 * problems 에서 "ERROR ..." 줄만 중복 제거해 최대 max 개 정리. 오류가 없으면 null(검증 통과로 간주).
	 */
	public static String focusErrors(String problems, int max) {
		if (problems == null) {
			return null;
		}
		Set<String> errs = new LinkedHashSet<>();
		for (String l : problems.split("\n")) {
			if (l.startsWith("ERROR ")) {
				errs.add(l);
			}
		}
		if (errs.isEmpty()) {
			return null;
		}
		List<String> list = new ArrayList<>(errs);
		StringBuilder sb = new StringBuilder();
		sb.append("컴파일 오류 ").append(list.size()).append("개(경고 제외). 아래 오류만 해결하도록 코드를 수정하세요:\n");
		for (int i = 0; i < list.size() && i < max; i++) {
			sb.append(list.get(i)).append('\n');
		}
		if (list.size() > max) {
			sb.append("...(이하 ").append(list.size() - max).append("개 생략)\n");
		}
		return sb.toString();
	}
}
