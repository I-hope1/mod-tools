package modtools.utils.ui;

import arc.graphics.Color;
import arc.math.geom.Rect;
import arc.scene.style.TextureRegionDrawable;

import java.util.*;
import java.util.function.Consumer;

public interface TmpVars {
	Color focusColor = Color.cyan.cpy().a(0.4f);

	/* 临时变量 */
	List     tmpList   = new ArrayList<>(1) {
		public void forEach(Consumer<? super Object> action) {
			super.forEach(action);
			clear();
		}
	};
	String[] tmpAmount = new String[1];
	Rect     mr1       = new Rect();
	Color c1 = new Color();

	TextureRegionDrawable trd = new TextureRegionDrawable();
}
