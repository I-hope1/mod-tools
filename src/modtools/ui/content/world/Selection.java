
package modtools.ui.content.world;

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
import arc.math.geom.*;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.ScrollPane.ScrollPaneStyle;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.util.*;
import arc.util.Timer;
import arc.util.Timer.Task;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.ctype.UnlockableContent;
import mindustry.entities.units.UnitController;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.environment.OverlayFloor;
import modtools.ui.Contents;
import modtools.ui.IntStyles;
import modtools.ui.IntUI;
import modtools.ui.components.Window;
import modtools.ui.components.Window.DisposableWindow;
import modtools.ui.content.Content;
import modtools.utils.JSFunc;
import modtools.utils.Tools;
import modtools.utils.WorldDraw;
import ihope_lib.MyReflect;

import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Consumer;

import static mindustry.Vars.tilesize;
import static mindustry.Vars.world;
import static modtools.IntVars.topGroup;
import static modtools.utils.MySettings.settings;
import static modtools.utils.WorldDraw.drawRegion;
import static modtools.utils.WorldDraw.rect;

public class Selection extends Content {
	public Selection() {
		super("selection");
	}

	final ObjectMap<String, Boolean> select = ObjectMap.of(
			"tile", settings.getBool(getSettingName() + "-tile", "true"),
			"building", settings.getBool(getSettingName() + "-building", "false"),
			"bullet", settings.getBool(getSettingName() + "-bullet", "false"),
			"unit", settings.getBool(getSettingName() + "-unit", "false")
	);

	public static final Color focusColor = Color.pink.cpy().a(0.4f);
	// public ObjectSet<Object> focusSet = new ObjectSet<>();
	// public Vec2 focusFrom = new Vec2();
	public WorldDraw unitWD = new WorldDraw(Layer.weather), tileWD = new WorldDraw(Layer.darkness + 1),
			buildWD = new WorldDraw(Layer.darkness), bulletWD = new WorldDraw(Layer.bullet + 5),
			otherWD = new WorldDraw(Layer.overlayUI);
	public Element fragSelect;
	public Window pane;
	public Table functions;
	Team defaultTeam;
	// show: pane是否显示
	// move: 是否移动
	boolean show = false, move = false;
	boolean drawSelect = true;

