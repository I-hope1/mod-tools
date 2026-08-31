package hope.magic.js.runtime;

public final class JSUndefined {
	public static final JSUndefined INSTANCE = new JSUndefined();

	private JSUndefined() {}

	@Override
	public String toString() {
		return "undefined";
	}
}
