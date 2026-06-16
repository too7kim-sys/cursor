package com.egov.ollama.assist;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Ollama 네이티브 API(/api/chat) 호출 클라이언트.
 * 스트리밍(stream=true) 응답을 한 줄씩 읽어 델타 텍스트를 콜백으로 전달한다.
 * JDK의 HttpURLConnection 만 사용해 외부 의존성이 없다.
 */
public class OllamaClient {

	/** 스트리밍 델타 수신 콜백 */
	public interface ChunkConsumer {
		void onText(String delta);
	}

	/**
	 * 임베딩 1건 요청(/api/embeddings). 실패 시 IOException.
	 */
	public static float[] embed(String baseUrl, String model, String text) throws IOException {
		String body = "{\"model\":" + JsonUtil.quote(model) + ",\"prompt\":" + JsonUtil.quote(text) + "}";
		String resp = post(baseUrl, "/api/embeddings", body);
		Object parsed = Json.parse(resp);
		if (!(parsed instanceof java.util.Map)) {
			throw new IOException("임베딩 응답 형식 오류");
		}
		Object emb = ((java.util.Map<?, ?>) parsed).get("embedding");
		if (!(emb instanceof java.util.List)) {
			throw new IOException("embedding 필드를 찾을 수 없습니다(모델/엔드포인트 확인)");
		}
		java.util.List<?> l = (java.util.List<?>) emb;
		float[] v = new float[l.size()];
		for (int i = 0; i < l.size(); i++) {
			Object n = l.get(i);
			v[i] = (n instanceof Number) ? ((Number) n).floatValue() : 0f;
		}
		return v;
	}

	private OllamaClient() {
	}

	private static String normalize(String u) {
		String s = u == null ? "" : u.trim();
		if (s.endsWith("/")) {
			s = s.substring(0, s.length() - 1);
		}
		return s;
	}

	/**
	 * Fill-In-Middle 자동완성(/api/generate, suffix 사용).
	 * prefix(커서 앞)와 suffix(커서 뒤) 사이에 들어갈 코드를 반환한다.
	 */
	public static String complete(String baseUrl, String model, String prefix, String suffix, double temperature)
			throws IOException {
		String body = "{\"model\":" + JsonUtil.quote(model)
				+ ",\"prompt\":" + JsonUtil.quote(prefix == null ? "" : prefix)
				+ ",\"suffix\":" + JsonUtil.quote(suffix == null ? "" : suffix)
				+ ",\"stream\":false"
				+ ",\"options\":{\"temperature\":" + temperature + ",\"num_predict\":256}}";
		String resp = post(baseUrl, "/api/generate", body);
		Object parsed = Json.parse(resp);
		if (parsed instanceof java.util.Map) {
			Object r = ((java.util.Map<?, ?>) parsed).get("response");
			return r == null ? "" : r.toString();
		}
		return "";
	}

	/** 단순 GET 호출(연결 테스트 등). 응답 본문 반환, 4xx/5xx 는 예외. */
	public static String get(String baseUrl, String path) throws IOException {
		URL url = java.net.URI.create(normalize(baseUrl) + path).toURL();
		HttpURLConnection con = (HttpURLConnection) url.openConnection();
		con.setRequestMethod("GET");
		con.setConnectTimeout(8000);
		con.setReadTimeout(15000);
		int code = con.getResponseCode();
		InputStream in = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
		if (in == null) {
			throw new IOException("HTTP " + code + " (응답 본문 없음)");
		}
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		try (InputStream i2 = in) {
			while ((n = i2.read(buf)) >= 0) {
				bout.write(buf, 0, n);
			}
		}
		String resp = new String(bout.toByteArray(), StandardCharsets.UTF_8);
		if (code >= 400) {
			throw new IOException("HTTP " + code + ": " + resp);
		}
		return resp;
	}

	/**
	 * 일반 POST(비스트리밍) 호출. 응답 본문 전체를 문자열로 반환한다.
	 * Agent 의 도구 호출(/api/chat, stream=false) 등에 사용.
	 */
	public static String post(String baseUrl, String path, String body) throws IOException {
		URL url = java.net.URI.create(normalize(baseUrl) + path).toURL();
		HttpURLConnection con = (HttpURLConnection) url.openConnection();
		con.setRequestMethod("POST");
		con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
		con.setDoOutput(true);
		con.setConnectTimeout(10000);
		con.setReadTimeout(600000);
		try (OutputStream os = con.getOutputStream()) {
			os.write(body.getBytes(StandardCharsets.UTF_8));
		}
		int code = con.getResponseCode();
		InputStream in = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
		if (in == null) {
			throw new IOException("HTTP " + code + " (응답 본문 없음)");
		}
		ByteArrayOutputStream bout = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		try (InputStream i2 = in) {
			while ((n = i2.read(buf)) >= 0) {
				bout.write(buf, 0, n);
			}
		}
		String resp = new String(bout.toByteArray(), StandardCharsets.UTF_8);
		if (code >= 400) {
			throw new IOException("HTTP " + code + ": " + resp);
		}
		return resp;
	}

	/**
	 * @param baseUrl 예: http://192.168.45.214:11434 (끝 슬래시 무관)
	 * @param model   예: qwen3-coder:30b
	 * @param system  시스템 프롬프트(비어 있으면 생략)
	 * @param user    사용자 프롬프트
	 * @param onChunk 응답 델타 콜백
	 */
	public static void chatStream(String baseUrl, String model, String system, String user, double temperature,
			ChunkConsumer onChunk) throws IOException {
		String base = baseUrl == null ? "" : baseUrl.trim();
		if (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		URL url = java.net.URI.create(base + "/api/chat").toURL();

		StringBuilder body = new StringBuilder();
		body.append("{\"model\":").append(JsonUtil.quote(model)).append(",\"messages\":[");
		if (system != null && !system.trim().isEmpty()) {
			body.append("{\"role\":\"system\",\"content\":").append(JsonUtil.quote(system)).append("},");
		}
		body.append("{\"role\":\"user\",\"content\":").append(JsonUtil.quote(user)).append("}],");
		body.append("\"stream\":true,\"options\":{\"temperature\":").append(temperature).append("}}");

		HttpURLConnection con = (HttpURLConnection) url.openConnection();
		con.setRequestMethod("POST");
		con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
		con.setRequestProperty("Accept", "application/x-ndjson");
		con.setDoOutput(true);
		con.setConnectTimeout(10000);
		con.setReadTimeout(600000);

		try (OutputStream os = con.getOutputStream()) {
			os.write(body.toString().getBytes(StandardCharsets.UTF_8));
		}

		int code = con.getResponseCode();
		InputStream in = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
		if (in == null) {
			throw new IOException("HTTP " + code + " (응답 본문 없음)");
		}
		try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			boolean any = false;
			while ((line = br.readLine()) != null) {
				if (line.trim().isEmpty()) {
					continue;
				}
				// 에러 응답: {"error":"..."} — message 가 없는 줄에서만 에러로 판단(오탐 방지)
				if (line.indexOf("\"message\"") < 0) {
					String err = JsonUtil.extractString(line, "error");
					if (err != null) {
						throw new IOException("Ollama 오류: " + err);
					}
				}
				String content = JsonUtil.extractString(line, "content");
				if (content != null && !content.isEmpty()) {
					onChunk.onText(content);
					any = true;
				}
			}
			if (!any && code >= 400) {
				throw new IOException("HTTP " + code);
			}
		}
	}
}
