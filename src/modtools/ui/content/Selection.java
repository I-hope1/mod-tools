
package modtools.ui.content;

import arc.Core;
import arc.func.Cons;
import arc.func.Cons2;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Lines;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Rect;
import arc.math.geom.Vec2;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.ui.Dialog;
import arc.scene.ui.Image;
import arc.scene.ui.ImageButton;
import arc.scene.ui.ScrollPane.ScrollPaneStyle;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.ctype.UnlockableContent;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.environment.OverlayFloor;
import modtools.ui.Contents;
import modtools.ui.IntFunc;
import modtools.ui.IntStyles;
import modtools.ui.IntUI;
import modtools.ui.components.MoveListener;
import modtools.ui.content.Tester.JSFunc;

import java.util.ArrayList;
import java.util.function.Consumer;

public class Selection extends Content {
	public Selection() {
		super("selection");
	}

	final ObjectMap<String, Boolean> select = ObjectMap.of(
			"tile", true,
			"building", false,
			"floor", false,
			"unit", false
	);
	public Dialog frag;
	public Table pane, functions;
	Team defaultTeam;
	boolean show = false, move = false;
	float x1, y1, x2, y2;

	static final int buttonWidth = 200, buttonHeight = 45;
	Function<Tile> tiles;
	Function<Building> buildings;
	Function<Tile> floors;
	Function<Unit> units;
	public static ObjectMap<String, Function<?>> all = new ObjectMap<>();

	public void loadSettings() {
		Table table = new Table();
		table.add(this.localizedName()).color(Pal.accent).growX().left().row();
		table.table(t -> {
			t.left().defaults().left();
			all.each((k, func) -> {
				func.setting(t);
			});
		}).growX().left().padLeft(16.0f).row();
		table.table(t -> {
			this.defaultTeam = Team.get((Integer) Core.settings.get(this.getSettingName() + "-defaultTeam", 1));
			t.left().defaults().left();
			t.add("默认队伍").color(Pal.accent).growX().left().row();
			t.table(t1 -> {
				t1.left().defaults().left();
				Team[] arr = Team.baseTeams;
				int c = 0;

				for (Team team : arr) {
					ImageButton b = t1.button(IntUI.whiteui, Styles.clearNoneTogglei, 32.0f, () -> {
						Core.settings.put(this.getSettingName() + "-defaultTeam", (this.defaultTeam = team).id);
					}).size(42.0f).get();
					b.getStyle().imageUp = IntUI.whiteui.tint(team.color);
					b.update(() -> {
						b.setChecked(this.defaultTeam == team);
					});
					++c;
					if (c % 3 == 0) {
						t1.row();
					}
				}

			}).growX().left().padLeft(16.0f);
		}).growX().left().padLeft(16.0f);
		Contents.settings.add(table);
	}

	public void load() {
		frag = new Dialog() {
			public void draw() {
				Lines.stroke(4.0f);
				Draw.color(Pal.accentBack);
				Rect r = new Rect(Math.min(x1, x2), Math.min(y1, y2) - 1.0f, Math.abs(x1 - x2), Math.abs(y1 - y2));
				Lines.rect(r);
				++r.y;
				Draw.color(Pal.accent);
				Lines.rect(r);
			}
		};
		frag.background(Tex.button);
		frag.touchable = Touchable.enabled;
		frag.setFillParent(true);
		int maxH = 400;
		InputListener listener = new InputListener() {
			public boolean keyDown(InputEvent event, KeyCode keycode) {
				if (keycode == KeyCode.escape) {
					hide();
				}

				return false;
			}

			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (button != KeyCode.mouseLeft) {
					hide();
					move = false;
					return false;
				} else {
					x1 = x2 = x;
					y1 = y2 = y;
					move = true;
					Time.run(2.0f, () -> {
						move = true;
					});
					return show;
				}
			}

			public void touchDragged(InputEvent event, float x, float y, int pointer) {
				x2 = x;
				y2 = y;
			}

			public void touchUp(InputEvent event, float _x, float _y, int pointer, KeyCode button) {
				if (move) {
					float mx = x2;
					float my = y2;
					float tmp;
					if (x1 > x2) {
						tmp = x2;
						x2 = x1;
						x1 = tmp;
					}

					if (y1 > y2) {
						tmp = y2;
						y2 = y1;
						y1 = tmp;
					}

					if (!(x2 - x1 < 8.0f) && !(y2 - y1 < 8.0f)) {
						if (!Core.input.alt()) {
							tiles.clearList();
							buildings.clearList();
							units.clearList();
						}

						Vec2 v1 = Core.camera.unproject(x1, y1).cpy();
						Vec2 v2 = Core.camera.unproject(x2, y2).cpy();
						if (select.get("unit")) {
							Rect rect = new Rect(v1.x, v1.y, v2.x - v1.x, v2.y - v1.y);
							Groups.unit.each(unit -> {
								return rect.contains(unit.getX(), unit.getY());
							}, unit -> {
								if (!units.list.contains(unit)) {
									units.list.add(unit);
								}

							});
						}

						for (float y = v1.y; y < v2.y; y += 8.0f) {
							for (float x = v1.x; x < v2.x; x += 8.0f) {
								Tile tile = Vars.world.tileWorld(x, y);
								if (tile != null) {
									if ((select.get("tile") || select.get("floor")) && !tiles.list.contains(tile)) {
										tiles.list.add(tile);
									}

									if (select.get("building") && tile.build != null && !buildings.list.contains(tile.build)) {
										buildings.list.add(tile.build);
									}
								}
							}
						}

						pane.touchable = Touchable.enabled;
						pane.visible = true;
						pane.setPosition(Mathf.clamp(mx, 0.0f, (float) Core.graphics.getWidth() - pane.getPrefWidth()), Mathf.clamp(my, 0.0f, (float) Core.graphics.getHeight() - pane.getPrefHeight()));
						frag.hide();
						show = false;
					} else {
						hide();
					}
				}
			}
		};
		Core.scene.addListener(listener);
		int W = buttonWidth;
		functions = new Table();
		functions.defaults().width((float) W);
		pane = new Table();
		pane.table(right -> {
			Image img = right.image().color(Color.sky).size((float) (W - 32), 32.0f).get();
			new MoveListener(img, pane);
			right.button(Icon.cancel, Styles.clearTogglei, this::hide).size(32.0f);
		}).fillX().row();
		ScrollPaneStyle paneStyle = new ScrollPaneStyle();
		paneStyle.background = Styles.none;
		pane.table(t -> {
			t.pane(paneStyle, functions).fillX().fillY();
		}).size((float) W, (float) maxH).get().background(Styles.black5);
		pane.left().bottom().defaults().width((float) W);
		pane.visible = false;
		pane.update(() -> {
			if (Vars.state.isMenu()) {
				hide();
			}

		});

