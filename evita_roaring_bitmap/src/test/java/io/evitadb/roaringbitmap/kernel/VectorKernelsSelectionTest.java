package io.evitadb.roaringbitmap.kernel;

import io.evitadb.roaringbitmap.RoaringKernels;
import io.evitadb.roaringbitmap.kernel.VectorKernels.KernelDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the provider's decision logic, which is what decides whether the container arithmetic runs on SIMD or
 * on the scalar loops.
 *
 * The decision itself is made once per JVM in a static initializer and cannot be re-run from a test, so the
 * ordered gate sequence lives in {@link VectorKernels#decide} as a pure function and is exercised here
 * directly. What the test asserts about the gates is not only *that* they disable the vector path but which
 * one of them claims responsibility: the summary line names the decisive gate, and a summary that blames the
 * wrong gate sends an operator to fix something that was never the problem.
 */
@DisplayName("The kernel provider's gates decide, and report, in the documented order")
public class VectorKernelsSelectionTest {

	@Nested
	@DisplayName("the gate sequence")
	class Gates {

		@Test
		@DisplayName("selects the vector implementation only when every gate passes")
		void shouldSelectVectorWhenAllGatesPass() {
			assertEquals(
				KernelDecision.VECTOR,
				VectorKernels.decide(false, false, true, true, true, true)
			);
		}

		@Test
		@DisplayName("the kill switch wins over every other gate")
		void shouldReportGlobalSwitchFirst() {
			// every later input is at its failing value, yet the kill switch is what gets named
			assertEquals(
				KernelDecision.DISABLED_GLOBALLY,
				VectorKernels.decide(true, true, false, false, false, false)
			);
			assertEquals(
				KernelDecision.DISABLED_GLOBALLY,
				VectorKernels.decide(true, false, true, true, true, true)
			);
		}

		@Test
		@DisplayName("the per-kernel switch wins over the module, the JIT and the implementation")
		void shouldReportKernelSwitchBeforeEnvironment() {
			assertEquals(
				KernelDecision.DISABLED_PER_KERNEL,
				VectorKernels.decide(false, true, false, false, false, false)
			);
			assertEquals(
				KernelDecision.DISABLED_PER_KERNEL,
				VectorKernels.decide(false, true, true, true, true, true)
			);
		}

		@Test
		@DisplayName("an absent module is reported before the JIT and the implementation")
		void shouldReportAbsentModuleBeforeJit() {
			assertEquals(
				KernelDecision.MODULE_ABSENT,
				VectorKernels.decide(false, false, false, false, false, false)
			);
			assertEquals(
				KernelDecision.MODULE_ABSENT,
				VectorKernels.decide(false, false, false, true, true, true)
			);
		}

		@Test
		@DisplayName("an unusable JIT is reported before the implementation")
		void shouldReportUnusableJitBeforeImplementation() {
			assertEquals(
				KernelDecision.JIT_UNUSABLE,
				VectorKernels.decide(false, false, true, false, false, false)
			);
			assertEquals(
				KernelDecision.JIT_UNUSABLE,
				VectorKernels.decide(false, false, true, false, true, true)
			);
		}

		@Test
		@DisplayName("a missing implementation is reported before the self-test")
		void shouldReportMissingImplementationBeforeSelfTest() {
			assertEquals(
				KernelDecision.VECTOR_UNAVAILABLE,
				VectorKernels.decide(false, false, true, true, false, false)
			);
			assertEquals(
				KernelDecision.VECTOR_UNAVAILABLE,
				VectorKernels.decide(false, false, true, true, false, true)
			);
		}

		@Test
		@DisplayName("a failed self-test is the last thing that can veto the vector implementation")
		void shouldReportFailedSelfTest() {
			assertEquals(
				KernelDecision.SELF_TEST_FAILED,
				VectorKernels.decide(false, false, true, true, true, false)
			);
		}
	}

	@Nested
	@DisplayName("the JIT check")
	class JitCheck {

		@Test
		@DisplayName("accepts an ordinary command line")
		void shouldAcceptOrdinaryArguments() {
			assertTrue(VectorKernels.isJitUsable(List.of()));
			assertTrue(VectorKernels.isJitUsable(List.of("-Xmx4g", "--add-modules", "jdk.incubator.vector")));
		}

		@Test
		@DisplayName("rejects an interpreted-only JVM")
		void shouldRejectInterpretedMode() {
			assertFalse(VectorKernels.isJitUsable(List.of("-Xmx4g", "-Xint")));
		}

		@Test
		@DisplayName("rejects a JIT capped below the optimizing compiler")
		void shouldRejectCappedTieredCompilation() {
			assertFalse(VectorKernels.isJitUsable(List.of("-XX:TieredStopAtLevel=1")));
			assertFalse(VectorKernels.isJitUsable(List.of("-XX:TieredStopAtLevel=0")));
			assertFalse(VectorKernels.isJitUsable(List.of("-XX:TieredStopAtLevel=3")));
		}

		@Test
		@DisplayName("accepts the full tiered range and anything it cannot parse")
		void shouldAcceptUncappedTieredCompilation() {
			assertTrue(VectorKernels.isJitUsable(List.of("-XX:TieredStopAtLevel=4")));
			// `-XX:-TieredCompilation` selects the optimizing compiler only, which is what the kernels want
			assertTrue(VectorKernels.isJitUsable(List.of("-XX:-TieredCompilation")));
			assertTrue(VectorKernels.isJitUsable(List.of("-XX:TieredStopAtLevel=")));
			assertTrue(VectorKernels.isJitUsable(List.of("-XX:TieredStopAtLevel=x")));
		}

		@Test
		@DisplayName("reads the cap as a number rather than matching a spelling")
		void shouldParseTheCapAsANumber() {
			// the launcher normalises its arguments, so the check must never depend on an exact string
			assertTrue(VectorKernels.isJitUsable(List.of("-XX:TieredStopAtLevel=04")));
			assertFalse(VectorKernels.isJitUsable(List.of("-XX:TieredStopAtLevel=03")));
			assertTrue(VectorKernels.isJitUsable(List.of("-XX:TieredStopAtLevel=10")));
			assertTrue(VectorKernels.isJitUsable(List.of("--add-modules=jdk.incubator.vector")));
		}

		@Test
		@DisplayName("the diagnostic-bean form agrees with the command-line form")
		void shouldReadTheDiagnosticBeanValues() {
			assertTrue(VectorKernels.isJitUsable(null, null));
			assertTrue(VectorKernels.isJitUsable("true", "4"));
			// `-Xint` is implemented by clearing UseCompiler, which is why this case matters
			assertFalse(VectorKernels.isJitUsable("false", "4"));
			assertFalse(VectorKernels.isJitUsable("true", "1"));
			assertTrue(VectorKernels.isJitUsable("true", ""));
			assertTrue(VectorKernels.isJitUsable(null, "4"));
		}
	}

	@Nested
	@DisplayName("the published decision")
	class PublishedDecision {

		@Test
		@DisplayName("the summary names both kernel families and a reason for each")
		void shouldSummarizeBothFamilies() {
			final String summary = VectorKernels.summary();
			assertNotNull(summary);
			assertFalse(summary.isEmpty());
			assertTrue(summary.startsWith("roaring vector kernels: "), summary);
			assertTrue(summary.contains("bitmap="), summary);
			assertTrue(summary.contains("array="), summary);
			assertTrue(summary.contains("(bitmap: "), summary);
			assertTrue(summary.contains("; array: "), summary);
		}

		@Test
		@DisplayName("the exported accessor returns the very same line")
		void shouldExposeTheSummaryThroughTheExportedPackage() {
			assertEquals(VectorKernels.summary(), RoaringKernels.vectorKernelsSummary());
		}

		@Test
		@DisplayName("the selected kernels are never null, and the summary agrees with them")
		void shouldAlwaysPublishUsableKernels() {
			assertNotNull(VectorKernels.BITMAP);
			// no vector formulation of the sparse-container merge has been measured to win yet
			assertSame(ScalarArrayKernels.INSTANCE, VectorKernels.ARRAY);
			// the summary is the operational record of the decision, so it has to agree with the field it
			// describes rather than merely be well-formed
			final String summary = VectorKernels.summary();
			assertEquals(
				VectorKernels.BITMAP == ScalarBitmapKernels.INSTANCE,
				summary.contains("bitmap=scalar"),
				summary
			);
		}

		@Test
		@DisplayName("the kill switch, when set for this JVM, is honoured and named")
		void shouldHonourTheKillSwitchWhenSet() {
			// this module's suite runs twice - once as the JVM offers it, once with the kill switch on - so
			// this assertion is the one that proves the second execution really is a different configuration
			if ("false".equalsIgnoreCase(System.getProperty("evita.roaring.vector"))) {
				assertSame(ScalarBitmapKernels.INSTANCE, VectorKernels.BITMAP);
				assertSame(ScalarArrayKernels.INSTANCE, VectorKernels.ARRAY);
				assertTrue(
					VectorKernels.summary().contains("disabled by -Devita.roaring.vector=false"),
					VectorKernels.summary()
				);
			}
		}
	}

}
