package io.evitadb.roaringbitmap.kernel;

import io.evitadb.roaringbitmap.RoaringKernels;
import io.evitadb.roaringbitmap.kernel.VectorKernels.KernelDecision;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
	/**
	 * The kill switch turning every vector kernel off at once. Spelled out here rather than read from the
	 * provider, whose constant is private, so that renaming the property has to be argued against this test.
	 */
	private static final String GLOBAL_SWITCH = "evita.roaring.vector";
	/**
	 * The dense-container family's own switch.
	 */
	private static final String BITMAP_SWITCH = "evita.roaring.vector.bitmap";
	/**
	 * The sparse-container family's own switch.
	 */
	private static final String ARRAY_SWITCH = "evita.roaring.vector.array";
	/**
	 * Opt-in property asserting that this JVM is one on which the vector kernels are *expected* to win every
	 * gate. Unlike the three switches above it is read by nobody in production - it names no kernel family and
	 * turns nothing off; it tells this test to fail rather than shrug when the provider published the scalar
	 * kernels. It stays opt-in because a runner whose preferred `long` shape is narrower than 256 bits runs
	 * scalar legitimately, and failing such a build would say the code is broken when the CPU is merely narrow.
	 */
	private static final String REQUIRE_VECTOR_SWITCH = "evita.roaring.vector.require";
	/**
	 * Fully qualified name of the vector implementation, which the provider loads reflectively and this test
	 * therefore has to name the same way.
	 */
	private static final String VECTOR_BITMAP_KERNELS = "io.evitadb.roaringbitmap.kernel.VectorBitmapKernels";
	/**
	 * Why a JVM may have no vector implementation to offer, said once for every assumption that skips.
	 */
	private static final String NO_VECTOR_IMPLEMENTATION =
		"VectorBitmapKernels could not be loaded - the JVM was most likely started without " +
			"`--add-modules jdk.incubator.vector`, so there is no reflective contract to check.";

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

		@Test
		@DisplayName("a switch that could not be read is reported as the failure it was, not as a kill switch")
		void shouldNotBlameTheKillSwitchWhenTheSwitchCannotBeRead() {
			// a refused property read leaves every switch input at its "off" default, so the gate sequence
			// reaches DISABLED_GLOBALLY on a JVM where nobody passed the flag. The gates are right to land
			// there - it is the answer that runs the scalar kernels - but the operator-facing line must name
			// the throwable that was actually decisive, or an operator greps the log for their own flag and
			// concludes they set it
			final String failure = "SecurityException: property access denied";
			final String summary = VectorKernels.buildSummary(
				KernelDecision.DISABLED_GLOBALLY, 0, null, failure, KernelDecision.DISABLED_GLOBALLY
			);

			assertTrue(summary.contains(failure), summary);
			assertFalse(
				summary.contains("disabled by -D" + GLOBAL_SWITCH + "=false"),
				"the summary blames a flag nobody passed: " + summary
			);
			assertTrue(summary.contains("bitmap=scalar"), summary);
			assertTrue(summary.contains("array=scalar"), summary);
		}

		@Test
		@DisplayName("a kill switch that really was set is still named as the reason")
		void shouldNameTheKillSwitchWhenItReallyWasSet() {
			// the counterfactual to the test above: with nothing thrown, the gate that decided is the reason,
			// so suppressing the kill-switch sentence under a failure cannot have suppressed it everywhere
			final String summary = VectorKernels.buildSummary(
				KernelDecision.DISABLED_GLOBALLY, 0, null, null, KernelDecision.DISABLED_GLOBALLY
			);

			assertTrue(summary.contains("disabled by -D" + GLOBAL_SWITCH + "=false"), summary);
		}
	}

	@Nested
	@DisplayName("the reported reason")
	class ReportedReason {

		@Test
		@DisplayName("every outcome renders its own non-empty fragment")
		void shouldRenderADistinctFragmentForEveryOutcome() {
			final Set<String> fragments = new HashSet<>();
			for (final KernelDecision decision : KernelDecision.values()) {
				final String fragment = decision.reason(BITMAP_SWITCH, "and");
				assertNotNull(fragment, decision.name());
				assertFalse(fragment.isEmpty(), decision.name());
				assertTrue(
					fragments.add(fragment),
					"two outcomes render the same fragment, so the summary cannot tell them apart: " + fragment
				);
			}
			assertEquals(KernelDecision.values().length, fragments.size());
		}

		@Test
		@DisplayName("the per-kernel outcome names the switch it was handed, never the global one")
		void shouldNameTheSwitchItWasHanded() {
			final String bitmap = KernelDecision.DISABLED_PER_KERNEL.reason(BITMAP_SWITCH, null);
			final String array = KernelDecision.DISABLED_PER_KERNEL.reason(ARRAY_SWITCH, null);

			assertTrue(bitmap.contains(BITMAP_SWITCH), bitmap);
			assertTrue(array.contains(ARRAY_SWITCH), array);
			assertNotEquals(
				bitmap, array,
				"the two families must not render the same sentence, or the summary blames the wrong flag"
			);
			assertFalse(
				bitmap.contains(GLOBAL_SWITCH + "="),
				"the per-kernel outcome must never name the kill switch: " + bitmap
			);
		}

		@Test
		@DisplayName("the kill-switch outcome reads the same whichever family asked")
		void shouldNameTheKillSwitchForBothFamilies() {
			final String bitmap = KernelDecision.DISABLED_GLOBALLY.reason(BITMAP_SWITCH, null);

			assertEquals(bitmap, KernelDecision.DISABLED_GLOBALLY.reason(ARRAY_SWITCH, null));
			assertTrue(bitmap.contains("-D" + GLOBAL_SWITCH + "=false"), bitmap);
		}

		@Test
		@DisplayName("a failed self-test carries the name of the kernel that disagreed")
		void shouldCarryTheNameOfTheFailingKernel() {
			assertTrue(
				KernelDecision.SELF_TEST_FAILED.reason(BITMAP_SWITCH, "extractAndNot").contains("extractAndNot"),
				"an operator cannot act on a mismatch whose kernel is not named"
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
		@DisplayName("rejects a JVM whose compiler was switched off on the command line")
		void shouldRejectADisabledCompilerArgument() {
			assertFalse(VectorKernels.isJitUsable(List.of("-XX:-UseCompiler")));
			assertFalse(
				VectorKernels.isJitUsable(
					List.of("-Xmx4g", "-XX:-UseCompiler", "--add-modules", "jdk.incubator.vector")
				)
			);
		}

		@Test
		@DisplayName("saturates a cap whose digit run would overflow the accumulator")
		void shouldSaturateAnOverlongCap() {
			// a twelve-digit cap overflows an int if the parse keeps accumulating, and an overflowed value can
			// land anywhere - including below the optimizing tier, which would disable the kernels outright
			assertTrue(VectorKernels.isJitUsable(List.of("-XX:TieredStopAtLevel=999999999999")));
			assertTrue(VectorKernels.isJitUsable(null, "999999999999"));
			// leading zeroes must not saturate anything: the value is still one
			assertFalse(VectorKernels.isJitUsable(List.of("-XX:TieredStopAtLevel=0000000001")));
			assertFalse(VectorKernels.isJitUsable(null, "0000000001"));
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
		@DisplayName("the vector implementation still exposes the contract the provider loads it through")
		void shouldExposeTheReflectiveContractTheProviderLoadsThrough() throws Exception {
			// the provider reaches VectorBitmapKernels by name and calls these three members reflectively;
			// renaming any of them throws into the fail-safe boundary, and every JVM then runs the scalar
			// kernels with a green suite and a summary that blames the CPU
			final Class<?> implementation = loadVectorBitmapKernelsClass();
			Assumptions.assumeTrue(implementation != null, NO_VECTOR_IMPLEMENTATION);

			final Method isSupported = implementation.getMethod("isSupported");
			assertTrue(Modifier.isPublic(isSupported.getModifiers()), "isSupported must be public");
			assertTrue(Modifier.isStatic(isSupported.getModifiers()), "isSupported must be static");
			assertEquals(boolean.class, isSupported.getReturnType(), "isSupported must return a boolean");

			final Method vectorBitSize = implementation.getMethod("vectorBitSize");
			assertTrue(Modifier.isPublic(vectorBitSize.getModifiers()), "vectorBitSize must be public");
			assertTrue(Modifier.isStatic(vectorBitSize.getModifiers()), "vectorBitSize must be static");
			assertEquals(int.class, vectorBitSize.getReturnType(), "vectorBitSize must return an int");

			final Constructor<?> constructor = implementation.getDeclaredConstructor();
			assertTrue(Modifier.isPublic(constructor.getModifiers()), "the no-arg constructor must be public");
			assertInstanceOf(
				BitmapKernels.class, constructor.newInstance(),
				"the provider casts what the constructor returns to BitmapKernels"
			);
		}

		// A plain run has to accept either answer: the module's second surefire execution disables the vector
		// kernels deliberately, and the first one falls back to scalar on any JVM that does not resolve
		// `jdk.incubator.vector` or whose preferred long shape is narrower than 256 bits - which is every
		// ordinary AArch64 core, so a hard requirement here would fail the build on hardware that is perfectly
		// entitled to run scalar. The requirement is opt-in instead, and the test below is what enforces it on
		// a machine that is supposed to be exercising the vector path.
		@Test
		@DisplayName("either kernel family is accepted, whichever the JVM ended up publishing")
		void shouldAcceptAScalarFallbackWhateverTheReason() {
			final String summary = VectorKernels.summary();
			assertTrue(
				summary.contains("bitmap=scalar") || summary.contains("bitmap=vector("),
				summary
			);
		}

		@Test
		@DisplayName("a run told to require the vector kernels really is running them")
		void shouldRunTheVectorKernelsWhenTheBuildRequiresThem() {
			// Without this, a green suite is compatible with the container arithmetic never once having
			// executed a vector kernel: the differential test skips itself when the implementation cannot be
			// loaded, and every other assertion in this class accepts both answers. Whoever runs the vector
			// suite on purpose - a release build on a wide-vector machine, or a developer checking that the
			// path they just changed is the one being measured - sets the property and finds out.
			Assumptions.assumeTrue(
				Boolean.getBoolean(REQUIRE_VECTOR_SWITCH),
				"this JVM was not asked to require the vector kernels - pass -D" + REQUIRE_VECTOR_SWITCH
					+ "=true on a machine where they are expected to be selected"
			);
			Assumptions.assumeFalse(
				"false".equalsIgnoreCase(System.getProperty(GLOBAL_SWITCH)),
				"the kill switch is set, which is this module's scalar execution doing exactly what it is for"
			);

			final String summary = VectorKernels.summary();
			assertNotSame(
				ScalarBitmapKernels.INSTANCE, VectorKernels.BITMAP,
				() -> "the vector kernels were required but the provider published the scalar ones: " + summary
			);
			assertTrue(
				summary.contains("bitmap=vector("),
				() -> "the vector kernels were required but the summary reports otherwise: " + summary
			);
		}

		@Test
		@DisplayName("the kill switch, when set for this JVM, is honoured and named")
		void shouldHonourTheKillSwitchWhenSet() {
			// this module's suite runs twice - once as the JVM offers it, once with the kill switch on - so
			// this assertion is the one that proves the second execution really is a different configuration
			if ("false".equalsIgnoreCase(System.getProperty(GLOBAL_SWITCH))) {
				assertSame(ScalarBitmapKernels.INSTANCE, VectorKernels.BITMAP);
				assertSame(ScalarArrayKernels.INSTANCE, VectorKernels.ARRAY);
				assertTrue(
					VectorKernels.summary().contains("disabled by -Devita.roaring.vector=false"),
					VectorKernels.summary()
				);
			}
		}
	}

	/**
	 * Loads {@link VectorBitmapKernels} the way {@link VectorKernels} does, so that a JVM without the
	 * incubator module skips the assertion instead of failing to link it.
	 *
	 * @return the implementation class, or `null` when this JVM has none
	 */
	@Nullable
	private static Class<?> loadVectorBitmapKernelsClass() {
		try {
			return Class.forName(
				VECTOR_BITMAP_KERNELS, true, VectorKernelsSelectionTest.class.getClassLoader()
			);
		} catch (Throwable ignored) {
			return null;
		}
	}

}
