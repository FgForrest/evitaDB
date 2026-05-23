/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.query.fetch;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import io.evitadb.api.query.require.Page;
import io.evitadb.api.query.require.Strip;
import io.evitadb.api.requestResponse.chunk.ChunkTransformer;
import io.evitadb.api.requestResponse.chunk.NoTransformer;
import io.evitadb.api.requestResponse.chunk.PageTransformer;
import io.evitadb.api.requestResponse.chunk.StripTransformer;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator.EntityPrimaryKeyAwareComparator;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.function.TriFunction;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BitmapSlicer} exercising the two slicing entry points and the page/strip
 * offset arithmetic that backs them. The tests use hand-rolled stubs (no Mockito) so the test data
 * stays under tight control and the test stays close to the project's existing style.
 *
 * Coverage focus areas:
 *
 * - Construction: rejection of unsupported transformers and identity behavior for `NoTransformer`.
 * - `sliceEntityIdsSorted`: empty/single-element/all-equal candidate sets, page-boundary rebasing,
 *   strip overflow, comparator-chain advance, `EntityPrimaryKeyAwareComparator` callback, group
 *   accounting derivation (must use `referenceContractsAccessor`, not the group translator),
 *   validity-mapping enforcement, cross-entity comparator-state leak tolerance, and multi-source-
 *   entity behavior.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("BitmapSlicer order-aware reference slicing")
class BitmapSlicerTest {

	private static final String REFERENCE_NAME = "parameter";

	@Nested
	@DisplayName("Construction and initialization")
	class ConstructionTest {

		@Test
		@DisplayName("Should reject unsupported chunk transformer subtypes at construction time")
		void shouldRejectUnsupportedChunkTransformer() {
			final ChunkTransformer unsupported = referenceContracts -> {
				throw new UnsupportedOperationException("not used");
			};

			final GenericEvitaInternalError thrown = assertThrows(
				GenericEvitaInternalError.class,
				() -> new BitmapSlicer(
					Collections.singletonMap(Scope.LIVE, new int[]{1}),
					REFERENCE_NAME,
					(refName, epk) -> EmptyFormula.INSTANCE,
					emptyGroupTranslator(),
					unsupported
				)
			);

			assertTrue(
				thrown.getPrivateMessage().contains("Unsupported chunk transformer"),
				"Internal error message should mention the unsupported transformer"
			);
		}

		@Test
		@DisplayName("Should treat NoTransformer as an identity chunker (no slicing)")
		void shouldUseIdentityChunkerForNoTransformer() {
			// arrange — single source entity owning refs {10, 20, 30}; filter keeps all of them
			final int sourceEntityPk = 1;
			final FakeReferenceContract r10 = ref(10);
			final FakeReferenceContract r20 = ref(20);
			final FakeReferenceContract r30 = ref(30);
			final Map<Integer, List<ReferenceContract>> refsByEntity = singletonRefMap(
				sourceEntityPk, r10, r20, r30
			);
			final BitmapSlicer slicer = new BitmapSlicer(
				Collections.singletonMap(Scope.LIVE, new int[]{sourceEntityPk}),
				REFERENCE_NAME,
				referencedEntityIdsFormula(refsByEntity),
				emptyGroupTranslator(),
				NoTransformer.INSTANCE
			);

			// act — NoTransformer must not chunk, so the sorted-slice returns all candidates
			// (sorted) and `sliceEntityIds` returns the full filtered bitmap untouched.
			final Bitmap allRefs = new BaseBitmap(10, 20, 30);
			final Bitmap sorted = slicer.sliceEntityIdsSorted(
				new ConstantFormula(allRefs),
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				new ReversedPkComparator()
			);

			// assert — all three present, sorted by reversed PK (30, 20, 10)
			assertArrayEquals(new int[]{30, 20, 10}, sorted.getArray());
		}
	}

	@Nested
	@DisplayName("sliceEntityIdsSorted")
	class SliceEntityIdsSortedTest {

		@Test
		@DisplayName("Should return empty bitmap when the source entity owns no references")
		void shouldReturnEmptyBitmapWhenSourceEntityHasNoMatchingReferences() {
			final int sourceEntityPk = 1;
			final Map<Integer, List<ReferenceContract>> refsByEntity = singletonRefMap(sourceEntityPk);
			final BitmapSlicer slicer = newPageSlicer(refsByEntity, new Page(1, 5));

			final Bitmap result = slicer.sliceEntityIdsSorted(
				EmptyFormula.INSTANCE,
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				new ReversedPkComparator()
			);

			assertTrue(result.isEmpty(), "no filtered references must produce empty result");
			assertTrue(
				slicer.getGroupIds(sourceEntityPk).compute().isEmpty(),
				"group accounting must be empty when no candidates survive"
			);
		}

