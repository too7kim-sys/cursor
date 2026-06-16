package com.egov.ollama.assist.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

import com.egov.ollama.assist.Activator;
import com.egov.ollama.assist.OllamaClient;
import com.egov.ollama.assist.preferences.PreferenceConstants;

/**
 * 커서 위치에 AI 자동완성(FIM)을 삽입한다(단축키: Ctrl+Alt+Space).
 * Ollama /api/generate 의 suffix(fill-in-middle)를 사용해 커서 앞/뒤 맥락 사이를 채운다.
 */
public class CompleteHandler extends AbstractHandler {

	private static final int CONTEXT = 2000; // 커서 앞/뒤 최대 문자 수

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
		String all = doc.get();
		if (offset < 0 || offset > all.length()) {
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
		final Display display = Display.getCurrent();

		Job job = new Job("Ollama 자동완성") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					final String completion = OllamaClient.complete(base, model, prefix, suffix, temperature);
					if (completion != null && !completion.isEmpty() && display != null && !display.isDisposed()) {
						display.asyncExec(() -> insert(doc, te, offset, completion));
					}
				} catch (Exception ex) {
					Activator.logError("자동완성 실패", ex);
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(false);
		job.schedule();
		return null;
	}

	private static void insert(IDocument doc, ITextEditor te, int offset, String completion) {
		try {
			if (offset <= doc.getLength()) {
				doc.replace(offset, 0, completion);
				te.selectAndReveal(offset + completion.length(), 0);
			}
		} catch (BadLocationException e) {
			Activator.logError("자동완성 삽입 실패", e);
		}
	}
}
