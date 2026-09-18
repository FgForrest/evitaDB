package io.evitadb.roaringbitmap.kernel;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.management.ManagementFactory;
import java.lang.management.PlatformManagedObject;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/**
 * Chooses, once per JVM, which {@link BitmapKernels} and {@link ArrayKernels} implementation the container
 * operators run on, and records in one sentence why.
 *
 * The JDK Vector API is an *incubating* module: it may be absent from the runtime image, it may be present
 * but not resolved (it is never in the default root set — the launcher has to pass
 * `--add-modules jdk.incubator.vector`), and even when it is resolved its operations are only fast if the
 * JIT intrinsifies them. Getting any of that wrong must cost nothing, so this holder resolves the question
 * defensively and falls back to {@link ScalarBitmapKernels} / {@link ScalarArrayKernels}, which are the code
 * the module ran before the kernels existed.
 *
 * The gates, in order, are:
 *
 * 1. the kill switch `-Devita.roaring.vector=false`, and the per-kernel switches
 *    `-Devita.roaring.vector.bitmap=false` / `-Devita.roaring.vector.array=false`;
 * 2. `jdk.incubator.vector` resolved in the boot layer;
 * 3. a usable JIT — read from the HotSpot diagnostic bean where there is one and from the command line
 *    otherwise, since a vector kernel that is not intrinsified degrades into a boxed lane loop far slower
 *    than the scalar code it replaced;
 * 4. a vector shape wide enough to pay for itself, asked of the vector class itself
 *    ({@link VectorBitmapKernels#isSupported()}) because this class must stay loadable where the module is not;
 * 5. the implementation loading at all — reflectively, catching `Throwable`, because a half-present module
 *    surfaces as `NoClassDefFoundError` and a refused operation as `UnsupportedOperationException`;
 * 6. a self-test that runs every kernel against its scalar twin on fixed inputs, so that a mis-intrinsified
 *    operation on a CPU nobody tested here falls back instead of returning wrong cardinalities.
 *
 * This module has no logging dependency and gains none. The decision is exposed as {@link #summary()},
 * which evitaDB's engine reads through `io.evitadb.roaringbitmap.RoaringKernels` and logs once at startup.
 */
public final class VectorKernels {
	/**
	 * Kernels over the 1024-word array of a dense container: vector where every gate passed, scalar otherwise.
	 */
	public static final BitmapKernels BITMAP;
	/**
	 * Kernels over the sorted value list of a sparse container. Scalar today — no vector formulation of the
	 * two-way merge has been measured to beat it, so only the switch slot exists.
	 */
	public static final ArrayKernels ARRAY;

