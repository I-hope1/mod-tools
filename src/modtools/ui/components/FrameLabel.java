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
		super.draw();
	}
}
