package modtools.content.world;

import arc.graphics.Color;
import arc.struct.Seq;
import arc.util.Tmp;
import arc.util.pooling.*;
import mindustry.entities.Effect;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.content.Content;
import modtools.misc.PairProv.SingleProv;
import modtools.ui.HopeStyles;
import modtools.ui.comp.Window;
import modtools.ui.gen.HopeIcons;
import modtools.utils.search.*;
import modtools.utils.ui.ShowInfoWindow;
import nipx.profiler.*;
import nipx.profiler.ProfilerData.MethodStats;

import java.util.*;
import java.util.regex.Pattern;

public class Profiler extends Content {

	public Profiler() {
		super("profiler", HopeIcons.profile);
	}

	Window ui;
	public void lazyLoad() {
		ui = new Window("Profiler", 300, 400, true);

		ui.cont.button("Show Probe Selector Window", () -> {
			new ProbeSelectorWindow().show();
		}).size(220, 64).row();

		ui.cont.button("Show Profiler Window", () -> {
			new ProfilerWindow().show();
		}).size(220, 64);
	}
	public void build() {
		ui.show();
	}

	public static class ProfilerWindow extends Window {
		private FilterTable<String> statsTable;
		private Pattern             pattern;
		private Search<String>      search;
		private int                 sortType = 0;

		public int colWidth       = 125;
		public int methodColWidth = 200;

		public ProfilerWindow() {
			super("Profiler Dashboard", 500, 600, true);
			build();
		}
		private void build() {
			// 搜索与工具栏
			cont.table(t -> {
				search = new Search<>((_, p) -> pattern = p);
				t.button(Icon.trashSmall, ProfilerData::clear).size(40);
				t.button(Icon.refreshSmall, this::fullRebuild).size(40);
				search.build(t, null);
			}).growX().row();

			statsTable = new FilterTable<>();

			// 表头：不参与过滤，始终可见
			cont.table(t -> {
				t.left().defaults().left().height(45f);
				t.add("Method").minWidth(methodColWidth).growX();
				t.defaults().width(colWidth).padLeft(4f);
				t.button("Avg(ms)", Styles.flatBordert, () -> {
					sortType = 0;
					sortUI();
				}).color(Pal.accent);
				t.button("Total(ms)", Styles.flatBordert, () -> {
					sortType = 1;
					sortUI();
				}).color(Color.gray);
				t.button("Calls", Styles.flatBordert, () -> {
					sortType = 2;
					sortUI();
				}).color(Color.gray);
			}).growX().pad(2).row();

			// 这里的逻辑：我们定时检查是否有新的方法产生（动态注入了新探针）
			// 如果没有新 key，我们只更新现有 label 的数值，不重构 Table
			cont.pane(HopeStyles.h_smallPane, statsTable).grow().update(p -> {
				if (ProfilerData.stats.size() != statsTable.getMapSize()) {
					fullRebuild();
				}
			});

			fullRebuild();
		}

		private static final Comparator<ProfileStats> SORT_AVG   = (a, b) -> Double.compare(b.avgMs, a.avgMs);
		private static final Comparator<ProfileStats> SORT_TOTAL = (a, b) -> Long.compare(b.totalNanos, a.totalNanos);
		private static final Comparator<ProfileStats> SORT_CALLS = (a, b) -> Long.compare(b.calls, a.calls);

		final Seq<ProfileStats> currentStats = new Seq<>();
		/** 从 ProfilerData 获取快照并排序 */
		private Seq<ProfileStats> getSortedStats() {
			Pools.freeAll(currentStats, true);
			currentStats.clear();

			// 游标归零
			ProfilerData.stats.forEach((name, stat) -> {
				long total = stat.time().sum();
				long calls = stat.count().sum();
				currentStats.add(ProfileStats.obtain(name, total, calls));
			});

			currentStats.sort(switch (sortType) {
				case 0 -> SORT_AVG;
				case 1 -> SORT_TOTAL;
				case 2 -> SORT_CALLS;
				default -> throw new IllegalArgumentException("Invalid sort type: " + sortType);
			});

			return currentStats;
		}