	/**
	 * Kill switch turning every vector kernel off at once.
	 */
	private static final String GLOBAL_SWITCH_PROPERTY = "evita.roaring.vector";
	/**
	 * Per-kernel switch for the dense-container kernels.
	 */
	private static final String BITMAP_SWITCH_PROPERTY = "evita.roaring.vector.bitmap";
	/**
	 * Per-kernel switch for the sparse-container kernels.
	 */
	private static final String ARRAY_SWITCH_PROPERTY = "evita.roaring.vector.array";
	/**
	 * Name of the incubating module the vector implementation is written against.
	 */
	private static final String VECTOR_MODULE_NAME = "jdk.incubator.vector";
	/**
	 * Fully qualified name of the vector implementation, loaded reflectively so that its absence is a
	 * fallback rather than a linkage error.
	 */
	private static final String VECTOR_BITMAP_KERNELS = "io.evitadb.roaringbitmap.kernel.VectorBitmapKernels";
	/**
	 * JVM argument that turns the JIT off altogether.
	 */
	private static final String INTERPRETED_MODE_ARGUMENT = "-Xint";
	/**
	 * JVM argument that disables the compiler while leaving the rest of the JIT infrastructure in place; it
	 * has the same effect on a vector kernel as {@link #INTERPRETED_MODE_ARGUMENT}.
	 */
	private static final String COMPILER_DISABLED_ARGUMENT = "-XX:-UseCompiler";
	/**
	 * Prefix of the JVM argument that caps the JIT below the optimizing compiler. Matched as a prefix and
	 * parsed as a number, because the launcher normalizes its arguments and an exact-string match would miss
	 * every spelling but one.
	 */
	private static final String TIERED_STOP_AT_LEVEL_PREFIX = "-XX:TieredStopAtLevel=";
	/**
	 * Tier at which HotSpot runs the optimizing compiler. A cap below this leaves the kernels interpreted or
	 * compiled by the fast tier, where the vector path loses to the scalar one.
	 */
	private static final int TIERED_FULL_OPTIMIZATION = 4;
	/**
	 * The VM's own diagnostic bean, which answers what the flags actually ended up being rather than what
	 * the command line asked for.
	 */
	private static final String DIAGNOSTIC_BEAN_CLASS = "com.sun.management.HotSpotDiagnosticMXBean";
	/**
	 * VM option that is `false` whenever the compiler is off, including under `-Xint`, which HotSpot
	 * implements by clearing exactly this flag.
	 */
	private static final String USE_COMPILER_OPTION = "UseCompiler";
	/**
	 * VM option carrying the tiered-compilation cap.
	 */
	private static final String TIERED_STOP_AT_LEVEL_OPTION = "TieredStopAtLevel";
	/**
	 * Word count of a dense container, and therefore the size of every self-test sample.
	 */
	private static final int SELF_TEST_WORDS = 1024;
	/**
	 * Seed of the self-test's pseudo-random samples. Fixed so that the decision a JVM makes is reproducible.
	 */
	private static final long SELF_TEST_SEED = 0x9E3779B97F4A7C15L;
	/**
	 * Bit ranges the self-test exercises `cardinalityInRange` over, as `start, end` pairs: empty, single-bit,
	 * inside one word, word-aligned, straddling a word boundary, and the whole container.
	 */
	private static final int[] SELF_TEST_RANGES = {
		0, 0, 0, 1, 3, 5, 1, 63, 0, 64, 0, 65, 63, 65, 100, 164, 511, 513,
		64, 65536, 12345, 54321, 65535, 65536, 0, 65536
	};

	/**
	 * Names of the four fused kernels, in the order {@link #applyFused} dispatches them.
	 */
	private static final String[] FUSED_KERNEL_NAMES = {"and", "or", "xor", "andNot"};
	/**
	 * Aliasing shape where the destination is a separate array from both operands.
	 */
	private static final int ALIASING_SEPARATE = 0;
	/**
	 * Aliasing shape where the destination *is* the first operand, which is what every in-place operator does.
	 */
	private static final int ALIASING_INTO_FIRST = 1;
	/**
	 * Aliasing shape where both operands and the destination are one and the same array.
	 */
	private static final int ALIASING_ALL_ONE = 2;
	/**
	 * The aliasing shapes the self-test sweeps, so that every one of them is named rather than implied by an
	 * index.
	 */
	private static final int[] ALIASING_SHAPES = {ALIASING_SEPARATE, ALIASING_INTO_FIRST, ALIASING_ALL_ONE};

	/**
	 * One-line record of the decision, built once in the static initializer.
	 */
	private static final String SUMMARY;

