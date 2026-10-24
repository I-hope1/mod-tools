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
import modtools.ui.IntUI;
import modtools.ui.control.HopeInput;

/**
 * 补全候选弹窗。
 * <p>
 * 只负责渲染与选中：候选怎么来的、什么时候弹出由 {@link Completion} 与
 * {@code Tester.ComplementListener} 决定。同一个基对象上连续输入走 {@link #narrow}，
 * 只过滤已经收集到的候选，不再向 Rhino 重新取一遍 id。
 * <p>
 * 高度按光标行的另一侧裁（见 {@link #availableHeight()}），候选全部排进列表、由
 * {@link ScrollPane} 自己滚动。不在这里按滚动位置换窗口：那要求「表格高度」和
 * 「滚动量」始终同步，而这两者分别由 {@code Table} 和 {@code ScrollPane} 维护，
 * 一旦有一帧不同步，画面就会停在中段。
 */
public class CompletionPopup extends Table {
	/** 单行高度（未缩放的 UI 单位；{@link Cell} 会再乘一次缩放） */
	public static final float ROW_HEIGHT        = 24f;
	/** 弹窗最多占屏幕高度的比例 */
	private static final float MAX_HEIGHT_RATIO = 0.9f;

	private final TextField        area;
	private final ScrollPane       scrollPane;
	private final Table            listTable;
	private final Cell<ScrollPane> listCell;

	/** 本次补全会话的全部候选，已按相关性排序 */
	private final Seq<String> candidates = new Seq<>();
	/** 按当前前缀过滤后的候选 */
	private final Seq<String> filtered   = new Seq<>();
	/** 按钮按下标复用：每敲一个字符重建一批 TextButton 会持续产生垃圾 */
	private final Seq<TextButton> buttonPool = new Seq<>();

	/** 光标所在词的首字符位置，决定弹窗放上还是放下 */
	private final Vec2 anchor     = new Vec2();
	/** 本次允许占用的最大高度：光标上方或下方的剩余空间，弹窗不会盖住正在输入的那一行 */
	private float      listHeight;

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

		listCell = add(scrollPane).grow();

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
		this.anchor.set(pos);

		if (!rebuildList()) return; // 没有候选，rebuildList 已经收起

		place();
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
		anchor.set(pos);
		if (!rebuildList()) return; // 过滤到空，已经收起
		place();
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

	/**
	 * 摆到光标那一行的上方或下方，优先空间大的一侧。
	 * <p>
	 * 高度已经在 {@link #rebuildList()} 里按同一侧的空间裁过，所以弹窗永远落在光标行的
	 * 另一侧，不会盖住正在输入的那一行——高到占满屏幕的候选列表会挡住输入，看不到自己在打什么。
	 * 具体落点交给 {@link IntUI#positionTooltip(Vec2, int, Table)}，和菜单/浮窗同一套翻转规则。
	 */
	private void place() {
		float lineHeight = area.getStyle().font.getLineHeight();
		float above      = anchor.y - lineHeight * 0.2f;
		float below      = Core.scene.getHeight() - anchor.y - lineHeight;

		// 和 availableHeight() 用同一个判据：高度是按更大的一侧裁的，那一侧必然放得下
		boolean up = above >= below;
		IntUI.positionTooltip(
		 up ? Tmp.v1.set(anchor.x, above) : Tmp.v1.set(anchor.x, anchor.y + lineHeight),
		 up ? Align.bottomLeft : Align.topLeft, this);
	}

	/**
	 * 光标行上方 / 下方能放下多少高度（场景单位）。
	 * <p>
	 * 取更大的一侧，再压到屏幕高度的上限。这样候选再多也只会把列表压短、让它内部滚动，
	 * 不会把弹窗撑到跨过光标行。
	 */
	private float availableHeight() {
		float lineHeight = area.getStyle().font.getLineHeight();
		float screenH    = Core.scene.getHeight();

		float above = anchor.y - lineHeight * 0.2f;          // 光标行下沿往上都是空的
		float below = screenH - anchor.y - lineHeight;       // 光标行上沿往下
		return Math.max(lineHeight, Math.min(Math.max(above, below), screenH * MAX_HEIGHT_RATIO));
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
		// 高度跟着光标位置走
		listHeight = availableHeight();
		listCell.maxHeight(listHeight);

		listTable.clearChildren(); // 会摘掉旧行，按钮仍留在 pool 里
		for (int i = 0; i < filtered.size; i++) {
			String     name   = filtered.get(i);
			TextButton button = button(i);
			if (!name.contentEquals(button.getText())) button.setText(name);
			button.userObject = name;
			listTable.add(button).wrapLabel(false).growX().height(ROW_HEIGHT).row();
		}
		highlightSelection();
		listTable.invalidateHierarchy();

		pack();
		// 换了前缀就回到顶部：沿用上一轮的滚动位置会让人以为列表少了内容
		scrollToImmediate(0);
		return true;
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

	private void highlightSelection() {
		// 只染真正排进列表的那些：pool 里可能还留着上一轮更长的尾巴
		for (int i = 0; i < filtered.size; i++) {
			buttonPool.get(i).getLabel().setColor(i == selectedIndex ? Pal.accent : Color.white);
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

		// Cell 的尺寸 setter 自带一次 Scl.scl，所以实际行高是 Scl.scl(ROW_HEIGHT)
		float rowH      = Scl.scl(ROW_HEIGHT);
		float viewH     = Math.min(filtered.size * rowH, listHeight);
		float maxScroll = filtered.size * rowH - viewH;

		float top    = selectedIndex * rowH; // 选中行上边缘距列表顶部的距离
		float target = scrollPane.getScrollY();
		if (top < target) target = top;
		else if (top + rowH > target + viewH) target = top + rowH - viewH;

		scrollToImmediate(Mathf.clamp(target, 0, maxScroll));
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
