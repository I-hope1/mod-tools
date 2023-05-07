package test0;

import arc.util.Log;

public class Private {
	private Private() {
		app();
	}
	void app() {
		Log.info("this is 'a'");

	}
	public static class Inner extends Private {
		void app() {
			Log.info("hhhh Inner");
		}
	}
}