		@Test
		@DisplayName("Should return empty bitmap when every candidate is filtered out before slicing")
		void shouldReturnEmptyBitmapWhenCandidatesArrayIsEmptyAfterFilter() {
			// the source entity has refs {10, 20} but the global filter only accepts {99},
			// so the filtered intersection is empty even though the entity has references
			final int sourceEntityPk = 1;
			final Map<Integer, List<ReferenceContract>> refsByEntity = singletonRefMap(
				sourceEntityPk, ref(10), ref(20)
			);
			final BitmapSlicer slicer = newPageSlicer(refsByEntity, new Page(1, 5));

			final Bitmap result = slicer.sliceEntityIdsSorted(
				new ConstantFormula(new BaseBitmap(99)),
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				new ReversedPkComparator()
			);

			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("Should pick the top-K candidates by comparator when the page slice fits the candidate set")
		void shouldPickTopKByComparatorWhenPageSliceFitsWithinCandidates() {
			// Unit-level mirror of the issue #1177 regression: candidates sorted by ReversedPK
			// would produce a different page-1 than candidates sorted by natural PK. The slicer
			// must respect the comparator's ordering, not insertion / natural PK order.
			final int sourceEntityPk = 1;
			final Map<Integer, List<ReferenceContract>> refsByEntity = singletonRefMap(
				sourceEntityPk, ref(10), ref(20), ref(30), ref(40), ref(50), ref(60), ref(70)
			);
			final BitmapSlicer slicer = newPageSlicer(refsByEntity, new Page(1, 3));

			final Bitmap result = slicer.sliceEntityIdsSorted(
				new ConstantFormula(new BaseBitmap(10, 20, 30, 40, 50, 60, 70)),
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				new ReversedPkComparator()
			);

			// the bitmap stores keys in ascending order, but the set must be the top-3 by reversed PK
			final int[] picked = result.getArray();
			Arrays.sort(picked);
			assertArrayEquals(new int[]{50, 60, 70}, picked);
		}

		@Test
		@DisplayName("Should fall back to the first page when the requested page is beyond the last available")
		void shouldFallBackToFirstPageWhenRequestedPageBeyondLast() {
			// requesting page 9 over a 3-element candidate set must rebase to page 1
			final int sourceEntityPk = 1;
			final Map<Integer, List<ReferenceContract>> refsByEntity = singletonRefMap(
				sourceEntityPk, ref(10), ref(20), ref(30)
			);
			final BitmapSlicer slicer = newPageSlicer(refsByEntity, new Page(9, 2));

			final Bitmap result = slicer.sliceEntityIdsSorted(
				new ConstantFormula(new BaseBitmap(10, 20, 30)),
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				new ReversedPkComparator()
			);

			// page 1 of 2 over reversed PK [30, 20, 10] -> [30, 20]
			final int[] picked = result.getArray();
			Arrays.sort(picked);
			assertArrayEquals(new int[]{20, 30}, picked);
		}

		@Test
		@DisplayName("Should return the last element when the strip offset sits at the end of the candidate range")
		void shouldReturnLastElementWhenStripOffsetEqualsCandidateSize() {
			// strip(offset=3, limit=5) with 3 candidates: `stripOffsetAndLimit` clamps offset
			// to size-1=2, then `Math.min(offset+limit, candidates.length)=3`, producing the
			// single-element tail of the sorted candidates. Reversed PK order is [30, 20, 10],
			// index 2 -> 10.
			final int sourceEntityPk = 1;
			final Map<Integer, List<ReferenceContract>> refsByEntity = singletonRefMap(
				sourceEntityPk, ref(10), ref(20), ref(30)
			);
			final BitmapSlicer slicer = newStripSlicer(refsByEntity, new Strip(3, 5));

			final Bitmap result = slicer.sliceEntityIdsSorted(
				new ConstantFormula(new BaseBitmap(10, 20, 30)),
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				new ReversedPkComparator()
			);

			assertArrayEquals(new int[]{10}, result.getArray());
		}

		@Test
		@DisplayName("Should clamp the strip limit when offset plus limit would overflow the candidate range")
		void shouldClampLimitWhenStripOffsetPlusLimitOverflowsCandidates() {
			// strip(offset=1, limit=99) with 3 candidates -> from=1, to=3 -> 2 elements
			final int sourceEntityPk = 1;
			final Map<Integer, List<ReferenceContract>> refsByEntity = singletonRefMap(
				sourceEntityPk, ref(10), ref(20), ref(30)
			);
			final BitmapSlicer slicer = newStripSlicer(refsByEntity, new Strip(1, 99));

			final Bitmap result = slicer.sliceEntityIdsSorted(
				new ConstantFormula(new BaseBitmap(10, 20, 30)),
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				new ReversedPkComparator()
			);

			// reversed PK order: [30, 20, 10]; offset=1 limit=99 clamped -> [20, 10]
			final int[] picked = result.getArray();
			Arrays.sort(picked);
			assertArrayEquals(new int[]{10, 20}, picked);
		}

		@Test
		@DisplayName("Should return a single-element slice when only one candidate survives filtering")
		void shouldReturnSingleElementSliceWhenOnlyOneCandidate() {
			final int sourceEntityPk = 1;
			final Map<Integer, List<ReferenceContract>> refsByEntity = singletonRefMap(
				sourceEntityPk, ref(42)
			);
			final BitmapSlicer slicer = newPageSlicer(refsByEntity, new Page(1, 5));

			final Bitmap result = slicer.sliceEntityIdsSorted(
				new ConstantFormula(new BaseBitmap(42)),
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				new ReversedPkComparator()
			);

			assertArrayEquals(new int[]{42}, result.getArray());
		}

		@Test
		@DisplayName("Should preserve every candidate when the comparator reports all pairs equal")
		void shouldKeepAllElementsWhenComparatorReportsAllEqual() {
			// AllEqualComparator returns 0 for every pair — Arrays.sort is stable so insertion
			// order is preserved; the page slice must return the full first page in that order.
			final int sourceEntityPk = 1;
			final Map<Integer, List<ReferenceContract>> refsByEntity = singletonRefMap(
				sourceEntityPk, ref(10), ref(20), ref(30)
			);
			final BitmapSlicer slicer = newPageSlicer(refsByEntity, new Page(1, 5));

			final Bitmap result = slicer.sliceEntityIdsSorted(
				new ConstantFormula(new BaseBitmap(10, 20, 30)),
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				new AllEqualComparator()
			);

			final int[] picked = result.getArray();
			Arrays.sort(picked);
			assertArrayEquals(new int[]{10, 20, 30}, picked);
		}

		@Test
		@DisplayName("Should advance to the next comparator in the chain when the primary leaves an unsorted tail")
		void shouldAdvanceComparatorChainWhenPrimaryComparatorReportsNonSortedTail() {
			// PrimaryComparatorWithUnsortedTail sorts only the first 2 elements (the rest are
			// reported as non-sorted) and chains a ReversedPkComparator as the secondary.
			// Expected layout after both passes: head of comparator-1 result (sorted by PK),
			// followed by reversed-PK ordering of the remainder.
			final int sourceEntityPk = 1;
			final Map<Integer, List<ReferenceContract>> refsByEntity = singletonRefMap(
				sourceEntityPk, ref(10), ref(20), ref(30), ref(40)
			);
			final BitmapSlicer slicer = newPageSlicer(refsByEntity, new Page(1, 4));

			final Bitmap result = slicer.sliceEntityIdsSorted(
				new ConstantFormula(new BaseBitmap(10, 20, 30, 40)),
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				new PrimaryComparatorWithUnsortedTail(2, new ReversedPkComparator())
			);

			// all four come back (bitmap stores ascending), but the test is about chain advance
			final int[] picked = result.getArray();
			Arrays.sort(picked);
			assertArrayEquals(new int[]{10, 20, 30, 40}, picked);
		}

		@Test
		@DisplayName("Should stop walking the comparator chain when the unsorted range is exhausted")
		void shouldStopComparatorChainWhenNonSortedRangeIsExhausted() {
			// Primary comparator reports `getNonSortedReferenceCount() == 0`, so even though a
			// next comparator is chained, the loop must exit immediately after the first pass.
			final SpyingComparator next = new SpyingComparator(new ReversedPkComparator());
			final ReferenceComparator primary = new FullySortingComparator(next);
			final int sourceEntityPk = 1;
			final Map<Integer, List<ReferenceContract>> refsByEntity = singletonRefMap(
				sourceEntityPk, ref(10), ref(20), ref(30)
			);
			final BitmapSlicer slicer = newPageSlicer(refsByEntity, new Page(1, 5));

			slicer.sliceEntityIdsSorted(
				new ConstantFormula(new BaseBitmap(10, 20, 30)),
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				primary
			);

			assertEquals(
				0, next.compareCallCount,
				"chained comparator must not be invoked when primary reports zero non-sorted refs"
			);
		}

		@Test
		@DisplayName("Should invoke setEntityPrimaryKey on EPK-aware comparators on every chain link")
		void shouldInvokeSetEntityPrimaryKeyOnEntityPrimaryKeyAwareComparator() {
			final EpkAwareComparator comparator = new EpkAwareComparator();
			final int sourceEntityA = 7;
			final int sourceEntityB = 11;
			final Map<Integer, List<ReferenceContract>> refsByEntity = new LinkedHashMap<>();
			refsByEntity.put(sourceEntityA, List.of(ref(10), ref(20)));
			refsByEntity.put(sourceEntityB, List.of(ref(30), ref(40)));
			final BitmapSlicer slicer = new BitmapSlicer(
				Collections.singletonMap(Scope.LIVE, new int[]{sourceEntityA, sourceEntityB}),
				REFERENCE_NAME,
				referencedEntityIdsFormula(refsByEntity),
				emptyGroupTranslator(),
				new PageTransformer(new Page(1, 5), new io.evitadb.api.requestResponse.EvitaRequest.ConditionalGap[0])
			);

			slicer.sliceEntityIdsSorted(
				new ConstantFormula(new BaseBitmap(10, 20, 30, 40)),
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				comparator
			);

			assertEquals(
				List.of(sourceEntityA, sourceEntityB), comparator.observedKeys,
				"setEntityPrimaryKey must be invoked once per source entity, in iteration order"
			);
		}

		@Test
		@DisplayName("Should compute group accounting over every filtered reference, independent of the slice window")
		void shouldComputeGroupAccountingOverAllFilteredReferencesIndependentOfSlice() {
			// 4 candidates, page size 2 — the slicer must keep group accounting for all 4 even
			// though only the top 2 by reversed PK are returned in the bitmap.
			final int sourceEntityPk = 1;
			final FakeReferenceContract r10 = refWithGroup(10, 100);
			final FakeReferenceContract r20 = refWithGroup(20, 200);
			final FakeReferenceContract r30 = refWithGroup(30, 300);
			final FakeReferenceContract r40 = refWithGroup(40, 400);
			final Map<Integer, List<ReferenceContract>> refsByEntity = singletonRefMap(
				sourceEntityPk, r10, r20, r30, r40
			);
			final BitmapSlicer slicer = newPageSlicer(refsByEntity, new Page(1, 2));

			slicer.sliceEntityIdsSorted(
				new ConstantFormula(new BaseBitmap(10, 20, 30, 40)),
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				new ReversedPkComparator()
			);

			final int[] groupIds = slicer.getGroupIds(sourceEntityPk).compute().getArray();
			Arrays.sort(groupIds);
			assertArrayEquals(
				new int[]{100, 200, 300, 400}, groupIds,
				"group accounting must include every filtered reference, not only the sliced ones"
			);
		}

		@Test
		@DisplayName("Should slice each source entity independently when their candidate sets are disjoint")
		void shouldHandleMultipleSourceEntitiesWithDisjointCandidateSets() {
			final int sourceEntityA = 1;
			final int sourceEntityB = 2;
			final Map<Integer, List<ReferenceContract>> refsByEntity = new LinkedHashMap<>();
			refsByEntity.put(sourceEntityA, List.of(ref(10), ref(20)));
			refsByEntity.put(sourceEntityB, List.of(ref(30), ref(40)));
			final BitmapSlicer slicer = new BitmapSlicer(
				Collections.singletonMap(Scope.LIVE, new int[]{sourceEntityA, sourceEntityB}),
				REFERENCE_NAME,
				referencedEntityIdsFormula(refsByEntity),
				emptyGroupTranslator(),
				new PageTransformer(new Page(1, 1), new io.evitadb.api.requestResponse.EvitaRequest.ConditionalGap[0])
			);

			final Bitmap result = slicer.sliceEntityIdsSorted(
				new ConstantFormula(new BaseBitmap(10, 20, 30, 40)),
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				new ReversedPkComparator()
			);

			// each source entity contributes its top-1 by reversed PK -> {20} and {40}
			final int[] picked = result.getArray();
			Arrays.sort(picked);
			assertArrayEquals(new int[]{20, 40}, picked);
		}

		@Test
		@DisplayName("Should derive group accounting from reference contracts on the sorted path, not from the translator")
		void shouldDeriveGroupAccountingFromReferenceContractsAccessorNotFromTranslator() {
			// Pinning the contract: groupsForEntity for the sorted slicing path is built from
			// `referenceContractsAccessor.apply(...).getGroup()`, not from
			// `referencedEntityToGroupIdTranslator`. The translator returns empty for every
			// (epk, refName, refId), yet the slicer must still surface the correct group PKs
			// because they come from the source references.
			final int sourceEntityPk = 1;
			final FakeReferenceContract r10 = refWithGroup(10, 100);
			final FakeReferenceContract r20 = refWithGroup(20, 200);
			final Map<Integer, List<ReferenceContract>> refsByEntity = singletonRefMap(
				sourceEntityPk, r10, r20
			);
			final BitmapSlicer slicer = new BitmapSlicer(
				Collections.singletonMap(Scope.LIVE, new int[]{sourceEntityPk}),
				REFERENCE_NAME,
				referencedEntityIdsFormula(refsByEntity),
				(epk, refName, refId) -> IntStream.empty(),
				new PageTransformer(new Page(1, 5), new io.evitadb.api.requestResponse.EvitaRequest.ConditionalGap[0])
			);

			slicer.sliceEntityIdsSorted(
				new ConstantFormula(new BaseBitmap(10, 20)),
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				new ReversedPkComparator()
			);

			final int[] groupIds = slicer.getGroupIds(sourceEntityPk).compute().getArray();
			Arrays.sort(groupIds);
			assertArrayEquals(
				new int[]{100, 200}, groupIds,
				"sorted slicer must derive groups from ReferenceContract.getGroup(), not from the translator"
			);
		}

		@Test
		@DisplayName("Should exclude references rejected by the validity mapping from the slice")
		void shouldExcludeReferencesRejectedByValidityMappingFromSlice() {
			// `sliceEntityIdsSorted` must honor `ValidEntityToReferenceMapping` the same way the
			// unsorted twin does — references not present in the per-source validity mapping
			// must be filtered out before sorting and slicing, otherwise the order-aware slice
			// would surface rows the unsorted path already rejected (e.g. multi-source dedup
			// winners) and the post-fetch sort would see extra rows.
			final int sourceEntityPk = 1;
			final FakeReferenceContract r10 = ref(10);
			final FakeReferenceContract r20 = ref(20);
			final Map<Integer, List<ReferenceContract>> refsByEntity = singletonRefMap(
				sourceEntityPk, r10, r20
			);
			final BitmapSlicer slicer = newPageSlicer(refsByEntity, new Page(1, 5));
			// validity mapping allows only ref 20 for the source entity — ref 10 must be dropped
			final ValidEntityToReferenceMapping validityMapping = restrictedValidityMapping(
				Map.of(sourceEntityPk, new int[]{20})
			);

			final Bitmap result = slicer.sliceEntityIdsSorted(
				new ConstantFormula(new BaseBitmap(10, 20)),
				validityMapping,
				referenceContractsAccessor(refsByEntity),
				new ReversedPkComparator()
			);

			assertArrayEquals(
				new int[]{20}, result.getArray(),
				"sorted slicer must honor validity mapping: ref 10 is excluded by the mapping"
			);
		}

		@Test
		@DisplayName("Should tolerate comparators whose non-sorted count is a cross-entity monotonic accumulator")
		void shouldToleratePerPassNonSortedCountWithCrossEntityAccumulator() {
			// Some comparator chains (notably `EntityNestedQueryComparator`) keep their
			// `nonSortedReferences` IntSet alive across source-entity passes — the count is
			// monotonically growing, not a per-pass reading. The slicer must therefore work with
			// the *delta* between the count before and after each `Arrays.sort` invocation and
			// clamp it to the sorted slice, so a stale accumulator from the first source entity
			// cannot produce a negative `start` and trip `Arrays.sort` with a backwards range.
			final int sourceEntityA = 1;
			final int sourceEntityB = 2;
			final Map<Integer, List<ReferenceContract>> refsByEntity = new LinkedHashMap<>();
			refsByEntity.put(sourceEntityA, List.of(ref(10), ref(20), ref(30)));
			refsByEntity.put(sourceEntityB, List.of(ref(40), ref(50)));
			final BitmapSlicer slicer = new BitmapSlicer(
				Collections.singletonMap(Scope.LIVE, new int[]{sourceEntityA, sourceEntityB}),
				REFERENCE_NAME,
				referencedEntityIdsFormula(refsByEntity),
				emptyGroupTranslator(),
				new PageTransformer(new Page(1, 5), new io.evitadb.api.requestResponse.EvitaRequest.ConditionalGap[0])
			);
			final ReferenceComparator chained = new ReversedPkComparator();
			final ReferenceComparator leaky = new MonotoneNonSortedAccumulator(chained);

			final Bitmap result = slicer.sliceEntityIdsSorted(
				new ConstantFormula(new BaseBitmap(10, 20, 30, 40, 50)),
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				leaky
			);

			// the accumulator returns 0 on its primary pass — every ref ends up handed to the
			// chained reversed-PK comparator, which sorts both entities' refs in descending PK
			// order; bitmap storage is ascending, so the union of all 5 PKs is returned
			final int[] picked = result.getArray();
			Arrays.sort(picked);
			assertArrayEquals(new int[]{10, 20, 30, 40, 50}, picked);
		}
	}

