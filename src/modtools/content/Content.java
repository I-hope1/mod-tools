
package modtools.content;

import arc.Core;
import arc.func.Boolp;
import arc.graphics.Color;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.Button.ButtonStyle;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.util.*;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import modtools.ui.HopeStyles;
import modtools.ui.comp.Window;
import modtools.ui.comp.buttons.CircleImageButton;
import modtools.ui.control.HKeyCode;
import modtools.ui.control.HKeyCode.KeyCodeData;
import modtools.utils.MySettings.Data;
import modtools.utils.Tools;

import java.util.ArrayList;

import static modtools.IntVars.modName;
import static modtools.utils.MySettings.SETTINGS;

public abstract class Content implements Disposable {
	public static final ArrayList<Content> all = new ArrayList<>();

	public final Drawable icon;
	public final String   name;
	public       Runnable update;


	/** 显示名称 */
	public String localizedName() {
		return Core.bundle.get(modName + "." + name, Strings.capitalize(name));
	}
	public final String getSettingName() {
		return name;
	}

	protected boolean experimental = false;
	protected boolean alwaysLoad = false, defLoadable = true;
	public final boolean loadable() {
		return alwaysLoad || SETTINGS.getBool("load-" + name, defLoadable);
	}
	public Content(String name) {
		this(name, null);
	}
	public Content(String name, Drawable icon) {
		this.name = name;

		if (icon == null) try {
			icon = Reflect.get(Icon.class, name + "Small");
		} catch (Throwable ignored) {
			icon = Styles.none;
		}
		this.icon = icon;
		all.add(this);
	}

	public final void loadSettings() {
		loadSettings(data());
	}
	public void loadSettings(Data SETTINGS) {
	}

	private Data data;
	/** 设置 */
	public final Data data() {
		return data == null ? data = SETTINGS.child(getSettingName()) : data;
	}
	/** 加载 */
	public void load() {
	}

	private boolean lazyLoaded = false;
	/** 当build构建前，才加载 */
	public void lazyLoad() { }

	/** 点击按钮触发的事件 */
	public void build() {
	}
	public final void build0() {
		if (!lazyLoaded) {
			lazyLoad();
			lazyLoaded = true;
		}
		build();
	}

	public String toString() {
		return "Content#" + name;
	}

	public Button buildButton(boolean isSmallized) {
		return buildButton(isSmallized, null);
	}
	public final Button buildButton(boolean isSmallized, Boolp checked) {
		Button btn = makeButton(isSmallized,
		 checked == null ?
			/* un-toggle */isSmallized ? HopeStyles.hope_flati : HopeStyles.cleart
			:/* toggle */ isSmallized ? HopeStyles.hope_flatTogglei : HopeStyles.flatTogglet);
		btn.clicked(() -> Tools.runShowedException(this::build0));
		if (checked != null) {
			btn.update(() -> btn.setChecked(checked.get()));
		}
		return btn;
	}
	public final String tipKey(String key) {
		return Core.bundle.get(STR."\{modName}.tips.\{name}.\{key}");
	}
	public final String tipKey(String key, String arg1) {
		return Core.bundle.format(STR."\{modName}.tips.\{name}.\{key}", arg1);
	}
	public KeyCodeData keyCodeData() {
		return HKeyCode.data.child(name);
	}
	private Button makeButton(boolean isSmallized, ButtonStyle style) {
		if (isSmallized) {
			Button btn = new CircleImageButton(icon, (ImageButtonStyle) style);
			btn.setTransform(true);
			return btn;
		}
		String     localizedName = localizedName();
		TextButton button        = new TextButton(localizedName, (TextButtonStyle) style);
		button.add(new Image(icon)).size(icon == Styles.none ? 0 : 20).padRight(8f);
		button.getCells().reverse();
		button.update(() -> button.setColor(button.isDisabled() ? Color.gray : Color.white));
		button.getLabel().setSize(0.9f);
		button.getLabel().setAlignment(Align.left);
		return button;
	}
	protected void disable() {
		setEnabled(false);
	}
	protected void setEnabled(boolean b) {
		SETTINGS.put("load-" + name, b);
	}

	public void dispose() {
	}
	public class IconWindow extends Window {
		public IconWindow(float minWidth, float minHeight, boolean full, boolean noButtons) {
			super(localizedName(), minWidth, minHeight, full, noButtons);
		}
		public IconWindow(float minWidth, float minHeight, boolean full) {
			super(localizedName(), minWidth, minHeight, full);
		}
		public IconWindow(float minWidth, float minHeight) {
			super(localizedName(), minWidth, minHeight);
		}
		public IconWindow() {
			super(localizedName());
		}
		public void buildTitle(String titleText) {
			titleTable.image(icon).padLeft(6f).padRight(2f);
			super.buildTitle(titleText);
		}
	}
}
