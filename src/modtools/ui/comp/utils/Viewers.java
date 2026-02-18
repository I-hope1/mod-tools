package modtools.ui.comp.utils;

import arc.files.Fi;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.math.geom.*;
import arc.scene.Element;
import arc.struct.*;
import arc.struct.ObjectMap.Entry;
import arc.util.*;
import arc.util.pooling.*;
import arc.util.pooling.Pool.Poolable;
import arc.util.serialization.*;
import arc.util.serialization.Jval.Jformat;
import mindustry.Vars;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.*;
import mindustry.world.Tile;
import modtools.IntVars;
import modtools.content.ui.ReviewElement;
import modtools.events.R_JSFunc;
import modtools.jsfunc.IScript;
import modtools.ui.comp.input.ExtendingLabel.DrawType;
import modtools.ui.comp.input.JSRequest;
import modtools.ui.comp.input.JSRequest.LazyArg;
import modtools.ui.comp.input.highlight.Syntax;
import modtools.utils.*;
import modtools.utils.ArrayUtils.AllCons;
import modtools.utils.JSFunc.JColor;
import modtools.utils.SR.SatisfyException;
import modtools.utils.reflect.FieldUtils;
import modtools.utils.ui.*;
import modtools.utils.world.WorldUtils;
import rhino.*;

import java.lang.reflect.*;
import java.util.*;

import static modtools.events.E_JSFunc.chunk_background;
import static modtools.jsfunc.type.CAST.box;
import static modtools.ui.comp.input.highlight.Syntax.c_map;

public class Viewers {
	public static final Seq<ViewerItem<?>>  internalViewers = new Seq<>();
	public static final Seq<ViewerItem<?>>  customViewers   = new Seq<>();
	public static final ObjectSet<Class<?>> identityClasses = ObjectSet.with(
	 Vec2.class, Rect.class, Color.class
	);
	public static void loadCustomMap() {
		Fi file = IntVars.dataDirectory.child("customViewers.json");
		if (!file.exists()) file.writeString("[\n]");
		JsonValue value = new JsonReader().parse(Jval.read(file.readString()).toString(Jformat.plain));
		vscope = JSRequest.wrapper(IScript.scope, "Viewers", (LazyArg) () -> IScript.cx.getWrapFactory().wrapJavaClass(IScript.cx, vscope, Viewers.class));
		customViewers.set(json.readValue(Seq.class, ViewerItem.class, value));
		// Log.info(customViewers);

		// json.setOutputType(OutputType.json);
		// json.toJson(customViewers, file.sibling("customMap2.json"));
	}

	static Scriptable          vscope;
	static ObjectSet<Class<?>> interfaces = ObjectSet.with(
	 Boolf.class, Func.class, Prov.class, Cons.class, Boolp.class, Floatp.class, Floatf.class,
	 Func2.class, Func3.class, Viewer.class
	);

	@SuppressWarnings("unchecked")
	static Json json = new Json() {
		{ setTypeName("type"); }

		public void writeValue(Object object, Class knownType, Class elementType) {
			if (object instanceof JSCode code) {
				object = code.getJSCode();
				knownType = String.class;
			}
			if (object instanceof Class clazz) {
				object = clazz.getName();
				knownType = String.class;
			}
			super.writeValue(object, knownType, elementType);
		}
		public <T> T readValue(Class<T> type, JsonValue jsonData) {
			return super.readValue(type, jsonData);
		}
		@Override
		public <T> T readValue(Class<T> type, Class elementType, JsonValue jsonData, Class keytype) {
			if (type == Class.class) {
				try {
					return (T) Vars.mods.mainLoader().loadClass(jsonData.asString());
				} catch (ClassNotFoundException e) {
					throw new RuntimeException(e);
				}
			}
			if (interfaces.contains(type)) {
				if (!jsonData.isString()) { return null; }
				String jscode = jsonData.asString();

				Context cx1 = IScript.cx;
				var interface_ = ScriptRuntime.doTopCall((cx, _, _, _) -> Context.jsToJava(cx.evaluateString(
				 JSRequest.wrapper(vscope), jscode, "<gen>", 1), type), cx1, vscope, vscope, null, true);
				try {
					return (T) Proxy.newProxyInstance(Vars.mods.mainLoader(), new Class[]{type, JSCode.class},
					 (proxy, method, args) -> {
						 if (method.getDeclaringClass() == JSCode.class && method.getName().equals("getJSCode")) {
							 return jscode;
						 }
						 return ScriptRuntime.hasTopCall(cx1) ? method.invoke(interface_, args) :
							ScriptRuntime.doTopCall((_, _, _, _) -> {
								try {
									return method.invoke(interface_, args);
								} catch (IllegalAccessException | InvocationTargetException e) {
									return null;
								}
							}, cx1, vscope, vscope, args, true);
					 });
				} catch (Throwable e) {
					Log.err(e);
					return null;
				}
			}
			return super.readValue(type, elementType, jsonData, keytype);
		}
	};