	@Nested
	@DisplayName("offset and limit helpers behavior (observed through chunker)")
	class OffsetAndLimitHelpersTest {

		@Test
		@DisplayName("Should compute the expected offset and limit for an ordinary page request")
		void shouldComputePageOffsetAndLimitForOrdinaryPage() {
			// page(2, 3) over 7 refs -> offset 3, limit 3 -> indices [3,4,5]; reversed PK order
			// of [10..70] is [70..10], so picked are [40, 30, 20].
			final int sourceEntityPk = 1;
			final Map<Integer, List<ReferenceContract>> refsByEntity = singletonRefMap(
				sourceEntityPk, ref(10), ref(20), ref(30), ref(40), ref(50), ref(60), ref(70)
			);
			final BitmapSlicer slicer = newPageSlicer(refsByEntity, new Page(2, 3));

			final Bitmap result = slicer.sliceEntityIdsSorted(
				new ConstantFormula(new BaseBitmap(10, 20, 30, 40, 50, 60, 70)),
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				new ReversedPkComparator()
			);

			final int[] picked = result.getArray();
			Arrays.sort(picked);
			assertArrayEquals(new int[]{20, 30, 40}, picked);
		}

		@Test
		@DisplayName("Should rebase the page offset to page one when the requested page is past the end")
		void shouldRebaseToFirstPageWhenPageBeyondLast() {
			// page(50, 2) over 3 refs -> rebased to page 1 of size 2 -> indices [0,1]; reversed
			// PK [30, 20, 10] -> picked [30, 20]
			final int sourceEntityPk = 1;
			final Map<Integer, List<ReferenceContract>> refsByEntity = singletonRefMap(
				sourceEntityPk, ref(10), ref(20), ref(30)
			);
			final BitmapSlicer slicer = newPageSlicer(refsByEntity, new Page(50, 2));

			final Bitmap result = slicer.sliceEntityIdsSorted(
				new ConstantFormula(new BaseBitmap(10, 20, 30)),
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				new ReversedPkComparator()
			);

			final int[] picked = result.getArray();
			Arrays.sort(picked);
			assertArrayEquals(new int[]{20, 30}, picked);
		}

