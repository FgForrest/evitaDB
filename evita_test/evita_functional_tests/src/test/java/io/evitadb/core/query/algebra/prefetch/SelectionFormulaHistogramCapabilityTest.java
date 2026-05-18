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

package io.evitadb.core.query.algebra.prefetch;

import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.FilteredPriceRecords;
import io.evitadb.core.query.algebra.price.filteredPriceRecords.ResolvedFilteredPriceRecords;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.core.query.algebra.price.termination.LowestPriceTerminationFormula;
import io.evitadb.core.query.algebra.price.termination.PriceEvaluationContext;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.price.model.PriceIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Currency;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.PRICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link SelectionFormula}'s histogram capability propagation and per-inner-record record merging,
 * which `PriceHistogramComputer` relies on so the histogram bypass continues to fire when the planner
 * wraps the histogram-aware {@link LowestPriceTerminationFormula} in a prefetch-eligible
 * {@link SelectionFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("SelectionFormula histogram capability propagation")
@Tag(ENGINE)
@Tag(PRICE)
@Tag(HISTOGRAM)
class SelectionFormulaHistogramCapabilityTest {

	private static final Currency CZK = Currency.getInstance("CZK");

	@Nested
	@DisplayName("Capability probe propagation")
	class CapabilityPropagationTest {

		@Test
		@DisplayName("should expose histogram capability when all inner accessors expose it")
		void shouldExposeHistogramCapabilityWhenAllInnerAccessorsExpose() {
			final LowestPriceTerminationFormula leftLp = newLp(new ArrayBitmap(1, 2), true);
			final LowestPriceTerminationFormula rightLp = newLp(new ArrayBitmap(3, 4), true);
			final SelectionFormula selection = new SelectionFormula(
				new AndFormula(leftLp, rightLp), new NoopBitmapFilter()
			);

			// every inner accessor opted in to histogram collection at construction time — the wrapper
			// must propagate the capability so PriceHistogramComputer's bypass continues to fire
			assertTrue(selection.exposesPerInnerRecordHistogramRecords());
		}

		@Test
		@DisplayName("should not expose histogram capability when any inner accessor is missing it")
		void shouldNotExposeHistogramCapabilityWhenAnyInnerAccessorMissing() {
			final LowestPriceTerminationFormula withFlag = newLp(new ArrayBitmap(1, 2), true);
			final LowestPriceTerminationFormula withoutFlag = newLp(new ArrayBitmap(3, 4), false);
			final SelectionFormula selection = new SelectionFormula(
				new AndFormula(withFlag, withoutFlag), new NoopBitmapFilter()
			);

			// the "all-true" semantics mean a single un-flagged accessor poisons the wrapper's capability —
			// otherwise the histogram producer would try to call `getFilteredPriceRecordsForHistogram` on
			// the un-flagged LP and trip its `GenericEvitaInternalError` guard
			assertFalse(selection.exposesPerInnerRecordHistogramRecords());
		}

		@Test
		@DisplayName("should not expose histogram capability when no inner accessors exist")
		void shouldNotExposeHistogramCapabilityWhenNoInnerAccessorsExist() {
			final SelectionFormula selection = new SelectionFormula(
				new ConstantFormula(new ArrayBitmap(1, 2, 3)), new NoopBitmapFilter()
			);

			// no FilteredPriceRecordAccessor in the delegate subtree — wrapping a non-price formula must
			// report `false` so the histogram producer falls back to the collector path
			assertFalse(selection.exposesPerInnerRecordHistogramRecords());
		}
	}

	@Nested
	@DisplayName("Merged histogram records")
	class MergedHistogramRecordsTest {

		@Test
		@DisplayName("should delegate histogram records to the single inner accessor")
		void shouldDelegateHistogramRecordsToSingleInnerAccessor() {
			// single-accessor fast path — SelectionFormula short-circuits and returns the LP's records
			// directly without allocating an intermediate merge array
			final LowestPriceTerminationFormula lp = newLp(EmptyFormula.INSTANCE, true);
			// trigger compute() on empty input — populates `perInnerRecordPriceRecords` so the LP's
			// histogram accessor returns a non-null value without needing executionContext setup
			lp.compute();
			final FilteredPriceRecords expected = lp.getFilteredPriceRecordsForHistogram(null);
			final SelectionFormula selection = new SelectionFormula(lp, new NoopBitmapFilter());

			final FilteredPriceRecords actual = selection.getFilteredPriceRecordsForHistogram(null);

			// fast path returns the exact same instance (no merging) — this is a deliberate optimisation
			assertSame(expected, actual);
		}

		/**
		 * The size-1 fast path must consult `exposesPerInnerRecordHistogramRecords` on the inner accessor
		 * before delegating to its histogram-side accessor. When the inner accessor does NOT expose the
		 * per-inner-record side-output (the common case for un-flagged LPs), the fast path must instead
		 * fall back to the per-entity `getFilteredPriceRecords(...)` — matching the default interface
		 * behaviour for non-histogram-aware accessors.
		 */
		@Test
		@DisplayName("should fall back to per-entity records when single inner accessor misses capability")
		void shouldFallBackToPerEntityRecordsWhenSingleInnerAccessorMissesCapability() {
			final LowestPriceTerminationFormula unflaggedLp = newLp(EmptyFormula.INSTANCE, false);
			// trigger compute() so the un-flagged LP populates `filteredPriceRecords` (per-entity side)
			// while leaving `perInnerRecordPriceRecords` deliberately null — the fast path must therefore
			// route to the per-entity records via the capability check rather than tripping the LP's guard
			unflaggedLp.compute();
			final FilteredPriceRecords expected = unflaggedLp.getFilteredPriceRecords(null);
			final SelectionFormula selection = new SelectionFormula(unflaggedLp, new NoopBitmapFilter());

			final FilteredPriceRecords actual = selection.getFilteredPriceRecordsForHistogram(null);

			// fast path must mirror the default interface behaviour for non-histogram-aware accessors —
			// delegate to `getFilteredPriceRecords(...)` rather than propagating the LP's guard error
			assertSame(expected, actual);
		}

		/**
		 * Empty-merge branch: when every inner accessor exposes empty per-inner-record records, the
		 * merged array has length 0 and `SelectionFormula.getFilteredPriceRecordsForHistogram` returns
		 * the shared {@link FilteredPriceRecords#EMPTY} singleton. Two flag-on LPs sharing the same
		 * empty input drive the size>=2 branch through the empty-result path.
		 */
		@Test
		@DisplayName("should return EMPTY singleton when merged records are empty")
		void shouldReturnEmptyResolvedRecordsWhenMergedRecordsAreEmpty() {
			final LowestPriceTerminationFormula leftLp = newLp(EmptyFormula.INSTANCE, true);
			final LowestPriceTerminationFormula rightLp = newLp(EmptyFormula.INSTANCE, true);
			// pre-compute both LPs so each publishes its (empty) per-inner-record side-output without
			// needing executionContext setup on the SelectionFormula
			leftLp.compute();
			rightLp.compute();
			final SelectionFormula selection = new SelectionFormula(
				new AndFormula(leftLp, rightLp), new NoopBitmapFilter()
			);

			final FilteredPriceRecords actual = selection.getFilteredPriceRecordsForHistogram(null);

			// size>=2 branch — merged.length == 0 short-circuits to the shared empty singleton instead
			// of allocating a fresh ResolvedFilteredPriceRecords; this is the only legitimate use of the
			// EMPTY singleton on the histogram path
			assertSame(FilteredPriceRecords.EMPTY, actual);
		}

		/**
		 * Size>=2 merge happy path with two flag-on LPs whose empty per-inner-record drains are
		 * concatenated by `mergePerInnerRecordHistogramRecords`. The current implementation collapses
		 * a zero-length merged array to the EMPTY singleton, so the assertable invariant at unit
		 * level is that the merge does not throw and returns a valid {@link FilteredPriceRecords}.
		 */
		@Test
		@DisplayName("should merge histogram records from two flag-on inner accessors without throwing")
		void shouldMergeRecordsFromTwoOrMoreInnerAccessors() {
			final LowestPriceTerminationFormula leftLp = newLp(EmptyFormula.INSTANCE, true);
			final LowestPriceTerminationFormula rightLp = newLp(EmptyFormula.INSTANCE, true);
			leftLp.compute();
			rightLp.compute();
			final SelectionFormula selection = new SelectionFormula(
				new AndFormula(leftLp, rightLp), new NoopBitmapFilter()
			);

			// the size>=2 branch must run the merge loop in mergePerInnerRecordHistogramRecords without
			// tripping; the actual concatenation cardinality of non-empty drains is exercised by the
			// functional histogram tests against real catalogs
			final FilteredPriceRecords actual = selection.getFilteredPriceRecordsForHistogram(null);

			// must produce a non-null FilteredPriceRecords instance — either the EMPTY singleton (when
			// merged.length == 0) or a freshly allocated ResolvedFilteredPriceRecords carrying the merge
			final boolean validShape = actual == FilteredPriceRecords.EMPTY
				|| actual instanceof ResolvedFilteredPriceRecords;
			assertTrue(validShape, "merge must return EMPTY singleton or ResolvedFilteredPriceRecords");
		}

		/**
		 * Capability probe is memoised — the first call walks the delegate sub-tree, the second call
		 * reads the cached boolean. Two consecutive calls must therefore return the same value and
		 * cannot diverge even if the delegate's accessor list were rebuilt (it isn't — accessor lists
		 * are immutable after construction, but the memoisation guards against accidental re-walks).
		 */
		@Test
		@DisplayName("should memoise capability probe across consecutive calls")
		void shouldMemoiseCapabilityProbeAcrossCalls() {
			final LowestPriceTerminationFormula lp = newLp(new ArrayBitmap(1, 2, 3), true);
			final SelectionFormula selection = new SelectionFormula(lp, new NoopBitmapFilter());

			final boolean first = selection.exposesPerInnerRecordHistogramRecords();
			final boolean second = selection.exposesPerInnerRecordHistogramRecords();

			// both reads must agree; this is a smoke test of `memoizedExposesPerInnerRecordHistogramRecords`
			// — divergence would point at a thread-safety bug or accidental probe re-evaluation
			assertTrue(first);
			assertTrue(second);
		}

		/**
		 * When the inner-accessor set is mixed (one flag-on, one flag-off LP), the size>=2 branch of
		 * `SelectionFormula.getFilteredPriceRecordsForHistogram` must short-circuit on the same
		 * capability probe the size-1 fast path uses, falling back to per-entity records rather than
		 * routing the flag-off LP through its histogram side-output (which would trip the LP's guard).
		 * This mirrors the default interface behaviour for non-histogram-aware accessors.
		 */
		@Test
		@DisplayName("should fall back to per-entity records when accessor set is mixed")
		void shouldFallBackToPerEntityRecordsWhenAccessorSetIsMixed() {
			final LowestPriceTerminationFormula flagOn = newLp(EmptyFormula.INSTANCE, true);
			final LowestPriceTerminationFormula flagOff = newLp(EmptyFormula.INSTANCE, false);
			flagOn.compute();
			flagOff.compute();
			final SelectionFormula selection = new SelectionFormula(
				new AndFormula(flagOn, flagOff), new NoopBitmapFilter()
			);
			// size>=2 fallback routes through `this.getFilteredPriceRecords()` which asserts a non-null
			// execution context — the production planner always sets one via initialize() before any
			// histogram fabrication, so the unit test mirrors that invariant rather than testing an
			// uninitialised formula
			initializeForNonPrefetch(selection);

			// graceful-fallback contract: the wrapper consults exposesPerInnerRecordHistogramRecords()
			// before entering the size>=2 merge loop. A flag-off inner accessor poisons the wrapper's
			// capability, so the producer reads per-entity records instead of tripping the flag-off LP.
			assertFalse(selection.exposesPerInnerRecordHistogramRecords());
			final FilteredPriceRecords actual = selection.getFilteredPriceRecordsForHistogram(null);

			// no exception, no merge attempt — the wrapper produced a valid per-entity FilteredPriceRecords
			// matching what `getFilteredPriceRecords(...)` would return. `getFilteredPriceRecords` allocates
			// a fresh ResolvedFilteredPriceRecords per call, so identity comparison would not hold; the
			// invariant under test is "fallback returns per-entity shape", which is captured by the
			// instance check plus an empty-array assertion (both LP delegates wrap EmptyFormula.INSTANCE).
			assertInstanceOf(ResolvedFilteredPriceRecords.class, actual);
			assertEquals(0, ((ResolvedFilteredPriceRecords) actual).getPriceRecords().length);
		}
	}

	/**
	 * Wraps the supplied {@link SelectionFormula} in a minimal {@link QueryExecutionContext} so its
	 * `getFilteredPriceRecords(...)` fallback branch can run in unit tests without spinning up the full
	 * engine. The context is non-prefetch (so the fallback follows the delegate path) and uses a Mockito
	 * mock for the {@link QueryPlanningContext} pointer because the only thing the formula reads is the
	 * `getPrefetchedEntities()` accessor on the execution context itself.
	 *
	 * @param formula the {@link SelectionFormula} to initialise before exercising its fallback branches
	 */
	private static void initializeForNonPrefetch(@Nonnull SelectionFormula formula) {
		final QueryExecutionContext context = new QueryExecutionContext(
			Mockito.mock(QueryPlanningContext.class),
			false,
			null,
			(aClass, sealedEntity) -> {
				throw new UnsupportedOperationException();
			}
		);
		formula.initialize(context);
	}

	/**
	 * Creates a {@link LowestPriceTerminationFormula} wrapping the supplied {@link Formula} delegate with a
	 * sane default `basic`/`CZK`/`WITH_TAX`/`ALL_RECORD_FILTER` configuration.
	 *
	 * @param delegate the inner formula used as the LP's delegate
	 * @param collectPerInnerRecordPrices `true` to construct the LP with the histogram side-output flag
	 * @return a freshly constructed LP carrying the requested flag
	 */
	@Nonnull
	private static LowestPriceTerminationFormula newLp(@Nonnull Formula delegate, boolean collectPerInnerRecordPrices) {
		return new LowestPriceTerminationFormula(
			delegate,
			new PriceEvaluationContext(null, new PriceIndexKey("basic", CZK, PriceInnerRecordHandling.NONE)),
			QueryPriceMode.WITH_TAX,
			PricePredicate.ALL_RECORD_FILTER,
			collectPerInnerRecordPrices
		);
	}

	/**
	 * Convenience wrapper that builds a {@link LowestPriceTerminationFormula} whose delegate is a
	 * {@link ConstantFormula} over the supplied bitmap. Used when the test wants to talk about "an LP with
	 * primary keys X, Y" rather than constructing the formula machinery inline.
	 *
	 * @param delegateBitmap the bitmap wrapped in a {@link ConstantFormula} used as the LP's delegate
	 * @param collectPerInnerRecordPrices `true` to construct the LP with the histogram side-output flag
	 * @return a freshly constructed LP carrying the requested flag
	 */
	@Nonnull
	private static LowestPriceTerminationFormula newLp(@Nonnull ArrayBitmap delegateBitmap, boolean collectPerInnerRecordPrices) {
		return newLp(new ConstantFormula(delegateBitmap), collectPerInnerRecordPrices);
	}

	/**
	 * Minimal {@link EntityToBitmapFilter} implementation used as the SelectionFormula's alternative
	 * branch. None of the histogram-side tests exercise the prefetch path, so this filter is never invoked
	 * — `filter` returns an empty bitmap and `getEntityRequire` returns `null`.
	 */
	private static final class NoopBitmapFilter implements EntityToBitmapFilter {

		@Nullable
		@Override
		public EntityFetchRequire getEntityRequire() {
			return null;
		}

		@Nonnull
		@Override
		public Bitmap filter(@Nonnull QueryExecutionContext context) {
			return EmptyBitmap.INSTANCE;
		}
	}
}