	public static final  boolean ARRAY_DEBUG  = false;
	private static final int     SIZE_MAX_BIT = 5;

	public static <T> void addViewer(Class<T> clazz, Viewer<T> viewer) {
		internalViewers.add(new ViewerItem<>(clazz, viewer));
	}
	public static void addViewer(Type type, Viewer<?> viewer) {
		internalViewers.add(new ViewerItem<>(type, viewer));
	}

	static {
		// map.put(String.class, (val, label) -> {
		// 	label.appendValue(label.getText(), val);
		// 	return true;
		// });
		addViewer(Color.class, (Color val, ValueLabel label) -> {
			StringBuilder text = label.getText();
			int           i    = label.startColor(val);
			text.append('■');
			defaultAppend(label, i, val);
			return true;
		});
		addViewer(Building.class, iconViewer((Building b) -> b.block.uiIcon));
		addViewer(Unit.class, iconViewer((Unit u) -> u.type().uiIcon));
		addViewer(Tile.class, iconViewer((Tile t) -> WorldUtils.getToDisplay(t).uiIcon));
		/* map.put(Bullet.class, iconViewer((Bullet b) -> WorldDraw.drawRegion((int) (b.hitSize * 1.8), (int) (b.hitSize * 1.8), () -> {
			float x = b.x, y = b.y;
			b.x = b.hitSize * 0.9f;
			b.y = b.hitSize * 0.9f;
			Draw.color();
			Fill.crect(0,0,100, 100);
			b.draw();
			b.x = x;
			b.y = y;
		}))); */
		addViewer(UnlockableContent.class, iconViewer((UnlockableContent uc) -> uc.uiIcon));

		addViewer(Type.map, (val, label) -> {
			if (!label.isExpand(val)) {
				label.clickedRegion(label.getPoint2Prov(val), () -> label.toggleExpand(val));
				label.expandVal.put(val, getMapSize(val) < R_JSFunc.max_auto_expand_size);
			}
			StringBuilder text = label.getText();

			int start = label.startColor(c_map);
			label.startIndexMap.put(start, val);
			text.append("|Map ").append(getMapSize(val)).append('|');
			label.endIndexMap.put(start, text.length());
			label.endColor();

			if (!label.expandVal.get(val)) {
				return true;
			}
			Runnable prev = label.appendTail;
			label.postAppendDelimiter(null);
			text.append("\n{");
			// label.postAppendDelimiter(null);
			switch (val) {
				case ObjectMap<?, ?> m -> appendMap(val, label, m.entries(), e -> e.key, e -> e.value);
				case Map<?, ?> m -> appendMap(val, label, m.entrySet(), Map.Entry::getKey, Map.Entry::getValue);

				case IntMap<?> m -> {
					for (var entry : m) {
						label.appendMap(val, entry.key, entry.value);
						if (label.isTruncate(label.getText().length())) break;
					}
				}
				case IntIntMap m -> {
					for (var entry : m) {
						label.appendMap(val, entry.key, entry.value);
						if (label.isTruncate(label.getText().length())) break;
					}
				}
				case IntFloatMap m -> {
					for (var entry : m) {
						label.appendMap(val, entry.key, entry.value);
						if (label.isTruncate(label.getText().length())) break;
					}
				}
				case LongMap<?> m -> {
					for (var entry : m) {
						label.appendMap(val, entry.key, entry.value);
						if (label.isTruncate(label.getText().length())) break;
					}
				}
				case ObjectIntMap<?> m -> {
					for (var entry : m) {
						label.appendMap(val, entry.key, entry.value);
						if (label.isTruncate(label.getText().length())) break;
					}
				}
				case ObjectFloatMap<?> m -> {
					for (var entry : m) {
						label.appendMap(val, entry.key, entry.value);
						if (label.isTruncate(label.getText().length())) break;
					}
				}
				default -> throw new UnsupportedOperationException();
			}
			// label.postAppendDelimiter();
			text.append('}');
			label.postAppendDelimiter(prev);
			// label.postAppendDelimiter();

			if (chunk_background.enabled()) label.addDrawRun(start, text.length(), DrawType.background, label.bgColor());

			return true;
		});
		addViewer(Type.array, (val, label) -> {
			if (!label.isExpand(val)) {
				label.clickedRegion(label.getPoint2Prov(val), () -> label.toggleExpand(val));
				label.expandVal.put(val, getArraySize(val) < R_JSFunc.max_auto_expand_size);
			}
			StringBuilder text  = label.getText();
			int           start = label.startColor(c_map);
			label.startIndexMap.put(start, val);

			text.append("|Array");
			int    sizeIndex = text.length();
			String repeat    = StringUtils.repeat('\u200d', SIZE_MAX_BIT);
			text.append(repeat).append('|');

			label.endIndexMap.put(start, text.length());
			label.endColor();

			if (!label.expandVal.get(val)) {
				return true;
			}

			text.append("\n[");

			Pool<IterCons> pool = Pools.get(IterCons.class, IterCons::new, 50);
			IterCons       cons = pool.obtain().init(label, val, text);
			Runnable       prev = label.appendTail;
			label.postAppendDelimiter(null);
			Runnable[] append = {null};
			try {
				switch (val) {
					case Iterable<?> iter -> {
						append[0] = () -> cons.append(null);
						for (Object item : iter) {
							cons.get(item);
						}
					}
					case IntSeq seq -> {
						append[0] = () -> cons.append(0);
						seq.each(cons::get);
					}
					case FloatSeq seq -> {
						append[0] = () -> cons.append(0f);
						for (int i = 0; i < seq.size; i++) {
							cons.get(seq.get(i));
						}
					}
					case LongSeq seq -> {
						append[0] = () -> cons.append(0L);
						for (int i = 0; i < seq.size; i++) {
							cons.get(seq.get(i));
						}
					}
					default -> {
						if (val.getClass().isArray()) ArrayUtils.forEach(val, cons, r -> append[0] = r);
					}
				}
			} catch (SatisfyException ignored) {
			} catch (ArcRuntimeException ignored) {
				defaultAppend(label, start, val);
				return true;
			} catch (Throwable e) {
				if (ARRAY_DEBUG) Log.err(e);
				text.append("▶ERROR◀");
			} finally {
				label.postAppendDelimiter(prev);
				try {
					if (append[0] != null) append[0].run();
				} catch (SatisfyException ignored) {
				} catch (Throwable e) {
					Log.err(e);
				}
				// 自动补位
				text.replace(sizeIndex, sizeIndex + SIZE_MAX_BIT, String.format("% " + SIZE_MAX_BIT + "d", cons.size()));
				pool.free(cons);
			}
			text.append(']');

			if (chunk_background.enabled()) label.addDrawRun(start, text.length(), DrawType.background, label.bgColor());
			// setColor(Color.white);
			return true;
		});
	}


