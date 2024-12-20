
package modtools.content.ui;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.math.Interp;
import arc.scene.*;
import arc.scene.actions.Actions;
import arc.scene.style.*;
import arc.scene.ui.Button.ButtonStyle;
import arc.scene.ui.*;
import arc.scene.ui.Dialog.DialogStyle;
import arc.scene.ui.ImageButton.ImageButtonStyle;
import arc.scene.ui.Label.LabelStyle;
import arc.scene.ui.ScrollPane.ScrollPaneStyle;
import arc.scene.ui.Slider.SliderStyle;
import arc.scene.ui.TextButton.TextButtonStyle;
import arc.scene.ui.TextField.TextFieldStyle;
import arc.scene.ui.layout.Stack;
import arc.scene.ui.layout.*;
import arc.scene.utils.Disableable;
import arc.struct.Seq;
import arc.util.*;
import arc.util.pooling.*;
import mindustry.Vars;
import mindustry.core.UI;
import mindustry.gen.*;
import mindustry.graphics.Pal;
import mindustry.ui.*;
import modtools.*;
import modtools.content.*;
import modtools.jsfunc.reflect.InitMethodHandle;
import modtools.ui.*;
import modtools.ui.IntUI.SelectTable;
import modtools.ui.comp.*;
import modtools.ui.comp.input.JSRequest;
import modtools.ui.comp.utils.*;
import modtools.ui.control.HopeInput;
import modtools.ui.reflect.RBuilder;
import modtools.utils.EventHelper;
import modtools.utils.io.FileUtils;
import modtools.utils.reflect.FieldUtils;
import modtools.utils.search.*;
import modtools.utils.ui.*;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static arc.scene.ui.CheckBox.CheckBoxStyle;
import static modtools.utils.Tools.*;
import static modtools.utils.ui.FormatHelper.fixed;

@SuppressWarnings("StringTemplateMigration")
public class ShowUIList extends Content {
	public static final  int    cols       = 3;
	private static final String INIT_ARRAY =
	 """
		[
		 // Write your qualified class names here.
		]
		""";

	Window ui;

	public ShowUIList() {
		super("showuilist", Icon.imageSmall);
	}

	public void lazyLoad() {
		ui = new IconWindow(getW(), 400, true);
		Table cont = ui.cont;

		Table[] tables = {icons, tex, styles, colorsT, interps, actions = newTable(new ActionComp())};
		Color[] colors = {Color.sky, Color.gold, Color.orange, Color.acid, Pal.command, Color.cyan};

		String[] names = {"Icon", "Tex", "Styles", "Colors", "Interp", "Actions"};
		IntTab   tab   = new IntTab(CellTools.unset, names, colors, tables);
		tab.eachWidth = getW() / 4.3f;
		tab.setPrefSize(getW(), CellTools.unset);
		cont.table(t -> {
			t.left().defaults().left();
			t.add(colorWrap);
			t.add("@mod-tools.tips.dclick_to_copy").color(Color.lightGray).padLeft(6f).row();

			t.table(t0 -> {
				t0.check("ForceDisabled",
					forceDisabled, val -> forceDisabled = val)
				 .with(c -> c.setStyle(HopeStyles.hope_defaultCheck));
			}).colspan(3).growX().padTop(-4f);
		}).row();

		Table top = new Table();
		cont.add(top).growX().row();
		cont.top();
		cont.add(tab.build()).grow();
		cont.act(0);
		cont.layout();
		new Search<>((_, pattern0) -> pattern = pattern0).build(top, cont);
		// ui.addCloseButton();
	}

	boolean forceDisabled;
	Pattern pattern;

	Color bgColor;
	static Color iconColor;
	Table colorWrap = new Table();

	{
		bgColor = SettingsUI.colorBlock(colorWrap.table().get(),
		 "bg", data(), "bgColor",
		 0x877F5E_FF, null);
		iconColor = SettingsUI.colorBlock(colorWrap.table().padLeft(6f).get(),
		 "icon", data(), "iconColor",
		 -1/* white */, null);
	}

	public static Fi uiConfig = IntVars.dataDirectory.child("ui");

