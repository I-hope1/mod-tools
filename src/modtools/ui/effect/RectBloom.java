package modtools.ui.effect;

import arc.Core;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.FrameBuffer;
import arc.math.geom.Rect;
import arc.util.pooling.Pools;
import arc.util.viewport.Viewport;
import modtools.ui.effect.MyDraw.DrawEffect;

import static arc.Core.graphics;
import static modtools.graphics.MyShaders.*;
import static modtools.utils.MySettings.D_BLUR;

public class RectBloom implements DrawEffect {
	Rect area = new Rect(), scissorBounds;
	// Bloom       bloom    = new Bloom(true);
	FrameBuffer pingpong = new FrameBuffer()
			// , pingpong2 = new FrameBuffer()
			;
	public RectBloom() {
		/* 刷新一下buffer */
		// bloom.buffer().begin(Color.clear);
		// bloom.buffer().end();
		pingpong.begin(Color.clear);
		pingpong.end();

		// bloom.blurPasses = 3;
		// bloom.setBloomIntensity(10f);
		// bloom.setClearColor(0, 0, 0, 0);
		// bloom.setThreshold(0f);
		// bloom.setOriginalIntensity(0.6f);
	}

	boolean pushed;
	public void resize(int width, int height) {
		pushed = false;
		int blurScl = D_BLUR.getInt("缩放级别", 4);
		// bloom.resize(graphics.getWidth(), graphics.getHeight(), blurScl);
		pingpong.resize(graphics.getWidth() / blurScl, graphics.getHeight() / blurScl);
	}
	public void capture(float x, float y, float w, float h) {
		/* 获取屏幕 */
		ScreenSampler.pause();
		// bloom.capture();
		pingpong.begin();
		Draw.blit(ScreenSampler.getSampler(), baseShader);
		// Draw.flush();
		// bloom.capturePause();
		// bloom.render();
		Draw.flush();
		pingpong.end();
		// bloom.capturePause();

		/* 对渲染区域进行裁剪 */
		scissorBounds = Pools.obtain(Rect.class, Rect::new);
		area.set(x, y, w, h);
		Viewport viewport = Core.scene.getViewport();
		/* 将area的坐标转换为camera的坐标 */
		ScissorStack.calculateScissors(
				viewport.getCamera(),
				// camera,
				// Mathf.clamp(x - w / 2f, 0, w), Mathf.clamp(y - h / 2f, 0, h),
				viewport.getScreenX(), viewport.getScreenY(),
				viewport.getScreenWidth(), viewport.getScreenHeight(),
				// graphics.getWidth(), graphics.getHeight(),
				Draw.trans(), area, scissorBounds);


		/* ScreenSampler.pause();
		Tools.clearScreen();
		ScreenSampler.contiune(); */
	}
	public void render() {
		// ScreenSampler.contiune();
		pushed = ScissorStack.push(scissorBounds);
		ScreenSampler.contiune();
		if (!pushed) {
			Pools.free(scissorBounds);
		} else {
			Draw.blend(Blending.disabled);
			pingpong.blit(blur);
			Draw.blend(Blending.normal);
			// pingpong2.end();
			Pools.free(ScissorStack.pop());
		}
		// Draw.flush();
		// pingpong2.blit(baseShader);
		// Draw.blit(pingpong.getTexture(), baseShader);
	}


	/* static class ClassMap extends ObjectMap<Class<?>, ClassMap> {}

	static ClassMap            root    = new ClassMap();
	static ObjectSet<Class<?>> classes = new ObjectSet<>();

	static ClassMap addClass(Class<?> parent, Class<?> cl) {
		Class<?> superCl = cl.getSuperclass();
		if (superCl == Block.class) return root;
		return addClass(cl, superCl).get(cl, parent == null ? () -> null : ClassMap::new);
	}
	static {
		mindustry.mod.ClassMap.classes.each((__, cl) -> {
			if (Block.class.isAssignableFrom(cl)) addClass(null, cl);
		});
		String $1 = root.toString(",\n", false)
				.replace('=', ':')
				// .replaceAll("class", "")
				.replaceAll("class .+?\\.([\\w$_]+:)", "$1");
		Log.info($1);
		Log.info(Jval.read($1).toString(Jformat.formatted));
	} */
}
