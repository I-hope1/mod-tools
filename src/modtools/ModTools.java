package modtools;


import arc.*;
import arc.files.Fi;
import arc.graphics.Color;
import arc.scene.ui.Label;
import arc.struct.*;
import arc.util.*;
import arc.util.io.PropertiesUtils;
import com.sun.tools.attach.VirtualMachine;
import ihope_lib.MyReflect;
import mindustry.core.Version;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.mod.*;
import mindustry.mod.Mods.ModMeta;
import modtools.android.*;
import modtools.content.*;
import modtools.content.debug.Tester;
import modtools.events.*;
import modtools.extending.URLRedirect;
import modtools.graphics.MyShaders;
import modtools.jsfunc.INFO_DIALOG;
import modtools.net.packet.HopeCall;
import modtools.struct.TaskSet;
import modtools.ui.*;
import modtools.ui.comp.utils.Viewers;
import modtools.ui.control.HopeInput;
import modtools.ui.effect.ScreenSampler;
import modtools.ui.gen.HopeIcons;
import modtools.ui.tutorial.AllTutorial;
import modtools.unsupported.HopeProcessor.MyContentParser;
import modtools.unsupported.HotSwapManager;
import modtools.utils.*;
import modtools.utils.files.HFi;
import modtools.utils.io.FileUtils;
import modtools.utils.ui.DropFile;
import modtools.utils.world.WorldDraw;
import sun.reflect.ReflectionFactory;
import sun.tools.attach.VirtualMachineImpl;

import java.lang.invoke.MethodHandles.Lookup;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
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
	private static      boolean isImportFromGame = false;
	public static final boolean DISABLE_UI       = false;

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
		if (R_Extending.world_save) {
			Tools.runLoggedException(WorldSaver::load);
		}
		if (OS.isAndroid) {
			AndroidInputFix.load();
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
		MySettings.load();

		try {
			if (OS.isAndroid) {
				HiddenApi.setHiddenApiExemptions();
			}

			if (HotSwapManager.valid()) {
				HotSwapManager.start();
			}
			if (R_Hook.dynamic_jdwp) {
				String         pid    = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
				VirtualMachine vm     = VirtualMachine.attach(pid);
				Method         method = VirtualMachineImpl.class.getDeclaredMethod("execute", String.class, Object[].class);
				method.setAccessible(true);
				String arg = "transport=dt_socket,server=y,suspend=n,address=" + R_Hook.dynamic_jdwp_port;
				vm.loadAgentLibrary("jdwp", arg);
				vm.detach();
				Log.info("Loaded jdwp.");
				/* // 获取当前 JVM 内部真正的 jdwp 路径
				String javaHome = System.getProperty("java.home");
				String jdwpPath = javaHome + File.separator + "bin" + File.separator + "jdwp.dll";

				// 检查文件是否存在
				if (new File(jdwpPath).exists()) {
					// 使用 loadAgent (注意：不是 loadAgentLibrary)
					// loadAgent 内部调用的是 Agent_OnAttach，且接受绝对路径
					vm.loadAgent(jdwpPath, "transport=dt_socket,server=y,suspend=n,address=127.0.0.1:5005");
				} */

				// vm.loadAgentLibrary("jdwp", arg);
				// vm.loadAgent("jdwp", arg);
				/* Class<?> CLS = Class.forName("sun.jvm.hotspot.HotSpotAgent");
				Method   method = CLS.getMethod("attach", int.class);
				method.invoke(CLS.newInstance(), Integer.parseInt(ManagementFactory.getRuntimeMXBean().getName().split("@")[0])); */
			}
			// HotSwapManager.attachAgent("jdwp", "transport=dt_socket,server=y,suspend=n,address=15005");
			// if (OS.isAndroid) TestAndroidVM.main();
		} catch (Throwable e) {
			Log.err(e);
		}

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

		if (OS.isAndroid) WSAInputFixer.install();
		IntVars.load();

		if (errors.any()) {
			errors.each(e -> ui.showException(e));
			return;
		}

		// 加载HopeIcons
		HopeIcons.modName = modName;
		// Core.batch = new MySpriteBatch();
		load("HopeIcons", HopeIcons::load);
		load("MyShaders", MyShaders::load);
		load("MyFonts", MyFonts::load);
		load("HopeInput", HopeInput::load);
		if (isDesktop() && E_Extending.import_mod_from_drop.enabled()) {
			load("DropMod", DropFile::load);
		}
		/* if (Core.app.isAndroid()) {
			load("AndroidOptimize", AndroidOptimize::load);
		} */
		load("Contents", Contents::load);
		load("IntUI", IntUI::load);
		load("CustomViewer", Viewers::loadCustomMap);
		//INFO_DIALOG.dialog(c -> c.button("BTN", () -> Log.info("==-Lggg4")));


		if (E_Extending.auto_update.enabled()) {
			load("Updater", Updater::checkUpdate);
		}

		INFO_DIALOG.dialog(d -> {
			d.image().size(64).update(i -> i.setColor(Core.input.alt() ? Color.white : Color.yellow)).row();
			Label elem = d.add("Click me").get();
			EventHelper.leftClick(elem, () -> IntUI.showInfoFade("left click"));
			EventHelper.rightClick(elem, () -> IntUI.showInfoFade("right click"));
		});

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
			Fi          toFi   = FileUtils.copyToTmp(sourceFi);
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
		IntVars.dispose();
		MyEvents.dispose();
		MyFonts.dispose();
		ModClassLoader   loader   = (ModClassLoader) mods.mainLoader();
		Seq<ClassLoader> children = Reflect.get(ModClassLoader.class, loader, "children");
		children.remove(ModTools.class.getClassLoader());
		Content.all.forEach(Content::dispose);
		Content.all.clear();
		System.gc();
		Core.app.post(() -> mods.removeMod(mod));
	}
	public static boolean isDisposed() {
		return isDisposed;
	}

	public static class UnexpectedPlatform extends RuntimeException { }
}