		@Test
		@DisplayName("Should compute the expected offset and limit for a strip request inside the candidate range")
		void shouldComputeStripOffsetAndLimitWithinBounds() {
			// strip(offset=1, limit=2) over reversed PK [30, 20, 10] -> [20, 10]
			final int sourceEntityPk = 1;
			final Map<Integer, List<ReferenceContract>> refsByEntity = singletonRefMap(
				sourceEntityPk, ref(10), ref(20), ref(30)
			);
			final BitmapSlicer slicer = newStripSlicer(refsByEntity, new Strip(1, 2));

			final Bitmap result = slicer.sliceEntityIdsSorted(
				new ConstantFormula(new BaseBitmap(10, 20, 30)),
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				new ReversedPkComparator()
			);

			final int[] picked = result.getArray();
			Arrays.sort(picked);
			assertArrayEquals(new int[]{10, 20}, picked);
		}

		@Test
		@DisplayName("Should clamp the strip offset to the candidate range when it sits at or past the end")
		void shouldClampStripOffsetWhenOffsetAtOrPastSize() {
			// strip(offset=100, limit=2) over 3 refs: `stripOffsetAndLimit` clamps to
			// size-1=2 and Math.min(2+2, 3)=3, so the tail single-element slice is returned.
			final int sourceEntityPk = 1;
			final Map<Integer, List<ReferenceContract>> refsByEntity = singletonRefMap(
				sourceEntityPk, ref(10), ref(20), ref(30)
			);
			final BitmapSlicer slicer = newStripSlicer(refsByEntity, new Strip(100, 2));

			final Bitmap result = slicer.sliceEntityIdsSorted(
				new ConstantFormula(new BaseBitmap(10, 20, 30)),
				permissiveValidityMapping(refsByEntity),
				referenceContractsAccessor(refsByEntity),
				new ReversedPkComparator()
			);

			// reversed PK candidates [30, 20, 10], tail index 2 -> 10
			assertArrayEquals(new int[]{10}, result.getArray());
		}
	}

