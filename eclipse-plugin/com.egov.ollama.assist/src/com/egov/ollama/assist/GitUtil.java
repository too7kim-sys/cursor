package com.egov.ollama.assist;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 간단한 git 연동(커밋 메시지 생성용 diff 조회). 순수 JDK(ProcessBuilder).
 */
public final class GitUtil {

	private static final int MAX = 20000;

	private GitUtil() {
	}

	/** 스테이징된 변경(git diff --cached)을 반환. 없으면 작업트리 변경(git diff). 둘 다 없으면 빈 문자열. */
	public static String diff(File root) {
		String staged = run(root, "git", "diff", "--cached");
		if (staged != null && !staged.trim().isEmpty()) {
			return staged;
		}
		String unstaged = run(root, "git", "diff");
		return unstaged == null ? "" : unstaged;
	}

	private static String run(File root, String... cmd) {
		try {
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.directory(root);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			StringBuilder out = new StringBuilder();
			try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				String l;
				while ((l = br.readLine()) != null) {
					out.append(l).append('\n');
					if (out.length() > MAX) {
						out.append("...(생략)\n");
						break;
					}
				}
			}
			if (!p.waitFor(30, TimeUnit.SECONDS)) {
				p.destroyForcibly();
			}
			return out.toString();
		} catch (Exception e) {
			return null;
		}
	}
}
