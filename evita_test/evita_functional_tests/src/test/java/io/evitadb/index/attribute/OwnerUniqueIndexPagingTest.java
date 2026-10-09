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

import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.UniqueIndexStoragePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the granular (PAGED) leaf-page emission and the boundary-stable reload of {@link OwnerUniqueIndex} at the
 * index level (without the Kryo / OffsetIndex layer): a standalone unique index with enough URL-slug values to split its
 * front-coded value tree across multiple leaves emits one leaf page per leaf plus a PAGED root, and
 * {@link OwnerUniqueIndex#fromPersistedPages} reassembles an equivalent index that, on a no-mutation flush, rewrites
 * nothing and, on a single-value mutation, rewrites only the changed leaf — the whole point of the page layout. A small
 * index stays inline (SINGLE).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Owner unique index PAGED leaf-page emission + boundary-stable reload")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(STORAGE)
class OwnerUniqueIndexPagingTest {
	private static final String ENTITY_TYPE = "product";
	private static final AttributeIndexKey ATTRIBUTE_KEY = new AttributeIndexKey(null, "url", null);
	private static final int ENTITY_INDEX_PK = 1;
	/** The value tree's leaf block size is 256; this many values guarantees a multi-leaf (PAGED) tree. */
	private static final int VALUE_COUNT = 600;

	/**
	 * Produces a distinct, prefix-heavy URL slug derived from the record id (so the round-trip can be asserted exactly
	 * and the values exercise the front-coded leaf column).
	 *
	 * @param recordId the owning record id (also encoded into the slug)
	 * @return the URL slug value
	 */
	@Nonnull
	private static String slug(int recordId) {
		return String.format("/catalog/category/product-%06d", recordId);
	}

	/**
	 * Builds a standalone unique index mapping {@link #VALUE_COUNT} distinct URL slugs to record ids `1..VALUE_COUNT`.
	 *
	 * @return the populated owner unique index
	 */
	@Nonnull
	private static OwnerUniqueIndex buildLargeIndex() {
		final OwnerUniqueIndex index = new OwnerUniqueIndex(ENTITY_TYPE, ATTRIBUTE_KEY, String.class, 0);
		for (int i = 1; i <= VALUE_COUNT; i++) {
			index.registerUniqueKey(slug(i), i);
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
	private static List<StoragePart> flush(@Nonnull OwnerUniqueIndex index) {
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
	 * Reassembles an owner unique index from the parts a PAGED flush emitted (the single PAGED root plus one leaf page
	 * per leaf), mirroring what `AttributeIndexLoader.fetchUnique` does on a cold load. Leaf pages are grouped by page
	 * sequence (a single stream per index, so the page sequence is unique).
	 *
	 * @param parts the emitted storage parts
	 * @return the reassembled owner unique index
	 */
	@Nonnull
	private static OwnerUniqueIndex reassemble(@Nonnull List<StoragePart> parts) {
		return reassemble(parts, String.class);
	}

	/**
	 * Reassembles an owner unique index of the given attribute type from the parts a PAGED flush emitted, mirroring what
	 * `AttributeIndexLoader.fetchUnique` does on a cold load. The attribute type drives the value comparator and the
	 * leaf-column choice, so it must be supplied for non-String unique attributes (BigDecimal, array-typed, …).
	 *
	 * @param parts         the emitted storage parts
	 * @param attributeType the declared attribute type the index was built with
	 * @return the reassembled owner unique index
	 */
	@Nonnull
	private static OwnerUniqueIndex reassemble(
		@Nonnull List<StoragePart> parts,
		@Nonnull Class<? extends Serializable> attributeType
	) {
		return reassemble(parts, attributeType, 0);
	}

	/**
	 * Reassembles an owner unique index like {@link #reassemble(List, Class)}, keyed at the passed scale - the
	 * `indexedDecimalPlaces` the loader reads from the attribute schema.
	 *
	 * @param parts                the emitted storage parts
	 * @param attributeType        the declared attribute type the index was built with
	 * @param indexedDecimalPlaces the scale `BigDecimal` values are keyed at
	 * @return the reassembled owner unique index
	 */
	@Nonnull
	private static OwnerUniqueIndex reassemble(
		@Nonnull List<StoragePart> parts,
		@Nonnull Class<? extends Serializable> attributeType,
		int indexedDecimalPlaces
	) {
		UniqueIndexStoragePart root = null;
		final Map<Integer, UniqueIndexLeafPagePart> leafByPageSequence = new HashMap<>();
		for (final StoragePart part : parts) {
			if (part instanceof UniqueIndexStoragePart rootPart) {
				root = rootPart;
			} else if (part instanceof UniqueIndexLeafPagePart leafPart) {
				leafByPageSequence.put(leafPart.getPageSequence(), leafPart);
			}
		}
		assertNotNull(root, "a PAGED flush must emit exactly one root part");
		assertTrue(root.isPaged(), "the emitted root must be PAGED");

		final int[] orderedPageSequences = root.getLeafPageSequences();
		final Serializable[][] perPageValues = new Serializable[orderedPageSequences.length][];
		final int[][] perPageRecordIds = new int[orderedPageSequences.length][];
		for (int i = 0; i < orderedPageSequences.length; i++) {
			final UniqueIndexLeafPagePart leaf = leafByPageSequence.get(orderedPageSequences[i]);
			assertNotNull(leaf, "the root's leaf-page list must reference an emitted leaf page");
			perPageValues[i] = leaf.getValues();
			perPageRecordIds[i] = leaf.getRecordIds();
		}
		return OwnerUniqueIndex.fromPersistedPages(
			ENTITY_TYPE, ATTRIBUTE_KEY, attributeType, indexedDecimalPlaces,
			orderedPageSequences, perPageValues, perPageRecordIds, root.getHighWaterPageSequence()
		);
	}

	@Test
	@DisplayName("a multi-leaf unique index emits one leaf page per leaf plus a PAGED root")
	void shouldEmitOneLeafPagePerLeaf() {
		final OwnerUniqueIndex index = buildLargeIndex();
		assertTrue(index.isPaged(), VALUE_COUNT + " values must span multiple leaves → PAGED");

		final List<StoragePart> parts = flush(index);

		final long rootCount = parts.stream().filter(UniqueIndexStoragePart.class::isInstance).count();
		final List<UniqueIndexLeafPagePart> leafPages = parts.stream()
			.filter(UniqueIndexLeafPagePart.class::isInstance)
			.map(UniqueIndexLeafPagePart.class::cast)
			.toList();
		assertEquals(1, rootCount, "exactly one PAGED root");
		assertTrue(leafPages.size() > 1, "a multi-leaf tree must emit more than one leaf page");

		// the union of every leaf page's values, in ascending key order, must reproduce the whole index exactly
		final int totalLeafValues = leafPages.stream().mapToInt(p -> p.getValues().length).sum();
		assertEquals(VALUE_COUNT, totalLeafValues, "every value lands in exactly one leaf page");
	}

	@Test
	@DisplayName("fromPersistedPages reassembles an index identical to the original")
	void shouldReassembleIdenticalIndex() {
		final OwnerUniqueIndex original = buildLargeIndex();
		final OwnerUniqueIndex reloaded = reassemble(flush(original));

		assertTrue(reloaded.isPaged(), "the reassembled index must still be PAGED");
		assertEquals(original.size(), reloaded.size(), "size must match");
		assertArrayEquals(
			UniqueIndexTestSupport.ownerRecordIds(original), UniqueIndexTestSupport.ownerRecordIds(reloaded),
			"the reassembled owning records must equal the original"
		);
		// every URL slug must resolve to its exact record through the point-lookup path
		for (int i = 1; i <= VALUE_COUNT; i++) {
			assertEquals(
				Integer.valueOf(i), reloaded.getRecordIdByUniqueValue(slug(i)),
				"point lookup for record " + i
			);
		}
	}

	@Test
	@DisplayName("a no-mutation flush after reload rewrites nothing")
	void shouldRewriteNothingOnNoMutationReflush() {
		final OwnerUniqueIndex reloaded = reassemble(flush(buildLargeIndex()));

		// the reloaded index is clean (every leaf's dirty flag was cleared, the index dirty flag is false), so a flush
		// must emit nothing at all — the boundary-stable reload guarantee
		final List<StoragePart> reflush = flush(reloaded);
		assertTrue(reflush.isEmpty(), "a clean reloaded index must emit no storage parts on flush");
	}

	@Test
	@DisplayName("a single-value mutation after reload rewrites only the changed leaf, not the whole index")
	void shouldRewriteOnlyChangedLeafAfterReload() {
		final OwnerUniqueIndex original = buildLargeIndex();
		final List<StoragePart> firstFlush = flush(original);
		final int totalLeaves = (int) firstFlush.stream()
			.filter(UniqueIndexLeafPagePart.class::isInstance).count();

		final OwnerUniqueIndex reloaded = reassemble(firstFlush);
		// mutate a single value (a brand-new slug+record) — it lands in exactly one leaf
		reloaded.registerUniqueKey(slug(VALUE_COUNT + 1), VALUE_COUNT + 1);

		final List<StoragePart> secondFlush = flush(reloaded);
		final long rootCount = secondFlush.stream().filter(UniqueIndexStoragePart.class::isInstance).count();
		final int rewrittenLeaves = (int) secondFlush.stream()
			.filter(UniqueIndexLeafPagePart.class::isInstance).count();

		// the new value lands in one existing leaf without splitting it, so the live page list is unchanged: the
		// redundant PAGED root re-emit is skipped. Only a leaf split/merge (a page-list change) re-emits it.
		assertEquals(0, rootCount, "a content-only leaf change leaves the page list unchanged, so the root is not re-emitted");
		assertTrue(rewrittenLeaves >= 1, "the mutated leaf (and any split sibling) must be rewritten");
		assertTrue(
			rewrittenLeaves < totalLeaves,
			"only the changed leaf(s) must be rewritten, not all " + totalLeaves + " leaves (was " + rewrittenLeaves + ")"
		);
		assertEquals(
			Integer.valueOf(VALUE_COUNT + 1), reloaded.getRecordIdByUniqueValue(slug(VALUE_COUNT + 1)),
			"the newly added value resolves"
		);
	}

	@Test
	@DisplayName("a small unique index stays SINGLE (inline) and is not paged")
	void shouldStaySingleForSmallIndex() {
		final OwnerUniqueIndex index = new OwnerUniqueIndex(ENTITY_TYPE, ATTRIBUTE_KEY, String.class, 0);
		for (int i = 1; i <= 10; i++) {
			index.registerUniqueKey(slug(i), i);
		}
		assertFalse(index.isPaged(), "10 values fit a single leaf → SINGLE (inline)");

		final List<StoragePart> parts = flush(index);
		assertEquals(1, parts.size(), "a SINGLE index emits exactly the one inline root part");
		final StoragePart only = parts.get(0);
		final UniqueIndexStoragePart root = assertInstanceOf(UniqueIndexStoragePart.class, only, "the single part is the root");
		assertFalse(root.isPaged(), "the root is the inline SINGLE shape");
		assertNotNull(root.getValues(), "a SINGLE root carries the value column inline");
		assertEquals(10, root.getValues().length, "a SINGLE root carries every value inline");
	}

	/*
		ADDITIONAL HELPERS
	 */

	/** Distinct BigDecimal value count used to span more than one leaf in the paged-reload scenario. */
	private static final int DECIMAL_VALUE_COUNT = 300;
	/** The indexed decimal places the BigDecimal scenarios key their values at. */
	private static final int DECIMAL_PLACES = 2;
	/** Record count for the array-typed scenario; multiplied by {@link #ARRAY_ELEMENTS_PER_RECORD} exceeds one leaf. */
	private static final int ARRAY_RECORD_COUNT = 130;
	/** Distinct array elements per record — every element is its own unique key inside the index. */
	private static final int ARRAY_ELEMENTS_PER_RECORD = 5;

	/**
	 * Produces a {@link BigDecimal} value for the given record (`"1.235"`, `"2.235"`, …), one digit finer than
	 * {@link #DECIMAL_PLACES}, so every value is keyed at the indexed scale (`1.24`, `2.24`, …) and stays distinct.
	 *
	 * @param recordId the owning record id (also the integral part of the value)
	 * @return the BigDecimal unique value
	 */
	@Nonnull
	private static BigDecimal decimalKey(int recordId) {
		return new BigDecimal(recordId + ".235");
	}

	/**
	 * Produces a distinct array element value for the `elementIndex`-th element of `recordId`, unique across every
	 * (record, element) pair so each element is its own unique key.
	 *
	 * @param recordId     the owning record id
	 * @param elementIndex the element position within the record's array
	 * @return the element value
	 */
	@Nonnull
	private static String arrayElement(int recordId, int elementIndex) {
		return String.format("/tag/%04d-%02d", recordId, elementIndex);
	}

	/**
	 * Builds a standalone BigDecimal unique index of {@link #DECIMAL_VALUE_COUNT} values keyed at
	 * {@link #DECIMAL_PLACES}. The total comfortably exceeds one leaf, forcing the PAGED shape.
	 *
	 * @return the populated BigDecimal owner unique index
	 */
	@Nonnull
	private static OwnerUniqueIndex buildLargeDecimalIndex() {
		final OwnerUniqueIndex index = new OwnerUniqueIndex(ENTITY_TYPE, ATTRIBUTE_KEY, BigDecimal.class, DECIMAL_PLACES);
		for (int i = 1; i <= DECIMAL_VALUE_COUNT; i++) {
			index.registerUniqueKey(decimalKey(i), i);
		}
		return index;
	}

	/**
	 * Builds a standalone array-typed (`String[]`) unique index where each of {@link #ARRAY_RECORD_COUNT} records owns
	 * {@link #ARRAY_ELEMENTS_PER_RECORD} distinct element values. The element total exceeds one leaf, so the value tree
	 * goes PAGED while the owning-record count stays at the (smaller) record count.
	 *
	 * @return the populated array-typed owner unique index
	 */
	@Nonnull
	private static OwnerUniqueIndex buildLargeArrayIndex() {
		final OwnerUniqueIndex index = new OwnerUniqueIndex(ENTITY_TYPE, ATTRIBUTE_KEY, String[].class, 0);
		for (int record = 1; record <= ARRAY_RECORD_COUNT; record++) {
			final String[] elements = new String[ARRAY_ELEMENTS_PER_RECORD];
			for (int k = 0; k < ARRAY_ELEMENTS_PER_RECORD; k++) {
				elements[k] = arrayElement(record, k);
			}
			index.registerUniqueKey(elements, record);
		}
		return index;
	}

	@Nested
	@DisplayName("BigDecimal uniqueness at the indexed decimal places")
	class BigDecimalUniquenessTest {

		@Test
		@DisplayName("values equal at the indexed decimal places are one unique key, whatever their scale")
		@Tag(INDEXING)
		@Tag(ATTRIBUTE)
		void shouldTreatValuesEqualAtIndexedScaleAsOneKey() {
			final OwnerUniqueIndex index = new OwnerUniqueIndex(ENTITY_TYPE, ATTRIBUTE_KEY, BigDecimal.class, DECIMAL_PLACES);

			index.registerUniqueKey(new BigDecimal("1.0"), 1);
			index.registerUniqueKey(new BigDecimal("1.235"), 2);

			assertEquals(Integer.valueOf(1), index.getRecordIdByUniqueValue(new BigDecimal("1.00")), "`1.00` is `1.0`");
			assertEquals(Integer.valueOf(2), index.getRecordIdByUniqueValue(new BigDecimal("1.236")), "`1.236` is `1.24`");
			assertEquals(2, index.getDistinctValueCount(), "two values at the indexed scale are two keys");
			// claiming a value equal at the indexed scale for a different record must violate uniqueness
			assertThrows(
				UniqueValueViolationException.class,
				() -> index.registerUniqueKey(new BigDecimal("1.000"), 3),
				"`1.000` is the value record 1 owns"
			);
			assertThrows(
				UniqueValueViolationException.class,
				() -> index.registerUniqueKey(new BigDecimal("1.2449"), 3),
				"`1.2449` is the value record 2 owns"
			);
		}

		@Test
		@DisplayName("BigDecimal keys survive a paged flush and reload at the indexed decimal places")
		@Tag(INDEXING)
		@Tag(ATTRIBUTE)
		@Tag(STORAGE)
		void shouldPreserveBigDecimalKeysAcrossPagedReload() {
			final OwnerUniqueIndex original = buildLargeDecimalIndex();
			assertTrue(original.isPaged(), DECIMAL_VALUE_COUNT + " values must span multiple leaves → PAGED");

			final List<StoragePart> parts = flush(original);
			// the leaf pages carry the declared type at the indexed scale, never the scaled int the tree keys by
			parts.stream()
				.filter(UniqueIndexLeafPagePart.class::isInstance)
				.flatMap(part -> Arrays.stream(((UniqueIndexLeafPagePart) part).getValues()))
				.forEach(value -> assertEquals(DECIMAL_PLACES, ((BigDecimal) value).scale(), "persisted " + value));
			final OwnerUniqueIndex reloaded = reassemble(parts, BigDecimal.class, DECIMAL_PLACES);

			assertTrue(reloaded.isPaged(), "the reassembled BigDecimal index must still be PAGED");
			assertEquals(original.size(), reloaded.size(), "size must match");
			assertArrayEquals(
				UniqueIndexTestSupport.ownerRecordIds(original),
				UniqueIndexTestSupport.ownerRecordIds(reloaded),
				"the owning records must equal the original"
			);
			for (int i = 1; i <= DECIMAL_VALUE_COUNT; i++) {
				assertEquals(Integer.valueOf(i), reloaded.getRecordIdByUniqueValue(decimalKey(i)), "point lookup for record " + i);
			}
			// any spelling equal at the indexed scale still resolves to the owner after the round-trip
			assertEquals(Integer.valueOf(1), reloaded.getRecordIdByUniqueValue(new BigDecimal("1.24")), "`1.24` resolves to record 1");
			assertEquals(Integer.valueOf(1), reloaded.getRecordIdByUniqueValue(new BigDecimal("1.2449")), "`1.2449` resolves to record 1");
		}

	}

	@Nested
	@DisplayName("Array-typed unique attribute through the paged path")
	class ArrayTypedUniqueAttributeTest {

		@Test
		@DisplayName("every array element is its own leaf value while size counts records")
		@Tag(INDEXING)
		@Tag(ATTRIBUTE)
		@Tag(STORAGE)
		void shouldPageArrayTypedUniqueAttribute() {
			final OwnerUniqueIndex index = buildLargeArrayIndex();
			final int elementCount = ARRAY_RECORD_COUNT * ARRAY_ELEMENTS_PER_RECORD;
			assertTrue(index.isPaged(), elementCount + " element values must span multiple leaves → PAGED");
			assertEquals(ARRAY_RECORD_COUNT, index.size(), "size() counts records, not elements");

			final List<StoragePart> parts = flush(index);
			final long rootCount = parts.stream().filter(UniqueIndexStoragePart.class::isInstance).count();
			final List<UniqueIndexLeafPagePart> leafPages = parts.stream()
				.filter(UniqueIndexLeafPagePart.class::isInstance)
				.map(UniqueIndexLeafPagePart.class::cast)
				.toList();
			assertEquals(1, rootCount, "exactly one PAGED root");
			assertTrue(leafPages.size() > 1, "the element values must span more than one leaf page");
			final int totalLeafValues = leafPages.stream().mapToInt(p -> p.getValues().length).sum();
			assertEquals(elementCount, totalLeafValues, "every array element lands in exactly one leaf page");

			final OwnerUniqueIndex reloaded = reassemble(parts, String[].class);
			assertArrayEquals(
				UniqueIndexTestSupport.ownerRecordIds(index),
				UniqueIndexTestSupport.ownerRecordIds(reloaded),
				"the reassembled owning records must equal the original"
			);
			// every element of every record must resolve to its owning record after the round-trip
			for (int record = 1; record <= ARRAY_RECORD_COUNT; record++) {
				for (int k = 0; k < ARRAY_ELEMENTS_PER_RECORD; k++) {
					assertEquals(
						Integer.valueOf(record), reloaded.getRecordIdByUniqueValue(arrayElement(record, k)),
						"element " + k + " of record " + record
					);
				}
			}
		}

	}

	@Nested
	@DisplayName("Array whole-value unregister keeps the owning records consistent")
	class ArrayWholeValueUnregisterInvariantTest {

		/**
		 * An array-typed unique attribute maps several element keys to one owning record, yet the real mutation path only
		 * ever (un)registers the WHOLE array value atomically (`executeAttributeRemoval` → `removeUniqueAttribute` →
		 * `unregisterUniqueKey(whole array)`). This locks in that removing one record's whole array makes only that
		 * record stop owning a value and leaves every other record's element keys live.
		 */
		@Test
		@DisplayName("unregistering a record's whole array drops only that record's values")
		@Tag(INDEXING)
		@Tag(ATTRIBUTE)
		void shouldDropOnlyTheRecordWhoseWholeArrayIsUnregistered() {
			final OwnerUniqueIndex index = new OwnerUniqueIndex(ENTITY_TYPE, ATTRIBUTE_KEY, String[].class, 0);
			index.registerUniqueKey(new String[] {"a", "b"}, 5);
			index.registerUniqueKey(new String[] {"c", "d"}, 6);
			assertArrayEquals(
				new int[] {5, 6},
				UniqueIndexTestSupport.ownerRecordIds(index),
				"both records present after registration"
			);

			// the real mutation path removes the WHOLE array value atomically — every element owned by record 5 leaves
			// the tree within this single call, so record 5, and only record 5, stops owning a value
			index.unregisterUniqueKey(new String[] {"a", "b"}, 5);

			assertArrayEquals(
				new int[] {6},
				UniqueIndexTestSupport.ownerRecordIds(index),
				"only record 5 is dropped, record 6 survives"
			);
			assertNull(index.getRecordIdByUniqueValue("a"), "element a of record 5 is gone");
			assertNull(index.getRecordIdByUniqueValue("b"), "element b of record 5 is gone");
			assertEquals(Integer.valueOf(6), index.getRecordIdByUniqueValue("c"), "record 6's element c stays live");
			assertEquals(Integer.valueOf(6), index.getRecordIdByUniqueValue("d"), "record 6's element d stays live");
		}

		/**
		 * Replacing an array value (`["a","b"]` → `["a","c"]`) goes through `executeAttributeUpsert`, which removes the
		 * WHOLE old array and inserts the WHOLE new array. The shared element `a` is removed and immediately re-added,
		 * so record 5 owns nothing for a moment and the subsequent whole-array insert makes it an owner again —
		 * record 5 must remain present.
		 */
		@Test
		@DisplayName("replacing an array via whole-value remove then add keeps the record an owner")
		@Tag(INDEXING)
		@Tag(ATTRIBUTE)
		void shouldKeepRecordWhenArrayValueReplacedThroughWholeValueRemoveThenAdd() {
			final OwnerUniqueIndex index = new OwnerUniqueIndex(ENTITY_TYPE, ATTRIBUTE_KEY, String[].class, 0);
			index.registerUniqueKey(new String[] {"a", "b"}, 5);

			// executeAttributeUpsert replaces an array value by removing the whole old array and inserting the whole new
			// array — the shared element a is removed and immediately re-added
			index.unregisterUniqueKey(new String[] {"a", "b"}, 5);
			index.registerUniqueKey(new String[] {"a", "c"}, 5);

			assertArrayEquals(
				new int[] {5},
				UniqueIndexTestSupport.ownerRecordIds(index),
				"record 5 survives the whole-array replace"
			);
			assertEquals(Integer.valueOf(5), index.getRecordIdByUniqueValue("a"), "retained element a still resolves");
			assertEquals(Integer.valueOf(5), index.getRecordIdByUniqueValue("c"), "added element c resolves");
			assertNull(index.getRecordIdByUniqueValue("b"), "removed element b is gone");
		}

		/**
		 * Removing the only record's whole array empties the value tree and leaves no owning record — the index
		 * becomes empty, which is what lets {@code AttributeIndex.removeUniqueAttribute} drop the now-empty owner index.
		 */
		@Test
		@DisplayName("removing the sole owner's whole array empties the index")
		@Tag(INDEXING)
		@Tag(ATTRIBUTE)
		void shouldEmptyIndexWhenSoleOwnersWholeArrayUnregistered() {
			final OwnerUniqueIndex index = new OwnerUniqueIndex(ENTITY_TYPE, ATTRIBUTE_KEY, String[].class, 0);
			index.registerUniqueKey(new String[] {"a", "b"}, 5);

			index.unregisterUniqueKey(new String[] {"a", "b"}, 5);

			assertTrue(index.isEmpty(), "the index is empty once its sole record's whole array is removed");
			assertEquals(0, UniqueIndexTestSupport.ownerRecordIds(index).length, "no record owns a value any more");
			assertEquals(0, index.size(), "the record count reads no owner either");
			assertNull(index.getRecordIdByUniqueValue("a"), "element a is gone");
			assertNull(index.getRecordIdByUniqueValue("b"), "element b is gone");
		}

	}

	@Nested
	@DisplayName("Paged to single collapse on shrink")
	class PagedToSingleCollapseTest {

		/** Values kept after the shrink — few enough to fit a single leaf so the tree collapses to SINGLE. */
		private static final int KEPT_VALUES = 40;

		@Test
		@DisplayName("shrinking below one leaf collapses to single and frees every prior leaf page")
		@Tag(INDEXING)
		@Tag(ATTRIBUTE)
		@Tag(STORAGE)
		void shouldEmitLeafRemovalsAndCollapseToSingleOnShrink() {
			final List<StoragePart> firstFlush = flush(buildLargeIndex());
			final int priorLeafPages = (int) firstFlush.stream()
				.filter(UniqueIndexLeafPagePart.class::isInstance).count();
			final OwnerUniqueIndex reloaded = reassemble(firstFlush);

			// shrink the index until it fits a single leaf again → it must collapse out of the PAGED shape
			for (int i = KEPT_VALUES + 1; i <= VALUE_COUNT; i++) {
				reloaded.unregisterUniqueKey(slug(i), i);
			}
			assertFalse(reloaded.isPaged(), "an index that fits one leaf must collapse to SINGLE");

			final List<StoragePart> collapseFlush = flush(reloaded);
			final List<UniqueIndexStoragePart> roots = collapseFlush.stream()
				.filter(UniqueIndexStoragePart.class::isInstance)
				.map(UniqueIndexStoragePart.class::cast)
				.toList();
			final long emittedLeafPages = collapseFlush.stream()
				.filter(UniqueIndexLeafPagePart.class::isInstance).count();
			// UniqueIndexLeafPageRemoval does not expose its page sequence, so the removed set is asserted by count
			// against the prior live-page set rather than by sequence identity
			final long removals = collapseFlush.stream()
				.filter(UniqueIndexLeafPageRemoval.class::isInstance).count();

			assertEquals(1, roots.size(), "the collapse emits exactly one root");
			final UniqueIndexStoragePart root = roots.get(0);
			assertFalse(root.isPaged(), "the collapsed root is the inline SINGLE shape");
			assertNotNull(root.getValues(), "a SINGLE root carries the value column inline");
			assertEquals(KEPT_VALUES, root.getValues().length, "the SINGLE root carries every surviving value");
			assertEquals(0, emittedLeafPages, "a SINGLE flush emits no leaf pages");
			assertEquals(priorLeafPages, removals, "every previously-live leaf page must be removed on collapse");

			// regrowing past one leaf must re-emit a leaf page for every leaf — the prior stream was forgotten on collapse
			for (int i = KEPT_VALUES + 1; i <= VALUE_COUNT; i++) {
				reloaded.registerUniqueKey(slug(i), i);
			}
			assertTrue(reloaded.isPaged(), "regrowing past one leaf must return to PAGED");

			final List<StoragePart> regrowFlush = flush(reloaded);
			final UniqueIndexStoragePart regrowRoot = regrowFlush.stream()
				.filter(UniqueIndexStoragePart.class::isInstance)
				.map(UniqueIndexStoragePart.class::cast)
				.findFirst()
				.orElseThrow();
			final int regrownLeafPages = (int) regrowFlush.stream()
				.filter(UniqueIndexLeafPagePart.class::isInstance).count();
			assertTrue(regrowRoot.isPaged(), "the regrown root is PAGED again");
			assertEquals(
				regrowRoot.getLeafPageSequences().length, regrownLeafPages,
				"a regrow from a forgotten stream re-emits a leaf page for every leaf"
			);
		}

	}

	@Nested
	@DisplayName("Transactional commit and rollback of a paged index")
	class PagedTransactionTest {

		@Test
		@DisplayName("committing a mutation yields a distinct paged copy that keeps incremental flushing")
		@Tag(INDEXING)
		@Tag(ATTRIBUTE)
		@Tag(STORAGE)
		@Tag(TRANSACTION)
		void shouldMergePagedIndexAndPreserveIncrementalFlushOnCommit() {
			final List<StoragePart> firstFlush = flush(buildLargeIndex());
			final int totalLeaves = (int) firstFlush.stream()
				.filter(UniqueIndexLeafPagePart.class::isInstance).count();
			final OwnerUniqueIndex baseline = reassemble(firstFlush);
			final int newRecord = VALUE_COUNT + 1;
			final AtomicReference<UniqueIndex> committedRef = new AtomicReference<>();

			assertStateAfterCommit(
				baseline,
				original -> original.registerUniqueKey(slug(newRecord), newRecord),
				(original, committed) -> {
					assertNotSame(original, committed, "a dirty commit must yield a distinct committed instance");
					final OwnerUniqueIndex committedOwner = assertInstanceOf(OwnerUniqueIndex.class, committed);
					assertTrue(committedOwner.isPaged(), "the committed copy must still be PAGED");
					assertEquals(VALUE_COUNT + 1, committedOwner.size(), "the committed copy contains the new record");
					final int[] expectedCommittedRecordIds = new int[VALUE_COUNT + 1];
					for (int i = 0; i < expectedCommittedRecordIds.length; i++) {
						expectedCommittedRecordIds[i] = i + 1;
					}
					assertArrayEquals(
						expectedCommittedRecordIds, UniqueIndexTestSupport.ownerRecordIds(committedOwner),
						"the committed index is owned by every original record plus the new one"
					);
					for (int i = 1; i <= VALUE_COUNT; i++) {
						assertEquals(Integer.valueOf(i), committedOwner.getRecordIdByUniqueValue(slug(i)), "point lookup for record " + i);
					}
					assertEquals(
						Integer.valueOf(newRecord), committedOwner.getRecordIdByUniqueValue(slug(newRecord)),
						"the transactionally added value resolves on the committed copy"
					);
					// the original baseline must stay untouched by the committed transaction
					assertEquals(VALUE_COUNT, original.size(), "the original baseline is unchanged by the committed transaction");
					assertNull(
						original.getRecordIdByUniqueValue(slug(newRecord)),
						"the original baseline must not see the transactionally added value"
					);
					committedRef.set(committed);
				}
			);

			// the freshly committed copy is a clean baseline (in production the flush runs on the transactional view
			// before the merge), so it is flushed only AFTER a fresh mutation here — the point is that the page-stream
			// bookkeeping survived the commit, so this flush stays incremental instead of re-paginating from scratch
			final OwnerUniqueIndex committed = (OwnerUniqueIndex) committedRef.get();
			committed.registerUniqueKey(slug(newRecord + 1), newRecord + 1);

			final List<StoragePart> postCommitFlush = flush(committed);
			final int rewrittenLeaves = (int) postCommitFlush.stream()
				.filter(UniqueIndexLeafPagePart.class::isInstance).count();
			// the new value lands in one existing leaf without splitting it, so the live page list is unchanged and the
			// redundant PAGED root re-emit is skipped. That the flush stayed incremental (one leaf, no root, no
			// re-pagination to SINGLE) is exactly what proves the page-stream bookkeeping survived the commit.
			assertTrue(
				postCommitFlush.stream().noneMatch(UniqueIndexStoragePart.class::isInstance),
				"a content-only change leaves the page list unchanged, so the PAGED root re-emit is skipped"
			);
			assertTrue(committed.isPaged(), "the committed copy stays PAGED after the incremental flush");
			assertTrue(rewrittenLeaves >= 1, "the mutated leaf must be rewritten");
			assertTrue(
				rewrittenLeaves < totalLeaves,
				"only the changed leaf(s) must be rewritten, not all " + totalLeaves + " (was " + rewrittenLeaves + ")"
			);
		}

		@Test
		@DisplayName("rolling back a mutation leaves the paged index exactly as before")
		@Tag(INDEXING)
		@Tag(ATTRIBUTE)
		@Tag(TRANSACTION)
		void shouldRestorePagedIndexUnchangedOnRollback() {
			final OwnerUniqueIndex baseline = reassemble(flush(buildLargeIndex()));
			final int sizeBefore = baseline.size();
			final int[] recordIdsBefore = UniqueIndexTestSupport.ownerRecordIds(baseline);

			assertStateAfterRollback(
				baseline,
				original -> {
					original.registerUniqueKey(slug(VALUE_COUNT + 1), VALUE_COUNT + 1);
					original.unregisterUniqueKey(slug(1), 1);
				},
				(original, committed) -> {
					assertNull(committed, "a rolled-back transaction yields no committed copy");
					assertTrue(original.isPaged(), "the rolled-back index is still PAGED");
					assertEquals(sizeBefore, original.size(), "size is unchanged after rollback");
					assertArrayEquals(
						recordIdsBefore, UniqueIndexTestSupport.ownerRecordIds(original),
						"the owning records are unchanged after rollback"
					);
					for (int i = 1; i <= VALUE_COUNT; i++) {
						assertEquals(Integer.valueOf(i), original.getRecordIdByUniqueValue(slug(i)), "point lookup for record " + i);
					}
					assertNull(
						original.getRecordIdByUniqueValue(slug(VALUE_COUNT + 1)),
						"the transactionally added value must not survive the rollback"
					);
				}
			);
		}

	}

}
