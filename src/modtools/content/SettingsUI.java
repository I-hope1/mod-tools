package modtools.content;

import arc.Core;
import arc.files.Fi;
import arc.func.*;
import arc.graphics.Color;
import arc.math.*;
import arc.scene.Element;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.TextField.TextFieldValidator;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.Log.LogLevel;
import arc.util.serialization.Jval;
import arc.util.serialization.Jval.JsonArray;
import mindustry.Vars;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.mod.Mods;
import mindustry.ui.Styles;
import modtools.IntVars;
import modtools.content.ui.ShowUIList.TotalLazyTable;
import modtools.events.*;
import modtools.ui.*;
import modtools.ui.TopGroup.TSettings;
import modtools.ui.comp.*;
import modtools.ui.comp.Window.DisWindow;
import modtools.ui.comp.utils.ClearValueLabel;
import modtools.ui.gen.HopeIcons;
import modtools.utils.*;
import modtools.utils.JSFunc.JColor;
import modtools.utils.MySettings.Data;
import modtools.utils.io.FileUtils;
import modtools.utils.ui.*;

import java.lang.reflect.Field;
import java.util.Objects;

import static modtools.utils.MySettings.SETTINGS;
import static modtools.utils.ui.CellTools.rowSelf;

public class SettingsUI extends Content {
	private Window ui;

	// 用于构建IntTab的数据列表
	private final Seq<String>   sectionNames  = new Seq<>();
	private final Seq<Drawable> sectionIcons  = new Seq<>();
	private final Seq<Table>    sectionTables = new Seq<>();
	private final Seq<Color>    sectionColors = new Seq<>(); // 可选：为标签页添加颜色

	@Override
	public void build() {
		if (ui == null) lazyLoad(); // 如果直接访问，确保UI已加载
		Log.info("assaasq11.build()");
		ui.show();
	}

	public SettingsUI() {
		super("settings");
		alwaysLoad = true;
	}

	/** 将加载项添加到Load部分的表格中 */
	private <T extends Content> void addLoadCheck(Table t, T contentModule) {
		t.check(contentModule.localizedName(), 28, contentModule.loadable(), contentModule::setEnabled)
		 .disabled(contentModule.alwaysLoad)
		 .update(b -> b.setChecked(contentModule.loadable()))
		 .with(b -> b.setStyle(HopeStyles.hope_defaultCheck))
		 .left().get().left();
	}

	public Seq<Runnable> customSections = new Seq<>();
	/**
	 * 准备一个设置区域，稍后添加到IntTab。
	 * @param title          区域标题 (会用于标签文本)
	 * @param icon           区域图标 (会用于标签图标)
	 * @param contentBuilder 构建区域内容的lambda (参数是内容Table)
	 */
	public void addSection(String title, Drawable icon, Cons<TotalLazyTable> contentBuilder) {
		addSection(title, icon, new TotalLazyTable(contentBuilder));
	}
	public void addSection(String title, Drawable icon, Table table) {
		customSections.add(() -> addSectionInternal(title, icon, table));
	}
	private void addSectionInternal(String title, Drawable icon, Cons<TotalLazyTable> table) {
		addSectionInternal(title, icon, new TotalLazyTable(table));
	}
	private void addSectionInternal(String title, Drawable icon, Table table) {
		sectionNames.add(title);
		sectionIcons.add(icon == null ? Styles.none : icon);
		sectionColors.add(Color.sky); // 使用默认颜色，可以自定义

		// 创建一个 TotalLazyTable 以实现内容的懒加载
		table.left().defaults().left().pad(6f); // 内容区域增加内边距
		sectionTables.add(table);
	}

