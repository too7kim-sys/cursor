package com.egov.ollama.assist;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 자동 검증 피드백 정제. Problems/테스트 로그에서 핵심만 추려 모델이 고치기 쉽게 정리한다. */
public final class VerifyReport {

	private static final String[] TEST_MARKERS = { "FAIL", "Failure", "Error", "Exception", "AssertionError",
			"expected", "BUILD FAILURE", "Tests run", "ERROR]", "✗" };

	private VerifyReport() {
	}

	public static String focusErrors(String problems, int max) {
		return focusErrors(problems, max, null);
	}

	/**
	 * problems 에서 "ERROR ..." 줄만 중복 제거해 최대 max 개 정리. 오류가 없으면 null(검증 통과로 간주).
	 * changedHints 가 있으면 그 힌트(파일명 등)를 포함하는 오류를 앞쪽에 정렬한다.
	 */
	public static String focusErrors(String problems, int max, Collection<String> changedHints) {
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
		List<String> ordered = new ArrayList<>();
		if (changedHints != null && !changedHints.isEmpty()) {
			List<String> related = new ArrayList<>();
			List<String> others = new ArrayList<>();
			for (String e : errs) {
				if (containsAny(e, changedHints)) {
					related.add(e);
				} else {
					others.add(e);
				}
			}
			ordered.addAll(related);
			ordered.addAll(others);
		} else {
			ordered.addAll(errs);
		}
		StringBuilder sb = new StringBuilder();
		sb.append("컴파일 오류 ").append(ordered.size()).append("개(경고 제외). 변경한 파일의 오류부터 해결하세요:\n");
		for (int i = 0; i < ordered.size() && i < max; i++) {
			sb.append(ordered.get(i)).append('\n');
		}
		if (ordered.size() > max) {
			sb.append("...(이하 ").append(ordered.size() - max).append("개 생략)\n");
		}
		return sb.toString();
	}

	/** 빌드/테스트 출력에서 실패/예외 관련 줄만 최대 max 개 추출. 없으면 null(원본 사용). */
	public static String focusTestLog(String output, int max) {
		if (output == null) {
			return null;
		}
		List<String> keep = new ArrayList<>();
		for (String l : output.split("\n")) {
			for (String mk : TEST_MARKERS) {
				if (l.contains(mk)) {
					keep.add(l.trim());
					break;
				}
			}
		}
		if (keep.isEmpty()) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < keep.size() && i < max; i++) {
			sb.append(keep.get(i)).append('\n');
		}
		if (keep.size() > max) {
			sb.append("...(이하 ").append(keep.size() - max).append("줄 생략)\n");
		}
		return sb.toString();
	}

	private static boolean containsAny(String line, Collection<String> hints) {
		for (String h : hints) {
			if (h != null && !h.isEmpty() && line.contains(h)) {
				return true;
			}
		}
		return false;
	}
}
