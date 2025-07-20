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
import modtools.extending.*;
import modtools.graphics.MyShaders;
import modtools.net.packet.HopeCall;
import modtools.struct.TaskSet;
import modtools.ui.*;
import modtools.ui.comp.utils.Viewers;
import modtools.ui.control.HopeInput;
import modtools.ui.effect.ScreenSampler;
import modtools.ui.gen.HopeIcons;
import modtools.ui.tutorial.AllTutorial;
import modtools.unsupported.HopeProcessor.MyContentParser;
import modtools.utils.*;
import modtools.utils.files.HFi;
import modtools.utils.io.FileUtils;
import modtools.utils.ui.DropFile;
import modtools.utils.world.WorldDraw;
import sun.reflect.ReflectionFactory;

import java.lang.invoke.MethodHandles.Lookup;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;

import static mindustry.Vars.*;
import static modtools.IntVars.*;
import static modtools.utils.MySettings.SETTINGS;

public class ModTools extends Mod {
	public static final boolean TEST = false;

	/** 如果不为empty，在进入是显示 */
	private static final Fi             libs   = root.child("libs");
	/** Stores errors encountered during library loading. */
	private static final Seq<Throwable> errors = new Seq<>();
	public static        boolean        isV6   = Version.number <= 135;

	/** 是否从游戏内导入进来的 */
	private static boolean isImportFromGame = false;
	public static final boolean DISABLE_UI = false;


