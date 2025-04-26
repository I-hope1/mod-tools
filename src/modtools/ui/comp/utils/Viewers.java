package modtools.ui.comp.utils;

import arc.func.*;
import arc.graphics.Color;
import arc.graphics.g2d.*;
import arc.struct.*;
import arc.util.*;
import arc.util.pooling.*;
import arc.util.pooling.Pool.Poolable;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.*;
import mindustry.world.Tile;
import modtools.ui.comp.input.ExtendingLabel.DrawType;
import modtools.utils.ArrayUtils;
import modtools.utils.ArrayUtils.AllCons;
import modtools.utils.SR.SatisfyException;
import modtools.utils.world.*;

import java.util.Map;

import static modtools.events.E_JSFunc.chunk_background;
import static modtools.ui.comp.input.highlight.Syntax.c_map;

public class Viewers {
	public static final ObjectMap<Class<?>, Viewer<?>> map     = new ObjectMap<>();
	public static final ObjectMap<Type, Viewer<?>>     typeMap = new ObjectMap<>();

	public static final boolean ARRAY_DEBUG = false;
	static int getSize(Object val) {
		return switch (val) {
			case ObjectMap<?, ?> map -> map.size;
			case IntMap<?> map -> map.size;
			case ObjectFloatMap<?> map -> map.size;
			case Map<?, ?> map -> map.size();
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
		array(o -> o instanceof Iterable<?> || (o != null && o.getClass().isArray()));
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
		map.put(Bullet.class, iconViewer((Bullet b) -> WorldDraw.drawRegion((int) (b.hitSize * 1.8), (int) (b.hitSize * 1.8), () -> {
			float x = b.x, y = b.y;
			b.x = b.hitSize * 0.9f;
			b.y = b.hitSize * 0.9f;
			Draw.color();
			Fill.crect(0,0,100, 100);
			b.draw();
			b.x = x;
			b.y = y;
		})));
		map.put(UnlockableContent.class, iconViewer((UnlockableContent uc) -> uc.uiIcon));


		typeMap.put(Type.map, (val, label) -> {
			if (!label.expandMap.containsKey(val)) {
				label.clickedRegion(label.getPoint2Prov(val), () -> label.toggleExpand(val));
				label.expandMap.put(val, false);
			}
			StringBuilder text  = label.getText();
			int           start = text.length();
			label.startIndexMap.put(start, val);
			label.colorMap.put(start, c_map);
			text.append("|Map ").append(getSize(val)).append('|');
			label.colorMap.put(text.length(), Color.white);
			label.endIndexMap.put(val, text.length() - 1);

			if (!label.expandMap.get(val, false)) {
				return Result.success;
			}
			text.append('\n');
			text.append('{');
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
			StringBuilder text  = label.getText();
			int           start = text.length();
			text.append('[');

			Pool<IterCons> pool = Pools.get(IterCons.class, IterCons::new, 50);
			IterCons       cons = pool.obtain().init(label, val, text);
			try {
				try {
					Runnable prev = label.appendTail;
					label.appendTail = null;
					if (val instanceof Iterable<?> iter) {
						for (Object item : iter) {
							cons.get(item);
						}
						cons.append(null);
					} else {
						ArrayUtils.forEach(val, cons);
					}
					label.appendTail = prev;
				} catch (SatisfyException ignored) { }
			} catch (ArcRuntimeException ignored) {
				return Result.error;
			} catch (Throwable e) {
				if (ARRAY_DEBUG) Log.err(e);
				text.append("▶ERROR◀");
			} finally {
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
		private Object        val;
		private StringBuilder text;

		private int        count;
		private boolean    gotFirst;
		private ValueLabel self;
		public IterCons init(ValueLabel self, Object val, StringBuilder text) {
			this.self = self;
			this.val = val;
			this.text = text;
			return this;
		}
		private Object last;
		public void get(Object item) {
			if (!gotFirst) {
				gotFirst = true;
				last = item;
			}
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
		public void append(Object item) {
			if (count == 0) return;
			self.postAppendDelimiter(text);
			self.appendValue(text, last);
			self.addCountText(text, count);
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
			if (self.isTruncate(text.length())) throw new SatisfyException();
			clast = item;
			count = 1;
		}


		public void reset() {
			last = null;
			self = null;
			count = 0;
			gotFirst = false;
		}
	}
}
