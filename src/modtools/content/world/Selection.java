
package modtools.content.world;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.*;
import arc.math.geom.*;
import arc.math.geom.QuadTree.QuadTreeObject;
import arc.scene.Element;
import arc.scene.actions.Actions;
import arc.scene.event.*;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.struct.ObjectMap.Entry;
import arc.util.*;
import arc.util.pooling.Pool;
import mindustry.content.Blocks;
import mindustry.core.World;
import mindustry.entities.Units;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.InputHandler;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;
import mindustry.world.modules.*;
import modtools.IntVars;
import modtools.annotations.settings.SettingsInit;
import modtools.content.*;
import modtools.content.ui.ShowUIList.TotalLazyTable;
import modtools.events.ISettings;
import modtools.jsfunc.INFO_DIALOG;
import modtools.net.packet.HopeCall;
import modtools.ui.*;
import modtools.ui.TopGroup.*;
import modtools.ui.comp.*;
import modtools.ui.comp.Window.NoTopWindow;
import modtools.ui.comp.linstener.*;
import modtools.ui.comp.utils.MyItemSelection;
import modtools.ui.control.*;
import modtools.ui.effect.MyDraw;
import modtools.ui.gen.HopeIcons;
import modtools.ui.menu.MenuBuilder;
import modtools.utils.*;
import modtools.utils.MySettings.Data;
import modtools.utils.reflect.ClassUtils;
import modtools.utils.search.BindCell;
import modtools.utils.ui.CellTools;
import modtools.utils.world.*;

import java.util.function.Predicate;

import static arc.Core.scene;
import static mindustry.Vars.*;
import static modtools.ui.IntUI.*;
import static modtools.utils.ui.TmpVars.*;
import static modtools.utils.world.WFunction.buildPos;
import static modtools.utils.world.WorldDraw.drawRegion;

@SuppressWarnings({"CodeBlock2Expr", "DanglingJavadoc"})
public class Selection extends Content {
	public WFunction<?> selectFunc;
	public WFunction<?> focusElemType;
	public Selection() {
		super("selection", Icon.craftingSmall);
		WFunction.init(this);
	}

	interface WDINSTANCE {
		WorldDraw
		 unit   = new WorldDraw(Layer.weather, "unit"),
		 tile   = new WorldDraw(Layer.darkness + 1, "tile"),
		 build  = new WorldDraw(Layer.darkness + 2, "build"),
		 bullet = new WorldDraw(Layer.bullet + 5, "bullet"),
		 other  = new WorldDraw(Layer.darkness + 5, "other");
	}

	public Element fragSelect;
	public Window  ui;
	// public Table functions;
	public Team    defaultTeam;
	/** select元素（用于选择）是否显示 **/
	boolean isSelecting        = false;
	boolean isDynamicSelecting = false;
	/**
	 * 动态更新的选区，格式(x1, y1, x2, y2)
	 * @see SelectListener#touchUp(InputEvent, float, float, int, KeyCode)
	 */
	public Seq<Rect> dynamicSelectRegions = new Seq<>();
	/** 鼠标是否移动，形成矩形 */
	boolean mouseChanged = false;
	public boolean drawSelect = true;

	public static final int
	 buttonWidth  = 200,
	 buttonHeight = 45;

	public TileFunction<Tile>      tiles;
	public BuildFunction<Building> buildings;
	public WFunction<Unit>         units;
	public WFunction<Bullet>       bullets;
	public WFunction<Entityc>      others;

	/** @see Settings */
	public static OrderedMap<String, WFunction<?>> allFunctions = new OrderedMap<>();

	public void loadSettings(Data data) {
		Contents.settings_ui.addSection(localizedName(), icon, new SettingsTable(data));
	}

	SelectListener listener;
	public void lazyLoad() {
		fragSelect = new BackElement();
		fragSelect.name = "SelectionElem";
		// fragSelect.update(() -> fragSelect.toFront());
		fragSelect.touchable = Touchable.enabled;

		fragSelect.setFillParent(true);
		fragSelect.addListener(listener = new SelectListener());

		loadUI();
	}
	public void load() {
		loadSettings();
		TaskManager.forceRun(() -> {
			if (!state.isGame()) return false;
			initTask();
			loadFocusWindow();
			initFunctions();
			return true;
		});
	}

	FocusWindow focusW;
	void loadFocusWindow() {
		if (focusW == null) focusW = new FocusWindow("Focus");
	}

