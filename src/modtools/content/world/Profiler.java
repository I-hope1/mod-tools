package modtools.content.world;

import arc.func.Cons;
import arc.graphics.Color;
import arc.struct.Seq;
import arc.util.Tmp;
import arc.util.pooling.*;
import mindustry.entities.Effect;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.annotations.settings.FlushField;
import modtools.content.Content;
import modtools.events.ISettings;
import modtools.misc.PairProv.SingleProv;
import modtools.ui.*;
import modtools.ui.comp.Window;
import modtools.ui.gen.HopeIcons;
import modtools.unsupported.HotSwapManager;
import modtools.utils.MySettings.Data;
import modtools.utils.Tools;
import modtools.utils.profiler.SamplingProfiler;
import modtools.utils.search.*;
import modtools.utils.ui.ShowInfoWindow;
import nipx.profiler.*;
import nipx.profiler.ProfilerData.MethodStats;

import java.util.*;
import java.util.regex.Pattern;

public class Profiler extends Content {

	public Profiler() {
		super("profiler", HopeIcons.profile);
		defLoadable = false;
		if (!HotSwapManager.valid()) Tools.TASKS.add(this::disable);
	}


	Window ui;
	public void lazyLoad() {
		ui = new Window("Profiler", 300, 430, true);

		ui.cont.button("Show Probe Selector Window", () -> {
			new ProbeSelectorWindow().show();
		}).size(220, 64).row();

		ui.cont.button("Show Profiler Window", () -> {
			new ProfilerWindow().show();
		}).size(220, 64).row();

		// ← 新增火焰图入口
		ui.cont.button("Show Flame Graph", () -> {
			new FlameGraphWindow().show();
		}).size(220, 64);
	}

	public void build() {
		ui.show();
	}

	// ─────────────────────────────────────────────────────────────────────────
	// 下方代码与原始版本完全相同
	// ─────────────────────────────────────────────────────────────────────────

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
			cont.table(t -> {
				search = new Search<>((_, p) -> pattern = p);
				t.button(Icon.trashSmall, ProfilerData::clear).size(40);
				t.button(Icon.refreshSmall, this::fullRebuild).size(40);
				search.build(t, null);
			}).growX().row();

			statsTable = new FilterTable<>();

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
		private Seq<ProfileStats> getSortedStats() {
			Pools.freeAll(currentStats, true);
			currentStats.clear();
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

		private void sortUI() {
			Seq<ProfileStats> stats    = getSortedStats();
			var               cells    = statsTable.getCells();
			var               children = statsTable.getChildren();
			cells.clear();
			children.clear();
			for (int i = 0; i < stats.size; i++) {
				ProfileStats          s     = stats.get(i);
				FilterTable.CellGroup group = statsTable.findBind(s.name);
				if (group != null && !group.removed) {
					for (int j = 0; j < group.size; j++) {
						BindCell bc = group.get(j);
						if (bc.cell != null) {
							cells.add(bc.cell);
							if (bc.cell.get() != null) children.add(bc.cell.get());
						}
					}
				}
			}
			statsTable.invalidate();
		}

		private void fullRebuild() {
			statsTable.clear();
			statsTable.top();
			statsTable.setPatternUpdateListener(() -> pattern);
			Seq<ProfileStats> stats = getSortedStats();
			for (ProfileStats s : stats) {
				final String mName = s.name;
				statsTable.bind(mName);
				statsTable.table(Styles.black3, t -> {
					t.left().defaults().left().width(colWidth).padLeft(4f);
					t.add(displayName(mName)).growX().width(methodColWidth).ellipsis(true).tooltip(mName);
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
					t.label(new SingleProv(() -> {
						MethodStats stat = ProfilerData.stats.get(mName);
						return Tmp.v1.set(stat != null ? stat.time().sum() / 1_000_000f : 0f, 0);
					}));
					t.label(new SingleProv(() -> {
						MethodStats stat = ProfilerData.stats.get(mName);
						return Tmp.v1.set(stat != null ? stat.count().sum() : 0, 0);
					})).color(Color.gray);
				}).name(mName).growX().pad(2).row();
			}
			statsTable.unbind();
		}
	}

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
				 })
				 .checked(i -> selectedBase == Building.class);
				t.button("Unit", Styles.flatTogglet, () -> {
					 selectedBase = Unit.class;
					 rebuild();
				 })
				 .checked(i -> selectedBase == Unit.class);
				t.button("Bullet", Styles.flatTogglet, () -> {
					 selectedBase = Bullet.class;
					 rebuild();
				 })
				 .checked(i -> selectedBase == Bullet.class);
				t.button("Effect", Styles.flatTogglet, () -> {
					 selectedBase = Effect.class;
					 rebuild();
				 })
				 .checked(i -> selectedBase == Effect.class);
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
					 .update(l -> l.setColor(
						ProfilerTransformer.hasTargetMethod(selectedBase, mName) ? Pal.accent : Color.white));
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
			stats.avgMs = avgMs(totalNanos, calls);
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


	public void loadSettings(Data SETTINGS) {
		Contents.settings_ui.addSection(localizedName(), icon, t -> {
			ISettings.buildAll(name, t, Settings.class);
		});
	}

	public enum Settings implements ISettings {
		// 单位ms
		mode(Mode.class, it -> it.buildEnum(Mode.sample, Mode.class)),
		@FlushField
		sampleFreq(int.class, it -> it.$(SamplingProfiler.intervalMs, 1, 10)),
		;
		Settings(Class<?> type, Cons<ISettings> builder) { }

		static {
			Runnable r = () -> {
				Tools.runWhen(HotSwapManager::loaded, () -> SamplingProfiler.toggleSampling(R_profiler.mode == Mode.sample));
			};
			mode.onChange(r);
			r.run();
		}
	}

	public enum Mode {
		instrument,
		sample,
	}
}