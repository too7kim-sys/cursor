package com.egov.ollama.assist;

/**
 * 라인 단위 통합(unified) diff 생성(LCS 기반). 외부 의존성 없는 순수 JDK 로직.
 * 변경 전/후를 사용자에게 보여주기 위한 미리보기용이며, 각 줄은 다음 접두어를 가진다.
 * <ul>
 * <li>"  " 변경 없음</li>
 * <li>"- " 삭제(이전에만 있음)</li>
 * <li>"+ " 추가(이후에만 있음)</li>
 * </ul>
 */
public final class TextDiff {

	/** LCS 표 메모리 폭주 방지: 양쪽 줄 수가 이 값을 넘으면 diff 대신 요약을 반환 */
	private static final int MAX_LINES = 1500;

	private TextDiff() {
	}

	public static String unified(String oldText, String newText) {
		String[] a = (oldText == null ? "" : oldText).split("\n", -1);
		String[] b = (newText == null ? "" : newText).split("\n", -1);

		if (a.length > MAX_LINES || b.length > MAX_LINES) {
			String head = newText == null ? "" : newText;
			if (head.length() > 1500) {
				head = head.substring(0, 1500) + "\n...(생략)";
			}
			return "(파일이 커서 diff 생략 — 새 내용 일부)\n" + head;
		}

		int[][] lcs = new int[a.length + 1][b.length + 1];
		for (int i = a.length - 1; i >= 0; i--) {
			for (int j = b.length - 1; j >= 0; j--) {
				lcs[i][j] = a[i].equals(b[j]) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
			}
		}

		StringBuilder sb = new StringBuilder();
		int i = 0;
		int j = 0;
		while (i < a.length && j < b.length) {
			if (a[i].equals(b[j])) {
				sb.append("  ").append(a[i]).append('\n');
				i++;
				j++;
			} else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
				sb.append("- ").append(a[i]).append('\n');
				i++;
			} else {
				sb.append("+ ").append(b[j]).append('\n');
				j++;
			}
		}
		while (i < a.length) {
			sb.append("- ").append(a[i++]).append('\n');
		}
		while (j < b.length) {
			sb.append("+ ").append(b[j++]).append('\n');
		}
		return sb.toString();
	}
}