	// 这里用HashMap是因为ObjectMap刚好会hash碰撞。。。
	public static Map<Drawable, String> iconKeyMap  = new HashMap<>();
	public static Map<Drawable, String> texKeyMap   = new HashMap<>();
	public static Map<Color, String>    colorKeyMap = new HashMap<>();

	public static Map<Style, String>    styleKeyMap     = new HashMap<>();
	public static Map<Drawable, String> styleIconKeyMap = new HashMap<>();

	public static Map<Group, String> uiKeyMap   = new HashMap<>();
	public static Map<Font, String>  fontKeyMap = new HashMap<>();

	static {
		fontKeyMap.put(Fonts.icon, "icon");
		fontKeyMap.put(Fonts.def, "def");
		fontKeyMap.put(Fonts.iconLarge, "iconLarge");
		fontKeyMap.put(Fonts.tech, "tech");
		fontKeyMap.put(Fonts.outline, "outline");
	}

	static Field colorField = FieldUtils.getFieldAccess(Element.class, "color");
	private static void setColor(Image image) {
		FieldUtils.setValue(colorField, image, iconColor);
	}
	public static final int imageSize = 36;

	Table icons = newTable(t -> FieldUtils.walkAllConstOf(Icon.class, (field, icon) -> {
		String k = field.getName();
		iconKeyMap.put(icon, k);
		t.addRun(() -> {
			t.bind(k);
			var region = icon.getRegion();
			setColor(t.image(icon)
			 .size(imageSize, region.height / (float) region.width * imageSize).get());
			field(t, k).row();
			t.unbind();
		});
	}, TextureRegionDrawable.class)),
	 tex        = newTable(t -> {
		 String prefix = "Tex.";
		 FieldUtils.walkAllConstOf(Tex.class, (field, drawable) -> {
			 texKeyMap.put(drawable, prefix + field.getName());
			 t.addRun(() -> {
				 t.bind(field.getName());
				 addImage(t, drawable);
				 fieldWithView(t, field, drawable);
				 t.unbind();
				 t.row();
			 });
		 }, Drawable.class);
	 }),
	 styles     = newTable(true, t -> {
		 final String type = "styles";
		 customJson(type, t);
		 Cons<Class<?>> builder = cls -> listAllStyles(t, cls);
		 builder.get(Styles.class);
		 readJson(type, builder);
	 }),
	 colorsT    = newTable(t -> {
		 final String type = "colors";
		 customJson(type, t);
		 Cons<Class<?>> buildColor = cls -> {
			 t.toRuns.add(() -> {
				 t.add(cls.getSimpleName()).colspan(2).color(Pal.accent).growX().row();
				 t.image().color(Pal.accent).colspan(2).growX().row();
			 });

			 String prefix = cls.getSimpleName() + ".";
			 FieldUtils.walkAllConstOf(cls, (field, color) -> {
				 colorKeyMap.put(color, prefix + field.getName());

				 t.addRun(() -> {
					 t.bind(field.getName());
					 t.listener(el -> IntUI.addTooltipListener(el, () -> FormatHelper.color(color)));
					 t.add(new BorderImage(Core.atlas.white(), 2f)
						.border(color.cpy().inv())).color(color).size(42f);
					 field(t, field.getName()).growX();
					 t.listener(null);
					 t.unbind();
					 t.row();
				 });
			 }, Color.class);
		 };
		 buildColor.get(Color.class);
		 buildColor.get(Pal.class);
		 readJson(type, buildColor);
	 }),
	 interps    = newTable(t -> t.addRun(() -> {
		 Table table = new Table();
		 t.pane(table).pad(10f).grow().colspan(2).get();
		 t.row();
		 t.defaults().growX();
		 int[] c = {0};
		 t.button("Built-in", () -> {
			 table.clearChildren();
			 c[0] = 0;
			 FieldUtils.walkAllConstOf(Interp.class, (f, interp) -> {
				 table.add(new InterpImage(interp))
					.tooltip(f.getName())
					.size(120).padBottom(32f);
				 if (++c[0] % cols == 0) table.row();
			 }, Interp.class);
		 });
		 t.button("@add", () -> {
			 JSRequest.requestFor(Interp.class, Interp.class, interp -> {
				 table.add(new InterpImage(interp))
					.size(120).padBottom(32f);
				 if (++c[0] % cols == 0) table.row();
			 });
		 });
	 })),
	 actions,
	 uis        = newTable(t -> {
		 UI obj = Vars.ui;
		 FieldUtils.walkAllConstOf(UI.class, (f, group) -> {
			 uiKeyMap.put(group, f.getName());
			 t.addRun(() -> {
				 t.bind(f.getName());
				 t.add(f.getName());
				 t.add(new FieldValueLabel(ValueLabel.unset, f, obj));
				 t.unbind();
			 });
		 }, Group.class, obj);
	 });
	private static void readJson(String type, Cons<Class<?>> builder) {
		runLoggedException(() -> {
			Fi json = uiConfig.child(type + ".json");
			if (!json.exists()) json.writeString(INIT_ARRAY);
			Class<?>[] classes = IntVars.json.fromJson(Class[].class, json);
			for (Class<?> cl : classes) {
				builder.get(cl);
			}
		});
	}
	private static void customJson(String type, LazyTable<Object> t) {
		t.addRun(() -> {
			t.table(t1 -> {
				t1.add("Custom " + Strings.capitalize(type) + ": ");
				String fileName = type + ".json";
				t1.button(fileName, () -> FileUtils.openFile(uiConfig.child(fileName))).growX();
			}).colspan(2).growX().row();
		});
	}
	private static void fieldWithView(LazyTable<Object> t, Field field, Drawable drawable) {
		if (drawable != null) {
			t.table(p -> {
				field(p, field.getName());
				viewDrawable(p, drawable);
			});
		} else {
			field(t, field.getName());
		}
	}
	private static Cell<Label> field(Table p, String fieldName) {
		return p.add(fieldName).with(EventHelper::addDClickCopy);
	}
	void listAllStyles(LazyTable<Object> t, Class<?> stylesClass) {
		String  prefix = stylesClass.getSimpleName() + ".";
		Field[] fields = getStyleFields(stylesClass);
		for (Field field : fields) {
			if (!Modifier.isStatic(field.getModifiers())) continue;
			Object obj = null;
			try {
				// 跳过访问检查，减少时间
				field.setAccessible(true);
				obj = field.get(null);
				if (obj instanceof Style style1) styleKeyMap.put(style1, prefix + field.getName());
				if (obj instanceof Drawable d) styleIconKeyMap.put(d, prefix + field.getName());
			} catch (IllegalAccessException | IllegalArgumentException err) {
				Log.err(err);
				continue;
			}
			Object finalObj = obj;
			t.addRun(() -> {
				t.bind(field.getName());

				switch (finalObj) {
					case ScrollPaneStyle style -> Builder.build(style, t);
					case DialogStyle style -> Builder.build(style, t);
					case LabelStyle style -> Builder.build(style, t);
					case SliderStyle style -> Builder.build(style, t);
					case TextFieldStyle style -> Builder.build(style, t);
					case CheckBoxStyle style -> Builder.build(style, t);
					case TextButtonStyle style -> Builder.build(style, t);
					case ImageButtonStyle style -> Builder.build(style, t);
					case ButtonStyle style -> Builder.build(style, t);
					case Drawable style -> Builder.build(style, t);
					default -> t.add();
				}

				t.table(t1 -> {
					field(t1, field.getName());
					PreviewUtils.addPreviewButton(t1, p -> {
						switch (finalObj) {
							case ScrollPaneStyle style -> Builder.view(style, p);
							case DialogStyle style -> Builder.view(style, p);
							case LabelStyle style -> Builder.view(style, p);
							case SliderStyle style -> Builder.view(style, p);
							case TextFieldStyle style -> Builder.view(style, p);
							case CheckBoxStyle style -> Builder.view(style, p);
							case TextButtonStyle style -> Builder.view(style, p);
							case ImageButtonStyle style -> Builder.view(style, p);
							case ButtonStyle style -> Builder.view(style, p);
							case Drawable style -> Builder.view(style, p);
							default -> { }
						}
					}).padLeft(8f);
				}).left().row();
				t.unbind();
			});
		}
	}