		/** 0 GC的排序  */
		private void sortUI() {
			// Seq<ProfileStats> stats = getSortedStats();
			// statsTable.getCells().sort(cell ->
			//  cell.get() instanceof Table table ? stats.indexOf(t -> t.name.equals(table.name)) : 0);
			Seq<ProfileStats> stats = getSortedStats();

			// 获取 FilterTable 底层的单元格序列(布局)与子元素序列(渲染/事件)
			var cells    = statsTable.getCells();
			var children = statsTable.getChildren();

			// 0 GC 清空（仅重置数组 size=0，不会解除父子绑定，不会产生垃圾）
			cells.clear();
			children.clear();

			// 根据排好序的 stats 重新构建 Table 的物理顺序
			for (int i = 0; i < stats.size; i++) {
				ProfileStats s = stats.get(i);

				// 利用 FilterTable 已有的映射，找回对应行的 BindCell
				FilterTable.CellGroup group = statsTable.findBind(s.name);

				if (group != null && !group.removed) {
					// 遍历改组绑定的 Cell（通常每行只有1个）
					for (int j = 0; j < group.size; j++) {
						BindCell bc = group.get(j);

						if (bc.cell != null) {
							// ① 布局层：无论元素是否被过滤隐藏，都将其放入 cells（被隐藏的是 UNSET_CELL，占位用）
							cells.add(bc.cell);

							// ② 渲染层：利用 bc.cell.get() 判断当前组件是否存活。
							// 仅当组件未被你的 Filter 搜索逻辑隐藏时，才把它放回 children 中
							if (bc.cell.get() != null) {
								children.add(bc.cell.get());
							}
						}
					}
				}
			}

			statsTable.invalidate();
		}
		// 只有当监测到的方法数量发生变化时才调用
		private void fullRebuild() {
			statsTable.clear();
			statsTable.top();
			statsTable.setPatternUpdateListener(() -> pattern);

			// 按照 Avg 排序获取当前所有数据
			Seq<ProfileStats> stats = getSortedStats();

			for (ProfileStats s : stats) {
				final String mName = s.name;
				statsTable.bind(mName);

				statsTable.table(Styles.black3, t -> {
					t.left().defaults().left().width(colWidth).padLeft(4f);
					t.add(displayName(mName)).growX().width(methodColWidth).ellipsis(true)
					 .tooltip(mName); // tooltip 保留完整 key

					// 显示平均耗时
					SingleProv avgProv = new SingleProv(() -> {
						MethodStats stat = ProfilerData.stats.get(mName);
						if (stat != null) {
							long   calls = stat.count().sum();
							double avg   = calls == 0 ? 0 : ((stat.time().sum() / 1_000_000.0) / calls);
							Tmp.v1.set((float) avg, 0);
						}
						return Tmp.v1;
					});
					avgProv.digits = 6;
					t.label(avgProv).color(Pal.accent);
					// 显示总耗时
					t.label(new SingleProv(() -> {
						MethodStats stat = ProfilerData.stats.get(mName);
						return Tmp.v1.set(stat != null ? stat.time().sum() / 1_000_000f : 0f, 0);
					}));
					// 显示调用次数
					t.label(new SingleProv(() -> {
						MethodStats stat = ProfilerData.stats.get(mName);
						return Tmp.v1.set(stat != null ? stat.count().sum() : 0, 0);
					})).color(Color.gray);
				})
				 .name(mName).growX().pad(2).row();
			}
			statsTable.unbind();
		}

	}
	/** 仅用于 UI 显示：去掉内部类宿主前缀，"A$B.method" → "B.method" */
	private static String displayName(String name) {
		int dollar = name.indexOf('$');
		return dollar >= 0 ? name.substring(dollar + 1) : name;
	}

