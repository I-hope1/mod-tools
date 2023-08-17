package test0;

import arc.util.Log;
import modtools.annotations.*;
import test0.Tests.AnnotationInterface;

@WatchClass(groups = {Private.NAME})
public class Private implements AnnotationInterface {
	private Private() {
		app();
	}
	@WatchField(group = Private.NAME)
	public Private self = this;
	void app() {
		@WatchVar(group = Private.NAME)
		int a = 1;
		Log.info("this is 'a'(@)", a);
	}
	public static boolean $condition_test$(Private pri) {
		return true;
	}

	private static final class Inner extends Private {
		void app() {
			Log.info("hhhh Inner");
		}
	}
}
final class FINAL {
	void a() {
		Log.info("this is a");
	}
}