	@Override
	public void lazyLoad() {
		// 初始化窗口
		ui = new IconWindow(550, 500, true);
		ui.cont.clear(); // 清除旧内容

		addSectionInternal("Load", Icon.downSmall, t -> {
			t.button("Disable All Experimental", HopeStyles.flatBordert, () -> all.forEach(c -> {
				if (c.experimental) c.disable();
			})).growX().height(42).row();
			t.button("Disable All", HopeStyles.flatBordert, () -> {
				IntUI.showConfirm("Disable All", "Are you sure to disable all modules?", () -> {
					all.forEach(Content::disable);
				});
			}).growX().height(42).row();
			// 在这里构建“加载”区域的内容
			// addLoadCheck 会直接将复选框添加到 t (即 TotalLazyTable 的内部 Table)
			// 两列
			t.table(Tex.pane, t2 -> {
				t2.left().defaults().growX().left().pad(2);

				final int cols = 2;
				int       c    = 0;
				for (Content contentModule : all) {
					if (!contentModule.alwaysLoad) {
						addLoadCheck(t2, contentModule);
						if (++c % cols == 0) t2.row();
					}
				}
			}).left().growX();
		});

		customSections.each(Runnable::run);

		addSectionInternal("JSFunc", Icon.logicSmall, t -> {
			SettingsBuilder.build(t);
			JColor.settingColor(t);
			SettingsBuilder.clearBuild();
		});

		addSectionInternal("Hook", Icon.refreshSmall, t -> {
			SettingsBuilder.build(t);
			// watch的路径数组配置
			ISettings.buildAll("hook", t, E_Hook.class);
		});

		addSectionInternal("UI", Icon.imageSmall, t -> {
			SettingsBuilder.build(t);

			ISettings.buildAll("ui", t, E_UISettings.class);

			// 构建各种设置
			ISettings.buildAll("", t, TSettings.class);
			addElemValueLabel(t, "Bound Element",
			 TopGroup::getDrawPadElem,
			 () -> TopGroup.setDrawPadElem(null),
			 TopGroup::setDrawPadElem,
			 TSettings.debugBounds::enabled);
			ISettings.buildAll("", t, E_Game.class);
			ISettings.buildAll("frag", t, Frag.Settings.class);

			SettingsBuilder.build(t);
			ISettings.buildAll("blur", t, E_Blur.class);
			SettingsBuilder.clearBuild();

			// 字体选择按钮
			t.button("@settings.font", HopeStyles.flatBordert, this::showFontDialog)
			 .growX().height(42).row();
		});

		addSectionInternal("@mod-tools.others", Icon.listSmall, t -> {
			SettingsBuilder.build(t); // 设置SettingsBuilder的目标表格

			// 主菜单背景切换
			String key = "ShowMainMenuBackground";
			SettingsBuilder.check("@settings.mainmenubackground", b -> SETTINGS.put(key, b), () -> SETTINGS.getBool(key));

			ISettings.buildAll("", t, E_Extending.class);

			// 功能按钮区域
			t.table(Tex.pane, ft -> {
				ft.defaults().growX().height(42).pad(2);
				ft.add("@mod-tools.functions").color(Pal.accent).center().row();
				ft.button("@settings.cancelmodsrestart", Icon.cancelSmall, HopeStyles.flatt, SettingsUI::disabledRestart).row();
				if (IntVars.isDesktop()) {
					ft.button("@settings.switchlanguage", Icon.chatSmall, HopeStyles.flatt, () -> {
						IntVars.async("Switching Language...", LanguageSwitcher::switchLanguage, () -> IntUI.showInfoFade("Language switched!"));
					}).row();
				}
				ft.button("@settings.enabledebuglevel", Icon.terminalSmall, HopeStyles.flatt, () -> {
					Log.level = LogLevel.debug;
					IntUI.showInfoFade("Debug log level enabled.");
				});
			}).growX().padTop(8f).row();

			// 关于按钮
			t.button("@about", Icon.infoCircleSmall, HopeStyles.flatBordert, this::showAboutDialog)
			 .height(42).growX().padTop(8f);

			SettingsBuilder.clearBuild(); // 清理SettingsBuilder的目标
		});

		IntTab tab = new IntTab(CellTools.unset, // 左侧标签栏的宽度 (titleWidth)
		 sectionNames.toArray(String.class),
		 sectionColors.toArray(Color.class),
		 sectionTables.toArray(Table.class),
		 1, // 列数 (垂直布局为1列)
		 true); // column = true 表示垂直布局

		// tab.labelWidth = 100f;
		tab.setIcons(sectionIcons.toArray(Drawable.class)); // 设置标签图标

		// 将 IntTab 添加到窗口内容区
		ui.cont.add(tab.build()).grow();

		// 清理临时列表
		sectionNames.clear();
		sectionIcons.clear();
		sectionTables.clear();
		sectionColors.clear();
	}

