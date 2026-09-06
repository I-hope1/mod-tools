package hope.magic.js.test;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

public class Test262BatchRunnerTest {

	@Test
	@DisplayName("TC39 Test262: In-Process Batch Runner on official test resources")
	public void testBatchRunnerOnBundledResources() {
		Path root = Test262BatchRunner.findTest262Root();
		Assertions.assertNotNull(root, "Test262 root directory should be discovered");

		Path harnessDir = root.resolve("harness");
		Path testDir = root.resolve("test");

		Test262BatchRunner.BatchSummary summary = Test262BatchRunner.runDirectory(testDir, harnessDir, false);
		summary.printReport();

		Assertions.assertTrue(summary.total > 0, "Should have executed at least 1 test case");
		Assertions.assertEquals(0, summary.failed, "All bundled Test262 test cases should pass!");
	}
}
