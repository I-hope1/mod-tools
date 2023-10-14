package modtools.utils.ui;

import arc.func.Cons;
import arc.math.*;
import arc.scene.style.Drawable;
import arc.scene.ui.ImageButton;
import arc.scene.ui.layout.Table;
import arc.util.Timer;
import arc.util.Timer.Task;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.Tex;
import mindustry.ui.Styles;
import modtools.ui.*;
import modtools.ui.IntUI.Tooltip;

import static mindustry.Vars.mobile;

public class Hover {
	public static <T> ImageButton getImageButton(Cons<T> cons, float size, float imageSize, Table p, Runnable hide,
																							 T item, Drawable icon) {
		ImageButton btn = p.button(Tex.whiteui, HopeStyles.clearNoneTogglei, imageSize, () -> {}).size(size).get();
		IntUI.longPress(btn, 500, b -> {
			if (b) return;
			cons.get(item);
			hide.run();
		});

		if (!mobile) addHover(imageSize, btn);
		// if (!mobile) {
		btn.addListener(new Tooltip(t -> {
			t.background(Tex.pane).add(item instanceof UnlockableContent u ? u.localizedName : "" + item)
			 .right().bottom();
		}));
		// }
		btn.getStyle().imageUp = icon;
		return btn;
	}
	private static void addHover(float imageSize, ImageButton btn) {
		var task = new MyTask(btn, imageSize);
		btn.hovered(() -> {
			task.reverse = false;
			if (!task.isScheduled()) Timer.schedule(task, 0, 0.02f, -1);
		});
		btn.exited(() -> {
			task.reverse = true;
			if (!task.isScheduled()) Timer.schedule(task, 0, 0.02f, -1);
		});
	}
	private static class MyTask extends Task {
		private final ImageButton btn;
		private final float       imageSize;
		private       boolean     reverse;
		private       float       a;
		public MyTask(ImageButton btn, float imageSize) {
			this.btn = btn;
			this.imageSize = imageSize;
			reverse = false;
			a = 0;
		}
		public void run() {
			a += (reverse ? -1 : 1) * 0.1f;
			btn.resizeImage(imageSize + Interp.pow2.apply(0, 5, Mathf.clamp(a)));
			btn.invalidate();
			if (reverse ? a <= 0 : a >= 1) {
				cancel();
			}
		}
	}
}
