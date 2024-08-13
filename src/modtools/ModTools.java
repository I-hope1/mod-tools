package modtools;


import arc.*;
import arc.files.Fi;
import arc.struct.*;
import arc.util.*;
import arc.util.io.PropertiesUtils;
import ihope_lib.MyReflect;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.mod.*;
import mindustry.mod.Mods.ModMeta;
import modtools.android.HiddenApi;
import modtools.content.SettingsUI;
import modtools.content.debug.Tester;
import modtools.events.*;
import modtools.files.HFi;
import modtools.graphics.MyShaders;
import modtools.net.packet.HopeCall;
import modtools.ui.*;
import modtools.ui.control.HopeInput;
import modtools.ui.gen.HopeIcons;
import modtools.ui.tutorial.AllTutorial;
import modtools.utils.*;
import modtools.utils.io.FileUtils;
import modtools.utils.ui.DropFile;
import modtools.utils.world.WorldDraw;

import java.util.Arrays;

import static mindustry.Vars.*;
import static modtools.IntVars.*;
import static modtools.utils.MySettings.SETTINGS;

public class ModTools extends Mod {
	/** 如果不为empty，在进入是显示 */
	private static final Seq<Throwable> errors = new Seq<>();
	private static final Fi             libs   = root.child("libs");

	/** 是否从游戏内导入进来的 */
	private static boolean isImportFromGame = false;
	public static  boolean isV6             = Version.number <= 135;


	public static boolean loaded = false;
	public ModTools() {
		if (loaded) throw new IllegalStateException("ModTools already loaded.");

		if (ui != null && ui.hudGroup != null) {
			isImportFromGame = true;
		}
		Log.info("Loaded ModTools constructor@.", (isImportFromGame ? " [[[from game]]]" : ""));
		if (headless) Log.info("Running in headless environment.");

		Core.app.post(this::load0);
	}
	public void load0() {
		try {
			ObjectMap<Class<?>, ModMeta> metas = Reflect.get(Mods.class, Vars.mods, "metas");
			IntVars.meta = metas.get(ModTools.class);
			load();
			if (isImportFromGame && SETTINGS.getBool("SDIFG", true)) {
				ui.showCustomConfirm("@mod-tools.close_modrestart", "@mod-tools.close_modrestart_text",
				 "@mod-tools.close_modrestart_yes", "@mod-tools.close_modrestart_no",
				 SettingsUI::disabledRestart, EMPTY_RUN);
			}
		} catch (Throwable e) {
			if (isImportFromGame) ui.showException("Failed to load ModTools. (Don't worry.)", e);
			Log.err("Failed to load ModTools.", e);
		}
	}

	private void extending() {
		if (E_Extending.http_redirect.enabled()) {
			Tools.runLoggedException(URLRedirect::load);
		}
	}

	private void load() {
		if (!isImportFromGame) IntVars.meta.hidden = false;
		resolveLibsCatch();

		try {
			if (OS.isAndroid) HiddenApi.setHiddenApiExemptions();
		} catch (Throwable e) {
			/* Log.err(e);
			System.exit(-1); */
		}

		WorldDraw.registerEvent();
		HopeCall.registerPacket();

		if (isImportFromGame) {
			// loadContent();
			loadInputAndUI();
		} else Events.on(ClientLoadEvent.class,
		 _ -> Tools.runLoggedException(this::loadInputAndUI));
	}


	public void loadContent() {
		IntVars.meta.hidden = true;
		// Time.mark();
		Tools.runIgnoredException(Tester::initExecution);

		extending();

		// Log.info("Initialized Execution in @ms", Time.elapsed());
	}
	private static void resolveLibsCatch() {
		try {
			loadLibs();
		} catch (Throwable e) {
			Log.err(e);
			if (e instanceof UnexpectedPlatform)
				Log.err("It seems you platform is special. (But don't worry.)");
			planB_resolveLibs();
		}
	}
	private static void planB_resolveLibs() {
		Tools.forceRun(() -> {
			if (Vars.mods.getMod(ModTools.class) == null) return false;
			loadLibs();
			return true;
		});
	}
	private static void loadLibs() {
		//noinspection Convert2MethodRef
		loadLib("reflect-core", "ihope_lib.MyReflect", true, () -> MyReflect.load());
		IntVars.hasDecompiler = loadLib("procyon-0.6", "com.strobel.decompiler.Decompiler", false);
		if (isImportFromGame) loadBundle();
	}
	private void loadInputAndUI() {
		if (ui == null) return;
		mod = mods.getMod(modName);
		Time.mark();

		IntVars.load();
		if (errors.any()) {
			errors.each(e -> ui.showException(e));
			return;
		}
		// 加载HopeIcons
		HopeIcons.modName = modName;
		load("HopeIcons", HopeIcons::load);
		load("MyShaders", MyShaders::load);
		load("MyFonts", MyFonts::load);
		load("HopeInput", HopeInput::load);
		if (isDesktop()) {
			load("DropMod", DropFile::load);
		}
		load("IntUI", IntUI::load);

		load("Updater", Updater::checkUpdate);
		loaded = true;
		IntVars.async(() -> {
			AllTutorial.init();
			if (SETTINGS.getBool("ShowMainMenuBackground")) {
				Core.app.post(() -> Tools.runIgnoredException(Background::load));
			}
			Tools.TASKS.add(() -> {
				if (mods.getMod(ModTools.class) == null) {
					disposeAll();
				}
			});
		}, () -> Log.info("Loaded ModTools input and ui in @ms", Time.elapsed()));
	}

