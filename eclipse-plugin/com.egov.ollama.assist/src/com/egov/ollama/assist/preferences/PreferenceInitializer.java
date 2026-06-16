package com.egov.ollama.assist.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import com.egov.ollama.assist.Activator;

/**
 * 기본 설정값. 폐쇄망 서버 IP/모델은 환경에 맞게 Preferences 에서 바꾸세요.
 */
public class PreferenceInitializer extends AbstractPreferenceInitializer {

	@Override
	public void initializeDefaultPreferences() {
		IPreferenceStore store = Activator.getDefault().getPreferenceStore();
		store.setDefault(PreferenceConstants.P_BASE_URL, "http://192.168.45.214:11434");
		store.setDefault(PreferenceConstants.P_MODEL, "qwen3-coder:30b");
		store.setDefault(PreferenceConstants.P_SYSTEM,
				"모든 답변과 설명은 반드시 한국어로 작성하세요. 코드 주석도 한국어로 작성하세요. 절대 중국어로 답변하지 마세요.");
		store.setDefault(PreferenceConstants.P_ENABLE_RUN, false);
		store.setDefault(PreferenceConstants.P_EMBED_MODEL, "nomic-embed-text");
		store.setDefault(PreferenceConstants.P_TEMPERATURE, "0.2");
		store.setDefault(PreferenceConstants.P_VERIFY_CMD, "");
		store.setDefault(PreferenceConstants.P_CHAT_RAG, true);
	}
}
