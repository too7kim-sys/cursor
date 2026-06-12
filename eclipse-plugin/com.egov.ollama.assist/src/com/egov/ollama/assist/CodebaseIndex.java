package com.egov.ollama.assist;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 프로젝트 코드베이스 RAG 색인(임베딩 기반 의미 검색). 순수 JDK 로직이라 단위 테스트가 가능하며,
 * 임베딩 함수는 {@link Embedder} 로 주입한다(실제로는 Ollama, 테스트는 가짜 벡터).
 */
public class CodebaseIndex {

	/** 텍스트 → 벡터 임베딩 함수 */
	public interface Embedder {
		float[] embed(String text) throws Exception;
	}

	/** 색인 단위 청크 */
	static final class Chunk {
		String path;
		int start;
		int end;
		String text;
		float[] vec;
		float norm;
	}

	private static final int WINDOW = 50; // 청크 줄 수
	private static final int STEP = 40; // 이동 간격(겹침 10줄)
	private static final int MAX_CHUNKS = 4000;
	private static final long MAX_FILE = 512_000;
	private static final int EMBED_TEXT_CAP = 2000;

	private final File root;
	private final Embedder embedder;
	private final List<Chunk> chunks = new ArrayList<>();

	public CodebaseIndex(File root, Embedder embedder) {
		this.root = root;
		this.embedder = embedder;
	}

	public int size() {
		return chunks.size();
	}

	/** 프로젝트를 색인한다. 반환: 색인된 청크 수. */
	public int build(BooleanSupplier cancel, Consumer<String> progress) throws Exception {
		chunks.clear();
		Path rootPath = root.getCanonicalFile().toPath();
		List<File> files = new ArrayList<>();
		collect(root.getCanonicalFile(), files);
		int fileNo = 0;
		for (File f : files) {
			if (cancel != null && cancel.getAsBoolean()) {
				break;
			}
			fileNo++;
			if (chunks.size() >= MAX_CHUNKS) {
				if (progress != null) {
					progress.accept("최대 청크 수(" + MAX_CHUNKS + ") 도달 — 색인 중단");
				}
				break;
			}
			String content;
			try {
				content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
			} catch (IOException e) {
				continue;
			}
			if (content.indexOf('\0') >= 0) {
				continue; // 바이너리
			}
			String rel = rootPath.relativize(f.toPath()).toString().replace('\\', '/');
			if (progress != null && fileNo % 10 == 0) {
				progress.accept("색인 중… " + fileNo + "/" + files.size() + " (" + chunks.size() + " 청크)");
			}
			String[] lines = content.split("\n", -1);
			for (int i = 0; i < lines.length; i += STEP) {
				if (cancel != null && cancel.getAsBoolean()) {
					break;
				}
				if (chunks.size() >= MAX_CHUNKS) {
					break;
				}
				int endLine = Math.min(i + WINDOW, lines.length);
				StringBuilder sb = new StringBuilder();
				for (int j = i; j < endLine; j++) {
					sb.append(lines[j]).append('\n');
				}
				String text = sb.toString().trim();
				if (text.isEmpty()) {
					continue;
				}
				String embedText = text.length() > EMBED_TEXT_CAP ? text.substring(0, EMBED_TEXT_CAP) : text;
				float[] vec = embedder.embed(rel + "\n" + embedText);
				Chunk c = new Chunk();
				c.path = rel;
				c.start = i + 1;
				c.end = endLine;
				c.text = text;
				c.vec = vec;
				c.norm = norm(vec);
				chunks.add(c);
				if (endLine >= lines.length) {
					break;
				}
			}
		}
		if (progress != null) {
			progress.accept("색인 완료: " + chunks.size() + " 청크");
		}
		return chunks.size();
	}

