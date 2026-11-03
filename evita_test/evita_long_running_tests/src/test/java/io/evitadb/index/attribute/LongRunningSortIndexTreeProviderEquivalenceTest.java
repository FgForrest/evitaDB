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
package io.evitadb.index.attribute;

import io.evitadb.core.query.sort.SortedRecordsSupplierFactory.SortedRecordsProvider;
import io.evitadb.index.attribute.SortIndexTreeProviderEquivalenceTest.Fixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.index.attribute.SortIndexTreeProviderEquivalenceTest.assertMaskSpansBothContainers;
import static io.evitadb.index.attribute.SortIndexTreeProviderEquivalenceTest.buildFixture;
import static io.evitadb.index.attribute.SortIndexTreeProviderEquivalenceTest.reverse;
import static io.evitadb.index.attribute.SortIndexTreeProviderEquivalenceTest.verifyProvider;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;

/**
 * The large-`N` case of {@link SortIndexTreeProviderEquivalenceTest}: a tree-path resolution whose positions span many
 * B+tree leaves AND both 16-bit RoaringBitmap containers (positions beyond 65536) must match the array path and the
 * oracle. Building an index this large takes too long for the fast functional loop, so the case runs here, with the
 * helpers of the fast test.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("SortIndex tree-backed provider equivalence across many leaves and bitmap containers")
class LongRunningSortIndexTreeProviderEquivalenceTest {

	/**
	 * Record count for the multi-leaf / multi-container coverage: large enough that a tree-path selection resolves to
	 * mask positions beyond the first 16-bit RoaringBitmap container (65536) and spanning many B+tree leaves.
	 */
	private static final int LARGE_RECORD_COUNT = 70_000;

	@Test
	@DisplayName("tree-path resolution spanning many leaves and multiple bitmap containers matches array and oracle")
	void shouldMatchAcrossMultipleLeavesAndContainers() {
		// a large index whose sorted order exceeds one 16-bit RoaringBitmap container (positions run up to ~70000)
		final Fixture fixture = buildFixture(20260706L, LARGE_RECORD_COUNT);
		// tree path requires (K + 2) * 64 <= LARGE_RECORD_COUNT (K up to ~1091); these selections spread present ids
		// across the whole order so the resolved masks span many B+tree leaves AND both bitmap containers
		final int[] treePathSizes = {200, 500, 800, 1000};

		final SortedRecordsProvider ascending = fixture.sortIndex().getAscendingOrderRecordsSupplier();
		verifyProvider(ascending, fixture.ascendingOrder(), fixture.presentRecordIds(), treePathSizes, 42L);
		assertMaskSpansBothContainers(ascending, fixture.ascendingOrder().length);

		final int[] descendingOrder = fixture.ascendingOrder().clone();
		reverse(descendingOrder);
		final SortedRecordsProvider descending = fixture.sortIndex().getDescendingOrderRecordsSupplier();
		verifyProvider(descending, descendingOrder, fixture.presentRecordIds(), treePathSizes, 99L);
		assertMaskSpansBothContainers(descending, descendingOrder.length);
	}

}
