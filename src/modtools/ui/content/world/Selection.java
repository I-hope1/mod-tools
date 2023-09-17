
package modtools.ui.content.world;

import arc.Core;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.*;
import arc.math.geom.*;
import arc.math.geom.QuadTree.QuadTreeObject;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.struct.ObjectMap.Entry;
import arc.util.*;
import arc.util.Timer.Task;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.ctype.UnlockableContent;
import mindustry.entities.units.UnitController;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.InputHandler;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.*;
import modtools.events.*;
import modtools.ui.*;
import modtools.ui.TopGroup.BackElement;
import modtools.ui.components.*;
import modtools.ui.components.Window.*;
import modtools.ui.components.input.JSRequest;
import modtools.ui.components.limit.*;
import modtools.ui.components.linstener.*;
import modtools.ui.content.*;
import modtools.ui.content.ui.PositionProv;
import modtools.ui.effect.MyDraw;
import modtools.utils.*;
import modtools.utils.MySettings.Data;
import modtools.utils.array.ArrayUtils;
import modtools.utils.ui.LerpFun;
import modtools.utils.world.*;

import java.lang.reflect.Field;
import java.util.Vector;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static modtools.ui.Contents.tester;
import static modtools.ui.IntUI.*;
import static modtools.utils.reflect.FieldUtils.getFieldAccess;
import static modtools.utils.world.WorldDraw.*;

@SuppressWarnings({"rawtypes", "CodeBlock2Expr", "DanglingJavadoc"})
public class Selection extends Content {
	private Function<?> selectFunc;
	private Function<?> focusElemType;
	public Selection() {
		super("selection");
	}

	/* 临时变量 */
	public static final List     tmpList   = new ArrayList<>(1) {
		public void forEach(Consumer<? super Object> action) {
			super.forEach(action);
			clear();
		}
	};
	public static final String[] tmpAmount = new String[1];

	public static final Color focusColor = Color.cyan.cpy().a(0.4f);

	public final WorldDraw
	 unitWD   = new WorldDraw(Layer.weather, "unit"),
	 tileWD   = new WorldDraw(Layer.darkness + 1, "tile"),
	 buildWD  = new WorldDraw(Layer.darkness + 2, "build"),
	 bulletWD = new WorldDraw(Layer.bullet + 5, "bullet"),
	 otherWD  = new WorldDraw(Layer.overlayUI, "other");

	public Element fragSelect;
	public Window  pane;
	// public Table functions;
	public Team    defaultTeam;
	// show: select（用于选择）是否显示
	// move: 是否移动
	boolean show = false,
	 move        = false,
	 drawSelect  = true;

	static final int
	 buttonWidth  = 200,
	 buttonHeight = 45;
	Function<Tile>     tiles;
	Function<Building> buildings;
	Function<Unit>     units;
	Function<Bullet>   bullets;

	/** @see E_Selection */
	public static OrderedMap<String, Function<?>> allFunctions = new OrderedMap<>();
	private static void intField(Table t1) {
		t1.row();
		t1.field("", s -> tmpAmount[0] = s).valid(Tools::validPosInt);
	}
	private static void floatField(Table t1) {
		t1.row();
		t1.field("", s -> tmpAmount[0] = s).valid(Strings::canParsePositiveFloat);
	}

	public void loadSettings(Data SETTINGS) {
		Contents.settings_ui.add(localizedName(), new Table() {{
			table(t -> {
				t.left().defaults().left();
				allFunctions.each((k, func) -> func.setting(t));
			}).growX().left().padLeft(16).row();
			table(t -> {
				defaultTeam = Team.get(SETTINGS.getInt("defaultTeam", 1));
				t.left().defaults().left();
				t.add("@selection.default.team").color(Pal.accent).growX().left().row();
				t.table(t1 -> {
					t1.left().defaults().left();
					Team[] arr = Team.baseTeams;
					int    c   = 0;

					for (Team team : arr) {
						ImageButton b = t1.button(IntUI.whiteui, IntStyles.clearNoneTogglei/*Styles.clearTogglei*/, 32.0f, () -> {
							SETTINGS.put("defaultTeam", team.id);
							defaultTeam = team;
						}).size(42).get();
						b.getStyle().imageUp = IntUI.whiteui.tint(team.color);
						b.update(() -> b.setChecked(defaultTeam == team));
						if (++c % 3 == 0) {
							t1.row();
						}
					}

				}).growX().left().padLeft(16);
			}).growX().left().padLeft(16).row();
			table(t -> {
				t.left().defaults().left();
				SettingsUI.checkboxWithEnum(t, "@settings.focusOnWorld", E_Selection.focusOnWorld).row();
				t.check("@settings.drawSelect", drawSelect, b -> drawSelect = b);
			}).growX().left().padLeft(16).row();
		}});
	}

	public void load() {
		fragSelect = new BackElement();
		fragSelect.name = "SelectionElem";
		// fragSelect.update(() -> fragSelect.toFront());
		fragSelect.touchable = Touchable.enabled;

		fragSelect.setFillParent(true);
		fragSelect.addListener(new SelectListener());

		/*fragDraw = new FragDraw();
		Core.scene.add(fragDraw);*/
		loadUI();
		loadFocusWindow();
	}

	FocusWindow focusW;
	void loadFocusWindow() {
		focusW = new FocusWindow("Focus");
	}

