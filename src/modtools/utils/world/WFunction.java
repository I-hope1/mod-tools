package modtools.utils.world;

import arc.Core;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.event.Touchable;
import arc.scene.style.*;
import arc.scene.ui.Button;
import arc.scene.ui.layout.Table;
import arc.struct.*;
import arc.struct.ObjectMap.Entry;
import arc.util.*;
import mindustry.Vars;
import mindustry.ctype.UnlockableContent;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.*;
import mindustry.world.Tile;
import modtools.IntVars;
import modtools.events.MyEvents;
import modtools.jsfunc.INFO_DIALOG;
import modtools.struct.v6.AThreads;
import modtools.ui.*;
import modtools.ui.components.Window.DisWindow;
import modtools.ui.components.input.JSRequest;
import modtools.ui.components.limit.*;
import modtools.ui.components.utils.TemplateTable;
import modtools.ui.content.ui.PairProv;
import modtools.ui.content.world.Selection;
import modtools.ui.content.world.Selection.Settings;
import modtools.ui.effect.*;
import modtools.ui.menu.MenuItem;
import modtools.utils.*;
import modtools.utils.ui.LerpFun;

import java.util.Vector;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static mindustry.Vars.tilesize;
import static modtools.ui.Contents.tester;
import static modtools.ui.IntUI.*;
import static modtools.utils.ui.TmpVars.*;
import static modtools.utils.world.WorldDraw.CAMERA_RECT;

@SuppressWarnings("CodeBlock2Expr")
public abstract class WFunction<T> {
	private static Selection SC;
	public static void init(Selection selection) {
		SC = selection;
	}
	public final Table   wrap    = new Table();
	public final Table   main    = new Table();
	public final Table   buttons = new Table();
	public       List<T> list    = new MyVector();


	// for select
	public        Seq<OrderedSet<T>> select      = new Seq<>();
	private final Runnable           changeEvent = () -> MyEvents.fire(this);
	public final  String             name;
	Settings data;
	public WorldDraw WD;

	public TemplateTable<OrderedSet<T>> template;

	public ObjectMap<Float, TextureRegion> iconMap = new ObjectMap<>();

	private final ExecutorService                         executor;
	private final ObjectMap<TextureRegion, OrderedSet<T>> selectMap = new ObjectMap<>();
	private       boolean                                 drawAll   = true;

