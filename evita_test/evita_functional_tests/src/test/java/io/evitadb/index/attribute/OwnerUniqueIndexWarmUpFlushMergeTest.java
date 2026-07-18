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

package io.evitadb.index.attribute;

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Iterator;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the WARM_UP (bulk, non-transactional) flush sequence on {@link OwnerUniqueIndex}: a first flush pages the
 * value tree out to disk WITHOUT publishing (a warm-up flush never reaches the commit-merge that would otherwise
 * promote the staged page set to live), followed by a second flush whose deletes force a leaf MERGE. A merge drops a
 * page without creating one, so the freed-page diff and the `PAGED` root re-emission both depend on the flush being
 * able to see what the FIRST flush actually wrote to disk — not on an empty baseline left behind by a commit-merge
 * that, for a warm-up flush, never runs.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("A warm-up flush leaf merge must free its dropped page and re-emit the PAGED root on OwnerUniqueIndex")
class OwnerUniqueIndexWarmUpFlushMergeTest {

	private static final String ENTITY_TYPE = "product";
	private static final AttributeIndexKey CODE_KEY = new AttributeIndexKey(null, "code", null);
	private static final int ENTITY_INDEX_PK = 1;
	/**
	 * With valueBlockSize=256 (split at 128) and minValueBlockSize=127, an ascending stream of 513 distinct values lays
	 * the value tree out as four leaves — [1..128], [129..256], [257..384], [385..513] — the smallest layout that can
	 * lose a leaf to a merge and still stay PAGED afterwards.
	 */
	private static final int VALUE_COUNT = 513;

	/**
	 * Returns the record id owning the `i`-th distinct value (stable 1:1 mapping).
	 */
	private static int recordId(int i) {
		return 500_000 + i;
	}

	@Test
	@DisplayName("should free the merged-away leaf page and re-emit the PAGED root after a second warm-up flush merges a leaf")
	void shouldFreeStalePageAndReemitRootAfterWarmUpFlushMerge() {
		final OwnerUniqueIndex index = new OwnerUniqueIndex(ENTITY_TYPE, CODE_KEY, Integer.class);
		for (int i = 1; i <= VALUE_COUNT; i++) {
			index.registerUniqueKey(i, recordId(i));
		}

		// first WARM_UP flush: allocates + stages 4 leaf pages, never publishes them - a warm-up flush never reaches
		// the commit-merge (createCopyWithMergedTransactionalMemory), which is the only place that call runs
		final TrappedChanges firstFlush = new TrappedChanges();
		index.appendStorageParts(ENTITY_INDEX_PK, firstFlush);
		index.resetDirty();

		assertTrue(index.isPaged(), "The index must be PAGED before the merge!");
		assertEquals(
			4, index.currentLeafPageSequences().length,
			"The first warm-up flush must lay the tree out as four leaf pages -- the layout the deletes below are " +
				"calibrated against!"
		);

		// second WARM_UP flush: drop exactly enough values to force leaf 0 to merge with leaf 1.
		// leaves start out [1..128], [129..256], [257..384], [385..513]:
		// - leaf 1: 128 -> 127, so it sits exactly at the minimum and can no longer donate a key
		index.unregisterUniqueKey(129, recordId(129));
		// - leaf 0: 128 -> 127 (still at the minimum, no underflow yet)
		index.unregisterUniqueKey(1, recordId(1));
		// - leaf 0: 127 -> 126 -> underflow. It has no left sibling, the right sibling cannot donate
		//   (127 > 127 is false) and 127 + 126 = 253 < 256, so consolidate takes mergeWithRight: leaf 0 absorbs
		//   leaf 1 IN PLACE - it keeps page 0 and is marked dirty, while leaf 1 is detached and its page must be freed
		index.unregisterUniqueKey(2, recordId(2));

		final TrappedChanges secondFlush = new TrappedChanges();
		index.appendStorageParts(ENTITY_INDEX_PK, secondFlush);

		// pin that a leaf really was merged away: without this the test would still pass if the block-size constants
		// ever drifted so the deletes no longer underflow a leaf - the tree would simply stay intact and never
		// exercise the defect at all
		assertEquals(
			3, index.currentLeafPageSequences().length,
			"The deletes must have merged a leaf away (four leaf pages -> three); if this still reports four, no " +
				"merge happened and the test is not exercising the freed-page path!"
		);
		// the tree must still span several leaves: had it collapsed to the inline SINGLE shape, that path force-emits
		// the root unconditionally and would mask the defect under test
		assertTrue(index.isPaged(), "The index must stay PAGED after the merge!");

		int removalCount = 0;
		UniqueIndexStoragePart rootPart = null;
		final Iterator<StoragePart> emittedParts = secondFlush.getTrappedChangesIterator();
		while (emittedParts.hasNext()) {
			final StoragePart part = emittedParts.next();
			if (part instanceof UniqueIndexLeafPageRemoval) {
				removalCount++;
			} else if (part instanceof UniqueIndexStoragePart uniquePart && uniquePart.isPaged()) {
				rootPart = uniquePart;
			}
		}

		assertEquals(
			1, removalCount,
			"The leaf merge must free exactly the one dropped leaf page - a leaf-page removal must be emitted for " +
				"it, or the freed page is never removed from storage and is copied forward by every compaction forever!"
		);
		assertNotNull(
			rootPart,
			"The PAGED root must be re-emitted after a merge changed the ordered leaf-page list - otherwise a cold " +
				"reload assembles the stale, still-listed dropped page back into the tree and the cross-leaf overlap " +
				"check fires!"
		);
		assertEquals(
			3, rootPart.getLeafPageSequences().length, "The re-emitted root must list only the three surviving leaves!"
		);
	}
}
