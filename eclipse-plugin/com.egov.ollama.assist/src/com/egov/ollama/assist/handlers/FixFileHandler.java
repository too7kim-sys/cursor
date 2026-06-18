package com.egov.ollama.assist.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

import com.egov.ollama.assist.Problems;

/**
 * 현재 파일의 모든 컴파일 오류/경고를 한 번에 수정. 파일 전체를 모델에 보내 수정본을 받아
 * 변경을 미리보기 후 적용한다. (큰 파일은 안전을 위해 거부)
 */
public class FixFileHandler extends AbstractHandler {

	private static final int MAX_CHARS = 12000;

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IEditorPart editor = HandlerUtil.getActiveEditor(event);
		final Shell shell = HandlerUtil.getActiveShell(event);
		if (!(editor instanceof ITextEditor)) {
			return null;
		}
		final ITextEditor te = (ITextEditor) editor;
		final IDocument doc = te.getDocumentProvider().getDocument(te.getEditorInput());
		IEditorInput in = te.getEditorInput();
		if (doc == null || !(in instanceof IFileEditorInput)) {
			return null;
		}
		final IFile file = ((IFileEditorInput) in).getFile();

		java.util.List<Problems.Item> items = FixSupport.allProblems(file);
		String errors = Problems.format(items);
		if (errors.isEmpty()) {
			MessageDialog.openInformation(shell, "Ollama 파일 오류 수정", "이 파일에 컴파일 오류·경고가 없습니다.");
			return null;
		}
		final String original = doc.get();
		if (original.length() > MAX_CHARS) {
			MessageDialog.openWarning(shell, "Ollama 파일 오류 수정",
					"파일이 너무 커서(" + original.length() + "자) 전체 일괄 수정을 건너뜁니다.\n"
							+ "오류 줄에 커서를 두고 Ctrl+Alt+. (부분 수정)을 사용하세요.");
			return null;
		}
		if (!MessageDialog.openConfirm(shell, "Ollama 파일 오류 수정",
				"오류/경고 " + items.size() + "건(오류 " + Problems.errorCount(items) + "건)을 한 번에 수정할까요?\n"
						+ "파일 전체가 모델로 전송되며, 변경은 적용 전 미리 확인합니다.")) {
			return null;
		}

		String lang = FixSupport.fileExtension(file.getName());
		// 전체 파일을 [0, length] 영역으로 수정
		FixSupport.runFix(shell, doc, te, 0, doc.getLength(), original, errors, lang);
		return null;
	}
}
