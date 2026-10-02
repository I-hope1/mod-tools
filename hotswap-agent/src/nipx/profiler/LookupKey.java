package nipx.profiler;

/** 自定义可复用 LookupKey，内嵌 StringBuilder 实现零字符串分配的 Map 查找与存储 */
public final class LookupKey implements CharSequence, Comparable<LookupKey> {

	public final StringBuilder sb;
	private int     hash;
	private boolean hashComputed;

	public LookupKey() {
		this(128);
	}

	public LookupKey(int capacity) {
		this.sb = new StringBuilder(capacity);
		this.hash = 0;
		this.hashComputed = false;
	}

	public LookupKey(CharSequence seq) {
		this(seq.length());
		this.sb.append(seq);
		this.hashCode();
	}

	/** 重置 key，准备下一次复用 */
	public LookupKey reset() {
		sb.setLength(0);
		hash = 0;
		hashComputed = false;
		return this;
	}

	public LookupKey append(String str) {
		sb.append(str);
		hashComputed = false;
		return this;
	}

	public LookupKey append(CharSequence s) {
		sb.append(s);
		hashComputed = false;
		return this;
	}

	public LookupKey append(CharSequence s, int start, int end) {
		sb.append(s, start, end);
		hashComputed = false;
		return this;
	}

	public LookupKey append(char c) {
		sb.append(c);
		hashComputed = false;
		return this;
	}

	public LookupKey append(int i) {
		sb.append(i);
		hashComputed = false;
		return this;
	}

	public LookupKey append(long l) {
		sb.append(l);
		hashComputed = false;
		return this;
	}

	/** 零 GC 追加无符号 16 进制 long 值 */
	public LookupKey appendHex(long val) {
		if (val == 0) {
			sb.append('0');
			hashComputed = false;
			return this;
		}
		int shift = 60;
		while (shift > 0 && ((val >>> shift) & 0xF) == 0) {
			shift -= 4;
		}
		while (shift >= 0) {
			int digit = (int) ((val >>> shift) & 0xF);
			sb.append((char) (digit < 10 ? '0' + digit : 'a' + digit - 10));
			shift -= 4;
		}
		hashComputed = false;
		return this;
	}

	/** 创建当前内容的永久副本，用于插入 Map 作为持久 key */
	public LookupKey copy() {
		LookupKey copy = new LookupKey(this.sb.length());
		copy.sb.append(this.sb);
		copy.hash = this.hashCode();
		copy.hashComputed = true;
		return copy;
	}

	@Override
	public int length() {
		return sb.length();
	}

	@Override
	public char charAt(int index) {
		return sb.charAt(index);
	}

	@Override
	public CharSequence subSequence(int start, int end) {
		return sb.subSequence(start, end);
	}

	@Override
	public int hashCode() {
		if (!hashComputed) {
			int h   = 0;
			int len = sb.length();
			for (int i = 0; i < len; i++) {
				h = 31 * h + sb.charAt(i);
			}
			hash         = h;
			hashComputed = true;
		}
		return hash;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) return true;
		if (obj instanceof LookupKey) {
			LookupKey other = (LookupKey) obj;
			if (this.hashCode() != other.hashCode()) return false;
			StringBuilder b1 = this.sb;
			StringBuilder b2 = other.sb;
			int len = b1.length();
			if (len != b2.length()) return false;
			for (int i = 0; i < len; i++) {
				if (b1.charAt(i) != b2.charAt(i)) return false;
			}
			return true;
		}
		if (obj instanceof String) {
			String s = (String) obj;
			if (this.hashCode() != s.hashCode()) return false;
			int len = sb.length();
			if (len != s.length()) return false;
			for (int i = 0; i < len; i++) {
				if (sb.charAt(i) != s.charAt(i)) return false;
			}
			return true;
		}
		return false;
	}

	@Override
	public int compareTo(LookupKey o) {
		StringBuilder b1 = this.sb;
		StringBuilder b2 = o.sb;
		int len1 = b1.length();
		int len2 = b2.length();
		int lim = Math.min(len1, len2);
		for (int k = 0; k < lim; k++) {
			char c1 = b1.charAt(k);
			char c2 = b2.charAt(k);
			if (c1 != c2) return c1 - c2;
		}
		return len1 - len2;
	}

	@Override
	public String toString() {
		return sb.toString();
	}
}
