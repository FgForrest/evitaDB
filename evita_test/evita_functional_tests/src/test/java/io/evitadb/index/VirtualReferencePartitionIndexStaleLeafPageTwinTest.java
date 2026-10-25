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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart.ChainPredecessorSlot;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart.SortValueSlot;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that reloading a {@link VirtualReferencePartitionIndex} from leaf pages whose page list references both a
 * superseded leaf page and the page that replaced it (a stale "twin" covering an overlapping key run) fails fast
 * instead of assembling a UNIQUE tree with duplicated keys - whatever the overlap shape, a strict-prefix twin or a
 * same-key twin whose payload diverges.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(REFERENCE)
@DisplayName("Stale leaf-page twin fails fast on the VirtualReferencePartitionIndex reload path")
class VirtualReferencePartitionIndexStaleLeafPageTwinTest {
	private static final int TWIN_PREFIX_SIZE = 128;
	private static final int GROWN_PAGE_SIZE = 190;

	/**
	 * Returns the `i`-th key - an owner-slot entry of a positive partition primary key.
	 *
	 * @param i the key ordinal
	 * @return the key
	 */
	private static long key(int i) {
		return NumberUtils.pack(VirtualReferencePartitionSlot.PARTITION_OWNER.getId(), 100_000 + i);
	}

	/**
	 * Returns the payload stored at the `i`-th key (distinct, so a diverged payload is observable).
	 *
	 * @param i the key ordinal
	 * @return the payload
	 */
	private static long payload(int i) {
		return NumberUtils.pack(-i, i);
	}

	/**
	 * Builds the key column of a page for the ordinals `[from, to)`.
	 */
	@Nonnull
	private static long[] keyPage(int from, int to) {
		final long[] keys = new long[to - from];
		for (int i = from; i < to; i++) {
			keys[i - from] = key(i);
		}
		return keys;
	}

	/**
	 * Builds the payload column of a page for the ordinals `[from, to)`.
	 */
	@Nonnull
	private static long[] payloadPage(int from, int to) {
		final long[] payloads = new long[to - from];
		for (int i = from; i < to; i++) {
			payloads[i - from] = payload(i);
		}
		return payloads;
	}

	/**
	 * Loads an index from the passed persisted pages the way the loader does: page sequences `0..n-1` and an empty
	 * tuple dictionary.
	 *
	 * @param keyPages     the per-page key columns
	 * @param payloadPages the per-page payload columns, positionally aligned with `keyPages`
	 */
	private static void loadFromPersistedPages(@Nonnull long[][] keyPages, @Nonnull long[][] payloadPages) {
		final int[] pageSequences = new int[keyPages.length];
		for (int i = 0; i < keyPages.length; i++) {
			pageSequences[i] = i;
		}
		VirtualReferencePartitionIndex.fromPersistedPages(
			"reference `media`",
			pageSequences,
			keyPages,
			payloadPages,
			keyPages.length - 1,
			new int[0],
			new Serializable[0][],
			new SortValueSlot[0],
			new ChainPredecessorSlot[0],
			slot -> SortIndex.createLegacyNormalizerFor(slot.comparatorBase(), slot.indexedDecimalPlaces())
		);
	}

	@Nested
	@DisplayName("Strict-prefix twin fails fast")
	class FailFastTest {

		@Test
		@DisplayName("should throw on a stale strict-prefix twin page instead of silently healing it")
		void shouldThrowOnStrictPrefixTwin() {
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(
					new long[][]{
						keyPage(0, TWIN_PREFIX_SIZE),
						keyPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + TWIN_PREFIX_SIZE),
						keyPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + GROWN_PAGE_SIZE)
					},
					new long[][]{
						payloadPage(0, TWIN_PREFIX_SIZE),
						payloadPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + TWIN_PREFIX_SIZE),
						payloadPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + GROWN_PAGE_SIZE)
					}
				),
				"A stale leaf-page twin must fail fast on reload rather than being silently healed."
			);
			assertTrue(
				ex.getMessage().contains("overlaps its successor leaf-page sequence"),
				"The failure must be the cross-leaf-order corruption diagnostic, got: " + ex.getMessage()
			);
		}
	}

	@Nested
	@DisplayName("Unhealable overlap fails fast")
	class HardFailureTest {

		@Test
		@DisplayName("should throw on a same-key twin whose payload diverges from its superseder")
		void shouldRefuseDivergedPayloadTwin() {
			final long[] divergedPayloads = payloadPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + GROWN_PAGE_SIZE);
			divergedPayloads[0] = payload(TWIN_PREFIX_SIZE) + 999L;
			assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(
					new long[][]{
						keyPage(0, TWIN_PREFIX_SIZE),
						keyPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + TWIN_PREFIX_SIZE),
						keyPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + GROWN_PAGE_SIZE)
					},
					new long[][]{
						payloadPage(0, TWIN_PREFIX_SIZE),
						payloadPage(TWIN_PREFIX_SIZE, TWIN_PREFIX_SIZE + TWIN_PREFIX_SIZE),
						divergedPayloads
					}
				),
				"A same-key twin with a diverged payload is an unknown corruption shape and must fail fast."
			);
		}
	}
}
