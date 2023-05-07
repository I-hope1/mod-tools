package modtools.ui.components.input;

import arc.func.Prov;
import arc.graphics.*;
import arc.graphics.g2d.Draw;
import arc.graphics.gl.FrameBuffer;
import arc.scene.ui.Label;
import modtools.graphics.MyShaders;

import static modtools.ui.effect.ScreenSampler.bufferCapture;

/**
 * 缓存label，可以缓存文本图像，减少cpu和gpu消耗
 **/
public class FrameLabel extends Label {

	public FrameLabel(Prov<CharSequence> sup) {
		super(sup);
	}

	public FrameLabel(CharSequence text) {
		super(text);
	}

	public FrameLabel(CharSequence text, LabelStyle style) {
		super(text, style);
	}

	boolean frameInvalid = true, sampling = false;
	public Texture texture;

	public void setText(CharSequence newText) {
		if (!text.toString().equals(newText.toString())) frameInvalid = true;
		super.setText(newText);
	}

	public void layout() {
		super.layout();
		frameInvalid = true;
	}
	public FrameBuffer pingpong = new FrameBuffer();
	public void draw() {
		if (sampling) {
			super.draw();
			return;
		}
		if (frameInvalid) {
			/* Time.runTask(0, () -> {
				sampling = true;
				texture = bufferCaptureAll(Tmp.v1.set(0, 0), this);
				sampling = false;
				frameInvalid = false;
			}); */
			sampling = true;
			pingpong.resize((int) width, (int) height);
			pingpong.begin(Color.clear);
			Draw.blit(bufferCapture(this), MyShaders.baseShader);
			Draw.flush();
			pingpong.end();
			texture = pingpong.getTexture();
			sampling = false;
			frameInvalid = false;
		}
		// bloom.render();
		// ScreenSampler.pause();
		Draw.rect(Draw.wrap(texture), x, y, width, height);
		// Draw.blit(pingpong, MyShaders.baseShader);
		// ScreenSampler.contiune();
	}
}