	static {
		// The scalar kernels are the answer this block starts from and falls back to. Everything that could
		// possibly fail - resolving the module, reading the JVM's command line, loading the vector class,
		// initializing its species constants, running the self-test - sits inside ONE try/catch(Throwable),
		// because the whole point of the provider is that no failure of the optional path may escape it. An
		// escaping error here would be an `ExceptionInInitializerError` on the first bitmap operation, i.e.
		// exactly the outage the optional dependency exists to prevent.
		BitmapKernels bitmapKernels = ScalarBitmapKernels.INSTANCE;
		final boolean globalSwitchOff = isSwitchedOff(GLOBAL_SWITCH_PROPERTY);
		final boolean bitmapSwitchOff = isSwitchedOff(BITMAP_SWITCH_PROPERTY);
		final boolean arraySwitchOff = isSwitchedOff(ARRAY_SWITCH_PROPERTY);
		boolean modulePresent = false;
		boolean jitUsable = false;
		boolean vectorAvailable = false;
		int vectorBitSize = 0;
		String selfTestFailure = null;
		String failure = null;
		try {
			// the gates are evaluated only while the ones before them still allow a vector kernel, so that a
			// JVM running with the kill switch on never touches the incubator module at all
			modulePresent = !globalSwitchOff
				&& ModuleLayer.boot().findModule(VECTOR_MODULE_NAME).isPresent();
			jitUsable = modulePresent && isJitUsable();
			final LoadedBitmapKernels loaded = (!bitmapSwitchOff && jitUsable) ? loadVectorBitmapKernels() : null;
			vectorAvailable = loaded != null;
			if (loaded != null) {
				selfTestFailure = selfTest(loaded.kernels());
				if (selfTestFailure == null) {
					bitmapKernels = loaded.kernels();
					vectorBitSize = loaded.vectorBitSize();
				}
			}
		} catch (Throwable t) {
			// `NoClassDefFoundError` from a module that is not resolved, `ExceptionInInitializerError` from a
			// species constant the API refuses, `UnsupportedOperationException` from an operation this CPU has
			// no shape for - every one of them means the same thing here, which is: run the scalar kernels.
			// The throwable is named in the summary so that the reason is diagnosable without a debugger.
			bitmapKernels = ScalarBitmapKernels.INSTANCE;
			vectorAvailable = false;
			vectorBitSize = 0;
			selfTestFailure = null;
			failure = t.getClass().getSimpleName() + ": " + t.getMessage();
		}

		final KernelDecision bitmapDecision = decide(
			globalSwitchOff, bitmapSwitchOff, modulePresent, jitUsable, vectorAvailable, selfTestFailure == null
		);
		// no vector implementation of the sparse-container merge exists yet, hence the permanently `false`
		// availability input - the switches in front of it still get to name themselves in the summary
		final KernelDecision arrayDecision = decide(
			globalSwitchOff, arraySwitchOff, modulePresent, jitUsable, false, true
		);

		BITMAP = bitmapDecision == KernelDecision.VECTOR ? bitmapKernels : ScalarBitmapKernels.INSTANCE;
		ARRAY = ScalarArrayKernels.INSTANCE;
		SUMMARY = buildSummary(bitmapDecision, vectorBitSize, selfTestFailure, failure, arrayDecision);
	}

	private VectorKernels() {
		throw new UnsupportedOperationException("VectorKernels is a holder and must not be instantiated.");
	}

	/**
	 * One line naming the kernel implementation in force and the reason it was chosen, e.g.
	 * `roaring vector kernels: bitmap=vector(512-bit) array=scalar (bitmap: jdk.incubator.vector present,
	 * JIT compiler available, self-test passed; array: no usable vector implementation)`.
	 *
	 * @return the decision summary; never empty
	 */
	@Nonnull
	public static String summary() {
		return SUMMARY;
	}

