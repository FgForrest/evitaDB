/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.transaction.memory;

import io.evitadb.test.annotation.RequiresDefaultWarmUpWritePath;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.AssertionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static io.evitadb.test.TestTags.SLOW;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * The shared generational fuzz harness behind the `LongRunningSavepoint*Test` suites: a scenario is declared **once**
 * and is then driven through the per-entity savepoint mechanism in **both** phases evitaDB writes in.
 *
 * - **ALIVE** — the write goes into a transactional diff layer and
 *   {@link TransactionalLayerMaintainer#rollbackSavepoint(Savepoint)} is what reverts it. The transaction is committed
 *   afterwards, so {@link TransactionalLayerMaintainer#verifyLayerWasFullySwept()} additionally proves the restore left
 *   no dangling or stale layer.
 * - **WARM_UP** — there is no transaction and no diff layer at all: the write lands straight on the delegate structure
 *   and a {@link WarmUpSavepoint} has to rewind it from the inverses the structures journal themselves.
 *
 * Both phases run the SAME scenario, which is the point: the two mechanisms have to agree on what a savepoint means,
 * and a scenario that can tell them apart has found a bug in one of them.
 *
 * **What a subclass declares** is one factory, {@link #newGeneration(Random)}, returning a {@link FuzzGeneration} — the
 * fixture for a single generation. It inherits all four test methods. The fixture is usually the structure under test
 * paired with an in-test reference model of its logical content, which is what lets randomized mutations stay coherent.
 *
 * **The shape of one generation** is the same in all four methods:
 *
 * 1. {@link #newGeneration(Random)} seeds a fresh structure outside any savepoint;
 * 2. {@link FuzzGeneration#applyBaselineOperations(Random)} stands for changes of *prior* entities — they must SURVIVE;
 * 3. {@link FuzzGeneration#contents()} reads the logical content into an `.equals`-comparable value — the oracle;
 * 4. a savepoint is opened and {@link FuzzGeneration#applySavepointOperations(Random)} stands for the *failing* entity;
 * 5. **the structure is read again while the savepoint is still open** (see below);
 * 6. the savepoint is rolled back (content must equal step 3) or committed (content must equal step 5).
 *
 * **Step 5 — the mid-savepoint read — is a first-class step, not an assertion of convenience.** It reads the whole
 * structure through the same public views, iterators and cursors a query would, at the one moment those views are
 * hardest to get right: mutations have been applied but are not yet permanent. It catches three failures that a read
 * taken only at the end cannot:
 *
 * - a view or wrapper path that **bypasses journalling** — it shows up as a mutation the read cannot see, or as a read
 *   that throws on a structure the mechanism left half-updated;
 * - a **stale read**, where the view still reports the pre-savepoint state the rollback is about to restore anyway, so
 *   the final comparison would pass for the wrong reason;
 * - most valuably, a **memoized cache that the rollback fails to invalidate**. Several structures memoize derived state
 *   lazily on read ({@link io.evitadb.index.bitmap.TransactionalBitmap}'s cardinality, `FilterIndex`, `RangeIndex`'s
 *   enveloping-now cache, the price indexes). Warm-up rollback deliberately INVALIDATES rather than restores those
 *   caches, and a read taken only after the rollback would repopulate them from correct state and never notice a
 *   missing invalidation. Reading here is what populates them from MUTATED state first, so a rollback that forgets one
 *   is caught by the very next read.
 *
 * The step doubles as the non-vacuity guard: it asserts the mid-savepoint content DIFFERS from the pre-savepoint
 * oracle, so no generation can pass by rolling back nothing.
 *
 * **Warm-up mode switches a process-wide flag, so it runs under a resource lock — and briefly.**
 * {@link WarmUpSavepoint#isEnabled()} is a static, and this module runs its test CLASSES concurrently inside one JVM
 * (`parallel=all` in the module's `pom.xml`; methods within a class stay sequential). A suite that flipped the flag
 * without coordinating would therefore change the write path under every catalog a concurrently running suite happens
 * to be bulk-loading. The two warm-up methods take {@link RequiresDefaultWarmUpWritePath#RESOURCE} in
 * {@link ResourceAccessMode#READ_WRITE} and every catalog-writing suite in this module carries
 * {@link RequiresDefaultWarmUpWritePath}, which takes the same resource in read mode — read mode is shared, so those
 * suites still run concurrently with each other and with everything else, and only overlap with a flag flip is
 * excluded.
 *
 * `Resources#GLOBAL` would have removed the need to annotate the other side, but it is the wrong tool: an exclusive
 * GLOBAL lock forces the whole discovered tree into single-threaded execution, which would turn a full-matrix sweep of
 * this module from tens of minutes into hours.
 *
 * Since the warm-up methods do serialize against one another, their budget is
 * {@link #WARM_UP_FUZZ_SECONDS_PROPERTY} seconds (default {@link #DEFAULT_WARM_UP_FUZZ_SECONDS}) rather than the whole
 * minute the ALIVE methods get; raise it for a deep sweep. Generations are cheap enough that seconds still buy
 * thousands of them.
 *
 * The flag is switched on for exactly that window, so the mechanism's runtime backstop
 * ({@link WarmUpSavepoint#verifyRollbackSupported(TransactionalLayerCreator)}, which reads the flag first) is live
 * while fuzzing: any structure a scenario reaches whose delegate branch was never ported fails the generation that
 * reaches it, rather than being silently left un-rewindable by a rollback that reports success.
 *
 * @param <R> the `.equals`-comparable value the oracle reads the structure's logical content into
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see WarmUpSavepoint
 * @see AssertionUtils#assertSavepointRollbackRestores
 * @see AssertionUtils#assertWarmUpSavepointRollbackRestores
 */
public abstract class AbstractSavepointFuzzTest<R> implements TimeBoundedTestSupport {
	/**
	 * System property setting how many seconds each warm-up fuzz method generates for. Kept separate from the
	 * `interval` the ALIVE methods use because warm-up methods run exclusively (see the type JavaDoc), so their budget
	 * is paid serially by the whole run rather than shared across the surefire threads.
	 */
	public static final String WARM_UP_FUZZ_SECONDS_PROPERTY = "warmUpFuzz.seconds";
	/**
	 * Default warm-up budget per method. Chosen so a full-matrix sweep pays a few minutes in total for the exclusive
	 * half, while still running thousands of generations of every scenario.
	 */
	public static final int DEFAULT_WARM_UP_FUZZ_SECONDS = 10;

	private static final int WARM_UP_FUZZ_SECONDS =
		Integer.getInteger(WARM_UP_FUZZ_SECONDS_PROPERTY, DEFAULT_WARM_UP_FUZZ_SECONDS);

	/**
	 * The warm-up fuzz budget in seconds, resolved from {@link #WARM_UP_FUZZ_SECONDS_PROPERTY}. Exposed so a suite that
	 * drives warm-up savepoints by hand rather than through this harness — `LongRunningSavepointFuzzFrameworkTest`,
	 * which validates the oracle helpers themselves and must stay independent of the harness built on them — budgets
	 * its exclusive methods the same way.
	 *
	 * @return how many seconds one warm-up fuzz method may generate for
	 */
	public static int warmUpFuzzSeconds() {
		return WARM_UP_FUZZ_SECONDS;
	}

	@ParameterizedTest(name = "Savepoint rollback restores the exact pre-savepoint state")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint rollback restores the exact pre-savepoint state")
	protected void shouldRestoreStateOnSavepointRollback(@Nonnull GenerationalTestInput input) {
		runFor(input, echoEachIterations(), 0L, (random, iteration) -> {
			final FuzzGeneration<R> generation = newGeneration(random);
			final AtomicReference<R> preSavepoint = new AtomicReference<>();
			assertRollbackRestores(
				generation.subject(),
				() -> {
					generation.applyBaselineOperations(random);
					preSavepoint.set(generation.contents());
				},
				generation::contents,
				() -> {
					generation.applySavepointOperations(random);
					assertMidSavepointReadSeesMutations(generation, preSavepoint.get());
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "Savepoint commit keeps the in-savepoint state")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Savepoint commit keeps the in-savepoint state")
	protected void shouldKeepStateOnSavepointCommit(@Nonnull GenerationalTestInput input) {
		runFor(input, echoEachIterations(), 0L, (random, iteration) -> {
			final FuzzGeneration<R> generation = newGeneration(random);
			final AtomicReference<R> preSavepoint = new AtomicReference<>();
			assertCommitKeeps(
				generation.subject(),
				() -> {
					generation.applyBaselineOperations(random);
					preSavepoint.set(generation.contents());
				},
				generation::contents,
				() -> {
					generation.applySavepointOperations(random);
					assertMidSavepointReadSeesMutations(generation, preSavepoint.get());
				}
			);
			return iteration + 1;
		});
	}

	@ParameterizedTest(name = "Warm-up savepoint rollback restores the exact pre-savepoint state")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Warm-up savepoint rollback restores the exact pre-savepoint state")
	@ResourceLock(value = RequiresDefaultWarmUpWritePath.RESOURCE, mode = ResourceAccessMode.READ_WRITE)
	protected void shouldRestoreStateOnWarmUpSavepointRollback(@Nonnull GenerationalTestInput input) {
		abortWhenWarmUpModeExcluded();
		runWithWarmUpAtomicityEnabled(
			() -> runForSeconds(input, WARM_UP_FUZZ_SECONDS, echoEachIterations(), 0L, (random, iteration) -> {
				final AtomicReference<R> preSavepoint = new AtomicReference<>();
				AssertionUtils.assertWarmUpSavepointRollbackRestores(
					newGeneration(random),
					tested -> {
						tested.applyBaselineOperations(random);
						preSavepoint.set(tested.contents());
					},
					FuzzGeneration::contents,
					tested -> {
						tested.applySavepointOperations(random);
						assertMidSavepointReadSeesMutations(tested, preSavepoint.get());
					}
				);
				return iteration + 1;
			})
		);
	}

	@ParameterizedTest(name = "Warm-up savepoint commit keeps the in-savepoint state")
	@Tag(SLOW)
	@ArgumentsSource(TimeArgumentProvider.class)
	@DisplayName("Warm-up savepoint commit keeps the in-savepoint state")
	@ResourceLock(value = RequiresDefaultWarmUpWritePath.RESOURCE, mode = ResourceAccessMode.READ_WRITE)
	protected void shouldKeepStateOnWarmUpSavepointCommit(@Nonnull GenerationalTestInput input) {
		abortWhenWarmUpModeExcluded();
		runWithWarmUpAtomicityEnabled(
			() -> runForSeconds(input, WARM_UP_FUZZ_SECONDS, echoEachIterations(), 0L, (random, iteration) -> {
				final AtomicReference<R> preSavepoint = new AtomicReference<>();
				AssertionUtils.assertWarmUpSavepointCommitKeeps(
					newGeneration(random),
					tested -> {
						tested.applyBaselineOperations(random);
						preSavepoint.set(tested.contents());
					},
					FuzzGeneration::contents,
					tested -> {
						tested.applySavepointOperations(random);
						assertMidSavepointReadSeesMutations(tested, preSavepoint.get());
					}
				);
				return iteration + 1;
			})
		);
	}

	/**
	 * Builds one generation of this scenario: a fresh structure (and, where the scenario needs one, its reference
	 * model) seeded with randomized content. Called OUTSIDE any transaction and any savepoint, so the seeding itself is
	 * never journalled and is the state a rollback is measured against.
	 *
	 * @param random the generation's source of randomness — the only one, so a failing run reproduces from its seed
	 * @return the fixture this generation operates on
	 */
	@Nonnull
	protected abstract FuzzGeneration<R> newGeneration(@Nonnull Random random);

	/**
	 * Says why this scenario cannot run in WARM_UP mode, or `null` (the default) when it can. A non-`null` reason
	 * aborts the two warm-up methods with that reason attached, so the exclusion is visible in the test report rather
	 * than being an absence nobody notices.
	 *
	 * Override only for a scenario that has no warm-up counterpart at all — one driving a structure that exists solely
	 * inside a transaction. A scenario that merely FAILS in warm-up mode is a bug to fix, not an exclusion to declare.
	 *
	 * @return the reason this scenario is ALIVE-only, or `null` when it runs in both phases
	 */
	@Nullable
	protected String warmUpExclusionReason() {
		return null;
	}

	/**
	 * How often the generation loop prints a progress dot. Lower it for a scenario whose generations are expensive
	 * enough that a thousand of them take visibly long.
	 *
	 * @return the number of generations between two progress dots
	 */
	protected int echoEachIterations() {
		return 1000;
	}

	/**
	 * One generation's fixture: the structure under test, usually paired with an in-test reference model of its logical
	 * content so randomized mutations can be generated that keep the two in lockstep.
	 *
	 * A fresh instance is built for every generation, so it may hold whatever per-generation state the scenario needs
	 * (sequences reserved for marker mutations, the model, and so on) without any resetting.
	 *
	 * @param <R> the `.equals`-comparable value {@link #contents()} reads the structure's logical content into
	 */
	public interface FuzzGeneration<R> {

		/**
		 * Returns the structure whose transactional state the ALIVE-mode transaction commits at the end of a
		 * generation, which is what makes the commit-time layer sweep meaningful.
		 *
		 * Unused in WARM_UP mode: there is no transaction there, and the delegate IS the committed state.
		 *
		 * @return the transactional structure under test
		 */
		@Nonnull
		TransactionalStateProducer<?> subject();

		/**
		 * Reads the structure's WHOLE logical content into an `.equals`-comparable value — the oracle every assertion
		 * in this harness compares against. Read it through the public views, iterators or cursors a query would use
		 * rather than through internal fields: this reader is also the mid-savepoint read step (see the enclosing
		 * type's JavaDoc), and it can only catch a view that bypasses journalling if it actually goes through it.
		 *
		 * Must not mutate the structure, and must be total — it is invoked while a savepoint is open, on a structure
		 * that is mid-mutation from the mechanism's point of view.
		 *
		 * @return the structure's logical content
		 */
		@Nonnull
		R contents();

		/**
		 * Applies a randomized batch of mutations BEFORE the savepoint is opened. These stand for changes made by
		 * *prior* entities of the same transaction or bulk load, and must survive whatever the savepoint does.
		 *
		 * @param random the generation's source of randomness
		 */
		void applyBaselineOperations(@Nonnull Random random);

		/**
		 * Applies a randomized batch of mutations WHILE the savepoint is open. These stand for the *failing* entity:
		 * they must be reverted by a rollback and kept by a commit.
		 *
		 * **The batch must make at least one guaranteed-visible change**, and the harness asserts it did (see the
		 * mid-savepoint read in the enclosing type's JavaDoc) — a batch that can randomly come out as a no-op fails
		 * rather than passing vacuously. The convention that satisfies it is to END with a marker mutation drawn from
		 * a reserved range the randomized operations never touch.
		 *
		 * **Ending, not starting.** A marker applied first is not a guarantee at all: it enters the scenario's own
		 * reference model, so the randomized operations that follow can pick it back out and undo it, leaving the
		 * batch a net no-op. That is precisely what the mid-savepoint read caught when this harness was introduced.
		 *
		 * @param random the generation's source of randomness
		 */
		void applySavepointOperations(@Nonnull Random random);

	}

	/**
	 * Reads the structure while the savepoint is still open and asserts the read sees the in-savepoint mutations. See
	 * the type JavaDoc for what this step is for; in short, it is the only read taken at the moment views, wrappers and
	 * memoized caches are half-way through a mutation, and it is what makes a lazily memoized cache populate from
	 * MUTATED state so a rollback that forgets to invalidate it is caught.
	 *
	 * @param generation   the fixture being mutated
	 * @param preSavepoint the content read before the savepoint was opened
	 */
	private void assertMidSavepointReadSeesMutations(
		@Nonnull FuzzGeneration<R> generation,
		@Nonnull R preSavepoint
	) {
		assertNotEquals(
			preSavepoint, generation.contents(),
			"A read taken inside the savepoint must see the mutations applied while it was open - either the batch " +
				"was vacuous (so the rollback assertion could pass without reverting anything), or a view is serving " +
				"pre-savepoint state!"
		);
	}

	/**
	 * Aborts the calling warm-up test when this scenario declared itself ALIVE-only, carrying the declared reason into
	 * the test report.
	 */
	private void abortWhenWarmUpModeExcluded() {
		final String reason = warmUpExclusionReason();
		if (reason != null) {
			abort("This scenario has no WARM_UP counterpart: " + reason);
		}
	}

	/**
	 * Switches the warm-up atomicity mechanism on for the duration of `fuzzing` and restores the previous value
	 * afterwards. The flag is process-wide, which is why the callers hold
	 * {@link RequiresDefaultWarmUpWritePath#RESOURCE} exclusively — see the type JavaDoc.
	 *
	 * Callers outside this class MUST hold {@link RequiresDefaultWarmUpWritePath#RESOURCE} in
	 * {@link ResourceAccessMode#READ_WRITE} for the duration, or the flip is visible to concurrently running suites.
	 *
	 * @param fuzzing the generation loop to run with the mechanism enabled
	 */
	public static void runWithWarmUpAtomicityEnabled(@Nonnull Runnable fuzzing) {
		final boolean previouslyEnabled = WarmUpSavepoint.isEnabled();
		WarmUpSavepoint.setEnabled(true);
		try {
			fuzzing.run();
		} finally {
			WarmUpSavepoint.setEnabled(previouslyEnabled);
		}
	}

	/**
	 * Bridges to {@link AssertionUtils#assertSavepointRollbackRestores} with the subject's committed-state type
	 * captured, so a scenario never has to name a diff-layer type it does not otherwise mention.
	 */
	private static <S, C> void assertRollbackRestores(
		@Nonnull TransactionalStateProducer<S> subject,
		@Nonnull Runnable baselineOperations,
		@Nonnull Supplier<C> contentsReader,
		@Nonnull Runnable savepointOperations
	) {
		AssertionUtils.assertSavepointRollbackRestores(
			subject,
			tested -> baselineOperations.run(),
			tested -> contentsReader.get(),
			tested -> savepointOperations.run()
		);
	}

	/**
	 * Bridges to {@link AssertionUtils#assertSavepointCommitKeeps}, mirroring
	 * {@link #assertRollbackRestores(TransactionalStateProducer, Runnable, Supplier, Runnable)}.
	 */
	private static <S, C> void assertCommitKeeps(
		@Nonnull TransactionalStateProducer<S> subject,
		@Nonnull Runnable baselineOperations,
		@Nonnull Supplier<C> contentsReader,
		@Nonnull Runnable savepointOperations
	) {
		AssertionUtils.assertSavepointCommitKeeps(
			subject,
			tested -> baselineOperations.run(),
			tested -> contentsReader.get(),
			tested -> savepointOperations.run()
		);
	}

}
