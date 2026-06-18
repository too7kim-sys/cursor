package com.egov.ollama.assist.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

import com.egov.ollama.assist.Activator;
import com.egov.ollama.assist.CodeEdit;
import com.egov.ollama.assist.OllamaClient;
import com.egov.ollama.assist.preferences.PreferenceConstants;

/**
 * 인라인 편집(단축키 Ctrl+I): 편집기에서 선택한 코드(없으면 현재 줄)를 자연어 지시로 수정한다.
 * 모델 응답을 받아 변경 diff 를 미리 보여주고, 확인하면 제자리에서 교체한다. (GitHub Copilot 인라인 채팅에 해당)
 */
public class InlineEditHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IEditorPart editor = HandlerUtil.getActiveEditor(event);
		final Shell shell = HandlerUtil.getActiveShell(event);
		if (!(editor instanceof ITextEditor)) {
			return null;
		}
		final ITextEditor te = (ITextEditor) editor;
		final IDocument doc = te.getDocumentProvider().getDocument(te.getEditorInput());
		ISelection sel = te.getSelectionProvider().getSelection();
		if (doc == null || !(sel instanceof ITextSelection)) {
			return null;
		}
		ITextSelection ts = (ITextSelection) sel;

		// 선택이 있으면 그 범위, 없으면 현재 줄 전체를 대상으로 한다.
		int offset = ts.getOffset();
		int length = ts.getLength();
		try {
			if (length <= 0) {
				IRegion line = doc.getLineInformationOfOffset(offset);
				offset = line.getOffset();
				length = line.getLength();
			}
		} catch (BadLocationException e) {
			return null;
		}
		if (length <= 0) {
			MessageDialog.openInformation(shell, "Ollama 인라인 편집", "수정할 코드를 선택하거나 줄에 커서를 두세요.");
			return null;
		}

		final String original;
		try {
			original = doc.get(offset, length);
		} catch (BadLocationException e) {
			return null;
		}

		InputDialog dlg = new InputDialog(shell, "Ollama 인라인 편집",
				"이 코드를 어떻게 수정할까요? (예: 예외 처리 추가, 변수명 정리, 주석 달기)", "", null);
		if (dlg.open() != Window.OK) {
			return null;
		}
		final String instruction = dlg.getValue();
		if (instruction == null || instruction.trim().isEmpty()) {
			return null;
		}

		String name = te.getEditorInput() != null ? te.getEditorInput().getName() : "";
		final String lang = FixSupport.fileExtension(name == null ? "" : name);
		final int fOffset = offset;
		final int fLength = length;

		IPreferenceStore store = Activator.getDefault().getPreferenceStore();
		final String base = store.getString(PreferenceConstants.P_BASE_URL);
		final String model = store.getString(PreferenceConstants.P_MODEL);
		final double temperature = CodeEdit.parseTemperature(store.getString(PreferenceConstants.P_TEMPERATURE), 0.1);
		final Display display = shell.getDisplay();

		Job job = new Job("Ollama 인라인 편집") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					final StringBuilder sb = new StringBuilder();
					OllamaClient.chatStream(base, model, CodeEdit.editSystemPrompt(),
							CodeEdit.buildEditPrompt(original, instruction, lang), temperature,
							delta -> sb.append(delta));
					final String edited = CodeEdit.cleanCode(sb.toString());
					if (edited.isEmpty()) {
						return Status.OK_STATUS;
					}
					if (display != null && !display.isDisposed()) {
						display.asyncExec(() -> EditorPreview.apply(shell, doc, te, fOffset, fLength, original, edited,
								"인라인 편집"));
					}
				} catch (Exception ex) {
					Activator.logError("인라인 편집 실패", ex);
					if (display != null && !display.isDisposed()) {
						display.asyncExec(() -> MessageDialog.openError(shell, "Ollama 인라인 편집",
								"수정 요청 중 오류: " + ex.getMessage()));
					}
				}
				return Status.OK_STATUS;
			}
		};
		job.setUser(true);
		job.schedule();
		return null;
	}
}
