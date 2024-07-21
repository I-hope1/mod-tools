package modtools.ui.content;

import arc.Core;
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
import arc.scene.ui.*;
import arc.scene.ui.ScrollPane.ScrollPaneStyle;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.environment.OverlayFloor;
import modtools.ui.Contents;
import modtools.ui.IntFunc;
import modtools.ui.IntUI;
import modtools.ui.comp.MoveListener;

import java.util.ArrayList;
import java.util.function.Consumer;

public class Selection extends Content {

	public Selection() {
		super("selection");
	}

	final ObjectMap<String, Boolean> select = ObjectMap.of(
			"tile", true, "building", false, "floor", false, "unit", false);

	public Dialog frag;
	public Table pane, functions;
	Team defaultTeam;
	boolean show = false, move = false;
	float x1, y1, x2, y2;
	static final int buttonWidth = 200, buttonHeight = 45;

	Function<Tile> tiles;
	Function<Building> buildings;
	Function<Floor> floors;
	Function<Unit> units;

	@Override
	public void loadSettings() {
		Table table = new Table();
		table.add(localizedName()).color(Pal.accent).growX().left().row();
		table.table(t -> {
			t.left().defaults().left();
			all.each((k, func) -> {
				func.setting(t);
			});
		}).growX().left().padLeft(16).row();
		table.table(t -> {
			defaultTeam = Team.get((int) Core.settings.get(getSettingName() + "-defaultTeam", 1));

			t.left().defaults().left();
			t.add("默认队伍").color(Pal.accent).growX().left().row();
			t.table(t1 -> {
				t1.left().defaults().left();
				Team[] arr = Team.baseTeams;

				int c = 0;
				for (Team team : arr) {
					ImageButton b = t1.button(IntUI.whiteui, Styles.clearToggleTransi, 32,
									() -> Core.settings.put(getSettingName() + "-defaultTeam", (defaultTeam = team).id))
							.size(42).get();
					b.getStyle().imageUp = IntUI.whiteui.tint(team.color);
					b.update(() -> {
						b.setChecked(defaultTeam == team);
					});
					if (++c % 3 == 0)
						t1.row();
				}

			}).growX().left().padLeft(16);
		}).growX().left().padLeft(16);
		Contents.settings.add(table);
	}

