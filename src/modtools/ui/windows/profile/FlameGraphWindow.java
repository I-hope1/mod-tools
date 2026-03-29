package modtools.ui.windows.profile;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.pooling.*;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import modtools.content.world.*;
import modtools.events.ISettings;
import modtools.ui.*;
import modtools.ui.IntUI.*;
import modtools.ui.comp.Window;
import modtools.ui.effect.MyDraw;
import modtools.utils.ElementUtils;
import modtools.utils.profiler.SamplingProfiler;
import nipx.profiler.ProfilerData;
import nipx.profiler.ProfilerData.FlameNode;

import java.util.*;
import java.util.function.Consumer;

import static modtools.ui.Contents.profiler;

/**
 * IDEA 风格火焰图窗口（Icicle 视图，根在顶、叶在底）。
 *
 * <h3>交互</h3>
 * <ul>
 *   <li>点击色块 → 缩放进入（该节点铺满全宽）</li>
 *   <li>← Back → 返回上一层缩放</li>
 *   <li>Reset  → 回到树根</li>
 *   <li>搜索框 → 高亮匹配方法（不区分大小写子串匹配）</li>
 *   <li>悬停   → 底部信息栏显示耗时详情</li>
 * </ul>
 */
public class FlameGraphWindow extends Window {

	// ─── UI 组件 ─────────────────────────────────────────────────────────────
	private FlameCanvas canvas;
	private Label       infoLabel;
	private TextField   searchField;
	private Button      backButton;
	private Button      liveButton;
	private SelectTable navigatorTable;

	private static final FlameGraphWindow instance = new FlameGraphWindow();

	public static void staticShow() {
		instance.show();
	}
	private FlameGraphWindow() {
		super("Flame Graph", 780, 620, true);
		buildUI();
		addListener(new ResizeListener() {
			public void resized() {
				Core.app.post(FlameGraphWindow.this::refresh);
			}
		});
	}

	private void buildUI() {
		// ── 顶部工具栏 ──────────────────────────────────────────────────────
		cont.table(bar -> {
			backButton = bar.button(Icon.leftSmall, HopeStyles.flati, this::goBack).size(32).pad(3).get();
			backButton.setDisabled(true);
			bar.button(Icon.eraserSmall, HopeStyles.flati, ProfilerData::clear).size(32).pad(3).tooltip("Clear Profile Data");
			bar.button(Icon.refreshSmall, HopeStyles.flati, this::refresh).size(32).pad(3);
			bar.button("Reset", Styles.flatt, () -> {
				canvas.resetZoom();
				syncBackButton();
			}).size(84, 34).pad(3);
			bar.button("Config", Icon.settingsSmall, HopeStyles.flatt, () -> {
			}).with(b -> b.clicked(() -> {
				IntUI.showSelectTable(b, (p, _, _) -> {
					p.add().width(160).row();
					ISettings.buildAll(profiler.name, p, Profiler.Settings.class);
				}, false);
			})).size(108, 32);

			bar.image(Icon.zoomSmall);
			searchField = bar.field("", txt -> {
				 canvas.searchQuery = txt.toLowerCase(Locale.ROOT);
				 canvas.invalidate();
				 if (navigatorTable != null) {
					 return;
				 }
				 navigatorTable = IntUI.showSelectListTable(searchField, canvas.slots,
					() -> null,
					slot -> canvas.zoomIn(slot.node), slot -> slot.node.name + ": " + slot.node.totalNanos.sum(),
					120, 32, true, Align.bottom);
				 float y1      = ElementUtils.getAbsolutePos(searchField).y;
				 int   height1 = Core.graphics.getHeight();
				 navigatorTable.cell.height(Mathf.clamp(Math.max(height1 - y1, y1) - searchField.getHeight() - 6f, 0, Core.graphics.getHeight()));
				 navigatorTable.hidden(() -> navigatorTable = null);
			 })
			 .growX().height(32).pad(3).get();
			searchField.setMessageText("@players.search");
		}).growX().padBottom(2).row();

		// ── 画布（放在 ScrollPane 里支持纵向滚动） ───────────────────────────
		canvas = new FlameCanvas();
		canvas.onZoomChanged = this::syncBackButton;

		cont.pane(pane -> pane.add(canvas).grow()).grow().row();

		// ── 底部信息栏 ───────────────────────────────────────────────────────
		infoLabel = cont.add("Hover over a block for details").height(64).fontScale(0.9f).growX()
		 .pad(4, 8, 4, 8).get();
		infoLabel.setWrap(true);
		infoLabel.setAlignment(Align.left);

		canvas.onHover = node -> {
			if (node == null) {
				infoLabel.setText("Hover over a block for details");
				return;
			}
			long   total   = node.totalNanos.sum();
			long   self    = selfNanos(node);
			long   rootTot = effectiveTotal(canvas.currentRoot);
			double pct     = rootTot > 0 ? 100.0 * total / rootTot : 0.0;
			// 采样模式下 totalNanos 单位是 intervalMs * 1_000_000，换算回 ms 时直接除即可
			boolean sampling = SamplingProfiler.isRunning();
			String unit = sampling
			 ? String.format("~%.0f samples", total / (SamplingProfiler.intervalMs * 1_000_000.0))
			 : String.format("%.3f ms", total / 1_000_000.0);
			infoLabel.setText(String.format(
			 "%s   |   %s (%.1f%%)   |   self %.3f ms   |   children %d",
			 node.name, unit, pct, self / 1_000_000.0, node.children.size()));
		};

		canvas.resetZoom();

		shown(() -> Core.app.post(this::refresh));
	}
	private void refresh() {
		canvas.rebuild(cont.getWidth() - 32);
		syncBackButton();
	}

