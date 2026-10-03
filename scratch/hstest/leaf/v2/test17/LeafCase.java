package test17;

/**
 * v2：开头插入一个新的叶子 lambda（gamma），A/B 源码一字未改。
 * 期望：A 认回 $0、B 认回 $1、新的拿新名字，不得互相抢。
 */
public class LeafCase {

	public void run(Runnable r) { }

	public void build() {
		run(() -> gamma());
		run(() -> alpha());
		run(() -> beta());
	}

	void alpha() { }
	void beta() { }
	void gamma() { }
}