	// 安卓上不支持Comparator
	@SuppressWarnings("ComparatorCombinators")
	private static Field[] getStyleFields(Class<?> stylesClass) {
		return OS.isAndroid ? Arrays.stream(stylesClass.getFields())
		 .sorted((a, b) -> a.getName().compareTo(b.getName()))
		 .toArray(Field[]::new) : stylesClass.getFields();
	}
	static void addImage(Table t, Drawable drawable) {
		Image image = new Image(drawable);
		setColor(image);
		image.fillParent = true;
		Label label = new Label(drawable instanceof TextureRegionDrawable ? "texture" : "nine");
		label.setColor(Color.lightGray);
		label.setFontScale(0.7f);
		label.fillParent = true;
		label.setAlignment(Align.topLeft);
		Stack stack = t.stack(image, label).size(32).get();
		IntUI.hoverAndExit(stack,
		 () -> label.visible = false,
		 () -> label.visible = true);
	}
	private static float getW() {
		return Core.graphics.isPortrait() ? 320 : 400;
	}
	public void build() {
		ui.show();
	}
	public <T> LazyTable<T> newTable(Cons<LazyTable<T>> cons) {
		return newTable(false, cons);
	}

	public <T> LazyTable<T> newTable(boolean withDisabled, Cons<LazyTable<T>> cons) {
		return new LazyTable<>(t -> {
			t.top().left().defaults().left().padLeft(6f);
			t.addChild(new FillElement() {
				public void draw() {
					Draw.color(bgColor, bgColor.a * parentAlpha);
					IntUI.whiteui.draw(t.x, t.y, t.getWidth(), t.getHeight());
				}
			});
			cons.get(t);
			t.addPatternUpdateListener(() -> pattern);
		}) {
			public <T1 extends Element> Cell<T1> add(T1 element) {
				if (withDisabled && element instanceof Disableable button) {
					element.update(() -> button.setDisabled(forceDisabled));
				}
				return super.add(element);
			}
		};
	}