	public WFunction(String name, WorldDraw WD) {
		super();
		this.name = name;
		data = Settings.valueOf(name);
		this.WD = WD;
		Tools.TASKS.add(() -> WD.alpha = SC.selectFunc == this ? 0.7f : 0.1f);
		executor = AThreads.impl.boundedExecutor(name + "-each", 1);

		main.button("Show All", HopeStyles.blackt, this::showAll).growX().height(Selection.buttonHeight).row();
		main.add(buttons).growX().row();
		buildButtons();

		MyEvents.on(this, () -> {
			template.clear();
			select.clear();
			selectMap.clear();
			Tools.each(list, t -> {
				selectMap.get(getIcon(t), OrderedSet::new).add(t);
			});
			int i = 0;
			for (Entry<TextureRegion, OrderedSet<T>> entry : selectMap) {
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

				var btn = new NewBtn();
				btn.update(() -> {
					btn.setChecked(btn.uiShowing || select.contains(value));
				});
				IntUI.doubleClick(btn, () -> {
					if (select.contains(value, true)) select.remove(value);
					else select.add(value);
				}, () -> {
					btn.toggleShowing();
					IntUI.showSelectTable(btn, (p, hide, str) -> {
						int c = 0;
						for (T item : value) {
							p.add(new SelectHover(item, t -> {
								t.image(getIcon(item)).size(42);
							}));
							if (++c % 6 == 0) p.row();
						}
					}, false, Align.center).hidden(btn::toggleShowing);
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
			if (data.enabled()) {
				setup();
			} else {
				remove();
			}
		});

		Selection.allFunctions.put(name, this);
		main.update(() -> SC.selectFunc = this);

		FunctionBuild("Copy", list -> {
			tester.put(IntVars.mouseVec, list.toArray());
		});
	}
	private void buildButtons() {
		buttons.defaults().height(Selection.buttonHeight).growX();

		buttons.button("Refresh", Icon.refreshSmall, HopeStyles.flatt, () -> {
			MyEvents.fire(this);
		});
		buttons.button("SelectAll", Icon.menuSmall, HopeStyles.flatTogglet, () -> { })
		 .with(b -> b.clicked(() -> {
			 boolean all = select.size != selectMap.size;
			 select.clear();
			 if (all) for (var entry : selectMap) select.add(entry.value);
		 }))
		 .update(b -> b.setChecked(select.size == selectMap.size));
		buttons.row();

		buttons.button("Run", Icon.okSmall, HopeStyles.flatt, () -> { })
		 .with(b -> b.clicked(() -> {
			showMenuList(getMenuLists(this, mergeList()));
		}))
		 .disabled(_ -> select.isEmpty());
		buttons.button("Filter", Icon.filtersSmall, HopeStyles.flatt, () -> {
			JSRequest.requestForSelection(mergeList(), null, boolf -> {
				int size = select.sum(seq -> seq.size);
				select.each(seq -> {
					var b    = ((Boolf<T>) boolf);
					var iter = seq.iterator();
					while (iter.hasNext()) {
						if (!b.get(iter.next())) iter.remove();
					}
				});
				showInfoFade("Filtered [accent]" + (size - select.sum(seq -> seq.size)) + "[] elements")
				 .sticky = true;
			});
		})
		 .disabled(_ -> select.size == 0);
		buttons.row();

		buttons.button("DrawAll", Icon.menuSmall, HopeStyles.flatTogglet, () -> {
			drawAll = !drawAll;
		})
		 .update(t -> t.setChecked(drawAll));
		buttons.button("NoSelect", Icon.trash, HopeStyles.flatt, () -> {
			clearList();
			changeEvent.run();
		})
		 .update(t -> t.setChecked(drawAll));
		buttons.row();
	}

	private List<T> mergeList() {
		Seq<T> seq = new Seq<>();
		select.each(seq::addAll);
		return seq.list();
	}

	public abstract TextureRegion getIcon(T key);
	public abstract TextureRegion getRegion(T t);

	public void setting(Table t) {
		t.check(name, 28, data.enabled(), checked -> {
			if (checked) setup();
			else remove();

			SC.hide();
			data.set(checked);
		}).with(cb -> {
			cb.left();
			cb.setStyle(HopeStyles.hope_defaultCheck);
		});
	}

	public void remove() {
		wrap.clearChildren();
		HopeFx.changedFx(wrap);
	}

	public void each(Consumer<? super T> action) {
		each(list, action);
	}

	public void each(List<T> list, Consumer<? super T> action) {
		if (((ThreadPoolExecutor) executor).getActiveCount() >= 2) {
			IntUI.showException(new RejectedExecutionException("There's already 2 tasks running."));
			return;
		}
		executor.submit(() -> Tools.each(list, Tools.consT(t -> {
			new LerpFun(Interp.fastSlow).onWorld().rev()
			 .registerDispose(1 / 24f, fin -> {
				 Draw.color(Pal.accent);
				 Vec2 pos = getPos(t);
				 Lines.stroke(3f - fin * 2f);
				 TextureRegion region = getRegion(t);
				 Lines.square(pos.x, pos.y,
					fin * Mathf.dst(region.width, region.height) / tilesize);
			 });
			Threads.sleep(0, 200000); // 0.2 ms
			Core.app.post(() -> action.accept(t));
		})));
	}
	public void removeAll(List<T> list, Predicate<? super T> action) {
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
			TaskManager.acquireTask(15f, changeEvent);
		});
		list.add(item);
		if (SC.drawSelect) {
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
			if (drawAll || (
			 select.contains(selectMap.get(getIcon(item)))
			 && selectMap.get(getIcon(item), OrderedSet::new).contains(item))) {
				Draw.rect(region, pos.x, pos.y, rotation(item));
			}
			return true;
		});
	}
	/** 返回{@code true}如果需要删除 */
	public abstract boolean checkRemove(T item);
	public Vec2 getPos(T item) {
		if (item instanceof Position pos) return Tmp.v3.set(pos);
		throw new UnsupportedOperationException("You don't overwrite it.");
	}
	public float rotation(T item) {
		return 0;
	}


	protected final ObjectMap<String, Cons<List<T>>> FUNCTIONS = new OrderedMap<>();