	// --- 辅助方法保持不变 ---

	/** 显示字体选择对话框 */
	private void showFontDialog() {
		new DisWindow("@settings.font", 220, 400) {{
			cont.top().defaults().top().growX();
			// 默认字体按钮
			cont.button(MyFonts.DEFAULT, HopeStyles.flatToggleMenut, () -> setFont(MyFonts.DEFAULT))
			 .height(42).checked(_ -> MyFonts.DEFAULT.equals(getSelectedFont())).row();
			cont.image().color(Color.gray).growX().pad(6f, 0, 6f, 0).row();

			// 自定义字体列表
			Seq<Fi> fontFiles = MyFonts.fontDirectory.findAll(fi -> fi.extEquals("ttf"));
			if (fontFiles.isEmpty()) {
				cont.add("@settings.font.nofonts").color(Color.gray).pad(10f).row();
			} else {
				// 使用ScrollPane显示可能很长的字体列表
				ScrollPane fontsPane = new ScrollPane(new Table(fontTable -> {
					fontTable.top().defaults().top().growX();
					for (Fi fi : fontFiles) {
						fontTable.button(fi.nameWithoutExtension(), Styles.flatToggleMenut, () -> setFont(fi.name()))
						 .height(42).checked(_ -> fi.name().equals(getSelectedFont())).row();
					}
				}), Styles.smallPane);
				fontsPane.setFadeScrollBars(false); // 始终显示滚动条
				fontsPane.setOverscroll(false, false); // 禁用过度滚动效果
				cont.add(fontsPane).growY().maxHeight(200f).row(); // 限制最大高度并允许垂直滚动
			}

			Underline.of(cont, 1);
			// 打开字体目录按钮
			cont.button("@settings.font.opendir", Icon.folderSmall, HopeStyles.flatBordert, () -> FileUtils.openFile(MyFonts.fontDirectory))
			 .growX().height(45);
			show();
		}};
	}

	/** 在设置中设置选定的字体 */
	private void setFont(String fontName) {
		SETTINGS.put("font", fontName);
		IntUI.showInfoFade("Font set. Restart might be needed.");
		// 如果需要立即生效，可以在这里强制重新加载字体或UI
	}

	/** 从设置中获取当前选定的字体名称 */
	private String getSelectedFont() {
		return SETTINGS.getString("font", MyFonts.DEFAULT);
	}

	/** 显示关于对话框 */
	private void showAboutDialog() {
		new DisWindow("@about", 220, 160) {{
			Table content = this.cont;
			content.left().defaults().left().pad(4f);
			float buttonHeight = 42f;
			content.add("Author").color(Pal.accent);
			content.add(IntVars.meta.author).row();
			content.add("Version").color(Pal.accent);
			content.add(IntVars.meta.version).row();
			content.button("GitHub", Icon.githubSmall, HopeStyles.flatt,
				() -> Core.app.openURI("https://github.com/" + IntVars.meta.repo))
			 .growX().height(buttonHeight).colspan(2).row();
			content.button("QQ Group", HopeIcons.QQ, HopeStyles.flatt,
				() -> Core.app.openURI(IntVars.QQ))
			 .growX().height(buttonHeight).colspan(2).row();
			show();
		}};
	}

	/** 在Mindustry模组系统中禁用“需要重启”标志 */
	public static void disabledRestart() {
		try {
			Reflect.set(Mods.class, Vars.mods, "requiresReload", false);
			IntUI.showInfoFade("Restart flag cleared.");
		} catch (Exception e) {
			Log.err("Failed to disable restart flag", e);
			IntUI.showException("Error clearing restart flag", e);
		}
	}