	// ---------------------------------------------------------------------------------------------
	// fixtures and helpers
	// ---------------------------------------------------------------------------------------------

	/**
	 * Builds a single-entity reference map keyed by the source entity primary key.
	 */
	@Nonnull
	private static Map<Integer, List<ReferenceContract>> singletonRefMap(
		int sourceEntityPk, @Nonnull FakeReferenceContract... refs
	) {
		final Map<Integer, List<ReferenceContract>> map = new HashMap<>();
		map.put(sourceEntityPk, List.of(refs));
		return map;
	}

	/**
	 * Produces a `referencedEntityIdsFormula` BiFunction that resolves the per-source bitmap of
	 * referenced PKs from the test's reference map.
	 */
	@Nonnull
	private static BiFunction<String, Integer, Formula> referencedEntityIdsFormula(
		@Nonnull Map<Integer, List<ReferenceContract>> refsByEntity
	) {
		return (refName, epk) -> {
			final List<ReferenceContract> refs = refsByEntity.getOrDefault(epk, List.of());
			if (refs.isEmpty()) {
				return EmptyFormula.INSTANCE;
			}
			final int[] pks = refs.stream()
				.mapToInt(ReferenceContract::getReferencedPrimaryKey)
				.toArray();
			return new ConstantFormula(new BaseBitmap(pks));
		};
	}

	/**
	 * Produces a `referenceContractsAccessor` BiFunction that resolves the per-source list of
	 * reference contracts from the test's reference map.
	 */
	@Nonnull
	private static BiFunction<String, Integer, Collection<ReferenceContract>> referenceContractsAccessor(
		@Nonnull Map<Integer, List<ReferenceContract>> refsByEntity
	) {
		return (refName, epk) -> refsByEntity.getOrDefault(epk, List.of());
	}

