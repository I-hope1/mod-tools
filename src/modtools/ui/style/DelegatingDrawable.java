package modtools.ui.style;

import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.scene.style.*;
import arc.util.*;
import modtools.utils.ui.FormatHelper;

public class DelegatingDrawable extends BaseDrawable {
	public Drawable drawable;
	public Color    color;
	public DelegatingDrawable(Drawable drawable, Color color) {
		reset(drawable, color);
	}
	public void reset(Drawable drawable, Color color) {
		if (drawable instanceof DelegatingDrawable d) {
			drawable = d.drawable;
			color = d.color;
		}
		this.drawable = drawable;
		this.color = color;
	}
	public void draw(float x, float y, float originX, float originY, float width, float height, float scaleX,
	                 float scaleY, float rotation) {
		if (drawable == null) return;
		Draw.color(Tmp.c1.set(color).mul(Draw.getColor()).toFloatBits());
		drawable.draw(x, y, originX, originY, width, height, scaleX, scaleY, rotation);
	}
	public void draw(float x, float y, float width, float height) {
		if (drawable == null) return;
		Draw.color(Tmp.c1.set(color).mul(Draw.getColor()).toFloatBits());
		drawable.draw(x, y, width, height);
	}
	public float getLeftWidth() {
		return drawable.getLeftWidth();
	}
	public void setLeftWidth(float leftWidth) {
		drawable.setLeftWidth(leftWidth);
	}
	public float getRightWidth() {
		return drawable.getRightWidth();
	}
	public void setRightWidth(float rightWidth) {
		drawable.setRightWidth(rightWidth);
	}
	public float getTopHeight() {
		return drawable.getTopHeight();
	}
	public void setTopHeight(float topHeight) {
		drawable.setTopHeight(topHeight);
	}
	public float getBottomHeight() {
		return drawable.getBottomHeight();
	}
	public void setBottomHeight(float bottomHeight) {
		drawable.setBottomHeight(bottomHeight);
	}
	public float getMinWidth() {
		return drawable.getMinWidth();
	}
	public void setMinWidth(float minWidth) {
		drawable.setMinWidth(minWidth);
	}
	public float getMinHeight() {
		return drawable.getMinHeight();
	}
	public void setMinHeight(float minHeight) {
		drawable.setMinHeight(minHeight);
	}

	public String toString() {
		try {
			if (color == Color.white) return FormatHelper.getUIKeyOrNull(drawable);

			return STR."\{FormatHelper.getUIKeyOrNull(drawable)}#\{FormatHelper.color(color)}";
		} catch (Throwable e) {
			return drawable + "#" + FormatHelper.color(color);
		}
	}
}
