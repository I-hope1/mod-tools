package modtools.ui.components;

import arc.func.Prov;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.scene.ui.*;
import arc.util.Time;
import modtools.utils.*;

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

	boolean frameInvalid = true;
	public TextureRegion region = new TextureRegion();

	@Override
	public void setText(CharSequence newText) {
		if (!text.toString().equals(newText.toString())) frameInvalid = true;
		super.setText(newText);
	}

	@Override
	public void draw() {
		if (frameInvalid) {
			Bloom bloom = new Bloom((int) width, (int) height, true, true);
			region = WorldDraw.drawRegion((int) width, (int) height, () -> {
				// validate();
				Color color = tempColor.set(this.color);
				color.a *= parentAlpha;
				if (style.background != null) {
					Draw.color(color.r, color.g, color.b, color.a);
					style.background.draw(0, 0, width, height);
				}
				if (style.fontColor != null) color.mul(style.fontColor);
				cache.tint(color);
				cache.setPosition(0, 0);
				cache.draw();
			});
			frameInvalid = false;
		}
		Draw.rect(region, x, y, width, height);
	}
}