	public static <M, K, V> void appendMap(Object val, ValueLabel label,
	                                       Iterable<M> map, Func<M, K> keyF, Func<M, V> valueF) {
		for (var entry : map) {
			label.appendMap(val, keyF.get(entry), valueF.get(entry));
			if (label.isTruncate(label.getText().length())) break;
		}
	}

	public static <T> Viewer<T> iconViewer(Func<T, TextureRegion> iconFunc) {
		return (T val, ValueLabel label) -> {
			StringBuilder text = label.getText();
			int           i    = text.length();
			label.addDrawRun(i, i + 1, DrawType.icon, Color.white, iconFunc.get(val));
			text.append('□');
			defaultAppend(label, i, val);
			return true;
		};
	}
	public interface JSCode {
		String getJSCode();
	}

	public static class ViewerItem<T> {
		public Viewer<T>     viewer;
		public Type          type;
		public Seq<Class<?>> classes;
		public Boolf<T>      valid;
		private ViewerItem() { }/* 用于序列化 */
		public ViewerItem(Type type, Viewer<T> viewer) {
			this.viewer = viewer;
			this.type = type;
		}
		public ViewerItem(Class<?> clazz, Viewer<T> viewer) {
			this.viewer = viewer;
			this.classes = new Seq<>();
			classes.add(clazz);
		}
		public ViewerItem(Viewer<T> viewer, Class<?>... classes) {
			this.viewer = viewer;
			this.classes = new Seq<>(classes);
		}
		public boolean valid(T val) {
			if (viewer == null) return false;
			return (type != null && type.valid.get(val))
			       || (valid != null && valid.get(val))
			       || (classes != null && classes.indexOf(c -> c.isInstance(val)) != -1);
		}
		public static final int maxDepth = 10;

