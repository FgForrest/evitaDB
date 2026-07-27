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

import io.evitadb.index.bitmap.BaseBitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;
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
	@DisplayName("Non-transactional operations (T8)")
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
			"commit grouped addFacet: new group in committed (T1)"
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
			"commit no-group addFacet (T1)"
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
			"commit removeFacet draining group (INV-12)"
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
		@DisplayName("T2: Original unchanged after commit")
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
			"T5: Add in no-group AND group in same tx"
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
			"no-group and grouped mutations discarded (T7)"
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
}
