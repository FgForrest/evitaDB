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

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexStoragePart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.Iterator;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Reproduces the WARM_UP (bulk, non-transactional) flush sequence on {@link GlobalUniqueIndex}: a first flush pages
 * the value tree out to disk WITHOUT publishing (a warm-up flush never reaches the commit-merge that would otherwise
 * promote the staged page set to live), followed by a second flush whose deletes force a leaf MERGE. A merge drops a
 * page without creating one, so the freed-page diff a leaf merge relies on depends on the flush being able to see
 * what the FIRST flush actually wrote to disk — not on an empty baseline left behind by a commit-merge that, for a
 * warm-up flush, never runs.
 *
 * Unlike {@link OwnerUniqueIndex}, the `PAGED` root of a {@link GlobalUniqueIndex} also carries the inline locale map
 * and is therefore re-emitted unconditionally on every dirty flush (it cannot use the page-list-unchanged skip), so
 * this index's manifestation of the defect is narrower: only the freed leaf page itself is left behind, never removed
 * from storage and copied forward by every future compaction — the root always correctly lists the surviving leaves.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("A warm-up flush leaf merge must free its dropped page on GlobalUniqueIndex")
class GlobalUniqueIndexWarmUpFlushMergeTest {

	private static final String ENTITY_TYPE = "product";
	private static final int ENTITY_TYPE_PK = 1;
	private static final AttributeKey URL_KEY = new AttributeKey("code");
	/**
	 * With valueBlockSize=256 (split at 128) and minValueBlockSize=127, an ascending stream of 513 distinct values lays
	 * the value tree out as four leaves — [1..128], [129..256], [257..384], [385..513] — the smallest layout that can
	 * lose a leaf to a merge and still stay PAGED afterwards.
	 */
	private static final int VALUE_COUNT = 513;

	private final Catalog catalog = Mockito.mock(Catalog.class);

	@BeforeEach
	void setUp() {
		final EntityCollection productCollection = Mockito.mock(EntityCollection.class);
		Mockito.when(productCollection.getEntityTypePrimaryKey()).thenReturn(ENTITY_TYPE_PK);
		Mockito.when(this.catalog.getCollectionForEntityOrThrowException(ENTITY_TYPE)).thenReturn(productCollection);
	}

	@Test
	@DisplayName("should free the merged-away leaf page after a second warm-up flush merges a leaf")
	void shouldFreeStalePageAfterWarmUpFlushMerge() {
		final GlobalUniqueIndex index = new GlobalUniqueIndex(Scope.LIVE, URL_KEY, Integer.class);
		index.attachToCatalog(null, this.catalog);
		for (int i = 1; i <= VALUE_COUNT; i++) {
			index.registerUniqueKey(i, ENTITY_TYPE, null, i);
		}

		// first WARM_UP flush: allocates + stages 4 leaf pages, never publishes them - a warm-up flush never reaches
		// the commit-merge (createCopyWithMergedTransactionalMemory), which is the only place that call runs
		final TrappedChanges firstFlush = new TrappedChanges();
		index.appendStorageParts(URL_KEY, firstFlush);
		index.resetDirty();

		assertTrue(index.isPaged(), "The index must be PAGED before the merge!");
		final GlobalUniqueIndexStoragePart firstRoot = extractPagedRoot(firstFlush);
		assertEquals(
			4, firstRoot.getLeafPageSequences().length,
			"The first warm-up flush must lay the tree out as four leaf pages -- the layout the deletes below are " +
				"calibrated against!"
		);

		// second WARM_UP flush: drop exactly enough values to force leaf 0 to merge with leaf 1.
		// leaves start out [1..128], [129..256], [257..384], [385..513]:
		// - leaf 1: 128 -> 127, so it sits exactly at the minimum and can no longer donate a key
		index.unregisterUniqueKey(129, ENTITY_TYPE, null, 129);
		// - leaf 0: 128 -> 127 (still at the minimum, no underflow yet)
		index.unregisterUniqueKey(1, ENTITY_TYPE, null, 1);
		// - leaf 0: 127 -> 126 -> underflow. It has no left sibling, the right sibling cannot donate
		//   (127 > 127 is false) and 127 + 126 = 253 < 256, so consolidate takes mergeWithRight: leaf 0 absorbs
		//   leaf 1 IN PLACE - it keeps page 0 and is marked dirty, while leaf 1 is detached and its page must be freed
		index.unregisterUniqueKey(2, ENTITY_TYPE, null, 2);

		final TrappedChanges secondFlush = new TrappedChanges();
		index.appendStorageParts(URL_KEY, secondFlush);

		// pin that a leaf really was merged away: without this the test would still pass if the block-size constants
		// ever drifted so the deletes no longer underflow a leaf - the tree would simply stay intact and never
		// exercise the defect at all
		final GlobalUniqueIndexStoragePart secondRoot = extractPagedRoot(secondFlush);
		assertEquals(
			3, secondRoot.getLeafPageSequences().length,
			"The deletes must have merged a leaf away (four leaf pages -> three); if this still reports four, no " +
				"merge happened and the test is not exercising the freed-page path!"
		);
		// the tree must still span several leaves: had it collapsed to the inline SINGLE shape, that path force-emits
		// removals for every prior page unconditionally and would mask the defect under test
		assertTrue(index.isPaged(), "The index must stay PAGED after the merge!");

		int removalCount = 0;
		final Iterator<StoragePart> emittedParts = secondFlush.getTrappedChangesIterator();
		while (emittedParts.hasNext()) {
			if (emittedParts.next() instanceof GlobalUniqueIndexLeafPageRemoval) {
				removalCount++;
			}
		}

		assertEquals(
			1, removalCount,
			"The leaf merge must free exactly the one dropped leaf page - a leaf-page removal must be emitted for " +
				"it, or the freed page is never removed from storage and is copied forward by every compaction forever!"
		);
	}

	/**
	 * Finds the single re-emitted `PAGED` root part in a flush emission (the root is unconditionally re-emitted on
	 * every dirty {@link GlobalUniqueIndex} flush, since it also carries the inline locale map).
	 */
	@Nonnull
	private static GlobalUniqueIndexStoragePart extractPagedRoot(@Nonnull TrappedChanges changes) {
		final Iterator<StoragePart> emittedParts = changes.getTrappedChangesIterator();
		while (emittedParts.hasNext()) {
			final StoragePart part = emittedParts.next();
			if (part instanceof GlobalUniqueIndexStoragePart root && root.isPaged()) {
				return root;
			}
		}
		return fail("No PAGED root storage part was emitted by the flush!");
	}
}
