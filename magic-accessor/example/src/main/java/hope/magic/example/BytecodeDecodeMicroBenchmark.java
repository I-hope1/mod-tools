package hope.magic.example;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BytecodeDecodeMicroBenchmark {

	private byte[] sampleBytes;
	private String base64String;
	private String iso88591String;

	@Setup
	public void setup() {
		// 模拟约 1KB 的 MagicBridge 字节码
		sampleBytes = new byte[1024];
		for (int i = 0; i < sampleBytes.length; i++) {
			sampleBytes[i] = (byte) (i & 0xFF);
		}
		base64String = Base64.getEncoder().encodeToString(sampleBytes);
		iso88591String = new String(sampleBytes, StandardCharsets.ISO_8859_1);
	}

	@Benchmark
	public byte[] benchmark_base64_decode() {
		return Base64.getDecoder().decode(base64String);
	}

	@Benchmark
	public byte[] benchmark_iso88591_getBytes() {
		return iso88591String.getBytes(StandardCharsets.ISO_8859_1);
	}

	public static void main(String[] args) throws Exception {
		Options opt = new OptionsBuilder()
			.include(BytecodeDecodeMicroBenchmark.class.getSimpleName())
			.build();
		new Runner(opt).run();
	}
}
