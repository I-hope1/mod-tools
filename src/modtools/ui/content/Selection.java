
package modtools.ui.content;

import arc.Core;
import arc.func.Cons;
import arc.func.Cons2;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.ui.ImageButton;
import arc.scene.ui.ScrollPane.ScrollPaneStyle;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.Vars;
import mindustry.ctype.UnlockableContent;
import mindustry.entities.units.UnitController;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.environment.OverlayFloor;
import modtools.ui.Contents;
import modtools.ui.IntStyles;
import modtools.ui.IntUI;
import modtools.ui.components.Window;
import modtools.utils.JSFunc;
import modtools.utils.Tools;
import modtools.utils.WorldDraw;
import modtools_lib.MyReflect;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.function.Consumer;

import static mindustry.Vars.tilesize;
import static modtools.utils.WorldDraw.drawRegion;
import static modtools.utils.WorldDraw.rect;

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
	public WorldDraw unitWD = new WorldDraw(Layer.weather), tileWD = new WorldDraw(Layer.darkness + 1),
			buildWD = new WorldDraw(Layer.darkness), otherWD = new WorldDraw(Layer.overlayUI);
	public Element fragSelect;
	public Window pane;
	public Table functions;
	Team defaultTeam;
	// show: pane????????????
	// move: ????????????
	boolean show = false, move = false;
	boolean drawSelect = true;

	static final int buttonWidth = 200, buttonHeight = 45;
	Function<Tile> tiles;
	Function<Building> buildings;
	Function<Tile> floors;
	Function<Unit> units;
	public static ObjectMap<String, Function<?>> all = new ObjectMap<>();

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
			t.add("????????????").color(Pal.accent).growX().left().row();
			t.table(t1 -> {
				t1.left().defaults().left();
				Team[] arr = Team.baseTeams;
				int c = 0;

				for (Team team : arr) {
					ImageButton b = t1.button(IntUI.whiteui, Styles.clearNoneTogglei/*Styles.clearTogglei*/, 32.0f, () -> {
						Core.settings.put(this.getSettingName() + "-defaultTeam", (this.defaultTeam = team).id);
					}).size(42).get();
					b.getStyle().imageUp = IntUI.whiteui.tint(team.color);
					b.update(() -> {
						b.setChecked(this.defaultTeam == team);
					});
					++c;
					if (c % 3 == 0) {
						t1.row();
					}
				}

			}).growX().left().padLeft(16);
		}).growX().left().padLeft(16).row();
		table.table(t -> {
			t.left().defaults().left();
			t.check("????????????????????????", drawSelect, b -> drawSelect = b);
		}).growX().left().padLeft(16).row();
		Contents.settings.add(table);
	}

	public void load() {
		fragSelect = new Element();
		fragSelect.update(() -> fragSelect.setZIndex(Integer.MAX_VALUE));
		fragSelect.touchable = Touchable.enabled;
		fragSelect.setFillParent(true);

		int maxH = 400;
		InputListener listener = new InputListener() {
			final Vec2 start = new Vec2();
			final Vec2 end = new Vec2();

			public boolean keyDown(InputEvent event, KeyCode keycode) {
				/*if (keycode == KeyCode.escape) {
					hide();
				}*/

				return false;
			}

			public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
				if (button != KeyCode.mouseLeft || Vars.state.isMenu()) {
					hide();
					move = false;
					return false;
				} else {
					end.set(start.set(Core.camera.unproject(x, y)));
					move = true;
					Time.runTask(2f, () -> {
						move = true;
					});
					otherWD.drawSeq.add(() -> {
						if (!show) {
							return false;
						}
						Draw.color(Pal.accent, 0.3f);
						float minX = Mathf.clamp(Math.min(start.x, end.x), rect.x, rect.x + rect.width);
						float minY = Mathf.clamp(Math.min(start.y, end.y), rect.y, rect.y + rect.height);
						float maxX = Mathf.clamp(Math.max(start.x, end.x), rect.x, rect.x + rect.width);
						float maxY = Mathf.clamp(Math.max(start.y, end.y), rect.y, rect.y + rect.height);

						Fill.crect(minX, minY, maxX - minX, maxY - minY);
						return true;
					});
					return show;
				}
			}

			public void touchDragged(InputEvent event, float x, float y, int pointer) {
				end.set(Core.camera.unproject(x, y));
			}

			public void touchUp(InputEvent event, float mx, float my, int pointer, KeyCode button) {
				btn.setChecked(false);

				if (!move) return;
				float tmp;
				if (start.x > end.x) {
					tmp = start.x;
					start.x = end.x;
					end.x = tmp;
				}
				if (start.y > end.y) {
					tmp = start.y;
					start.y = end.y;
					end.y = tmp;
				}
				fragSelect.visible = false;

				if (!Core.input.alt()) {
					tiles.clearList();
					buildings.clearList();
					units.clearList();
				}

				if (select.get("unit")) {
					Groups.unit.each(unit -> {
						// ????????????????????????????????????
						return start.x <= unit.x && end.x >= unit.x && start.y <= unit.y && end.y >= unit.y;
					}, unit -> {
						if (!units.list.contains(unit)) {
							units.add(unit);
						}
					});
				}

				float minX = Mathf.clamp(start.x, 0, Vars.world.unitWidth());
				float maxX = Mathf.clamp(end.x, 0, Vars.world.unitWidth());
				float minY = Mathf.clamp(start.y, 0, Vars.world.unitHeight());
				float maxY = Mathf.clamp(end.y, 0, Vars.world.unitHeight());
				for (float y = minY; y < maxY; y += tilesize) {
					for (float x = minX; x < maxX; x += tilesize) {
						Tile tile = Vars.world.tileWorld(x, y);
						if (tile != null) {
							if ((select.get("tile") || select.get("floor")) && !tiles.list.contains(tile)) {
								tiles.add(tile);
							}

							if (select.get("building") && tile.build != null && !buildings.list.contains(tile.build)) {
								buildings.add(tile.build);
							}
						}
					}
				}

				if (!pane.isShown()) {
					pane.show();
					pane.setPosition(Mathf.clamp(mx, 0f, Core.graphics.getWidth() - pane.getPrefWidth()), Mathf.clamp(my, 0f, Core.graphics.getHeight() - pane.getPrefHeight()));
				}
				show = false;
//				start = end = null;
			}
		};
		Core.scene.add(fragSelect);
		Core.scene.addListener(listener);

		/*fragDraw = new FragDraw();
		Core.scene.add(fragDraw);*/

		final int W = buttonWidth;
		functions = new Table();
		functions.defaults().width(W);
		pane = new Window("??????", W - 64/* two buttons */, maxH, true);
		pane.hidden(this::hide);
		ScrollPaneStyle paneStyle = new ScrollPaneStyle();
		paneStyle.background = Styles.none;
		pane.cont.table(t -> {
			t.pane(paneStyle, functions).fillX().fillY();
		});