		tiles = new TileFunction<>("tile", (t, func) -> {
			FunctionBuild(t, "设置", button -> {
				IntUI.showSelectImageTable(button, Vars.content.blocks(), () -> null, block -> {
					func.each(tile -> {
						if (tile.block() != block) {
							tile.setBlock(block, tile.block() != Blocks.air ? tile.team() : defaultTeam);
						}

					});
				}, 42.0f, 32, 6, true);
			});
			FunctionBuild(t, "清除", __ -> {
				func.each(Tile::setAir);
			});
		});

		buildings = new BuildFunction<>("building", (t, func) -> {
			FunctionBuild(t, "无限血量", __ -> {
				func.each(b -> {
					b.health = Float.POSITIVE_INFINITY;
				});
			});
			TeamFunctionBuild(t, "设置队伍", team -> {
				func.each(b -> {
					b.changeTeam(team);
				});
			});
			ListFunction(t, "设置物品", Vars.content.items(), (button, item) -> {
				IntUI.showSelectTable(button, (table, hide, str) -> {
					String[] amount = new String[1];
					table.field("", s -> {
						amount[0] = s;
					});
					table.button("", Icon.ok, Styles.cleart, () -> {
						func.each(b -> {
							if (b.items != null) {
								b.items.set(item, IntFunc.parseInt(amount[0]));
							}
						});
						hide.run();
					});
				}, false);
			});
			ListFunction(t, "设置液体", Vars.content.liquids(), (button, liquid) -> {
				IntUI.showSelectTable(button, (table, hide, str) -> {
					String[] amount = new String[1];
					table.field("", s -> {
						amount[0] = s;
					});
					table.button("", Icon.ok, Styles.cleart, () -> {
						func.each(b -> {
							if (b.liquids != null) {
								float now = b.liquids.get(liquid);
								b.liquids.add(liquid, IntFunc.parseFloat(amount[0]) - now);
							}
						});
						hide.run();
					});
				}, false);
			});
			FunctionBuild(t, "杀死", __ -> {
				func.each(Building::kill);
			});
			FunctionBuild(t, "清除", __ -> {
				func.each(Building::remove);
			});
		});

		floors = new TileFunction<>("floor", (t, __) -> {
			ListFunction(t, "Set Floor Reset Overlay", Vars.content.blocks().select(block -> block instanceof Floor), (button, floor) -> {
				tiles.each(tile -> {
					tile.setFloor((Floor) floor);
				});
			});
			ListFunction(t, "Set Floor Preserving Overlay", Vars.content.blocks().select(block -> block instanceof Floor && !(block instanceof OverlayFloor)), (button, floor) -> {
				tiles.each(tile -> {
					tile.setFloorUnder((Floor) floor);
				});
			});
			ListFunction(t, "Set Overlay", Vars.content.blocks().select(block -> block instanceof OverlayFloor), (button, overlay) -> {
				tiles.each(tile -> {
					tile.setOverlay(overlay);
				});
			});
		});
		floors.list = tiles.list;

