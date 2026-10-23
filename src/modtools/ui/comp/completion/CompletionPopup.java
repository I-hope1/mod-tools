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
 */
public class CompletionPopup extends Table {
	/** 一次最多渲染多少行：候选可能上千，行数不设上限时每次重排都要遍历全部行 */
	public static final int MAX_ROWS   = 80;
	public static final float ROW_HEIGHT = 24f;

	private final TextField  area;
	private final ScrollPane scrollPane;
	private final Table      listTable;

	/** 本次补全会话的全部候选，已按相关性排序 */
	private final Seq<String> candidates = new Seq<>();
	/** 按当前前缀过滤后的候选 */
	private final Seq<String> filtered   = new Seq<>();
	/** 按钮按行号复用：每敲一个字符重建一批 TextButton 会持续产生垃圾 */
	private final Seq<TextButton> buttonPool = new Seq<>();
	/** 当前真正显示出来的那些行 */
	private final Seq<TextButton> suggestionButtons = new Seq<>();

	private Cons<String> onSelect;
	/** 候选的基对象，以及它是不是成员访问；会话能否延续靠这两者判定 */
	private Object  base;
	private boolean memberAccess;
	private int     selectedIndex = -1;
	/** 供 JSSyntax 用：补全期间把高亮解析截止到 '.' 之前 */
	public  int     cursorBeforeDot;
	private String  currentPrefix = "";
	private boolean scrollPending = false;

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

		add(scrollPane).grow().maxHeight(Core.graphics.getHeight() / Scl.scl() * 0.9f);

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
		if (!isShown() || suggestionButtons.isEmpty()) return false;

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
			selectedIndex = Mathf.clamp(selectedIndex - 1, 0, suggestionButtons.size - 1);
			highlightSelection();
			scrollToSelection();
			return true;
		}
		if (keycode == KeyCode.down) {
			selectedIndex = Mathf.clamp(selectedIndex + 1, 0, suggestionButtons.size - 1);
			highlightSelection();
			scrollToSelection();
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

	/** 按当前前缀重建候选行；一行都排不出来时自行收起并返回 false */
	private boolean rebuildList() {
		filtered.clear();
		for (String name : candidates) {
			if (Completion.rank(name, currentPrefix) != Completion.NO_MATCH) filtered.add(name);
		}

		listTable.clearChildren(); // 会摘掉旧行，按钮仍留在 pool 里
		suggestionButtons.clear();

		int rows = Math.min(filtered.size, MAX_ROWS);
		for (int i = 0; i < rows; i++) {
			String     name   = filtered.get(i);
			TextButton button = button(i);
			if (!name.contentEquals(button.getText())) button.setText(name);
			button.userObject = name;
			listTable.add(button).wrapLabel(false).growX().height(ROW_HEIGHT * Scl.scl()).row();
			suggestionButtons.add(button);
		}
		if (filtered.size > rows) {
			// 传字符串而不是整数：MessageFormat 会把数字按 locale 加千位分隔
			listTable.add(Core.bundle.format("tester.completion.more", String.valueOf(filtered.size - rows)))
			 .color(Color.gray).left().padLeft(6f).padTop(4f).row();
		}

		if (suggestionButtons.isEmpty()) {
			hide();
			return false;
		}

		selectedIndex = Mathf.clamp(selectedIndex, 0, suggestionButtons.size - 1);
		highlightSelection();
		scrollToSelection();
		listTable.layout();
		listTable.invalidateHierarchy();
		pack();
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
		if (selectedIndex >= 0 && selectedIndex < suggestionButtons.size) {
			select(suggestionButtons.get(selectedIndex).userObject.toString());
		} else {
			hide();
		}
	}

	private void select(String suggestion) {
		Cons<String> cons = onSelect;
		// 先收起：之后的文本变更会触发高亮，那时 cursor() 必须回到真实光标
		hide();
		if (cons != null) cons.get(suggestion);
	}

	private void highlightSelection() {
		for (int i = 0; i < suggestionButtons.size; i++) {
			suggestionButtons.get(i).getLabel().setColor(i == selectedIndex ? Pal.accent : Color.white);
		}
	}

	private void scrollToSelection() {
		if (scrollPending) return;
		if (selectedIndex < 0 || selectedIndex >= suggestionButtons.size || scrollPane.getHeight() <= 0) return;

		// 等本帧布局完成，否则 scrollTo 用的是旧坐标
		scrollPending = true;
		Core.app.post(() -> {
			scrollPending = false;
			if (!isShown() || selectedIndex < 0 || selectedIndex >= suggestionButtons.size) return;
			Element selected = suggestionButtons.get(selectedIndex);
			scrollPane.scrollTo(0, selected.y, selected.getWidth(), selected.getHeight(), false, true);
		});
	}
}