	public static class Builder {
		static void build(ScrollPaneStyle style, Table t) {
			t.pane(style, p -> {
				p.add("pane").row();
				p.add("test-test-test").color(Color.gray).row();
				p.add("test-test-test").color(Color.gray).row();
			}).growX().maxWidth(144).height(64);
		}
		static void build(DialogStyle style, Table t) {
			t.pane(p -> p.add(new Dialog("dialog", style))).growX().height(42);
		}
		static void build(LabelStyle style, Table t) {
			t.add("label", style);
		}
		static void build(SliderStyle style, Table t) {
			t.slider(0, 10, 1, f -> { })
			 .get().setStyle(style);
		}
		static void build(TextButtonStyle style, Table t) {
			t.button("text button", style, IntVars.EMPTY_RUN).size(96, 42);
		}
		static void build(ImageButtonStyle style, Table t) {
			t.button(Icon.ok, style, IntVars.EMPTY_RUN).size(96, 42);
		}
		static void build(ButtonStyle style, Table t) {
			t.button(b -> {
				b.add("button");
			}, style, IntVars.EMPTY_RUN).size(96, 42);
		}
		static void build(TextFieldStyle style, Table t) {
			t.field("field", style, _ -> { });
		}
		static void build(CheckBoxStyle style, Table t) {
			t.add(new CheckBox("checkbox", style)).height(42);
		}
		static void build(Drawable drawable, Table t) {
			addImage(t, drawable);
		}

		static void view(Style style, Table t) {
			t.button("Copy", HopeStyles.flatBordert, () -> {
				ValueLabel.copyStyle(style);
			}).size(96, 42);
		}