	public static void load(String moduleName, Runnable r) {
		Tools.runLoggedException(moduleName, r::run);
	}

	/**
	 * @see Mods#buildFiles()
	 */
	public static void loadBundle() {
		if (root instanceof HFi && mods.getMod(modName) == null) return;
		root = mods.getMod(modName).root;

		ObjectMap<String, Seq<Fi>> bundles;
		//load up bundles.
		Fi folder = root.child("bundles");
		if (!folder.exists()) return;

		Fi[] files = Arrays.stream(folder.list()).filter(file ->
			file.name().startsWith("bundle") && file.extension().equals("properties"))
		 .toArray(Fi[]::new);
		bundles = new ObjectMap<>(files.length);
		for (Fi file : files) {
			String name = file.nameWithoutExtension();
			bundles.get(name, Seq::new).add(file);
		}

		I18NBundle bundle = Core.bundle;
		while (bundle != null) {
			String str    = bundle.getLocale().toString();
			String locale = "bundle" + (str.isEmpty() ? "" : "_" + str);
			if (bundles.containsKey(locale)) for (Fi file : bundles.get(locale)) {
				try {
					PropertiesUtils.load(bundle.getProperties(), file.reader());
				} catch (Throwable e) {
					Log.err(STR."Error loading bundle: \{file}/\{locale}", e);
				}
			}
			bundle = bundle.getParent();
		}
	}

	public static ClassLoader lastLoader;
	public static boolean loadLib(String fileName, String mainClassName, boolean showError) {
		return loadLib(fileName, mainClassName, showError, null);
	}
	public static boolean loadLib(String fileName, String mainClassName, boolean showError,
	                              Runnable callback) {
		try {
			// 没报错的话，证明已经加载
			IntVars.mainLoader.loadClass(mainClassName);
			return true;
		} catch (Exception ignored) { }

		Fi sourceFi = libs.child(fileName + ".jar");
		if (!sourceFi.exists()) return false;

		Log.info("Loading @.jar", fileName);
		Time.mark();
		// 加载前置
		try {
			Fi toFi = Vars.dataDirectory.child(STR."tmp/mod-tools-\{fileName}.jar");
			FileUtils.delete(toFi);
			sourceFi.copyTo(toFi);
			ClassLoader loader = Vars.platform.loadJar(toFi, IntVars.mainLoader);
			IntVars.mainLoader.addChild(loader);
			Class.forName(mainClassName, true, loader);
			lastLoader = loader;
			if (callback != null) callback.run();
			toFi.delete();
			return true;
		} catch (Throwable e) {
			if (showError) {
				errors.add(e);
				Log.err(STR."Unexpected exception when loading '\{sourceFi}'", e);
			}
			return false;
		} finally {
			Log.info("Loaded '@' in @ms", sourceFi.name(), Time.elapsed());
		}
	}


	private static boolean isDisposed = false;
	public static void disposeAll() {
		if (isDisposed) return;
		isDisposed = true;
		Tools.TASKS.clear();
		WorldDraw.tasks.clear();
		IntUI.disposeAll();
		HopeInput.dispose();
		IntVars.dispose();
		MyEvents.dispose();
		MyFonts.dispose();
		ModClassLoader   loader   = (ModClassLoader) mods.mainLoader();
		Seq<ClassLoader> children = Reflect.get(ModClassLoader.class, loader, "children");
		children.remove(ModTools.class.getClassLoader());
		System.gc();
		Core.app.post(() -> mods.removeMod(mod));
	}
	public static boolean isDisposed() {
		return isDisposed;
	}

	static class UnexpectedPlatform extends RuntimeException { }
}