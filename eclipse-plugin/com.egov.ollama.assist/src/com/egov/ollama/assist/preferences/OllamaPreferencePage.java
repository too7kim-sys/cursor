package com.egov.ollama.assist.preferences;

import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.egov.ollama.assist.Activator;

/**
 * Window &gt; Preferences &gt; Ollama Assist 설정 화면.
 */
public class OllamaPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

	public OllamaPreferencePage() {
		super(GRID);
		setPreferenceStore(Activator.getDefault().getPreferenceStore());
		setDescription("폐쇄망 Ollama 서버 연결 설정");
	}

	@Override
	public void createFieldEditors() {
		addField(new StringFieldEditor(PreferenceConstants.P_BASE_URL,
				"Ollama 서버 URL:", getFieldEditorParent()));
		addField(new StringFieldEditor(PreferenceConstants.P_MODEL,
				"모델 이름:", getFieldEditorParent()));
		StringFieldEditor sys = new StringFieldEditor(PreferenceConstants.P_SYSTEM,
				"시스템 프롬프트:", getFieldEditorParent());
		addField(sys);
		addField(new BooleanFieldEditor(PreferenceConstants.P_ENABLE_RUN,
				"Agent 의 명령 실행(run_command) 허용 (주의: 빌드/테스트 등 실제 실행)",
				getFieldEditorParent()));
	}

	@Override
	public void init(IWorkbench workbench) {
		// no-op
	}
}
