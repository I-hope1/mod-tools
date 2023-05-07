
package modtools.ui.content.world;

import arc.*;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.*;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.util.*;
import arc.util.Timer;
import arc.util.Timer.Task;
import ihope_lib.MyReflect;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.ctype.UnlockableContent;
import mindustry.entities.units.UnitController;
import mindustry.game.EventType.Trigger;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.Styles;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;
import modtools.ui.*;
import modtools.ui.IntUI.*;
import modtools.ui.TopGroup.BackElement;
import modtools.ui.components.*;
import modtools.ui.components.Window.*;
import modtools.ui.components.limit.*;
import modtools.ui.content.Content;
import modtools.ui.effect.MyDraw;
import modtools.utils.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.Vector;
import java.util.function.*;

import static mindustry.Vars.*;
import static modtools.ui.IntUI.topGroup;
import static modtools.ui.Contents.tester;
import static modtools.utils.MySettings.SETTINGS;
import static modtools.utils.WorldDraw.*;

public class Selection extends Content {
	private Function<?> selectedFunc;
	public Selection() {
		super("selection");
	}

	final ObjectMap<String, Boolean> select = ObjectMap.of(
			"tile", SETTINGS.getBool(getSettingName() + "-tile", true)
			, "building", SETTINGS.getBool(getSettingName() + "-building", false)
			, "unit", SETTINGS.getBool(getSettingName() + "-unit", false)
			, "bullet", SETTINGS.getBool(getSettingName() + "-bullet", false)
	);

	/* 临时变量 */
	public static final List     tmpList   = new ArrayList<>(1) {
		public void forEach(Consumer<? super Object> action) {
			super.forEach(action);
			clear();
		}
	};
	public static final String[] tmpAmount = new String[1];

	public static final Color focusColor = Color.cyan.cpy().a(0.3f);

	public final WorldDraw
			unitWD   = new WorldDraw(Layer.weather),
			tileWD   = new WorldDraw(Layer.darkness - 1),
			buildWD  = new WorldDraw(Layer.darkness),
			bulletWD = new WorldDraw(Layer.bullet + 5),
			otherWD  = new WorldDraw(Layer.overlayUI);
	public Element fragSelect;
	public Window  pane;
	// public Table functions;
	Team    defaultTeam;
	// show: pane是否显示
	// move: 是否移动
	boolean show       = false,
			move       = false,
			drawSelect = true;

	static final int
			buttonWidth  = 200,
			buttonHeight = 45;
	Function<Tile>     tiles;
	Function<Building> buildings;
	Function<Unit>     units;
	Function<Bullet>   bullets;
	public static OrderedMap<String, Function<?>> allFunctions = new OrderedMap<>();
	private static void intField(Table t1) {
		t1.row();
		t1.field("", s -> {
			tmpAmount[0] = s;
		}).valid(Tools::validPosInt);
	}

	public void loadSettings() {
		Table table = new Table();
		table.add(localizedName()).color(Pal.accent).growX().left().row();
		table.table(t -> {
			t.left().defaults().left();
			allFunctions.each((k, func) -> {
				func.setting(t);
			});
		}).growX().left().padLeft(16).row();
		table.table(t -> {
			defaultTeam = Team.get(SETTINGS.getInt(getSettingName() + "-defaultTeam", 1));
			t.left().defaults().left();
			t.add("@selection.default.team").color(Pal.accent).growX().left().row();
			t.table(t1 -> {
				t1.left().defaults().left();
				Team[] arr = Team.baseTeams;
				int    c   = 0;

				for (Team team : arr) {
					ImageButton b = t1.button(IntUI.whiteui, IntStyles.clearNoneTogglei/*Styles.clearTogglei*/, 32.0f, () -> {
						SETTINGS.put(getSettingName() + "-defaultTeam", team.id);
						defaultTeam = team;
					}).size(42).get();
					b.getStyle().imageUp = IntUI.whiteui.tint(team.color);
					b.update(() -> {
						b.setChecked(defaultTeam == team);
					});
					if (++c % 3 == 0) {
						t1.row();
					}
				}

			}).growX().left().padLeft(16);
		}).growX().left().padLeft(16).row();
		table.table(t -> {
			t.left().defaults().left();
			t.check("@settings.focusOnWorld", SETTINGS.getBool("focusOnWorld"), b -> SETTINGS.put("focusOnWorld", b)).row();
			t.check("@settings.drawSelect", drawSelect, b -> drawSelect = b);
		}).growX().left().padLeft(16).row();

		Contents.settingsUI.add(table);
	}
	public void load() {
		fragSelect = new BackElement();
		fragSelect.name = "SelectionElem";
		// fragSelect.update(() -> fragSelect.toFront());
		fragSelect.touchable = Touchable.enabled;

		fragSelect.setFillParent(true);

		InputListener listener = new SelectListener();
		fragSelect.addListener(listener);

		/*fragDraw = new FragDraw();
		Core.scene.add(fragDraw);*/


		loadFocusWindow();
		loadUI();
	}