	private void goBack() {
		canvas.zoomOut();
		syncBackButton();
	}

	private void syncBackButton() {
		backButton.setDisabled(canvas.zoomStack.isEmpty());
	}

	// ─── 工具方法 ────────────────────────────────────────────────────────────

	static long selfNanos(FlameNode node) {
		long childSum = 0;
		for (FlameNode c : node.children.values()) childSum += c.totalNanos.sum();
		return Math.max(0, node.totalNanos.sum() - childSum);
	}
	static long effectiveTotal(FlameNode node) {
		long v = node.totalNanos.sum();
		if (v > 0) return v;
		long s = 0;
		for (FlameNode c : node.children.values()) s += c.totalNanos.sum();
		return s;
	}

	// ═══════════════════════════════════════════════════════════════════════════
	// FlameCanvas：自绘 Widget，负责布局 + 渲染 + 交互
	// ═══════════════════════════════════════════════════════════════════════════
	static final class FlameCanvas extends Element {

		static final float ROW_H            = 22f;
		static final float GAP              = 1f;
		static final int   PALETTE_SIZE     = 24;
		static final long  LIVE_INTERVAL_MS = 500;

		static final Color[] PALETTE = buildPalette();
		static Color[] buildPalette() {
			Color[] p = new Color[PALETTE_SIZE];
			for (int i = 0; i < PALETTE_SIZE; i++) {
				float hue = (360f / PALETTE_SIZE) * i;
				if (hue >= 210 && hue <= 260) hue += 30;
				p[i] = Color.white.cpy().fromHsv(hue, 0.72f, 0.82f);
			}
			return p;
		}

		static final Color SEARCH_HIGHLIGHT = Color.valueOf("ffdd55cc");
		static final Color SELF_TIME_TINT   = Color.valueOf("00000044");

		FlameNode currentRoot = ProfilerData.flameRoot;
		final Deque<FlameNode> zoomStack = new ArrayDeque<>();
		String searchQuery = "";
		private long lastLiveMs = 0;


		static final class Slot {
			static final Pool<Slot> pool = Pools.get(Slot.class, Slot::new);
			private Slot() { }

			FlameNode node;
			float     x, y, w, childrenW;
			int   depth;
			Color color;
			static Slot obtain(FlameNode node, float x, float y, float w, int depth, Color color, float childrenW) {
				Slot slot = pool.obtain();
				slot.node = node;
				slot.x = x;
				slot.y = y;
				slot.w = w;
				slot.depth = depth;
				slot.color = color;
				slot.childrenW = childrenW;
				return slot;
			}
		}