		public int currentDepth = 0;
		public boolean view(T val, ValueLabel label) {
			if (currentDepth > maxDepth) return false;
			currentDepth++;
			try {
				return viewer.view(val, label);
			} catch (Throwable e) {
				Log.err("Viewer Error", e);
				return false;
			} finally {
				currentDepth--; // 绝对对称
			}
		}

		public boolean equals(Object o) {
			if (!(o instanceof ViewerItem<?> that)) return false;
			return Objects.equals(viewer, that.viewer) && type == that.type && Objects.equals(classes, that.classes) && Objects.equals(valid, that.valid);
		}
		public int hashCode() {
			return Objects.hash(viewer, type, classes, valid);
		}
		public String toString() {
			return "ViewerItem{" +
			       "viewer=" + viewer +
			       ", type=" + type +
			       ", classes=" + classes +
			       ", valid=" + valid +
			       '}';
		}
	}
	public interface Viewer<T> {
		boolean view(T val, ValueLabel label); /* 是否成功 */
	}
	static class IterCons extends AllCons implements Poolable {
		private ValueLabel    self;
		private Object        val;
		private StringBuilder text;

		private int     count;
		private boolean gotFirst;
		private int     index;
		public int size() {
			if (index == -1) throw new IllegalStateException("size() must be called after forEach()");
			return index;
		}
		public IterCons init(ValueLabel self, Object val, StringBuilder text) {
			this.self = self;
			this.val = val;
			this.text = text;
			this.index = 0;
			return this;
		}
		private Object last;
		public void get(Object item) {
			if (!gotFirst) {
				gotFirst = true;
				last = item;
			}
			// Log.info("item = " + item);
			checkCount();
			if (item != null) {
				self.valToObj.put(item, val);
				self.valToType.put(item, val.getClass());
			}

			boolean b = (last != null && identityClasses.contains(val.getClass()))
			 ? !last.equals(item) : last != item;
			if (b) {
				append(item);
			} else {
				count++;
			}
		}

		public static class DelegteProv implements Prov<Point2> {
			public Prov<Point2> prov;
			public DelegteProv(Prov<Point2> p) {
				this.prov = p;
			}
			@Override
			public Point2 get() {
				return prov.get();
			}
		}
		private Runnable afterAppend;
		private void checkCount() {
			// 必须是toplevel级别
			if (val != self.val || index < self.maxItemCount) {
				index++;
				return;
			}

			// 再展开64个元素
			afterAppend = () -> {
				self.startColor(Color.gray);
				text.append("\n...");
				int start = text.length();
				//noinspection StringTemplateMigration
				text.append("[Expand " + ValueLabel.STEP_SIZE + " more elements]");
				self.postAppendDelimiter();
				self.endColor();
				int end = text.length();
				for (Entry<Prov<Point2>, Runnable> click : self.clicks) {
					if (click.key instanceof DelegteProv p) {
						p.prov = () -> Tmp.p1.set(start, end);
					}
				}
			};

			throw new SatisfyException();
		}
		public void append(Object item) {
			if (count == 0) return;
			self.postAppendDelimiter();
			self.appendValue(last);
			self.addCountText(count);

			if (afterAppend != null) afterAppend.run();
			if (self.isTruncate()) throw new SatisfyException();
			last = item;
			count = 1;
		}
		private long llast;
		public void get(long item) {
			if (!gotFirst) {
				gotFirst = true;
				llast = item;
			}
			checkCount();

			if (item != llast) {
				append(item);
			} else {
				count++;
			}
		}
		public void append(long item) {
			if (count == 0) return;
			self.postAppendDelimiter();
			self.appendValue(llast);
			self.addCountText(count);
			if (afterAppend != null) afterAppend.run();
			if (self.isTruncate()) throw new SatisfyException();
			llast = item;
			count = 1;
		}
		private double dlast;
		public void get(double item) {
			if (!gotFirst) {
				gotFirst = true;
				dlast = item;
			}
			checkCount();

			if (item != dlast) {
				append(item);
			} else {
				count++;
			}
		}
		public void append(double item) {
			if (count == 0) return;
			self.postAppendDelimiter();
			self.appendValue(dlast);
			self.addCountText(count);
			if (afterAppend != null) afterAppend.run();
			if (self.isTruncate()) throw new SatisfyException();
			dlast = item;
			count = 1;
		}
		private boolean zlast;
		public void get(boolean item) {
			if (!gotFirst) {
				gotFirst = true;
				zlast = item;
			}
			checkCount();

			if (item != zlast) {
				append(item);
			} else {
				count++;
			}
		}
		public void append(boolean item) {
			if (count == 0) return;
			self.postAppendDelimiter();
			self.appendValue(zlast);
			self.addCountText(count);
			if (afterAppend != null) afterAppend.run();
			if (self.isTruncate()) throw new SatisfyException();
			zlast = item;
			count = 1;
		}
		private char clast;
		public void get(char item) {
			if (!gotFirst) {
				gotFirst = true;
				clast = item;
			}
			checkCount();

			if (item != clast) {
				append(item);
			} else {
				count++;
			}
		}
		public void append(char item) {
			if (count == 0) return;
			self.postAppendDelimiter();
			self.appendValue(clast);
			self.addCountText(count);
			if (afterAppend != null) afterAppend.run();
			if (self.isTruncate()) throw new SatisfyException();
			clast = item;
			count = 1;
		}