//		pane.cont.left().bottom().defaults().width(W);
		pane.update(() -> {
			if (Vars.state.isMenu()) {
				pane.hide();
			}
		});

		tiles = new TileFunction<>("tile", (t, func) -> {
			FunctionBuild(t, "??????", button -> {
				IntUI.showSelectImageTable(button, Vars.content.blocks(), () -> null, block -> {
					func.each(tile -> {
						if (tile.block() != block) {
							tile.setBlock(block, tile.build != null ? tile.team() : defaultTeam);
						}

					});
				}, 42.0f, 32, 6, true);
			});
			FunctionBuild(t, "??????", __ -> {
				func.each(Tile::setAir);
			});
		});

		buildings = new BuildFunction<>("building", (t, func) -> {
			FunctionBuild(t, "????????????", __ -> {
				func.each(b -> {
					b.health = Float.POSITIVE_INFINITY;
				});
			});
			TeamFunctionBuild(t, "????????????", team -> {
				func.each(b -> {
					b.changeTeam(team);
				});
			});
			ListFunction(t, "????????????", Vars.content.items(), (button, item) -> {
				IntUI.showSelectTable(button, (table, hide, str) -> {
					String[] amount = new String[1];
					table.field("", s -> {
						amount[0] = s;
					}).valid(Tools::validPosInt);

					table.button("", Icon.ok, Styles.cleart, () -> {
						func.each(b -> {
							if (b.items != null) {
								b.items.set(item, Tools.asInt(amount[0]));
							}
						});
						hide.run();
					});
				}, false);
			});
			ListFunction(t, "????????????", Vars.content.liquids(), (button, liquid) -> {
				IntUI.showSelectTable(button, (table, hide, str) -> {
					String[] amount = new String[1];
					table.field("", s -> {
						amount[0] = s;
					}).valid(Tools::validPosInt);
					table.button("", Icon.ok, Styles.cleart, () -> {
						func.each(b -> {
							if (b.liquids != null) {
								float now = b.liquids.get(liquid);
								b.liquids.add(liquid, Tools.asInt(amount[0]) - now);
							}
						});
						hide.run();
					});
				}, false);
			});
			FunctionBuild(t, "??????", __ -> {
				func.each(Building::kill);
			});
			FunctionBuild(t, "??????", __ -> {
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
			FunctionBuild(t, "????????????", __ -> {
				func.each(unit -> {
					unit.health(Float.POSITIVE_INFINITY);
				});
			});
			TeamFunctionBuild(t, "????????????", team -> {
				func.each(unit -> {
					unit.team(team);
				});
			});
			FunctionBuild(t, "??????", __ -> {
				func.each(Unit::kill);
			});
			FunctionBuild(t, "??????", __ -> {
				func.each(Unit::remove);
			});
			FunctionBuild(t, "????????????", __ -> {
				func.each(u -> {
					u.remove();
					if (!Groups.unit.contains(unit -> unit == u)) return;
					Groups.all.remove(u);
					Groups.unit.remove(u);
					Groups.sync.remove(u);
					Groups.draw.remove(u);
					u.team.data().updateCount(u.type, -1);
					try {
						addedField.setBoolean(u, false);
						((UnitController) controller.get(u)).removed(u);
					} catch (Exception ignored) {}
				});
			});
		});

//		pane.show();
		btn.setDisabled(() -> Vars.state.isMenu());
		loadSettings();

		btn.setStyle(Styles.logicTogglet);
	}

	public static Field addedField, controller;

	static {
		try {
			addedField = UnitEntity.class.getDeclaredField("added");
			MyReflect.setOverride(addedField);
			controller = UnitEntity.class.getDeclaredField("controller");
			MyReflect.setOverride(controller);
		} catch (Throwable e) {
			throw new RuntimeException(e);
		}
	}

	public void hide() {
		fragSelect.visible = false;
		show = false;
//		pane.visible = false;
//		pane.touchable = Touchable.disabled;
		btn.setChecked(false);

//		if (!Core.input.alt()) {
		tiles.clearList();
		buildings.clearList();
		units.clearList();
//		}
	}

	public void build() {
		show = true;
		fragSelect.visible = true;
//		fragSelect.touchable = Touchable.enabled;
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

		public void buildTable(T unit, Table table) {
			table.image(unit.type().uiIcon).row();
			table.add("x:" + unit.x).padRight(6.0f);
			table.add("y:" + unit.y);
		}

		public ObjectMap<Float, TextureRegion> map = new ObjectMap<>();

		@Override
		public void add(T unit) {
			super.add(unit);
			if (!drawSelect) return;
			float rsize = unit.hitSize * 1.4f * 4;
			TextureRegion region = map.get(unit.hitSize, () -> {
				int size = (int) (rsize * 2);
				float thick = 12f;
				return drawRegion(size, size, () -> {
					MyDraw.square(size / 2f, size / 2f, size * 2 / (float) tilesize - 1, thick, Color.sky);
				});
			});
			unitWD.drawSeq.add(() -> {
				if (!unit.isAdded()) {
					return false;
				}
				if (!rect.contains(unit.x, unit.y)) return true;
				Draw.rect(region, unit.x, unit.y);
				return true;
			});
		}

		@Override
		public void clearList() {
			super.clearList();
			if (!unitWD.drawSeq.isEmpty()) unitWD.drawSeq.clear();
		}
	}

	public class BuildFunction<T extends Building> extends Function<T> {
		public BuildFunction(String name, Cons2<Table, Function<T>> cons) {
			super(name, cons);
		}

		public void buildTable(T building, Table table) {
			Table cont = new Table();
			building.display(cont);
			table.add(cont).row();
			Table pos = new Table(t -> {
				t.add("x:" + building.x).padRight(6.0f);
				t.add("y:" + building.y);
			});
			table.add(pos).row();
		}

		public ObjectMap<Integer, TextureRegion> map = new ObjectMap<>();

		@Override
		public void add(T building) {
			super.add(building);
			if (!drawSelect) return;
			TextureRegion region = map.get(building.block.size, () -> {
				int size = building.block.size * 32;
				int thick = 7;
				return drawRegion(size + thick, size + thick, () -> {
					MyDraw.dashSquare(thick, Pal.accent, (size + thick) / 2f, (size + thick) / 2f, size);
				});
			});

			buildWD.drawSeq.add(() -> {
				if (building.tile.build != building) {
//					buildWD.hasChange = false;
					return false;
				}
				if (!rect.contains(building.x, building.y)) return true;
				Draw.rect(region, building.x, building.y);
				return true;
			});
		}

		@Override
		public void clearList() {
			super.clearList();
			if (!buildWD.drawSeq.isEmpty()) buildWD.drawSeq.clear();
		}
	}

	public class TileFunction<T extends Tile> extends Function<T> {
		public TileFunction(String name, Cons2<Table, Function<T>> cons) {
			super(name, cons);
		}

		public void buildTable(T tile, Table table) {
			tile.display(table);
			table.row();
			table.add("x:" + tile.x).padRight(6.0f);
			table.add("y:" + tile.y);
		}

		public ObjectMap<Integer, TextureRegion> map = new ObjectMap<>();

		@Override
		public void add(T tile) {
			super.add(tile);
			if (!drawSelect) return;
			TextureRegion region = map.get(tile.block().size, () -> {
				int size = 1 * tilesize * 4;
				int thick = 7;
				return drawRegion(size + thick, size + thick, () -> {
					MyDraw.dashSquare(thick, Pal.accentBack, (size + thick) / 2f, (size + thick) / 2f, size);
				});
			});
			tileWD.drawSeq.add(() -> {
				if (!rect.contains(tile.worldx(), tile.worldy())) return true;
				Draw.rect(region, tile.worldx(), tile.worldy());
				return true;
			});
		}

		@Override
		public void clearList() {
			super.clearList();
			if (!tileWD.drawSeq.isEmpty()) {
				tileWD.drawSeq.clear();
			}
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
						cont.button("????????????", IntStyles.cleart, () -> {
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

		public void add(T item) {
			list.add(item);
		}
	}

	public static class MyDraw {
		public static void dashLine(float thick, Color color, float x, float y, float x2, float y2, int segments) {
			Lines.stroke(thick);
			Draw.color(Pal.gray, color.a);
			Lines.dashLine(x, y, x2, y2, segments);
			Lines.stroke(thick / 3f, color);
			Lines.dashLine(x, y, x2, y2, segments);
			Draw.reset();
//			Log.info(segments);
		}

		public static void dashLine(float thick, Color color, float x, float y, float x2, float y2) {
			dashLine(thick, color, x, y, x2, y2, (int) (Math.max(Math.abs(x - x2), Math.abs(y - y2)) / 5f));
		}

		public static void dashRect(float thick, Color color, float x, float y, float width, float height) {
			dashLine(thick, color, x, y, x + width, y);
			dashLine(thick, color, x + width, y, x + width, y + height);
			dashLine(thick, color, x + width, y + height, x, y + height);
			dashLine(thick, color, x, y + height, x, y);
		}

		public static void dashSquare(float thick, Color color, float x, float y, float size) {
			dashRect(thick, color, x - size / 2f, y - size / 2f, size, size);
		}

		public static void square(float x, float y, float radius, float rotation, float thick, Color color) {
			Lines.stroke(thick, Pal.gray);
			Lines.square(x, y, radius + 1f, rotation);
			Lines.stroke(thick / 3f, color);
			Lines.square(x, y, radius + 1f, rotation);
			Draw.reset();
		}

		public static void square(float x, float y, float radius, float thick, float rotation) {
			square(x, y, radius, rotation, thick, Pal.accent);
		}

		public static void square(float x, float y, float radius, float thick, Color color) {
			square(x, y, radius, 45, thick, color);
		}
	}

	/*public static class MyEffect<T> extends Effect {
		public EffectState entity;

		public MyEffect(T item, float x, float y, Cons<EffectContainer> renderer) {
			super(Float.POSITIVE_INFINITY, renderer);
			init();

			entity = EffectState.create();
			entity.effect = this;
			entity.rotation = baseRotation;
			entity.data = item;
			entity.lifetime = lifetime;
			entity.set(x, y);
			entity.color.set(Color.white);

			entity.add();
		}
	}*/
}