	static final int buttonWidth = 200, buttonHeight = 45;
	Function<Tile> tiles;
	Function<Building> buildings;
	Function<Unit> units;
	Function<Bullet> bullets;
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
			defaultTeam = Team.get(settings.getInt(getSettingName() + "-defaultTeam", 1));
			t.left().defaults().left();
			t.add("默认队伍").color(Pal.accent).growX().left().row();
			t.table(t1 -> {
				t1.left().defaults().left();
				Team[] arr = Team.baseTeams;
				int c = 0;

				for (Team team : arr) {
					ImageButton b = t1.button(IntUI.whiteui, IntStyles.clearNoneTogglei/*Styles.clearTogglei*/, 32.0f, () -> {
						defaultTeam = team;
						settings.put(this.getSettingName() + "-defaultTeam", "" + team.id);
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
			t.check("@settings.focusOnWorld", settings.getBool("focusOnWorld"), b -> settings.put("focusOnWorld", b)).row();
			t.check("@settings.drawSelect", drawSelect, b -> drawSelect = b);
		}).growX().left().padLeft(16).row();

		Contents.settings.add(table);
	}

	public void load() {
		fragSelect = new Element();
		fragSelect.name = "SelectionElem";
		// fragSelect.update(() -> fragSelect.toFront());
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
				fragSelect.remove();

				if (!Core.input.alt()) {
					tiles.clearList();
					buildings.clearList();
					bullets.clearList();
					units.clearList();
				}

				if (select.get("bullet")) {
					Groups.bullet.each(bullet -> {
						// 返回单位是否在所选区域内
						return start.x <= bullet.x && end.x >= bullet.x && start.y <= bullet.y && end.y >= bullet.y;
					}, bullet -> {
						if (!bullets.list.contains(bullet)) {
							bullets.add(bullet);
						}
					});
				}
				if (select.get("unit")) {
					Groups.unit.each(unit -> {
						// 返回单位是否在所选区域内
						return start.x <= unit.x && end.x >= unit.x && start.y <= unit.y && end.y >= unit.y;
					}, unit -> {
						if (!units.list.contains(unit)) {
							units.add(unit);
						}
					});
				}

				float minX = Mathf.clamp(start.x, 0, world.unitWidth());
				float maxX = Mathf.clamp(end.x, 0, world.unitWidth());
				float minY = Mathf.clamp(start.y, 0, world.unitHeight());
				float maxY = Mathf.clamp(end.y, 0, world.unitHeight());
				for (float y = minY; y < maxY; y += tilesize) {
					for (float x = minX; x < maxX; x += tilesize) {
						Tile tile = world.tileWorld(x, y);
						if (tile != null) {
							if (select.get("tile") && !tiles.list.contains(tile)) {
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
		// Core.scene.add(fragSelect);
		fragSelect.addListener(listener);

		/*fragDraw = new FragDraw();
		Core.scene.add(fragDraw);*/

		functions = new Table();
		functions.defaults().width(buttonWidth);
		pane = new Window(localizedName(), 0/* two buttons */, maxH, true);
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
			FunctionBuild(t, "设置", button -> {
				IntUI.showSelectImageTable(button, Vars.content.blocks(), () -> null, block -> {
					func.each(tile -> {
						int offsetx = -(block.size - 1) / 2;
						int offsety = -(block.size - 1) / 2;
						for (int dx = 0; dx < block.size; dx++) {
							for (int dy = 0; dy < block.size; dy++) {
								int worldx = dx + offsetx + tile.x;
								int worldy = dy + offsety + tile.y;
								Tile other = world.tile(worldx, worldy);

								if (other != null && other.block().isMultiblock() && other.block() == block) {
									return;
								}
							}
						}

						tile.setBlock(block, tile.build != null ? tile.team() : defaultTeam);
					});
				}, 42.0f, 32, 6, true);
			});
			FunctionBuild(t, "清除", __ -> {
				func.each(Tile::setAir);
			});
			ListFunction(t, "设置地板重置Overlay", Vars.content.blocks().select(block -> block instanceof Floor), (button, floor) -> {
				tiles.each(tile -> {
					tile.setFloor((Floor) floor);
				});
			});
			ListFunction(t, "设置地板保留Overlay", Vars.content.blocks().select(block -> block instanceof Floor && !(block instanceof OverlayFloor)), (button, floor) -> {
				tiles.each(tile -> {
					tile.setFloorUnder((Floor) floor);
				});
			});
			ListFunction(t, "设置Overlay", Vars.content.blocks().select(block -> block instanceof OverlayFloor || block == Blocks.air), (button, overlay) -> {
				tiles.each(tile -> {
					tile.setOverlay(overlay);
				});
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
					}).valid(Tools::validPosInt);

					table.button("", Icon.ok, IntStyles.cleart, () -> {
						func.each(b -> {
							if (b.items != null) {
								b.items.set(item, Tools.asInt(amount[0]));
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
					}).valid(Tools::validPosInt);
					table.button("", Icon.ok, IntStyles.cleart, () -> {
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
			FunctionBuild(t, "杀死", __ -> {
				func.list.removeIf(b -> {
					if (b.tile.build == b) b.kill();
					return b.tile.build != null;
				});
			});
			FunctionBuild(t, "清除", __ -> {
				func.list.removeIf(b -> {
					if (b.tile != null && b.tile.block() != Blocks.air) {
						b.tile.remove();
					}
					b.remove();
					return b.tile.build != null;
				});
			});
		});

		units = new UnitFunction<>("unit", (t, func) -> {
			FunctionBuild(t, "无限血量", __ -> {
				func.each(unit -> {
					unit.health(Float.POSITIVE_INFINITY);
				});
			});
			TeamFunctionBuild(t, "设置队伍", team -> {
				func.each(unit -> {
					if (Vars.player.unit() == unit) Vars.player.team(team);
					unit.team(team);
				});
			});
			FunctionBuild(t, "杀死", __ -> {
				func.list.removeIf(u -> {
					Call.unitDeath(u.id);
					try {
						return !addedField.getBoolean(u);
					} catch (Exception ignored) {}
					return false;
				});
			});
			FunctionBuild(t, "清除", __ -> {
				func.list.removeIf(u -> {
					u.remove();
					try {
						return !addedField.getBoolean(u);
					} catch (Exception ignored) {}
					return false;
				});
			});
			FunctionBuild(t, "强制清除", __ -> {
				func.list.removeIf(u -> {
					u.remove();
					if (!Groups.unit.contains(unit -> unit == u)) return true;
					Groups.all.remove(u);
					Groups.unit.remove(u);
					Groups.sync.remove(u);
					Groups.draw.remove(u);
					u.team.data().updateCount(u.type, -1);
					try {
						addedField.setBoolean(u, false);
						((UnitController) controller.get(u)).removed(u);
					} catch (Exception ignored) {}
					return true;
				});
			});
		});

		bullets = new BulletFunction<>("bullet", (t, func) -> {

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
		fragSelect.remove();
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
		Core.scene.add(fragSelect);
		//		fragSelect.touchable = Touchable.enabled;
	}

	public static Rect getWorldRect(Tile t) {
		return new Rect(t.worldx(), t.worldy(), tilesize * 4, tilesize * 4);
	}

	public static Rect getWorldRect(Unit unit) {
		return new Rect(unit.x, unit.y, unit.type.fullIcon.width, unit.type.fullIcon.height);
	}

	public static Rect getWorldRect(Building t) {
		TextureRegion region = t.block.region;
		return new Rect(t.x, t.y, region.width, region.height);
	}

	public static Rect getWorldRect(Bullet t) {
		return new Rect(t.x, t.y, t.hitSize, t.hitSize);
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

			IntUI.showSelectImageTableWithIcons(btn, new Seq<>(arr), icons, () -> null, cons, 42f, 32f, 3, false);
		});
	}

	public class BulletFunction<T extends Bullet> extends Function<T> {
		public BulletFunction(String name, Cons2<Table, Function<T>> cons) {
			super(name, cons);
		}

		public void buildTable(T bullet, Table table) {
			table.add(String.valueOf(bullet.type)).row();
			table.label(() -> "(" + bullet.x + ", " + bullet.y + ')');
		}

		public ObjectMap<Float, TextureRegion> map = new ObjectMap<>();

		public TextureRegion getRegion(T bullet) {
			float rsize = bullet.hitSize * 1.4f * 4;
			return map.get(bullet.hitSize, () -> {
				int size = (int) (rsize * 2);
				float thick = 12f;
				return drawRegion(size, size, () -> {
					MyDraw.square(size / 2f, size / 2f, size * 2 / (float) tilesize - 1, thick, Color.sky);
				});
			});
		}

		@Override
		public void add(T bullet) {
			super.add(bullet);
			if (!drawSelect) return;
			TextureRegion region = getRegion(bullet);
			bulletWD.drawSeq.add(() -> {
				if (!bullet.isAdded()) {
					return false;
				}
				if (!rect.contains(bullet.x, bullet.y)) return true;
				Draw.rect(region, bullet.x, bullet.y);
				return true;
			});
		}

		@Override
		public void clearList() {
			super.clearList();
			if (!bulletWD.drawSeq.isEmpty()) bulletWD.drawSeq.clear();
		}
	}

	public class UnitFunction<T extends Unit> extends Function<T> {
		public UnitFunction(String name, Cons2<Table, Function<T>> cons) {
			super(name, cons);
		}

		public void buildTable(T unit, Table table) {
			table.image(unit.type().uiIcon).row();
			// table.label(() -> "(" + unit.x + ", " + unit.y + ')');
		}

		public ObjectMap<Float, TextureRegion> map = new ObjectMap<>();

		public TextureRegion getRegion(T unit) {
			float rsize = unit.hitSize * 1.4f * 4;
			return map.get(unit.hitSize, () -> {
				int size = (int) (rsize * 2);
				float thick = 12f;
				return drawRegion(size, size, () -> {
					MyDraw.square(size / 2f, size / 2f, size * 2 / (float) tilesize - 1, thick, Color.sky);
				});
			});
		}

		@Override
		public void add(T unit) {
			super.add(unit);
			if (!drawSelect) return;
			TextureRegion region = getRegion(unit);
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

		@Override
		public TextureRegion getRegion(T building) {
			return map.get(building.block.size, () -> {
				int size = building.block.size * 32;
				int thick = 7;
				return drawRegion(size + thick, size + thick, () -> {
					MyDraw.dashSquare(thick, Pal.accent, (size + thick) / 2f, (size + thick) / 2f, size);
				});
			});
		}

		public void buildTable(T building, Table table) {
			Table cont = new Table();
			building.display(cont);
			table.add(cont).row();
			String[] pos = {"(" + building.x + ", " + building.y + ')'};
			Vec2 last = new Vec2(building.x, building.y);
			table.label(() -> {
				if (last.x != building.x || last.y != building.y) {
					pos[0] = "(" + building.x + ", " + building.y + ')';
				}
				return pos[0];
			}).padRight(6.0f).row();
		}

		public ObjectMap<Integer, TextureRegion> map = new ObjectMap<>();

		@Override
		public void add(T building) {
			super.add(building);
			if (!drawSelect) return;

			TextureRegion region = getRegion(building);
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

		@Override
		public TextureRegion getRegion(T tile) {
			return map.get(tile.block().size, () -> {
				int size = tilesize * 4;
				int thick = 7;
				return drawRegion(size + thick, size + thick, () -> {
					MyDraw.dashSquare(thick, Pal.accentBack, (size + thick) / 2f, (size + thick) / 2f, size);
				});
			});
		}

		public void buildTable(T tile, Table table) {
			tile.display(table);
			table.row();
			table.add("(" + tile.x + ", " + tile.y + ')');
		}

		public ObjectMap<Integer, TextureRegion> map = new ObjectMap<>();

		@Override
		public void add(T tile) {
			super.add(tile);
			if (!drawSelect) return;
			TextureRegion region = getRegion(tile);
			// int thick = 3;
			tileWD.drawSeq.add(() -> {
				if (!rect.contains(tile.worldx(), tile.worldy())) return true;
				Draw.rect(region, tile.worldx(), tile.worldy());
				// MyDraw.dashSquare(thick, Pal.accentBack, tile.worldx(), tile.worldy(), size);
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

	public final Task task2 = new Task() {
		@Override
		public void run() {
			focusElem = null;
		}
	};


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
			main.button("show all", IntStyles.blackt, this::showAll).growX().height(buttonHeight).row();
			main.add(cont).width(buttonWidth);
			wrap.update(() -> {
				if (list.isEmpty()) remove();
				else setup();
			});
			if (select.get(name)) {
				setup();
			} else {
				remove();
			}

			all.put(name, this);
		}

		public abstract TextureRegion getRegion(T t);

		public void setting(Table t) {
			t.check(name, select.get(name), b -> {
				if (b) {
					setup();
				} else {
					remove();
				}

				hide();
				select.put(name, b);
				settings.put(getSettingName() + "-" + name, b);
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

		public final void showAll() {
			final int cols = Vars.mobile ? 4 : 6;
			final int[] c = new int[]{0};
			new DisposableWindow(name, 0, 200, true) {{
				cont.pane(table -> {
					list.forEach(item -> {
						var cont = new Table(Tex.pane) {
							public final Task task = new Task() {
								@Override
								public void run() {
									if (item instanceof Tile) focusTile = null;
									else if (item instanceof Building) focusBuild = null;
									else if (item instanceof Unit) focusUnits.remove((Unit) item);
									else if (item instanceof Bullet) focusBullets.remove((Bullet) item);
									focusLock = false;
								}
							};

							public Element hit(float x, float y, boolean touchable) {
								Element tmp = super.hit(x, y, touchable);
								if (tmp == null) return null;
								focusElem = this;
								if (task2.isScheduled()) task2.cancel();
								Timer.schedule(task2, Time.delta * 2f / 60f);
								if (item instanceof Tile) focusTile = (Tile) item;
								else if (item instanceof Building) focusBuild = (Building) item;
								else if (item instanceof Unit) focusUnits.add((Unit) item);
								else if (item instanceof Bullet) focusBullets.add((Bullet) item);
								if (task.isScheduled()) task.cancel();

								focusLock = true;
								Timer.schedule(task, Time.delta * 2f / 60f);
								return tmp;
							}

							public void draw() {
								super.draw();
								if (focusElem == this) {
									Draw.color(focusColor);
									Fill.crect(x, y, width, height);
								}
							}

							{
								touchable = Touchable.enabled;
								update(() -> {
									if (!focusLock && (focusTile == item || focusBuild == item
											|| (item instanceof Unit && focusUnits.contains((Unit) item)))) {
										focusElem = this;
										if (task.isScheduled()) task.cancel();
										Timer.schedule(task, Time.delta * 2f / 60f);
										if (task2.isScheduled()) task2.cancel();
										Timer.schedule(task2, Time.delta * 2f / 60f);
									}
								});
							}
						};
						table.add(cont).minWidth(150);
						buildTable(item, cont);
						cont.row();
						cont.button("更多信息", IntStyles.blackt, () -> {
							JSFunc.showInfo(item);
						}).growX().height(buttonHeight);
						if (++c[0] % cols == 0) {
							table.row();
						}
					});
					// table.getCells().reverse();
				}).fill();
				//				addCloseButton();
			}}.show();
		}

		public void buildTable(T item, Table table) {
		}


		public void add(T item) {
			list.add(item);
		}

	}

	Vec2 mouse = new Vec2();
	Vec2 mouseWorld = new Vec2();

	public void drawFocus() {
		mouse.set(Core.input.mouse());
		mouseWorld.set(Core.camera.unproject(mouse));
		drawFocus(focusTile);
		drawFocus(focusBuild);
		for (var focus : focusUnits) {
			drawFocus(focus);
		}
		for (var focus : focusBullets) {
			drawFocus(focus);
		}
	}

	public void drawFocus(Object focus) {
		if (focus == null) return;
		Rect rect;
		if (focus instanceof Tile) {
			rect = getWorldRect((Tile) focus);
		} else if (focus instanceof Building) {
			rect = getWorldRect((Building) focus);
		} else if (focus instanceof Unit) {
			rect = getWorldRect((Unit) focus);
		} else if (focus instanceof Bullet) {
			rect = getWorldRect((Bullet) focus);
		} else return;
		// Color lastColor = Draw.getColor().cpy();

		Draw.color(focusColor);
		// Log.info(Draw.getColor());
		// float z = Draw.z();
		Draw.z(Layer.max);
		Vec2 tmp = Core.camera.project(rect.x, rect.y);
		float x = tmp.x, y = tmp.y;
		tmp = Core.camera.project(rect.x + rect.width / 4f, rect.y + rect.height / 4f);
		float w = tmp.x - x, h = tmp.y - y;
		Fill.rect(x, y, w, h);
		// Vec2 vec2 = Core.camera.unproject(focusFrom.x, focusFrom.y);
		Draw.color(Pal.accent);

		Lines.stroke(4f);
		if (focusElem != null) {
			Vec2 vec2 = focusElem.localToStageCoordinates(
					Tmp.v1.set(focusElem.getWidth(), focusElem.getHeight()));
			Lines.line(vec2.x, vec2.y, x, y);
			vec2 = focusElem.localToStageCoordinates(
					Tmp.v1.set(0, 0));
			Lines.line(vec2.x, vec2.y, x, y);
		}
		// Draw.z(z);
		// Draw.color(lastColor);
	}

	private boolean focusLock;
	private Element focusElem;
	private Tile focusTile;
	private Building focusBuild;
	private final ObjectSet<Unit> focusUnits = new ObjectSet<>();
	private final ObjectSet<Bullet> focusBullets = new ObjectSet<>();

	{
		otherWD.drawSeq.add(() -> {
			if (!focusLock) {
				focusUnits.clear();
				if (settings.getBool("focusOnWorld")) {
					focusTile = world.tileWorld(mouseWorld.x, mouseWorld.y);
					focusBuild = focusTile != null ? focusTile.build : null;
					Groups.unit.each(u -> {
						if (this.mouseWorld.x > u.x - u.hitSize && this.mouseWorld.y > u.y - u.hitSize
								&& this.mouseWorld.x < u.x + u.hitSize && this.mouseWorld.y < u.y + u.hitSize)
							focusUnits.add(u);
					});
				} else {
					if (focusTile != null) {
						focusTile = null;
					}
					if (focusBuild != null) {
						focusBuild = null;
					}
				}
			}


			return true;
		});
		topGroup.drawSeq.add(() -> {
			drawFocus();
			return true;
		});
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
