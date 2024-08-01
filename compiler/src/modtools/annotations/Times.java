package modtools.annotations;

import modtools.annotations.PrintHelper.SPrinter;

import java.util.*;

/** @see arc.util.Time  */
public interface Times {
	Stack<Long> marks = new Stack<>();
	static void mark() {
		marks.push(System.nanoTime());
	}

	/** A value of -1 means mark() wasn't called beforehand. */
	static float elapsed() {
		if (marks.isEmpty()) {
			return -1;
		} else {
			return timeSinceNanos(marks.pop()) / 1000000f;
		}
	}
	/**
	 * Get the time in nanos passed since a previous time
	 * @param prevTime - must be nanoseconds
	 * @return - time passed since prevTime in nanoseconds
	 */
	static long timeSinceNanos(long prevTime) {
		return System.nanoTime() - prevTime;
	}

	static void printElapsed(Object prev, String text, Object ...args) {
		ArrayList<Object> list = new ArrayList<>(List.of(args));
		list.add(0, elapsed());
		list.add(0, prev);
		SPrinter.println(text, list.toArray());
	}

	static void printElapsed(String text, Object ...args) {
		ArrayList<Object> list = new ArrayList<>(List.of(args));
		list.add(0, elapsed());
		SPrinter.println(text, list.toArray());
	}
}