	@Override
	public void load() {
		frag = new Dialog() {
			public void draw() {
				Lines.stroke(4f);

				Draw.color(Pal.accentBack);
				Rect r = new Rect(Math.min(x1, x2), Math.min(y1, y2) - 1, Math.abs(x1 - x2), Math.abs(y1 - y2));
				Lines.rect(r);
				r.y += 1f;
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
				}
				x1 = x2 = x;
				y1 = y2 = y;
				move = true;
				Time.run(2, () -> move = true);
				return show;
			}

			public void touchDragged(InputEvent event, float x, float y, int pointer) {
				x2 = x;
				y2 = y;
			}

			public void touchUp(InputEvent event, float _x, float _y, int pointer, KeyCode button) {
				if (!move)
					return;
				float mx = x2, my = y2, tmp;
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
				if (x2 - x1 < Vars.tilesize || y2 - y1 < Vars.tilesize) {
					hide();
					return;
				}

				if (!Core.input.alt()) {
					tiles.clearList();
					buildings.clearList();
					units.clearList();
				}

				Vec2 v1 = Core.camera.unproject(x1, y1).cpy();
				Vec2 v2 = Core.camera.unproject(x2, y2).cpy();
				if (select.get("unit")) {
					Rect rect = new Rect(v1.x, v1.y, v2.x - v1.x, v2.y - v1.y);
					Groups.unit.each(unit -> rect.contains(unit.getX(), unit.getY()), unit -> {
						if (!units.list.contains(unit)) units.list.add(unit);
					});
				}
				for (float y = v1.y; y < v2.y; y += Vars.tilesize) {
					for (float x = v1.x; x < v2.x; x += Vars.tilesize) {
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
				pane.setPosition(
						Mathf.clamp(mx, 0f, Core.graphics.getWidth() - pane.getPrefWidth()),
						Mathf.clamp(my, 0f, Core.graphics.getHeight() - pane.getPrefHeight()));
				frag.hide();
				show = false;
			}
		};
		Core.scene.addListener(listener);

		int W = buttonWidth, H = buttonHeight;

		functions = new Table();
		functions.defaults().width(W);

		pane = new Table();
		pane.table(right -> {
			Image img = right.image().color(Color.sky).size(W - 32, 32).get();
			new MoveListener(img, pane);
			// right.right().defaults().right();
			right.button(Icon.cancel, Styles.clearTransi, this::hide).size(32);
		}).fillX().row();
		ScrollPaneStyle paneStyle = new ScrollPaneStyle();
		paneStyle.background = Styles.none;

		pane.table(t -> t.pane(paneStyle, functions).fillX().fillY())
				.size(W, maxH).get().background(Styles.black5);

		pane.left().bottom().defaults().width(W);
		pane.visible = false;
		pane.update(() -> {
			if (Vars.state.isMenu())
				hide();
		});

		tiles = new Function<>("tile", (t, func) -> {
			Button btn1 = FunctionButton(t, "设置", () -> {});
			btn1.clicked(() -> IntUI.showSelectImageTable(btn1, Vars.content.blocks(), () -> null,
					block -> func.each(tile -> {
						if (tile.block() != block)
							tile.setBlock(block, tile.block() != Blocks.air ? tile.team() : defaultTeam);
					}), 42, 32, 6, true));

			FunctionButton(t, "清除", () -> func.each(Tile::setAir));
		});

		buildings = new Function<>("building", (t, func) -> {
			FunctionButton(t, "无限血量", () -> func.each(b -> b.health = Float.POSITIVE_INFINITY));
			Button btn1 = FunctionButton(t, "队伍", () -> {});
			btn1.clicked(() -> {
				Team[] arr = Team.baseTeams;
				Seq<Drawable> icons = new Seq<>();

				for (Team team : arr) {
					icons.add(IntUI.whiteui.tint(team.color));
				}
				IntUI.showSelectImageTableWithIcons(btn1, new Seq<>(arr), icons, () -> null,
						team -> func.each(b -> b.changeTeam(team)), 42, 32, 3, false);
			});

			Button btn2 = FunctionButton(t, "设置物品", () -> {});
			btn2.clicked(() -> {
				IntUI.showSelectImageTable(btn2, Vars.content.items(), () -> null, item -> {
					IntUI.showSelectTable(btn2, (table, hide, str) -> {
						String[] amount = new String[1];
						table.field("", s -> amount[0] = s);
						table.button("", Icon.ok, Styles.clearTogglet, () -> {
							func.each(b -> {
								if (b.items != null)
									b.items.set(item, IntFunc.parseInt(amount[0]));
							});
							hide.run();
						});
					}, false);
				}, 42, 32, 6, true);
			});

			Button btn3 = FunctionButton(t, "设置液体", () -> {});
			btn3.clicked(() -> {
				IntUI.showSelectImageTable(btn3, Vars.content.liquids(), () -> null, liquid -> {
					IntUI.showSelectTable(btn3, (table, hide, str) -> {
						String[] amount = new String[1];
						table.field("", s -> amount[0] = s);
						table.button("", Icon.ok, Styles.clearTogglet, () -> {
							func.each(b -> {
								if (b.liquids == null)
									return;
								float now = b.liquids.get(liquid);
								b.liquids.add(liquid, IntFunc.parseFloat(amount[0]) - now);
							});
							hide.run();
						});
					}, false);
				}, 42, 32, 6, true);
			});

			FunctionButton(t, "杀死", () -> func.each(Building::kill));
			FunctionButton(t, "清除", () -> func.each(Building::remove));
		});

		floors = new Function<>("floor", (t, func) -> {
			TextButton btn1 = FunctionButton(t, "Set Floor Reset Overlay", () -> {});
			btn1.clicked(() -> IntUI.showSelectImageTable(btn1,
					Vars.content.blocks().select(block -> block instanceof Floor),
					() -> null, floor -> tiles.each(tile -> tile.setFloor((Floor) floor)), 42, 32, 6, true));

			TextButton btn2 = FunctionButton(t, "Set Floor Preserving Overlay", () -> {});
			btn2.clicked(() -> IntUI.showSelectImageTable(btn2, Vars.content.blocks()
							.select(block -> block instanceof Floor && !(block instanceof OverlayFloor)), () -> null,
					floor -> tiles.each(tile -> tile.setFloorUnder((Floor) floor)), 42, 32, 6, true));
			t.row();
			TextButton btn3 = FunctionButton(t, "Set Overlay", () -> {
			});
			btn3.clicked(() -> IntUI.showSelectImageTable(btn3,
					Vars.content.blocks().select(block -> block instanceof OverlayFloor),
					() -> null, overlay -> tiles.each(tile -> tile.setOverlay(overlay)), 42, 32, 6, true));
			t.row();
		});

		units = new Function<>("unit", (t, func) -> {
			FunctionButton(t, "无限血量", () -> func.each(u -> u.health(Float.POSITIVE_INFINITY)));
			FunctionButton(t, "杀死", () -> func.each(Unit::kill));
			FunctionButton(t, "清除", () -> func.each(Unit::remove));
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

	@Override
	public void build() {
		show = true;
		frag.show();
	}

	public TextButton FunctionButton(Table t, String text, Runnable run) {
		try {
			return t.button(text, run).height(buttonHeight).growX().get();
		} finally {
			t.row();
		}
	}

	public static ObjectMap<String, Function<?>> all = new ObjectMap<>();

	public class Function<T> {
		public final Table wrap;
		public final Table main;
		public final Table cont;
		public final ArrayList<T> list = new ArrayList<>();
		public final String name;

		public Function(String n, Cons2<Table, Function<T>> cons) {
			name = n;
			wrap = new Table();
			main = new Table();
			cont = new Table();
			cons.get(cont, this);
			functions.add(wrap).row();
			main.image().color(Color.gray).height(2).padTop(3f).padBottom(3f).fillX().row();
			main.add(name).growX().left().row();
			main.add(cont).width(buttonWidth);
			select.put(n, (Boolean) Core.settings.get(getSettingName() + "-" + name, select.get(n)));
			if (select.get(n))
				setup();
			else
				remove();

			all.put(name, this);
		}

		public void setting(Table t) {
			t.check(name, select.get(name), b -> {
				if (b)
					setup();
				else
					remove();
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
	}

}
