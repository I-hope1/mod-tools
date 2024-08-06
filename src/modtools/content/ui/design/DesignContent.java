package modtools.content.ui.design;

import arc.scene.Element;
import arc.scene.style.Drawable;
import arc.scene.ui.Button;
import arc.struct.Seq;
import modtools.IntVars;
import modtools.ui.*;
import modtools.ui.menu.*;
import modtools.ui.comp.Window;
import modtools.content.Content;

public class DesignContent extends Content {
	public Window ui;
	public DesignContent() {
		super("designContent");
	}
	public void buildUI() {
		ui = new Window(localizedName());
		ui.cont.button("text", HopeStyles.flatBordert, IntVars.EMPTY_RUN)
		 .height(42)
		 .self(c -> MenuBuilder.addShowMenuListenerp(c.get(), () -> Seq.with(
			MenuItem.with("growX", null, "growX", () -> {
				c.growX();
				c.getTable().layout();
			}))
		 ));
		ui.cont.layout();
	}
	public void build() {
		if (ui == null) buildUI();
		ui.show();
	}
	public static class ElementButton extends Button {
		public Element element;
		public ElementButton(ButtonStyle style) {
			super(style);
		}
		public ElementButton() {
		}
		public ElementButton(Drawable up) {
			super(up);
		}
		public ElementButton(Drawable up, Drawable down) {
			super(up, down);
		}
		public ElementButton(Drawable up, Drawable down, Drawable checked) {
			super(up, down, checked);
		}
	}
}