		final Seq<Slot> slots = new Seq<>();
		float prefW = 600, prefH = 200;
		FlameNode hoveredNode = null;

		Consumer<FlameNode> onHover;
		Runnable            onZoomChanged;

		void rebuild(float availW) {
			Pools.freeAll(slots, true);
			slots.clear();
			hoveredNode = null;
			if (currentRoot.children.isEmpty()) {
				prefH = 60;
				invalidateHierarchy();
				return;
			}
			prefW = Math.max(availW, 400);
			prefH = (currentRoot.maxDepth(0) + 1) * (ROW_H + GAP) + 24;
			layout(currentRoot, 0f, prefW, 0);
			invalidateHierarchy();
		}

		void layout(FlameNode node, float x, float w, int depth) {
			if (w < 0.5f) return;
			float slotY     = prefH - (depth + 1) * (ROW_H + GAP);
			long  nodeTotal = effectiveTotal(node);
			float cx        = x, childW = 0;
			for (FlameNode child : node.children.values()) {
				float cw = nodeTotal > 0 ? w * child.totalNanos.sum() / nodeTotal : 0;
				layout(child, cx, cw, depth + 1);
				childW += cw;
				cx += cw;
			}
			slots.add(Slot.obtain(node, x, slotY, w, depth, colorOf(node.name), childW));
		}

		void liveUpdate() {
			if (slots.isEmpty()) return;
			Slot root = slots.peek();
			applyWidths(root, 0f, prefW);
		}

		void applyWidths(Slot parentSlot, float x, float w) {
			parentSlot.x = x;
			parentSlot.w = w;
			long  nodeTotal  = effectiveTotal(parentSlot.node);
			float cx         = x, childW = 0;
			int   childDepth = parentSlot.depth + 1;
			for (FlameNode child : parentSlot.node.children.values()) {
				float cw = nodeTotal > 0 ? w * child.totalNanos.sum() / nodeTotal : 0;
				if (cw >= 0.5f) {
					Slot cs = findSlot(child, childDepth);
					if (cs != null) applyWidths(cs, cx, cw);
				}
				childW += cw;
				cx += cw;
			}
			parentSlot.childrenW = childW;
		}

		Slot findSlot(FlameNode node, int depth) {
			for (Slot s : slots) if (s.node == node && s.depth == depth) return s;
			return null;
		}

		@Override
		public void act(float delta) {
			super.act(delta);
			if (!R_profiler.auto_update_pane) return;
			long now = System.currentTimeMillis();
			if (now - lastLiveMs < LIVE_INTERVAL_MS) return;
			lastLiveMs = now;
			if (countNodes(currentRoot) != slots.size) { rebuild(prefW); } else liveUpdate();
		}

		void zoomIn(FlameNode node) {
			if (node.children.isEmpty()) return;
			zoomStack.push(currentRoot);
			currentRoot = node;
			rebuild(prefW);
			if (onZoomChanged != null) onZoomChanged.run();
		}
		void zoomOut() {
			if (zoomStack.isEmpty()) return;
			currentRoot = zoomStack.pop();
			rebuild(prefW);
			if (onZoomChanged != null) onZoomChanged.run();
		}
		void resetZoom() {
			zoomStack.clear();
			currentRoot = ProfilerData.flameRoot;
			rebuild(prefW);
		}

		{
			addListener(new HoverAndExitListener() {
				@Override
				public boolean mouseMoved(InputEvent ev, float mx, float my) {
					FlameNode prev = hoveredNode;
					hoveredNode = null;
					for (Slot s : slots)
						if (s.w >= 0.5f && mx >= s.x && mx < s.x + s.w && my >= s.y && my < s.y + ROW_H) {
							hoveredNode = s.node;
							break;
						}
					if (hoveredNode != prev && onHover != null) onHover.accept(hoveredNode);
					return false;
				}
				@Override
				public boolean touchDown(InputEvent ev, float mx, float my, int ptr, KeyCode button) {
					for (Slot s : slots)
						if (s.w >= 0.5f && mx >= s.x && mx < s.x + s.w && my >= s.y && my < s.y + ROW_H) {
							zoomIn(s.node);
							return true;
						}
					return false;
				}
				@Override
				public void exit0(InputEvent ev, float x, float y, int ptr, Element toActor) {
					if (hoveredNode != null) {
						hoveredNode = null;
						if (onHover != null) onHover.accept(null);
					}
				}
			});
		}