	public static class ProbeSelectorWindow extends Window {
		private Class<?>            selectedBase = Building.class;
		private FilterTable<String> methodTable;
		private Search<String>      search;
		private Pattern             pattern;

		public ProbeSelectorWindow() {
			super("Probe Manager", 400, 600, true);
			mysetup();
		}

		private void mysetup() {
			cont.table(t -> {
				t.left().defaults().pad(4).size(78, 40);
				t.button("Building", Styles.flatTogglet, () -> {
					selectedBase = Building.class;
					rebuild();
				}).checked(i -> selectedBase == Building.class);
				t.button("Unit", Styles.flatTogglet, () -> {
					selectedBase = Unit.class;
					rebuild();
				}).checked(i -> selectedBase == Unit.class);
				t.button("Bullet", Styles.flatTogglet, () -> {
					selectedBase = Bullet.class;
					rebuild();
				}).checked(i -> selectedBase == Bullet.class);
				t.button("Effect", Styles.flatTogglet, () -> {
					selectedBase = Effect.class;
					rebuild();
				}).checked(i -> selectedBase == Effect.class);
				// 打开当前基类的完整反射视图
				t.button(Icon.eyeSmall, Styles.cleari, () ->
				 new ShowInfoWindow(null, selectedBase).show()
				).size(40);
			}).row();

			search = new Search<>((_, p) -> pattern = p);
			search.build(cont, null);
			cont.row();

			methodTable = new FilterTable<>();
			methodTable.top().left();

			cont.pane(methodTable).grow();

			rebuild();
		}

		private void rebuild() {
			methodTable.clear();
			methodTable.setPatternUpdateListener(() -> pattern);
			List<String> methods = ProbeScanner.findCandidateMethods(selectedBase);

			for (String mName : methods) {
				methodTable.bind(mName);

				methodTable.table(Styles.black3, t -> {
					t.left().defaults().left();

					t.label(() -> mName)
					 .growX()
					 .update(l -> l.setColor(ProfilerTransformer.hasTargetMethod(selectedBase, mName) ? Pal.accent : Color.white));

					// 跳到 ShowInfoWindow 查看该方法的完整反射信息
					t.button(Icon.eyeSmall, Styles.cleari, () -> {
						ShowInfoWindow win = new ShowInfoWindow(null, selectedBase);
						win.show();
						win.search(mName);
					}).size(32);

					t.button(Icon.add, Styles.cleari, () -> {
						boolean isEnabled = ProfilerTransformer.hasTargetMethod(selectedBase, mName);
						DynamicProfilerAPI.toggleEntityProbe(selectedBase, mName, !isEnabled);
					}).size(32).update(b -> {
						boolean isEnabled = ProfilerTransformer.hasTargetMethod(selectedBase, mName);
						b.getStyle().imageUp = isEnabled ? Icon.cancel : Icon.add;
					});
				}).growX().pad(2).row();
			}
			methodTable.unbind();
		}
	}
	public static class ProfileStats implements Pool.Poolable {
		public        String             name;
		public        long               totalNanos;
		public        long               calls;
		public        double             avgMs;
		public static Pool<ProfileStats> statsPool = Pools.get(ProfileStats.class, ProfileStats::new);
		private ProfileStats() { }

		public static ProfileStats obtain(String name, long totalNanos, long calls) {
			ProfileStats stats = statsPool.obtain();
			stats.name = name;
			stats.totalNanos = totalNanos;
			stats.calls = calls;
			stats.avgMs = avgMs(totalNanos,  calls);
			return stats;
		}
		public static double avgMs(long totalNanos, long calls) {
			return (calls == 0) ? 0 : ((totalNanos / 1_000_000.0) / calls);
		}

		public void reset() {
			name = null;
			totalNanos = 0;
			calls = 0;
			avgMs = 0;
		}
	}
}