	/**
	 * The pure decision function behind the static initializer, kept separate so its truth table can be
	 * tested without a JVM that has (or lacks) the incubator module.
	 *
	 * The gates are ordered, and the first one that fails is the answer: that is what makes the summary name
	 * the *decisive* fact rather than an incidental one.
	 *
	 * @param globalSwitchOff `true` when `-Devita.roaring.vector=false` was given
	 * @param kernelSwitchOff `true` when this kernel family's own switch was set to `false`
	 * @param modulePresent   `true` when `jdk.incubator.vector` is resolved in the boot layer
	 * @param jitUsable       `true` when the JVM runs an optimizing JIT
	 * @param vectorAvailable `true` when a vector implementation loaded and accepted the running CPU
	 * @param selfTestPassed  `true` when every vector kernel matched its scalar twin
	 * @return the decision, {@link KernelDecision#VECTOR} only when every gate passed
	 */
	@Nonnull
	static KernelDecision decide(
		final boolean globalSwitchOff,
		final boolean kernelSwitchOff,
		final boolean modulePresent,
		final boolean jitUsable,
		final boolean vectorAvailable,
		final boolean selfTestPassed
	) {
		if (globalSwitchOff) {
			return KernelDecision.DISABLED_GLOBALLY;
		}
		if (kernelSwitchOff) {
			return KernelDecision.DISABLED_PER_KERNEL;
		}
		if (!modulePresent) {
			return KernelDecision.MODULE_ABSENT;
		}
		if (!jitUsable) {
			return KernelDecision.JIT_UNUSABLE;
		}
		if (!vectorAvailable) {
			return KernelDecision.VECTOR_UNAVAILABLE;
		}
		if (!selfTestPassed) {
			return KernelDecision.SELF_TEST_FAILED;
		}
		return KernelDecision.VECTOR;
	}

	/**
	 * Decides whether an optimizing JIT will be available for the vector kernels.
	 *
	 * A vector kernel the JIT does not intrinsify runs as an interpreted lane-by-lane loop, markedly slower
	 * than the scalar loop it replaced, so the conditions that guarantee that outcome disqualify the vector
	 * path outright. The VM's own diagnostic bean is asked first, because it reports what the flags settled
	 * on rather than what was typed — `-Xint`, for one, does not appear as a `-XX:` option at all; HotSpot
	 * implements it by clearing `UseCompiler`. The command line is the fallback for a runtime that has no
	 * such bean.
	 *
	 * `-XX:-TieredCompilation` is deliberately not disqualifying: it selects the optimizing compiler only,
	 * which is exactly what the kernels want. Narrow vector hardware needs no handling here either —
	 * `-XX:UseAVX=0` and `-XX:MaxVectorSize=16` both collapse the preferred shape, which
	 * {@link VectorBitmapKernels#isSupported()} rejects on width.
	 *
	 * @return `true` when nothing observable rules the optimizing compiler out
	 */
	private static boolean isJitUsable() {
		final Boolean fromDiagnosticBean = jitUsableFromDiagnosticBean();
		if (fromDiagnosticBean != null) {
			return fromDiagnosticBean;
		}
		return isJitUsable(ManagementFactory.getRuntimeMXBean().getInputArguments());
	}

	/**
	 * Asks the HotSpot diagnostic bean for the two options that decide the question.
	 *
	 * The local `catch` here is not a hole in the static initializer's single failure boundary: a runtime
	 * without this bean is not a failure, it is a runtime whose command line has to be read instead, and
	 * that distinction is exactly what the `null` return carries.
	 *
	 * @return the verdict, or `null` when the bean is unavailable and the command line must be read
	 */
	@Nullable
	private static Boolean jitUsableFromDiagnosticBean() {
		try {
			final Class<?> beanType = Class.forName(DIAGNOSTIC_BEAN_CLASS);
			//noinspection unchecked
			final PlatformManagedObject bean = ManagementFactory.getPlatformMXBean(
				(Class<PlatformManagedObject>) beanType
			);
			if (bean == null) {
				return null;
			}
			final Method getVmOption = beanType.getMethod("getVMOption", String.class);
			return isJitUsable(
				readVmOptionValue(getVmOption, bean, USE_COMPILER_OPTION),
				readVmOptionValue(getVmOption, bean, TIERED_STOP_AT_LEVEL_OPTION)
			);
		} catch (Throwable ignored) {
			return null;
		}
	}

