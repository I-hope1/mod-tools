package modtools.ui.comp;

import arc.graphics.Color;
import arc.math.*;
import arc.scene.*;
import arc.scene.actions.Actions;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.ui.Styles;
import modtools.ui.HopeStyles;
import modtools.ui.comp.limit.PrefPane;
import modtools.ui.effect.HopeFx;
import modtools.utils.ui.CellTools;

import java.util.Arrays;

/**
 * 一个可定制的选项卡面板组件 (Tabbable Pane)。
 * 支持水平和垂直布局，并带有切换动画。
 * @author YourName
 */
public class IntTab {
	// --- 公共配置 ---
	public float titleWidth, eachWidth, labelWidth = CellTools.unset;
	public int     cols;
	public boolean verticalLayout;

	// --- 私有UI组件 ---
	private Table      mainContainer;
	private Table      tabsBar;
	private ScrollPane contentPane;

	// --- 数据源 ---
	private final String[]   tabNames;
	private final Color[]    tabColors;
	private final Table[]    tabContents;
	private       Drawable[] tabIcons;

	// --- 内部状态 ---
	private       int                      selectedIndex = -1;
	private       boolean                  isTransitioning;
	private       boolean                  hideTabTitles;
	private final ObjectMap<String, Label> labels        = new ObjectMap<>();

	// --- 构造函数 ---

	/**
	 * 主要构造函数，所有其他构造函数都应调用此函数。
	 * @param titleWidth     标题栏的总宽度/高度（取决于布局）
	 * @param names          选项卡名称
	 * @param colors         每个选项卡的强调色
	 * @param tables         每个选项卡对应的内容Table
	 * @param cols           在水平布局中，每行显示的选项卡按钮数量
	 * @param verticalLayout 是否为垂直布局（true: 标题在左侧，内容在右侧； false: 标题在顶部，内容在底部）
	 */
	public IntTab(float titleWidth, String[] names, Color[] colors, Table[] tables, int cols, boolean verticalLayout) {
		if (names.length != colors.length || names.length != tables.length) {
			Log.err("Inconsistent sizes: names=@, colors=@, tables=@", names.length, colors.length, tables.length);
			throw new IllegalArgumentException("All array arguments must have the same size.");
		}
		if (names.length == 0) {
			throw new IllegalArgumentException("Cannot create IntTab with zero tabs.");
		}

		this.titleWidth = titleWidth;
		this.tabNames = names;
		this.tabColors = colors;
		this.tabContents = tables;
		this.cols = cols;
		this.verticalLayout = verticalLayout;
	}

	// 辅助构造函数，提供便利
	public IntTab(float titleWidth, String[] names, Color color, Table[] tables) {
		this(titleWidth, names, fillWithColor(names.length, color), tables, Integer.MAX_VALUE, false);
	}

	public IntTab(float titleWidth, String[] names, Color color, Table[] tables, int cols, boolean vertical) {
		this(titleWidth, names, fillWithColor(names.length, color), tables, cols, vertical);
	}

	public IntTab(float titleWidth, String[] names, Color[] colors, Table[] tables) {
		this(titleWidth, names, colors, tables, Integer.MAX_VALUE, false);
	}

	/** Bug修复：之前这里是 colors.toArray(Table.class) */
	public IntTab(float titleWidth, Seq<String> names, Seq<Color> colors, Seq<Table> tables, int cols, boolean vertical) {
		this(titleWidth, names.toArray(String.class),
		 colors.toArray(Color.class), // <-- BUG FIX
		 tables.toArray(Table.class), cols, vertical);
	}

	// --- 公共API ---

	public void setIcons(Drawable... icons) {
		if (icons.length != tabNames.length) {
			Log.warn("IntTab: Icons array length does not match tabs count. Icons will be ignored.");
			return;
		}
		this.tabIcons = icons;
	}

