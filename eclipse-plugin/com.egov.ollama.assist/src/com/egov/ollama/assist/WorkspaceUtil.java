package com.egov.ollama.assist;

import java.io.File;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * Agent 가 작업할 프로젝트 루트(파일시스템 경로)를 찾고, 작업 후 워크스페이스를 새로고침한다.
 * <p>activeProjectDir() 는 UI 스레드에서 호출하세요.
 */
public final class WorkspaceUtil {

	private WorkspaceUtil() {
	}

	/** 활성 편집기의 프로젝트, 없으면 워크스페이스의 첫 열린 프로젝트 위치를 반환(없으면 null). */
	public static File activeProjectDir() {
		try {
			IWorkbenchWindow w = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (w != null && w.getActivePage() != null) {
				IEditorPart ed = w.getActivePage().getActiveEditor();
				if (ed != null && ed.getEditorInput() instanceof IFileEditorInput) {
					IProject p = ((IFileEditorInput) ed.getEditorInput()).getFile().getProject();
					if (p != null && p.getLocation() != null) {
						return p.getLocation().toFile();
					}
				}
			}
		} catch (Exception ignore) {
			// fall through
		}
		try {
			for (IProject p : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
				if (p.isOpen() && p.getLocation() != null) {
					return p.getLocation().toFile();
				}
			}
		} catch (Exception ignore) {
			// none
		}
		return null;
	}

	/** 파일시스템 변경 후 Eclipse가 인식하도록 워크스페이스를 새로고침한다. */
	public static void refresh() {
		try {
			ResourcesPlugin.getWorkspace().getRoot().refreshLocal(IResource.DEPTH_INFINITE, null);
		} catch (Exception ignore) {
			// best-effort
		}
	}
}
