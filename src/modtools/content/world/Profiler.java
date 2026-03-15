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

		private String getSortName(int type) {
			return switch (type) {
				case 1 -> "Total";
				case 2 -> "Calls";
				default -> "Avg";
			};
		}
		private void build() {
			// 搜索与工具栏
			cont.table(t -> {
				search = new Search<>((_, p) -> pattern = p);
				t.button(Icon.trashSmall, ProfilerData::clear).size(40);
				t.button(STR."Sort: \{getSortName(sortType)}", Styles.flatBordert, () -> {
					sortType = (sortType + 1) % 3;
					fullRebuild(); // 重新触发 FilterTable 的构建
				}).size(130, 40).with(b -> b.update(() -> b.setText(STR."Sort: \{getSortName(sortType)}")));
				search.build(t, null);
			}).growX().row();

			statsTable = new FilterTable<>();

			// 这里的逻辑：我们定时检查是否有新的方法产生（动态注入了新探针）
			// 如果没有新 key，我们只更新现有 label 的数值，不重构 Table
			cont.pane(statsTable).grow().update(p -> {
				if (ProfilerData.totalTime.size() != statsTable.getMapSize()) {
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

			ProfilerData.totalTime.forEach((name, totalAdder) -> {
				long total      = totalAdder.sum();
				var  countAdder = ProfilerData.callCount.get(name);
				long calls      = (countAdder != null) ? countAdder.sum() : 0;

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
			statsTable.addPatternUpdateListener(() -> pattern);

			// 表头：不调用 bind，不参与过滤，始终可见
			statsTable.table(t -> {
				t.left().defaults().left();
				t.add("Method").growX();
				t.add("Avg (ms)").width(120).color(Pal.accent);
				t.add("Calls").width(120).color(Color.gray);
			}).growX().pad(2).row();

			// 按照 Avg 排序获取当前所有数据
			Seq<ProfileStats> stats = getSortedStats();

			for (ProfileStats s : stats) {
				statsTable.bind(s.name);

				statsTable.table(Styles.black3, t -> {
					t.update(() -> s.refresh()); // 每帧刷新 ProfileStats 字段
					t.left().defaults().left();
					// 1. 名字：点击尝试找到对应类并打开 ShowInfoWindow
					t.add(displayName(s.name)).growX().maxWidth(200).ellipsis(true)
					 .tooltip(s.name); // tooltip 保留完整 key
					// 2. SingleProv 读 s 的字段，不再直接访问 ProfilerData
					SingleProv prov = new SingleProv(() -> Tmp.v1.set((float) s.avgMs, 0), s1 -> s1 + " ms");
					prov.digits = 7;
					t.label(prov).width(120).color(Pal.accent);
					t.label(new SingleProv(() -> Tmp.v1.set(s.calls, 0), s1 -> s1 + " calls"))
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
			methodTable.addPatternUpdateListener(() -> pattern);
			List<String> methods = ProbeScanner.findCandidateMethods(selectedBase);

			for (String mName : methods) {
				methodTable.bind(mName);

				methodTable.table(Styles.black3, t -> {
					t.left().defaults().left();

					t.label(() -> mName)
					 .growX()
					 .update(l -> l.setColor(ProfilerData.dynamicTargets.contains("*." + mName) ? Pal.accent : Color.white));

					// 跳到 ShowInfoWindow 查看该方法的完整反射信息
					t.button(Icon.eyeSmall, Styles.cleari, () -> {
						ShowInfoWindow win = new ShowInfoWindow(null, selectedBase);
						win.show();
						win.search(mName);
					}).size(32);

					t.button(Icon.add, Styles.cleari, () -> {
						boolean isEnabled = ProfilerData.dynamicTargets.contains("*." + mName);
						DynamicProfilerAPI.toggleEntityProbe(selectedBase, mName, !isEnabled);
					}).size(32).update(b -> {
						boolean isEnabled = ProfilerData.dynamicTargets.contains("*." + mName);
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
			var totalAdder = ProfilerData.totalTime.get(name);
			var countAdder = ProfilerData.callCount.get(name);
			totalNanos = totalAdder != null ? totalAdder.sum() : 0;
			calls = countAdder != null ? countAdder.sum() : 0;
			avgMs = calls == 0 ? 0 : (totalNanos / 1_000_000.0) / calls;
		}
	}
}