	public static boolean loaded = false;
	public ModTools() {
		if (loaded) throw new IllegalStateException("ModTools already loaded.");

		ScreenSampler.resetMark();

		if (ui != null && ui.hudGroup != null) {
			isImportFromGame = true;
		}
		// HopeProcessor.main();
		Log.info("Loaded ModTools constructor@.", (isImportFromGame ? " [[[from game]]]" : ""));
		if (headless) Log.info("Running in headless environment.");

		// Defer loading to the main thread to ensure proper initialization order
		Core.app.post(this::load0);
	}
	public void load0() {
		try {
			ObjectMap<Class<?>, ModMeta> metas = Reflect.get(Mods.class, mods, "metas");
			meta = metas.get(ModTools.class);
			loadCore();
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
		if (R_Extending.http_redirect) {
			Tools.runLoggedException(URLRedirect::load);
		}
		if (R_Extending.object_pool && !OS.isAndroid) {
			Tools.runLoggedException(MagicInstaller::installMagic);
			Tools.runLoggedException(ObjectPool::load);
		}
		if (R_Extending.world_save) {
			Tools.runLoggedException(WorldSaver::load);
		}

		if (TEST) {
			test();
		}
	}
	private static void test() {
		// World w = SampleWorldInterface.changeClass(new World());
		try {
			Log.info(ReflectionFactory.getReflectionFactory().newConstructorForSerialization(
			 Lookup.class,
			 Lookup.class.getDeclaredConstructor(Class.class)
			 ).newInstance(Lookup.class));
		} catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
		new MyContentParser();
	}

	private void loadCore() {
		if (!isImportFromGame) meta.hidden = false;
		resolveLibsCatch();

		try {
			if (OS.isAndroid) HiddenApi.setHiddenApiExemptions();
		} catch (Throwable e) {
			/* Log.err(e);
			System.exit(-1); */
		}

		// HopeProcessor.main();

		WorldDraw.registerEvent();
		HopeCall.registerPacket();

		if (isImportFromGame) {
			// loadContent();
			loadModules();
		} else {
			Events.on(ClientLoadEvent.class,
			 _ -> Tools.runLoggedException(this::loadModules));
		}
	}


	private static final TaskSet taskLoadContent = new TaskSet();
	public void loadContent() {
		meta.hidden = true;
		// Time.mark();
		Tools.runLoggedException(Tester::initExecution);

		extending();
		// new SamplePackage();
		taskLoadContent.exec();
		// Log.info("Initialized Execution in @ms", Time.elapsed());
	}
	private static void resolveLibsCatch() {
		try {
			loadLibs();
		} catch (Throwable e) {
			Log.err(e);
			if (e instanceof UnexpectedPlatform) {
				Log.err("It seems you platform is special. (But don't worry.)");
			}
			planB_resolveLibs();
		}
	}
	private static void planB_resolveLibs() {
		TaskManager.forceRun(() -> {
			if (mods.getMod(ModTools.class) == null) return false;
			loadLibs();
			return true;
		});
	}
	private static void loadLibs() {
		//noinspection Convert2MethodRef
		loadLib("reflect-core", "ihope_lib.MyReflect", true, () -> MyReflect.load());
		hasDecompiler = loadLib("procyon-0.6", "com.strobel.decompiler.Decompiler", false);
		if (E_Extending.force_language.getLocale() != null && Core.bundle.getLocale() != E_Extending.force_language.getLocale()) {
			if (isImportFromGame) {
				loadBundle(E_Extending.force_language.getLocale().toString());
			} else {
				taskLoadContent.addOnce(() -> loadBundle(E_Extending.force_language.getLocale().toString()));
			}
		} else if (isImportFromGame) {
			loadBundle();
		}
	}

	/** 包括 Input，UI，Contents */
	public void loadModules() {
		if (ui == null) return;
		if (DISABLE_UI) return;
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
		if (isDesktop() && E_Extending.import_mod_from_drop.enabled()) {
			load("DropMod", DropFile::load);
		}
		load("Contents", Contents::load);
		load("IntUI", IntUI::load);
		load("CustomViewer", Viewers::loadCustomMap);

		if (E_Extending.auto_update.enabled()) {
			load("Updater", Updater::checkUpdate);
		}

		// INFO_DIALOG.dialog(
		//  LABEL."aaa\{pink}2290\{sky}sky\n\{UnitTypes.alpha.fullIcon}Map");


		// new MapEditor<>("MapEditor", new JsonReader().parse("""
		//  [{"key": "133", "value": "134"},
		//  {"key": "1asas33", "value": "13sa4"}]
		//  """),
		//  String.class, String.class,
		//  JsonValue::name, JsonValue::asString);

		/* INFO_DIALOG.dialog(p -> {
			ExtendingLabel label = new ExtendingLabel("aaos\nhttps://baidu.com");
			label.addUnderline(5, 5 + 17, Color.sky);
			label.down = Tex.underline;
			label.over = Tex.underlineOver;
			label.clickedRegion(() -> Tmp.p1.set(5, 5 + 17), () -> {
				Core.app.openURI("https://baidu.com");
			});
			p.add(label);
		}); */

		loaded = true;
		async(() -> {
			AllTutorial.init();
			if (SETTINGS.getBool("ShowMainMenuBackground")) {
				Core.app.post(() -> Tools.runLoggedException(Background::load));
			}
			Tools.TASKS.add(() -> {
				if (mods.getMod(ModTools.class) == null) {
					disposeAll();
				}
			});
		}, () -> Log.info("Loaded ModTools modules in @ms", Time.elapsed()));
	}

	public static void load(String moduleName, Runnable r) {
		Tools.runLoggedException(moduleName, r::run);
	}

	public static void loadBundle() {
		loadBundle(null);
	}
	/**
	 * @see Mods#buildFiles()
	 */
	public static void loadBundle(String locate) {
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
			String str    = locate == null ? bundle.getLocale().toString() : locate;
			String locale = "bundle" + (str.isEmpty() ? "" : "_" + str);
			if (bundles.containsKey(locale)) {
				for (Fi file : bundles.get(locale)) {
					try {
						PropertiesUtils.load(bundle.getProperties(), file.reader());
					} catch (Throwable e) {
						Log.err(STR."Error loading bundle: \{file}/\{locale}", e);
					}
				}
			}
			bundle = bundle.getParent();
		}
		// jdk.internal.event.EventHelper.isLoggingSecurity();
	}

	public static ClassLoader lastLoader;
	public static boolean loadLib(String fileName, String mainClassName, boolean showError) {
		return loadLib(fileName, mainClassName, showError, null);
	}
	public static boolean loadLib(String fileName, String mainClassName, boolean showError,
	                              Runnable callback) {
		try {
			// 没报错的话，证明已经加载
			mainLoader.loadClass(mainClassName);
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
			ClassLoader loader = platform.loadJar(toFi, mainLoader);
			mainLoader.addChild(loader);
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
		Tools.dispose();
		WorldDraw.tasks.clear();
		IntUI.disposeAll();
		HopeInput.dispose();
		dispose();
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

	public static class UnexpectedPlatform extends RuntimeException { }
}