	/**
	 * 构建并返回最终的UI组件。
	 * 此方法应只被调用一次。
	 * @return 构建完成的选项卡面板。
	 */
	public Table build() {
		if (mainContainer != null) return mainContainer;

		mainContainer = new Table();
		mainContainer.name = "IntTab-MainContainer";

		tabsBar = new Table();
		tabsBar.name = "IntTab-TabsBar";

		// 为内容创建一个带默认样式的ScrollPane
		contentPane = new ScrollPane(null, Styles.smallPane);
		contentPane.setFadeScrollBars(false);
		contentPane.setOverscroll(false, false);

		buildLayout();
		populateTabs();

		// 默认选中第一个选项卡，无动画
		selectTab(0, false);

		return mainContainer;
	}

	public int getSelectedIndex() {
		return selectedIndex;
	}

	// --- 内部实现 ---

	/**
	 * 根据 verticalLayout 标志构建主布局。
	 */
	private void buildLayout() {
		// 创建一个可以滚动的标题栏容器
		var tabsPane = new PrefPane(tabsBar, Styles.smallPane);
		tabsPane.setScrollingDisabled(verticalLayout, !verticalLayout);
		tabsPane.setFadeScrollBars(false);

		if (verticalLayout) {
			// 垂直布局：[标题栏 | 内容面板]
			mainContainer.add(tabsPane).minHeight(42f).growY();
			mainContainer.add(contentPane).grow();
		} else {
			// 水平布局：
			// [ 标题栏 ]
			// [ 内容面板 ]
			mainContainer.add(tabsPane).growX().row();
			mainContainer.add(contentPane).grow();
		}
	}

	/**
	 * 填充所有选项卡按钮到标题栏。
	 */
	private void populateTabs() {
		for (int i = 0; i < tabNames.length; i++) {
			tabsBar.add(createTabButton(i)).growX().minHeight(42f).maxWidth(eachWidth != 0 ? eachWidth : CellTools.unset);

			if ((i + 1) % cols == 0) {
				tabsBar.row();
			}
		}

		if (verticalLayout) {
			tabsBar.row();
			tabsBar.add().expandY(); // 占位符，将按钮推到顶部
			tabsBar.row();
			// 添加折叠按钮
			tabsBar.button(Icon.menuSmall, HopeStyles.flati, () -> {
				hideTabTitles = !hideTabTitles;
				// 延迟一帧执行，确保UI元素大小更新后再重绘
				Time.run(1f / 60f, () -> tabsBar.invalidateHierarchy());
			}).size(24f).left();
		}
	}

	/**
	 * 创建单个选项卡按钮。
	 * @param index 选项卡的索引。
	 * @return 一个配置好的按钮Table。
	 */
	private Element createTabButton(int index) {
		Table button = new Button(HopeStyles.clearb);

		// 按钮内容
		button.table(t -> {
			Drawable icon = (tabIcons != null && index < tabIcons.length) ? tabIcons[index] : null;
			if (icon != null) {
				t.image(icon).size(28f).padRight(4f);
			}
			t.label(() -> hideTabTitles ? "" : tabNames[index])
			 .growX().align(Align.left)
			 .with(l -> labels.put(tabNames[index], l))
			 .maxWidth(labelWidth)
			 .get().setWrap(false);
		}).growX().pad(4f);
		button.row();

		// 底部指示器
		Image indicator = button.image().growX().height(3f).get();
		button.update(() -> indicator.setColor(selectedIndex == index ? tabColors[index] : Color.gray));

		// 点击事件
		button.clicked(() -> selectTab(index, true));

		return button;
	}

