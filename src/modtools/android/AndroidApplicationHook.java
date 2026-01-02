package modtools.android;

import arc.backend.android.AndroidApplication;
import arc.util.Log;
import modtools.annotations.asm.Sample;
import modtools.annotations.asm.Sample.SampleForMethod;

import static modtools.android.AndroidInputFix.releaseModifierKeys;
import static modtools.annotations.asm.Sample.SampleTemp._super;

@Sample
public class AndroidApplicationHook {
	@SampleForMethod
	public static void onWindowFocusChanged(AndroidApplication self, boolean hasFocus) {
		Log.info("AndroidApplicationHook.onWindowFocusChanged");
		// 如果失去焦点，强制释放修饰键
		if (!hasFocus) {
			releaseModifierKeys();
		}

		// 调用原方法（非常重要，否则可能导致音频不暂停等副作用）
		_super(self).onWindowFocusChanged(hasFocus);
	}

}