	/**
	 * Builds a `ValidEntityToReferenceMapping` whose per-source allow-set is exactly the reference
	 * PKs found in `refsByEntity` for that source entity — equivalent to "no validity restriction
	 * beyond what the source already exposes". Use this in tests that focus on slicing behavior
	 * rather than validity filtering.
	 */
	@Nonnull
	private static ValidEntityToReferenceMapping permissiveValidityMapping(
		@Nonnull Map<Integer, List<ReferenceContract>> refsByEntity
	) {
		final ReferenceSchema schema = ReferenceSchema._internalBuild(
			REFERENCE_NAME, "referenced", false, Cardinality.ZERO_OR_MORE,
			null, false, null, null
		);
		final ValidEntityToReferenceMapping mapping = new ValidEntityToReferenceMapping(
			refsByEntity.size(), schema
		);
		for (Map.Entry<Integer, List<ReferenceContract>> entry : refsByEntity.entrySet()) {
			final int[] pks = entry.getValue().stream()
				.mapToInt(ReferenceContract::getReferencedPrimaryKey)
				.toArray();
			mapping.setInitialVisibilityForEntity(entry.getKey(), new BaseBitmap(pks));
		}
		return mapping;
	}

	/**
	 * Builds a `ValidEntityToReferenceMapping` restricted to exactly the given allowed referenced
	 * PKs per source entity. References not listed for a source entity are rejected by the
	 * validity gate even if the source entity owns them.
	 */
	@Nonnull
	private static ValidEntityToReferenceMapping restrictedValidityMapping(
		@Nonnull Map<Integer, int[]> allowedByEntity
	) {
		final ReferenceSchema schema = ReferenceSchema._internalBuild(
			REFERENCE_NAME, "referenced", false, Cardinality.ZERO_OR_MORE,
			null, false, null, null
		);
		final ValidEntityToReferenceMapping mapping = new ValidEntityToReferenceMapping(
			allowedByEntity.size(), schema
		);
		for (Map.Entry<Integer, int[]> entry : allowedByEntity.entrySet()) {
			mapping.setInitialVisibilityForEntity(entry.getKey(), new BaseBitmap(entry.getValue()));
		}
		return mapping;
	}

	/**
	 * Returns a default translator that produces no groups for any reference.
	 */
	@Nonnull
	private static TriFunction<Integer, String, Integer, IntStream> emptyGroupTranslator() {
		return (epk, refName, refId) -> IntStream.empty();
	}

	/**
	 * Builds a slicer wired to a `PageTransformer` for the provided page constraint.
	 */
	@Nonnull
	private static BitmapSlicer newPageSlicer(
		@Nonnull Map<Integer, List<ReferenceContract>> refsByEntity,
		@Nonnull Page page
	) {
		final int[] sourcePks = refsByEntity.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
		return new BitmapSlicer(
			Collections.singletonMap(Scope.LIVE, sourcePks),
			REFERENCE_NAME,
			referencedEntityIdsFormula(refsByEntity),
			emptyGroupTranslator(),
			new PageTransformer(page, new io.evitadb.api.requestResponse.EvitaRequest.ConditionalGap[0])
		);
	}

	/**
	 * Builds a slicer wired to a `StripTransformer` for the provided strip constraint.
	 */
	@Nonnull
	private static BitmapSlicer newStripSlicer(
		@Nonnull Map<Integer, List<ReferenceContract>> refsByEntity,
		@Nonnull Strip strip
	) {
		final int[] sourcePks = refsByEntity.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
		return new BitmapSlicer(
			Collections.singletonMap(Scope.LIVE, sourcePks),
			REFERENCE_NAME,
			referencedEntityIdsFormula(refsByEntity),
			emptyGroupTranslator(),
			new StripTransformer(strip)
		);
	}

	/**
	 * Builds a fake reference contract with the given referenced primary key and no group.
	 */
	@Nonnull
	private static FakeReferenceContract ref(int referencedPk) {
		return new FakeReferenceContract(referencedPk, null);
	}

	/**
	 * Builds a fake reference contract with the given referenced primary key and the given
	 * group primary key.
	 */
	@Nonnull
	private static FakeReferenceContract refWithGroup(int referencedPk, int groupPk) {
		return new FakeReferenceContract(referencedPk, groupPk);
	}

	/**
	 * Hand-rolled stub that implements only the methods `BitmapSlicer` invokes. Everything
	 * else throws `UnsupportedOperationException`, which keeps the test failure modes loud
	 * if the production code starts depending on something new.
	 */
	private static final class FakeReferenceContract implements ReferenceContract {
		@Serial private static final long serialVersionUID = -1161299331361146718L;
		private final ReferenceKey referenceKey;
		@Nullable private final GroupEntityReference group;

		FakeReferenceContract(int referencedPk, @Nullable Integer groupPk) {
			this.referenceKey = new ReferenceKey(REFERENCE_NAME, referencedPk);
			this.group = groupPk == null ? null : new GroupEntityReference("group", groupPk);
		}

		@Nonnull
		@Override
		public ReferenceKey getReferenceKey() {
			return this.referenceKey;
		}

		@Nonnull
		@Override
		public Optional<SealedEntity> getReferencedEntity() {
			return Optional.empty();
		}

		@Nonnull
		@Override
		public String getReferencedEntityType() {
			return "referenced";
		}

		@Nonnull
		@Override
		public Cardinality getReferenceCardinality() {
			return Cardinality.ZERO_OR_MORE;
		}

		@Nonnull
		@Override
		public Optional<GroupEntityReference> getGroup() {
			return Optional.ofNullable(this.group);
		}

		@Nonnull
		@Override
		public Optional<SealedEntity> getGroupEntity() {
			return Optional.empty();
		}

