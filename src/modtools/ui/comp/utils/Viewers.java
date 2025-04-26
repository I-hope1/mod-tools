package modtools.ui.comp.utils;

import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.TextureRegion;
import arc.math.geom.Point2;
import arc.struct.*;
import arc.struct.ObjectMap.Entry;
import arc.util.*;
import arc.util.pooling.*;
import arc.util.pooling.Pool.Poolable;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.*;
import mindustry.world.Tile;
import modtools.events.R_JSFunc;
import modtools.ui.comp.input.ExtendingLabel.DrawType;
import modtools.ui.comp.input.InlineLabel;
import modtools.utils.*;
import modtools.utils.ArrayUtils.AllCons;
import modtools.utils.SR.SatisfyException;
import modtools.utils.reflect.FieldUtils;
import modtools.utils.world.WorldUtils;

import java.lang.reflect.Array;
import java.util.*;

import static modtools.events.E_JSFunc.chunk_background;
import static modtools.ui.comp.input.highlight.Syntax.c_map;

public class Viewers {
	public static final ObjectMap<Class<?>, Viewer<?>> map     = new ObjectMap<>();
	public static final ObjectMap<Type, Viewer<?>>     typeMap = new ObjectMap<>();

	public static final  boolean ARRAY_DEBUG           = false;
	private static final int     SIZE_MAX_BIT          = 5;
	public static final  boolean ENABLE_MAX_ITEM_COUNT = true;

