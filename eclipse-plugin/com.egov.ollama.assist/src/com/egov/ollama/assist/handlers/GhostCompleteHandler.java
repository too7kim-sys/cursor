package com.egov.ollama.assist.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
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
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

import com.egov.ollama.assist.Activator;
import com.egov.ollama.assist.CodeEdit;
import com.egov.ollama.assist.OllamaClient;
import com.egov.ollama.assist.preferences.PreferenceConstants;
import com.egov.ollama.assist.ui.GhostTextController;

/**
 * 커서 위치에서 AI 완성을 받아 GitHub Copilot 식 고스트 텍스트(회색 미리보기)로 보여준다.
 * Tab 으로 수락, Esc/다른 키로 취소. (단축키: Ctrl+Alt+\\)
 */
public class GhostCompleteHandler extends AbstractHandler {

	private static final int CONTEXT = 2000;

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IEditorPart editor = HandlerUtil.getActiveEditor(event);
		if (!(editor instanceof ITextEditor)) {
			return null;
		}
		final ITextEditor te = (ITextEditor) editor;
		final IDocument doc = te.getDocumentProvider().getDocument(te.getEditorInput());
		ISelection sel = te.getSelectionProvider().getSelection();
		if (doc == null || !(sel instanceof ITextSelection)) {
			return null;
		}
		final int offset = ((ITextSelection) sel).getOffset();
		final String all = doc.get();
		if (offset < 0 || offset > all.length()) {
			return null;
		}
		final StyledText st = styledTextOf(te);
		if (st == null) {
			return null;
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
						display.asyncExec(() -> GhostTextController.show(te, st, doc, offset, completion));
					}
				} catch (Exception ex) {
					Activator.logError("고스트 완성 실패", ex);
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(false);
		job.schedule();
		return null;
	}

	private static StyledText styledTextOf(ITextEditor te) {
		Object c = te.getAdapter(Control.class);
		return (c instanceof StyledText) ? (StyledText) c : null;
	}
}