	/** 질의와 의미적으로 가까운 상위 k개 청크를 포맷해 반환. */
	public String search(String query, int k) throws Exception {
		if (chunks.isEmpty()) {
			return "색인이 비어 있습니다. 먼저 색인을 생성하세요.";
		}
		float[] qv = embedder.embed(query);
		float qn = norm(qv);
		List<Scored> scored = new ArrayList<>();
		for (int idx = 0; idx < chunks.size(); idx++) {
			Chunk c = chunks.get(idx);
			float sim = cosine(qv, qn, c.vec, c.norm);
			scored.add(new Scored(idx, sim));
		}
		Collections.sort(scored, (a, b) -> Float.compare(b.score, a.score));
		StringBuilder sb = new StringBuilder();
		int n = Math.min(k, scored.size());
		for (int i = 0; i < n; i++) {
			Chunk c = chunks.get(scored.get(i).idx);
			String body = c.text.length() > 800 ? c.text.substring(0, 800) + "\n…" : c.text;
			sb.append("### ").append(c.path).append(":").append(c.start).append("-").append(c.end)
					.append("  (유사도 ").append(String.format("%.3f", scored.get(i).score)).append(")\n");
			sb.append(body).append("\n\n");
		}
		return sb.toString();
	}

	private void collect(File dir, List<File> out) {
		File[] kids = dir.listFiles();
		if (kids == null) {
			return;
		}
		for (File k : kids) {
			String n = k.getName();
			if (n.equals(".git") || n.equals("target") || n.equals("node_modules") || n.equals("bin")
					|| n.equals(".settings") || n.equals(".metadata") || n.equals(".svn")
					|| n.equals(".ollama-assist")) {
				continue;
			}
			if (k.isDirectory()) {
				collect(k, out);
			} else if (k.isFile() && k.length() <= MAX_FILE) {
				out.add(k);
			}
		}
	}

	// ===================== 영속화 =====================

	/** 색인을 JSON 파일로 저장. */
	public void save(File f) throws IOException {
		List<Object> arr = new ArrayList<>();
		for (Chunk c : chunks) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("p", c.path);
			m.put("s", (long) c.start);
			m.put("e", (long) c.end);
			m.put("t", c.text);
			List<Object> v = new ArrayList<>(c.vec.length);
			for (float x : c.vec) {
				v.add((double) x);
			}
			m.put("v", v);
			arr.add(m);
		}
		File parent = f.getParentFile();
		if (parent != null && !parent.exists()) {
			parent.mkdirs();
		}
		Files.write(f.toPath(), Json.write(arr).getBytes(StandardCharsets.UTF_8));
	}

	/** JSON 파일에서 색인을 로드. 성공 시 true. */
	public boolean load(File f) throws IOException {
		if (!f.isFile()) {
			return false;
		}
		Object parsed = Json.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
		if (!(parsed instanceof List)) {
			return false;
		}
		chunks.clear();
		for (Object o : (List<?>) parsed) {
			if (!(o instanceof Map)) {
				continue;
			}
			Map<?, ?> m = (Map<?, ?>) o;
			Chunk c = new Chunk();
			c.path = str(m.get("p"));
			c.start = (int) lng(m.get("s"));
			c.end = (int) lng(m.get("e"));
			c.text = str(m.get("t"));
			Object vo = m.get("v");
			if (vo instanceof List) {
				List<?> vl = (List<?>) vo;
				c.vec = new float[vl.size()];
				for (int i = 0; i < vl.size(); i++) {
					Object x = vl.get(i);
					c.vec[i] = (x instanceof Number) ? ((Number) x).floatValue() : 0f;
				}
			} else {
				c.vec = new float[0];
			}
			c.norm = norm(c.vec);
			chunks.add(c);
		}
		return !chunks.isEmpty();
	}

	// ===================== 수학/헬퍼 =====================

	private static float norm(float[] v) {
		double s = 0;
		for (float x : v) {
			s += (double) x * x;
		}
		return (float) Math.sqrt(s);
	}

	private static float cosine(float[] a, float an, float[] b, float bn) {
		if (an == 0 || bn == 0 || a.length != b.length) {
			return -1f;
		}
		double dot = 0;
		for (int i = 0; i < a.length; i++) {
			dot += (double) a[i] * b[i];
		}
		return (float) (dot / (an * bn));
	}

	private static String str(Object o) {
		return o == null ? "" : o.toString();
	}

	private static long lng(Object o) {
		return (o instanceof Number) ? ((Number) o).longValue() : 0L;
	}

	private static final class Scored {
		final int idx;
		final float score;

		Scored(int idx, float score) {
			this.idx = idx;
			this.score = score;
		}
	}
}
