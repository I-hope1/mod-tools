package modtools.ui.comp.completion;

import arc.Core;
import arc.func.Cons;
import arc.graphics.Color;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.Seq;
import arc.util.*;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.ui.Styles;
import modtools.ui.HopeStyles;
import modtools.ui.control.HopeInput;

/**
 * 补全候选弹窗。
 * <p>
 * 只负责渲染与选中：候选怎么来的、什么时候弹出由 {@link Completion} 与
 * {@code Tester.ComplementListener} 决定。同一个基对象上连续输入走 {@link #narrow}，
 * 只过滤已经收集到的候选，不再向 Rhino 重新取一遍 id。
 * <p>
 * 候选可能有上千条，所以按滚动位置只渲染视口内的那一段，未渲染的部分用上下两个
 * 占位元素撑出真实高度。这样「每帧要排的行数」与候选总数无关，而滚动又能到最后
 * 一条——两个目标同时成立，不需要在某个行数上截断。
 */
public class CompletionPopup extends Table {
	/** 单行高度（屏幕像素） */
	public static final float ROW_HEIGHT        = 24f;
	/** 视口上下各多渲染几行：滚动时窗口边缘不会先露出空白 */
	private static final int   OVERSCAN         = 4;
	/** 弹窗最多占屏幕高度的比例 */
	private static final float MAX_HEIGHT_RATIO = 0.9f;

	private final TextField  area;
	private final ScrollPane scrollPane;
	private final Table      listTable;

	/** 本次补全会话的全部候选，已按相关性排序 */
	private final Seq<String> candidates = new Seq<>();
	/** 按当前前缀过滤后的候选 */
	private final Seq<String> filtered   = new Seq<>();
	/** 按钮按窗口内序号复用：每敲一个字符重建一批 TextButton 会持续产生垃圾 */
	private final Seq<TextButton> buttonPool = new Seq<>();
	/** 当前渲染出来的行，对应 {@link #filtered} 的 [windowStart, windowStart + windowCount) */
	private final Seq<TextButton> suggestionButtons = new Seq<>();
	/** 窗口上下的占位，撑出未渲染部分的真实高度。用空 Table 是因为 Element 带泛型参数，没法直接 new */
	private final Table topSpacer = new Table(), bottomSpacer = new Table();

	private int windowStart;
	private int windowCount;

	private Cons<String> onSelect;
	/** 候选的基对象，以及它是不是成员访问；会话能否延续靠这两者判定 */
	private Object  base;
	private boolean memberAccess;
	private int     selectedIndex = -1;
	/** 供 JSSyntax 用：补全期间把高亮解析截止到 '.' 之前 */
	public  int     cursorBeforeDot;
	private String  currentPrefix = "";

	public CompletionPopup(TextField area) {
		super(Tex.pane);
		this.area = area;
		touchable = Touchable.enabled;
		visible = false;

		listTable = new Table();
		scrollPane = new ScrollPane(listTable, Styles.smallPane);
		scrollPane.setFadeScrollBars(false);
		scrollPane.setOverscroll(false, false);
		// 不让弹窗抢走文本区的滚动焦点
		scrollPane.setCancelTouchFocus(false);
		// 滚动时换窗口。放在 ScrollPane 上而不是本表上：本表的 update 槽位已经被 keepInStage 占了
		scrollPane.update(this::syncWindow);

		add(scrollPane).grow().maxHeight(maxListHeight());

		update(this::keepInStage);
	}

