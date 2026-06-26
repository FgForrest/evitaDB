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

package io.evitadb.index.price;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexStoragePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the granular (PAGED) leaf-page emission and the boundary-stable reload of
 * {@link PriceListAndCurrencyPriceSuperIndex} at the index level (without the Kryo / OffsetIndex layer): a super price
 * index with enough records to split its element-keyed price-record tree across multiple leaves emits one leaf page per
 * leaf plus a PAGED root, and {@link PriceListAndCurrencyPriceSuperIndex#fromPersistedPages} reassembles a byte-stable
 * equivalent that, on a no-mutation flush, rewrites nothing and, on a single-record mutation, rewrites only the changed
 * leaf — the whole point of the page layout.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Super price index PAGED leaf-page emission + boundary-stable reload")
@Tag(INDEXING)
@Tag(PRICE)
@Tag(STORAGE)
class PriceListAndCurrencyPriceSuperIndexPagingTest {
	private static final PriceIndexKey PRICE_INDEX_KEY = new PriceIndexKey(
		"basic", Currency.getInstance("EUR"), PriceInnerRecordHandling.NONE
	);
	private static final int ENTITY_INDEX_PK = 1;
	/** Default element-tree block size is 64; this many records guarantees a multi-leaf (PAGED) tree. */
	private static final int RECORD_COUNT = 300;

	/**
	 * Produces a distinct price record keyed on its internal price id, with content derivable from the key (so the
	 * round-trip can be asserted exactly).
	 *
	 * @param internalPriceId the internal price id (the tree key)
	 * @return the price record
	 */
	@Nonnull
	private static PriceRecord rec(int internalPriceId) {
		return new PriceRecord(
			internalPriceId, internalPriceId + 1000, internalPriceId, internalPriceId * 100 + 21, internalPriceId * 100
		);
	}

	/**
	 * Builds a super price index holding {@link #RECORD_COUNT} distinct records (internal price ids `1..RECORD_COUNT`).
	 *
	 * @return the populated super price index
	 */
	@Nonnull
	private static PriceListAndCurrencyPriceSuperIndex buildLargeSuperIndex() {
		final PriceListAndCurrencyPriceSuperIndex index = new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
		for (int i = 1; i <= RECORD_COUNT; i++) {
			index.addPrice(rec(i), null);
		}
		return index;
	}

	/**
	 * Flushes the index's modified storage parts into a captured bundle.
	 *
	 * @param index the index to flush
	 * @return the emitted parts
	 */
	@Nonnull
	private static List<StoragePart> flush(@Nonnull PriceListAndCurrencyPriceSuperIndex index) {
		final TrappedChanges trappedChanges = new TrappedChanges();
		index.appendStorageParts(ENTITY_INDEX_PK, trappedChanges);
		final List<StoragePart> parts = new ArrayList<>();
		final Iterator<StoragePart> iterator = trappedChanges.getTrappedChangesIterator();
		while (iterator.hasNext()) {
			parts.add(iterator.next());
		}
		return parts;
	}

	/**
	 * Reassembles a super price index from the parts a PAGED flush emitted (the single PAGED root plus one leaf page per
	 * leaf), mirroring what `PriceSuperIndexLoader` does on a cold load.
	 *
	 * @param parts the emitted storage parts
	 * @return the reassembled super price index
	 */
	@Nonnull
	private static PriceListAndCurrencyPriceSuperIndex reassemble(@Nonnull List<StoragePart> parts) {
		PriceListAndCurrencySuperIndexStoragePart root = null;
		final Map<Integer, PriceRecordContract[]> leafRecordsByPageSequence = new HashMap<>();
		for (final StoragePart part : parts) {
			if (part instanceof PriceListAndCurrencySuperIndexStoragePart rootPart) {
				root = rootPart;
			} else if (part instanceof PriceListAndCurrencySuperIndexLeafPagePart leafPart) {
				leafRecordsByPageSequence.put(leafPart.getPageSequence(), leafPart.getPriceRecords());
			}
		}
		assertNotNull(root, "a PAGED flush must emit exactly one root part");
		assertTrue(root.isPaged(), "the emitted root must be PAGED");

		final int[] orderedPageSequences = root.getLeafPageSequences();
		final PriceRecordContract[][] perPagePriceRecords = new PriceRecordContract[orderedPageSequences.length][];
		for (int i = 0; i < orderedPageSequences.length; i++) {
			final PriceRecordContract[] pageRecords = leafRecordsByPageSequence.get(orderedPageSequences[i]);
			assertNotNull(pageRecords, "the root's leaf-page list must reference an emitted leaf page");
			perPagePriceRecords[i] = pageRecords;
		}
		return PriceListAndCurrencyPriceSuperIndex.fromPersistedPages(
			PRICE_INDEX_KEY, root.getValidityIndex(), orderedPageSequences, perPagePriceRecords,
			root.getHighWaterPageSequence()
		);
	}

	@Test
	@DisplayName("a multi-leaf super price index emits one leaf page per leaf plus a PAGED root")
	void shouldEmitOneLeafPagePerLeaf() {
		final PriceListAndCurrencyPriceSuperIndex index = buildLargeSuperIndex();
		assertTrue(index.isPaged(), "300 records must span multiple leaves → PAGED");

		final List<StoragePart> parts = flush(index);

		final long rootCount = parts.stream().filter(PriceListAndCurrencySuperIndexStoragePart.class::isInstance).count();
		final List<PriceListAndCurrencySuperIndexLeafPagePart> leafPages = parts.stream()
			.filter(PriceListAndCurrencySuperIndexLeafPagePart.class::isInstance)
			.map(PriceListAndCurrencySuperIndexLeafPagePart.class::cast)
			.toList();
		assertEquals(1, rootCount, "exactly one PAGED root");
		assertTrue(leafPages.size() > 1, "a multi-leaf tree must emit more than one leaf page");

		// the union of every leaf page's records, in ascending key order, must reproduce the whole index exactly
		final int totalLeafRecords = leafPages.stream().mapToInt(p -> p.getPriceRecords().length).sum();
		assertEquals(RECORD_COUNT, totalLeafRecords, "every record lands in exactly one leaf page");
	}

	@Test
	@DisplayName("fromPersistedPages reassembles an index identical to the original")
	void shouldReassembleIdenticalIndex() {
		final PriceListAndCurrencyPriceSuperIndex original = buildLargeSuperIndex();
		final PriceListAndCurrencyPriceSuperIndex reloaded = reassemble(flush(original));

		assertTrue(reloaded.isPaged(), "the reassembled index must still be PAGED");
		assertArrayEquals(
			original.getPriceRecords(), reloaded.getPriceRecords(),
			"the reassembled records must equal the original in ascending key order"
		);
		// every internal price id must resolve to its exact record through the point-lookup path
		for (int i = 1; i <= RECORD_COUNT; i++) {
			assertEquals(rec(i), reloaded.getPriceRecord(i), "point lookup for internal price id " + i);
		}
	}

	@Test
	@DisplayName("a no-mutation flush after reload rewrites nothing")
	void shouldRewriteNothingOnNoMutationReflush() {
		final PriceListAndCurrencyPriceSuperIndex reloaded = reassemble(flush(buildLargeSuperIndex()));

		// the reloaded index is clean (every leaf's dirty flag was cleared, the index dirty flag is false), so a flush
		// must emit nothing at all — the boundary-stable reload guarantee
		final List<StoragePart> reflush = flush(reloaded);
		assertTrue(reflush.isEmpty(), "a clean reloaded index must emit no storage parts on flush");
	}

	@Test
	@DisplayName("a single-record mutation after reload rewrites only the changed leaf, not the whole index")
	void shouldRewriteOnlyChangedLeafAfterReload() {
		final PriceListAndCurrencyPriceSuperIndex original = buildLargeSuperIndex();
		final List<StoragePart> firstFlush = flush(original);
		final int totalLeaves = (int) firstFlush.stream()
			.filter(PriceListAndCurrencySuperIndexLeafPagePart.class::isInstance).count();

		final PriceListAndCurrencyPriceSuperIndex reloaded = reassemble(firstFlush);
		// mutate a single record (a brand-new internal price id) — it lands in exactly one leaf
		reloaded.addPrice(rec(RECORD_COUNT + 1), null);

		final List<StoragePart> secondFlush = flush(reloaded);
		final long rootCount = secondFlush.stream()
			.filter(PriceListAndCurrencySuperIndexStoragePart.class::isInstance).count();
		final int rewrittenLeaves = (int) secondFlush.stream()
			.filter(PriceListAndCurrencySuperIndexLeafPagePart.class::isInstance).count();

		assertEquals(1, rootCount, "a PAGED flush always re-emits the root");
		assertTrue(rewrittenLeaves >= 1, "the mutated leaf (and any split sibling) must be rewritten");
		assertTrue(
			rewrittenLeaves < totalLeaves,
			"only the changed leaf(s) must be rewritten, not all " + totalLeaves + " leaves (was " + rewrittenLeaves + ")"
		);
		// the new record must resolve, proving the mutation took effect
		assertEquals(rec(RECORD_COUNT + 1), reloaded.getPriceRecord(RECORD_COUNT + 1), "the newly added record resolves");
	}

	@Test
	@DisplayName("a small super price index stays SINGLE (inline) and is not paged")
	void shouldStaySingleForSmallIndex() {
		final PriceListAndCurrencyPriceSuperIndex index = new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
		for (int i = 1; i <= 10; i++) {
			index.addPrice(rec(i), null);
		}
		assertFalse(index.isPaged(), "10 records fit a single leaf → SINGLE (inline)");

		final List<StoragePart> parts = flush(index);
		assertEquals(1, parts.size(), "a SINGLE index emits exactly the one inline root part");
		assertInstanceOf(PriceListAndCurrencySuperIndexStoragePart.class, parts.get(0), "the single part is the root");
		final PriceListAndCurrencySuperIndexStoragePart root = (PriceListAndCurrencySuperIndexStoragePart) parts.get(0);
		assertFalse(root.isPaged(), "the root is the inline SINGLE shape");
		assertEquals(10, root.getPriceRecords().length, "a SINGLE root carries every record inline");
	}

}
