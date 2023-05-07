package modtools.ui.components.input;

import arc.func.Prov;
import arc.graphics.Texture;
import arc.graphics.g2d.*;
import arc.graphics.gl.FrameBuffer;
import arc.scene.Group;
import arc.scene.ui.*;
import arc.util.*;
import modtools.graphics.MyShaders;
import modtools.ui.TopGroup;
import modtools.ui.effect.ScreenSampler;
import modtools.utils.*;

import static arc.Core.graphics;
import static modtools.ui.effect.ScreenSampler.*;

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
	static FrameBuffer buffer = new FrameBuffer(2, 2, false);
	public void draw() {
		if (true) {
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
			// Draw.flush();
			// bloom.capture();
			// bloom.capturePause();
			// if (texture != null) texture.dispose();
			WorldDraw.drawTexture(buffer, graphics.getWidth(), graphics.getHeight(), super::draw);
			frameInvalid = false;
			// 	// validate();
			// 	float lx = x, ly = y;
			// 	x = -width;
			// 	y = -height;
			// 	Group lparent = parent;
			// 	parent = null;
			// 	super.draw();
			// 	x = lx;
			// 	y = ly;
			// 	parent = lparent;
			// 	/*Color color = tempColor.set(this.color);
			// 	color.a *= parentAlpha;
			// 	if (style.background != null) {
			// 		Draw.color(color.r, color.g, color.b, color.a);
			// 		style.background.draw(0, 0, width, height);
			// 	}
			// 	if (style.fontColor != null) color.mul(style.fontColor);
			// 	cache.tint(color);
			// 	cache.setPosition(0, 0);
			// 	cache.draw();*/
			// });
		}
		// bloom.render();
		// ScreenSampler.pause();
		Draw.flush();
		Draw.blit(buffer, MyShaders.baseShader);
		// ScreenSampler.contiune();
	}
}