	/** 添加一个带有值显示标签的标签，该值可以清除或设置 */
	public static void addElemValueLabel(
	 Table table, String text, Prov<Element> prov,
	 Runnable clear, Cons<Element> setter,
	 Boolp condition) {
		Objects.requireNonNull(table, "table cannot be null");
		Objects.requireNonNull(text, "text cannot be null");
		Objects.requireNonNull(prov, "prov cannot be null");
		Objects.requireNonNull(clear, "clear cannot be null");
		Objects.requireNonNull(setter, "setter cannot be null");
		Objects.requireNonNull(condition, "condition cannot be null");
		var valueLabel = new ClearValueLabel<>(Element.class, prov, clear, setter);
		valueLabel.setAlignment(Align.right);
		Label keyLabel = new Label(text);
		table.stack(keyLabel, valueLabel)
		 .update(stack -> {
			 Color color = condition.get() ? Color.white : Color.gray;
			 keyLabel.setColor(color);
			 valueLabel.setColor(color);
			 // 动态更新值
			 try {
				 valueLabel.setVal(prov.get());
			 } catch (Exception e) {
				 Log.err("Error updating value label for " + text, e);
				 valueLabel.setText("[#ff4444]Error");
			 }
		 }).growX().padBottom(2f).row();
	}

	/** 添加一个带有标签和颜色选择器按钮的颜色设置块 */
	public static Color colorBlock(
	 Table table, String text,
	 Data data, String key, int defaultColor,
	 Cons<Color> colorCons) {
		Objects.requireNonNull(table, "table cannot be null");
		Objects.requireNonNull(text, "text cannot be null");
		Color color = new Color(
		 (data == null || key == null) ? defaultColor : data.get0xInt(key, defaultColor)
		) {
			@Override
			public Color set(Color other) {
				if (this.equals(other)) return this;
				if (data != null && key != null) {
					data.putString(key, other);
				}
				super.set(other);
				if (colorCons != null) {
					colorCons.get(this);
				}
				return this;
			}
		};
		Label label = table.add(text).growY().padRight(4f).left().get();
		EventHelper.doubleClick(label, null, () -> color.set(Tmp.c2.set(defaultColor)));
		EventHelper.rightClick(label, () -> color.set(Tmp.c2.set(defaultColor)));
		ColorBlock.of(table.add().right().growX(), color, false);
		table.row();
		return color;
	}

	/** 添加一个链接到 E_DataInterface 枚举设置的复选框 */
	public static Cell<CheckBox> checkboxWithEnum(Table t, String text, E_DataInterface enum_) {
		Objects.requireNonNull(t, "table cannot be null");
		Objects.requireNonNull(text, "text cannot be null");
		Objects.requireNonNull(enum_, "enum_ cannot be null");
		return t.check(text, 28, enum_.enabled(), enum_::set)
		 .with(cb -> cb.setStyle(HopeStyles.hope_defaultCheck));
	}

	/** 工具提示资源束键的前缀 */
	public static final String TIP_PREFIX = "settings.tip.";

	/** 如果捆绑包中存在相应的键，则尝试向元素添加工具提示 */
	public static void tryAddTip(Element element, String tipKey) {
		Objects.requireNonNull(element, "element cannot be null");
		Objects.requireNonNull(tipKey, "tipKey cannot be null");
		String fullTipKey = TIP_PREFIX + tipKey;
		if (Core.bundle != null && Core.bundle.has(fullTipKey)) {
			IntUI.addTooltipListener(element,
			 Tools.provT(() -> FormatHelper.parseVars(Core.bundle.get(fullTipKey)))
			);
		}
	}