	public <R extends UnlockableContent> void ListFunction(
	 String name, Prov<Seq<R>> list,
	 Cons<SelectTable> builder, Cons2<List<T>, R> cons) {
		FunctionBuild(name, from -> {
			var table = IntUI.showSelectImageTable(
			 IntVars.mouseVec.cpy(), list.get(), () -> null,
			 n -> cons.get(from, n), 42f, 32, 6,
			 true);
			if (builder != null) builder.get(table);
		});
	}
	/** 这个exec的list是用来枚举的 */
	public void FunctionBuild(String name, Cons<List<T>> exec) {
		FUNCTIONS.put(name, exec);
	}
	public void TeamFunctionBuild(String name, Cons2<List<T>, Team> cons) {
		FunctionBuild(name, from -> {
			Team[]        arr   = Team.baseTeams;
			Seq<Drawable> icons = new Seq<>();

			for (Team team : arr) {
				icons.add(IntUI.whiteui.tint(team.color));
			}

			IntUI.showSelectImageTableWithIcons(IntVars.mouseVec.cpy(), new Seq<>(arr), icons, () -> null,
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
	class MyVector extends Vector<T> {
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

	public abstract void setFocus(T t);
	public abstract boolean checkFocus(T item);
	public abstract void clearFocus(T item);
	public class SelectHover extends LimitButton {
		private final T item;

		public SelectHover(T item) {
			super(HopeStyles.flati);
			this.item = item;
			init();
		}
		void init() {
			margin(2, 4, 2, 4);

			touchable = Touchable.enabled;

			IntUI.hoverAndExit(this, () -> {
				Selection.focusElem = this;
				SC.focusElemType = WFunction.this;
				SC.focusDisabled = true;
				setFocus(item);
			}, () -> {
				Selection.focusElem = null;
				SC.focusElemType = null;
				SC.focusDisabled = false;
				clearFocus(item);
			});

			clicked(() -> WorldInfo.showInfo(this, item));
		}

		public void updateVisibility() {
			super.updateVisibility();
			// 检查链接是否有效
			if (!checkFocus(item)) {
				if (Selection.focusElem == this) {
					Selection.focusElem = null;
					return;
				}
				return;
			}
			if (SC.focusDisabled) return;

			Selection.focusElem = this;
			SC.focusElemType = WFunction.this;
		}

		public SelectHover(T item, Cons<Table> cons) {
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
			if (Selection.focusElem == this) {
				Draw.color(Pal.accent, Draw.getColor().a);
				Lines.stroke(4f);
				float w = width - 2;
				float h = height - 2;
				MyDraw.dashRect(x + width / 2f, y + height / 2f, w, h,
				 Interp.smooth.apply(0, (w + h) / 2, Time.globalTime / ((w + h) / 2) % 1));
				// Fill.crect(x, y, width, height);
			}
		}

	}
	class ShowAllWindow extends DisWindow {
		int c, cols = Vars.mobile ? 4 : 6;
		public ShowAllWindow() {
			super(WFunction.this.name, 0, 200, true);
			cont.pane(new LimitTable(table -> {
				for (T item : list) {
					var cont = new SelectHover(item);
					table.add(cont).minWidth(150);
					buildTable(item, cont);
					cont.row();
					cont.button("@details", HopeStyles.blackt, () -> {
						 INFO_DIALOG.showInfo(item);
					 }).growX().height(Selection.buttonHeight)
					 .colspan(10);
					if (++c % cols == 0) {
						table.row();
					}
				}
			})).grow();
		}
	}
	class SeqBind extends OrderedSet<T> {
		final Iterable<T> from;
		public SeqBind(Iterable<T> from) {
			this.from = from;
			for (var e : from) add(e);
		}
		public boolean equals(Object object) {
			if (object == null) return false;
			if (object.getClass() != SeqBind.class) return false;
			return this == object || ((SeqBind) object).from == from;
		}
	}


	static <R> Seq<MenuItem> getMenuLists(WFunction<R> function, List<R> list) {
		Seq<MenuItem> seq = new Seq<>(function.FUNCTIONS.size);
		function.FUNCTIONS.each((k, r) -> {
			seq.add(MenuItem.with(k.replace("@", ""), null, k, () -> r.get(list)));
		});
		return seq;
	}

	@SuppressWarnings("unchecked")
	public static Seq<MenuItem> getMenuLists0(ObjectSet<Bullet> bulletSet) {
		tmpList.clear();
		bulletSet.each(tmpList::add);
		return getMenuLists(SC.bullets, tmpList);
	}
	@SuppressWarnings("unchecked")
	public static Seq<MenuItem> getMenuLists(ObjectSet<Unit> unitSet) {
		tmpList.clear();
		unitSet.each(tmpList::add);
		return getMenuLists(SC.units, tmpList);
	}
	@SuppressWarnings("unchecked")
	public static Seq<MenuItem> getMenuLists(Building build) {
		tmpList.clear();
		tmpList.add(build);
		return getMenuLists(SC.buildings, tmpList);
	}
	@SuppressWarnings("unchecked")
	public static Seq<MenuItem> getMenuLists(Tile tile) {
		tmpList.clear();
		tmpList.add(tile);
		return getMenuLists(SC.tiles, tmpList);
	}


	public static void buildPos(Table table, Position u) {
		table.label(new PairProv(() -> Tmp.v1.set(u),
			u instanceof Building || u instanceof Vec2 ? ", " : "\n"))
		 .fontScale(0.7f).color(Color.lightGray)
		 .get().act(0.1f);
	}
	protected <U extends UnlockableContent, E> void sumItems(Seq<U> items, Func<U, E> func, Cons2<U, String> setter) {
		var watcher = JSFunc.watch();
		watcher.addAllCheckbox();
		items.each(i -> {
			if (i.id % 6 == 0) watcher.newLine();
			watcher.watchWithSetter(icon(i),
			 () -> func.get(i),
			 setter == null ? null : str -> setter.get(i, str));
		});
		watcher.show();
	}
}
