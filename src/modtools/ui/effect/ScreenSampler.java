package modtools.ui.effect;

import arc.Events;
import arc.graphics.*;
import arc.graphics.g2d.Draw;
import arc.graphics.gl.FrameBuffer;
import arc.math.geom.Vec2;
import arc.scene.Element;
import mindustry.game.EventType.*;
import modtools.IntVars;
import modtools.graphics.MyShaders;

import static arc.Core.graphics;

public class ScreenSampler {
	public static final FrameBuffer BUFFER_SCREEN = new FrameBuffer();

	public static void init() {
		BUFFER_SCREEN.resize(graphics.getWidth(), graphics.getHeight());
		BUFFER_SCREEN.begin(Color.clear);
		BUFFER_SCREEN.end();
		IntVars.addResizeListener(() -> {
			BUFFER_SCREEN.resize(graphics.getWidth(), graphics.getHeight());
		});
		Events.run(Trigger.uiDrawEnd, () -> {
			if (BUFFER_SCREEN.isBound()) {
				BUFFER_SCREEN.end();
				BUFFER_SCREEN.blit(MyShaders.baseShader);
			}
			BUFFER_SCREEN.begin(Color.clear);
		});
	}

	public static Texture getSampler() {
		/* if (!BUFFER_SCREEN.isBound()) {
			BUFFER_SCREEN.begin(Color.clear);
			BUFFER_SCREEN.end();
		} */
		// if (true) return getSampler0();
		return BUFFER_SCREEN.getTexture();
	}
	/* static FrameBuffer pingpong1 = new FrameBuffer();
	public static Texture getSampler0() {
		pingpong1.resize(graphics.getWidth(), graphics.getHeight());
		pingpong1.begin(Color.clear);
		BUFFER_SCREEN.blit(MyShaders.baseShader);
		pingpong1.end();
		return pingpong1.getTexture();
	} */

	public static void pause() {
		/* pingpong1.resize(BUFFER_SCREEN.getWidth(), BUFFER_SCREEN.getHeight());
		pingpong1.begin(Color.clear);
		BUFFER_SCREEN.blit(MyShaders.baseShader);
		pingpong1.end(); */
		// FrameBuffer.unbind();
		BUFFER_SCREEN.end();
	}
	public static void contiune() {
		// Draw.flush();
		BUFFER_SCREEN.begin();
	}

	private static final FrameBuffer buffer = new FrameBuffer();

	public static Texture bufferCaptureAll(Vec2 pos, Element element) {
		float lastX = element.x, lastY = element.y;
		element.x = pos.x;
		element.y = pos.y;
		buffer.resize(graphics.getWidth(), graphics.getHeight());
		buffer.begin(Color.clear);
		Draw.reset();
		// Tools.clearScreen();
		element.draw();
		buffer.end();
		element.x = lastX;
		element.y = lastY;
		return buffer.getTexture();
	}
	public static Texture bufferCapture(Element element) {
		float lastX = element.x, lastY = element.y;
		element.x = 0;
		element.y = 0;
		buffer.resize((int) element.getWidth(), (int) element.getHeight());
		buffer.begin(Color.clear);
		Draw.reset();
		// Tools.clearScreen();
		element.draw();
		buffer.end();
		element.x = lastX;
		element.y = lastY;
		return buffer.getTexture();
	}

}
