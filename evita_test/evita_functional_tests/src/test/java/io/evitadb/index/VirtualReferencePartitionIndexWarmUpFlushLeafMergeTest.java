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

package io.evitadb.index;

import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A warm-up (bulk) flush never reaches the transactional commit-merge that publishes the staged page set, so two
 * consecutive flushes of the same live {@link VirtualReferencePartitionIndex}, with a leaf merge in between, must
 * still detect and remove the page the merge dropped - and, because this PAGED root lists only its leaves, must
 * rewrite the root that still lists it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(REFERENCE)
@DisplayName("A leaf merge between two warm-up flushes with no intermediate publish (VirtualReferencePartitionIndex)")
class VirtualReferencePartitionIndexWarmUpFlushLeafMergeTest {
	/** Entry-tree leaf capacity; a full leaf splits into two 128-halves. */
	private static final int VALUE_BLOCK_SIZE = 256;
	/**
	 * Three leaves of half capacity - the layout an ascending insertion of this many owner entries in front of the
	 * {@link #SENTINEL_PARTITION} produces: leaf 0 holds partitions 1..128, leaf 1 partitions 129..256 and leaf 2
	 * partitions 257..383 with the sentinel.
	 */
	private static final int PARTITION_COUNT = 3 * (VALUE_BLOCK_SIZE / 2) - 1;
	/**
	 * Inserted first and always the largest key, so every later insertion lands in front of it and full leaves split
	 * into halves instead of taking the append-at-the-end path.
	 */
	private static final int SENTINEL_PARTITION = Integer.MAX_VALUE;

	@Test
	@DisplayName("the leaf page a merge drops between two warm-up flushes must be removed and unlisted")
	void shouldReportFreedLeafPageAcrossConsecutiveWarmUpFlushesWithoutAnIntermediatePublish() {
		final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
		index.putPartitionOwner(SENTINEL_PARTITION, -1, -1);
		for (int partition = 1; partition <= PARTITION_COUNT; partition++) {
			index.putPartitionOwner(partition, partition * 10, partition * 100);
		}

		// first warm-up flush: stages page sequences 0, 1, 2 for the three fresh leaves and is never published
		final List<StoragePart> firstFlush = VirtualReferencePartitionIndexTest.flush(index);
		index.resetDirty();
		assertEquals(3, countLeafPages(firstFlush), "The first flush must write every one of the three fresh leaves.");
		assertEquals(3, singleRoot(firstFlush).getLeafPageSequences().length);

		// force leaf 0 to merge into leaf 1 while leaf 2 stays untouched: bring leaf 1 down to the minimum (127) so it
		// can no longer donate, then push leaf 0 one below the minimum
		index.removePartitionOwner(129);
		index.removePartitionOwner(1);
		index.removePartitionOwner(2);

		// second warm-up flush: the freed-page diff must be taken against what the first flush staged, or the dropped
		// page is never removed - and the root, which only lists leaves, must be rewritten without it
		final List<StoragePart> secondFlush = VirtualReferencePartitionIndexTest.flush(index);
		assertEquals(
			1, countRemovals(secondFlush),
			"The leaf page the merge dropped must be reported as freed, or it leaks in storage forever."
		);
		assertEquals(
			2, singleRoot(secondFlush).getLeafPageSequences().length,
			"The root must be rewritten and list the two surviving leaves only."
		);
	}

	/**
	 * Extracts the single root emitted by a flush, failing if none or more than one is present.
	 *
	 * @param parts the emitted storage parts
	 * @return the sole root part
	 */
	@Nonnull
	private static VirtualReferencePartitionIndexStoragePart singleRoot(@Nonnull List<StoragePart> parts) {
		VirtualReferencePartitionIndexStoragePart root = null;
		for (final StoragePart part : parts) {
			if (part instanceof VirtualReferencePartitionIndexStoragePart rootPart) {
				assertNull(root, "A flush must emit exactly one root part.");
				root = rootPart;
			}
		}
		assertNotNull(root, "This flush must emit a root part: " + parts);
		assertTrue(root.isPaged(), "The index must be paged.");
		return root;
	}

	/**
	 * Counts the leaf pages among the emitted parts.
	 *
	 * @param parts the emitted storage parts
	 * @return the number of leaf pages
	 */
	private static long countLeafPages(@Nonnull List<StoragePart> parts) {
		return parts.stream().filter(VirtualReferencePartitionIndexLeafPagePart.class::isInstance).count();
	}

	/**
	 * Counts the leaf-page removals among the emitted parts.
	 *
	 * @param parts the emitted storage parts
	 * @return the number of removals
	 */
	private static long countRemovals(@Nonnull List<StoragePart> parts) {
		return parts.stream().filter(VirtualReferencePartitionIndexLeafPageRemoval.class::isInstance).count();
	}

}