		static void view(Drawable drawable, Table t) {
			KeyValue keyValue = KeyValue.THE_ONE;
			keyValue.keyValue(t, "Left", () -> fixed(drawable.getLeftWidth()));
			keyValue.keyValue(t, "Right", () -> fixed(drawable.getRightWidth()));
			keyValue.keyValue(t, "Top", () -> fixed(drawable.getTopHeight()));
			keyValue.keyValue(t, "Bottom", () -> fixed(drawable.getBottomHeight()));
			keyValue.keyValue(t, "MinWidth", () -> fixed(drawable.getMinHeight()));
			keyValue.keyValue(t, "MinHeight", () -> fixed(drawable.getMinHeight()));
			keyValue.keyValue(t, "ImageSize", () -> fixed(drawable.imageSize()));
		}
	}

	public static class LazyTable<T> extends FilterTable<T> {
		private Seq<Runnable> toRuns;
		Seq<Runnable> toRuns() {
			if (toRuns == null) toRuns = new Seq<>();
			return toRuns;
		}
		public LazyTable(Cons<LazyTable<T>> cons) {
			super(cons);
		}
		public void act(float delta) {
			super.act(delta);
			toRuns().each(Runnable::run);
			toRuns().clear();
		}
		public void addRun(Runnable run) {
			toRuns().add(run);
		}
	}
	public static class TotalLazyTable extends Table {
		private Cons<TotalLazyTable> cons;
		public TotalLazyTable(Cons<TotalLazyTable> lazyCons) {
			this(_ -> { }, lazyCons);
		}

		@SuppressWarnings("unchecked")
		public TotalLazyTable(Cons<TotalLazyTable> notLazyCons, Cons<TotalLazyTable> lazyCons) {
			super((Cons) notLazyCons);
			this.cons = lazyCons;
		}
		public void act(float delta) {
			if (cons != null) {
				cons.get(this);
				cons = null;
			}
			super.act(delta);
		}
	}


	static class ActionComp implements Cons<LazyTable<String>> {
		final Element element = new Image();
		MethodHandle init = Constants.nl(() -> InitMethodHandle.findInit(Image.class.getConstructor()));
		final Seq<String> blackList = Seq.with("time", "began", "complete", "lastPercent", "color",
		 "start",
		 "startR", "startG", "startB", "startA",
		 "startX", "startY");

		public void get(LazyTable<String> cont) {
			cont.center();
			Cell<Element> cell = cont.add(element).size(64).pad(24);
			cont.row();
			element.update(() -> element.setOrigin(Align.center));
			// element.setOrigin(element.getWidth() / 2f, element.getHeight() / 2f);
			// element.translation.set(element.getWidth() / 2f, element.getHeight() / 2f);
			Set<Class<?>> classes = Arrays.stream(Actions.class.getDeclaredMethods())
			 .map(Method::getReturnType).collect(Collectors.toCollection(LinkedHashSet::new));
			cont.button("Reset", Styles.flatt, runT(() -> {
				element.rotation = 0;
				cell.setElement(element);
				init.invoke(element);
				cont.layout();
			})).growX().height(42).row();
			cont.pane(t -> {
				int c = 0;
				for (Class<?> action : classes) {
					if (!Action.class.isAssignableFrom(action)) continue;
					if (Modifier.isAbstract(action.getModifiers())) continue;
					String text = action.getSimpleName();
					t.button(text.substring(0, text.length() - 6/* Action */), HopeStyles.flatt, () -> {
						applyToAction(as(action));
					}).size(140, 42);
					if (++c % 2 == 0) t.row();
				}
			}).grow();
		}
		private <T extends Action> void applyToAction(Class<T> actionClass) {
			Pool<T> pool = Pools.get(actionClass, () -> Reflect.make(actionClass.getName()));

			T action = pool.obtain();
			action.setPool(pool);
			action.reset();
			SelectTable table = IntUI.showSelectTable(HopeInput.mouseHit(), (p, hide, _) -> {
				RBuilder.build(p);
				RBuilder.buildFor(actionClass, action, blackList);
				RBuilder.clearBuild();
			}, false);
			table.hidden(() -> element.addAction(action));
		}
	}

	static void viewDrawable(Table table, Drawable drawable) {
		PreviewUtils.addPreviewButton(table, p -> Builder.view(drawable, p)).padLeft(8f);
	}
}