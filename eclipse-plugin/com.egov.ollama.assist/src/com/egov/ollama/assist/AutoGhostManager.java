package com.egov.ollama.assist;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.egov.ollama.assist.preferences.PreferenceConstants;

/**
 * 자동 고스트 완성(실험적). 텍스트 편집기에서 타이핑이 멈추면(디바운스) 자동으로 FIM 제안을 고스트로 띄운다.
 * 환경설정 P_AUTO_GHOST 가 켜져 있을 때만 동작한다. 로컬 모델 지연 때문에 기본은 꺼져 있다.
 */
public class AutoGhostManager implements IStartup {

	private static final int DELAY_MS = 700;
	private static final String HOOKED = "com.egov.ollama.assist.autoGhostHooked";

	private ITextEditor pending;
	private final Runnable fire = () -> {
		ITextEditor te = pending;
		if (te == null || !enabled()) {
			return;
		}
		// 편집기가 이미 닫혔으면(StyledText 폐기) 트리거하지 않고 참조를 해제한다.
		StyledText st = GhostCompletions.styledTextOf(te);
		if (st == null || st.isDisposed()) {
			pending = null;
			return;
		}
		GhostCompletions.trigger(te);
	};

	@Override
	public void earlyStartup() {
		final IWorkbench wb = PlatformUI.getWorkbench();
		wb.getDisplay().asyncExec(() -> {
			for (IWorkbenchWindow w : wb.getWorkbenchWindows()) {
				hook(w);
			}
			wb.addWindowListener(new IWindowListener() {
				@Override
				public void windowOpened(IWorkbenchWindow window) {
					hook(window);
				}

				@Override
				public void windowActivated(IWorkbenchWindow window) {
					// 무시
				}

				@Override
				public void windowDeactivated(IWorkbenchWindow window) {
					// 무시
				}

				@Override
				public void windowClosed(IWorkbenchWindow window) {
					// 무시
				}
			});
		});
	}

	private void hook(IWorkbenchWindow w) {
		if (w == null) {
			return;
		}
		w.getPartService().addPartListener(new IPartListener2() {
			@Override
			public void partActivated(IWorkbenchPartReference ref) {
				attach(ref);
			}

			@Override
			public void partOpened(IWorkbenchPartReference ref) {
				attach(ref);
			}

			@Override
			public void partBroughtToTop(IWorkbenchPartReference ref) {
				// 무시
			}

			@Override
			public void partClosed(IWorkbenchPartReference ref) {
				// 닫힌 편집기를 가리키던 예약을 해제해 stale 참조/누수와 닫힌 편집기 트리거를 막는다.
				if (ref != null && ref.getPart(false) == pending) {
					pending = null;
				}
			}

			@Override
			public void partDeactivated(IWorkbenchPartReference ref) {
				// 무시
			}

			@Override
			public void partHidden(IWorkbenchPartReference ref) {
				// 무시
			}

			@Override
			public void partVisible(IWorkbenchPartReference ref) {
				// 무시
			}

			@Override
			public void partInputChanged(IWorkbenchPartReference ref) {
				// 무시
			}
		});
	}

	private void attach(IWorkbenchPartReference ref) {
		if (ref == null) {
			return;
		}
		IWorkbenchPart part = ref.getPart(false);
		if (!(part instanceof ITextEditor)) {
			return;
		}
		final ITextEditor te = (ITextEditor) part;
		final StyledText st = GhostCompletions.styledTextOf(te);
		if (st == null || st.isDisposed() || Boolean.TRUE.equals(st.getData(HOOKED))) {
			return;
		}
		st.setData(HOOKED, Boolean.TRUE);
		st.addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(KeyEvent e) {
				if (!enabled() || ignorable(e)) {
					return;
				}
				Display d = st.getDisplay();
				d.timerExec(-1, fire); // 이전 예약 취소
				pending = te;
				d.timerExec(DELAY_MS, fire);
			}
		});
	}

	/** 내비게이션/단축키/비인쇄 키는 트리거하지 않는다. */
	private static boolean ignorable(KeyEvent e) {
		if ((e.stateMask & (SWT.CTRL | SWT.ALT | SWT.COMMAND)) != 0) {
			return true;
		}
		switch (e.keyCode) {
		case SWT.ARROW_UP:
		case SWT.ARROW_DOWN:
		case SWT.ARROW_LEFT:
		case SWT.ARROW_RIGHT:
		case SWT.PAGE_UP:
		case SWT.PAGE_DOWN:
		case SWT.HOME:
		case SWT.END:
		case SWT.ESC:
		case SWT.TAB:
		case SWT.SHIFT:
		case SWT.CTRL:
		case SWT.ALT:
			return true;
		default:
			return e.character == 0; // 비인쇄 키
		}
	}

	private static boolean enabled() {
		try {
			IPreferenceStore store = Activator.getDefault().getPreferenceStore();
			return store.getBoolean(PreferenceConstants.P_AUTO_GHOST);
		} catch (Exception e) {
			return false;
		}
	}
}
