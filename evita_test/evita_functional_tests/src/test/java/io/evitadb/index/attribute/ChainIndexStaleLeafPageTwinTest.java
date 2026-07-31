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

import io.evitadb.dataType.ChainableType;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexLeafPagePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extends the senesi stale-leaf-page-twin reproduction (`documentation/adr/2026-07-18-paged-index-corruption-and-flush-failure-boundary/`) to the
 * {@link ChainIndex} paged restore path. Unlike the key-ordered paged indexes, a chain-index element page is positional
 * (an `UnorderedLookupTree` leaf), so there is no ordering invariant to violate — the twin manifests instead as
 * DUPLICATE record ids across pages: `UnorderedLookupTree.assembleFromLeafPages` copies pages verbatim, so a twin's
 * duplicated record ids silently overwrite the value index while the physical array keeps both copies (wrong chain
 * lengths, later "Position N not found!").
 *
 * The reload no longer attempts any healing: a duplicate record id across chain leaf pages is unconditionally fatal.
 * `ChainIndex.fromPersistedPages` calls `assertNoDuplicateChainRecords`, which fails fast with a
 * {@link GenericEvitaInternalError} naming the offending record id and both leaf-page sequences. Even a shape that a
 * previous revision would have "healed" — a page whose record ids are a strict prefix of its immediate successor's,
 * regardless of any head-mark divergence over the shared prefix — must now throw.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("Stale leaf-page twin fails fast on the ChainIndex reload path")
class ChainIndexStaleLeafPageTwinTest {

	private static final AttributeIndexKey ORDER_KEY = new AttributeIndexKey(null, "orderInChain", null);

	/**
	 * Builds a read-path chain leaf page.
	 *
	 * @param pageSequence  the page sequence
	 * @param recordIds     the leaf's record ids in tree order
	 * @param headPositions the positions (into `recordIds`) that are chain heads, ascending
	 * @return the leaf page
	 */
	@Nonnull
	private static ChainIndexLeafPagePart page(int pageSequence, @Nonnull int[] recordIds, @Nonnull int[] headPositions) {
		final long[] headWords = new long[(recordIds.length + 63) >>> 6];
		final int[] headPredecessors = new int[headPositions.length];
		for (int k = 0; k < headPositions.length; k++) {
			final int position = headPositions[k];
			headWords[position >>> 6] |= 1L << (position & 63);
			// every head in these fixtures is a true chain head (its predecessor is the HEAD_PK sentinel)
			headPredecessors[k] = ChainableType.HEAD_PK;
		}
		return new ChainIndexLeafPagePart(0, pageSequence, recordIds, headWords, headPredecessors, (long) (pageSequence + 1));
	}

	/**
	 * Loads a {@link ChainIndex} from the passed persisted pages the way the catalog-open path does (see
	 * `AttributeIndexLoader.fetchChain`): the global index (no reference key), high-water = last page sequence.
	 *
	 * @param pages the persisted leaf pages in ascending logical order
	 * @return the rebuilt index
	 */
	@Nonnull
	private static ChainIndex loadFromPersistedPages(@Nonnull ChainIndexLeafPagePart... pages) {
		return ChainIndex.fromPersistedPages(null, ORDER_KEY, List.of(pages), pages.length - 1);
	}

	@Nested
	@DisplayName("Any duplicate-record twin fails fast")
	class FailFastTest {

		@Test
		@DisplayName("should fail fast on a stale strict-prefix record-id twin")
		void shouldThrowOnStrictPrefixTwin() {
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(
					// a healthy predecessor chain 100 -> 101
					page(0, new int[]{100, 101}, new int[]{0}),
					// the frozen STALE twin of the next chain leaf (page sequence 1): 200 -> 201 -> 202
					page(1, new int[]{200, 201, 202}, new int[]{0}),
					// the leaf that superseded it (page sequence 2): same 3 records plus 203, 204 the chain gained afterwards
					page(2, new int[]{200, 201, 202, 203, 204}, new int[]{0})
				),
				"A duplicate-record chain twin must fail fast on reload — a strict-prefix twin is no longer healed."
			);
			assertTrue(
				ex.getMessage().contains("appears in more than one leaf page"),
				"The failure must be the duplicate-record corruption diagnostic, got: " + ex.getMessage()
			);
		}

		@Test
		@DisplayName("should fail fast on a duplicate even when head marks diverge over the shared prefix")
		void shouldThrowOnHeadMarkDivergentDuplicate() {
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(
					page(0, new int[]{100, 101}, new int[]{0}),
					// the twin marks 201 ALSO as a head — a head-state divergence over the shared prefix
					page(1, new int[]{200, 201, 202}, new int[]{0, 1}),
					// the superseder marks only 200 as a head; the divergence must NOT prevent the throw
					page(2, new int[]{200, 201, 202, 203, 204}, new int[]{0})
				),
				"A duplicate-record chain twin must fail fast on reload even when the head marks diverge over the shared prefix."
			);
			assertTrue(
				ex.getMessage().contains("appears in more than one leaf page"),
				"The failure must be the duplicate-record corruption diagnostic, got: " + ex.getMessage()
			);
		}
	}

	@Nested
	@DisplayName("Unhealable overlap fails fast")
	class HardFailureTest {

		@Test
		@DisplayName("should throw on a partial-overlap duplicate that is not a strict record-id prefix")
		void shouldRefusePartialOverlapDuplicate() {
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(
					page(0, new int[]{100, 101}, new int[]{0}),
					// shares 200, 201 with its successor but position 2 diverges (202 vs 999) => not a strict prefix
					page(1, new int[]{200, 201, 202}, new int[]{0}),
					page(2, new int[]{200, 201, 999, 203, 204}, new int[]{0})
				),
				"A partial-overlap duplicate is an unknown corruption shape and must fail fast."
			);
			assertTrue(
				ex.getMessage().contains("appears in more than one leaf page"),
				"The failure must be the duplicate-record corruption diagnostic, got: " + ex.getMessage()
			);
		}

		@Test
		@DisplayName("should throw on a non-adjacent duplicate record id")
		void shouldRefuseNonAdjacentDuplicate() {
			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> loadFromPersistedPages(
					page(0, new int[]{100, 101}, new int[]{0}),
					page(1, new int[]{200, 201}, new int[]{0}),
					// record 100 reappears far from its first page and is not part of any strict-prefix twin
					page(2, new int[]{300, 100}, new int[]{0})
				),
				"A non-adjacent duplicate record id is an unknown corruption shape and must fail fast."
			);
			assertTrue(
				ex.getMessage().contains("appears in more than one leaf page"),
				"The failure must be the duplicate-record corruption diagnostic, got: " + ex.getMessage()
			);
		}
	}
}