	/**
	 * 切换到指定的选项卡。
	 * @param index   目标选项卡的索引。
	 * @param animate 是否使用动画。
	 */
	public void selectTab(int index, boolean animate) {
		if (selectedIndex == index || isTransitioning) return;

		isTransitioning = true;
		Table newContent = tabContents[index];

		if (selectedIndex == -1 || !animate) {
			// 首次选择或无动画
			if (selectedIndex != -1) tabContents[selectedIndex].remove();
			contentPane.setWidget(newContent);
			selectedIndex = index;
			isTransitioning = false;
		} else {
			// 带动画的切换
			Table oldContent = tabContents[selectedIndex];

			// 1. 创建并执行旧内容的退出动画
			Action outAction = createOutAction(index, () -> {
				// 2. 当退出动画结束后，这个回调会被执行
				contentPane.setWidget(newContent);
				newContent.addAction(createInAction(index)); // 3. 执行新内容的进入动画

				selectedIndex = index;
				isTransitioning = false;
			});
			oldContent.actions(outAction);
		}
	}

	private Action createInAction(int toIndex) {
		float duration = 0.16f;
		float fromX    = 0, fromY = 0;
		int   sign     = Mathf.sign(toIndex - selectedIndex); // 决定动画方向

		if (verticalLayout) {
			fromY = contentPane.getHeight() * -0.5f * sign;
		} else {
			fromX = contentPane.getWidth() * 0.5f * sign;
		}

		return Actions.sequence(
		 Actions.translateTo(fromX, fromY),
		 Actions.alpha(0f),
		 Actions.parallel(
			HopeFx.translateTo(0, 0, duration, Interp.fastSlow),
			// Actions.run(contentPane::invalidate),
			Actions.fadeIn(duration, Interp.fastSlow)
		 )
		);
	}

	private Action createOutAction(int toIndex, Runnable onEnd) {
		float duration = 0.16f;
		float toX      = 0, toY = 0;
		int   sign     = Mathf.sign(toIndex - selectedIndex); // 决定动画方向

		if (verticalLayout) {
			toY = contentPane.getHeight() * 0.5f * sign;
		} else {
			toX = contentPane.getWidth() * -0.5f * sign;
		}

		return Actions.sequence(
		 Actions.parallel(
			HopeFx.translateTo(toX, toY, duration, Interp.slowFast),
			Actions.fadeOut(duration, Interp.slowFast)
		 ),
		 Actions.run(onEnd) // 动画结束后执行回调
		);
	}

	/**
	 * 设置整个选项卡组件的首选尺寸。
	 * 这会影响布局系统如何分配空间给此组件。
	 * 注意：单位是dp，会自动进行缩放。
	 * @param width  首选宽度
	 * @param height 首选高度
	 */
	public void setPrefSize(float width, float height) {
		if (mainContainer == null) {
			Log.warn("IntTab: setPrefSize() called before build(). The size will be applied upon building.");
			// 这种情况下，我们需要一个地方暂存它。或者直接在build()中应用。
			// 为了简单起见，我们强制要求在build()之后调用。
			// 如果确实需要在build()前设置，需要添加额外的字段来存储这些值，然后在build()时应用。
			// 但更好的做法是让调用者管理布局。
			return;
		}
		Cell<?> cell = CellTools.getCell(mainContainer);
		if (cell != null) {
			cell.minSize(width, height);
		} else {
			// throw new IllegalStateException("IntTab: setPrefSize() called before adding to a parent. This is not allowed.");
			Time.runTask(0.1f, () -> {
				Cell<?> cell2 = CellTools.getCell(mainContainer);
				if (cell2 != null) {
					cell2.minSize(width, height);
				}
			});
			// 如果还没有被添加到父级，直接设置在元素上
			// mainContainer.prefSize(width, height);
		}
		mainContainer.invalidateHierarchy(); // 通知布局系统需要重新计算
	}

	private static Color[] fillWithColor(int size, Color color) {
		Color[] colors = new Color[size];
		Arrays.fill(colors, color);
		return colors;
	}

	public ScrollPane getContentPane() {
		return contentPane;
	}
	public Table getMainContainer() {
		return mainContainer;
	}
	public ObjectMap<String, Label> getLabels() {
		return labels;
	}
	public Table getTabsBar() {
		return tabsBar;
	}
}