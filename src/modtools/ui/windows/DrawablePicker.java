package modtools.ui.windows;

import arc.scene.style.Drawable;
import arc.scene.ui.Image;
import mindustry.gen.Tex;
import modtools.ui.components.Window;

public class DrawablePicker extends Window {

	Drawable selected = Tex.whiteui;
	Drawable delegate = new DelegateDrawable(selected);
	public DrawablePicker() {
		super("drawablePicker", 100, 100, true);

		cont.stack(new Image(Tex.alphaBgLine), new Image(delegate));
	}
	private static class DelegateDrawable implements Drawable {
		Drawable delegate;
		public DelegateDrawable(Drawable delegate) {
			this.delegate = delegate;
		}
		public void draw(float x, float y, float width, float height) {
			delegate.draw(x, y, width, height);
		}
		public void draw(float x, float y, float originX, float originY, float width, float height, float scaleX,
										 float scaleY, float rotation) {
			delegate.draw(x, y, width, height);
		}
		public float getLeftWidth() {
			return delegate.getLeftWidth();
		}
		public void setLeftWidth(float leftWidth) {
		}
		public float getRightWidth() {
			return delegate.getRightWidth();
		}
		public void setRightWidth(float rightWidth) {
		}
		public float getTopHeight() {
			return delegate.getTopHeight();
		}
		public void setTopHeight(float topHeight) {
		}
		public float getBottomHeight() {
			return delegate.getBottomHeight();
		}
		public void setBottomHeight(float bottomHeight) {
		}
		public float getMinWidth() {
			return delegate.getMinWidth();
		}
		public void setMinWidth(float minWidth) {
		}
		public float getMinHeight() {
			return delegate.getMinHeight();
		}
		public void setMinHeight(float minHeight) {
		}
	}
}