	static int getMapSize(Object val) {
		return switch (val) {
			case ObjectMap<?, ?> map -> map.size;
			case IntMap<?> map -> map.size;
			case ObjectFloatMap<?> map -> map.size;
			case Map<?, ?> map -> map.size();
			default -> throw new UnsupportedOperationException();
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
	public static boolean testHashCode(Object object) {
		try {
			object.hashCode();
			return true;
		} catch (Throwable e) {
			return false;
		}
	}

	public enum Type {
		map(ObjectMap.class, IntMap.class,
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
	public enum Result {
		success,
		skip,
		error,
		unknown
	}

	static {
		// map.put(String.class, (val, label) -> {
		// 	label.appendValue(label.getText(), val);
		// 	return true;
		// });
		map.put(Color.class, (Color val, ValueLabel label) -> {
			StringBuilder text = label.getText();
			label.colorMap.put(text.length(), val);
			text.append('■');
			return Result.skip;
		});
		map.put(Building.class, iconViewer((Building b) -> b.block.uiIcon));
		map.put(Unit.class, iconViewer((Unit u) -> u.type().uiIcon));
		map.put(Tile.class, iconViewer((Tile t) -> WorldUtils.getToDisplay(t).uiIcon));
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
		map.put(UnlockableContent.class, iconViewer((UnlockableContent uc) -> uc.uiIcon));


		typeMap.put(Type.map, (val, label) -> {
			if (!label.expandVal.containsKey(val)) {
				label.clickedRegion(label.getPoint2Prov(val), () -> label.toggleExpand(val));
				label.expandVal.put(val, getMapSize(val) < R_JSFunc.min_expand_size);
			}
			StringBuilder text  = label.getText();
			int           start = text.length();
			label.startIndexMap.put(start, val);
			label.colorMap.put(start, c_map);
			text.append("|Map ").append(getMapSize(val)).append('|');
			label.colorMap.put(text.length(), Color.white);
			label.endIndexMap.put(val, text.length() - 1);

			if (!label.expandVal.get(val)) {
				return Result.success;
			}
			text.append("\n{");
			Runnable prev = label.appendTail;
			label.appendTail = null;
			switch (val) {
				case ObjectMap<?, ?> m -> appendMap(val, label, text, m, e -> e.key, e -> e.value);
				case IntMap<?> m -> appendMap(val, label, text, m, e -> e.key, e -> e.value);
				case ObjectFloatMap<?> m -> appendMap(val, label, text, m, e -> e.key, e -> e.value);
				case Map<?, ?> m -> appendMap(val, label, text, m.entrySet(), Map.Entry::getKey, Map.Entry::getValue);
				default -> throw new UnsupportedOperationException();
			}
			label.appendTail = prev;
			text.append('}');

			if (chunk_background.enabled()) label.addDrawRun(start, text.length(), DrawType.background, label.bgColor());

			return Result.success;
		});
		typeMap.put(Type.array, (val, label) -> {
			if (!label.expandVal.containsKey(val)) {
				label.clickedRegion(label.getPoint2Prov(val), () -> label.toggleExpand(val));
				label.expandVal.put(val, getArraySize(val) < R_JSFunc.min_expand_size);
			}
			StringBuilder text  = label.getText();
			int           start = text.length();
			label.startIndexMap.put(start, val);
			label.colorMap.put(start, c_map);
			text.append("|Array ");
			int sizeIndex = text.length();
			text.append(StringUtils.repeat(" ", SIZE_MAX_BIT)).append("|");
			label.colorMap.put(text.length(), Color.white);
			label.endIndexMap.put(val, text.length() - 1);

			if (!label.expandVal.get(val)) {
				return Result.success;
			}

			text.append("\n[");

			Pool<IterCons> pool = Pools.get(IterCons.class, IterCons::new, 50);
			IterCons       cons = pool.obtain().init(label, val, text);
			Runnable       prev = label.appendTail;
			label.appendTail = null;
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
				return Result.error;
			} catch (Throwable e) {
				if (ARRAY_DEBUG) Log.err(e);
				text.append("▶ERROR◀");
			} finally {
				label.appendTail = prev;
				try {
					if (append[0] != null) append[0].run();
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
			return Result.success;
		});
	}


	private static <M, K, V> void appendMap(Object val, ValueLabel label,
	                                        StringBuilder text, Iterable<M> map, Func<M, K> keyF, Func<M, V> valueF) {
		for (var entry : map) {
			label.appendMap(text, val, keyF.get(entry), valueF.get(entry));
			if (label.isTruncate(text.length())) break;
		}
	}

	private static <T> Viewer<T> iconViewer(Func<T, TextureRegion> iconFunc) {
		return (T t, ValueLabel label) -> {
			StringBuilder text = label.getText();
			int           i    = text.length();
			label.addDrawRun(i, i + 1, DrawType.icon, Color.white, iconFunc.get(t));
			text.append('□');
			return Result.skip;
		};
	}

	public interface Viewer<T> {
		Result view(T val, ValueLabel label);
	}
	static class IterCons extends AllCons implements Poolable {
		private ValueLabel    self;
		private Object        val;
		private StringBuilder text;

		private int     objId;
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
			this.objId = self.objId;
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
			checkCount();
			if (item != null) {
				self.valToObj.put(item, val);
				self.valToType.put(item, val.getClass());
			}

			boolean b = (last != null && ValueLabel.identityClasses.contains(val.getClass()))
			 ? !last.equals(item) : last != item;
			if (b) {
				append(item);
			} else {
				count++;
			}
		}

		public static class Point2Prov implements Prov<Point2> {
			public static final int STEP_SIZE = 64;

			public int          maxItemCount = STEP_SIZE;
			public int          objId;
			public Prov<Point2> prov;
			public Point2Prov(int objId, Prov<Point2> p) {
				this.objId = objId;
				this.prov = p;
			}
			@Override
			public Point2 get() {
				return prov.get();
			}
		}
		public static int getMaxCount(ValueLabel label, int objId) {
			if (ENABLE_MAX_ITEM_COUNT) return Integer.MAX_VALUE;

			for (Entry<Prov<Point2>, Runnable> click : label.clicks) {
				if (click.key instanceof Point2Prov p && p.objId == objId) {
					return p.maxItemCount;
				}
			}
			Point2Prov p = new Point2Prov(objId, () -> InlineLabel.UNSET_P);
			label.clickedRegion(p, () -> {
				p.maxItemCount += 64;
				// Log.info(p.maxItemCount);
			});
			return p.maxItemCount;
		}
		private Runnable afterAppend;
		private void checkCount() {
			if (index < getMaxCount(self, objId)) {
				index++;
				return;
			}

			// 再展开64个元素
			afterAppend = () -> {
				self.colorMap.put(text.length(), Color.gray);
				text.append("\n...");
				int start = text.length();
				text.append("[Expand " + Point2Prov.STEP_SIZE + " more elements]");
				self.postAppendDelimiter(text);
				self.colorMap.put(text.length(), Color.white);
				int end = text.length();
				for (Entry<Prov<Point2>, Runnable> click : self.clicks) {
					if (click.key instanceof Point2Prov p && p.objId == objId) {
						p.prov = () -> Tmp.p1.set(start, end);
					}
				}
			};

			throw new SatisfyException();
		}
		public void append(Object item) {
			if (count == 0) return;
			self.postAppendDelimiter(text);
			self.appendValue(text, last);
			self.addCountText(text, count);

			if (afterAppend != null) afterAppend.run();
			if (self.isTruncate(text.length())) throw new SatisfyException();
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
			self.postAppendDelimiter(text);
			self.appendValue(text, llast);
			self.addCountText(text, count);
			if (afterAppend != null) afterAppend.run();
			if (self.isTruncate(text.length())) throw new SatisfyException();
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
			self.postAppendDelimiter(text);
			self.appendValue(text, dlast);
			self.addCountText(text, count);
			if (afterAppend != null) afterAppend.run();
			if (self.isTruncate(text.length())) throw new SatisfyException();
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
			self.postAppendDelimiter(text);
			self.appendValue(text, zlast);
			self.addCountText(text, count);
			if (afterAppend != null) afterAppend.run();
			if (self.isTruncate(text.length())) throw new SatisfyException();
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
			self.postAppendDelimiter(text);
			self.appendValue(text, clast);
			self.addCountText(text, count);
			if (afterAppend != null) afterAppend.run();
			if (self.isTruncate(text.length())) throw new SatisfyException();
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
}
