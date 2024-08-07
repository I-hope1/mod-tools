
package modtools.content.world;

import arc.Core;
import arc.func.*;
import arc.graphics.*;
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
import arc.util.*;
import arc.util.pooling.Pool;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.InputHandler;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;
import modtools.IntVars;
import modtools.content.*;
import modtools.events.ISettings;
import modtools.jsfunc.INFO_DIALOG;
import modtools.net.packet.HopeCall;
import modtools.ui.*;
import modtools.ui.TopGroup.BackElement;
import modtools.ui.comp.*;
import modtools.ui.comp.Window.NoTopWindow;
import modtools.ui.comp.linstener.*;
import modtools.ui.control.*;
import modtools.ui.effect.MyDraw;
import modtools.ui.gen.HopeIcons;
import modtools.ui.menu.MenuBuilder;
import modtools.utils.*;
import modtools.utils.MySettings.Data;
import modtools.utils.reflect.ClassUtils;
import modtools.utils.world.*;

import java.util.Random;
import java.util.concurrent.ExecutorService;
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
	boolean isSelecting  = false;
	/** 鼠标是否移动，形成矩形 */
	boolean mouseChanged = false;
	public boolean drawSelect = true;

	public static final int
	 buttonWidth  = 200,
	 buttonHeight = 45;

	public WFunction<Tile>     tiles;
	public WFunction<Building> buildings;
	public WFunction<Unit>     units;
	public WFunction<Bullet>   bullets;
	public WFunction<Entityc>  others;

	/** @see Settings */
	public static OrderedMap<String, WFunction<?>> allFunctions = new OrderedMap<>();
	static void intField(SelectTable t) {
		buildValidate(t, t.row().field("0", s -> tmpAmount[0] = s)
		 .valid(NumberHelper::isPositiveInt)
		 .get());
	}
	static void floatField(SelectTable t) {
		buildValidate(t, t.row().field("0", s -> tmpAmount[0] = s)
		 .valid(NumberHelper::isPositiveFloat)
		 .get());
	}
	static void buildValidate(SelectTable t, TextField field) {
		field.update(() -> {
			t.hide = field.isValid() ? null : IntVars.EMPTY_RUN;
		});
	}

	public void loadSettings(Data data) {
		Contents.settings_ui.add(localizedName(), icon, new SettingsTable(data));
	}

	public void load() {
		fragSelect = new BackElement();
		fragSelect.name = "SelectionElem";
		// fragSelect.update(() -> fragSelect.toFront());
		fragSelect.touchable = Touchable.enabled;

		fragSelect.setFillParent(true);
		fragSelect.addListener(new SelectListener());

		initTask();

		loadUI();
		loadFocusWindow();
	}

	FocusWindow focusW;
	void loadFocusWindow() {
		focusW = new FocusWindow("Focus");
	}

	void loadUI() {
		int minH = 300;
		ui = new Window(localizedName(), buttonWidth * 1.5f/* two buttons */,
		 minH, false);
		ui.shown(() -> Time.runTask(3, ui::display));
		ui.hidden(this::hide);
		ui.update(() -> {
			if (state.isMenu()) {
				ui.hide();
			}
		});

		/* 初始化functions */
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
				if (!NumberHelper.isPositiveInt(tmpAmount[0])) return;
				each(list, b -> {
					if (b.items == null) return;
					Call.setItem(b, item, NumberHelper.asInt(tmpAmount[0]));
				});
			});
			ListFunction("@selection.liquids", () -> content.liquids(), Selection::floatField, (list, liquid) -> {
				if (!NumberHelper.isPositiveFloat(tmpAmount[0])) return;
				each(list, b -> {
					if (b.liquids == null) return;
					b.liquids.add(liquid, NumberHelper.asFloat(tmpAmount[0]) - b.liquids.get(liquid));
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


		var tab = new IntTab(-1, allFunctions.orderedKeys().toArray(String.class),
		 Color.sky,
		 ArrayUtils.map2Arr(Table.class, allFunctions, e -> e.value.wrap),
		 1, true);
		tab.setIcons(HopeIcons.tile, HopeIcons.building, Icon.unitsSmall, Icon.gridSmall, Icon.folderSmall);
		Table cont = ui.cont;
		cont.update(() -> {
			tab.labels.each((name, l) -> {
				l.color.set(Settings.valueOf(name).enabled() ? Color.white : Color.lightGray);
			});
		});
		cont.button("Select", HopeStyles.flatTogglet, () -> {
			isSelecting = !isSelecting;
			ElementUtils.addOrRemove(fragSelect, isSelecting);
		}).size(100, 40).checked(_ -> isSelecting);
		cont.row();
		cont.left().add(tab.build())
		 // .colspan(2)
		 .grow().left();

		loadSettings();
	}
	private static Predicate<Building> killCons() {
		return b -> {
			if (b.tile.build == b) b.kill();
			return b.tile.build == null;
		};
	}

	public void hide() {
		fragSelect.remove();
		isSelecting = false;

		tiles.clearList();
		buildings.clearList();
		units.clearList();
		bullets.clearList();

		if (executor != null) executor.shutdownNow();
	}
	public Button buildButton(boolean isSmallized) {
		Button btn = super.buildButton(isSmallized);
		btn.setDisabled(() -> state.isMenu());
		return btn;
	}
	public void build() {
		ui.show();
	}
	public static void getWorldRect(Tile t) {
		mr1.set(t.worldx(), t.worldy(), 32, 32);
	}

	public class EntityFunction<T extends Entityc> extends WFunction<T> {
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
			} catch (UnsupportedOperationException e) {
				return Tmp.v3.set(Mathf.random(10F), Mathf.random(10F));
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
			if (Core.atlas.has(IntVars.modName + "-" + text.toLowerCase())) {
				return icons[i] = Core.atlas.find(IntVars.modName + "-" + text.toLowerCase());
			}

			return icons[i] = drawRegion(48, 48, () -> {
				Draw.color();
				Fonts.def.draw(text, 8, 36, Color.white, 1, false, Align.left);
			});
		}
		public TextureRegion getIcon(T key) {
			if (key instanceof Fire) return lazyGetIcon(0);
			if (key instanceof WorldLabel) return lazyGetIcon(1);
			if (key instanceof Player) return lazyGetIcon(2);
			if (key instanceof Puddle) return lazyGetIcon(3);
			if (key instanceof WeatherState) return lazyGetIcon(4);
			if (key instanceof Drawc) return lazyGetIcon(5);
			if (key instanceof Pool.Poolable) return lazyGetIcon(6);
			if (key instanceof PowerGraphUpdaterc) return lazyGetIcon(7);
			if (key instanceof Syncc) return lazyGetIcon(8);
			return Core.atlas.white();
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
		public CharSequence getTips(T item) {
			return item.block.localizedName + "\n" + item.block.name;
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
				var style = HopeStyles.flatt;
				t.button("@items.summary", style, () -> sumItems(content.items(),
				 i -> build.items == null ? 0 : build.items.get(i),
				 getItemSetter(build)));
				t.button("@liquids.summary", style, () -> sumItems(content.liquids(),
				 l -> build.liquids == null ? 0 : build.liquids.get(l),
				 getLiquidSetter(build)));
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
		public CharSequence getTips(T item) {
			Block b = getBlock(item);
			return b.localizedName + "\n" + b.name;
		}
		public void buildTable(T tile, Table t) {
			// tile.display(table);
			// table.row();
			t.left().defaults().left().padRight(4f);
			t.image(tile.block() == Blocks.air ? null : new TextureRegionDrawable(tile.block().uiIcon)).size(24);
			t.add(tile.block().name).with(EventHelper::addDClickCopy);
			buildPos(t, new Vec2().set(tile.x, tile.y));
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

	Mat mat = new Mat();
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
		} else if (focus instanceof QuadTreeObject && focus instanceof Posc) {
			/** @see InputHandler#selectedUnit() */
			var box = (QuadTreeObject & Posc) focus;
			/** {@link QuadTreeObject#hitbox}以中心对称
			 * @see Rect#setCentered(float, float, float, float)
			 * @see Rect#setCentered(float, float, float) */
			box.hitbox(mr1);
			region = focus instanceof Building ? ((Building) focus).block.fullIcon :
			 focus instanceof Unit ? ((Unit) focus).icon() : Core.atlas.white();
			if (region != Core.atlas.white()) mr1.setSize(region.width, region.height);
			mr1.setPosition(box.x(), box.y());
		} else return;
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
		Draw.mixcol(focusColor, 1);
		Draw.alpha((region == Core.atlas.white() ? 0.7f : 0.9f) * focusColor.a);

		Draw.rect(region, x, y, w, h,
		 !(focus instanceof BlockUnitc) && focus instanceof Unit u ? u.rotation - 90f
			: 90f);

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
				Fill.crect(0, 0, 100, 100);
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

	public boolean focusDisabled;
	boolean focusLocked;
	private       boolean focusEnabled;
	public static Element focusElem;
	/** 用于反应 元素 对应的 焦点 */
	public static Object  focusAny;

	public static void addFocusSource(Element source, Prov<Object> focusProv) {
		if (focusProv == null) throw new IllegalArgumentException("focusProv is null.");

		source.addListener(new HoverAndExitListener() {
			public void enter0(InputEvent event, float x, float y, int pointer, Element fromActor) {
				focusElem = source;
				focusAny = focusProv.get();
			}
			public void exit0(InputEvent event, float x, float y, int pointer, Element toActor) {
				if (toActor != null && source.isAscendantOf(toActor)) return;
				focusElem = null;
				focusAny = null;
			}
		});
	}

	public       Tile               focusTile;
	public       Building           focusBuild;
	public final ObjectSet<Unit>    focusUnits    = new ObjectSet<>();
	public final ObjectSet<Bullet>  focusBullets  = new ObjectSet<>();
	public final ObjectSet<Entityc> focusEntities = new ObjectSet<>();

	/** 用于 ValueLabel 添加 焦点 */
	public final ObjectSet<Object> focusInternal = new ObjectSet<>();

	public void initTask() {
		WorldUtils.uiWD.submit(() -> {
			Gl.flush();
			if (Core.input.alt()) {
				Draw.alpha(0.3f);
			}
			drawFocusInternal();
		});
		Tools.TASKS.add(() -> {
			Element hit = HopeInput.mouseHit();
			focusLocked = control.input.locked();
			focusEnabled = !focusLocked && !scene.hasDialog() && (
			 hit == null || hit.isDescendantOf(focusW) ||
			 (!hit.visible && hit.touchable == Touchable.disabled)
			 || hit.isDescendantOf(el -> el instanceof IMenu)
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
			if (!drawFocusAny(focus)) focusAny = null;
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
		if (focus instanceof Object[] arr) for (Object child : arr) {
			if (drawFocusAny(child)) valid = true;
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
			Groups.unit.each(u -> {
				if (IntVars.mouseWorld.dst(u.x, u.y) < u.hitSize / 2f + 2)
					focusUnits.add(u);
			});
			Groups.bullet.each(b -> {
				if (IntVars.mouseWorld.dst(b.x, b.y) < b.hitSize / 2f + 4)
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

		public Window show() {
			return show(scene, Actions.fadeIn(0.1f));
		}

		HKeyCode fixedKeyCode = keyCodeData().keyCode("fixedWindow", () -> new HKeyCode(KeyCode.anyKey).ctrl().alt());

		{
			margin(4, 4, 4, 4);
			titleTable.remove();
			/* 禁用缩放和移动侦听器 */
			touchable = Touchable.childrenOnly;
			sclListener.remove();
			cont.update(() -> {
				if (updatePosUI && focusEnabled) updatePosUIAndWorld();
				else updatePosOnlyWorld();
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
				    || !fixedKeyCode.isPress()) return;

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
			if (mobile) pane.add(tipKey("fixed", fixedKeyCode.toString())).color(Color.lightGray).fontScale(0.8f).row();
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
			t.button("More", HopeStyles.flatBordert, () -> INFO_DIALOG.showInfo(o)).size(80, 24);
		}
		private void buildCont(Table table, Tile tile) {
			if (lastTile == tile) return;
			table.clearChildren();
			lastTile = tile;
			if (tile == null) return;

			table.table(t -> {
				MenuBuilder.addShowMenuListenerp(t, () -> WFunction.getMenuLists(tile));
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
				MenuBuilder.addShowMenuListenerp(t, () -> WFunction.getMenuLists(build));
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
				MenuBuilder.addShowMenuListenerp(t, () -> WFunction.getMenuLists(unitSet));
				t.left().defaults().padRight(6f).growY().left();
				t.image(Icon.starSmall).size(10).color(u.team.color);
				t.image(new TextureRegionDrawable(u.type.uiIcon)).size(24);
				t.add(u.type.name).with(EventHelper::addDClickCopy);

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
				MenuBuilder.addShowMenuListenerp(t, () -> WFunction.getMenuLists0(bulletSet));
				t.left().defaults().padRight(6f).growY().left();
				t.image(Icon.starSmall).size(10).color(u.team.color).colspan(0);
				t.label(() -> u.time + "[lightgray]/[]" + u.lifetime).size(10).colspan(2).row();
				// t.add("" + u.type).with(JSFunc::addDClickCopy);

				buildPos(t, u);
				// t.add("pathfind:" + u.pathType());
				addMoreButton(t, u);
			}).growX().row());
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


	private static ExecutorService executor;
	private static ExecutorService acquireExecutor() {
		return executor == null || executor.isShutdown() ? executor = Threads.executor() : executor;
	}
	class SelectListener extends WorldSelectListener {
		public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
			if (button != KeyCode.mouseLeft || state.isMenu()) {
				hide();
				mouseChanged = false;
				return false;
			}
			Log.info("down");
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

			/* if (!Core.input.alt()) {
				tiles.clearList();
				buildings.clearList();
				bullets.clearList();
				units.clearList();
			} */

			if (Settings.bullet.enabled()) {
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
			if (Settings.unit.enabled()) {
				Groups.unit.each(unit -> {
					// 返回单位是否在所选区域内
					return start.x <= unit.x && end.x >= unit.x && start.y <= unit.y && end.y >= unit.y;
				}, unit -> {
					if (!units.list.contains(unit)) {
						units.add(unit);
					}
				});
			}
			if (Settings.others.enabled()) {
				Groups.all.each(entity -> {
					if (entity instanceof Building) return false;
					if (entity instanceof Bullet) return false;
					if (entity instanceof Unit) return false;
					if (!(entity instanceof Position pos)) return false;
					// 返回单位是否在所选区域内
					return start.x <= pos.getX() && end.x >= pos.getX() && start.y <= pos.getY() && end.y >= pos.getY();
				}, entity -> {
					if (!others.list.contains(entity)) {
						others.add(entity);
					}
				});
			}

			clampWorld();

			boolean enabledTile  = Settings.tile.enabled();
			boolean enabledBuild = Settings.building.enabled();
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

			if (!ui.isShown()) {
				ui.setPosition(mx, my);
			}
			ui.show();
			isSelecting = false;
		}
	}

	public enum Settings implements ISettings {
		tile, building, unit, bullet, others
		/* other */, focusOnWorld
	}
	class SettingsTable extends Table {
		int lastIndex;

		public SettingsTable(Data data) {
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
				defaultTeam = Team.get(data.getInt("defaultTeam", 1));
				t.left().defaults().left();
				t.add("@selection.default.team").color(Pal.accent).growX().left().row();
				t.table(t1 -> {
					t1.left().defaults().left();
					Team[] arr = Team.baseTeams;
					int    c   = 0;

					for (Team team : arr) {
						ImageButton b = t1.button(whiteui, HopeStyles.clearNoneTogglei/*Styles.clearTogglei*/, 32.0f, () -> {
							data.put("defaultTeam", team.id);
							defaultTeam = team;
						}).size(42).get();
						b.getStyle().imageUp = whiteui.tint(team.color);
						b.update(() -> b.setChecked(defaultTeam == team));
						if (++c % 3 == 0) {
							t1.row();
						}
					}

				}).growX().left().padLeft(16);
			}).row();
			table(t -> {
				t.left().defaults().left();
				SettingsUI.checkboxWithEnum(t, "@settings.focusOnWorld", Settings.focusOnWorld).row();
				t.check("@settings.drawSelect", 28, drawSelect, b -> drawSelect = b)
				 .with(cb -> cb.setStyle(HopeStyles.hope_defaultCheck));
			}).row();
		}
	}
}