	void loadUI() {
		int minH = 300;
		ui = new IconWindow(buttonWidth * 1.5f/* two buttons */,
		 minH, false);
		ui.shown(() -> Time.runTask(3, ui::display));
		ui.hidden(this::hide);
		ui.update(() -> {
			if (state.isMenu()) {
				ui.hide();
			}
		});

		/* 初始化functions */
		initFunctions();


		var tab = new IntTab(CellTools.unset, allFunctions.orderedKeys().toArray(String.class),
		 Color.sky,
		 ArrayUtils.map2Arr(Table.class, allFunctions, e -> e.value.wrap),
		 1, true);
		tab.setIcons(HopeIcons.tile, HopeIcons.building, Icon.unitsSmall, Icon.gridSmall, Icon.folderSmall);
		tab.setPrefSize(260, CellTools.unset);
		Table cont = ui.cont;
		cont.update(() -> {
			tab.getLabels().each((name, l) -> {
				l.color.set(Settings.valueOf(name).enabled() ? Color.white : Color.lightGray);
			});
		});
		cont.button("StaticSelect", HopeStyles.squareTogglet, () -> {
			isSelecting = !isSelecting;
			isDynamicSelecting = false;
			ElementUtils.addOrRemove(fragSelect, isSelecting);
		}).growX().minWidth(170).height(40).checked(_ -> isSelecting && !isDynamicSelecting);
		TextButton btn = cont.button("DynamicSelect", HopeStyles.squareTogglet, () -> {
			 isSelecting = !isSelecting;
			 isDynamicSelecting = true;
			 ElementUtils.addOrRemove(fragSelect, isSelecting);
		 })
		 .growX().minWidth(170).height(40).checked(_ -> isSelecting && isDynamicSelecting).get();
		SettingsUI.tryAddTip(btn, name + ".dynamicselect");
		cont.row();
		cont.left().add(tab.build())
		 .colspan(2)
		 .grow().left();
		for (Entry<String, Label> label : tab.getLabels()) {
			label.value.setAlignment(Align.left);
		}
	}
	private void initFunctions() {
		if (tiles != null) return;
		tiles = new TileFunction<>("tile") {{
			ListFunction("@selection.reset", () -> content.blocks(), null, (list, block) -> {
				each(list, tile -> WorldUtils.setBlock(tile, block));
			});
			FunctionBuild("@clear", list -> {
				each(list, WorldUtils::setAir);
			});
			ListFunction("@selection.setfloor",
			 () -> content.blocks().select(block -> block instanceof Floor), null, (list, floor) -> {
				 each(list, tile -> HopeCall.setFloor(tile, floor));
			 });
			ListFunction("@selection.setfloorUnder",
			 () -> content.blocks().select(block -> block instanceof Floor && !(block instanceof OverlayFloor)),
			 null, (list, floor) -> {
				 each(list, tile -> HopeCall.setFloorUnder(tile, floor));
			 });
			ListFunction("@selection.setoverlay",
			 () -> content.blocks().select(block -> block instanceof OverlayFloor || block == Blocks.air),
			 null, (list, overlay) -> {
				 each(list, tile -> HopeCall.setOverlay(tile, overlay));
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
			ListFunction("@selection.reset", () -> content.blocks(), null, (list, block) -> {
				each(list, b -> b.tile.setBlock(block));
			});
			ListFunction("@selection.items", () -> content.items(), Selection::intField, (list, item) -> {
				if (!NumberHelper.notNegativeInt(tmpAmount[0])) return;
				int amount = NumberHelper.asInt(tmpAmount[0]);
				each(list, b -> {
					if (b.items == null) return;
					Call.setItem(b, item, amount);
				});
			});
			ListFunction("@selection.liquids", () -> content.liquids(), Selection::floatField, (list, liquid) -> {
				if (!NumberHelper.isPositiveFloat(tmpAmount[0])) return;
				float amount = NumberHelper.asFloat(tmpAmount[0]);
				each(list, b -> {
					if (b.liquids == null) return;
					b.liquids.add(liquid, amount - b.liquids.get(liquid));
				});
			});
			FunctionBuild("@selection.sumitems", list -> {
				sumItems(content.items(), i -> ArrayUtils.sum(list, b -> b.items == null ? 0 : b.items.get(i)),
				 list.size() == 1 ? getItemSetter(list.get(0)) : null);
			});
			FunctionBuild("@selection.sumliquids", list -> {
				sumItems(content.liquids(), l -> ArrayUtils.sumf(list, b -> b.liquids == null ? 0 : b.liquids.get(l)),
				 list.size() == 1 ? getLiquidSetter(list.get(0)) : null);
			});
			FunctionBuild("@kill", list -> {
				removeAll(list, killCons());
			});
			FunctionBuild("@clear", list -> {
				removeAll(list, b -> {
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
					if (player.unit() == unit) player.team(team);
					unit.team(team);
				});
			});
			FunctionBuild("@stat.healing", list -> {
				each(list, Healthc::heal);
			});
			FunctionBuild("@kill", list -> {
				removeAll(list, UnitUtils::kill);
			});
			FunctionBuild("@clear", list -> {
				removeAll(list, UnitUtils::clear);
			});
			FunctionBuild("@selection.forceClear", list -> {
				removeAll(list, UnitUtils::forceRemove);
			});
			FunctionBuild("@selection.status.clear", (list) -> {
				each(list, Statusc::clearStatuses);
			});
			ListFunction("@selection.status.apply", () -> content.statusEffects(), Selection::floatField, (list, status) -> {
				if (!NumberHelper.isPositiveFloat(tmpAmount[0])) return;
				float time = NumberHelper.asFloat(tmpAmount[0]);
				each(list, b -> {
					b.apply(status, time);
				});
			});
		}};
		bullets = new BulletFunction<>("bullet") {{
			FunctionBuild("@clear", list -> {
				removeAll(list, BulletUtils::clear);
			});
		}};
		others = new EntityFunction<>("others") {{
			FunctionBuild("@clear", list -> {
				removeAll(list, entity -> {
					try {
						entity.remove();
						return !entity.isAdded();
					} catch (Exception ignored) { }
					return false;
				});
			});
		}};
	}
	private static Predicate<Building> killCons() {
		return b -> {
			if (b.tile.build == b) b.kill();
			return b.tile.build == null;
		};
	}

	public void build() {
		ui.show();
	}
	public void hide() {
		if (ui == null) return;
		fragSelect.remove();
		isSelecting = false;
		dynamicSelectRegions.clear();
	}
	public Button buildButton(boolean isSmallized) {
		Button btn = super.buildButton(isSmallized);
		btn.setDisabled(() -> state.isMenu());
		return btn;
	}

	public static void getWorldRect(Tile t) {
		mr1.set(t.worldx(), t.worldy(), 32, 32);
	}

	public abstract class BaseEntityFunction<T> extends WFunction<T> {
		public BaseEntityFunction(String name, WorldDraw draw) {
			super(name, draw);
		}
	}
	public class EntityFunction<T extends Entityc> extends BaseEntityFunction<T> {
		public EntityFunction(String name) {
			super(name, WDINSTANCE.other);
		}

		public void buildTable(T t, Table table) {
			if (t instanceof Position) buildPos(table, (Position) t);
		}

		public TextureRegion getRegion(T t) {
			float hitSize = t instanceof Hitboxc ? ((Hitboxc) t).hitSize() : 4;
			return iconMap.get(hitSize, () -> {
				int   size  = (int) (hitSize * 1.4f * 4 * 2);
				float thick = 12f;
				return drawRegion(size, size, () -> {
					MyDraw.square(size / 2f, size / 2f, size * 2 / (float) tilesize - 1, thick, Color.cyan);
				});
			});
		}
		public CharSequence getTips(T item) {
			return ClassUtils.getSuperExceptAnonymous(item.getClass()).getSimpleName();
		}
		public Vec2 getPos(T item) {
			if (item instanceof PowerGraphUpdaterc) return Tmp.v3.set(-1, -1);
			try {
				return super.getPos(item);
			} catch (Exception e) {
				return Tmp.v3.set(0, 0);
			}
		}
		TextureRegion[] icons = new TextureRegion[9];
		public TextureRegion lazyGetIcon(int i) {
			if (icons[i] != null) return icons[i];
			String text = switch (i) {
				case 0 -> "Fire";
				case 1 -> "Label";
				case 2 -> "Player";
				case 3 -> "Puddle";
				case 4 -> "Weather";
				case 5 -> "Drawc";
				case 6 -> "Pool";
				case 7 -> "Graph";
				case 8 -> "Sync";
				default -> throw new IllegalStateException("Unexpected value: " + i);
			};
			@SuppressWarnings("StringTemplateMigration")
			String key =
			 IntVars.modName + "-" + text.toLowerCase() // todo: fix the bug
			 // NPX."\{text.toLowerCase()}"
			 ;
			if (Core.atlas.has(key)) {
				return icons[i] = Core.atlas.find(key);
			}

			return icons[i] = drawRegion(48, 48, () -> {
				Draw.color();
				Fonts.def.draw(text, 8, 36, Color.white, 1, false, Align.left);
			});
		}

		@SuppressWarnings("unused")
		public TextureRegion getIcon(T key) {
			return switch (key) {
				case Fire fire -> lazyGetIcon(0);
				case WorldLabel worldLabel -> lazyGetIcon(1);
				case Player player1 -> lazyGetIcon(2);
				case Puddle puddle -> lazyGetIcon(3);
				case WeatherState weatherState -> lazyGetIcon(4);
				case Drawc drawc -> lazyGetIcon(5);
				case Pool.Poolable poolable -> lazyGetIcon(6);
				case PowerGraphUpdaterc powerGraphUpdaterc -> lazyGetIcon(7);
				case Syncc syncc -> lazyGetIcon(8);
				case null, default -> Core.atlas.white();
			};
		}
		public boolean checkRemove(T item) {
			return !item.isAdded();
		}

		public void setFocus(T t) {
			focusEntities.add(t);
		}
		public boolean checkFocus(T item) {
			return focusEntities.contains(item);
		}
		public void clearFocus(T item) {
			focusEntities.remove(item);
		}
	}
	public class BulletFunction<T extends Bullet> extends WFunction<T> {
		public BulletFunction(String name) {
			super(name, WDINSTANCE.bullet);
		}

		public void buildTable(T bullet, Table table) {
			table.add(String.valueOf(bullet.type)).row();
			buildPos(table, bullet);
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

		final ObjectMap<Class<?>, TextureRegion> class2icon = new ObjectMap<>();
		public CharSequence getTips(T item) {
			return item.type == null ? "error" : ClassUtils.getSuperExceptAnonymous(item.type.getClass()).getSimpleName();
		}
		public TextureRegion getIcon(T key) {
			if (key.type == null) return Core.atlas.find("error");
			return class2icon.get(key.type.getClass(), () -> {
				return drawRegion(32, 32, () -> {
					Mathf.rand.setSeed(key.type.id * 9999);
					Draw.color(Mathf.random(), Mathf.random(), Mathf.random(), 1);
					Fill.crect(0, 0, 32, 32);
				});
			});
		}
		public boolean checkRemove(T item) {
			return !item.isAdded();
		}
		public void setFocus(T t) {
			focusBullets.add(t);
		}
		public boolean checkFocus(T item) {
			return focusBullets.contains(item);
		}
		public void clearFocus(T item) {
			focusBullets.remove(item);
		}
	}
	public class UnitFunction<T extends Unit> extends WFunction<T> {
		public UnitFunction(String name) {
			super(name, WDINSTANCE.unit);
		}

		public void buildTable(T unit, Table table) {
			table.image(icon(unit.type())).row();
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

		@SuppressWarnings("StringTemplateMigration")
		public CharSequence getTips(T item) {
			return item.type.localizedName + "\n" + item.type.name;
		}
		public TextureRegion getIcon(T key) {
			return key.type.uiIcon;
		}
		public boolean checkRemove(T item) {
			return !item.isAdded();
		}

		public void setFocus(T t) {
			focusUnits.add(t);
		}
		public boolean checkFocus(T item) {
			return focusUnits.contains(item);
		}
		public void clearFocus(T item) {
			focusUnits.remove(item);
		}
	}
	public class BuildFunction<T extends Building> extends WFunction<T> {
		public BuildFunction(String name) {
			super(name, WDINSTANCE.build);
		}

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
		@SuppressWarnings("StringTemplateMigration")
		public CharSequence getTips(T item) {
			return item.block.localizedName + "\n" + item.block.name;
		}

		boolean inited = false;
		final Position p = new Position() {
			public float getX() {
				return currentBuild().x;
			}
			public float getY() {
				return currentBuild().y;
			}
		};
		public void buildTable(T build, Table table) {
			currentBuild = build;
			if (currentBuild == null) return;// 应该由外部控制，整个删除，不能再Update，否则可能NPE
			if (inited) {
				return;
			}
			inited = true;
			buildTableInternal(table);
		}
		@SuppressWarnings("DataFlowIssue")
		private void buildTableInternal(Table table) {
			/*Table cont = new Table();
			// building.display(cont);
			table.add(cont).row();*/
			table.table(t -> {
				t.image(Icon.starSmall).size(10).update(i -> i.setColor(currentBuild().team.color));
				t.label(() -> currentBuild().block.name);
				buildPos(t, p);
			}).growX().row();
			table.table(t -> {
				t.defaults().size(95, 42);
				var style = HopeStyles.flatt;
				t.button("@items.summary", style, () -> sumItems(content.items(),
				 i -> {
					 ItemModule items = currentBuild().items;
					 return items == null ? 0 : items.get(i);
				 },
				 (i, str) -> currentBuild().items.set(i, NumberHelper.asInt(str))));
				t.button("@liquids.summary", style, () -> sumItems(content.liquids(),
				 l -> {
					 LiquidModule liquids = currentBuild().liquids;
					 return liquids == null ? 0 : currentBuild().liquids.get(l);
				 },
				 (l, str) -> currentBuild().liquids.set(l, NumberHelper.asFloat(str))));
			});
		}
		public boolean checkRemove(T item) {
			return item.tile.build != item;
		}

		Cons2<Item, String> getItemSetter(T build) {
			return (i, str) -> build.items.set(i, NumberHelper.asInt(str));
		}
		Cons2<Liquid, String> getLiquidSetter(T build) {
			return (l, str) -> build.liquids.set(l, NumberHelper.asFloat(str));
		}

		public void setFocus(T t) {
			focusBuild = t;
		}
		public boolean checkFocus(T item) {
			return focusBuild == item;
		}
		public void clearFocus(T item) {
			focusBuild = null;
		}


		private Building currentBuild;
		public Building currentBuild() {
			return currentBuild;
		}
	}
	public class TileFunction<T extends Tile> extends WFunction<T> {
		public TileFunction(String name) {
			super(name, WDINSTANCE.tile);
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

		public Block getBlock(T key) {
			return key.block() == Blocks.air ? key.floor() : key.block();
		}
		public TextureRegion getIcon(T key) {
			return getBlock(key).uiIcon;
		}

		@SuppressWarnings("StringTemplateMigration")
		public CharSequence getTips(T item) {
			Block b = getBlock(item);
			return b.localizedName + "\n" + b.name;
		}
		Tile currentTile;
		public Tile currentTile() {
			return currentTile;
		}
		boolean  inited = false;
		Position u      = new Position() {
			public float getX() {
				return currentTile.x;
			}
			public float getY() {
				return currentTile.y;
			}
		};
		BindCell itemDropCell, liquidDropCell;
		@Override
		public void buildTable(T tile, Table t) {
			currentTile = tile;
			if (currentTile == null) return;// 应该由外部控制，整个删除，不能再Update，否则可能NPE
			if (inited) {
				if (itemDropCell != null) itemDropCell.toggle(currentTile.overlay().itemDrop != null);
				if (liquidDropCell != null) liquidDropCell.toggle(currentTile.floor().liquidDrop != null);
				return;
			}
			inited = true;
			buildTableInternal(t);
		}
		private void buildTableInternal(Table t) {
			if (currentTile == null) return;
			// tile.display(table);
			// table.row();
			t.left().defaults().left().padRight(4f);
			t.image().size(24).update(i -> i.setDrawable(WorldUtils.getToDisplay(currentTile).uiIcon));
			t.label(() -> currentTile.block().name).with(EventHelper::addDClickCopy);
			buildPos(t, u);
			itemDropCell = BindCell.ofConst(t.image().size(24).update(i -> {
				Item itemDrop = currentTile.overlay().itemDrop;
				if (itemDrop != null) i.setDrawable(itemDrop.uiIcon);
			}));
			liquidDropCell = BindCell.ofConst(t.image().size(24).update(i -> {
				Liquid liquidDrop = currentTile.floor().liquidDrop;
				if (liquidDrop != null) i.setDrawable(liquidDrop.uiIcon);
			}));
			itemDropCell.toggle(currentTile.overlay().itemDrop != null);
			liquidDropCell.toggle(currentTile.floor().liquidDrop != null);
		}
		public boolean checkRemove(T item) {
			return item == null;
		}
		public Vec2 getPos(T item) {
			return Tmp.v3.set(item.worldx(), item.worldy());
		}

		public void setFocus(T t) {
			focusTile = t;
		}
		public boolean checkFocus(T item) {
			return focusTile == item;
		}
		public void clearFocus(T item) {
			focusTile = null;
		}
	}


	public void drawFocus() {
		drawFocus(focusTile);
		drawFocus(focusBuild);
		focusUnits.each(this::drawFocus);
		focusBullets.each(this::drawFocus);
		focusEntities.each(this::drawFocus);
	}

	/** <p>World -> UI (if transform)</p> */
	private boolean transform = true;
	private boolean drawArrow = false;

	private final Mat mat = new Mat();
	/** @see OverlayRenderer#drawTop */
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
		} else if (focus instanceof QuadTreeObject box && focus instanceof Posc p) {
			/** @see InputHandler#selectedUnit() */
			/** {@link QuadTreeObject#hitbox}以中心对称
			 * @see Rect#setCentered(float, float, float, float)
			 * @see Rect#setCentered(float, float, float) */
			box.hitbox(mr1);
			region = focus instanceof Building b ? b.block.fullIcon :
			 focus instanceof Unit u ? u.icon() : Core.atlas.white();
			if (region != Core.atlas.white()) mr1.setSize(region.width, region.height);
			mr1.setPosition(p.x(), p.y());
		} else {
			return;
		}

		mr1.setSize(mr1.width / 4f, mr1.height / 4f);

		if (transform) {
			mat.set(Draw.proj());
			// world
			Draw.proj(Core.camera);
		}
		Draw.color();
		Draw.z(Layer.end);
		float x = mr1.x, y = mr1.y;
		float w = mr1.width, h = mr1.height;
		Draw.mixcol(focusColor, 0.7f);
		Draw.alpha((region == Core.atlas.white() ? 0.7f : 0.9f) * focusColor.a);

		Draw.rect(region, x, y, w, h,
		 !(focus instanceof BlockUnitc) && focus instanceof Unit u ? u.rotation - 90f
			: 90f);

		Draw.reset();
		Draw.color(Pal.accent);
		if (drawArrow) {
			float off = ((focus.hashCode() * 31) & 0xFFFF) % 360f;
			for (int i = 0; i < 4; i++) {
				float rot    = off + i * 90f + 45f + (-Time.time) % 360f;
				float length = Mathf.dst(w, h) * 1.5f + (1 * 2.5f);
				Draw.rect("select-arrow", x + Angles.trnsx(rot, length), y + Angles.trnsy(rot, length), length / 1.9f, length / 1.9f, rot - 135f);
			}
		}
		/* 恢复原来的proj */
		if (transform) Draw.proj(mat);
		// if (transform) Draw.proj(Core.camera.inv);

		if (focusElem != null && focusElem.getScene() != null
		    && ((!transform && focusAny != null) || (focusElemType != null && focusElemType.list.contains(focus)))) {
			if (transform) {
				drawLineOnScreen(x, y);
			} else {
				// 将screen映射到world
				Draw.proj(Core.camera.inv);
				// Fill.crect(0, 0, 100, 100);
				drawLineOnScreen(x, y);
				Draw.proj(Core.camera);
			}
		}

		Draw.reset();
	}
	private static void drawLineOnScreen(float worldX, float worldY) {
		Lines.stroke(4f);
		/* world -> screen */
		Vec2  tmp0 = Core.camera.project(worldX, worldY);
		Vec2  vec2 = ElementUtils.getAbsPosCenter(focusElem);
		float x1   = vec2.x, y1 = vec2.y;
		vec2 = ElementUtils.getAbsolutePos(focusElem);
		float x2 = vec2.x, y2 = vec2.y;
		Fill.tri(x1, y1, x2, y2, tmp0.x, tmp0.y);
		// Lines.line(vec2.x, vec2.y, screenX, screenY);
	}

	public boolean focusDisabled = false;
	boolean focusLocked = false;
	private       boolean focusEnabled;
	public static Element focusElem;
	/** 用于反应 元素 对应的 焦点 */
	public static Object  focusAny;

	public static void addFocusSource(Element source, Prov<Object> focusProv) {
		if (focusProv == null) throw new IllegalArgumentException("focusProv is null.");

		source.addListener(new HoverAndExitListener() {
			public void enter0(InputEvent event, float x, float y, int pointer, Element fromActor) {
				focusElem = source;
				focusAny = focusProv;
			}
			public void exit0(InputEvent event, float x, float y, int pointer, Element toActor) {
				if (toActor != null && source.isAscendantOf(toActor)) return;
				focusElem = null;
				focusAny = null;
			}
		});
	}

	public @Nullable Tile               focusTile;
	public @Nullable Building           focusBuild;
	public final     ObjectSet<Unit>    focusUnits    = new ObjectSet<>();
	public final     ObjectSet<Bullet>  focusBullets  = new ObjectSet<>();
	public final     ObjectSet<Entityc> focusEntities = new ObjectSet<>();
	public final     ObjectSet<Object>  focusInternal = new ObjectSet<>();

	// 动态选区扫描节流计时
	private int dynamicScanTimer = 0;

	public void initTask() {
		WorldUtils.uiWD.submit(() -> {
			WorldUtils.uiWD.alpha = Core.input.alt() ? 0.3f : 1f;

			if (ui != null && ui.isShown()) {
				for (Rect rect : dynamicSelectRegions) {
					MyDraw.dashRect(2, Color.sky, rect.x, rect.y, rect.width - rect.x, rect.height - rect.y);
				}
			}
			drawFocusInternal();
		});
		final Vec2 start = new Vec2(), end = new Vec2();
		/* 更新动态选区  */
		Tools.TASKS.add(() -> {
			if (state.isMenu() || dynamicSelectRegions.isEmpty()) return;
			if (++dynamicScanTimer >= 10) {
				dynamicScanTimer = 0;
				for (Rect rect : dynamicSelectRegions) {
					listener.updateRegion(
					 rect.getPosition(start),
					 rect.getSize(end)/* @see dynamicSelectRegions */,
					 false
					);
				}
			}
		});
		Tools.TASKS.add(() -> {
			if (state.isMenu()) return;
			Element hit = HopeInput.mouseHit();
			focusLocked = control.input.locked();
			focusEnabled = !focusLocked && !scene.hasDialog() && (
			 hit == null || hit.isDescendantOf(focusW) ||
			 (!hit.visible && hit.touchable == Touchable.disabled)
			 || hit.isDescendantOf(el -> el instanceof IInfo)
			 // || hit.isDescendantOf(el -> clName(el).contains("modtools.ui.IntUI"))
			 /* || hit instanceof IMenu */);
			if (!focusEnabled) return;
			if (!focusDisabled) {
				reacquireFocus();
			}
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
		if (focusAny != null) {
			Object focus = focusAny;
			if (focus instanceof Prov<?> p) focus = p.get();
			if (!drawFocusAny(focus) && !(focusAny instanceof Prov)) focusAny = null;
		}
		focusInternal.each(this::drawFocus);
		transform = true;
		drawArrow = false;
	}
	public boolean drawFocusAny(Object focus) {
		if (focus == null) return false;
		if (!focus.getClass().isArray()) {
			mr1.setPosition(Float.NaN, Float.NaN);
			drawFocus(focus);
			return !Float.isNaN(mr1.x) && !Float.isNaN(mr1.y);
		}

		boolean valid = false;
		if (focus instanceof Object[] arr) {
			for (Object child : arr) {
				if (drawFocusAny(child)) valid = true;
			}
		}
		return valid;
	}

	private void reacquireFocus() {
		focusUnits.clear();
		focusBullets.clear();
		focusEntities.clear();

		if (Settings.focusOnWorld.enabled()) {
			focusTile = world.tileWorld(IntVars.mouseWorld.x, IntVars.mouseWorld.y);
			focusBuild = focusTile != null ? focusTile.build : null;

			// 使用 Mindustry 原生四叉树直接范围查询，不遍历全图
			Units.nearby(IntVars.mouseWorld.x - 32, IntVars.mouseWorld.y - 32, 64, 64, u -> {
				if (IntVars.mouseWorld.dst(u.x, u.y) < u.hitSize / 2f + 2) {
					focusUnits.add(u);
				}
			});
			Groups.bullet.each(b -> {
				if (b != null && IntVars.mouseWorld.dst(b.x(), b.y()) < b.hitSize() / 2f + 4) {
					focusBullets.add(b);
				}
			});
		} else {
			focusTile = null;
			focusBuild = null;
		}
	}

	public class FocusWindow extends NoTopWindow {
		private final int b = 218;
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

		public Window show() {
			return show(scene, Actions.fadeIn(0.1f));
		}

		HKeyCode fixedKeyCode = keyCodeData().dynamicKeyCode("fixedWindow", () -> new HKeyCode(KeyCode.anyKey).ctrl().alt());

		{
			margin(5);
			titleTable.remove();
			/* 禁用缩放和移动侦听器 */
			touchable = Touchable.childrenOnly;
			sclListener.remove();
			cont.update(() -> {
				if (updatePosUI && focusEnabled) { updatePosUIAndWorld(); } else updatePosOnlyWorld();
				clampPosition();
			});
			cont.pane(Styles.smallPane, p -> pane = p).grow();
			buildCont0();
			Tools.TASKS.add(() -> {
				if (state.isMenu() || !Settings.focusOnWorld.enabled() || !focusEnabled) {
					hide();
				} else if (!isShown() && SclListener.fireElement == null) {
					show();
				}
				toBack();

				if (mobile || Time.millis() - lastToggleTime <= toggleDelay
				    || !fixedKeyCode.isPress()) { return; }

				lastToggleTime = Time.millis();
				updatePosUI = !updatePosUI;
				focusDisabled = !updatePosUI;
			});
		}

		public void hide() {
			if (!isShown()) return;
			if (!(this instanceof IDisposable)) screenshot();
			setOrigin(Align.center);
			setClip(false);

			hide(null);
		}
		public Element hit(float x, float y, boolean touchable) {
			Element el = super.hit(x, y, touchable);
			if (mobile) {
				updatePosUI = el == null;
				focusDisabled = !updatePosUI;
			}
			return el;
		}

		private void buildCont0() {
			pane.left().defaults().left();
			if (!mobile) pane.add(tipKey("fixed", fixedKeyCode.getText())).color(Color.lightGray).fontScale(0.8f).row();
			/* tile */
			newTable(t -> buildCont(t, focusTile));
			/* build */
			newTable(t -> buildCont(t, focusBuild));
			/* unit */
			newTable(t -> buildCont(t, focusUnits));
			/* bullet */
			newTable(t -> buildCont1(t, focusBullets));
		}
		private void newTable(Cons<Table> cons) {
			pane.table(t -> {
				t.act(0.1f);
				t.left().defaults().grow().left();
				t.background(t.getChildren().any() ? Tex.underlineOver : null);
			}).update(cons).grow().row();
		}

		private void addMoreButton(Table t, Object o) {
			t.button("More", HopeStyles.flatBordert, () -> INFO_DIALOG.showInfo(o)).size(80, 24);
		}
		/* private void freeMoreButton(Table table) {
			SnapshotSeq<Element> children = table.getChildren();
			for (Element elem : children.begin()) {
				if (elem instanceof MoreButton moreButton) {
					MoreButton.free(moreButton);
				}
				if (elem instanceof Table t) {
					freeMoreButton(t);
				}
			}
			children.end();
		} */

		private BindCell tileTableCell;
		private void buildCont(Table table, Tile tile) {
			if (tiles.currentTile() == tile) return;

			// freeMoreButton(table);
			if (tileTableCell != null) {
				tileTableCell.toggle(tile != null);
				tiles.buildTable(tile, (Table) tileTableCell.el);
				return;
			}

			table.clearChildren();
			tileTableCell = BindCell.ofConst(table.table(t -> {
				MenuBuilder.addShowMenuListenerp(t, () -> WFunction.getMenuLists(tiles.currentTile()));
				tiles.buildTable(tile, t);
				t.button("More", HopeStyles.flatBordert, () -> INFO_DIALOG.showInfo(tiles.currentTile())).size(80, 24);
			}));
			table.row();

		}

		private       BindCell      buildTableCell;
		private final StringBuilder sb = new StringBuilder(), bulletSb = new StringBuilder();
		private void buildCont(Table table, Building build) {
			if (buildings.currentBuild() == build) return;
			// freeMoreButton(table);
			if (buildTableCell != null) {
				buildTableCell.toggle(build != null);
				buildings.buildTable(build, (Table) buildTableCell.el);
				return;
			}

			buildTableCell = BindCell.ofConst(table.table(t -> {
				t.left().defaults().padRight(6f).growY().left();

				MenuBuilder.addShowMenuListenerp(t, () -> WFunction.getMenuLists(buildings.currentBuild()));
				buildings.buildTable(build, t);
				t.button("More", HopeStyles.flatBordert, () -> INFO_DIALOG.showInfo(buildings.currentBuild())).size(80, 24);
			}));
			table.row();
		}

		private final ObjectSet<Unit> cachedUnits = new ObjectSet<>();
		private void buildCont(Table table, ObjectSet<Unit> unitSet) {
			if (cachedUnits.equals(unitSet)) return;
			// freeMoreButton(table);
			table.clearChildren();
			cachedUnits.clear();
			cachedUnits.addAll(unitSet);
			if (unitSet.isEmpty()) return;

			int count = 0;
			for (Unit u : unitSet) {
				if (++count > 50) break;
				table.table(Tex.underline, t -> {
					MenuBuilder.addShowMenuListenerp(t, () -> WFunction.getMenuLists(u));
					t.left().defaults().padRight(6f).growY().left();
					t.table(t1 -> {
						t1.left().defaults().left();
						t1.image(Icon.starSmall).size(10).color(u.team.color);
						t1.image(new TextureRegionDrawable(u.type.uiIcon)).size(24);
						t1.add(u.type.name).with(EventHelper::addDClickCopy);
						buildPos(t1, u);
					});

					t.row();
					t.table(t1 -> {
						t1.left().defaults().left();
						t1.add("ID: ").color(Pal.accent);
						sb.setLength(0);
						sb.append(u.id).append("(unit);").append(u.type.id).append("(type)");
						t1.add(sb).color(Color.gray).fontScale(0.6f);
						sb.setLength(0);
						addMoreButton(t1, u);
					});
				}).growX().row();
			}
			;
		}

		private final ObjectSet<Bullet> cachedBullets = new ObjectSet<>();
		private void buildCont1(Table table, ObjectSet<Bullet> bulletSet) {
			if (cachedBullets.equals(bulletSet)) return;
			table.clearChildren();
			cachedBullets.clear();
			cachedBullets.addAll(bulletSet);
			if (bulletSet.isEmpty()) return;

			for (Bullet b : bulletSet) {
				table.table(Tex.underlineDisabled, t -> {
					MenuBuilder.addShowMenuListenerp(t, () -> WFunction.getMenuLists(b));
					t.left().defaults().padRight(6f).growY().left();
					t.image(Icon.starSmall).size(10).color(b.team.color).colspan(0);
					t.label(() -> {
						bulletSb.setLength(0);
						return bulletSb.append(b.time).append("[lightgray]/[]").append(b.lifetime);
					}).size(10).colspan(2).row();
					// t.add("" + b.type).with(JSFunc::addDClickCopy);

					buildPos(t, b);
					t.row();
					t.table(t1 -> {
						t1.left().defaults().left();
						t1.add("ID: ").color(Pal.accent);
						sb.setLength(0);
						t1.add(sb.append(b.id).append("(bullet); ").append(b.type.id).append("(type)")).color(Color.gray).fontScale(0.6f);
						sb.setLength(0);
						addMoreButton(t1, b);
					}).colspan(4);
				}).growX().row();
			}
		}

		private final Vec2 world = new Vec2();
		private void updatePosOnlyWorld() {
			Vec2 v1 = Core.camera.project(Tmp.v1.set(world));
			x = v1.x;
			y = v1.y;
		}
		public void clampPosition() {
			if (x + width > scene.getWidth()) x -= width;
			if (y + height > scene.getHeight()) y -= height;
		}
		private void updatePosUIAndWorld() {
			Vec2 v1 = Tmp.v1.set(IntVars.mouseVec)
			 /* 向右上偏移 */
			 .add(2, 2);
			x = v1.x;
			y = v1.y;
			world.set(Core.camera.unproject(v1));
		}
	}

	private final Vec2 cacheStart = new Vec2(), cacheEnd = new Vec2();
	class SelectListener extends WorldSelectListener {
		public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
			if (button != KeyCode.mouseLeft || state.isMenu()) {
				hide();
				mouseChanged = false;
				return false;
			}
			super.touchDown(event, x, y, pointer, button);
			start.set(end);
			mouseChanged = true;
			Core.app.post(() -> {
				mouseChanged = true;
			});
			WorldUtils.uiWD.drawSeq.add(() -> {
				if (!isSelecting) return false;
				Draw.color(Pal.accent, 0.3f);
				draw();
				return true;
			});
			return isSelecting;
		}
		public void touchUp(InputEvent event, float mx, float my, int pointer, KeyCode button) {
			if (!mouseChanged) return;
			super.touchUp(event, mx, my, pointer, button);
			isSelecting = false;
			fragSelect.remove();

			if (isDynamicSelecting) {
				dynamicSelectRegions.add(new Rect(start.x, start.y, end.x, end.y));
			}
			cacheStart.set(start);
			cacheEnd.set(end);
			Core.app.post(() -> {
				updateRegion(cacheStart, cacheEnd, true);
			});

			if (!ui.isShown()) {
				ui.setPosition(mx, my);
			}
			ui.show();
			isSelecting = false;
		}
		@SuppressWarnings("DuplicateBranchesInSwitch")
		protected void updateRegion(Vec2 start, Vec2 end, boolean includeTile) {
			float minX = Math.min(start.x, end.x), maxX = Math.max(start.x, end.x);
			float minY = Math.min(start.y, end.y), maxY = Math.max(start.y, end.y);
			float w    = maxX - minX, h = maxY - minY;

			// 1. 单位查询（使用四叉树，耗时约几微秒）
			if (Settings.unit.enabled()) {
				Units.nearby(minX, minY, w, h, u -> {
					if (u.x >= minX && u.x <= maxX && u.y >= minY && u.y <= maxY) {
						units.addUnique(u);
					}
				});
			}

			// 2. 子弹查询（直接遍历组，通常极快）
			if (Settings.bullet.enabled()) {
				Groups.bullet.each(b -> {
					if (b.x >= minX && b.x <= maxX && b.y >= minY && b.y <= maxY) {
						bullets.addUnique(b);
					}
				});
			}

			// 3. 其他实体
			if (Settings.others.enabled()) {
				Groups.all.each(e -> {
					if (e instanceof Building || e instanceof Bullet || e instanceof Unit) return;
					if (e instanceof Position p && p.getX() >= minX && p.getX() <= maxX && p.getY() >= minY && p.getY() <= maxY) {
						others.addUnique(e);
					}
				});
			}

			// 4. 瓦砾与建筑查询（网格快速整数运算，耗时不到 1ms）
			boolean enabledTile  = Settings.tile.enabled() && includeTile;
			boolean enabledBuild = Settings.building.enabled();

			if (enabledTile || enabledBuild) {
				int tileMinX = Math.max(0, World.toTile(minX));
				int tileMaxX = Math.min(world.width() - 1, World.toTile(maxX));
				int tileMinY = Math.max(0, World.toTile(minY));
				int tileMaxY = Math.min(world.height() - 1, World.toTile(maxY));

				for (int ty = tileMinY; ty <= tileMaxY; ty++) {
					for (int tx = tileMinX; tx <= tileMaxX; tx++) {
						Tile tile = world.tile(tx, ty);
						if (tile == null) continue;
						if (enabledTile) tiles.addUnique(tile);
						if (enabledBuild && tile.build != null) buildings.addUnique(tile.build);
					}
				}
			}
		}
	}

	@SettingsInit
	public enum Settings implements ISettings {
		tile, building, unit, bullet, others
		/* other */, focusOnWorld
	}
	class SettingsTable extends TotalLazyTable {
		int lastIndex;

		public SettingsTable(Data data) {
			super(t -> ((SettingsTable) t).initSetting(data), t -> ((SettingsTable) t).init(data));
		}
		public void initSetting(Data data) {
			defaultTeam = Team.get(data.getInt("defaultTeam", 1));
		}
		public void init(Data data) {
			lastIndex = 0;
			defaults().growX().left();
			table(t -> {
				t.left().defaults().left().padRight(4f).growX();
				allFunctions.each((k, func) -> {
					if (lastIndex++ % 3 == 0) t.row();
					func.setting(t);
				});
			}).row();
			table(t -> {
				t.left().defaults().left();
				t.add("@selection.default.team").color(Pal.accent).growX().left().row();
				MyItemSelection.buildTable0(t, Team.baseTeams, () -> defaultTeam, team -> {
					data.put("defaultTeam", team.id);
					defaultTeam = team;
				}, 3, team -> whiteui.tint(team.color)).setMinCheckCount(1);
			}).row();
			table(t -> {
				t.left().defaults().left();
				SettingsUI.checkboxWithEnum(t, "@settings.focusOnWorld", Settings.focusOnWorld).row();
				t.check("@settings.drawSelect", 28, drawSelect, b -> drawSelect = b)
				 .with(cb -> cb.setStyle(HopeStyles.hope_defaultCheck));
			}).row();
		}
	}

	static void intField(SelectTable t) {
		buildValidate(t, t.row().field("0", s -> tmpAmount[0] = s).valid(NumberHelper::notNegativeInt).get());
	}

	static void floatField(SelectTable t) {
		buildValidate(t, t.row().field("0", s -> tmpAmount[0] = s).valid(NumberHelper::isPositiveFloat).get());
	}

	static void buildValidate(SelectTable t, TextField field) {
		field.update(() -> t.hide = field.isValid() ? null : IntVars.EMPTY_RUN);
	}
}