package com.egov.ollama.assist.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Problems 퀵픽스(에디터): 현재 줄(또는 선택 범위)에 걸린 컴파일 오류/경고를 모델에게 고치게 한다.
 * (단축키: Ctrl+Alt+.)
 */
public class FixErrorHandler extends AbstractHandler {

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
		IEditorInput in = te.getEditorInput();
		if (doc == null || !(sel instanceof ITextSelection) || !(in instanceof IFileEditorInput)) {
			return null;
		}
		final IFile file = ((IFileEditorInput) in).getFile();
		ITextSelection ts = (ITextSelection) sel;

		int offset = ts.getOffset();
		int length = ts.getLength();
		int startLine;
		int endLine;
		try {
			if (length <= 0) {
				IRegion line = doc.getLineInformationOfOffset(offset);
				offset = line.getOffset();
				length = line.getLength();
				startLine = endLine = doc.getLineOfOffset(offset);
			} else {
				startLine = doc.getLineOfOffset(offset);
				// 선택이 줄 경계(다음 줄 첫 오프셋)에서 끝나면 다음 줄을 포함하지 않도록 보정
				endLine = doc.getLineOfOffset(offset + Math.max(0, length - 1));
			}
		} catch (BadLocationException e) {
			return null;
		}

		String errors = FixSupport.collectMarkers(file, startLine, endLine);
		if (errors.isEmpty()) {
			MessageDialog.openInformation(shell, "Ollama 오류 수정",
					"현재 줄/선택 범위에 컴파일 오류·경고가 없습니다. 오류가 있는 줄에 커서를 두세요.");
			return null;
		}

		final String original;
		try {
			original = doc.get(offset, length);
		} catch (BadLocationException e) {
			return null;
		}
		FixSupport.runFix(shell, doc, te, offset, length, original, errors, FixSupport.fileExtension(file.getName()));
		return null;
	}
}
