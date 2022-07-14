package modtools.ui.content;

import arc.Core;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.ui.Image;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import modtools.IntVars;
import modtools.ui.IntUI;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static modtools.ui.Contents.tester;

public class ElementShow extends Content {
	public ElementShow() {
		super("显示元素");
	}

	public ElementShowDialog dialog;
	public static boolean hideSelf = true;
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
		if (frag.parent == null) {
			Core.scene.add(frag);
		} else frag.remove();
	}

	public static class ElementShowDialog extends BaseDialog {
		Table pane = new Table();
		Element element = null;
		Pattern pattern;

		public ElementShowDialog() {
			super("调试");

			name = "ElementShowDialog";

			addCloseButton();
			pane.left().defaults().left();
			cont.table(t -> {
				t.button("显示父元素", Icon.up, () -> {
					Runnable go = () -> rebuild(element = element.parent, pattern);
					if (element.parent == Core.scene.root) {
						Vars.ui.showConfirm("父元素为根节点，是否确定", () -> {
							if (hideSelf) remove();
							go.run();
							if (hideSelf) Core.scene.add(this);
						});
					} else go.run();
				}).disabled(b -> element == null || element.parent == null).width(120);
				t.table(search -> {
					search.image(Icon.zoom);
					search.field("", str -> rebuild(element, str)).growX();
				}).growX();
			}).growX().row();

			cont.add(new ScrollPane(pane) {
				@Override
				public String toString() {
					if (hideSelf) return name;
					return super.toString();
				}
			}).grow();
		}

		public void rebuild(Element element, String text) {
			pane.clearChildren();

			if (element == null) return;
			Pattern pattern = null;
			try {
				pattern = text.isEmpty() ? null : Pattern.compile("(.*?)(" + text + ")", Pattern.CASE_INSENSITIVE);
			} catch (Exception ignored) {}
			rebuild(element, pattern);
		}

		public void rebuild(Element element, Pattern pattern) {
			pane.clearChildren();

			if (element == null) return;
			this.pattern = pattern;
			build(element, pane, pattern);

			pane.row();
			pane.image().color(Pal.accent).growX().padTop(10).padBottom(10).row();
			highlightShowMultiRow(pane, pattern, element + "");
		}


		public void highlightShowMultiRow(Table table, Pattern pattern, String text) {
			if (pattern == null) {
				table.add(text);
				return;
			}
			table.table(t -> {
				t.left().defaults().left();
				for (var line : text.split("\n")) {
					highlightShow(t, pattern, line);
					t.row();
				}
			});
		}

		public void highlightShow(Table table, Pattern pattern, String text) {
			if (pattern == null) {
				table.add(text);
				return;
			}
			Matcher matcher = pattern.matcher(text);
			table.table(t -> {
				int index = 0;
				var ref = new Object() {
					String curText;
				};
				t.left().defaults().left();
				int times = 0;
				while (index <= text.length() && matcher.find(index)) {
					times++;
					if (times > 40) {
						Vars.ui.showException(new Exception("too many"));
						break;
					}
					int len = matcher.group().length();
					if (len == 0) break;
					index += len;

					ref.curText = matcher.group(1);
					t.add(ref.curText);
					ref.curText = matcher.group(2);
					t.table(IntUI.whiteui.tint(Pal.accent), t1 -> {
						t1.add(ref.curText, Color.sky).row();
					});
				}
				if (text.length() - index > 0) t.add(text.substring(index));
			});
		}

		public void build(Element element, Table table, Pattern pattern) {
			if (hideSelf) {
				if (element == this) {
					table.add("----" + name + "-----").row();
					return;
				}
				if (element == pane) {
					table.add("----" + name + "$pane-----").row();
					return;
				}
			}
			table.left().defaults().left();

			try {
				table.table(Tex.underlineWhite, wrap -> {
					if (element instanceof Group) {
						highlightShowMultiRow(wrap, pattern, getSimpleName(element.getClass()) + (element.name != null ? ": " + element.name : ""));
						var children = ((Group) element).getChildren();
						wrap.table(Tex.button, t -> {
							for (var child : children) {
								build(child, t, pattern);
							}
						}).padLeft(8).left();
					} else if (element instanceof Image) {
						Drawable drawable = ((Image) element).getDrawable();
						if (drawable != null) wrap.image(drawable);
						else wrap.add("空图像");
					} else {
						highlightShowMultiRow(wrap, pattern, element + "");
					}
					wrap.button("储存为js变量", () -> tester.put(element)).size(96, 50);
				}).row();
			} catch (Exception e) {
//				Vars.ui.showException(e);
				Log.err(e);
			}

			table.row();
		}

		public void show(Element element) {
			this.element = element;
			rebuild(element, "");
			show();
		}

		@Override
		public String toString() {
			if (hideSelf) return name;
			return super.toString();
		}
	}

	public static String getSimpleName(Class<?> clazz) {
		/*while (clazz.getSimpleName() == null && clazz.isAssignableFrom(Group.class)) {
			clazz = clazz.getSuperclass();
		}*/
		return clazz.getSimpleName();
	}
}
