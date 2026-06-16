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
 * 편집기에서 선택한 코드를 Ollama Assist 뷰 입력창에 코드블록으로 채워 넣는다.
 * (단축키 바인딩: Ctrl+Alt+A) 사용자가 지시문을 덧붙여 채팅/Agent 로 보낼 수 있다.
 */
public class SendToViewHandler extends AbstractHandler {

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
			view.prefillFromEditor(code, lang);
		} catch (PartInitException e) {
			throw new ExecutionException("Ollama Assist 뷰를 열 수 없습니다.", e);
		}
		return null;
	}
}
