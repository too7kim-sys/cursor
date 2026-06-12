package com.egov.ollama.assist;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

/**
 * 플러그인 생명주기 + 설정 저장소(PreferenceStore) 제공.
 */
public class Activator extends AbstractUIPlugin {

	public static final String PLUGIN_ID = "com.egov.ollama.assist";

	private static Activator plugin;

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		plugin = null;
		super.stop(context);
	}

	public static Activator getDefault() {
		return plugin;
	}
}