		units = new UnitFunction<>("unit", (t, func) -> {
			FunctionBuild(t, "无限血量", __ -> {
				func.each(unit -> {
					unit.health(Float.POSITIVE_INFINITY);
				});
			});
			TeamFunctionBuild(t, "设置队伍", team -> {
				func.each(unit -> {
					unit.team(team);
				});
			});
			FunctionBuild(t, "杀死", __ -> {
				func.each(Unitc::kill);
			});
			FunctionBuild(t, "清除", __ -> {
				func.each(Unitc::remove);
			});
		});

		Core.scene.root.addChildAt(10, pane);
		btn.setDisabled(() -> Vars.state.isMenu());
		loadSettings();
	}

	public void hide() {
		frag.hide();
		show = false;
		pane.visible = false;
		pane.touchable = Touchable.disabled;
	}

	public void build() {
		show = true;
		frag.show();
	}

	public <T extends UnlockableContent> void ListFunction(Table t, String name, Seq<T> list, Cons2<TextButton, T> cons) {
		FunctionBuild(t, name, btn -> {
			IntUI.showSelectImageTable(btn, list, () -> null, item -> {
				cons.get(btn, item);
			}, 42.0f, 32, 6, true);
		});
	}

	public void FunctionBuild(Table table, String name, Cons<TextButton> cons) {
		TextButton button = new TextButton(name);
		table.add(button).height(buttonHeight).growX().row();
		button.clicked(() -> {
			cons.get(button);
		});
	}

	public void TeamFunctionBuild(Table table, String name, Cons<Team> cons) {
		FunctionBuild(table, name, btn -> {
			Team[] arr = Team.baseTeams;
			Seq<Drawable> icons = new Seq<>();

			for (Team team : arr) {
				icons.add(IntUI.whiteui.tint(team.color));
			}

			IntUI.showSelectImageTableWithIcons(btn, new Seq<>(arr), icons, () -> null, cons, 42.0f, 32.0f, 3, false);
		});
	}

	public class UnitFunction<T extends Unit> extends Function<T> {
		public UnitFunction(String name, Cons2<Table, Function<T>> cons) {
			super(name, cons);
		}

		public void buildTable(T item, Table table) {
			table.image(item.type().uiIcon).row();
			table.add("x:" + item.x).padRight(6.0f);
			table.add("y:" + item.y);
		}
	}

	public class BuildFunction<T extends Building> extends Function<T> {
		public BuildFunction(String name, Cons2<Table, Function<T>> cons) {
			super(name, cons);
		}

		public void buildTable(T item, Table table) {
			Table cont = new Table();
			item.display(cont);
			table.add(cont).row();
			Table pos = new Table(t -> {
				t.add("x:" + item.x).padRight(6.0f);
				t.add("y:" + item.y);
			});
			table.add(pos).row();
		}
	}

	public class TileFunction<T extends Tile> extends Function<T> {
		public TileFunction(String name, Cons2<Table, Function<T>> cons) {
			super(name, cons);
		}

		public void buildTable(T item, Table table) {
			item.display(table);
			table.row();
			table.add("x:" + item.x).padRight(6.0f);
			table.add("y:" + item.y);
		}
	}

	public abstract class Function<T> {
		public final Table wrap;
		public final Table main;
		public final Table cont;
		public ArrayList<T> list = new ArrayList<>();
		public final String name;

		public Function(String name, Cons2<Table, Function<T>> cons) {
			this.name = name;
			wrap = new Table();
			main = new Table();
			cont = new Table();
			cons.get(cont, this);
			functions.add(wrap).padTop(10.0f).row();
			main.image().color(Color.white).height(3.0f).padTop(3.0f).padBottom(3.0f).fillX().row();
			main.add(name).growX().left().row();
			main.button("show all", IntStyles.cleart, this::showAll).growX().height(buttonHeight).row();
			main.add(cont).width(buttonWidth);
			select.put(name, (Boolean) Core.settings.get(getSettingName() + "-" + name, select.get(name)));
			if (select.get(name)) {
				setup();
			} else {
				remove();
			}

			all.put(name, this);
		}

		public void setting(Table t) {
			t.check(name, select.get(name), b -> {
				if (b) {
					setup();
				} else {
					remove();
				}

				hide();
				select.put(name, b);
				Core.settings.put(getSettingName() + "-" + name, b);
			});
		}

		public void remove() {
			wrap.clearChildren();
		}

		public void each(Consumer<? super T> action) {
			list.forEach(action);
		}

		public void clearList() {
			list.clear();
		}

		public void setup() {
			wrap.add(main);
		}

		public void showAll() {
			final int[] c = new int[]{0};
			final int cols = Vars.mobile ? 4 : 6;
			new BaseDialog(name) {{
				cont.pane(table -> {
					list.forEach(item -> {
						Table cont = new Table(Tex.button);
						table.add(cont);
						buildTable(item, cont);
						cont.row();
						cont.button("更多信息", IntStyles.cleart, () -> {
							JSFunc.showInfo(item);
						}).fillX().height(buttonHeight);
						if (++c[0] % cols == 0) {
							table.row();
						}

					});
				}).fillX().fillY();
				addCloseButton();
			}}.show();
		}

		public void buildTable(T item, Table table) {
		}
	}
}