	/**
	 * Reads one VM option's value through the diagnostic bean, tolerating an option this VM does not know.
	 *
	 * @param getVmOption the bean's `getVMOption` method
	 * @param bean        the bean instance
	 * @param name        name of the option, without the `-XX:` prefix
	 * @return the option's value, or `null` when the VM does not carry it
	 */
	@Nullable
	private static String readVmOptionValue(
		@Nonnull final Method getVmOption,
		@Nonnull final PlatformManagedObject bean,
		@Nonnull final String name
	) {
		try {
			final Object option = getVmOption.invoke(bean, name);
			return option == null ? null : (String) option.getClass().getMethod("getValue").invoke(option);
		} catch (Throwable ignored) {
			// `getVMOption` raises for an option the VM does not have; that is "cannot tell", not "unusable"
			return null;
		}
	}

	/**
	 * The diagnostic-bean form of the JIT check, kept separate so its truth table can be tested.
	 *
	 * @param useCompiler       value of the `UseCompiler` option, `null` when unknown
	 * @param tieredStopAtLevel value of the `TieredStopAtLevel` option, `null` when unknown
	 * @return `true` when neither option rules the optimizing compiler out
	 */
	static boolean isJitUsable(@Nullable final String useCompiler, @Nullable final String tieredStopAtLevel) {
		if ("false".equalsIgnoreCase(useCompiler)) {
			return false;
		}
		final int level = parseTieredStopAtLevel(tieredStopAtLevel);
		return level < 0 || level >= TIERED_FULL_OPTIMIZATION;
	}

