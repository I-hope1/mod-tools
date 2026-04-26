package modtools.ui.effect;

import arc.graphics.gl.FrameBuffer;
import modtools.utils.reflect.ClassUtils;

public interface ScreenSampler {
	ScreenSampler instance =
	 ClassUtils.exists("universe.graphic.ScreenSampler")
	 && ClassUtils.hasMethod(universe.graphic.ScreenSampler.class, "toBuffer", FrameBuffer.class)
		? new UnkScreenSampler() : new ScreenSampler() {
		 public void getToBuffer(FrameBuffer buffer, boolean clear) {
		 }
		 public void setup() {
		 }
		 public boolean activity() {
			 return false;
		 }
	 };

	void getToBuffer(FrameBuffer buffer, boolean clear);
	void setup();
	boolean activity();

	class UnkScreenSampler implements ScreenSampler {
		@Override
		public void getToBuffer(FrameBuffer buffer, boolean clear) {
			universe.graphic.ScreenSampler.toBuffer(buffer);
		}
		@Override
		public boolean activity() {
			return true;
		}
		@Override
		public void setup() {
			ClassUtils.forName("universe.graphic.ScreenSampler");
		}
	}
}