		@Nonnull
		@Override
		public Optional<ReferenceSchemaContract> getReferenceSchema() {
			return Optional.empty();
		}

		@Nonnull
		@Override
		public ReferenceSchemaContract getReferenceSchemaOrThrow() {
			throw new UnsupportedOperationException("not used by BitmapSlicer");
		}

		@Override
		public boolean dropped() {
			return false;
		}

		@Override
		public int version() {
			return 1;
		}

		// AttributesContract surface — none of these are touched by BitmapSlicer
		@Override
		public boolean attributesAvailable() {
			throw new UnsupportedOperationException("not used by BitmapSlicer");
		}

		@Override
		public boolean attributesAvailable(@Nonnull java.util.Locale locale) {
			throw new UnsupportedOperationException("not used by BitmapSlicer");
		}

		@Override
		public boolean attributeAvailable(@Nonnull String attributeName) {
			throw new UnsupportedOperationException("not used by BitmapSlicer");
		}

		@Override
		public boolean attributeAvailable(@Nonnull String attributeName, @Nonnull java.util.Locale locale) {
			throw new UnsupportedOperationException("not used by BitmapSlicer");
		}

		@Nullable
		@Override
		public <T extends Serializable> T getAttribute(@Nonnull String attributeName) {
			throw new UnsupportedOperationException("not used by BitmapSlicer");
		}

		@Nullable
		@Override
		public <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName) {
			throw new UnsupportedOperationException("not used by BitmapSlicer");
		}

		@Nonnull
		@Override
		public Optional<io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue> getAttributeValue(
			@Nonnull String attributeName) {
			throw new UnsupportedOperationException("not used by BitmapSlicer");
		}

		@Nullable
		@Override
		public <T extends Serializable> T getAttribute(@Nonnull String attributeName, @Nonnull java.util.Locale locale) {
			throw new UnsupportedOperationException("not used by BitmapSlicer");
		}

		@Nullable
		@Override
		public <T extends Serializable> T[] getAttributeArray(
			@Nonnull String attributeName, @Nonnull java.util.Locale locale) {
			throw new UnsupportedOperationException("not used by BitmapSlicer");
		}

		@Nonnull
		@Override
		public Optional<io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue> getAttributeValue(
			@Nonnull String attributeName, @Nonnull java.util.Locale locale) {
			throw new UnsupportedOperationException("not used by BitmapSlicer");
		}

		@Nonnull
		@Override
		public Optional<AttributeSchemaContract> getAttributeSchema(@Nonnull String attributeName) {
			throw new UnsupportedOperationException("not used by BitmapSlicer");
		}

		@Nonnull
		@Override
		public Set<String> getAttributeNames() {
			throw new UnsupportedOperationException("not used by BitmapSlicer");
		}

		@Nonnull
		@Override
		public Set<AttributesContract.AttributeKey> getAttributeKeys() {
			throw new UnsupportedOperationException("not used by BitmapSlicer");
		}

		@Nonnull
		@Override
		public Optional<AttributesContract.AttributeValue> getAttributeValue(
			@Nonnull AttributesContract.AttributeKey attributeKey) {
			throw new UnsupportedOperationException("not used by BitmapSlicer");
		}

		@Nonnull
		@Override
		public Collection<AttributesContract.AttributeValue> getAttributeValues() {
			throw new UnsupportedOperationException("not used by BitmapSlicer");
		}

		@Nonnull
		@Override
		public Collection<AttributesContract.AttributeValue> getAttributeValues(@Nonnull String attributeName) {
			throw new UnsupportedOperationException("not used by BitmapSlicer");
		}

		@Nonnull
		@Override
		public Set<java.util.Locale> getAttributeLocales() {
			throw new UnsupportedOperationException("not used by BitmapSlicer");
		}

		@Override
		public boolean differsFrom(@Nullable ReferenceContract otherReference) {
			return true;
		}
	}

	/**
	 * Comparator that orders references by descending primary key. Used as the canonical
	 * comparator in tests because it disagrees with PK-bitmap ordering, exposing slicer
	 * regressions.
	 */
	private static final class ReversedPkComparator implements ReferenceComparator, Serializable {
		@Serial private static final long serialVersionUID = 1049423735946139846L;

		@Override
		public int compare(ReferenceContract o1, ReferenceContract o2) {
			return Integer.compare(o2.getReferencedPrimaryKey(), o1.getReferencedPrimaryKey());
		}

		@Override
		public int getNonSortedReferenceCount() {
			return 0;
		}

		@Nonnull
		@Override
		public ReferenceComparator andThen(@Nonnull ReferenceComparator comparatorForUnknownRecords) {
			throw new UnsupportedOperationException("not used");
		}

		@Nullable
		@Override
		public ReferenceComparator getNextComparator() {
			return null;
		}
	}

	/**
	 * Comparator that reports every pair as equal. Combined with `Arrays.sort` stability,
	 * insertion order is preserved.
	 */
	private static final class AllEqualComparator implements ReferenceComparator, Serializable {
		@Serial private static final long serialVersionUID = 2921062812216467221L;

		@Override
		public int compare(ReferenceContract o1, ReferenceContract o2) {
			return 0;
		}

		@Override
		public int getNonSortedReferenceCount() {
			return 0;
		}

		@Nonnull
		@Override
		public ReferenceComparator andThen(@Nonnull ReferenceComparator comparatorForUnknownRecords) {
			throw new UnsupportedOperationException("not used");
		}

		@Nullable
		@Override
		public ReferenceComparator getNextComparator() {
			return null;
		}
	}

	/**
	 * Primary comparator that successfully sorts only the first `headSize` elements (by PK
	 * ascending) and reports the rest as non-sorted, chaining to a secondary comparator.
	 */
	private static final class PrimaryComparatorWithUnsortedTail implements ReferenceComparator, Serializable {
		@Serial private static final long serialVersionUID = 1212470114057994610L;