	void loadUI() {
		int minH = 300;
		pane = new Window(localizedName(), buttonWidth * 1.5f/* two buttons */,
		 minH, false);
		pane.shown(() -> Time.runTask(3, pane::display));
		pane.hidden(this::hide);
		pane.update(() -> {
			if (Vars.state.isMenu()) {
				pane.hide();
			}
		});

		/* 初始化functions */
		tiles = new TileFunction<>("tile") {{
			ListFunction("@selection.reset", () -> content.blocks(), null, (list, block) -> {
				each(list, tile -> WorldUtils.setBlock(tile, block));
			});
			FunctionBuild("@clear", list -> {
				each(list, tile -> {
					if (tile.block() != Blocks.air) tile.setAir();
				});
			});
			ListFunction("@selection.setfloor",
			 () -> content.blocks().select(block -> block instanceof Floor), null, (list, floor) -> {
				 each(list, tile -> {
					 tile.setFloor((Floor) floor);
				 });
			 });
			ListFunction("@selection.setfloorUnder",
			 () -> content.blocks().select(block -> block instanceof Floor && !(block instanceof OverlayFloor)),
			 null, (list, floor) -> {
				 each(list, tile -> {
					 tile.setFloorUnder((Floor) floor);
				 });
			 });
			ListFunction("@selection.setoverlay",
			 () -> content.blocks().select(block -> block instanceof OverlayFloor || block == Blocks.air),
			 null, (list, overlay) -> {
				 each(list, tile -> {
					 tile.setOverlay(overlay);
				 });
			 });
		}};
		buildings = new BuildFunction<>("building") {{
			FunctionBuild("@selection.infiniteHealth", list -> {
				each(list, b -> {
					b.health = Float.POSITIVE_INFINITY;
				});
			});
			TeamFunctionBuild("@editor.teams", (list, team) -> {
				each(list, b -> {
					b.changeTeam(team);
				});
			});
			ListFunction("@selection.items", () -> content.items(), Selection::intField, (list, item) -> {
				each(list, b -> {
					if (b.items != null) {
						b.items.set(item, Tools.asInt(tmpAmount[0]));
					}
				});
			});
			ListFunction("@selection.liquids", () -> content.liquids(), Selection::floatField, (list, liquid) -> {
				each(list, b -> {
					if (b.liquids != null) {
						b.liquids.add(liquid, Tools.asInt(tmpAmount[0]) - b.liquids.get(liquid));
					}
				});
			});
			FunctionBuild("@selection.sumitems", list -> {
				sumItems(content.items(), i -> sum(list, b -> b.items == null ? 0 : b.items.get(i)),
				 list.size() == 1 ? getItemSetter(list.get(0)) : null);
			});
			FunctionBuild("@selection.sumliquids", list -> {
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
			FunctionBuild("@selection.infiniteHealth", list -> {
				each(list, unit -> {
					unit.health(Float.POSITIVE_INFINITY);
				});
			});
			TeamFunctionBuild("@editor.teams", (list, team) -> {
				each(list, unit -> {
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

		var tab = new IntTab(-1, allFunctions.orderedKeys().toArray(String.class),
		 Color.sky,
		 ArrayUtils.map2Arr(Table.class, allFunctions, e -> e.value.wrap),
		 1, true);
		pane.cont.update(() -> {
			tab.labels.each((name, l) -> {
				l.color.set(E_Selection.valueOf(name).enabled() ? Color.white : Color.lightGray);
			});
		});
		pane.cont.left().add(tab.build()).grow().left();

		btn.setDisabled(() -> Vars.state.isMenu());
		loadSettings();
		btn.setStyle(Styles.logicTogglet);
	}

	public static final Field
	 addedField = getFieldAccess(UnitEntity.class, "added"),
	 controller = getFieldAccess(UnitEntity.class, "controller");

	public void hide() {
		fragSelect.remove();
		show = false;
		btn.setChecked(false);

		tiles.clearList();
		buildings.clearList();
		units.clearList();
		bullets.clearList();

		if (executor != null) executor.shutdownNow();
	}
	public void build() {
		show = btn.isChecked();
		ElementUtils.addOrRemove(fragSelect, show);
	}
	public static void getWorldRect(Tile t) {
		TMP_RECT.set(t.worldx(), t.worldy(), 32, 32);
	}

	public class BulletFunction<T extends Bullet> extends Function<T> {
		public BulletFunction(String name) {
			super(name, bulletWD);
		}

		public void buildTable(T bullet, Table table) {
			table.add(String.valueOf(bullet.type)).row();
			table.label(() -> "(" + bullet.x + ", " + bullet.y + ')');
		}

		public TextureRegion getRegion(T bullet) {
			return iconMap.get(bullet.hitSize, () -> {
				int   size  = (int) (bullet.hitSize * 1.4f * 4 * 2);
				float thick = 12f;
				return drawRegion(size, size, () -> {
					MyDraw.square(size / 2f, size / 2f, size * 2 / (float) tilesize - 1, thick, Color.sky);
				});
			});
		}

		public TextureRegion getIcon(T key) {
			return Core.atlas.white();
		}
		public boolean checkRemove(T item) {
			return !item.isAdded();
		}
	}
	public class UnitFunction<T extends Unit> extends Function<T> {
		public UnitFunction(String name) {
			super(name, unitWD);
		}

		public void buildTable(T unit, Table table) {
			table.image(unit.type().uiIcon).row();
			// table.label(() -> "(" + unit.x + ", " + unit.y + ')');
		}
		static final double sqrt2 = Math.sqrt(2);
		public TextureRegion getRegion(T unit) {
			return iconMap.get(unit.hitSize, () -> {
				int   size  = (int) (unit.hitSize * sqrt2 * 4 * 2);
				float thick = 9f;
				return drawRegion(size, size, () -> {
					MyDraw.square(size / 2f, size / 2f, size * 2 / (float) tilesize - 1, thick, Pal.items);
				});
			});
		}

		public TextureRegion getIcon(T key) {
			return key.type.uiIcon;
		}
		public boolean checkRemove(T item) {
			return !item.isAdded();
		}
	}
	public class BuildFunction<T extends Building> extends Function<T> {
		public BuildFunction(String name) {
			super(name, buildWD);
		}

		@Override
		public TextureRegion getRegion(T building) {
			return iconMap.get((float) building.block.size, () -> {
				int size  = building.block.size * 32;
				int thick = 6, rsize = size + thick;
				return drawRegion(rsize, rsize, () -> {
					MyDraw.dashSquare(thick, Pal.accent, rsize / 2f, rsize / 2f, size);
				});
			});
		}
		public float rotation(T item) {
			return 45;
		}
		public TextureRegion getIcon(T key) {
			return key.block.uiIcon;
		}
		public void buildTable(T build, Table table) {
			/*Table cont = new Table();
			// building.display(cont);
			table.add(cont).row();*/
			table.image(Icon.starSmall).size(10).color(build.team.color).colspan(0);
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
		public boolean checkRemove(T item) {
			return item.tile.build != item;
		}
		Cons2<Liquid, String> getLiquidSetter(T build) {
			return (l, str) -> build.liquids.set(l, Tools.asFloat(str));
		}
		Cons2<Item, String> getItemSetter(T build) {
			return (i, str) -> build.items.set(i, Tools.asInt(str));
		}

	}
	public class TileFunction<T extends Tile> extends Function<T> {
		public TileFunction(String name) {
			super(name, tileWD);
		}
		public float rotation(T item) {
			return 30;
		}

		TextureRegion region;
		public TextureRegion getRegion(T tile) {
			if (region == null) {
				int size  = tilesize * 4;
				int thick = 5, rsize = size + thick;
				region = drawRegion(rsize, rsize, () -> {
					MyDraw.dashSquare(thick, Pal.heal, (rsize) / 2f, (rsize) / 2f, size);
				});
			}
			return region;
		}

		public TextureRegion getIcon(T key) {
			return key.block() == Blocks.air ? key.floor().uiIcon : key.block().uiIcon;
		}
		public void buildTable(T tile, Table t) {
			// tile.display(table);
			// table.row();
			t.left().defaults().left().padRight(4f);
			t.image(tile.block() == Blocks.air ? null : new TextureRegionDrawable(tile.block().uiIcon)).size(24);
			t.add(tile.block().name).with(JSFunc::addDClickCopy);
			t.add("(" + tile.x + ", " + tile.y + ")")
			 .fontScale(0.9f).color(Color.lightGray);
			if (tile.overlay().itemDrop != null) t.image(tile.overlay().itemDrop.uiIcon).size(24);
			if (tile.floor().liquidDrop != null) t.image(tile.floor().liquidDrop.uiIcon).size(24);
		}

		public IntMap<TextureRegion> map = new IntMap<>();

		public boolean checkRemove(T item) {
			return item == null;
		}
		public Vec2 getPos(T item) {
			return Tmp.v3.set(item.worldx(), item.worldy());
		}
	}

	public final Task clearFocusElem = new Task() {
		public void run() {
			focusElem = null;
		}
	};


	public abstract class Function<T> {
		public final Table   wrap    = new Table();
		public final Table   main    = new Table();
		public final Table   buttons = new Table();
		public       List<T> list    = new MyVector();


		// for select
		public        Seq<Seq<T>> select      = new Seq<>();
		private final Runnable    changeEvent = () -> MyEvents.fire(this);
		public final  String      name;
		public        WorldDraw   WD;

		public TemplateTable<Seq<T>> template;

		public ObjectMap<Float, TextureRegion> iconMap = new ObjectMap<>();

		private final ExecutorService                  executor;
		private final ObjectMap<TextureRegion, Seq<T>> selectMap = new ObjectMap<>();
		private       boolean                          drawAll   = true;

		public Function(String name, WorldDraw WD) {
			this.name = name;
			this.WD = WD;
			Tools.TASKS.add(() -> WD.alpha = selectFunc == this ? 0.7f : 0.1f);
			executor = Threads.boundedExecutor(name + "-each", 1);

			main.button("show all", IntStyles.blackt, this::showAll).growX().height(buttonHeight).row();
			main.add(buttons).growX().row();
			buildButtons();

			MyEvents.on(this, () -> {
				template.clear();
				select.clear();
				selectMap.clear();
				Tools.each(list, t -> {
					selectMap.get(getIcon(t), Seq::new).add(t);
				});
				int i = 0;
				for (Entry<TextureRegion, Seq<T>> entry : selectMap) {
					var value = new SeqBind(entry.value);
					selectMap.put(entry.key, value);
					template.bind(value);
					class NewBtn extends Button {
						public NewBtn() {
							super(new ButtonStyle(Styles.flatTogglet));
						}
						static final NinePatchDrawable tinted = ((NinePatchDrawable) Styles.flatDown).tint(Color.pink);

						static {
							tinted.setLeftWidth(0);
							tinted.setRightWidth(0);
							tinted.setTopHeight(0);
							tinted.setBottomHeight(0);
						}

						boolean uiShowing = false;
						void toggleShowing() {
							if (uiShowing) {
								uiShowing = false;
								getStyle().checked = Styles.flatDown;
							} else {
								uiShowing = true;
								getStyle().checked = tinted;
							}
						}
					}
					;
					var btn = new NewBtn();
					btn.update(() -> {
						btn.setChecked(btn.uiShowing || select.contains(value, true));
					});
					IntUI.doubleClick(btn, () -> {
						if (select.contains(value, true)) select.remove(value);
						else select.add(value);
					}, () -> {
						btn.toggleShowing();
						IntUI.showSelectTable(btn, (p, hide, str) -> {
							int c = 0;
							for (T item : value) {
								p.add(new MyLimitTable(item, t -> {
									t.image(getIcon(item));
								}));
								if (++c % 6 == 0) p.row();
							}
						}, false).hidden(btn::toggleShowing);
					});
					btn.add(new ItemImage(entry.key, value.size)).grow().pad(6f);
					template.add(btn);
					template.unbind();
					if (++i % 4 == 0) template.newLine();
				}
			});

			template = new TemplateTable<>(null, list -> list.size != 0);
			template.top().defaults().top();
			main.add(template).grow().row();
			template.addAllCheckbox(main);
			wrap.update(() -> {
				if (E_Selection.valueOf(name).enabled()) {
					setup();
				} else {
					remove();
				}
			});

			allFunctions.put(name, this);
			main.update(() -> selectFunc = this);

			FunctionBuild("copy", list -> {
				tester.put(Core.input.mouse(), list.toArray());
			});
		}
		private void buildButtons() {
			buttons.defaults().height(buttonHeight).growX();
			buttons.button("refresh", Icon.refreshSmall, Styles.flatt, () -> {
				MyEvents.fire(this);
			});
			buttons.button("all", Icon.menuSmall, Styles.flatTogglet, () -> {}).with(b -> b.clicked(() -> {
				 boolean all = select.size != selectMap.size;
				 select.clear();
				 if (all) for (var entry : selectMap) select.add(entry.value);
			 })).update(b -> b.setChecked(select.size == selectMap.size))
			 .row();
			buttons.button("run", Icon.okSmall, Styles.flatt, () -> {}).with(b -> b.clicked(() -> {
				showMenuList(getMenuLists(this, mergeList()));
			})).disabled(__ -> select.isEmpty());
			buttons.button("filter", Icon.filtersSmall, Styles.flatt, () -> {
				JSRequest.requestForSelection(mergeList(), null, boolf -> {
					select.each(seq -> seq.filter((Boolf) boolf));
				});
			}).row();
			buttons.button("drawAll", Icon.menuSmall, Styles.flatTogglet, () -> {
				drawAll = !drawAll;
			}).update(t -> t.setChecked(drawAll));
			buttons.button("clearAll", Icon.trash, Styles.flatt, () -> {
				clearList();
				changeEvent.run();
			}).update(t -> t.setChecked(drawAll)).row();
		}

		private List<T> mergeList() {
			Seq<T> seq = new Seq<>();
			select.each(seq::addAll);
			return seq.list();
		}

		public abstract TextureRegion getIcon(T key);
		public abstract TextureRegion getRegion(T t);

		public void setting(Table t) {
			t.check(name, E_Selection.valueOf(name).enabled(), b -> {
				if (b) setup();
				else remove();

				hide();
				E_Selection.valueOf(name).set(b);
			});
		}

		public void remove() {
			wrap.clearChildren();
		}

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


		public void each(Consumer<? super T> action) {
			each(list, action);
		}

		public void each(List<T> list, Consumer<? super T> action) {
			if (((ThreadPoolExecutor) executor).getActiveCount() >= 2) {
				IntUI.showException(new RejectedExecutionException("There's already 2 tasks running."));
				return;
			}
			executor.submit(() -> {
				Tools.each(list, t -> {
					new LerpFun(Interp.linear).onWorld().rev()
					 .registerDispose(1 / 20f, fin -> {
						 Draw.color(Pal.accent);
						 Vec2 pos = getPos(t);
						 Lines.stroke(3f - fin * 2f);
						 TextureRegion region = getRegion(t);
						 Lines.square(pos.x, pos.y,
							fin * Mathf.dst(region.width, region.height) / tilesize);
					 });
					Core.app.post(() -> action.accept(t));
					Threads.sleep(1);
				});
			});
		}
		public void removeIf(List<T> list, Predicate<? super T> action) {
			list.removeIf(action);
		}
		public final void clearList() {
			if (!WD.drawSeq.isEmpty()) WD.drawSeq.clear();
			if (!list.isEmpty()) list.clear();
		}
		public void setup() {
			if (main.parent == wrap) return;
			wrap.add(main).grow();
		}
		public final void showAll() {
			new ShowAllWindow().show();
		}

		public abstract void buildTable(T item, Table table);

		public final void add(T item) {
			Core.app.post(() -> {
				TaskManager.acquireTask(15, changeEvent);
			});
			list.add(item);
			if (drawSelect) {
				// 异步无法创建FrameBuffer
				Core.app.post(() -> Core.app.post(() -> afterAdd(item)));
			}
		}


		public final void afterAdd(T item) {
			TextureRegion region = getRegion(item);
			new BindBoolp(item, () -> {
				if (checkRemove(item)) {
					return false;
				}
				Vec2 pos = getPos(item);
				/* 判断是否在相机内 */
				if (!CAMERA_RECT.overlaps(pos.x, pos.y, region.width, region.height)) return true;
				if (drawAll || select.contains(t -> t.contains(item, true))) {
					Draw.rect(region, pos.x, pos.y, rotation(item));
				}
				return true;
			});
		}
		/** 返回{@code true}如果需要删除 */
		public abstract boolean checkRemove(T item);
		public Vec2 getPos(T item) {
			if (item instanceof Posc) return Tmp.v3.set(((Posc) item).x(), ((Posc) item).y());
			throw new RuntimeException("you don't overwrite it.");
		}
		public float rotation(T item) {
			return 0;
		}


		protected final ObjectMap<String, Cons<List<T>>> FUNCTIONS = new OrderedMap<>();

		public <R extends UnlockableContent> void ListFunction(
		 String name, Prov<Seq<R>> list,
		 Cons<Table> builder, Cons2<List<T>, R> cons) {
			FunctionBuild(name, from -> {
				var table = IntUI.showSelectImageTableWithFunc(
				 Core.input.mouse().cpy(), list.get(), () -> null,
				 n -> cons.get(from, n), 42f, 32, 6, t -> new TextureRegionDrawable(t.uiIcon), true);
				if (builder != null) builder.get(table);
			});
		}
		/** 这个exec的list是用来枚举的 */
		public void FunctionBuild(String name, Cons<List<T>> exec) {
			// TextButton button = new TextButton(name);
			// cont.add(button).height(buttonHeight).growX().row();

			FUNCTIONS.put(name, exec);
			// button.clicked(() -> {
			// 	clickedBtn = button;
			// 	exec.get(list);
			// });
		}
		public void TeamFunctionBuild(String name, Cons2<List<T>, Team> cons) {
			FunctionBuild(name, from -> {
				Team[]        arr   = Team.baseTeams;
				Seq<Drawable> icons = new Seq<>();

				for (Team team : arr) {
					icons.add(IntUI.whiteui.tint(team.color));
				}

				IntUI.showSelectImageTableWithIcons(Core.input.mouse().cpy(), new Seq<>(arr), icons, () -> null,
				 n -> cons.get(from, n), 42f, 32f, 3, false);
			});
		}


		public boolean onRemoved = false;
		private void onRemoved() {
			if (!onRemoved) Core.app.post(() -> onRemoved = false);
			onRemoved = true;
		}

		public class BindBoolp implements Boolp {
			public T     hashObj;
			public Boolp boolp;
			public BindBoolp(T hashObj, Boolp boolp) {
				this.hashObj = hashObj;
				this.boolp = boolp;
				WD.drawSeq.add(this);
			}
			public boolean get() {
				return (!onRemoved || list.contains(hashObj)) && boolp.get();
			}
			public int hashCode() {
				return hashObj.hashCode();
			}
			public boolean equals(Object obj) {
				return obj == hashObj;
			}
		}
		private class MyVector extends Vector<T> {
			protected void removeRange(int fromIndex, int toIndex) {
				super.removeRange(fromIndex, toIndex);
				onRemoved();
			}
			public boolean removeIf(Predicate<? super T> filter) {
				onRemoved();
				return super.removeIf(filter);
			}
			public boolean remove(Object o) {
				onRemoved();
				return super.remove(o);
			}
			public synchronized T remove(int index) {
				onRemoved();
				return super.remove(index);
			}
		}
		private class MyLimitTable extends LimitButton {
			public final Task clearFocusWorld = new Task() {
				public void run() {
					if (item instanceof Tile) focusTile = null;
					else if (item instanceof Building) focusBuild = null;
					else if (item instanceof Unit) focusUnits.remove((Unit) item);
					else if (item instanceof Bullet) focusBullets.remove((Bullet) item);
					focusDisabled = false;
				}
			};

			private final T item;

			public MyLimitTable(T item) {
				super(Styles.flati);
				margin(2, 4, 2, 4);
				this.item = item;

				touchable = Touchable.enabled;

				hovered(() -> {
					if (focusDisabled) return;
					focusElem = this;
					focusElemType = Function.this;
					if (item instanceof Tile) focusTile = (Tile) item;
					else if (item instanceof Building) focusBuild = (Building) item;
					else if (item instanceof Unit) focusUnits.add((Unit) item);
					else if (item instanceof Bullet) focusBullets.add((Bullet) item);
					focusDisabled = true;
				});
				exited(() -> {
					focusElem = null;
					focusElemType = null;
					clearFocusWorld.run();
				});
			}

			public void updateVisibility() {
				super.updateVisibility();
				if (focusDisabled || focusElem == this ||
						(focusTile != item && focusBuild != item
						 && !(item instanceof Unit && focusUnits.contains((Unit) item))
						 && !(item instanceof Bullet && focusBullets.contains((Bullet) item))
						)
				) return;

				focusElem = this;
				focusElemType = Function.this;
			}

			public MyLimitTable(T item, Cons<Table> cons) {
				this(item);
				cons.get(this);
			}
			/* public Element hit(float x, float y, boolean touchable) {
				Element tmp = super.hit(x, y, touchable);
				if (tmp == null) return null;

				focusElem = this;
				return tmp;
			} */

			public void draw() {
				super.draw();
				if (focusElem == this) {
					Draw.color(Pal.accent);
					Lines.stroke(4f);
					MyDraw.dashRect(x + width / 2f, y + height / 2f, width - 2, height - 2, Time.globalTime / 10f);
					// Fill.crect(x, y, width, height);
				}
			}

		}
		private class ShowAllWindow extends DisWindow {
			int c, cols = Vars.mobile ? 4 : 6;
			public ShowAllWindow() {
				super(Function.this.name, 0, 200, true);
				cont.pane(new LimitTable(table -> {
					for (T item : list) {
						var cont = new MyLimitTable(item);
						table.add(cont).minWidth(150);
						buildTable(item, cont);
						cont.row();
						cont.button("@details", IntStyles.blackt, () -> {
							 JSFunc.showInfo(item);
						 }).growX().height(buttonHeight)
						 .colspan(10);
						if (++c % cols == 0) {
							table.row();
						}
					}
				})).grow();
			}
		}
		private class SeqBind extends Seq<T> {
			final Seq<T> from;
			public SeqBind(Seq<T> from) {
				this.from = from;
				addAll(from);
			}
			public boolean equals(Object object) {
				return this == object && ((SeqBind) object).from == from;
			}
		}
	}

	final                Vec2 mouse      = new Vec2();
	final                Vec2 mouseWorld = new Vec2();
	private static final Rect TMP_RECT   = new Rect();


	public void drawFocus() {
		mouse.set(Core.input.mouse());
		mouseWorld.set(Core.camera.unproject(mouse));
		drawFocus(focusTile);
		drawFocus(focusBuild);
		focusUnits.each(this::drawFocus);
		focusBullets.each(this::drawFocus);
	}

	/** world -> ui(if transform) */
	boolean drawArrow = false, transform = true;
	Mat mat = new Mat();
	/** @see mindustry.graphics.OverlayRenderer#drawTop */
	public void drawFocus(Object focus) {
		if (focus == null) return;
		if (focus instanceof Seq<?> seq) {
			seq.each(this::drawFocus);
			return;
		}

		TextureRegion region;
		if (focus instanceof Tile) {
			Selection.getWorldRect((Tile) focus);
			region = Core.atlas.white();
		} else if (focus instanceof QuadTreeObject) {
			/** @see InputHandler#selectedUnit() */
			var box = (QuadTreeObject & Posc) focus;
			/** {@link QuadTreeObject#hitbox}以中心对称
			 * @see Rect#setCentered(float, float, float, float)
			 * @see Rect#setCentered(float, float, float) */
			box.hitbox(TMP_RECT);
			region = focus instanceof Building ? ((Building) focus).block.fullIcon :
			 focus instanceof Unit ? ((Unit) focus).icon() : Core.atlas.white();
			if (region != Core.atlas.white()) TMP_RECT.setSize(region.width, region.height);
			TMP_RECT.setPosition(box.x(), box.y());
		} else return;
		TMP_RECT.setSize(TMP_RECT.width / 4f, TMP_RECT.height / 4f);

		if (transform) {
			mat.set(Draw.proj());
			Draw.proj(Core.camera);
		}
		Draw.color();
		Draw.z(Layer.end);
		float x = TMP_RECT.x, y = TMP_RECT.y;
		float w = TMP_RECT.width, h = TMP_RECT.height;
		Draw.mixcol(focusColor, 1);
		Draw.alpha((region == Core.atlas.white() ? 0.7f : 0.9f) * focusColor.a);

		/* Vec2  tmp = transform ? Core.camera.project(TMP_RECT.x, TMP_RECT.y) : Tmp.v3.set(TMP_RECT.x, TMP_RECT.y);
		float x   = tmp.x, y = tmp.y, w, h;
		if (transform) {
			tmp = Core.camera.project(TMP_RECT.x + TMP_RECT.width, TMP_RECT.y + TMP_RECT.height);
			w = tmp.x - x;
			h = tmp.y - y;
		} else {
			w = TMP_RECT.width;
			h = TMP_RECT.height;
		} */

		Draw.rect(region, x, y, w, h,
		 !(focus instanceof BlockUnitc) && focus instanceof Unit u ? u.rotation - 90f : 0f);

		Draw.reset();
		Draw.color(Pal.accent);
		if (drawArrow) {
			Mathf.rand.setSeed(focus.hashCode());
			float off = Mathf.random() * 360;
			Mathf.rand.setSeed(new Random().nextLong());
			for (int i = 0; i < 4; i++) {
				float rot    = off + i * 90f + 45f + (-Time.time) % 360f;
				float length = Mathf.dst(w, h) * 1.5f + (1 * 2.5f);
				Draw.rect("select-arrow", x + Angles.trnsx(rot, length), y + Angles.trnsy(rot, length), length / 1.9f, length / 1.9f, rot - 135f);
			}
		}
		if (transform) Draw.proj(mat);

		if (focusElem != null && focusElemType != null && focusElemType.list.contains(focus)) {
			Lines.stroke(4f);
			Vec2 tmp0 = Core.camera.project(x, y);
			x = tmp0.x;
			y = tmp0.y;
			Vec2 vec2 = ElementUtils.getAbsPosCenter(focusElem);
			Lines.line(vec2.x, vec2.y, x, y);
			vec2 = ElementUtils.getAbsPos(focusElem);
			Lines.line(vec2.x, vec2.y, x, y);
		}
		Draw.reset();
	}

	private boolean focusDisabled, focusEnabled;
	public       Element           focusElem;
	public       Tile              focusTile;
	public       Building          focusBuild;
	public final ObjectSet<Unit>   focusUnits   = new ObjectSet<>();
	public final ObjectSet<Bullet> focusBullets = new ObjectSet<>();

	public final ObjectSet<Object> focusInternal = new ObjectSet<>();

	{
		otherWD.drawSeq.add(() -> {
			Gl.flush();
			if (Core.input.alt()) {
				Draw.alpha(0.3f);
			}
			drawFocusInternal();


			Element tmp = Core.scene.hit(Core.input.mouseX(), Core.input.mouseY(), true);
			focusEnabled = !topGroup.isSelecting() && (
			 tmp == null || tmp.isDescendantOf(focusW) || (!tmp.visible && tmp.touchable == Touchable.disabled)
			 // || tmp.isDescendantOf(el -> clName(el).contains("modtools.ui.IntUI"))
			 || tmp.isDescendantOf(topGroup.getTopG()));
			if (!focusEnabled) return true;
			if (!focusDisabled) {
				reacquireFocus();
			}

			return true;
		});
		topGroup.backDrawSeq.add(() -> {
			if (!focusEnabled && focusElem == null) return true;
			if (state.isGame()) {
				drawFocus();
			}
			return true;
		});
	}

	private void drawFocusInternal() {
		drawArrow = true;
		transform = false;
		focusInternal.each(this::drawFocus);
		transform = true;
		drawArrow = false;
	}

	private void reacquireFocus() {
		focusUnits.clear();
		focusBullets.clear();
		if (data().getBool("focusOnWorld")) {
			focusTile = world.tileWorld(mouseWorld.x, mouseWorld.y);
			focusBuild = focusTile != null ? focusTile.build : null;
			Groups.unit.each(u -> {
				if (mouseWorld.dst(u.x, u.y) < u.hitSize / 2f + 2)
					focusUnits.add(u);
			});
			Groups.bullet.each(b -> {
				if (mouseWorld.dst(b.x, b.y) < b.hitSize / 2f + 4)
					focusBullets.add(b);
			});
		} else {
			focusTile = null;
			focusBuild = null;
		}
	}

	public class FocusWindow extends NoTopWindow {
		public FocusWindow(String title) {
			super(title, 0, 42, false);
		}

		public float getWidth() {
			return width = super.getPrefWidth();
		}
		public float getHeight() {
			return height = super.getPrefHeight();
		}
		boolean updatePosUI = true;
		long    toggleDelay = 200, lastToggleTime = 0;

		public Table pane;

		{
			titleTable.remove();
			/* 禁用缩放和移动侦听器 */
			touchable = Touchable.childrenOnly;
			sclListener.disabled1 = true;
			moveListener.disabled = true;
			cont.update(() -> {
				if (updatePosUI && focusEnabled) updatePosUI();
				else updatePosWorld();
				clampPosition();
			});
			cont.pane(p -> pane = p).grow();
			buildCont0();
			Tools.TASKS.add(() -> {
				if (state.isMenu() || !data().getBool("focusOnWorld") || !focusEnabled) {
					hide();
				} else if (!isShown() && SclListener.fireElement == null) {
					show();
				}
				toBack();

				if (Vars.mobile || Time.millis() - lastToggleTime <= toggleDelay
						|| !Core.input.alt() || !Core.input.ctrl()) return;
				lastToggleTime = Time.millis();
				updatePosUI = !updatePosUI;
				focusDisabled = !updatePosUI;
			});
		}

		public void hide() {
			remove();
		}
		public Element hit(float x, float y, boolean touchable) {
			Element el = super.hit(x, y, touchable);
			if (Vars.mobile) {
				updatePosUI = el == null;
				focusDisabled = !updatePosUI;
			}
			return el;
		}
		private void buildCont0() {
			/* tile */
			newTable(t -> {
				buildCont(t, focusTile);
			});
			/* build */
			newTable(t -> {
				buildCont(t, focusBuild);
			});
			/* unit */
			newTable(t -> {
				buildCont(t, focusUnits);
			});
			/* bullet */
			newTable(t -> {
				buildCont1(t, focusBullets);
			});
		}
		private void newTable(Cons<Table> cons) {
			pane.table(t -> {
				t.act(0.1f);
				t.left().defaults().grow().left();
				t.update(() -> cons.get(t));
				t.background(t.getChildren().any() ? Tex.underlineOver : null);
			}).grow().row();
		}

		public Tile lastTile;
		private void addMoreButton(Table t, Object o) {
			t.button("More", Styles.flatBordert, () -> JSFunc.showInfo(o)).size(80, 24);
		}
		private void buildCont(Table table, Tile tile) {
			if (lastTile == tile) return;
			table.clearChildren();
			lastTile = tile;
			if (tile == null) return;

			table.table(t -> {
				IntUI.addShowMenuListener(t, () -> getMenuLists(tile));
				tiles.buildTable(tile, t);
				addMoreButton(t, tile);
			}).row();
		}

		public Building lastBuild;
		private void buildCont(Table table, Building build) {
			if (lastBuild == build) return;
			table.clearChildren();
			lastBuild = build;
			if (build == null) return;

			table.table(t -> {
				IntUI.addShowMenuListener(t, () -> getMenuLists(build));
				t.left().defaults().padRight(6f).growY().left();
				t.image(Icon.starSmall).size(20).color(build.team.color);
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

			unitSet.each(u -> table.table(Tex.underline, t -> {
				IntUI.addShowMenuListener(t, () -> getMenuLists(unitSet));
				t.left().defaults().padRight(6f).growY().left();
				t.image(Icon.starSmall).size(10).color(u.team.color);
				t.image(new TextureRegionDrawable(u.type.uiIcon)).size(24);
				t.add(u.type.name).with(JSFunc::addDClickCopy);

				buildPos(t, u);
				// t.add("pathfind:" + u.pathType());
				addMoreButton(t, u);
			}).growX().row());
		}

		public int lastBulletSize;
		private void buildCont1(Table table, ObjectSet<Bullet> bulletSet) {
			if (lastBulletSize == bulletSet.size) return;
			table.clearChildren();
			lastBulletSize = bulletSet.size;
			if (lastBulletSize == 0) return;

			bulletSet.each(u -> table.table(Tex.underlineDisabled, t -> {
				IntUI.addShowMenuListener(t, () -> getMenuLists0(bulletSet));
				t.left().defaults().padRight(6f).growY().left();
				t.image(Icon.starSmall).size(10).color(u.team.color).colspan(0);
				t.label(() -> u.time + "/" + u.lifetime).size(10).colspan(2).row();
				// t.add("" + u.type).with(JSFunc::addDClickCopy);

				buildPos(t, u);
				// t.add("pathfind:" + u.pathType());
				addMoreButton(t, u);
			}).growX().row());
		}

		private final Vec2 world = new Vec2();
		private void updatePosWorld() {
			Vec2 v1 = Core.camera.project(Tmp.v1.set(world));
			x = v1.x;
			y = v1.y;
		}
		public void clampPosition() {
			if (x + width > Core.scene.getWidth()) x -= width;
			if (y + height > Core.scene.getHeight()) y -= height;
		}
		private void updatePosUI() {
			Vec2 v1 = Core.input.mouse();
			/* 向右上偏移 */
			v1.add(2, 2);
			world.set(Core.camera.unproject(Tmp.v1.set(v1)));
			x = v1.x;
			y = v1.y;
		}
	}

	private <T> Seq<MenuList> getMenuLists(Function<T> function, List<T> list) {
		Seq<MenuList> seq = new Seq<>(function.FUNCTIONS.size);
		function.FUNCTIONS.each((k, r) -> {
			seq.add(MenuList.with(null, k, () -> r.get(list)));
		});
		return seq;
	}
	@SuppressWarnings("unchecked")
	private Seq<MenuList> getMenuLists0(ObjectSet<Bullet> bulletSet) {
		tmpList.clear();
		bulletSet.each(tmpList::add);
		return getMenuLists(bullets, tmpList);
	}
	@SuppressWarnings("unchecked")
	private Seq<MenuList> getMenuLists(ObjectSet<Unit> unitSet) {
		tmpList.clear();
		unitSet.each(tmpList::add);
		return getMenuLists(units, tmpList);
	}
	@SuppressWarnings("unchecked")
	private Seq<MenuList> getMenuLists(Building build) {
		tmpList.clear();
		tmpList.add(build);
		return getMenuLists(buildings, tmpList);
	}
	@SuppressWarnings("unchecked")
	private Seq<MenuList> getMenuLists(Tile tile) {
		tmpList.clear();
		tmpList.add(tile);
		return getMenuLists(tiles, tmpList);
	}


	private static void buildPos(Table table, Position u) {
		table.label(new PositionProv(() -> Tmp.v1.set(u),
			u instanceof Building ? "," : "\n"))
		 .fontScale(0.9f).color(Color.lightGray)
		 .get().act(0.1f);
	}
	private <T extends UnlockableContent, E> void sumItems(Seq<T> items, Func<T, E> func, Cons2<T, String> setter) {
		var watcher = JSFunc.watch();
		watcher.addAllCheckbox();
		items.each(i -> {
			if (i.id % 6 == 0) watcher.newLine();
			watcher.watchWithSetter(new TextureRegionDrawable(i.uiIcon),
			 () -> func.get(i),
			 setter == null ? null : str -> setter.get(i, str),
			 2);
		});
		watcher.show();
	}


	private static ExecutorService executor;
	private static ExecutorService acquireExecutor() {
		return executor == null || executor.isShutdown() ? executor = Threads.executor() : executor;
	}
	private class SelectListener extends WorldSelectListener {
		public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
			if (button != KeyCode.mouseLeft || Vars.state.isMenu()) {
				hide();
				move = false;
				return false;
			} else {
				super.touchDown(event, x, y, pointer, button);
				start.set(end);
				move = true;
				Time.runTask(2f, () -> {
					move = true;
				});
				otherWD.drawSeq.add(() -> {
					if (!show) return false;
					Draw.color(Pal.accent, 0.3f);
					draw();
					return true;
				});
				return show;
			}
		}
		public void touchUp(InputEvent event, float mx, float my, int pointer, KeyCode button) {
			if (!move) return;
			super.touchUp(event, mx, my, pointer, button);
			btn.setChecked(false);
			fragSelect.remove();

			/* if (!Core.input.alt()) {
				tiles.clearList();
				buildings.clearList();
				bullets.clearList();
				units.clearList();
			} */

			if (E_Selection.bullet.enabled()) {
				acquireExecutor().submit(() -> {
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
			if (E_Selection.unit.enabled()) {
				Groups.unit.each(unit -> {
					// 返回单位是否在所选区域内
					return start.x <= unit.x && end.x >= unit.x && start.y <= unit.y && end.y >= unit.y;
				}, unit -> {
					if (!units.list.contains(unit)) {
						units.add(unit);
					}
				});
			}

			clampWorld();

			boolean enabledTile  = E_Selection.tile.enabled();
			boolean enabledBuild = E_Selection.building.enabled();
			acquireExecutor().submit(() -> {
				for (float y = start.y; y < end.y; y += tilesize) {
					for (float x = start.x; x < end.x; x += tilesize) {
						Tile tile = world.tileWorld(x, y);
						if (tile == null) continue;

						if (enabledTile && !tiles.list.contains(tile)) {
							tiles.add(tile);
						}

						if (enabledBuild && tile.build != null && !buildings.list.contains(tile.build)) {
							buildings.add(tile.build);
						}
					}
				}
			});

			if (!pane.isShown()) {
				pane.setPosition(mx, my);
			}
			pane.show();
			show = false;
		}
	}
}
