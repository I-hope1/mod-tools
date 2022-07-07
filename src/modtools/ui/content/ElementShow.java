package modtools.ui.content;

import arc.Core;
import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.Image;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import modtools.IntVars;

import static modtools.ui.Contents.tester;

public class ElementShow extends Content {
	public ElementShow() {
		super("显示元素");
	}

	public ElementShowDialog dialog;
	public Element frag;
	public Element selected, tmp;
	public boolean selecting;

	@Override
	public void load() {
		dialog = new ElementShowDialog();
		frag = new Element() {
			@Override
			public Element hit(float x, float y, boolean touchable) {
				return selecting ? null : super.hit(x, y, touchable);
			}
		};
		frag.touchable = Touchable.enabled;
		frag.setFillParent(true);
		frag.addListener(new InputListener() {
			@Override
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				selecting = true;
				tmp = Core.scene.root.hit(x, y, true);
				selected = null;
				if (tmp != null) {
					do {
						selected = tmp;
						tmp = selected.hit(x, y, true);
					} while (tmp != null && selected != tmp);
				}

				selecting = false;
				return true;
			}

			@Override
			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				IntVars.async(() -> dialog.show(selected), () -> {});
				frag.remove();
				btn.setChecked(false);
			}
		});

		btn.setStyle(Styles.logicTogglet);
	}

	@Override
	public void build() {
		Core.scene.add(frag);
	}

	public static class ElementShowDialog extends BaseDialog {
		Table pane = new Table();
		Element element = null;

		public ElementShowDialog() {
			super("调试");

			addCloseButton();
			pane.left().defaults().left();
			cont.button("显示父元素", Icon.up, () -> {
				rebuild(element = element.parent);
			}).disabled(b -> element == null || element.parent == null);
			cont.pane(pane).grow();
		}

		public void rebuild(Element element) {
			pane.clearChildren();

			if (element == null) return;
			build(element, pane);

			pane.row();
			pane.image().color(Pal.accent).growX().padTop(10).padBottom(10).row();
			pane.add(element + "");
		}

		public void build(Element element, Table table) {
			table.left().defaults().left();
			table.table(Tex.underlineWhite, wrap -> {
				if (element instanceof Group) {
					wrap.add(getSimpleName(element.getClass()) + (element.name != null ? ": " + element.name : ""));
					var children = ((Group) element).getChildren();
					wrap.table(Tex.button, t -> {
						for (var child : children) {
							build(child, t);
						}
					}).padLeft(8).left();
				} else if (element instanceof Image) {
					wrap.image(((Image) element).getRegion());
				} else {
					wrap.add(element + "");
				}
				wrap.button("储存为js变量", () -> {
					Vars.ui.showInfoFade("已储存为[accent]" + tester.put(element));
				}).size(96, 50);
			}).row();

			table.row();
		}

		public void show(Element element) {
			this.element = element;
			rebuild(element);
			show();
		}
	}

	public static String getSimpleName(Class<?> clazz) {
		/*while (clazz.getSimpleName() == null && clazz.isAssignableFrom(Group.class)) {
			clazz = clazz.getSuperclass();
		}*/
		return clazz.getSimpleName();
	}
}