	// --- SettingsBuilder 保持不变 ---
	public static class SettingsBuilder {
		private static Table main;
		public static Table main() { return main; }
		public static void build(Table mainTable) {
			main = Objects.requireNonNull(mainTable, "main table cannot be null");
			main.left().defaults().left();
		}
		public static void clearBuild() { main = null; }
		public static <T> Cell<Table> list(String text, Cons<T> cons, Prov<T> prov, Seq<T> list,
		                                   Func<T, String> stringify) {
			return list(text, cons, prov, list, stringify, () -> true);
		}
		public static Cell<Table> list(String prefix, String key, Data data, Seq<String> list,
		                               Func<String, String> stringify) {
			Objects.requireNonNull(data, "data cannot be null");
			Objects.requireNonNull(key, "key cannot be null");
			Objects.requireNonNull(list, "list cannot be null");
			if (list.isEmpty()) throw new IllegalArgumentException("List cannot be empty");
			String localizedText = "@" + prefix + "." + key.toLowerCase();
			return list(localizedText, v -> data.put(key, v), () -> data.getString(key, list.get(0)), list, stringify, () -> true);
		}
		public static <T> Cell<Table> list(String text, Cons<T> cons, Prov<T> prov, Seq<T> list, Func<T, String> stringify,
		                                   Boolp condition) {
			Table t = new Table();
			t.right();
			t.add(text).left().padRight(10).growX().labelAlign(Align.left).update(a -> a.setColor(condition.get() ? Color.white : Color.gray));
			t.button(b -> {
				b.margin(0, 8f, 0, 8f);
				b.add("").grow().labelAlign(Align.right).update(l -> {
					l.setText(stringify.get(prov.get()));
					l.setColor(condition.get() ? Color.white : Color.gray);
				});
				b.clicked(() -> {
					if (condition.get()) IntUI.showSelectListTable(b, list, prov, cons, stringify, 100, 42, true, Align.left);
				});
				b.setDisabled(() -> !condition.get());
				tryAddTip(b, text.substring(text.indexOf('.') + 1));
			}, HopeStyles.hope_defaultb, IntVars.EMPTY_RUN).minWidth(64).height(42).self(c -> c.update(b -> c.width(Mathf.clamp(b.getPrefWidth() / Scl.scl(), 64, 220))));
			return rowSelf(main.add(t).growX().padTop(0));
		}
		/**
		 * 添加一个用于编辑字符串数组的UI组件。
		 * 数组在Data中以换行符分隔的字符串形式存储。
		 * @param text      标题标签的文本 (可以是本地化键)
		 * @param data      设置数据实例
		 * @param key       存储数组的键
		 * @param condition 控制该组件是否启用的条件
		 * @return 添加到主设置表格的单元格
		 */
		public static Cell<Table> array(String text, Data data, String key, Boolp condition) {
			Table container = new Table();
			container.left();

			Label titleLabel = new Label(text);
			container.add(titleLabel).left().padBottom(4f).row();
			tryAddTip(titleLabel, text.substring(text.indexOf('.') + 1));

			Table listTable = new Table(Tex.paneLeft);
			listTable.left().defaults().padBottom(2f);

			// 用于重建列表的函数
			Runnable rebuildList = new Runnable() {
				public void run() {
					listTable.clear();
					// 从数据加载，按换行符分割，并移除空行
					JsonArray items = data.getArray(key);

					if (items == null || items.isEmpty()) {
						listTable.add(Core.bundle.get("settings.array.empty", "(Empty)"))
						 .color(Color.gray).left();
					} else {
						for (int i = 0; i < items.size; i++) {
							final int index = i;
							String    item  = items.get(index).asString();
							Table     row   = new Table();
							row.left();
							row.add(item).growX().wrap().width(300).left();
							// 移除按钮
							row.button(Icon.trashSmall, Styles.emptyi, () -> {
								data.removeArrayIndex(key, index);
								this.run();
							}).size(40f).padLeft(8f).disabled(b -> !condition.get());
							listTable.add(row).growX().left().row();
						}
					}
				}
			};

			// 列表的滚动窗格
			ScrollPane listPane = new ScrollPane(listTable, Styles.smallPane);
			listPane.setFadeScrollBars(false);
			listPane.setOverscroll(false, false);
			container.add(listPane).growX().maxHeight(160f).left().row();

			// 添加新项目的输入区域
			Table addTable = new Table();
			addTable.left();
			TextField inputField = new TextField();
			addTable.add(inputField).growX().height(42f);

			// 添加按钮
			addTable.button(Icon.addSmall, Styles.emptyi, () -> {
				String newItem = inputField.getText();
				if (newItem != null && !newItem.isBlank()) {
					JsonArray items = data.getArray(key);
					items.add(Jval.valueOf(newItem.trim()));
					data.fireChanged(key);
					inputField.setText("");
				}
			}).size(42f);
			container.add(addTable).growX().padTop(4f).row();

			// 当数据改变时重建UI
			data.onChange(key, rebuildList);
			rebuildList.run(); // 初始构建

			// 根据条件更新整个组件的颜色和禁用状态
			container.update(() -> {
				boolean enabled = condition.get();
				container.touchablility = () -> enabled ? Touchable.enabled : Touchable.disabled;
				Color color = enabled ? Color.white : Color.gray;
				titleLabel.setColor(color);
				// 可以在此处为其他组件设置颜色
			});

			return rowSelf(main.add(container).growX().padTop(8f));
		}
		public static void number(String text, Floatc cons,
		                          Floatp prov) { number(text, false, cons, prov, () -> true, 0, Float.MAX_VALUE); }
		public static void number(String text, Floatc cons, Floatp prov, float min,
		                          float max) { number(text, false, cons, prov, () -> true, min, max); }
		public static void number(String text, boolean integer, Floatc cons, Floatp prov,
		                          Boolp condition) { number(text, integer, cons, prov, condition, 0, Float.MAX_VALUE); }
		public static void number(String text, Floatc cons, Floatp prov,
		                          Boolp condition) { number(text, false, cons, prov, condition, 0, Float.MAX_VALUE); }
		public static void numberi(String text, Intc cons, Intp prov, int min,
		                           int max) { numberi(text, cons, prov, () -> true, min, max); }
		public static void numberi(String text, Intc cons, Intp prov, Boolp condition, int min, int max) {
			main.table(t -> {
				t.left();
				t.add(text).left().padRight(5).update(a -> a.setColor(condition.get() ? Color.white : Color.gray));
				t.field(String.valueOf(prov.get()), s -> cons.get(Strings.parseInt(s))).update(a -> a.setDisabled(!condition.get())).padRight(100f).valid(f -> {
					int i = Strings.parseInt(f);
					return i >= min && i <= max;
				}).width(120f).left();
			}).padTop(0).row();
		}
		public static void numberi(String text, Data data, String key, int defaultValue, Boolp condition, int min,
		                           int max) {
			if (defaultValue < min || defaultValue > max) {
				throw new IllegalArgumentException(StringTemplate.STR."defaultValue(\{defaultValue}) must be in (\{min}, \{max})");
			}
			numberi(text, val -> data.put(key, val), () -> data.getInt(key, defaultValue), condition, min, max);
		}
		public static void number(String text, boolean integer, Floatc cons, Floatp prov, Boolp condition, float min,
		                          float max) {
			main.table(t -> {
				t.left();
				t.add(text).left().padRight(5).update(a -> a.setColor(condition.get() ? Color.white : Color.gray));
				String val = integer ? String.valueOf((int) prov.get()) : FormatHelper.fixed(prov.get(), 2);
				t.field(val, s -> cons.get(NumberHelper.asFloat(s))).padRight(100f).update(a -> a.setDisabled(!condition.get())).valid(f -> NumberHelper.isFloat(f) && NumberHelper.asFloat(f) >= min && NumberHelper.asFloat(f) <= max).width(120f).left();
			}).padTop(0);
			main.row();
		}
		public static void number(String text, Data data, String key, float defaultValue, Boolp condition, float min,
		                          float max) {
			if (defaultValue < min || defaultValue > max) {
				throw new IllegalArgumentException(StringTemplate.STR."defaultValue must be between \{min} and \{max}. Current value: \{defaultValue}");
			}
			number(text, false, val -> data.put(key, val), () -> data.getFloat(key, defaultValue), condition, min, max);
		}
		public static void check(String text, Boolc cons, Boolp prov) { check(text, cons, prov, null); }
		public static void check(String text, Data data, String key) { check(text, data, key, () -> true); }
		public static void check(String text, Data data, String key,
		                         Boolp condition) { check(text, data, key, false, condition); }
		public static void check(String text, Data data, String key, boolean defaultValue,
		                         Boolp condition) { check(text, val -> data.put(key, val), () -> data.getBool(key, defaultValue), condition); }
		public static void check(String text, Boolc cons, Boolp prov, Boolp condition) {
			CheckBox checkBox = main.check(text, cons).update(a -> {
				a.setChecked(prov.get());
				if (condition != null) a.setDisabled(!condition.get());
			}).padLeft(10f).get();
			checkBox.setStyle(HopeStyles.hope_defaultCheck);
			checkBox.left();
			tryAddTip(checkBox, text.substring(text.indexOf('.') + 1));
			main.row();
		}
		public static void title(String text) {
			main.add(text).color(Pal.accent).padTop(20).padRight(100f).padBottom(-3);
			main.row();
			main.image().color(Pal.accent).height(3f).padRight(100f).padBottom(20);
			main.row();
		}
		public static <T extends Enum<T>> void enum_(String text, Class<T> enumClass, Cons<Enum<T>> cons,
		                                             Prov<Enum<T>> prov, Boolp condition) {
			var enums = new Seq<>((Enum<T>[]) enumClass.getEnumConstants());
			list(text, cons, prov, enums, Enum::name, condition);
		}
		public static void field(String text, float value, Floatc setter) {
			field(text, value, setter, () -> true);
		}
		public static void field(String text, float value, Floatc setter, Boolp condition) {
			main.table(t -> {
				t.add(text).left().padRight(10).growX().labelAlign(Align.left).update(a -> a.setColor(condition.get() ? Color.white : Color.gray));
				t.field(Strings.autoFixed(value, 2), v -> setter.get(NumberHelper.asFloat(v)))
				 .valid(Strings::canParsePositiveFloat)
				 .size(90f, 40f).pad(2f);
			}).padTop(0).row();
		}
		public static void field(String text, int value, Intc setter) {
			field(text, value, setter, Integer.MIN_VALUE, Integer.MAX_VALUE, () -> true);
		}
		public static void field(String text, int value, Intc setter, int min, int max) {
			field(text, value, setter, min, max, () -> true);
		}

