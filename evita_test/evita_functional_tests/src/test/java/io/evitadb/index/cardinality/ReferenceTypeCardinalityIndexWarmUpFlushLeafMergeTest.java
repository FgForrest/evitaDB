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

package io.evitadb.index.cardinality;

import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ReferenceTypeCardinalityIndexStoragePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A warm-up (bulk) flush never reaches the transactional commit-merge — {@code publishStaged} runs only there — so two
 * consecutive {@link ReferenceTypeCardinalityIndex#appendStorageParts} calls on the same live index, with a leaf merge
 * of its composed-key `(key, count)` bucket tree happening in between, must still correctly detect and free the page
 * the merge dropped even though nothing was ever published between the two flushes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(REFERENCE)
@DisplayName("A leaf merge between two warm-up flushes with no intermediate publish (ReferenceTypeCardinalityIndex)")
class ReferenceTypeCardinalityIndexWarmUpFlushLeafMergeTest {

	private static final int ENTITY_INDEX_PK = 1;
	private static final String REFERENCE_NAME = "testReference";
	/**
	 * The single whole-index primary key used for every {@code addRecord}/{@code removeRecord} call. Reusing the same
	 * index PK for every tuple means only the per-tuple composed key {@code -pack(indexPk, referencedEntityPrimaryKey)}
	 * is a fresh tree key per call — the per-whole-index counter {@code +pack(indexPk, 0)} is created once and merely
	 * incremented/decremented in place afterward — which keeps the tree's ascending key layout fully predictable.
	 */
	private static final int INDEX_PK = 777;
	/** Cardinality bucket-tree leaf capacity ({@code VALUE_BLOCK_SIZE}); a full leaf splits into two 128-halves. */
	private static final int VALUE_BLOCK_SIZE = 256;
	/**
	 * Three leaves of exactly half capacity (128) — the layout an ascending append of this many distinct referenced
	 * primary keys produces (packing {@link #INDEX_PK} with `referencedEntityPrimaryKey` and negating yields a
	 * monotonically ascending key stream when `referencedEntityPrimaryKey` descends from {@code TUPLE_COUNT} to 1).
	 */
	private static final int TUPLE_COUNT = 3 * (VALUE_BLOCK_SIZE / 2) - 1;

	@Test
	@DisplayName("the leaf page a merge drops between two warm-up flushes must still be reported as freed")
	void shouldReportFreedLeafPageAcrossConsecutiveWarmUpFlushesWithoutAnIntermediatePublish() {
		final ReferenceTypeCardinalityIndex index = new ReferenceTypeCardinalityIndex();
		// descending referencedEntityPrimaryKey -> ascending -pack(INDEX_PK, referencedEntityPrimaryKey) key stream;
		// the first call also creates the single +pack(INDEX_PK, 0) counter key, which — being positive — is always
		// the global maximum and therefore always lands in (and stays in) the rightmost leaf
		for (int referencedEntityPrimaryKey = TUPLE_COUNT; referencedEntityPrimaryKey >= 1; referencedEntityPrimaryKey--) {
			index.addRecord(INDEX_PK, referencedEntityPrimaryKey);
		}
		assertTrue(
			index.isPaged(),
			"383 tuples (384 tree keys) must span three leaves (leaf 0 [256..383], 1 [128..255], 2 [1..127]+sentinel)."
		);

		// first warm-up flush: stages page sequences 0, 1, 2 for the three fresh leaves but — mirroring a real
		// warm-up, which never reaches the transactional commit-merge — is never published
		final List<StoragePart> firstFlush = flush(index);
		assertEquals(
			3, countLeafPages(firstFlush), "The first flush must write every one of the three fresh leaves."
		);
		assertEquals(
			3, singleRoot(firstFlush).getLeafPageSequences().length,
			"The first flush's root must list all three leaf pages."
		);

		// force leaf 0 [256..383] to merge with leaf 1 [128..255] while leaf 2 [1..127]+sentinel stays structurally
		// untouched: bring leaf 1 down to exactly the minimum (127) first so it can no longer donate, then push
		// leaf 0 one below the minimum (126) so it underflows and merges into leaf 1 (mergeWithRight)
		index.removeRecord(INDEX_PK, 128); // leaf 1: 128 -> 127 (== minValueBlockSize, can no longer donate)
		index.removeRecord(INDEX_PK, 383); // leaf 0: 128 -> 127 (still at the minimum, no underflow yet)
		index.removeRecord(INDEX_PK, 382); // leaf 0: 127 -> 126 (< minValueBlockSize) -> merges with leaf 1

		assertTrue(index.isPaged(), "The index must stay PAGED (two leaves) after the merge, not collapse to SINGLE.");

		// second warm-up flush: without the fix, the freed-page diff is computed against the still-EMPTY published
		// live set (nothing was ever published after the first flush), so the page the merge dropped is silently
		// never reported and therefore never removed from storage
		final List<StoragePart> secondFlush = flush(index);
		assertEquals(
			2, singleRoot(secondFlush).getLeafPageSequences().length,
			"The merge must have dropped the tree to two live leaves."
		);
		assertEquals(
			1, countRemovals(secondFlush),
			"The leaf page the merge dropped must be reported as freed, or it leaks in storage forever."
		);
	}

	/**
	 * Flushes the index's modified storage parts into a captured list, mirroring what a real flush drains from
	 * {@link TrappedChanges}.
	 *
	 * @param index the index to flush
	 * @return the emitted parts, in emission order
	 */
	@Nonnull
	private static List<StoragePart> flush(@Nonnull ReferenceTypeCardinalityIndex index) {
		final TrappedChanges trappedChanges = new TrappedChanges();
		index.appendStorageParts(ENTITY_INDEX_PK, REFERENCE_NAME, trappedChanges);
		final List<StoragePart> parts = new ArrayList<>();
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			parts.add(iterator.next());
		}
		return parts;
	}

	/**
	 * Extracts the single {@link ReferenceTypeCardinalityIndexStoragePart} root emitted by a flush, failing if none or
	 * more than one is present.
	 *
	 * @param parts the emitted storage parts
	 * @return the sole root part
	 */
	@Nonnull
	private static ReferenceTypeCardinalityIndexStoragePart singleRoot(@Nonnull List<StoragePart> parts) {
		ReferenceTypeCardinalityIndexStoragePart root = null;
		for (final StoragePart part : parts) {
			if (part instanceof ReferenceTypeCardinalityIndexStoragePart rootPart) {
				assertNull(root, "A flush must emit exactly one root part.");
				root = rootPart;
			}
		}
		assertNotNull(root, "A dirty PAGED flush must emit a root part.");
		return root;
	}

	/**
	 * Counts the leaf pages ({@link ReferenceTypeCardinalityIndexLeafPagePart}) among the emitted parts.
	 *
	 * @param parts the emitted storage parts
	 * @return the number of leaf pages
	 */
	private static long countLeafPages(@Nonnull List<StoragePart> parts) {
		return parts.stream().filter(ReferenceTypeCardinalityIndexLeafPagePart.class::isInstance).count();
	}

	/**
	 * Counts the leaf-page removals ({@link ReferenceTypeCardinalityIndexLeafPageRemoval}) among the emitted parts.
	 *
	 * @param parts the emitted storage parts
	 * @return the number of freed-page removal instructions
	 */
	private static long countRemovals(@Nonnull List<StoragePart> parts) {
		return parts.stream().filter(ReferenceTypeCardinalityIndexLeafPageRemoval.class::isInstance).count();
	}

}
