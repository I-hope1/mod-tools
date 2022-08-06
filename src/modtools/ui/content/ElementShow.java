package modtools.ui.content;

import arc.Core;
import arc.func.Boolp;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.event.*;
import arc.scene.ui.Button;
import arc.scene.ui.Image;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.ui.IntStyles;
import modtools.ui.IntUI;
import modtools.ui.MyFonts;
import modtools.ui.components.MyLabel;
import modtools.ui.components.Window;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static modtools.ui.Contents.tester;
import static modtools.utils.Tools.getAbsPos;

public class ElementShow extends Content {
	public ElementShow() {
		super("reviewElement");
	}

	public static final boolean hideSelf = true;
	public Element frag;
	public Element selected, tmp;
	public boolean selecting;

	// 获取指定位置的元素
	public void getSelected(float x, float y) {
		tmp = Core.scene.root.hit(x, y, true);
		selected = null;
		if (tmp != null) {
			do {
				selected = tmp;
				tmp = selected.hit(x, y, true);
			} while (tmp != null && selected != tmp);
		}
	}

	@Override
	public void load() {
		frag = new Element() {
			@Override
			public void draw() {
				super.draw();
				if (selected == null || !selecting) return;
				Draw.z(Layer.fogOfWar);
				Draw.color(Color.blue, 0.4f);
				Vec2 vec2 = getAbsPos(selected);
				Fill.crect(vec2.x, vec2.y, selected.getWidth(), selected.getHeight());
			}

			@Override
			public Element hit(float x, float y, boolean touchable) {
				return super.hit(x, y, touchable);
			}
		};
		frag.update(() -> frag.toFront());
		frag.touchable = Touchable.enabled;
		frag.fillParent = true;
		frag.addListener(new InputListener() {
			/*@Override
			public boolean mouseMoved(InputEvent event, float x, float y) {
				if (!selecting) return false;
				getSelected(x, y);
				return true;
			}

			@Override
			public void clicked(InputEvent event, float x, float y) {
				// Seq<? extends Element> tmp = Core.scene.root.getChildren().copy();
				if (!selecting || selected == null) return;
				// Core.scene.unfocusAll();
				// super.clicked(event, x, y);
				selecting = false;
				IntVars.async(() -> {
					frag.remove();
					if (((Boolp) () -> {
						Element parent = selected.parent;
						while (true) {
							if (parent instanceof ElementShowDialog) return false;
							if (parent == null) return true;
							parent = parent.parent;
						}
					}).get()) new ElementShowDialog().show(selected);
				}, () -> {});
				btn.setChecked(false);
			}*/
			@Override
			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (!selecting) return false;
				frag.touchable = Touchable.disabled;
				getSelected(x, y);
				return true;
			}

			@Override
			public void touchDragged(InputEvent event, float x, float y, int pointer) {
				getSelected(x, y);
			}

			@Override
			public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
				selecting = false;
				IntVars.async(() -> {
					if (((Boolp) () -> {
						if (selected == null) return false;
						Element parent = selected.parent;
						while (true) {
							if (parent instanceof ElementShowDialog) return false;
							if (parent == null) return true;
							parent = parent.parent;
						}
					}).get()) new ElementShowDialog().show(selected);
				}, () -> {});
				frag.remove();
			}
		});

		btn.update(() -> btn.setChecked(selecting));
		btn.setStyle(Styles.logicTogglet);
	}

	@Override
	public void build() {
		selected = null;
		if (frag.parent == null) {
			selecting = true;
			frag.touchable = Touchable.enabled;
			Core.scene.add(frag);
		} else {
			selecting = false;
			frag.remove();
		}
	}

	public static class ElementShowDialog extends Window {
		Table pane = new Table();
		Element element = null;
		Pattern pattern;

		public ElementShowDialog() {
			super("审查元素", 0, 160, true);
			getCell(cont).maxWidth(Core.graphics.getWidth());

			name = "ElementShowDialog";

			//			addCloseButton();
			pane.left().defaults().left();
			cont.table(t -> {
				Button[] bs = {null};
				bs[0] = t.button("显示父元素", Icon.up, () -> {
					Runnable go = () -> {
						hide();
						var window = new ElementShowDialog();
						window.pattern = pattern;
						window.show(element.parent);
						window.setPosition(x, y);
						window.display();
					};
					if (element.parent == Core.scene.root) {
						Vec2 vec2 = bs[0].localToStageCoordinates(new Vec2(0, 0));
						IntUI.showConfirm("父元素为根节点，是否确定", go).setPosition(vec2);
					} else go.run();
				}).disabled(b -> element == null || element.parent == null).width(120).get();
				t.button(Icon.copy, Styles.flati, () -> {
					var window = new ElementShowDialog();
					window.pattern = pattern;
					window.show(element);
				}).padLeft(4f).padRight(4f);
				t.button(Icon.refresh, Styles.flati, () -> rebuild(element, pattern)).padLeft(4f).padRight(4f);
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
				table.add(new MyLabel(text, IntStyles.myLabel));
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
				table.add(text, IntStyles.myLabel);
				return;
			}
			Matcher matcher = pattern.matcher(text);
			table.table(t -> {
				// Font font = style.font;

				int index = 0;
				var ref = new Object() {
					String curText;
				};
				int times = 0;
				float offsetX = 0;
				while (index <= text.length() && matcher.find(index)) {
					times++;
					if (times > 70) {
						IntUI.showException(new Exception("too many"));
						break;
					}
					int len = matcher.group().length();
					if (len == 0) break;
					index += len;

					ref.curText = matcher.group(1);
					t.add(ref.curText);
					ref.curText = matcher.group(2);
					t.table(IntUI.whiteui.tint(Pal.accent), t1 -> {
						t1.add(new MyLabel(ref.curText, new LabelStyle(MyFonts.MSYHMONO, Color.sky))).row();
					});
				}
				if (text.length() - index > 0) t.add(text.substring(index), IntStyles.myLabel);
			});
		}

		public void build(Element element, Table table, Pattern pattern) {
			if (hideSelf) {
				if (element == this) {
					table.add("----" + name + "-----", IntStyles.myLabel).row();
					return;
				}
				if (element == pane) {
					table.add("----" + name + "$pane-----", IntStyles.myLabel).row();
					return;
				}
			}
			table.left().defaults().left();

			try {
				table.table(Tex.underlineWhite, wrap -> {
					if (element instanceof Group) {
						highlightShowMultiRow(wrap, pattern, getSimpleName(element.getClass()) + (element.name != null ? ": " + element.name : ""));
						var children = ((Group) element).getChildren();
						wrap.table(Window.myPane, t -> {
							if (children.size == 0) {
								return;
							}
							Button button = t.button("更多", Icon.info, Styles.togglet, () -> {}).minWidth(200).height(45).growX().get();
							t.row();
							t.collapser(c -> {
								for (var child : children) {
									build(child, c, pattern);
								}
							}, true, button::isChecked).growX().with(col -> {
								col.setDuration(0.2f);
							}).row();
							button.setChecked(children.size < 20);
						}).padLeft(8).left();
					} else if (element instanceof Image) {
						try {
							wrap.table(Window.myPane, p -> p.add(new Image(((Image) element).getRegion())).color(element.color).size(element.getWidth(), element.getHeight()));
						} catch (Throwable e) {
							wrap.add("空图像");
						}
					} else {
						highlightShowMultiRow(wrap, pattern, element + "");
					}
					wrap.button("储存为js变量", () -> tester.put(wrap, element)).size(96, 50);
					IntUI.doubleClick(wrap.getChildren().first(), () -> {}, () -> {
						tester.put(wrap, element);
					});
				}).row();
			} catch (Exception e) {
				//				Vars.ui.showException(e);
				Log.err(e);
			}

			table.row();
		}

		public void show(Element element) {
			this.element = element;
			((ScrollPane) pane.parent).setScrollY(0);
			IntVars.async(() -> {
				rebuild(element, "");
			}, this::show);
			// 不知道为什么，这样就可以显示全面
			Vars.ui.showInfoFade("[clear]额");

			//			Vars.ui.loadSync();
			//			Time.runTask(1, () -> {
				/*int i = 0;
				String prefix = "temp";
				while (ScriptableObject.hasProperty(tester.scope, prefix + i)) {
					i++;
				}*/
			//				tester.put(prefix + i, "ikzak");
			//			});
			/*Time.runTask(1, () -> {
				for (int i = 0; i < 1E6; i++) ;
			});*/
		}

		@Override
		public void hide() {
			super.hide();
			all.remove(this);
			Time.runTask(30f, this::clear);
		}

		@Override
		public String toString() {
			if (hideSelf) return name;
			return super.toString();
		}
	}

	public static String getSimpleName(Class<?> clazz) {
		if (!Group.class.isAssignableFrom(clazz)) return clazz.getSimpleName();
		while (clazz.getSimpleName().isEmpty() && clazz != Group.class) {
			clazz = clazz.getSuperclass();
		}
		return clazz.getSimpleName();
	}
}