	Window focusW;
	void loadFocusWindow() {
		focusW = new FocusWindow("Focus");
	}

	void loadUI() {
		int maxH = 400;
		// functions = new Table();
		// functions.defaults().width(buttonWidth);
		pane = new Window(localizedName(), buttonWidth * 1.5f/* two buttons */,
				maxH, true);
		pane.hidden(this::hide);

		//		pane.cont.left().bottom().defaults().width(W);
		pane.update(() -> {
			if (Vars.state.isMenu()) {
				pane.hide();
			}
		});

		tiles = new TileFunction<>("tile") {{
			FunctionBuild("@selection.reset", __ -> {
				IntUI.showSelectImageTable(Core.input.mouse().cpy(), Vars.content.blocks(), () -> null, block -> {
					each(tile -> {
						setBlock(block, tile);
					});
				}, 42f, 32, 6, true);
			});
			FunctionBuild("@clear", __ -> {
				each(tile -> {
					if (tile.block() != Blocks.air) tile.setAir();
				});
			});
			ListFunction("@selection.setfloor",
					Vars.content.blocks().select(block -> block instanceof Floor),
					null, floor -> {
						each(tile -> {
							tile.setFloor((Floor) floor);
						});
					});
			ListFunction("@selection.setfloorUnder", Vars.content.blocks().select(block -> block instanceof Floor && !(block instanceof OverlayFloor)),
					null, floor -> {
						each(tile -> {
							tile.setFloorUnder((Floor) floor);
						});
					});
			ListFunction("@selection.setoverlay", Vars.content.blocks().select(block -> block instanceof OverlayFloor || block == Blocks.air), null, overlay -> {
				each(tile -> {
					tile.setOverlay(overlay);
				});
			});
		}};
		buildings = new BuildFunction<>("building") {{
			FunctionBuild("@selection.infiniteHealth", __ -> {
				each(b -> {
					b.health = Float.POSITIVE_INFINITY;
				});
			});
			TeamFunctionBuild("@editor.teams", team -> {
				each(b -> {
					b.changeTeam(team);
				});
			});
			ListFunction("@selection.items", Vars.content.items(), Selection::intField, item -> {
				each(b -> {
					if (b.items != null) {
						b.items.set(item, Tools.asInt(tmpAmount[0]));
					}
				});
			});
			ListFunction("@selection.liquids", Vars.content.liquids(), Selection::intField, liquid -> {
				each(b -> {
					if (b.liquids != null) {
						b.liquids.add(liquid, Tools.asInt(tmpAmount[0]) - b.liquids.get(liquid));
					}
				});
			});
			FunctionBuild("物品统计", list -> {
				sumItems(content.items(), i -> sum(list, b -> b.items == null ? 0 : b.items.get(i)),
						list.size() == 1 ? getItemSetter(list.get(0)) : null);
			});
			FunctionBuild("液体统计", list -> {
				sumItems(content.liquids(), l -> sumf(list, b -> b.liquids == null ? 0 : b.liquids.get(l)),
						list.size() == 1 ? getLiquidSetter(list.get(0)) : null);
			});
			FunctionBuild("@kill", list -> {
				removeIf(list, b -> {
					if (b.tile.build == b) b.kill();
					return b.tile.build != null;
				});
			});
			FunctionBuild("@clear", list -> {
				removeIf(list, b -> {
					if (b.tile != null && b.tile.block() != Blocks.air) {
						b.tile.remove();
					}
					b.remove();
					return b.tile.build != null;
				});
			});
		}};
		units = new UnitFunction<>("unit") {{
			FunctionBuild("@selection.infiniteHealth", __ -> {
				each(unit -> {
					unit.health(Float.POSITIVE_INFINITY);
				});
			});
			TeamFunctionBuild("@editor.teams", team -> {
				each(unit -> {
					if (Vars.player.unit() == unit) Vars.player.team(team);
					unit.team(team);
				});
			});
			FunctionBuild("@kill", list -> {
				removeIf(list, u -> {
					Call.unitDeath(u.id);
					try {
						return !addedField.getBoolean(u);
					} catch (Exception ignored) {}
					return false;
				});
			});
			FunctionBuild("@clear", list -> {
				removeIf(list, u -> {
					u.remove();
					return Groups.unit.contains(u0 -> u0 == u);
					// return !addedField.getBoolean(u);
					// return false;
				});
			});
			FunctionBuild("@selection.forceClear", list -> {
				removeIf(list, u -> {
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
		}};
		bullets = new BulletFunction<>("bullet") {{
			FunctionBuild("@clear", list -> {
				removeIf(list, bullet -> {
					bullet.remove();
					try {
						return Groups.bullet.contains(b -> b == bullet);
					} catch (Exception ignored) {}
					return false;
				});
			});

		}};

		// ScrollPaneStyle paneStyle = new ScrollPaneStyle();
		// paneStyle.background = Styles.none;
		Seq<Table> tableSeq = new Seq<>();
		for (var func : allFunctions) {
			tableSeq.add(func.value.wrap);
		}
		var tab = new IntTab(100, allFunctions.orderedKeys(),
				Color.sky, tableSeq, 1, true);
		var values = allFunctions.values().toSeq();
		pane.cont.update(() -> {
			selectedFunc = values.get(tab.getSelected());
		});
		pane.cont.left().add(tab.build()).grow().left();
		// t.pane(paneStyle, functions).grow();
		// });
		//		pane.show();
		btn.setDisabled(() -> Vars.state.isMenu());
		loadSettings();

		btn.setStyle(Styles.logicTogglet);

		/*{
			Pools.set(Bullet.class, new Pool<>(4, 5000) {
				public Bullet newObject() {
					return new Bullet() {
						@Override
						public void update() {
							super.update();
							rotation(360 * (float) Math.random());
						}
					};
				}
			});
		}*/
	}
	private void setBlock(Block block, Tile tile) {
		if (tile.block() == block) return;
		if (block.isMultiblock()) {
			int offsetx = -(block.size - 1) / 2;
			int offsety = -(block.size - 1) / 2;
			for (int dx = 0; dx < block.size; dx++) {
				for (int dy = 0; dy < block.size; dy++) {
					int  worldx = dx + offsetx + tile.x;
					int  worldy = dy + offsety + tile.y;
					Tile other  = world.tile(worldx, worldy);

					if (other != null && other.block().isMultiblock() && other.block() == block) {
						return;
					}
				}
			}
		}
		tile.setBlock(block, tile.build != null ? tile.team() : defaultTeam);
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
		bullets.clearList();
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

	public class BulletFunction<T extends Bullet> extends Function<T> {
		public BulletFunction(String name) {
			super(name);
		}

		public void buildTable(T bullet, Table table) {
			table.add(String.valueOf(bullet.type)).row();
			table.label(() -> "(" + bullet.x + ", " + bullet.y + ')');
		}

		public ObjectMap<Float, TextureRegion> map = new ObjectMap<>();

		public TextureRegion getRegion(T bullet) {
			float rsize = bullet.hitSize * 1.4f * 4;
			return map.get(bullet.hitSize, () -> {
				int   size  = (int) (rsize * 2);
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
			Time.runTask(0, () -> {
				TextureRegion region = getRegion(bullet);
				bulletWD.drawSeq.add(() -> {
					if (!bullet.isAdded()) {
						return false;
					}
					if (!rect.contains(bullet.x, bullet.y)) return true;
					Draw.alpha(getAlpha(this));
					Draw.rect(region, bullet.x, bullet.y);
					return true;
				});
			});
		}

		@Override
		public void clearList() {
			super.clearList();
			if (!bulletWD.drawSeq.isEmpty()) bulletWD.drawSeq.clear();
		}
	}

	public class UnitFunction<T extends Unit> extends Function<T> {
		public UnitFunction(String name) {
			super(name);
		}

		public void buildTable(T unit, Table table) {
			table.image(unit.type().uiIcon).row();
			// table.label(() -> "(" + unit.x + ", " + unit.y + ')');
		}

		public ObjectMap<Float, TextureRegion> map = new ObjectMap<>();

		public TextureRegion getRegion(T unit) {
			float rsize = unit.hitSize * 1.4f * 4;
			return map.get(unit.hitSize, () -> {
				int   size  = (int) (rsize * 2);
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
			Time.runTask(0, () -> {
				TextureRegion region = getRegion(unit);
				unitWD.drawSeq.add(() -> {
					if (!unit.isAdded()) {
						return false;
					}
					if (!rect.contains(unit.x, unit.y)) return true;
					Draw.alpha(getAlpha(this));
					Draw.rect(region, unit.x, unit.y);
					return true;
				});
			});
		}

		@Override
		public void clearList() {
			super.clearList();
			if (!unitWD.drawSeq.isEmpty()) unitWD.drawSeq.clear();
		}
	}

	public class BuildFunction<T extends Building> extends Function<T> {
		public BuildFunction(String name) {
			super(name);
		}

		@Override
		public TextureRegion getRegion(T building) {
			return map.get(building.block.size, () -> {
				int size  = building.block.size * 32;
				int thick = 7;
				return drawRegion(size + thick, size + thick, () -> {
					MyDraw.dashSquare(thick, Pal.accent, (size + thick) / 2f, (size + thick) / 2f, size);
				});
			});
		}

		public void buildTable(T build, Table table) {
			/*Table cont = new Table();
			// building.display(cont);
			table.add(cont).row();*/
			table.image(Icon.star).size(10).color(build.team.color).colspan(0);
			table.add(build.block.name).row();
			buildPos(table, build);
			table.row();
			table.table(t -> {
				t.defaults().size(75, 42);
				var style = Styles.flatt;
				t.button("物品统计", style, () -> sumItems(content.items(),
						i -> build.items == null ? 0 : build.items.get(i),
						getItemSetter(build)));
				t.button("液体统计", style, () -> sumItems(content.liquids(),
						l -> build.liquids == null ? 0 : build.liquids.get(l),
						getLiquidSetter(build)));
			});
		}
		Cons2<Liquid, String> getLiquidSetter(T build) {
			return (l, str) -> build.liquids.set(l, Tools.asFloat(str));
		}
		Cons2<Item, String> getItemSetter(T build) {
			return (i, str) -> build.items.set(i, Tools.asInt(str));
		}

		public ObjectMap<Integer, TextureRegion> map = new ObjectMap<>();

		@Override
		public void add(T building) {
			super.add(building);
			if (!drawSelect) return;

			Time.runTask(0, () -> {
				TextureRegion region = getRegion(building);
				buildWD.drawSeq.add(() -> {
					if (building.tile.build != building) {
						// buildWD.hasChange = false;
						return false;
					}
					if (!rect.contains(building.x, building.y)) return true;
					Draw.alpha(getAlpha(this));
					Draw.rect(region, building.x, building.y, 45);
					return true;
				});
			});
		}

		@Override
		public void clearList() {
			super.clearList();
			if (!buildWD.drawSeq.isEmpty()) buildWD.drawSeq.clear();
		}
	}

	public class TileFunction<T extends Tile> extends Function<T> {
		public TileFunction(String name) {
			super(name);
		}

		@Override
		public TextureRegion getRegion(T tile) {
			return map.get(tile.block().size, () -> {
				int size  = tilesize * 4;
				int thick = 7;
				return drawRegion(size + thick, size + thick, () -> {
					MyDraw.dashSquare(thick, Pal.heal, (size + thick) / 2f, (size + thick) / 2f, size);
				});
			});
		}

		public void buildTable(T tile, Table t) {
			// tile.display(table);
			// table.row();
			t.defaults().padRight(4f);
			t.image(tile.block() == Blocks.air ? Styles.none : new TextureRegionDrawable(tile.block().uiIcon)).size(24);
			t.add(tile.block().name).with(JSFunc::addDClickCopy);
			t.add("(" + tile.x + ", " + tile.y + ")");
			if (tile.overlay().itemDrop != null) t.image(tile.overlay().itemDrop.uiIcon).size(24);
		}

		public ObjectMap<Integer, TextureRegion> map = new ObjectMap<>();

		@Override
		public void add(T tile) {
			super.add(tile);
			if (!drawSelect) return;
			Time.runTask(0, () -> {
				TextureRegion region = getRegion(tile);
				tileWD.drawSeq.add(() -> {
					if (!rect.contains(tile.worldx(), tile.worldy())) return true;
					Draw.alpha(getAlpha(this));
					Draw.rect(region, tile.worldx(), tile.worldy());
					// MyDraw.dashSquare(thick, Pal.accentBack, tile.worldx(), tile.worldy(), size);
					return true;
				});
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
	private float getAlpha(Function<?> func) {
		return pane.isShown() && func == selectedFunc ? 1f : 0.3f;
	}

	public final Task task2 = new Task() {
		@Override
		public void run() {
			focusElem = null;
		}
	};


	public static final ObjectMap<String, Drawable> icons = new ObjectMap<>();

	public abstract class Function<T> {
		public final Table   wrap = new Table();
		public final Table   main = new Table();
		public final Table   cont = new Table();
		public       List<T> list = new Vector<>();
		public final String  name;

		public Function(String name) {
			this.name = name;
			// functions.add(wrap).padTop(10.0f).row();
			// main.image().color(Color.white).height(3.0f).padTop(3.0f).padBottom(3.0f).fillX().row();
			// main.add(name).growX().left().row();
			main.button("show all", IntStyles.blackt, this::showAll).grow().top().height(buttonHeight).row();
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

			allFunctions.put(name, this);

			FunctionBuild("copy", list -> {
				tester.put(Core.input.mouse(), Seq.with(list).toArray());
			});
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
				SETTINGS.put(getSettingName() + "-" + name, b);
			});
		}

		public void remove() {
			wrap.clearChildren();
		}

		// private int c;

		public float sumf(List<T> list, Floatf<T> summer) {
			float sum = 0;
			for (T t : list) {
				sum += summer.get(t);
			}
			return sum;
		}
		public int sum(List<T> list, Intf<T> summer) {
			int sum = 0;
			for (T t : list) {
				sum += summer.get(t);
			}
			return sum;
		}

		Thread thread;
		public void each(Consumer<? super T> action) {
			if (tmpList.size() != 0) {
				tmpList.forEach(action);
				return;
			}
			// if (list.size() < 1e4) {
			// 	IntVars.async("each", () -> list.forEach(action), () -> {});
			// 	return;
			// }
			/*c = 0;
			list.forEach(e -> {
				Time.runTask(c++ / 100f, () -> action.accept(e));
			});*/

			Thread[] tmp = {null};
			tmp[0] = Threads.thread(() -> {
				while (thread != null && thread.isAlive()) {
					Threads.sleep(10);
				}
				thread = tmp[0];
				for (T t : list) {
					try {
						action.accept(t);
					} catch (Exception e) {
						Log.err(e);
					}
					Threads.sleep(1, 1000);
				}
				thread = null;
			});
		}
		void removeIf(List<T> list, Predicate<? super T> action) {
			list.removeIf(action);
		}

		public void clearList() {
			list.clear();
		}
		public void setup() {
			if (main.parent == wrap) return;
			wrap.add(main);
		}
		public final void showAll() {
			final int   cols = Vars.mobile ? 4 : 6;
			final int[] c    = new int[]{0};
			new DisWindow(name, 0, 200, true) {{
				cont.pane(new LimitTable(table -> {
					for (T item : list) {
						var cont = new LimitTable(Tex.pane) {
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
					}
					// table.getCells().reverse();
					// table.getCells().reverse();
				})).fill();
				//				addCloseButton();
			}}.show();
		}

		public void buildTable(T item, Table table) {
		}

		public void add(T item) {
			list.add(item);
		}


		final ObjectMap<String, Cons<List<T>>> FUNCTIONS = new OrderedMap<>();

		public class MENU_FUNCTION {
			Cons<List<T>> cons;
			MenuList      list;
			public MENU_FUNCTION(Cons<List<T>> cons, MenuList list) {
				this.cons = cons;
				this.list = list;
			}
		}
		public <R extends UnlockableContent> void ListFunction(String name, Seq<R> list,
		                                                       Cons<Table> builder,
		                                                       Cons<R> cons) {
			FunctionBuild(name, __ -> {
				var table = IntUI.showSelectImageTable(
						Core.input.mouse().cpy(), list, () -> null,
						cons, 42f, 32,
						6, true);
				if (builder != null) builder.get(table);
			});
		}

		/** 这个exec的list只是为Functions提供作用 */
		public void FunctionBuild(String name, Cons<List<T>> exec) {
			TextButton button = new TextButton(name);
			cont.add(button).height(buttonHeight).growX().row();

			FUNCTIONS.put(name, exec);
			button.clicked(() -> {
				exec.get(list);
			});
		}

		public void TeamFunctionBuild(String name, Cons<Team> cons) {
			FunctionBuild(name, __ -> {
				Team[]        arr   = Team.baseTeams;
				Seq<Drawable> icons = new Seq<>();

				for (Team team : arr) {
					icons.add(IntUI.whiteui.tint(team.color));
				}

				IntUI.showSelectImageTableWithIcons(Core.input.mouse().cpy(), new Seq<>(arr), icons, () -> null, cons, 42f, 32f, 3, false);
			});
		}
	}

	Vec2 mouse      = new Vec2();
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
		Vec2  tmp = Core.camera.project(rect.x, rect.y);
		float x   = tmp.x, y = tmp.y;
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

	private boolean focusLock, focusActive;
	private       Element           focusElem;
	private       Tile              focusTile;
	private       Building          focusBuild;
	private final ObjectSet<Unit>   focusUnits   = new ObjectSet<>();
	private final ObjectSet<Bullet> focusBullets = new ObjectSet<>();


	{
		otherWD.drawSeq.add(() -> {
			if (Core.input.alt()) {
				Draw.alpha(0.3f);
			}
			return true;
		});
		otherWD.drawSeq.add(() -> {
			Element tmp = Core.scene.hit(Core.input.mouseX(), Core.input.mouseY(), true);
			focusActive = !topGroup.isSelecting() && (tmp == null || tmp.isDescendantOf(focusW) || !tmp.visible
			                                          || tmp.isDescendantOf(el -> el.getClass().getName().contains("modtools.ui.IntUI"))
			                                          || tmp.isDescendantOf(topGroup.getTopG()));
			if (!focusActive) return true;
			if (!focusLock) {
				reacquireFocus();
			}

			return true;
		});
		topGroup.backDrawSeq.add(() -> {
			if (state.isGame()) drawFocus();
			return true;
		});
	}

	private void reacquireFocus() {
		focusUnits.clear();
		focusBullets.clear();
		if (SETTINGS.getBool("focusOnWorld")) {
			focusTile = world.tileWorld(mouseWorld.x, mouseWorld.y);
			focusBuild = focusTile != null ? focusTile.build : null;
			Groups.unit.each(u -> {
				if (mouseWorld.x > u.x - u.hitSize && mouseWorld.y > u.y - u.hitSize
				    && mouseWorld.x < u.x + u.hitSize && mouseWorld.y < u.y + u.hitSize)
					focusUnits.add(u);
			});
			Groups.bullet.each(b -> {
				float hitSize = b.hitSize + 2;
				if (mouseWorld.x > b.x - hitSize && mouseWorld.y > b.y - hitSize
				    && mouseWorld.x < b.x + hitSize && mouseWorld.y < b.y + hitSize)
					focusBullets.add(b);
			});
		} else {
			focusTile = null;
			focusBuild = null;
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
	public class FocusWindow extends Window {
		public FocusWindow(String title) {
			super(title);
		}

		boolean updatePos = true;
		long    delayTime = 200, lastFire = 0;

		{
			top.remove();
			/* 禁用缩放和移动侦听器 */
			touchable = Touchable.childrenOnly;
			sclLisetener.disabled1 = true;
			moveListener.disabled = true;
			cont.update(() -> {
				if (updatePos && focusActive) updatePos();
			});
			buildCont0();
			Events.run(Trigger.update, () -> {
				if (state.isMenu() || !SETTINGS.getBool("focusOnWorld") || !focusActive) {
					hide();
				} else if (!isShown()) {
					show();
				}
				if (!Vars.mobile && Time.millis() - lastFire > delayTime
				    && Core.input.alt() && Core.input.ctrl()) {
					lastFire = Time.millis();
					updatePos = !updatePos;
					focusLock = !updatePos;
				}
			});
		}

		public void hide() {
			remove();
		}
		public Element hit(float x, float y, boolean touchable) {
			Element el = super.hit(x, y, touchable);
			if (Vars.mobile) {
				updatePos = el == null;
				focusLock = !updatePos;
			}
			return el;
		}
		private void buildCont0() {
			/* tile */
			newTable(t -> {
				buildCont(t, focusTile);
				t.background(t.getChildren().any() ? Tex.underlineDisabled : null);
			});
			/* build */
			newTable(t -> {
				buildCont(t, focusBuild);
				t.background(t.getChildren().any() ? Tex.underlineDisabled : null);
			});
			/* unit */
			newTable(t -> {
				buildCont(t, focusUnits);
				t.background(t.getChildren().any() ? Tex.underlineDisabled : null);
			});
			/* bullet */
			newTable(t -> {
				buildCont1(t, focusBullets);
				t.background(t.getChildren().any() ? Tex.underlineDisabled : null);
			});
		}
		private void newTable(Cons<Table> cons) {
			cont.table(t -> {
				t.act(0.15f);
				t.left().defaults().left().growY();
				t.marginTop(-2f);
				t.update(() -> cons.get(t));
			}).growX().row();
		}

		public Tile lastTile;
		private void addMoreButton(Table t, Object o) {
			t.button("More", Styles.flatt, () -> JSFunc.showInfo(o)).size(50, 24);
		}
		private void buildCont(Table table, Tile tile) {
			if (lastTile == tile) return;
			table.clearChildren();
			lastTile = tile;
			if (tile == null) return;

			ArrayList<MenuList> list = new ArrayList<>(tiles.FUNCTIONS.size);
			tiles.FUNCTIONS.each((k, r) -> {
				list.add(new MenuList(Styles.none, k, () -> {
					tmpList.clear();
					tmpList.add(tile);
					r.get(tmpList);
				}));
			});
			table.table(t -> {
				IntUI.addShowMenuListener(t, list);
				t.defaults().padRight(4f).growY();
				t.image(tile.block() == Blocks.air ? Styles.none : new TextureRegionDrawable(tile.block().uiIcon)).size(24);
				t.add(tile.block().name).with(JSFunc::addDClickCopy);
				t.add("(" + tile.x + ", " + tile.y + ")");
				if (tile.overlay().itemDrop != null) t.image(tile.overlay().itemDrop.uiIcon).size(24);
				addMoreButton(t, tile);
			}).row();
		}

		public Building lastBuild;
		private void buildCont(Table table, Building build) {
			if (lastBuild == build) return;
			table.clearChildren();
			lastBuild = build;
			if (build == null) return;

			ArrayList<MenuList> list = new ArrayList<>(buildings.FUNCTIONS.size);
			buildings.FUNCTIONS.each((k, r) -> {
				list.add(new MenuList(Styles.none, k, () -> {
					tmpList.clear();
					tmpList.add(build);
					r.get(tmpList);
				}));
			});
			table.table(t -> {
				IntUI.addShowMenuListener(t, list);
				t.defaults().padRight(6f).growY();
				t.image(Icon.star).size(10).color(build.team.color);
				buildPos(t, build);
				addMoreButton(t, build);
			}).row();
		}

		public int lastUnitSize;
		private void buildCont(Table table, ObjectSet<Unit> unitSet) {
			if (lastUnitSize == unitSet.size) return;
			table.clearChildren();
			lastUnitSize = unitSet.size;
			if (lastUnitSize == 0) return;

			ArrayList<MenuList> list = new ArrayList<>(units.FUNCTIONS.size);
			units.FUNCTIONS.each((k, r) -> {
				list.add(new MenuList(Styles.none, k, () -> {
					tmpList.clear();
					unitSet.each(tmpList::add);
					r.get(tmpList);
				}));
			});
			unitSet.each(u -> table.table(Tex.underline, t -> {
				IntUI.addShowMenuListener(t, list);
				t.defaults().padRight(6f).growY();
				t.image(Icon.star).size(10).color(u.team.color);
				t.image(new TextureRegionDrawable(u.type.uiIcon)).size(24);
				t.add("" + u.type.name).with(JSFunc::addDClickCopy);

				buildPos(t, u);
				// t.add("pathfind:" + u.pathType());
				addMoreButton(t, u);
			}).growX().row());
		}

		public int lastBulletSize;
		private void buildCont1(Table table, ObjectSet<Bullet> unitSet) {
			if (lastBulletSize == unitSet.size) return;
			table.clearChildren();
			lastBulletSize = unitSet.size;
			if (lastBulletSize == 0) return;

			ArrayList<MenuList> list = new ArrayList<>(units.FUNCTIONS.size);
			units.FUNCTIONS.each((k, r) -> {
				list.add(new MenuList(Styles.none, k, () -> {
					tmpList.clear();
					unitSet.each(tmpList::add);
					r.get(tmpList);
				}));
			});
			unitSet.each(u -> table.table(Tex.underline, t -> {
				IntUI.addShowMenuListener(t, list);
				t.defaults().padRight(6f).growY();
				t.image(Icon.star).size(10).color(u.team.color).colspan(0);
				t.label(() -> u.time + "/" + u.lifetime).size(10).colspan(2).row();
				// t.add("" + u.type).with(JSFunc::addDClickCopy);

				buildPos(t, u);
				// t.add("pathfind:" + u.pathType());
				addMoreButton(t, u);
			}).growX().row());
		}

		private void updatePos() {
			Tmp.v1.set(Core.input.mouse());
			x = Tmp.v1.x;
			if (x + width > Core.scene.getWidth()) x -= width;
			y = Tmp.v1.y;
			if (y + height > Core.scene.getHeight()) y -= height;
		}
	}
	private static void buildPos(Table table, Position u) {
		String[] pos  = {"(" + u.getX() + ", " + u.getY() + ')'};
		Vec2     last = new Vec2(u.getX(), u.getY());
		table.label(() -> {
			if (last.x != u.getX() || last.y != u.getY()) {
				pos[0] = "(" + u.getX() + ", " + u.getY() + ')';
			}
			return pos[0];
		}).get().act(0.1f);
	}
	private <T extends UnlockableContent, E> void sumItems(Seq<T> items, Func<T, E> func, Cons2<T, String> setter) {
		var wacther = JSFunc.watch();
		wacther.addAllCheckbox();
		items.each(i -> {
			wacther.watchWithSetter(new TextureRegionDrawable(i.uiIcon), () -> {
				if (i.id % 6 == 0) wacther.newLine();
				return func.get(i);
			}, setter == null ? null : str -> setter.get(i, str), 2);
		});
		wacther.show();
	}
	private class SelectListener extends InputListener {
		final Vec2 start = new Vec2();
		final Vec2 end   = new Vec2();

		public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
			if (button != KeyCode.mouseLeft || Vars.state.isMenu()) {
				hide();
				move = false;
				return false;
			} else {
				aquireWorldPos(x, y);
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
			aquireWorldPos(x, y);
		}
		private void aquireWorldPos(float x, float y) {
			end.set(Core.camera.unproject(x, y));
		}
		public void touchUp(InputEvent event, float mx, float my, int pointer, KeyCode button) {
			aquireWorldPos(mx, my);
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
				Threads.thread(() -> {
					Groups.bullet.each(bullet -> {
						// 返回单位是否在所选区域内
						return start.x <= bullet.x && end.x >= bullet.x && start.y <= bullet.y && end.y >= bullet.y;
					}, bullet -> {
						Threads.sleep(1);
						if (!bullets.list.contains(bullet)) {
							bullets.add(bullet);
						}
					});
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
			Threads.thread(() -> {
				for (float y = minY; y < maxY; y += tilesize) {
					for (float x = minX; x < maxX; x += tilesize) {
						Tile tile = world.tileWorld(x, y);
						if (tile != null) {
							Threads.sleep(1);
							if (select.get("tile") && !tiles.list.contains(tile)) {
								tiles.add(tile);
							}

							if (select.get("building") && tile.build != null && !buildings.list.contains(tile.build)) {
								buildings.add(tile.build);
							}
						}
					}
				}
			});

			if (!pane.isShown()) {
				pane.setPosition(mx, my);
				pane.show();
			}
			show = false;
			//				start = end = null;
		}
	}
}
