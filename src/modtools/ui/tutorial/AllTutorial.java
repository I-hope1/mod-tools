package modtools.ui.tutorial;


import arc.func.Boolp;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.FrameBuffer;
import arc.input.KeyCode;
import arc.math.*;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.*;
import arc.util.Timer.Task;
import arc.util.Tmp;
import mindustry.Vars;
import modtools.graphics.MyShaders;
import modtools.ui.IntUI;
import modtools.utils.*;
import modtools.utils.ui.LerpFun;

import static arc.Core.*;
import static modtools.IntVars.mouseVec;
import static modtools.ui.tutorial.AllTutorial.Buffer.*;
import static modtools.utils.Tools.TASKS;

public class AllTutorial {
	public static void focusOn(Element elem, Boolp boolp) {
		IntUI.focusOnElement(elem, boolp);
	}
	public static void f2(Element elem) {
		Camera camera = scene.getCamera();
		float  mul    = graphics.getHeight() / (float) graphics.getWidth();
		float  mul2   = elem.getHeight() / elem.getWidth();
		Vec2   endPos = ElementUtils.getAbsPosCenter(elem);

		Interp  fun   = Interp.swing;
		float[] a     = {0};
		float   end   = elem.getWidth();
		float   start = graphics.getWidth();
		float startX = camera.position.x, endX = endPos.x,
		 startY = camera.position.y, endY = endPos.y;
		TASKS.add(() -> {
			a[0] += 0.01f;
			float v    = a[0] < 1 ? a[0] : 2 - a[0];
			float unit = fun.apply(start, end, v);
			camera.position.set(
			 fun.apply(startX, endX, v),
			 fun.apply(startY, endY, v)
			);
			camera.width = unit;
			camera.height = unit * mul;
			if (a[0] >= 2) {
				camera.width = graphics.getWidth();
				camera.height = graphics.getHeight();
				camera.position.set(graphics.getWidth() / 2f, graphics.getHeight() / 2f);
			}
			return a[0] <= 2;
		});
	}

	static class Buffer {
		public static final FrameBuffer pingpong1 = new FrameBuffer(), pingpong2 = new FrameBuffer();

		static {
			pingpong1.begin(Color.clear);
			pingpong1.end();
			pingpong2.begin(Color.clear);
			pingpong2.end();
		}
	}

	public static void drawFocus(Color bgColor, Runnable draw) {
		drawFocus(bgColor, draw, Color.clear);
	}

	public static void drawFocus(Color bgColor, Runnable draw, Color color) {
		int w = graphics.getWidth();
		int h = graphics.getHeight();
		pingpong1.resize(w, h);
		pingpong1.begin(Color.clear);
		Draw.color(bgColor);
		Fill.crect(0, 0, w, h);
		pingpong1.end();
		pingpong2.resize(w, h);
		pingpong2.begin(Color.clear);
		draw.run();
		pingpong2.end();
		pingpong2.getTexture().bind(1);
		MyShaders.mixScreen.color = color;
		pingpong1.blit(MyShaders.mixScreen);
	}
	public static boolean enableFocusMouse;
	public static void init() {
		if (Vars.mobile) return;
		/* Events.run(Trigger.update, () -> {
			viewport.getCamera().position.set(Core.graphics.getWidth() / 2f, Core.graphics.getHeight() / 2f - 50);
			viewport.setWorldHeight(Core.graphics.getHeight() - 50);
		}); */
		InputListener listener = new InputListener() {
			final Task task = new Task() {
				public void run() {}
			};
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				if (keycode == KeyCode.shiftLeft) {
					if (!TaskManager.scheduleOrCancel(0.4f, task)) {
						enableFocusMouse = !enableFocusMouse;
					}
				}
				return false;
			}
			public boolean keyTyped(InputEvent event, char character) {
				task.cancel();
				return false;
			}
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (enableFocusMouse) event.cancel();
				enableFocusMouse = false;
				return false;
			}
		};
		scene.root.getCaptureListeners().insert(0, listener);
		LerpFun fun = new LerpFun(Interp.fastSlow, Interp.slowFast);
		fun.register(0.05f);
		JSFunc.applyDraw(() -> {
			if (enableFocusMouse || fun.a > 0) {
				graphics.requestRendering();
				fun.enabled = true;
				fun.reverse = !enableFocusMouse;
				float aLerp = Mathf.lerp(0.1f, 0.5f, fun.applyV);
				drawFocus(Tmp.c1.set(Color.black).a(aLerp), () -> {
					Fill.circle(mouseVec.x, mouseVec.y,
					 Mathf.lerp(1000f, 80f, fun.applyV)
					);
				}, Tmp.c2.set(Color.white).a(aLerp));
			} else fun.enabled = false;
		});
		// boolean valid[] = {false};
		// viewport.setScreenBounds(20, 40,
		//  Core.graphics.getWidth() -20,
		//  Core.graphics.getHeight() -40);
		// Draw.trans().scl(2);
	}
}
