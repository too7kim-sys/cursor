package com.egov.ollama.assist.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

import com.egov.ollama.assist.GhostCompletions;

/**
 * 커서 위치에서 AI 완성을 받아 GitHub Copilot 식 고스트 텍스트(회색 미리보기)로 보여준다.
 * Tab 으로 수락, Esc/다른 키로 취소. (단축키: Ctrl+Alt+Space)
 */
public class GhostCompleteHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IEditorPart editor = HandlerUtil.getActiveEditor(event);
		if (editor instanceof ITextEditor) {
			GhostCompletions.trigger((ITextEditor) editor);
		}
		return null;
	}
}
