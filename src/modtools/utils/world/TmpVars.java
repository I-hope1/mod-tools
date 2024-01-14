package modtools.utils.world;

import arc.graphics.Color;
import arc.math.geom.*;

import java.util.*;
import java.util.function.Consumer;

public interface TmpVars {
	Color focusColor = Color.cyan.cpy().a(0.4f);
	/* 临时变量 */
	interface A {
		List tmpList = new ArrayList<>(1) {
			public void forEach(Consumer<? super Object> action) {
				super.forEach(action);
				clear();
			}
		};
	}
	String[] tmpAmount = new String[1];

	Vec2 mouse      = new Vec2();
	Vec2 mouseWorld = new Vec2();
	Rect TMP_RECT   = new Rect();
}
