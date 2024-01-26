package modtools.ui.content.ui.design;

import arc.scene.Element;
import arc.scene.style.Drawable;
import arc.scene.ui.Button;
import arc.struct.Seq;
import mindustry.ui.Styles;
import modtools.ui.*;
import modtools.ui.menu.MenuList;
import modtools.ui.components.Window;
import modtools.ui.content.Content;

public class DesignContent extends Content {
	public Window ui;
	public DesignContent() {
		super("designContent");
	}
	public void buildUI() {
		ui = new Window(localizedName());
		ui.cont.button("text", HopeStyles.flatBordert, () -> {})
		 .height(42)
		 .self(c -> IntUI.addShowMenuListenerp(c.get(), () -> Seq.with(
			MenuList.with(null, "growX", () -> {
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