		public void reset() {
			last = null;
			self = null;
			index = 0;
			count = 0;
			afterAppend = null;
			gotFirst = false;
		}
	}

	/** @see Type#map */
	static int getMapSize(Object val) {
		return switch (val) {
			case ObjectMap<?, ?> map -> map.size;
			case IntMap<?> map -> map.size;
			case IntIntMap map -> map.size;
			case IntFloatMap map -> map.size;
			case LongMap<?> map -> map.size;
			case ObjectIntMap<?> map -> map.size;
			case ObjectFloatMap<?> map -> map.size;
			case Map<?, ?> map -> map.size();
			default -> FieldUtils.getInt(val, "size", 0);
		};
	}
	static int getArraySize(Object val) {
		if (val.getClass().isArray()) return Array.getLength(val);

		return switch (val) {
			case Seq<?> seq -> seq.size;
			case IntSeq seq -> seq.size;
			case FloatSeq seq -> seq.size;
			case LongSeq seq -> seq.size;
			case List<?> list -> list.size();
			case Iterable<?> iter -> FieldUtils.getInt(iter, "size", -1);

			default -> throw new UnsupportedOperationException();
		};
	}
	public static void defaultAppend(ValueLabel label, int startIndex, Object val) {
		StringBuilder text      = label.getText();
		Color         mainColor = colorOf(val);
		label.startColor(mainColor);
		label.startIndexMap.put(startIndex, val);
		text.append(toString(val));
		int endI = text.length();
		label.endIndexMap.put(startIndex, endI);
		label.endColor();
	}
	private static Object wrapVal(Object val) {
		if (val == null) return ValueLabel.NULL_MARK;
		return val;
	}
	public static Color colorOf(Object val) {
		return val == null ? Syntax.c_objects
		 : val instanceof String || val instanceof Character ? Syntax.c_string
		 : val instanceof Number ? Syntax.c_number
		 : val instanceof Class ? TmpVars.c1.set(JColor.c_type)
		 : val.getClass().isEnum() ? ValueLabel.c_enum
		 : box(val.getClass()) == Boolean.class ? Syntax.c_keyword
		 : Color.white;
	}
	static String toString(Object val) {
		return CatchSR.apply(() ->
		 CatchSR.of(() ->
			 val instanceof String ? '"' + (String) val + '"'
				: val instanceof Character ? STR."'\{val}'"
				: val instanceof Float || val instanceof Double ? FormatHelper.fixed(((Number) val).floatValue(), 2)
				: val instanceof Class ? ((Class<?>) val).getSimpleName()

				: val instanceof Element ? ReviewElement.getElementName((Element) val)
				: FormatHelper.getUIKey(val))
			.get(() -> String.valueOf(val))
			/** @see Objects#toIdentityString(Object)  */
			.get(() -> Tools.clName(val) + "@" + Integer.toHexString(System.identityHashCode(val)))
			.get(() -> Tools.clName(val))
		);
	}
	static String getArrayDelimiter() {
		return R_JSFunc.array_delimiter;
	}

	public enum Type {
		/** @see #getMapSize(Object) */
		map(ObjectMap.class, IntMap.class, IntIntMap.class, IntFloatMap.class,
		 LongMap.class,
		 ObjectIntMap.class, ObjectFloatMap.class, Map.class),
		array(o -> o instanceof Iterable<?> ||
		           (o instanceof IntSeq || o instanceof FloatSeq || o instanceof LongSeq) ||
		           (o != null && o.getClass().isArray()));

		public final Boolf<Object> valid;
		Type(Class<?>... classes) {
			Seq<Class<?>> classSeq = Seq.with(classes);
			valid = o -> o != null && classSeq.indexOf(c -> c.isAssignableFrom(o.getClass())) != -1;
		}
		Type(Boolf<Object> valid) {
			this.valid = valid;
		}
	}
}
