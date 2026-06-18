package com.egov.ollama.assist;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.texteditor.ITextEditor;

import com.egov.ollama.assist.preferences.PreferenceConstants;
import com.egov.ollama.assist.ui.GhostTextController;

/** 커서 위치에서 FIM 완성을 받아 고스트 텍스트로 표시(수동 단축키 + 자동 트리거 공용). */
public final class GhostCompletions {

	private static final int CONTEXT = 2000;

	private GhostCompletions() {
	}

	public static void trigger(final ITextEditor te) {
		if (te == null) {
			return;
		}
		final IDocument doc = te.getDocumentProvider().getDocument(te.getEditorInput());
		ISelection sel = te.getSelectionProvider().getSelection();
		if (doc == null || !(sel instanceof ITextSelection)) {
			return;
		}
		final int offset = ((ITextSelection) sel).getOffset();
		final String all = doc.get();
		if (offset < 0 || offset > all.length()) {
			return;
		}
		final StyledText st = styledTextOf(te);
		if (st == null) {
			return;
		}
		if (!GhostText.atLineEnd(all, offset)) {
			return; // 줄 끝에서만 표시(줄 중간 오버레이로 인한 혼란 방지)
		}
		final String prefix = all.substring(Math.max(0, offset - CONTEXT), offset);
		final String suffix = all.substring(offset, Math.min(all.length(), offset + CONTEXT));

		IPreferenceStore store = Activator.getDefault().getPreferenceStore();
		final String base = store.getString(PreferenceConstants.P_BASE_URL);
		final String model = store.getString(PreferenceConstants.P_FIM_MODEL);
		double t = 0.1;
		try {
			t = Double.parseDouble(store.getString(PreferenceConstants.P_TEMPERATURE));
		} catch (Exception ignore) {
			// 기본 0.1
		}
		final double temperature = t;
		final Display display = st.getDisplay();

		Job job = new Job("Ollama 고스트 완성") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					String raw = OllamaClient.complete(base, model, prefix, suffix, temperature);
					final String completion = CodeEdit.cleanCode(raw);
					if (completion != null && !completion.isEmpty() && display != null && !display.isDisposed()) {
						// 요청 사이 커서가 그대로일 때만 표시
						display.asyncExec(() -> {
							if (!st.isDisposed() && st.getCaretOffset() == offset) {
								GhostTextController.show(te, st, doc, offset, completion);
							}
						});
					}
				} catch (Exception ex) {
					Activator.logError("고스트 완성 실패", ex);
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(false);
		job.schedule();
	}

	public static StyledText styledTextOf(ITextEditor te) {
		Object c = te.getAdapter(Control.class);
		return (c instanceof StyledText) ? (StyledText) c : null;
	}
}