		private final int headSize;
		private final ReferenceComparator next;
		private int sortedSoFar;

		PrimaryComparatorWithUnsortedTail(int headSize, @Nonnull ReferenceComparator next) {
			this.headSize = headSize;
			this.next = next;
		}

		@Override
		public int compare(ReferenceContract o1, ReferenceContract o2) {
			this.sortedSoFar++;
			return Integer.compare(o1.getReferencedPrimaryKey(), o2.getReferencedPrimaryKey());
		}

		@Override
		public int getNonSortedReferenceCount() {
			// the comparator chain walks at most `len - headSize` items into the secondary
			return Math.max(0, this.sortedSoFar - this.headSize);
		}

		@Nonnull
		@Override
		public ReferenceComparator andThen(@Nonnull ReferenceComparator comparatorForUnknownRecords) {
			throw new UnsupportedOperationException("not used");
		}

		@Nullable
		@Override
		public ReferenceComparator getNextComparator() {
			return this.next;
		}
	}

	/**
	 * Comparator that fully sorts everything (PK ascending) and reports zero non-sorted refs;
	 * the slicer must therefore never invoke the chained comparator.
	 */
	private static final class FullySortingComparator implements ReferenceComparator, Serializable {
		@Serial private static final long serialVersionUID = -3439388572083215292L;

		private final ReferenceComparator next;

		FullySortingComparator(@Nonnull ReferenceComparator next) {
			this.next = next;
		}

		@Override
		public int compare(ReferenceContract o1, ReferenceContract o2) {
			return Integer.compare(o1.getReferencedPrimaryKey(), o2.getReferencedPrimaryKey());
		}

		@Override
		public int getNonSortedReferenceCount() {
			return 0;
		}

		@Nonnull
		@Override
		public ReferenceComparator andThen(@Nonnull ReferenceComparator comparatorForUnknownRecords) {
			throw new UnsupportedOperationException("not used");
		}

		@Nullable
		@Override
		public ReferenceComparator getNextComparator() {
			return this.next;
		}
	}

	/**
	 * Comparator that counts `compare` invocations on a wrapped delegate, used to assert
	 * whether the chain advances or stops.
	 */
	private static final class SpyingComparator implements ReferenceComparator, Serializable {
		@Serial private static final long serialVersionUID = -1109723067085802297L;

		private final ReferenceComparator delegate;
		int compareCallCount;

		SpyingComparator(@Nonnull ReferenceComparator delegate) {
			this.delegate = delegate;
		}

		@Override
		public int compare(ReferenceContract o1, ReferenceContract o2) {
			this.compareCallCount++;
			return this.delegate.compare(o1, o2);
		}

		@Override
		public int getNonSortedReferenceCount() {
			return this.delegate.getNonSortedReferenceCount();
		}

		@Nonnull
		@Override
		public ReferenceComparator andThen(@Nonnull ReferenceComparator comparatorForUnknownRecords) {
			throw new UnsupportedOperationException("not used");
		}

		@Nullable
		@Override
		public ReferenceComparator getNextComparator() {
			return this.delegate.getNextComparator();
		}
	}

	/**
	 * `EntityPrimaryKeyAwareComparator` whose `setEntityPrimaryKey` callback records each
	 * invocation in insertion order so tests can assert per-source-entity setup.
	 */
	private static final class EpkAwareComparator
		implements ReferenceComparator, EntityPrimaryKeyAwareComparator, Serializable {
		@Serial private static final long serialVersionUID = 7880289428338626380L;

		final List<Integer> observedKeys = new ArrayList<>();

		@Override
		public void setEntityPrimaryKey(int entityPrimaryKey) {
			this.observedKeys.add(entityPrimaryKey);
		}

		@Override
		public int compare(ReferenceContract o1, ReferenceContract o2) {
			return Integer.compare(o1.getReferencedPrimaryKey(), o2.getReferencedPrimaryKey());
		}

		@Override
		public int getNonSortedReferenceCount() {
			return 0;
		}

		@Nonnull
		@Override
		public ReferenceComparator andThen(@Nonnull ReferenceComparator comparatorForUnknownRecords) {
			throw new UnsupportedOperationException("not used");
		}

		@Nullable
		@Override
		public ReferenceComparator getNextComparator() {
			return null;
		}
	}

	/**
	 * Comparator that mimics `EntityNestedQueryComparator`'s lazy-init `IntSet` for tracking
	 * non-sorted refs across `compare` calls. The set accumulates across source entities, so
	 * the second source entity's pass sees a stale count from the first pass — reproducing
	 * the comparator-state-leak failure mode.
	 */
	private static final class MonotoneNonSortedAccumulator implements ReferenceComparator, Serializable {
		@Serial private static final long serialVersionUID = -1951359585234161118L;

		private final ReferenceComparator next;
		private IntSet nonSorted;

		MonotoneNonSortedAccumulator(@Nonnull ReferenceComparator next) {
			this.next = next;
		}

		@Override
		public int compare(ReferenceContract o1, ReferenceContract o2) {
			if (this.nonSorted == null) {
				this.nonSorted = new IntHashSet();
			}
			this.nonSorted.add(o1.getReferencedPrimaryKey());
			this.nonSorted.add(o2.getReferencedPrimaryKey());
			return 0;
		}

		@Override
		public int getNonSortedReferenceCount() {
			return this.nonSorted == null ? 0 : this.nonSorted.size();
		}

		@Nonnull
		@Override
		public ReferenceComparator andThen(@Nonnull ReferenceComparator comparatorForUnknownRecords) {
			throw new UnsupportedOperationException("not used");
		}

		@Nullable
		@Override
		public ReferenceComparator getNextComparator() {
			return this.next;
		}
	}

}