		@Override
		public float getPrefWidth() { return prefW; }
		@Override
		public float getPrefHeight() { return prefH; }

		@Override
		public void draw() {
			validate();
			if (slots.isEmpty()) {
				Draw.color(Color.darkGray, parentAlpha * 0.5f);
				Fill.rect(x + width / 2f, y + height / 2f, width, height);
				Font f = getFont();
				Draw.color(Color.lightGray, parentAlpha);
				if (f != null) {
					f.draw("No data — enable probes or start Sampling mode, then Refresh", x + 12, y + height / 2f + 6);
				}
				Draw.color(Color.white, parentAlpha);
				return;
			}
			boolean hasSearch = !searchQuery.isEmpty();
			Font    font      = getFont();
			for (Slot s : slots) {
				if (s.w < 0.5f) continue;
				float wx    = x + s.x, wy = y + s.y, rw = Math.max(s.w - GAP, 0), rh = ROW_H - GAP, cx = wx + rw / 2f, cy = wy + rh / 2f;
				float alpha = parentAlpha;
				if (hasSearch) {
					if (s.node.name.toLowerCase(Locale.ROOT).contains(searchQuery)) {
						Draw.color(SEARCH_HIGHLIGHT, alpha);
						Fill.rect(cx, cy, rw + 2, rh + 2);
					} else { alpha *= 0.25f; }
				}
				Draw.color(s.color, alpha);
				Fill.rect(cx, cy, rw, rh);
				if (s.w > 8f && s.childrenW < s.w - 2f) {
					float sw = rw - s.childrenW;
					if (sw > 1f) {
						Draw.color(SELF_TIME_TINT, alpha);
						Fill.rect(wx + s.childrenW + sw / 2f, cy, sw, rh);
					}
				}
				if (s.node == hoveredNode) {
					Draw.color(Color.white, parentAlpha);
					Lines.rect(wx, wy, rw, rh);
				}
				if (font != null && rw > 24) {
					Draw.color(Color.white, parentAlpha);
					String lbl = fitLabel(font, s.node.name, rw - 6);
					if (!lbl.isEmpty()) MyDraw.drawText(lbl, wx + 3, wy + rh - 3, Color.white, Align.left);
				}
			}
			Draw.color(Color.white, parentAlpha);
		}

		static long effectiveTotal(FlameNode node) {
			long v = node.totalNanos.sum();
			if (v > 0) return v;
			long s = 0;
			for (FlameNode c : node.children.values()) s += c.totalNanos.sum();
			return s;
		}
		static int countNodes(FlameNode node) {
			int n = 1;
			for (FlameNode c : node.children.values()) n += countNodes(c);
			return n;
		}
		static Color colorOf(String name) { return PALETTE[Math.abs(name.hashCode()) % PALETTE_SIZE]; }
		private static Font getFont() { return MyFonts.def; }

		private static final GlyphLayout _gl = new GlyphLayout();
		private static String fitLabel(Font font, String text, float maxWidth) {
			_gl.setText(font, text);
			if (_gl.width <= maxWidth) return text;
			int    dot    = text.lastIndexOf('.');
			String simple = dot >= 0 ? text.substring(dot + 1) : text;
			_gl.setText(font, simple);
			if (_gl.width <= maxWidth) return simple;
			int    lo     = 1, hi = simple.length();
			String result = "";
			while (lo <= hi) {
				int    mid = (lo + hi) >> 1;
				String c   = simple.substring(0, mid) + "…";
				_gl.setText(font, c);
				if (_gl.width <= maxWidth) {
					result = c;
					lo = mid + 1;
				} else { hi = mid - 1; }
			}
			return result;
		}
	}
}