		public static void field(String text, int value, Intc setter, int min, int max,Boolp condition) {
			main.table(t -> {
				t.add(text).left().padRight(10).growX().labelAlign(Align.left).update(a -> a.setColor(condition.get() ? Color.white : Color.gray));
				t.field(String.valueOf(value), v -> setter.get(Strings.parseInt(v)))
				 .valid(f -> Strings.canParseInt(f) && Strings.parseInt(f) >= min && Strings.parseInt(f) <= max)
				 .size(90f, 40f).pad(2f);
			}).padTop(0).row();
		}
		public static void field(String text, String value, TextFieldValidator validator, Cons<String> setter) {
			field(text, value, validator, setter, () -> true);
		}
		public static void field(String name, String value, TextFieldValidator validator, Cons<String> setter,Boolp condition) {
			main.table(t -> {
				t.add(name).left().padRight(10).growX().labelAlign(Align.left).update(a -> a.setColor(condition.get() ? Color.white : Color.gray));
				t.field(value, setter)
				 .valid(validator).size(90f, 40f).pad(2f);
			}).padTop(0).row();
		}
		public static void color(String text, Color defaultColor,
		                         Cons<Color> colorSet) { colorBlock(main, text, null, null, defaultColor.rgba(), colorSet); }
		public static void interpolator(String name, Cons<Interp> cons, Prov<Interp> prov) {
			Seq<Field>               seq = Seq.with(Interp.class.getFields());
			ObjectMap<Interp, Field> map = seq.asMap(Reflect::get, f -> f);
			list(name, f -> cons.get(Reflect.get(f)), () -> map.get(prov.get()), seq, Field::getName);
		}
	}
}