	/** 点击弹窗之外的地方收起。只在显示期间挂着，避免永久监听整棵 scene */
	private final InputListener outsideListener = new InputListener() {
		@Override
		public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
			Element target = event.targetActor;
			if (isShown() && target != CompletionPopup.this
			    && (target == null || !target.isDescendantOf(CompletionPopup.this))) {
				hide();
			}
			return false;
		}
	};

	public void show(Seq<String> suggestions, String prefix, Object base, boolean memberAccess,
	                 Cons<String> onSelect, Vec2 pos) {
		candidates.clear().addAll(suggestions);
		this.base = base;
		this.memberAccess = memberAccess;
		this.onSelect = onSelect;
		this.currentPrefix = prefix;
		this.selectedIndex = -1;

		if (!rebuildList()) return; // 没有候选，rebuildList 已经收起

		place(pos);
		visible = true;
		touchable = Touchable.enabled;
		Core.scene.removeCaptureListener(outsideListener);
		Core.scene.addCaptureListener(outsideListener);
		toFront();
	}

	/**
	 * 在同一个基对象上继续输入：只按新前缀过滤已有候选。
	 * 调用前需确认 {@link #isSession}，否则候选集合可能不属于当前基对象。
	 */
	public void narrow(String prefix, Vec2 pos) {
		currentPrefix = prefix;
		if (!rebuildList()) return; // 过滤到空，已经收起
		place(pos);
	}

	/** 基对象没变才能复用候选：换对象（含成员访问与普通名字补全之间的切换）必须重新收集 */
	public boolean isSession(Object base, boolean memberAccess) {
		return isShown() && this.base == base && this.memberAccess == memberAccess;
	}

	/**
	 * 是否正在显示。
	 * <p>
	 * 直接用 {@link #visible} 而不是另设一个标记位：两者必须同步时，漏写一处就会「有候选
	 * 却什么都不显示」，而且看不出是哪里少了。
	 */
	public boolean isShown() {
		return visible;
	}

	public void hide() {
		visible = false;
		touchable = Touchable.disabled; // 隐藏时不吃触摸
		base = null;
		onSelect = null;
		candidates.clear();
		if (Core.scene != null) Core.scene.removeCaptureListener(outsideListener);
	}

	public boolean handleKeyDown(KeyCode keycode) {
		if (!isShown() || filtered.isEmpty()) return false;

		if (keycode == KeyCode.escape) {
			hide();
			HopeInput.pressed.remove(keycode.ordinal());
			HopeInput.justPressed.remove(keycode.ordinal());
			return true;
		}
		if (keycode == KeyCode.enter || keycode == KeyCode.tab) {
			selectCurrent();
			HopeInput.pressed.remove(keycode.ordinal());
			HopeInput.justPressed.remove(keycode.ordinal());
			return true;
		}
		if (keycode == KeyCode.up) {
			selectedIndex = Mathf.clamp(selectedIndex - 1, 0, filtered.size - 1);
			highlightSelection();
			scrollSelectionIntoView();
			return true;
		}
		if (keycode == KeyCode.down) {
			selectedIndex = Mathf.clamp(selectedIndex + 1, 0, filtered.size - 1);
			highlightSelection();
			scrollSelectionIntoView();
			return true;
		}
		return false;
	}

	private void place(Vec2 pos) {
		float lineHeight = area.getStyle().font.getLineHeight();

		// 优先放在光标上一行，放不下再放到下一行
		float popupY = pos.y - getHeight() - lineHeight * 0.2f;
		if (popupY < 0) popupY = pos.y + lineHeight;

		float popupX = pos.x;
		if (popupX + getWidth() > Core.graphics.getWidth()) popupX = Core.graphics.getWidth() - getWidth();
		popupX = Math.max(0, popupX);

		if (popupY + getHeight() > Core.graphics.getHeight()) popupY = Core.graphics.getHeight() - getHeight();
		popupY = Math.max(0, popupY);

		setPosition(popupX, popupY);
	}

	/** 列表最多能占的高度（场景单位），和构造里给 cell 的 maxHeight 是同一个值 */
	private float maxListHeight() {
		return Core.graphics.getHeight() / Scl.scl() * MAX_HEIGHT_RATIO;
	}

	/** 按当前前缀过滤候选并回到顶部；一条都不剩时收起并返回 false */
	private boolean rebuildList() {
		filtered.clear();
		for (String name : candidates) {
			if (Completion.rank(name, currentPrefix) != Completion.NO_MATCH) filtered.add(name);
		}

		if (filtered.isEmpty()) {
			hide();
			return false;
		}

		selectedIndex = Mathf.clamp(selectedIndex, 0, filtered.size - 1);
		// 换了前缀就回到顶部：沿用上一轮的滚动位置会让人以为列表少了内容
		scrollToImmediate(0);
		// 候选变了，窗口内容必须重建，不能因为首行下标没变就跳过
		windowStart = -1;
		windowCount = 0;
		renderWindow();

		listTable.layout();
		listTable.invalidateHierarchy();
		pack();
		return true;
	}

	/** 滚动时换窗口。每帧都会调用，但只有首行真的变了才重建行 */
	private void syncWindow() {
		if (isShown()) renderWindow();
	}

	/**
	 * 按滚动位置渲染 [windowStart, windowStart + windowCount) 这一段行。
	 * <p>
	 * 未渲染的行由 {@link #topSpacer} / {@link #bottomSpacer} 按行高撑出高度，所以整表高度
	 * 始终等于全部候选的高度，滚动条和滚动范围都不受「当前渲染了几行」影响。
	 */
	private void renderWindow() {
		float rowH    = ROW_HEIGHT * Scl.scl();
		int   total   = filtered.size;
		int   visible = Math.max(1, Math.min(total, (int) Math.ceil(maxListHeight() / rowH)));
		int   top     = Mathf.clamp((int) (scrollPane.getScrollY() / rowH), 0, Math.max(0, total - 1));

		int start = Math.max(0, top - OVERSCAN);
		int end   = Math.min(total, top + visible + OVERSCAN);
		if (start == windowStart && end - start == windowCount) return;

		windowStart = start;
		windowCount = end - start;

		listTable.clearChildren(); // 会摘掉旧行，按钮仍留在 pool 里
		suggestionButtons.clear();

		if (start > 0) listTable.add(topSpacer).height(start * rowH).row();
		for (int i = start; i < end; i++) {
			String     name   = filtered.get(i);
			TextButton button = button(suggestionButtons.size);
			if (!name.contentEquals(button.getText())) button.setText(name);
			button.userObject = name;
			listTable.add(button).wrapLabel(false).growX().height(rowH).row();
			suggestionButtons.add(button);
		}
		if (end < total) listTable.add(bottomSpacer).height((total - end) * rowH).row();

		highlightSelection();
		listTable.invalidateHierarchy();
	}

	private TextButton button(int index) {
		if (index < buttonPool.size) return buttonPool.get(index);

		TextButton button = new TextButton("", HopeStyles.cleart);
		button.getLabelCell().labelAlign(Align.left).padLeft(4).padRight(4);
		// 复用同一个按钮，所以候选名从 userObject 取，而不是在闭包里捕获
		button.clicked(() -> {
			Object name = button.userObject;
			if (name != null) select(name.toString());
		});
		buttonPool.add(button);
		return button;
	}

	private void selectCurrent() {
		if (selectedIndex >= 0 && selectedIndex < filtered.size) select(filtered.get(selectedIndex));
		else hide();
	}

	private void select(String suggestion) {
		Cons<String> cons = onSelect;
		// 先收起：之后的文本变更会触发高亮，那时 cursor() 必须回到真实光标
		hide();
		if (cons != null) cons.get(suggestion);
	}

	/** 高亮按 {@link #selectedIndex} 判定，而不是按按钮在窗口里的位置 */
	private void highlightSelection() {
		for (int i = 0; i < suggestionButtons.size; i++) {
			suggestionButtons.get(i).getLabel().setColor(windowStart + i == selectedIndex ? Pal.accent : Color.white);
		}
	}

	/**
	 * 键盘上下移动时把选中行滚进视口。
	 * <p>
	 * 不走 {@link ScrollPane#scrollTo}：它的目标值依赖上一次布局算出来的 {@code maxY}，
	 * 列表刚重建时那个值是旧的，落点会偏。这里按行号直接算出滚动量。
	 */
	private void scrollSelectionIntoView() {
		if (selectedIndex < 0 || selectedIndex >= filtered.size) return;

		float rowH      = ROW_HEIGHT * Scl.scl();
		float viewH     = Math.min(filtered.size * rowH, maxListHeight());
		float maxScroll = filtered.size * rowH - viewH;

		float top    = selectedIndex * rowH; // 选中行上边缘距列表顶部的距离
		float target = scrollPane.getScrollY();
		if (top < target) target = top;
		else if (top + rowH > target + viewH) target = top + rowH - viewH;

		scrollToImmediate(Mathf.clamp(target, 0, maxScroll));
		renderWindow();
	}

	/**
	 * 立即滚到指定位置，不走进度动画。
	 * <p>
	 * {@code scrollTo} 只写目标值，视觉位置由 {@code act()} 逐帧追上去（默认开平滑滚动）。
	 * 列表刚重建、键盘上下选行时都希望一步到位：前者的起点是上一轮列表的位置，动画会
	 * 从无关的地方滑过来；后者逐行追会显得拖沓。{@code setScrollYForce} 同时写两个量，
	 * 正好是「立刻」的语义。
	 */
	private void scrollToImmediate(float y) {
		scrollPane.setScrollYForce(y);
	}
}
