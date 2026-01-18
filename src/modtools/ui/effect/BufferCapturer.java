package modtools.ui.effect;

import arc.graphics.*;
import arc.graphics.g2d.Draw;
import arc.graphics.gl.FrameBuffer;
import arc.math.Mat;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.util.Tmp;
import modtools.struct.LazyValue;

import static arc.Core.graphics;

public class BufferCapturer {
	static LazyValue<FrameBuffer> bufferL = LazyValue.of(BufferCapturer::createBuffer);
	static FrameBuffer createBuffer() {
		return new FrameBuffer(512, 512);
	}
	public static Texture capture(Element element) {
		FrameBuffer buffer = bufferL.get();
		buffer.begin();
		element.draw();
		buffer.end();
		return buffer.getTexture();
	}
	static Mat mat = new Mat();
	/** 图片反着的 */
	public static Texture bufferCapture(Element element) {
		Gl.flush();

		Tmp.m1.set(Draw.proj());
		FrameBuffer buffer = bufferL.get();
		mat.setOrtho(element.x, element.y, element.getWidth(), element.getHeight());
		Draw.proj(mat);
		buffer.resize((int) element.getWidth(), (int) element.getHeight());
		buffer.begin(Color.clear);
		Draw.reset();
		// Tools.clearScreen();
		element.draw();
		buffer.end();
		Draw.proj(Tmp.m1);
		return buffer.getTexture();
	}
	public static Texture bufferCaptureAll(Vec2 pos, Element element) {
		FrameBuffer buffer = bufferL.get();
		float       lastX  = element.x, lastY = element.y;
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
}