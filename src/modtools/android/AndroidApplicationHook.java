package modtools.android;

import arc.backend.android.AndroidApplication;
import arc.input.KeyCode;
import arc.struct.IntSet;
import arc.util.*;
import modtools.annotations.asm.Sample;
import modtools.annotations.asm.Sample.SampleForMethod;
import modtools.ui.control.HopeInput;

import static modtools.annotations.asm.Sample.SampleTemp._super;

/** @see AndroidApplication  */
@Sample
public class AndroidApplicationHook {
	static IntSet toRemove = IntSet.with(
	 KeyCode.altLeft.ordinal(), KeyCode.altRight.ordinal(),
	 KeyCode.controlLeft.ordinal(), KeyCode.controlRight.ordinal(),
	 KeyCode.shiftLeft.ordinal(), KeyCode.shiftRight.ordinal()); /* 功能键 */
	@SampleForMethod
	public static void onWindowFocusChanged(AndroidApplication self, boolean hasFocus) {
		Log.info("AndroidApplicationHook.onWindowFocusChanged");

		Time.runTask(0.2f, () -> {
			toRemove.each(key -> {
				HopeInput.justPressed.remove(key);
				HopeInput.pressed.remove(key);
			});
			// Log.info("alt: " + Core.input.alt());
		});
		// 调用原方法（非常重要，否则可能导致音频不暂停等副作用）
		_super(self).onWindowFocusChanged(hasFocus);
	}

}
