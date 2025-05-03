package modtools.ui.effect;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.Draw;
import arc.graphics.gl.*;
import arc.util.serialization.Jval;
import mindustry.game.EventType;

import java.lang.reflect.Field;

import static modtools.graphics.MyShaders.baseShader;

/** @author EBwilson  */
public class ScreenSampler {
	private static FrameBuffer worldBuffer, uiBuffer;

	private static FrameBuffer currBuffer;
	public static  boolean     activity;

	public static void resetMark() {
		Core.settings.remove("sampler.setup");
	}
	public static void setup() {
		if (activity) throw new RuntimeException("forbid setup sampler twice");

		Jval e = Jval.read(Core.settings.getString("sampler.setup", "{enabled: false}"));

		// Events.run(EventType.DisposeEvent.class, ScreenSampler::resetMark);
		if (!e.getBool("enabled", false)) {
			e = Jval.newObject();
			e.put("enabled", true);
			e.put("className", ScreenSampler.class.getName());
			e.put("worldBuffer", "worldBuffer");
			e.put("uiBuffer", "uiBuffer");

			worldBuffer = new FrameBuffer();
			uiBuffer = new FrameBuffer();

			Core.settings.put("sampler.setup", e.toString());

			Events.run(EventType.Trigger.preDraw, ScreenSampler::beginWorld);
			Events.run(EventType.Trigger.postDraw, ScreenSampler::endWorld);
			Events.run(EventType.Trigger.uiDrawBegin, ScreenSampler::beginUI);
			Events.run(EventType.Trigger.uiDrawEnd, ScreenSampler::endUI);
		} else {
			String className       = e.getString("className");
			String worldBufferName = e.getString("worldBuffer");
			String uiBufferName    = e.getString("uiBuffer");
			try {
				Class<?> clazz            = Class.forName(className);
				Field    worldBufferField = clazz.getDeclaredField(worldBufferName);
				Field    uiBufferField    = clazz.getDeclaredField(uiBufferName);
				worldBufferField.setAccessible(true);
				uiBufferField.setAccessible(true);
				worldBuffer = (FrameBuffer) worldBufferField.get(null);
				uiBuffer = (FrameBuffer) uiBufferField.get(null);

				Events.run(EventType.Trigger.preDraw, () -> currBuffer = worldBuffer);
				Events.run(EventType.Trigger.postDraw, () -> currBuffer = null);
				Events.run(EventType.Trigger.uiDrawBegin, () -> currBuffer = uiBuffer);
				Events.run(EventType.Trigger.uiDrawEnd, () -> currBuffer = null);
			} catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException ex) {
				activity = false;
				return;
			}
		}
		activity = true;
	}

	private static void beginWorld() {
		currBuffer = worldBuffer;
		worldBuffer.resize(Core.graphics.getWidth(), Core.graphics.getHeight());
		worldBuffer.begin(Color.clear);
	}
	private static void endWorld() {
		currBuffer = null;
		worldBuffer.end();
	}

	private static void beginUI() {
		currBuffer = uiBuffer;
		uiBuffer.resize(Core.graphics.getWidth(), Core.graphics.getHeight());
		uiBuffer.begin(Color.clear);
		blitBuffer(worldBuffer, uiBuffer);
	}

	private static void endUI() {
		currBuffer = null;
		uiBuffer.end();
		blitBuffer(uiBuffer, null);
	}

	/**将当前的屏幕纹理使用传入的着色器绘制到屏幕上*/
	public static void blit(Shader shader) {
		blit(shader, 0);
	}

	/**将当前的屏幕纹理使用传入的着色器绘制到屏幕上
	 * @param unit 屏幕采样纹理绑定的纹理单元*/
	public static void blit(Shader shader, int unit) {
		if (currBuffer == null) throw new IllegalStateException("currently no buffer bound");

		currBuffer.getTexture().bind(unit);
		Draw.blit(shader);
	}

	private static void blitBuffer(FrameBuffer from, FrameBuffer to) {
		if (Core.gl30 == null) {
			from.blit(baseShader);
		} else {
			Gl.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.getFramebufferHandle());
			Gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to == null ? 0 : to.getFramebufferHandle());
			Core.gl30.glBlitFramebuffer(
			 0, 0, from.getWidth(), from.getHeight(),
			 0, 0,
			 to == null ? Core.graphics.getWidth() : to.getWidth(),
			 to == null ? Core.graphics.getHeight() : to.getHeight(),
			 Gl.colorBufferBit, Gl.nearest
			);
		}
	}

	/**将当前屏幕纹理转存到一个{@linkplain FrameBuffer 帧缓冲区}，这将成为一份拷贝，可用于暂存屏幕内容
	 *
	 * @param target 用于转存屏幕纹理的目标缓冲区
	 * @param clear 在转存之前是否清空帧缓冲区*/
	public static void getToBuffer(FrameBuffer target, boolean clear) {
		if (currBuffer == null) throw new IllegalStateException("currently no buffer bound");

		if (clear) { target.begin(Color.clear); } else target.begin();

		Gl.bindFramebuffer(GL30.GL_READ_FRAMEBUFFER, currBuffer.getFramebufferHandle());
		Gl.bindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.getFramebufferHandle());
		Core.gl30.glBlitFramebuffer(
		 0, 0, currBuffer.getWidth(), currBuffer.getHeight(),
		 0, 0, target.getWidth(), target.getHeight(),
		 Gl.colorBufferBit, Gl.nearest
		);

		target.end();
	}
}