	/**
	 * The command-line form of the JIT check, kept separate so its truth table can be tested.
	 *
	 * @param jvmArguments the JVM's input arguments
	 * @return `true` when nothing on the command line rules the optimizing compiler out
	 */
	static boolean isJitUsable(@Nonnull final List<String> jvmArguments) {
		for (int i = 0; i < jvmArguments.size(); i++) {
			final String argument = jvmArguments.get(i);
			if (INTERPRETED_MODE_ARGUMENT.equals(argument) || COMPILER_DISABLED_ARGUMENT.equals(argument)) {
				return false;
			}
			if (argument.startsWith(TIERED_STOP_AT_LEVEL_PREFIX)) {
				final int level = parseTieredStopAtLevel(
					argument.substring(TIERED_STOP_AT_LEVEL_PREFIX.length())
				);
				if (level >= 0 && level < TIERED_FULL_OPTIMIZATION) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Parses a tiered-compilation cap without using an exception as a branch.
	 *
	 * @param value the option's value, `null` or empty when it was not given
	 * @return the cap, or `-1` when it cannot be read and the check must abstain
	 */
	private static int parseTieredStopAtLevel(@Nullable final String value) {
		if (value == null || value.isEmpty()) {
			return -1;
		}
		int level = 0;
		for (int i = 0; i < value.length(); i++) {
			final char digit = value.charAt(i);
			if (digit < '0' || digit > '9') {
				return -1;
			}
			level = level * 10 + (digit - '0');
			if (level >= TIERED_FULL_OPTIMIZATION) {
				// anything at or above the optimizing tier is equivalent here, and saturating keeps a long
				// run of digits from overflowing the accumulator
				return TIERED_FULL_OPTIMIZATION;
			}
		}
		return level;
	}

	/**
	 * Loads the vector implementation reflectively and asks it whether the running CPU is worth it.
	 *
	 * Reflection is the point: a direct reference would link `VectorBitmapKernels` — and through it the
	 * incubator module — into this class, turning an absent module into a `NoClassDefFoundError` at the
	 * first bitmap operation instead of a fallback at startup.
	 *
	 * @return the loaded kernels together with their vector width, or `null` when they are not usable
	 */
	@Nullable
	private static LoadedBitmapKernels loadVectorBitmapKernels() throws ReflectiveOperationException {
		final Class<?> implementation = Class.forName(
			VECTOR_BITMAP_KERNELS, true, VectorKernels.class.getClassLoader()
		);
		if (!((Boolean) implementation.getMethod("isSupported").invoke(null))) {
			return null;
		}
		final int vectorBitSize = (Integer) implementation.getMethod("vectorBitSize").invoke(null);
		return new LoadedBitmapKernels(
			(BitmapKernels) implementation.getDeclaredConstructor().newInstance(), vectorBitSize
		);
	}

	/**
	 * Runs every kernel of `candidate` against {@link ScalarBitmapKernels} on fixed inputs — all-zero,
	 * all-ones and two pseudo-random word arrays, in every ordered pair — plus a set of bit ranges that
	 * covers the empty, single-word, word-aligned and straddling cases, and the three aliasings a container
	 * operator can present to a fused kernel (a separate destination, the destination being the first
	 * operand, and both operands and the destination being one and the same array).
	 *
	 * This is insurance against a mis-intrinsified operation on a CPU that was never tested here: the cost
	 * is a few microseconds once per JVM, and the alternative is silently wrong cardinalities.
	 *
	 * Any throwable this raises is caught by the single failure boundary in the static initializer, which is
	 * why nothing is caught here: an operation the running JVM refuses outright is a failed self-test like
	 * any other, and it is reported the same way.
	 *
	 * @param candidate the freshly loaded vector implementation
	 * @return the name of the first kernel that disagreed, or `null` when all of them matched
	 */
	@Nullable
	private static String selfTest(@Nonnull final BitmapKernels candidate) {
		final BitmapKernels reference = ScalarBitmapKernels.INSTANCE;
		final long[][] samples = buildSelfTestSamples();
		for (int i = 0; i < samples.length; i++) {
			final long[] a = samples[i];
			if (candidate.cardinality(a) != reference.cardinality(a)) {
				return "cardinality";
			}
			for (int r = 0; r < SELF_TEST_RANGES.length; r += 2) {
				final int start = SELF_TEST_RANGES[r];
				final int end = SELF_TEST_RANGES[r + 1];
				if (candidate.cardinalityInRange(a, start, end) != reference.cardinalityInRange(a, start, end)) {
					return "cardinalityInRange";
				}
			}
			for (int j = 0; j < samples.length; j++) {
				final String mismatch = selfTestPair(candidate, reference, a, samples[j]);
				if (mismatch != null) {
					return mismatch;
				}
			}
			// the self-aliased shape a container operator reaches when both operands are the same array
			final String selfAliased = selfTestPair(candidate, reference, a, a);
			if (selfAliased != null) {
				return selfAliased;
			}
		}
		return null;
	}

	/**
	 * Runs the eight two-operand kernels for one ordered pair of samples, in all three destination aliasings.
	 *
	 * @param candidate the vector implementation under test
	 * @param reference the scalar implementation it must agree with
	 * @param a         first operand
	 * @param b         second operand
	 * @return the name of the first kernel that disagreed, or `null` when all of them matched
	 */
	@Nullable
	private static String selfTestPair(
		@Nonnull final BitmapKernels candidate,
		@Nonnull final BitmapKernels reference,
		@Nonnull final long[] a,
		@Nonnull final long[] b
	) {
		if (candidate.andCardinality(a, b) != reference.andCardinality(a, b)) {
			return "andCardinality";
		}
		if (candidate.orCardinality(a, b) != reference.orCardinality(a, b)) {
			return "orCardinality";
		}
		if (candidate.xorCardinality(a, b) != reference.xorCardinality(a, b)) {
			return "xorCardinality";
		}
		if (candidate.andNotCardinality(a, b) != reference.andNotCardinality(a, b)) {
			return "andNotCardinality";
		}
		for (int operation = 0; operation < FUSED_KERNEL_NAMES.length; operation++) {
			for (int shape = 0; shape < ALIASING_SHAPES.length; shape++) {
				final int aliasing = ALIASING_SHAPES[shape];
				final long[] expectedFirst = aliasing == ALIASING_SEPARATE ? a : a.clone();
				final long[] expectedSecond = aliasing == ALIASING_ALL_ONE ? expectedFirst : b;
				final long[] expectedOut = aliasing == ALIASING_SEPARATE ? new long[a.length] : expectedFirst;
				final long[] actualFirst = aliasing == ALIASING_SEPARATE ? a : a.clone();
				final long[] actualSecond = aliasing == ALIASING_ALL_ONE ? actualFirst : b;
				final long[] actualOut = aliasing == ALIASING_SEPARATE ? new long[a.length] : actualFirst;
				final int expectedCount = applyFused(reference, operation, expectedFirst, expectedSecond, expectedOut);
				final int actualCount = applyFused(candidate, operation, actualFirst, actualSecond, actualOut);
				if (expectedCount != actualCount || !Arrays.equals(expectedOut, actualOut)) {
					return FUSED_KERNEL_NAMES[operation];
				}
			}
		}
		return null;
	}

	/**
	 * Invokes one of the four fused kernels by index, so that the self-test can sweep them without
	 * repeating the same aliasing scaffolding four times.
	 *
	 * @param kernels   implementation to invoke
	 * @param operation index into {@link #FUSED_KERNEL_NAMES}
	 * @param a         first operand
	 * @param b         second operand
	 * @param out       destination
	 * @return the population count the kernel returned
	 */
	private static int applyFused(
		@Nonnull final BitmapKernels kernels,
		final int operation,
		@Nonnull final long[] a,
		@Nonnull final long[] b,
		@Nonnull final long[] out
	) {
		return switch (operation) {
			case 0 -> kernels.and(a, b, out);
			case 1 -> kernels.or(a, b, out);
			case 2 -> kernels.xor(a, b, out);
			case 3 -> kernels.andNot(a, b, out);
			default -> throw new IllegalArgumentException("Unknown fused kernel index: " + operation);
		};
	}

	/**
	 * Builds the four self-test samples: all-zero, all-ones and two pseudo-random word arrays drawn from a
	 * fixed seed with SplitMix64, so that the decision a JVM reaches never depends on the run.
	 *
	 * @return the samples, each {@link #SELF_TEST_WORDS} words long
	 */
	@Nonnull
	private static long[][] buildSelfTestSamples() {
		final long[] zeros = new long[SELF_TEST_WORDS];
		final long[] ones = new long[SELF_TEST_WORDS];
		Arrays.fill(ones, ~0L);
		final long[] randomDense = new long[SELF_TEST_WORDS];
		final long[] randomSparse = new long[SELF_TEST_WORDS];
		long state = SELF_TEST_SEED;
		for (int k = 0; k < SELF_TEST_WORDS; k++) {
			state += 0x9E3779B97F4A7C15L;
			long word = state;
			word = (word ^ (word >>> 30)) * 0xBF58476D1CE4E5B9L;
			word = (word ^ (word >>> 27)) * 0x94D049BB133111EBL;
			word ^= word >>> 31;
			randomDense[k] = word;
			// AND-ing three draws leaves roughly one bit in eight set, which exercises the sparse shape
			randomSparse[k] = word & (word >>> 17) & (word << 23);
		}
		return new long[][]{zeros, ones, randomDense, randomSparse};
	}

	/**
	 * Renders the decision as the single line {@link #summary()} returns.
	 *
	 * @param bitmapDecision  outcome for the dense-container kernels
	 * @param vectorBitSize   width of the loaded vector shape in bits, `0` when none loaded
	 * @param selfTestFailure name of the kernel that failed the self-test, `null` when none did
	 * @param failure         the throwable the failure boundary caught, rendered, `null` when none was
	 * @param arrayDecision   outcome for the sparse-container kernels
	 * @return the one-line summary
	 */
	@Nonnull
	private static String buildSummary(
		@Nonnull final KernelDecision bitmapDecision,
		final int vectorBitSize,
		@Nullable final String selfTestFailure,
		@Nullable final String failure,
		@Nonnull final KernelDecision arrayDecision
	) {
		final StringBuilder summary = new StringBuilder(256);
		summary.append("roaring vector kernels: bitmap=");
		if (bitmapDecision == KernelDecision.VECTOR) {
			summary.append("vector(").append(vectorBitSize).append("-bit)");
		} else {
			summary.append("scalar");
		}
		summary.append(" array=")
			.append(arrayDecision == KernelDecision.VECTOR ? "vector" : "scalar")
			.append(" (bitmap: ")
			.append(bitmapDecision.reason(BITMAP_SWITCH_PROPERTY, selfTestFailure));
		if (failure != null) {
			summary.append(", ").append(failure);
		}
		summary.append("; array: ")
			.append(arrayDecision.reason(ARRAY_SWITCH_PROPERTY, null))
			.append(')');
		return summary.toString();
	}

	/**
	 * Answers whether a switch property was explicitly set to `false`. Anything else — unset, empty, any
	 * other value — leaves the kernel family enabled.
	 *
	 * @param property name of the system property
	 * @return `true` when the property reads `false`
	 */
	private static boolean isSwitchedOff(@Nonnull final String property) {
		return "false".equalsIgnoreCase(System.getProperty(property));
	}

	/**
	 * Outcome of the gate sequence for one kernel family, carrying the reason it reached.
	 */
	enum KernelDecision {
		/**
		 * Every gate passed; the vector implementation is in force.
		 */
		VECTOR,
		/**
		 * The kill switch turned every vector kernel off.
		 */
		DISABLED_GLOBALLY,
		/**
		 * This kernel family's own switch turned it off.
		 */
		DISABLED_PER_KERNEL,
		/**
		 * `jdk.incubator.vector` is not resolved in the boot layer — most often because the launcher did not
		 * pass `--add-modules jdk.incubator.vector`.
		 */
		MODULE_ABSENT,
		/**
		 * The JVM runs without an optimizing JIT, where a vector kernel would be slower than the scalar one.
		 */
		JIT_UNUSABLE,
		/**
		 * No vector implementation could be loaded, or it declined the running CPU's vector width.
		 */
		VECTOR_UNAVAILABLE,
		/**
		 * A vector kernel disagreed with its scalar twin on fixed inputs.
		 */
		SELF_TEST_FAILED;

		/**
		 * Renders this outcome as the fragment the summary line carries.
		 *
		 * @param kernelSwitchProperty name of this family's own switch, named when it was the deciding one
		 * @param selfTestFailure      name of the kernel that failed the self-test, when one did
		 * @return a human-readable reason
		 */
		@Nonnull
		String reason(@Nonnull final String kernelSwitchProperty, @Nullable final String selfTestFailure) {
			return switch (this) {
				case VECTOR -> "jdk.incubator.vector present, JIT compiler available, self-test passed";
				case DISABLED_GLOBALLY -> "disabled by -D" + GLOBAL_SWITCH_PROPERTY + "=false";
				case DISABLED_PER_KERNEL -> "disabled by -D" + kernelSwitchProperty + "=false";
				case MODULE_ABSENT -> "module " + VECTOR_MODULE_NAME + " absent";
				case JIT_UNUSABLE -> "JIT compiler unavailable";
				case VECTOR_UNAVAILABLE -> "no usable vector implementation";
				case SELF_TEST_FAILED -> "self-test mismatch in " + selfTestFailure;
			};
		}
	}

	/**
	 * A successfully loaded vector implementation together with the width of the shape it runs on.
	 *
	 * @param kernels       the loaded implementation
	 * @param vectorBitSize width of its vector shape, in bits
	 */
	private record LoadedBitmapKernels(@Nonnull BitmapKernels kernels, int vectorBitSize) {
	}

}
