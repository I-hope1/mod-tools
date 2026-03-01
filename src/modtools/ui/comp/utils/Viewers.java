package modtools.ui.comp.utils;

import arc.files.Fi;
import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.math.geom.*;
import arc.scene.Element;
import arc.struct.*;
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
import modtools.ui.comp.utils.ValueLabel.DelegteProv;
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
	private static final int     SIZE_MAX_BIT = 6;

	/** 每层缩进使用的括号颜色，循环取用 */
	private static final Color[] PRETTY_BRACKET_COLORS = {
	 new Color(0xFFD700FF), // 金色
	 new Color(0xDA70D6FF), // 兰花紫
	 new Color(0x4FC3F7FF), // 浅蓝
	 new Color(0xA5D6A7FF), // 浅绿
	};

	/** 预缓存常用深度的缩进字符串，避免每次 repeat 产生新对象 */
	private static final int      INDENT_CACHE_SIZE = 16;
	private static final String[] INDENT_CACHE;

	static {
		INDENT_CACHE = new String[INDENT_CACHE_SIZE];
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < INDENT_CACHE_SIZE; i++) {
			INDENT_CACHE[i] = sb.toString();
			sb.append("  ");
		}
	}

	private static String prettyIndent(int depth) {
		if (depth <= 0) return "";
		if (depth < INDENT_CACHE_SIZE) return INDENT_CACHE[depth];
		return "  ".repeat(depth); // 超出缓存范围（极深嵌套）才真正 repeat
	}
	private static Color prettyBracketColor(int depth) {
		return PRETTY_BRACKET_COLORS[depth % PRETTY_BRACKET_COLORS.length];
	}

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

			// 提取 entries 遍历，避免在 pretty/普通 两个分支中重复
			Runnable runEntries = () -> {
				switch (val) {
					case ObjectMap<?, ?> m -> appendMap(val, label, m.entries(), e -> e.key, e -> e.value);
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
					case Map<?, ?> m -> appendMap(val, label, m.entrySet(), Map.Entry::getKey, Map.Entry::getValue);
					default -> throw new UnsupportedOperationException();
				}
			};

			if (R_JSFunc.pretty_print) {
				int    depth       = label.prettyDepth;
				String baseIndent  = prettyIndent(depth);
				String entryIndent = prettyIndent(depth + 1);
				Color  bColor      = prettyBracketColor(depth);
				// 彩色左括号
				text.append('\n');
				label.startColor(bColor);
				text.append('{');
				label.endColor();
				// 第一个 entry 前只需换行+缩进；后续 entry 前追加 ",\n<indent>"
				label.overrideDelimiter = () -> text.append(",\n").append(entryIndent);
				label.postAppendDelimiter(() -> text.append('\n').append(entryIndent));
				label.prettyDepth = depth + 1;
				runEntries.run();
				label.prettyDepth = depth;
				label.overrideDelimiter = null;
				// 彩色右括号（前加换行+基础缩进）
				text.append('\n').append(baseIndent);
				label.startColor(bColor);
				text.append('}');
				label.endColor();
			} else {
				label.postAppendDelimiter(null);
				text.append("\n{");
				runEntries.run();
				text.append('}');
			}
			// 直接赋值而非 postAppendDelimiter(prev)：后者会把最后一个 entry 的分隔符
			// 注入到 '}' 之后（如 "{k=v, k=v}, "），造成尾部多余分隔符。
			label.appendTail = prev;
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

			Pool<IterCons> pool = Pools.get(IterCons.class, IterCons::new, 50);
			IterCons       cons = pool.obtain().init(label, val, text);
			Runnable       prev = label.appendTail;

			// pretty-print: 设置彩色括号和换行缩进
			int     ppDepth     = label.prettyDepth;
			boolean prettyPrint = R_JSFunc.pretty_print;
			String  baseIndent  = prettyPrint ? prettyIndent(ppDepth) : "";
			String  entryIndent = prettyPrint ? prettyIndent(ppDepth + 1) : "";
			Color   bColor      = prettyPrint ? prettyBracketColor(ppDepth) : null;

			if (prettyPrint) {
				text.append('\n');
				label.startColor(bColor);
				text.append('[');
				label.endColor();
				label.overrideDelimiter = () -> text.append(",\n").append(entryIndent);
				label.postAppendDelimiter(() -> text.append('\n').append(entryIndent));
				label.prettyDepth = ppDepth + 1;
			} else {
				text.append("\n[");
				label.postAppendDelimiter(null);
			}
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
			} catch (ArcRuntimeException e) {
				if ("#iterator() cannot be used nested.".equals(e.getMessage())) {
					defaultAppend(label, start, val);
				} else {
					handleError(e, text);
				}
				return true;
			} catch (Throwable e) {
				handleError(e, text);
			} finally {
				// append[0] 必须先于 prev 恢复运行：它负责输出最后一个缓冲元素。
				// 恢复时直接赋值而非调用 postAppendDelimiter，避免触发末尾多余分隔符，
				// 也避免把外层 prev 注入到数组内部（即 "a, <outer_delim>b]" 的 bug）。
				try {
					if (append[0] != null) append[0].run();
				} catch (SatisfyException ignored) {
				} catch (Throwable e) {
					Log.err(e);
				}
				label.appendTail = prev; // 丢弃末尾多余分隔符，还原外层上下文
				if (prettyPrint) {
					label.prettyDepth = ppDepth;
					label.overrideDelimiter = null;
				}
				// 自动补位：格式化结果必须严格等于 SIZE_MAX_BIT 个字符，否则 text.replace
				// 会插入多余字符，把之后所有 colorMap / startIndexMap / endIndexMap 的下标
				// 全部错位（静默破坏，不报错）。
				// 用 %-5s 截断到固定宽度：超过 99999 时显示 "9999+"（含前导空格仍为5字符）。
				int    size = cons.size();
				String sizeStr;
				if (size < (int) Math.pow(10, SIZE_MAX_BIT)) {
					sizeStr = String.format("%" + SIZE_MAX_BIT + "d", size); // 右对齐，宽度固定
				} else {
					sizeStr = String.format("%" + (SIZE_MAX_BIT - 1) + "d+", // 最后一位留给 '+'
					 (int) Math.pow(10, SIZE_MAX_BIT - 1) - 1); // e.g. "99999+"
				}
				// assert sizeStr.length() == SIZE_MAX_BIT;
				text.replace(sizeIndex, sizeIndex + SIZE_MAX_BIT, sizeStr);
				pool.free(cons);
			}
			if (prettyPrint) {
				text.append('\n').append(baseIndent);
				label.startColor(bColor);
				text.append(']');
				label.endColor();
			} else {
				text.append(']');
			}

			if (chunk_background.enabled()) label.addDrawRun(start, text.length(), DrawType.background, label.bgColor());
			// setColor(Color.white);
			return true;
		});
	}

	private static void handleError(Throwable e, StringBuilder text) {
		if (ARRAY_DEBUG) Log.err(e);
		text.append("▶ERROR◀:").append(e.getMessage());
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
			// '□' 是占位符，必须设为透明；否则字形以白色渲染在图标底层，
			// 两者尺寸/位置不完全吻合时 '□' 会漏出（insertIcon 已有此处理，这里补上）。
			label.colorMap.put(i, Color.clear);
			label.addDrawRun(i, i + 1, DrawType.icon, Color.white, iconFunc.get(val));
			text.append('□');
			// defaultAppend 内部会调用 startColor(mainColor)，在 i+1 处写入正常颜色，
			// 因此只需在 i 处设 clear，无需再手动恢复。
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
				self.valToType.put(item, item.getClass()); // fix: 应存元素自身的类型，而非父集合的类型
			}

			boolean b = (last != null && identityClasses.contains(last.getClass())) // fix: 应检查元素类型，而非父集合类型
			 ? !last.equals(item) : last != item;
			if (b) {
				append(item);
			} else {
				count++;
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
				if (!self.expandMoreClick.containsKey(val)) {
					DelegteProv prov = new DelegteProv();
					prov.start = start;
					prov.end = end;
					self.expandMoreClick.put(val, prov);
					var self0 = self;
					self.clickedRegion(prov, () -> {
						self0.maxItemCount += ValueLabel.STEP_SIZE;
						self0.flushValExpand();
					});
				} else {
					DelegteProv prov = self.expandMoreClick.get(val);
					prov.start = start;
					prov.end = end;
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
		// 统一在此设置 delimiter tail，避免各 viewer 各自调用导致遗漏：
		// appendValue(Object) 开头会 appendTail = null，若 viewer 提前 return 而不经过此处，
		// IterCons.append() 已压入的 lambda 就永远死在 null 里，导致元素间没有分隔符。
		label.postAppendDelimiter();
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