package modtools.utils.world;

import arc.Core;
import arc.graphics.Color;
import arc.math.geom.*;
import arc.scene.style.TextureRegionDrawable;
import mindustry.Vars;

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

	MouseVec mouseVec   = new MouseVec();
	Vec2     mouseWorld = new Vec2();

	Rect                  mr1 = new Rect();
	TextureRegionDrawable trd = new TextureRegionDrawable();

	class MouseVec extends Vec2 {
		public void require() {
			super.set(Core.input.mouse());
			if (Vars.state.isGame()) { mouseWorld.set(Core.camera.unproject(Core.input.mouse())); }
		}
		/* 禁止外部设置 */
		public Vec2 set(Vec2 v) {
			return this;
		}
	}

}
