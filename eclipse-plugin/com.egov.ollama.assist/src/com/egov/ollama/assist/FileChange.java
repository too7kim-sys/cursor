package com.egov.ollama.assist;

/**
 * 한 파일의 변경 기록(불변 값 타입). 기존의 {@code String[]{path,before,after}} 위치 인덱스 접근을
 * 대체한다. SWT/Eclipse 비의존이라 테스트 가능.
 */
public final class FileChange {

	public final String path;
	public final String before;
	public final String after;
	/** 변경 전에 파일이 존재했는지. false 면 새로 생성된 파일 → 되돌리면 삭제해야 한다. */
	public final boolean existedBefore;

	public FileChange(String path, String before, String after) {
		this(path, before, after, true);
	}

	public FileChange(String path, String before, String after, boolean existedBefore) {
		this.path = path == null ? "" : path;
		this.before = before == null ? "" : before;
		this.after = after == null ? "" : after;
		this.existedBefore = existedBefore;
	}

	/** 새로 생성된 파일 변경(되돌리면 삭제 대상). before 는 없음(""). */
	public static FileChange created(String path, String after) {
		return new FileChange(path, "", after, false);
	}

	/** 변경 후 내용(= after). 적용/표시 시 의미를 명확히 하기 위한 별칭. */
	public String content() {
		return after;
	}
}
