
package modtools.ui.content.world;

import arc.Core;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.*;
import arc.math.geom.QuadTree.QuadTreeObject;
import arc.math.geom.*;
import arc.scene.Element;
import arc.scene.actions.Actions;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.ImageButton;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.util.*;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.input.InputHandler;
import mindustry.type.*;
import mindustry.ui.Styles;
import mindustry.world.*;
import mindustry.world.blocks.environment.*;
import modtools.events.E_Selection;
import modtools.ui.*;
import modtools.ui.HopeIcons;
import modtools.ui.TopGroup.BackElement;
import modtools.ui.components.*;
import modtools.ui.components.Window.NoTopWindow;
import modtools.ui.components.linstener.*;
import modtools.ui.content.*;
import modtools.ui.effect.MyDraw;
import modtools.utils.*;
import modtools.utils.MySettings.Data;
import modtools.utils.array.ArrayUtils;
import modtools.utils.world.*;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import static mindustry.Vars.*;
import static modtools.ui.IntUI.topGroup;
import static modtools.ui.content.world.WFunction.buildPos;
import static modtools.utils.world.WorldDraw.drawRegion;

@SuppressWarnings({"rawtypes", "CodeBlock2Expr", "DanglingJavadoc"})
public class Selection extends Content {
	WFunction<?> selectFunc;
	WFunction<?> focusElemType;
	public Selection() {
		super("selection", Icon.craftingSmall);
		WFunction.init(this);
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
	 otherWD = new WorldDraw(Layer.darkness + 5, "other");

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

	WFunction<Tile>     tiles;
	WFunction<Building> buildings;
	WFunction<Unit>     units;
	WFunction<Bullet>   bullets;
	WFunction<Entityc>  others;

	/** @see E_Selection */
	public static OrderedMap<String, WFunction<?>> allFunctions = new OrderedMap<>();
	private static void intField(Table t1) {
		t1.row().field("", s -> tmpAmount[0] = s).valid(Tools::validPosInt);
	}
	private static void floatField(Table t1) {
		t1.row().field("", s -> tmpAmount[0] = s).valid(Strings::canParsePositiveFloat);
	}

	public void loadSettings(Data SETTINGS) {
		Contents.settings_ui.add(localizedName(), icon, new Table() {
			int lastIndex = 0;

			{
				defaults().growX().left();
				table(t -> {
					t.left().defaults().left().padRight(4f).growX();
					allFunctions.each((k, func) -> {
						if (lastIndex++ % 3 == 0) t.row();
						func.setting(t);
					});
				}).row();
				table(t -> {
					defaultTeam = Team.get(SETTINGS.getInt("defaultTeam", 1));
					t.left().defaults().left();
					t.add("@selection.default.team").color(Pal.accent).growX().left().row();
					t.table(t1 -> {
						t1.left().defaults().left();
						Team[] arr = Team.baseTeams;
						int    c   = 0;

						for (Team team : arr) {
							ImageButton b = t1.button(IntUI.whiteui, HopeStyles.clearNoneTogglei/*Styles.clearTogglei*/, 32.0f, () -> {
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
				}).row();
				table(t -> {
					t.left().defaults().left();
					SettingsUI.checkboxWithEnum(t, "@settings.focusOnWorld", E_Selection.focusOnWorld).row();
					t.check("@settings.drawSelect", 28, drawSelect, b -> drawSelect = b)
					 .with(cb -> cb.setStyle(HopeStyles.hope_defaultCheck));
				}).row();
			}
		});
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
					if (tile.block() != Blocks.air) WorldUtils.setBlock(tile, Blocks.air);
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
				removeAll(list, b -> {
					if (b.tile.build == b) b.kill();
					return b.tile.build != null;
				});
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
					if (Vars.player.unit() == unit) Vars.player.team(team);
					unit.team(team);
				});
			});
			FunctionBuild("@heal", list -> {
				each(list, Healthc::heal);
			});
			FunctionBuild("@kill", list -> {
				removeAll(list, u -> {
					Call.unitDeath(u.id);
					return UnitUtils.kill(u);
				});
			});
			FunctionBuild("@clear", list -> {
				removeAll(list, u -> {
					u.remove();
					return Groups.unit.contains(u0 -> u0 == u);
					// return !addedField.getBoolean(u);
					// return false;
				});
			});
			FunctionBuild("@selection.forceClear", list -> {
				removeAll(list, u -> {
					u.remove();
					if (!Groups.unit.contains(unit -> unit == u)) return true;
					Groups.all.remove(u);
					Groups.unit.remove(u);
					Groups.sync.remove(u);
					Groups.draw.remove(u);
					u.team.data().updateCount(u.type, -1);

					return UnitUtils.forceRemove(u);
				});
			});
		}};
		bullets = new BulletFunction<>("bullet") {{
			FunctionBuild("@clear", list -> {
				removeAll(list, bullet -> {
					bullet.remove();
					try {
						return Groups.bullet.contains(b -> b == bullet);
					} catch (Exception ignored) {}
					return false;
				});
			});
		}};
		others = new EntityFunction<>("others") {{
			FunctionBuild("@clear", list -> {
				removeAll(list, entity -> {
					try {
						entity.remove();
						return !entity.isAdded();
					} catch (Exception ignored) {}
					return false;
				});
			});
		}};


		var tab = new IntTab(-1, allFunctions.orderedKeys().toArray(String.class),
		 Color.sky,
		 ArrayUtils.map2Arr(Table.class, allFunctions, e -> e.value.wrap),
		 1, true);
		tab.icons = new Drawable[]{HopeIcons.tile, HopeIcons.building, Icon.unitsSmall, Icon.gridSmall, Icon.folderSmall};
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

	public class EntityFunction<T extends Entityc> extends WFunction<T> {
		public EntityFunction(String name) {
			super(name, otherWD);
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

		public TextureRegion getIcon(T key) {
			return Core.atlas.white();
		}
		public boolean checkRemove(T item) {
			return !item.isAdded();
		}
	}
	public class BulletFunction<T extends Bullet> extends WFunction<T> {
		public BulletFunction(String name) {
			super(name, bulletWD);
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

		public TextureRegion getIcon(T key) {
			return Core.atlas.white();
		}
		public boolean checkRemove(T item) {
			return !item.isAdded();
		}
	}
	public class UnitFunction<T extends Unit> extends WFunction<T> {
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
	public class BuildFunction<T extends Building> extends WFunction<T> {
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
	public class TileFunction<T extends Tile> extends WFunction<T> {
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

		if (focusElem != null && focusElem.getScene() != null &&
				focusElemType != null && focusElemType.list.contains(focus)) {
			Lines.stroke(4f);
			Vec2 tmp0 = Core.camera.project(x, y);
			x = tmp0.x;
			y = tmp0.y;
			Vec2 vec2 = ElementUtils.getAbsPosCenter(focusElem);
			Lines.line(vec2.x, vec2.y, x, y);
			vec2 = ElementUtils.getAbsolutePos(focusElem);
			Lines.line(vec2.x, vec2.y, x, y);
		}
		Draw.reset();
	}

	boolean focusDisabled;
	private boolean focusEnabled;
	public       Element           focusElem;
	public       Tile              focusTile;
	public       Building          focusBuild;
	public final ObjectSet<Unit>   focusUnits   = new ObjectSet<>();
	public final ObjectSet<Bullet> focusBullets = new ObjectSet<>();

	public final ObjectSet<Object> focusInternal = new ObjectSet<>();

	{
		WorldUtils.uiWD.drawSeq.add(() -> {
			Gl.flush();
			if (Core.input.alt()) {
				Draw.alpha(0.3f);
			}
			drawFocusInternal();


			Element tmp = Core.scene.hit(Core.input.mouseX(), Core.input.mouseY(), true);
			focusEnabled = !topGroup.isSelecting() && (
			 tmp == null || tmp.isDescendantOf(focusW) || (!tmp.visible && tmp.touchable == Touchable.disabled)
			 // || tmp.isDescendantOf(el -> clName(el).contains("modtools.ui.IntUI"))
			 || tmp instanceof Hitter);
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
		if (E_Selection.focusOnWorld.enabled()) {
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

		public Window show() {
			return show(Core.scene, Actions.fadeIn(0.1f));
		}

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
			cont.pane(Styles.smallPane, p -> pane = p).grow();
			buildCont0();
			Tools.TASKS.add(() -> {
				if (state.isMenu() || !E_Selection.focusOnWorld.enabled() || !focusEnabled) {
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
				IntUI.addShowMenuListenerp(t, () -> WFunction.getMenuLists(tile));
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
				IntUI.addShowMenuListenerp(t, () -> WFunction.getMenuLists(build));
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
				IntUI.addShowMenuListenerp(t, () -> WFunction.getMenuLists(unitSet));
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
				IntUI.addShowMenuListenerp(t, () -> WFunction.getMenuLists0(bulletSet));
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
				WorldUtils.uiWD.drawSeq.add(() -> {
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
			if (E_Selection.others.enabled()) {
				Groups.all.each(unit -> {
					if (unit instanceof Bullet) return false;
					if (unit instanceof Unit) return false;
					// 返回单位是否在所选区域内
					return !(unit instanceof Position pos) || start.x <= pos.getX() && end.x >= pos.getX() && start.y <= pos.getY() && end.y >= pos.getY();
				}, unit -> {
					if (!others.list.contains(unit)) {
						others.add(unit);
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

