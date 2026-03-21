package modtools.content.world;

import arc.graphics.Color;
import arc.struct.Seq;
import arc.util.Tmp;
import mindustry.entities.Effect;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.content.Content;
import modtools.misc.PairProv.SingleProv;
import modtools.ui.comp.Window;
import modtools.ui.gen.HopeIcons;
import modtools.utils.search.*;
import modtools.utils.ui.ShowInfoWindow;
import nipx.profiler.*;
import nipx.profiler.ProfilerData.MethodStats;

import java.util.List;
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

			// 这里的逻辑：我们定时检查是否有新的方法产生（动态注入了新探针）
			// 如果没有新 key，我们只更新现有 label 的数值，不重构 Table
			cont.pane(statsTable).grow().update(p -> {
				if (ProfilerData.stats.size() != statsTable.getMapSize()) {
					fullRebuild();
				}
			});

			fullRebuild();
		}
		/**
		 * 从 ProfilerData 获取快照并排序
		 */
		private Seq<ProfileStats> getSortedStats() {
			Seq<ProfileStats> list = new Seq<>();

			ProfilerData.stats.forEach((name, stat) -> {
				long total = stat.time().sum();
				long calls = stat.count().sum();

				list.add(new ProfileStats(name, total, calls));
			});

			list.sort((a, b) -> switch (sortType) {
				case 1 -> Long.compare(b.totalNanos, a.totalNanos);
				case 2 -> Long.compare(b.calls, a.calls);
				default -> Double.compare(b.avgMs, a.avgMs);
			});

			return list;
		}
		// 只有当监测到的方法数量发生变化时才调用
		private void fullRebuild() {
			statsTable.clear();
			statsTable.top();
			statsTable.setPatternUpdateListener(() -> pattern);

			// 表头：不调用 bind，不参与过滤，始终可见
			statsTable.table(t -> {
				t.left().defaults().left().height(45f);
				t.add("Method").growX();
				t.button("Avg (ms)", Styles.flatBordert, () -> {
					sortType = 0;
					fullRebuild();
				}).width(120).color(Pal.accent);
				t.button("Calls", Styles.flatBordert, () -> {
					sortType = 2;
					fullRebuild();
				}).width(120).color(Color.gray);
			}).growX().pad(2).row();

			// 按照 Avg 排序获取当前所有数据
			Seq<ProfileStats> stats = getSortedStats();

			for (ProfileStats s : stats) {
				statsTable.bind(s.name);

				statsTable.table(Styles.black3, t -> {
					t.update(s::refresh); // 每帧刷新 ProfileStats 字段
					t.left().defaults().left();
					t.add(displayName(s.name)).growX().maxWidth(200).ellipsis(true)
					 .tooltip(s.name); // tooltip 保留完整 key
					SingleProv prov = new SingleProv(() -> Tmp.v1.set((float) s.avgMs, 0));
					prov.digits = 7;
					t.label(prov).width(120).color(Pal.accent);
					t.label(new SingleProv(() -> Tmp.v1.set(s.calls, 0)))
					 .width(120).color(Color.gray);
				}).growX().pad(2).row();
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
	public static class ProfileStats {
		public String name;
		public long   totalNanos;
		public long   calls;
		public double avgMs;

		public ProfileStats(String name, long totalNanos, long calls) {
			this.name = name;
			this.totalNanos = totalNanos;
			this.calls = calls;
			this.avgMs = (calls == 0) ? 0 : ((totalNanos / 1_000_000.0) / calls);
		}

		/** 从 ProfilerData 拉取最新数据，由 UI 的 update() 每帧调用 */
		public void refresh() {
			MethodStats stat = ProfilerData.stats.get(name);
			if (stat == null) return;
			totalNanos = stat.time().sum();
			calls = stat.count().sum();
			avgMs = calls == 0 ? 0 : (totalNanos / 1_000_000.0) / calls;
		}
	}
}