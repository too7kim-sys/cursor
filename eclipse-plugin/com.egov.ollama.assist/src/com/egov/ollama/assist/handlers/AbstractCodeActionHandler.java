package com.egov.ollama.assist.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

import com.egov.ollama.assist.ui.ChatView;

/**
 * 편집기에서 선택한 코드를 Ollama 채팅 뷰로 보내는 공통 핸들러.
 * 하위 클래스가 지시문(instruction)만 제공한다.
 */
public abstract class AbstractCodeActionHandler extends AbstractHandler {

	/** 선택 코드에 대해 모델에게 줄 한국어 지시문 */
	protected abstract String instruction();

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IEditorPart editor = HandlerUtil.getActiveEditor(event);
		String code = null;
		String lang = "";

		if (editor instanceof ITextEditor) {
			ITextEditor te = (ITextEditor) editor;
			ISelection sel = te.getSelectionProvider().getSelection();
			if (sel instanceof ITextSelection) {
				code = ((ITextSelection) sel).getText();
			}
			IEditorInput in = te.getEditorInput();
			if (in instanceof IFileEditorInput) {
				String name = ((IFileEditorInput) in).getFile().getName();
				int dot = name.lastIndexOf('.');
				if (dot >= 0 && dot < name.length() - 1) {
					lang = name.substring(dot + 1);
				}
			}
		}

		if (code == null || code.trim().isEmpty()) {
			MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "Ollama Assist",
					"먼저 편집기에서 코드를 선택하세요.");
			return null;
		}

		try {
			IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindowChecked(event).getActivePage();
			ChatView view = (ChatView) page.showView(ChatView.ID);
			view.askWithCode(instruction(), code, lang);
		} catch (PartInitException e) {
			throw new ExecutionException("Ollama Assist 뷰를 열 수 없습니다.", e);
		}
		return null;
	}
}
