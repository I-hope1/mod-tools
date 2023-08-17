package modtools.ui.components.linstener;

import arc.flabel.FLabel;
import arc.graphics.g2d.Draw;
import arc.util.Tmp;

// @WatchClass(groups = {"proj"})
public class MyFLabel extends FLabel {
	public MyFLabel(CharSequence text) {
		super(text);
	}
	public void draw() {
		Tmp.m1.set(Draw.proj());
		Tmp.m2.set(Draw.trans());
		Draw.trans().scale(-2, 1);
		Draw.proj().scale(-1, 1);
		// @WatchVar(group = "proj")
		// float x1 = ElementUtils.getAbsPosCenter(this).x * 2f;
		// Draw.proj().setOrtho(x1, 0, -Core.graphics.getWidth(), Core.graphics.getHeight());
		super.draw();
		Draw.proj(Tmp.m1);
		Draw.trans(Tmp.m2);
	}
}
