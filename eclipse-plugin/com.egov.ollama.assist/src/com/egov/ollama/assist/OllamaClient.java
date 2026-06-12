package com.egov.ollama.assist;

import java.io.BufferedReader;
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

	private OllamaClient() {
	}

	/**
	 * @param baseUrl 예: http://192.168.45.214:11434 (끝 슬래시 무관)
	 * @param model   예: qwen3-coder:30b
	 * @param system  시스템 프롬프트(비어 있으면 생략)
	 * @param user    사용자 프롬프트
	 * @param onChunk 응답 델타 콜백
	 */
	public static void chatStream(String baseUrl, String model, String system, String user, ChunkConsumer onChunk)
			throws IOException {
		String base = baseUrl == null ? "" : baseUrl.trim();
		if (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		URL url = new URL(base + "/api/chat");

		StringBuilder body = new StringBuilder();
		body.append("{\"model\":").append(JsonUtil.quote(model)).append(",\"messages\":[");
		if (system != null && !system.trim().isEmpty()) {
			body.append("{\"role\":\"system\",\"content\":").append(JsonUtil.quote(system)).append("},");
		}
		body.append("{\"role\":\"user\",\"content\":").append(JsonUtil.quote(user)).append("}],");
		body.append("\"stream\":true}");

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
				// 에러 응답: {"error":"..."}
				String err = JsonUtil.extractString(line, "error");
				if (err != null) {
					throw new IOException("Ollama 오류: " + err);
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
