/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.index.facet;

import com.sun.management.ThreadMXBean;
import io.evitadb.core.query.algebra.facet.FacetGroupAndFormula;
import io.evitadb.core.query.algebra.facet.FacetGroupFormula;
import io.evitadb.function.TriFunction;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;

import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.REFERENCE;

/**
 * Tests for {@link FacetReferenceIndex} covering construction,
 * non-transactional operations, STM commit/rollback, and toString.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("FacetReferenceIndex")
@Tag(INDEXING)
@Tag(FACET)
@Tag(REFERENCE)
class FacetReferenceIndexTest {

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName(
			"empty constructor: no-group null, grouped empty"
		)
		void shouldCreateEmpty() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			assertNull(index.getNotGroupedFacets());
			assertTrue(index.getGroupedFacets().isEmpty());
			assertTrue(index.isEmpty());
			assertEquals("ref", index.getReferenceName());
		}

		@Test
		@DisplayName(
			"collection constructor with no-group index"
		)
		void shouldCreateWithNoGroupIndex() {
			final FacetIdIndex fi =
				new FacetIdIndex(1, new BaseBitmap(10));
			final FacetGroupIndex noGroup =
				new FacetGroupIndex(List.of(fi));
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref", List.of(noGroup));
			assertNotNull(index.getNotGroupedFacets());
			assertEquals(1, index.size());
		}

		@Test
		@DisplayName(
			"collection constructor with multiple groups"
		)
		void shouldCreateWithMultipleGroups() {
			final FacetIdIndex fi1 =
				new FacetIdIndex(1, new BaseBitmap(10));
			final FacetGroupIndex group1 =
				new FacetGroupIndex(100, List.of(fi1));
			final FacetIdIndex fi2 =
				new FacetIdIndex(2, new BaseBitmap(20));
			final FacetGroupIndex group2 =
				new FacetGroupIndex(200, List.of(fi2));
			final FacetReferenceIndex index =
				new FacetReferenceIndex(
					"ref", List.of(group1, group2)
				);
			assertNull(index.getNotGroupedFacets());
			assertEquals(2, index.getGroupedFacets().size());
			assertEquals(2, index.size());
		}

		@Test
		@DisplayName(
			"two no-group indexes → assertion error"
		)
		void shouldRejectTwoNoGroupIndexes() {
			final FacetGroupIndex noGroup1 =
				new FacetGroupIndex();
			final FacetGroupIndex noGroup2 =
				new FacetGroupIndex();
			assertThrows(
				Exception.class,
				() -> new FacetReferenceIndex(
					"ref", List.of(noGroup1, noGroup2)
				)
			);
		}
	}

	@Nested
	@DisplayName("Non-transactional operations")
	class NonTransactionalTest {

		@Test
		@DisplayName(
			"addFacet with null group creates no-group index"
		)
		void shouldCreateNoGroupOnAdd() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			assertTrue(index.addFacet(10, null, 100));
			assertNotNull(index.getNotGroupedFacets());
			assertEquals(1, index.size());
		}

		@Test
		@DisplayName(
			"addFacet null group reuses existing no-group"
		)
		void shouldReuseExistingNoGroup() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, null, 100);
			index.addFacet(20, null, 200);
			assertEquals(2, index.size());
		}

		@Test
		@DisplayName("addFacet with groupId creates group")
		void shouldCreateGroupOnAdd() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			assertTrue(index.addFacet(10, 1, 100));
			assertNotNull(index.getFacetsInGroup(1));
		}

		@Test
		@DisplayName(
			"addFacet with groupId reuses existing group"
		)
		void shouldReuseExistingGroup() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, 1, 100);
			index.addFacet(20, 1, 200);
			assertEquals(2, index.size());
		}

		@Test
		@DisplayName(
			"removeFacet when notGroupedFacets is null → throws"
		)
		void shouldThrowWhenNoGroupNull() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			assertThrows(
				Exception.class,
				() -> index.removeFacet(10, null, 100)
			);
		}

		@Test
		@DisplayName(
			"removeFacet when group not found → throws"
		)
		void shouldThrowWhenGroupNotFound() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			assertThrows(
				Exception.class,
				() -> index.removeFacet(10, 999, 100)
			);
		}

		@Test
		@DisplayName(
			"removeFacet drains no-group → set to null"
		)
		void shouldSetNoGroupToNullWhenDrained() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, null, 100);
			index.removeFacet(10, null, 100);
			assertNull(index.getNotGroupedFacets());
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName(
			"removeFacet drains group → removed from map"
		)
		void shouldRemoveGroupWhenDrained() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, 1, 100);
			index.removeFacet(10, 1, 100);
			assertNull(index.getFacetsInGroup(1));
			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("isEmpty and size tracking")
		void shouldTrackEmptinessAndSize() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			assertTrue(index.isEmpty());
			assertEquals(0, index.size());
			index.addFacet(10, null, 100);
			assertFalse(index.isEmpty());
			assertEquals(1, index.size());
			index.addFacet(20, 1, 200);
			assertEquals(2, index.size());
		}

		@Test
		@DisplayName("getFacetsInGroup(null) returns no-group")
		void shouldReturnNoGroupFacets() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, null, 100);
			assertNotNull(index.getFacetsInGroup(null));
		}

		@Test
		@DisplayName(
			"getFacetsInGroup(Integer) returns specific group"
		)
		void shouldReturnSpecificGroup() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, 5, 100);
			assertNotNull(index.getFacetsInGroup(5));
			assertNull(index.getFacetsInGroup(999));
		}

		@Test
		@DisplayName("isFacetInGroup true/false paths")
		void shouldCheckFacetInGroup() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, 5, 100);
			assertTrue(index.isFacetInGroup(5, 10));
			assertFalse(index.isFacetInGroup(5, 999));
			assertFalse(index.isFacetInGroup(999, 10));
		}
	}

	@Nested
	@DisplayName("STM — Commit")
	class CommitTest {

		@Test
		@DisplayName(
			"commit grouped addFacet: new group in committed"
		)
		void shouldCommitGroupedAdd() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");

			assertStateAfterCommit(
				index,
				original -> original.addFacet(10, 1, 100),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertTrue(original.isEmpty());
					assertFalse(committed.isEmpty());
					assertNotNull(committed.getFacetsInGroup(1));
				}
			);
		}

		@Test
		@DisplayName(
			"commit no-group addFacet"
		)
		void shouldCommitNoGroupAdd() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");

			assertStateAfterCommit(
				index,
				original -> original.addFacet(10, null, 100),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertNull(original.getNotGroupedFacets());
					assertNotNull(
						committed.getNotGroupedFacets()
					);
				}
			);
		}

		@Test
		@DisplayName(
			"commit removeFacet draining group"
		)
		void shouldCommitRemoveDrainingGroup() {
			final FacetIdIndex fi =
				new FacetIdIndex(10, new BaseBitmap(100));
			final FacetGroupIndex group =
				new FacetGroupIndex(1, List.of(fi));
			final FacetReferenceIndex index =
				new FacetReferenceIndex(
					"ref", List.of(group)
				);

			assertStateAfterCommit(
				index,
				original -> original.removeFacet(10, 1, 100),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertTrue(committed.isEmpty());
				}
			);
		}

		@Test
		@DisplayName("original unchanged after commit")
		void shouldPreserveOriginalAfterCommit() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");

			assertStateAfterCommit(
				index,
				original -> {
					original.addFacet(10, null, 100);
					original.addFacet(20, 1, 200);
				},
				(original, committed) -> {
					assertTrue(original.isEmpty());
					assertEquals(2, committed.size());
				}
			);
		}

		@Test
		@DisplayName(
			"add in no-group AND group in same tx"
		)
		void shouldCommitMixedChanges() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");

			assertStateAfterCommit(
				index,
				original -> {
					original.addFacet(10, null, 100);
					original.addFacet(20, 5, 200);
				},
				(original, committed) -> {
					assertTrue(original.isEmpty());
					assertNotNull(
						committed.getNotGroupedFacets()
					);
					assertNotNull(committed.getFacetsInGroup(5));
					assertEquals(2, committed.size());
				}
			);
		}
	}

	@Nested
	@DisplayName("STM — Rollback")
	class RollbackTest {

		@Test
		@DisplayName(
			"no-group and grouped mutations discarded"
		)
		void shouldRollbackAllMutations() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");

			assertStateAfterRollback(
				index,
				original -> {
					original.addFacet(10, null, 100);
					original.addFacet(20, 1, 200);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isEmpty());
				}
			);
		}
	}

	@Nested
	@DisplayName("Other")
	class OtherTest {

		@Test
		@DisplayName("toString with only no-group facets")
		void shouldFormatToStringNoGroupOnly() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, null, 100);
			final String result = index.toString();
			assertTrue(
				result.contains("[NO_GROUP]"),
				"Expected [NO_GROUP] in toString but was: "
					+ result
			);
		}

		@Test
		@DisplayName("toString with only grouped facets")
		void shouldFormatToStringGroupedOnly() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, 5, 100);
			final String result = index.toString();
			assertTrue(
				result.contains("GROUP 5"),
				"Expected GROUP 5 in toString but was: "
					+ result
			);
		}

		@Test
		@DisplayName("toString with both no-group and grouped")
		void shouldFormatToStringBoth() {
			final FacetReferenceIndex index =
				new FacetReferenceIndex("ref");
			index.addFacet(10, null, 100);
			index.addFacet(20, 5, 200);
			final String result = index.toString();
			assertTrue(result.contains("[NO_GROUP]"));
			assertTrue(result.contains("GROUP 5"));
		}
	}

	/**
	 * Tests for {@link FacetReferenceIndex#getGroupIdForFacet(int)} covering
	 * grouped facets, ungrouped facets, missing facets, multi-group scenarios,
	 * and group reassignment.
	 */
	@Nested
	@DisplayName("getGroupIdForFacet lookup")
	class GetGroupIdForFacetTest {

		@Test
		@DisplayName("returns group id when facet exists in a group")
		void shouldReturnGroupIdWhenFacetExistsInGroup() {
			final FacetReferenceIndex index = new FacetReferenceIndex("ref");
			index.addFacet(10, 5, 100);

			final Integer result = index.getGroupIdForFacet(10);

			assertEquals(5, result);
		}

		@Test
		@DisplayName("returns null when facet is ungrouped")
		void shouldReturnNullWhenFacetIsUngrouped() {
			final FacetReferenceIndex index = new FacetReferenceIndex("ref");
			index.addFacet(10, null, 100);

			final Integer result = index.getGroupIdForFacet(10);

			assertNull(result);
		}

		@Test
		@DisplayName("returns null when facet does not exist at all")
		void shouldReturnNullWhenFacetDoesNotExist() {
			final FacetReferenceIndex index = new FacetReferenceIndex("ref");

			final Integer result = index.getGroupIdForFacet(999);

			assertNull(result);
		}

		@Test
		@DisplayName("returns first group id when facet belongs to multiple groups")
		void shouldReturnFirstGroupWithMultipleGroups() {
			final FacetReferenceIndex index = new FacetReferenceIndex("ref");
			index.addFacet(10, 5, 100);
			index.addFacet(10, 7, 200);

			final Integer result = index.getGroupIdForFacet(10);

			// facetToGroupIndex stores sorted int[], so the smallest group id comes first
			assertEquals(5, result);
		}

		/**
		 * Verifies that after removing all facets from a group via `removeFacet`, `getGroupIdForFacet`
		 * returns null for that facet (the facet-to-group mapping is cleaned up).
		 */
		@Test
		@DisplayName("returns null after all members removed from group")
		void shouldReturnNullAfterAllMembersRemovedFromGroup() {
			final FacetReferenceIndex index = new FacetReferenceIndex("ref");
			index.addFacet(10, 5, 100);
			index.addFacet(10, 5, 200);

			assertEquals(5, index.getGroupIdForFacet(10));

			// remove all entity PKs from the group
			index.removeFacet(10, 5, 100);
			index.removeFacet(10, 5, 200);

			// facet 10 should no longer be mapped to any group
			assertNull(
				index.getGroupIdForFacet(10),
				"getGroupIdForFacet should return null after all facet members removed"
			);
		}

		@Test
		@DisplayName("reflects group reassignment after remove and re-add")
		void shouldReflectGroupReassignment() {
			final FacetReferenceIndex index = new FacetReferenceIndex("ref");
			index.addFacet(10, 5, 100);
			assertEquals(5, index.getGroupIdForFacet(10));

			index.removeFacet(10, 5, 100);
			index.addFacet(10, 8, 100);

			final Integer result = index.getGroupIdForFacet(10);

			assertEquals(8, result);
		}
	}

	/**
	 * Tests for {@link FacetReferenceIndex#getFacetReferencingEntityIdsFormula(TriFunction, Bitmap)} covering
	 * the bucketing by group id, the `null` key the ungrouped index is held under, the order the formulas come
	 * back in, and the positional alignment of the entity-id bitmaps with the facet ids they belong to.
	 */
	@Nested
	@DisplayName("getFacetReferencingEntityIdsFormula")
	class GetFacetReferencingEntityIdsFormulaTest {
		/**
		 * Reference name every index and formula built here carries.
		 */
		private static final String REFERENCE_NAME = "ref";
		/**
		 * Width of the request the allocation guard makes. It stands for a `facetHaving` inner filter that
		 * resolved to many referenced entities, which is what the method is handed in production - the bitmap
		 * is a computed result, not a user's enumeration, so nothing bounds it.
		 */
		private static final int REQUESTED_FACETS = 20_000;
		/**
		 * Samples the allocation guard takes, of which it reports the median, so that one TLAB refill landing
		 * inside a measurement window cannot decide the verdict.
		 */
		private static final int SAMPLES = 9;
		/**
		 * Largest tolerated difference between bucketing {@link #REQUESTED_FACETS} facets into two groups and
		 * making the same request against an index that holds nothing. Everything that scales with the request
		 * cancels between those two arms, so what is left is the accumulator, the two buckets and the two
		 * formulas. Measured medians of nine, OpenJDK 21: **2 408 B** for a map sized by the group count,
		 * **133 480 B** for one sized by the request, which is the 32 768-slot table the latter allocates on
		 * the first bucket it creates. The limit sits an order of magnitude below the second and an order of
		 * magnitude above the first, so it discriminates with headroom in both directions.
		 */
		private static final long MAX_BUCKET_MAP_BYTES = 32_768L;

		/**
		 * Builds one {@link FacetGroupAndFormula} per group. Its constructor asserts that there is exactly one
		 * bitmap per facet id, so handing it in as the factory checks the array's alignment on every call below
		 * without a single explicit assertion.
		 */
		private final TriFunction<Integer, Bitmap, Bitmap[], FacetGroupFormula> formulaFactory =
			(groupId, facetIds, bitmaps) -> new FacetGroupAndFormula(REFERENCE_NAME, groupId, facetIds, bitmaps);

		@Test
		@DisplayName("returns one formula per group, each carrying only its own facets")
		void shouldReturnOneFormulaPerGroup() {
			final FacetReferenceIndex index = new FacetReferenceIndex(REFERENCE_NAME);
			index.addFacet(10, 1, 100);
			index.addFacet(11, 1, 101);
			index.addFacet(20, 2, 200);

			final List<FacetGroupFormula> formulas = index.getFacetReferencingEntityIdsFormula(
				this.formulaFactory, new BaseBitmap(10, 11, 20)
			);

			assertEquals(2, formulas.size());
			assertEquals(1, formulas.get(0).getFacetGroupId());
			assertArrayEquals(new int[]{10, 11}, formulas.get(0).getFacetIds().getArray());
			assertEquals(2, formulas.get(1).getFacetGroupId());
			assertArrayEquals(new int[]{20}, formulas.get(1).getFacetIds().getArray());
		}

		@Test
		@DisplayName("a facet held in several groups is reported once under each of them")
		void shouldReturnAFormulaPerGroupForAFacetInSeveralGroups() {
			final FacetReferenceIndex index = new FacetReferenceIndex(REFERENCE_NAME);
			index.addFacet(10, 1, 100);
			index.addFacet(10, 2, 200);

			final List<FacetGroupFormula> formulas = index.getFacetReferencingEntityIdsFormula(
				this.formulaFactory, new BaseBitmap(10)
			);

			assertEquals(2, formulas.size());
			assertEquals(1, formulas.get(0).getFacetGroupId());
			assertArrayEquals(new int[]{100}, formulas.get(0).getBitmaps()[0].getArray());
			assertEquals(2, formulas.get(1).getFacetGroupId());
			assertArrayEquals(new int[]{200}, formulas.get(1).getBitmaps()[0].getArray());
		}

		@Test
		@DisplayName("an ungrouped facet is bucketed under the null key")
		void shouldBucketUngroupedFacetsUnderTheNullKey() {
			final FacetReferenceIndex index = new FacetReferenceIndex(REFERENCE_NAME);
			index.addFacet(10, null, 100);
			index.addFacet(11, null, 101);

			final List<FacetGroupFormula> formulas = index.getFacetReferencingEntityIdsFormula(
				this.formulaFactory, new BaseBitmap(10, 11)
			);

			assertEquals(1, formulas.size());
			assertNull(
				formulas.get(0).getFacetGroupId(),
				"the ungrouped index is keyed by null, which a grouping collector would have rejected"
			);
			assertArrayEquals(new int[]{10, 11}, formulas.get(0).getFacetIds().getArray());
		}

		@Test
		@DisplayName("grouped and ungrouped facets asked for together come back as separate formulas")
		void shouldReturnBothTheUngroupedAndTheGroupedFormula() {
			final FacetReferenceIndex index = new FacetReferenceIndex(REFERENCE_NAME);
			index.addFacet(10, null, 100);
			index.addFacet(20, 5, 200);

			final List<FacetGroupFormula> formulas = index.getFacetReferencingEntityIdsFormula(
				this.formulaFactory, new BaseBitmap(10, 20)
			);

			assertEquals(2, formulas.size());
			assertNull(formulas.get(0).getFacetGroupId());
			assertArrayEquals(new int[]{10}, formulas.get(0).getFacetIds().getArray());
			assertEquals(5, formulas.get(1).getFacetGroupId());
			assertArrayEquals(new int[]{20}, formulas.get(1).getFacetIds().getArray());
		}

		@Test
		@DisplayName("each entity bitmap sits at the position of the facet id it belongs to")
		void shouldAlignTheEntityBitmapsWithTheFacetIds() {
			final FacetReferenceIndex index = new FacetReferenceIndex(REFERENCE_NAME);
			index.addFacet(10, 1, 100);
			index.addFacet(10, 1, 101);
			index.addFacet(11, 1, 200);

			final List<FacetGroupFormula> formulas = index.getFacetReferencingEntityIdsFormula(
				this.formulaFactory, new BaseBitmap(10, 11)
			);

			assertEquals(1, formulas.size());
			final FacetGroupFormula formula = formulas.get(0);
			assertArrayEquals(new int[]{10, 11}, formula.getFacetIds().getArray());
			assertEquals(2, formula.getBitmaps().length);
			assertArrayEquals(new int[]{100, 101}, formula.getBitmaps()[0].getArray());
			assertArrayEquals(new int[]{200}, formula.getBitmaps()[1].getArray());
		}

		@Test
		@DisplayName("a facet its bucket does not hold still gets a slot, filled with an empty bitmap")
		void shouldFillTheSlotOfAnUnknownFacetWithAnEmptyBitmap() {
			final FacetReferenceIndex index = new FacetReferenceIndex(REFERENCE_NAME);
			index.addFacet(10, null, 100);

			// facet 999 belongs to no group, so it joins the ungrouped bucket even though that index has never
			// heard of it - the bitmap array must keep a slot for it rather than shift the remaining ones
			final List<FacetGroupFormula> formulas = index.getFacetReferencingEntityIdsFormula(
				this.formulaFactory, new BaseBitmap(10, 999)
			);

			assertEquals(1, formulas.size());
			final FacetGroupFormula formula = formulas.get(0);
			assertArrayEquals(new int[]{10, 999}, formula.getFacetIds().getArray());
			assertEquals(2, formula.getBitmaps().length);
			assertArrayEquals(new int[]{100}, formula.getBitmaps()[0].getArray());
			assertSame(EmptyBitmap.INSTANCE, formula.getBitmaps()[1]);
		}

		@Test
		@DisplayName("a facet no index holds at all is skipped")
		void shouldSkipFacetsThatAreNotIndexed() {
			final FacetReferenceIndex index = new FacetReferenceIndex(REFERENCE_NAME);
			index.addFacet(10, 1, 100);

			// nothing was ever added without a group, so there is no ungrouped index for facet 999 to land in
			final List<FacetGroupFormula> formulas = index.getFacetReferencingEntityIdsFormula(
				this.formulaFactory, new BaseBitmap(10, 999)
			);

			assertEquals(1, formulas.size());
			assertArrayEquals(new int[]{10}, formulas.get(0).getFacetIds().getArray());
		}

		@Test
		@DisplayName("an input of nothing but unknown facets yields no formula at all")
		void shouldReturnNoFormulaWhenNoRequestedFacetIsIndexed() {
			final FacetReferenceIndex index = new FacetReferenceIndex(REFERENCE_NAME);
			index.addFacet(10, 1, 100);

			assertTrue(
				index.getFacetReferencingEntityIdsFormula(this.formulaFactory, new BaseBitmap(998, 999)).isEmpty()
			);
		}

		@Test
		@DisplayName("the formulas follow the order their groups were first met in")
		void shouldReturnFormulasInEncounterOrder() {
			final FacetReferenceIndex index = new FacetReferenceIndex(REFERENCE_NAME);
			// the lower facet id belongs to the higher group id, so a map ordered by key would swap the two
			index.addFacet(1, 20, 100);
			index.addFacet(2, 10, 200);

			final List<FacetGroupFormula> formulas = index.getFacetReferencingEntityIdsFormula(
				this.formulaFactory, new BaseBitmap(1, 2)
			);

			assertEquals(2, formulas.size());
			assertEquals(20, formulas.get(0).getFacetGroupId(), "the group of the first facet met");
			assertEquals(10, formulas.get(1).getFacetGroupId(), "the group of the second facet met");
		}

		@Test
		@DisplayName("the number of formulas follows the group count, not the number of facets asked for")
		void shouldReturnOneFormulaPerGroupWhateverTheFacetCountIs() {
			final int facetCount = 20_000;
			final FacetReferenceIndex index = new FacetReferenceIndex(REFERENCE_NAME);
			final int[] facetIds = new int[facetCount];
			for (int i = 0; i < facetCount; i++) {
				facetIds[i] = i + 1;
				index.addFacet(facetIds[i], i % 2 == 0 ? 1 : 2, 1_000_000 + i);
			}

			final List<FacetGroupFormula> formulas = index.getFacetReferencingEntityIdsFormula(
				this.formulaFactory, new BaseBitmap(facetIds)
			);

			assertEquals(2, formulas.size(), "twenty thousand facets fall into exactly two groups");
			assertEquals(facetCount / 2, formulas.get(0).getFacetIds().size());
			assertEquals(facetCount / 2, formulas.get(1).getFacetIds().size());
		}

		@Test
		@DisplayName("the bucket map is sized by the groups it will hold, not by the facets asked for")
		void shouldNotSizeTheGroupBucketMapByTheRequestedFacetCount() {
			final java.lang.management.ThreadMXBean platformThreads = ManagementFactory.getThreadMXBean();
			assumeTrue(
				platformThreads instanceof ThreadMXBean,
				"per-thread allocation accounting is a HotSpot extension - this guard cannot run on this JVM"
			);
			final ThreadMXBean threads = (ThreadMXBean) platformThreads;
			assumeTrue(
				threads.isThreadAllocatedMemorySupported() && threads.isThreadAllocatedMemoryEnabled(),
				"per-thread allocation accounting is disabled - this guard cannot run"
			);

			// Two arms over the *same* request bitmap, differing only in whether any facet of it is indexed.
			// Everything that scales with the request width - the iteration, and one boxed key per lookup - is
			// paid identically by both, so their difference is the accumulator and nothing else. The empty arm
			// never puts anything, and a hash map allocates its table on the first put, so it never has one.
			final Bitmap request = new BaseBitmap(requestedFacetIds(REQUESTED_FACETS));
			final FacetReferenceIndex empty = new FacetReferenceIndex(REFERENCE_NAME);
			final FacetReferenceIndex twoGroups = new FacetReferenceIndex(REFERENCE_NAME);
			twoGroups.addFacet(1, 1, 100);
			twoGroups.addFacet(2, 2, 200);

			// warm up, so neither arm is charged for one-time class initialisation of the path it walks
			for (int warmUp = 0; warmUp < 3; warmUp++) {
				empty.getFacetReferencingEntityIdsFormula(this.formulaFactory, request);
				twoGroups.getFacetReferencingEntityIdsFormula(this.formulaFactory, request);
			}

			final long[] deltas = new long[SAMPLES];
			for (int sample = 0; sample < SAMPLES; sample++) {
				final long beforeEmpty = threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
				assertTrue(empty.getFacetReferencingEntityIdsFormula(this.formulaFactory, request).isEmpty());
				final long emptyBytes =
					threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - beforeEmpty;

				final long beforeTwoGroups = threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
				final List<FacetGroupFormula> formulas =
					twoGroups.getFacetReferencingEntityIdsFormula(this.formulaFactory, request);
				final long twoGroupBytes =
					threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - beforeTwoGroups;

				assertEquals(2, formulas.size(), "the request touches exactly two groups");
				deltas[sample] = twoGroupBytes - emptyBytes;
			}

			final long[] sorted = deltas.clone();
			Arrays.sort(sorted);
			final long median = sorted[sorted.length / 2];
			// reported whether or not the assertion holds, because the number is the first thing anyone
			// looking at this test wants and the last thing they can get from a boolean
			System.out.printf(
				"FacetReferenceIndex bucket accumulator for %d requested facets in 2 groups: %d B (median of %d)%n",
				REQUESTED_FACETS, median, SAMPLES
			);
			assertTrue(
				median < MAX_BUCKET_MAP_BYTES,
				() -> "bucketing " + REQUESTED_FACETS + " requested facets into two groups allocated " + median
					+ " bytes more than the same request against an empty index (limit " + MAX_BUCKET_MAP_BYTES
					+ ") - the accumulator appears to be sized by the request width again"
			);
		}

		/**
		 * The facet ids one request carries: the two the index knows, then a long tail of ids no index holds,
		 * so that the requested width is large while the bucket count stays at two.
		 *
		 * @param count how many ids to build
		 * @return the ids, ascending
		 */
		@Nonnull
		private static int[] requestedFacetIds(int count) {
			final int[] facetIds = new int[count];
			for (int i = 0; i < count; i++) {
				facetIds[i] = i + 1;
			}
			return facetIds;
		}

		// Known limitation: a facet held in both a group index and the ungrouped index is reported only under
		// its group, so the entities that reference it without a group are dropped from the formula
		@Test
		@DisplayName("a facet indexed both in a group and without one is reported only under its group")
		void shouldReportOnlyTheGroupFormulaForAFacetIndexedBothWays() {
			final FacetReferenceIndex index = new FacetReferenceIndex(REFERENCE_NAME);
			index.addFacet(10, null, 100);
			index.addFacet(10, 5, 200);

			final List<FacetGroupFormula> formulas = index.getFacetReferencingEntityIdsFormula(
				this.formulaFactory, new BaseBitmap(10)
			);

			assertEquals(1, formulas.size());
			assertEquals(5, formulas.get(0).getFacetGroupId());
			assertArrayEquals(
				new int[]{200}, formulas.get(0).getBitmaps()[0].getArray(),
				"entity 100 references facet 10 without a group and is not reported"
			);
		}
	}
}
