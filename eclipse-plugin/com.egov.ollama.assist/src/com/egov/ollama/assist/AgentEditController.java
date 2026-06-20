package com.egov.ollama.assist;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 에이전트 변경 기록·다단계 되돌리기의 상태/저장 로직(SWT 비의존). UI(ChatView)는 다이얼로그·메시지만 담당하고
 * 히스토리 적재, 프로젝트별 디스크 영속화, 파일 쓰기/되돌리기 계산을 이 컨트롤러에 위임한다. 테스트 가능.
 */
public final class AgentEditController {

	/** record() 결과: 합쳐진 변경 목록과 새 체크포인트 인덱스. */
	public static final class RecordResult {
		public final List<FileChange> distinct;
		public final int index;

		RecordResult(List<FileChange> distinct, int index) {
			this.distinct = distinct;
			this.index = index;
		}
	}

	private final EditHistory history = new EditHistory();
	private final File baseDir;
	private File root;

	public AgentEditController(File baseDir) {
		this.baseDir = baseDir;
	}

	public void setMaxCheckpoints(int n) {
		history.setMaxCheckpoints(n);
	}

	public EditHistory history() {
		return history;
	}

	public File root() {
		return root;
	}

	/** 경로별로 합침: 최초 before 와 최초 existedBefore 유지, 최종 after 갱신, 첫 등장 순서 보존. */
	public static List<FileChange> merge(List<FileChange> raw) {
		LinkedHashMap<String, Object[]> m = new LinkedHashMap<>(); // [before, after, existedBefore]
		if (raw != null) {
			for (FileChange c : raw) {
				if (m.containsKey(c.path)) {
					m.get(c.path)[1] = c.after; // 최종 after 만 갱신(before/existed 는 최초 유지)
				} else {
					m.put(c.path, new Object[] { c.before, c.after, c.existedBefore });
				}
			}
		}
		List<FileChange> out = new ArrayList<>();
		for (Map.Entry<String, Object[]> e : m.entrySet()) {
			Object[] v = e.getValue();
			out.add(new FileChange(e.getKey(), (String) v[0], (String) v[1], (Boolean) v[2]));
		}
		return out;
	}

	/** root 가 바뀌면 해당 프로젝트의 히스토리를 디스크에서 로드(같으면 유지). */
	public void ensure(File root) {
		if (root == null || root.equals(this.root)) {
			return;
		}
		this.root = root;
		load();
	}

	/** 한 번의 에이전트 실행을 체크포인트로 적재(합치고 push, 디스크 저장). */
	public RecordResult record(File root, String label, List<FileChange> raw) {
		ensure(root);
		List<FileChange> distinct = merge(raw);
		history.push(label, distinct);
		save();
		return new RecordResult(distinct, history.size() - 1);
	}

	/** index 체크포인트 시점 이후를 되돌릴 때 각 파일 복원 내용(경로→복원 텍스트). */
	public Map<String, String> restoreFrom(int index) {
		return history.restoreStateFrom(index);
	}

	/** 되돌린 시점 이후 기록 제거 + 저장. */
	public void truncateAndSave(int index) {
		history.truncateTo(index);
		save();
	}

	public File historyFile() {
		if (baseDir == null || root == null) {
			return null;
		}
		String key = Integer.toHexString(root.getAbsolutePath().hashCode());
		return new File(baseDir, "history_" + key + ".json");
	}

	public void load() {
		File f = historyFile();
		try {
			if (f != null && f.isFile()) {
				history.loadJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
			} else {
				history.loadJson("");
			}
		} catch (Exception e) {
			history.loadJson("");
		}
	}

	public void save() {
		File f = historyFile();
		if (f == null) {
			return;
		}
		try {
			Files.write(f.toPath(), history.toJson().getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			// 저장 실패는 무시(상위에서 로깅 가능)
		}
	}

	/** root 내부에만 파일을 쓴다(경로 이탈 차단). 성공 시 true. */
	public static boolean writeFile(File root, String rel, String content) {
		if (root == null) {
			return false;
		}
		try {
			File f = new File(root, rel);
			String rootPath = root.getCanonicalPath();
			String fp = f.getCanonicalPath();
			// "/root" 가 "/root-evil" 의 접두어가 되는 우회를 막기 위해 구분자까지 확인
			if (!fp.equals(rootPath) && !fp.startsWith(rootPath + File.separator)) {
				return false;
			}
			File parent = f.getParentFile();
			if (parent != null) {
				parent.mkdirs();
			}
			Files.write(f.toPath(), content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/** root 내부의 파일을 삭제(경로 이탈 차단). 이미 없으면 성공으로 간주. 되돌리기에서 "생성 취소"용. */
	public static boolean deleteFile(File root, String rel) {
		if (root == null) {
			return false;
		}
		try {
			File f = new File(root, rel);
			String rootPath = root.getCanonicalPath();
			String fp = f.getCanonicalPath();
			if (!fp.equals(rootPath) && !fp.startsWith(rootPath + File.separator)) {
				return false;
			}
			if (!f.exists()) {
				return true;
			}
			return f.delete();
		} catch (Exception e) {
			return false;
		}
	}
}
