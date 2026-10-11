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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.dataType.ComparableCurrency;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart.SortValueSlot;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;

import static io.evitadb.index.VirtualReferencePartitionIndexTest.flush;
import static io.evitadb.index.VirtualReferencePartitionIndexTest.rootOf;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static io.evitadb.utils.AssertionUtils.assertWarmUpSavepointRollbackRestores;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the sort value slots of {@link VirtualReferencePartitionIndex} in memory: declaring and retiring sort keys,
 * the per-slot value dictionary and its reference counting, the normalization of every value form a sort key can hold
 * (localized and decomposed strings, scaled and unscaled `BigDecimal`, `Locale`, `Currency`, compounds with missing
 * elements and with `Locale` and `Currency` elements), the sort index kept in step with the slot, and the
 * transactional (STM) and warm-up savepoint behaviour of all of it.
 *
 * Every sort index is checked against a model - the raw values per partition, normalized and ordered by the very
 * normalizer and comparator {@link SortIndex} derives - so a sort index that merely agrees with a broken twin of
 * itself does not pass.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition index sort value slots")
@Tag(INDEXING)
@Tag(REFERENCE)
class VirtualReferencePartitionIndexSortValueTest {
	static final String REFERENCE_NAME = "media";
	static final Locale CZECH = Locale.forLanguageTag("cs");
	static final AttributeIndexKey ORDER = new AttributeIndexKey(REFERENCE_NAME, "mediaOrder", null);
	static final ComparatorSource[] INTEGER_ASC = {
		new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
	};
	/**
	 * Decomposed and composed spellings of the same word: both normalize to one decomposed `String`.
	 */
	static final String CAJ_COMPOSED = "čaj";
	static final String CAJ_DECOMPOSED = "čaj";
	/**
	 * Every sort key shape the slots must hold, each with a generator of raw values - several of which normalize to,
	 * or are ordered as, one value.
	 */
	static final List<SortKeyFixture> FIXTURES = List.of(
		new SortKeyFixture(
			ORDER, INTEGER_ASC, 0, false,
			random -> 1 + random.nextInt(77)
		),
		new SortKeyFixture(
			new AttributeIndexKey(REFERENCE_NAME, "title", CZECH),
			new ComparatorSource[]{new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)},
			0, false,
			random -> pick(
				random, CAJ_COMPOSED, CAJ_DECOMPOSED, "chata", "cesta", "hora", "Česko", "Česko", "zebra",
				"Ábel", "Ábel", "abeceda"
			)
		),
		new SortKeyFixture(
			new AttributeIndexKey(REFERENCE_NAME, "weight", null),
			new ComparatorSource[]{
				new ComparatorSource(BigDecimal.class, OrderDirection.DESC, OrderBehaviour.NULLS_LAST)
			},
			2, false,
			random -> pick(
				random, new BigDecimal("1.5"), new BigDecimal("1.50"), new BigDecimal("2"), new BigDecimal("0.25"),
				new BigDecimal("-3.10"), new BigDecimal("-3.1")
			)
		),
		new SortKeyFixture(
			new AttributeIndexKey(REFERENCE_NAME, "language", null),
			new ComparatorSource[]{new ComparatorSource(Locale.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)},
			0, false,
			random -> pick(random, Locale.ENGLISH, Locale.GERMAN, Locale.forLanguageTag("cs-CZ"), Locale.FRENCH)
		),
		new SortKeyFixture(
			new AttributeIndexKey(REFERENCE_NAME, "currency", null),
			new ComparatorSource[]{new ComparatorSource(Currency.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)},
			0, false,
			random -> pick(
				random, Currency.getInstance("CZK"), Currency.getInstance("EUR"), Currency.getInstance("USD")
			)
		),
		new SortKeyFixture(
			new AttributeIndexKey(REFERENCE_NAME, "roleWeight", null),
			new ComparatorSource[]{
				new ComparatorSource(String.class, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
				new ComparatorSource(BigDecimal.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
			},
			0, true,
			random -> new Serializable[]{
				pick(random, "gallery", "video", null),
				pick(random, new BigDecimal("1.0"), new BigDecimal("1.00"), new BigDecimal("2.5"), null),
				pick(random, 1, 2, null)
			}
		),
		new SortKeyFixture(
			new AttributeIndexKey(REFERENCE_NAME, "roleWeight", CZECH),
			new ComparatorSource[]{
				new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				new ComparatorSource(Integer.class, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
			},
			0, true,
			random -> new Serializable[]{
				pick(random, CAJ_COMPOSED, CAJ_DECOMPOSED, "chata", "cesta", null),
				pick(random, 1, 2, 3, null)
			}
		),
		new SortKeyFixture(
			new AttributeIndexKey(REFERENCE_NAME, "languageCurrency", null),
			new ComparatorSource[]{
				new ComparatorSource(Locale.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				new ComparatorSource(Currency.class, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
				new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
			},
			0, true,
			random -> new Serializable[]{
				pick(random, Locale.ENGLISH, Locale.GERMAN, Locale.forLanguageTag("cs-CZ"), null),
				pick(random, Currency.getInstance("CZK"), Currency.getInstance("EUR"), null),
				pick(random, 1, 2, null)
			}
		)
	);

	/**
	 * One sort key shape: what it sorts by and how its raw values are generated.
	 *
	 * @param key            the sort key
	 * @param comparatorBase one descriptor per sorted element
	 * @param decimalPlaces  the decimal places a `BigDecimal` attribute is scaled by
	 * @param compound       whether the key sorts by a compound and its values are element arrays
	 * @param generator      produces a random raw value
	 */
	record SortKeyFixture(
		@Nonnull AttributeIndexKey key,
		@Nonnull ComparatorSource[] comparatorBase,
		int decimalPlaces,
		boolean compound,
		@Nonnull Function<Random, Serializable> generator
	) {

		/**
		 * Declares the key in the index.
		 *
		 * @param index the index
		 */
		void declare(@Nonnull VirtualReferencePartitionIndex index) {
			index.declareSortKey(this.key, this.comparatorBase, this.decimalPlaces);
		}

		/**
		 * Records a raw value of the key for the partition, through the overload matching the key's shape.
		 *
		 * @param index     the index
		 * @param partition the partition primary key
		 * @param value     the raw value
		 */
		void put(@Nonnull VirtualReferencePartitionIndex index, int partition, @Nonnull Serializable value) {
			if (this.compound) {
				index.putSortValue(this.key, partition, (Serializable[]) value);
			} else {
				index.putSortValue(this.key, partition, value);
			}
		}

		@Override
		public String toString() {
			return this.key.toString();
		}
	}

	/**
	 * The partitions a reading of a sort key probes besides those an owner row names: every partition the tests of the
	 * index write sort values under lies in `1..2 000`. The index has no reading that lists the partitions holding a
	 * value (see {@link VirtualReferencePartitionIndexEntries#forEachSortValueBlock}), so a value held only beyond
	 * them would show as an empty block of the snapshot.
	 */
	static final int[] PROBED_PARTITIONS = IntStream.rangeClosed(1, 2_000).toArray();
	/**
	 * The snapshot of a declared sort key no partition holds a value of.
	 */
	static final SortSnapshot NO_VALUES = new SortSnapshot(List.of(), List.of(), List.of());

	/**
	 * The observable ordering of a sort key: its partitions in sort order, its distinct values in sort order and the
	 * size of each value's block.
	 *
	 * @param records    the record ids in sort order
	 * @param values     the distinct values in sort order
	 * @param blockSizes the number of records of each value, aligned with `values`
	 */
	record SortSnapshot(
		@Nonnull List<Integer> records,
		@Nonnull List<Serializable> values,
		@Nonnull List<Integer> blockSizes
	) {
	}

	/**
	 * Picks one of the options.
	 *
	 * @param random  the random source
	 * @param options the options, `null` among them allowed
	 * @param <T>     the option type
	 * @return the picked option
	 */
	@SafeVarargs
	@Nullable
	static <T extends Serializable> T pick(@Nonnull Random random, @Nonnull T... options) {
		return options[random.nextInt(options.length)];
	}

	/**
	 * Reads the observable ordering of a declared sort key through the public reading surface of the index - the values
	 * in the order of the key's sort index and the partitions holding each, among the owner rows and
	 * {@link #PROBED_PARTITIONS} (see {@link VirtualReferencePartitionIndexEntries#forEachSortValueBlock}).
	 *
	 * @param index   the index
	 * @param sortKey the sort key, which must be declared
	 * @return the snapshot
	 */
	@Nonnull
	static SortSnapshot snapshotOf(@Nonnull VirtualReferencePartitionIndex index, @Nonnull AttributeIndexKey sortKey) {
		assertTrue(index.getDeclaredSortKeys().contains(sortKey), "Sort key " + sortKey + " must be declared");
		final List<Integer> records = new ArrayList<>(16);
		final List<Serializable> values = new ArrayList<>(16);
		final List<Integer> blockSizes = new ArrayList<>(16);
		VirtualReferencePartitionIndexEntries.forEachSortValueBlock(
			index, sortKey,
			(value, partitions) -> {
				values.add(value);
				blockSizes.add(partitions.length);
				for (final int partition : partitions) {
					records.add(partition);
				}
			},
			PROBED_PARTITIONS
		);
		return new SortSnapshot(records, values, blockSizes);
	}

	/**
	 * Returns the value a partition holds for a sort key, normalized as the index stores it - the value its value id
	 * names in the dictionary of the key.
	 *
	 * @param index     the index
	 * @param sortKey   the sort key
	 * @param partition the partition primary key
	 * @return the value, or `null` when the key is not declared or the partition holds no value for it
	 */
	@Nullable
	static Serializable storedValueOf(
		@Nonnull VirtualReferencePartitionIndex index,
		@Nonnull AttributeIndexKey sortKey,
		int partition
	) {
		final int valueId = index.getSortValueId(sortKey, partition);
		return valueId == VirtualReferencePartitionIndex.NO_SORT_VALUE_ID ?
			null : index.getNormalizedSortValue(sortKey, valueId);
	}

	/**
	 * Asserts the declared sort key orders exactly the partitions of the model, by their normalized values, with
	 * ascending partitions inside each block of values its comparator orders as one.
	 *
	 * @param fixture the sort key
	 * @param index   the index declaring the key
	 * @param model   the raw value of every partition holding one
	 */
	static void assertMatchesModel(
		@Nonnull SortKeyFixture fixture,
		@Nonnull VirtualReferencePartitionIndex index,
		@Nonnull Map<Integer, Serializable> model
	) {
		final ComparatorSource[] base = fixture.comparatorBase();
		final Locale locale = fixture.key().locale();
		@SuppressWarnings({"unchecked", "rawtypes"}) final Comparator<Serializable> comparator = base.length == 1 ?
			SortIndex.createComparatorFor(locale, base[0]) :
			(Comparator) SortIndex.createCombinedComparatorFor(locale, base);
		final Map<Integer, Serializable> normalized = new TreeMap<>();
		model.forEach((partition, value) -> normalized.put(partition, normalize(fixture, value)));

		final List<Integer> expectedRecords = new ArrayList<>(normalized.keySet());
		// ascending partitions first, then a stable sort by value keeps them ascending inside each block
		expectedRecords.sort(Comparator.comparing(normalized::get, comparator));
		final List<Serializable> expectedValues = new ArrayList<>();
		final List<Integer> expectedBlockSizes = new ArrayList<>();
		for (final Integer partition : expectedRecords) {
			final Serializable value = normalized.get(partition);
			if (expectedValues.isEmpty() || comparator.compare(expectedValues.get(expectedValues.size() - 1), value) != 0) {
				expectedValues.add(value);
				expectedBlockSizes.add(1);
			} else {
				expectedBlockSizes.set(expectedBlockSizes.size() - 1, expectedBlockSizes.get(expectedBlockSizes.size() - 1) + 1);
			}
		}

		final SortSnapshot actual = snapshotOf(index, fixture.key());
		assertEquals(expectedRecords, actual.records(), "Records of " + fixture + " must follow the model");
		assertEquals(expectedBlockSizes, actual.blockSizes(), "Blocks of " + fixture + " must follow the model");
		assertEquals(expectedValues.size(), actual.values().size(), "Distinct values of " + fixture);
		for (int i = 0; i < expectedValues.size(); i++) {
			// which of two values ordered as one represents the block depends on history - compare by order
			assertEquals(
				0, comparator.compare(expectedValues.get(i), actual.values().get(i)),
				"Value " + i + " of " + fixture + ": expected " + expectedValues.get(i) + ", got " + actual.values().get(i)
			);
		}
	}

	/**
	 * Normalizes a raw value the way the sort index of the key does.
	 *
	 * @param fixture the sort key
	 * @param value   the raw value
	 * @return the normalized value
	 */
	@Nonnull
	static Serializable normalize(@Nonnull SortKeyFixture fixture, @Nonnull Serializable value) {
		final ComparatorSource[] base = fixture.comparatorBase();
		if (fixture.compound()) {
			final Serializable[] elements = (Serializable[]) value;
			return new ComparableArray(
				(Serializable[]) SortIndex.createNormalizerFor(base, fixture.decimalPlaces())
					.apply(Arrays.copyOf(elements, elements.length))
			);
		}
		final Optional<UnaryOperator<Serializable>> normalizer =
			SortIndex.createNormalizerFor(base[0], fixture.decimalPlaces());
		return normalizer.map(it -> it.apply(value)).orElse(value);
	}

	/**
	 * Applies random puts and removals of every fixture's values to the index and mirrors them in the models.
	 *
	 * @param index      the index, every fixture declared in it
	 * @param models     the raw value of every partition per sort key, updated in step
	 * @param random     the random source
	 * @param operations how many operations to apply
	 * @param partitions the partition primary keys are drawn from `1..partitions`
	 */
	static void churn(
		@Nonnull VirtualReferencePartitionIndex index,
		@Nonnull Map<AttributeIndexKey, Map<Integer, Serializable>> models,
		@Nonnull Random random,
		int operations,
		int partitions
	) {
		for (int i = 0; i < operations; i++) {
			final SortKeyFixture fixture = FIXTURES.get(random.nextInt(FIXTURES.size()));
			final Map<Integer, Serializable> model = models.computeIfAbsent(fixture.key(), key -> new HashMap<>());
			final int partition = 1 + random.nextInt(partitions);
			if (random.nextInt(4) == 0) {
				assertEquals(
					model.remove(partition) != null, index.removeSortValue(fixture.key(), partition),
					"Removal of partition " + partition + " from " + fixture
				);
			} else {
				final Serializable value = fixture.generator().apply(random);
				fixture.put(index, partition, value);
				model.put(partition, value);
			}
		}
	}

	/**
	 * Copies the models deeply, so a churn that is rolled back can be mirrored without touching the originals.
	 *
	 * @param models the raw value of every partition per sort key
	 * @return the independent copy
	 */
	@Nonnull
	static Map<AttributeIndexKey, Map<Integer, Serializable>> copyOf(
		@Nonnull Map<AttributeIndexKey, Map<Integer, Serializable>> models
	) {
		final Map<AttributeIndexKey, Map<Integer, Serializable>> copy = new HashMap<>();
		models.forEach((key, model) -> copy.put(key, new HashMap<>(model)));
		return copy;
	}

	/**
	 * Asserts every fixture's sort index follows its model.
	 *
	 * @param index  the index
	 * @param models the raw value of every partition per sort key
	 */
	static void assertMatchesModels(
		@Nonnull VirtualReferencePartitionIndex index,
		@Nonnull Map<AttributeIndexKey, Map<Integer, Serializable>> models
	) {
		for (final SortKeyFixture fixture : FIXTURES) {
			assertMatchesModel(fixture, index, models.getOrDefault(fixture.key(), Map.of()));
		}
	}

	/**
	 * Returns the persisted slot of the sort key from the root of an emission.
	 *
	 * @param parts the emission
	 * @param key   the sort key
	 * @return the slot, or `null` when the root does not carry it
	 */
	@Nullable
	static SortValueSlot persistedSlot(@Nonnull List<StoragePart> parts, @Nonnull AttributeIndexKey key) {
		return Arrays.stream(rootOf(parts).getSortValueSlots())
			.filter(slot -> slot.attributeIndexKey().equals(key))
			.findFirst()
			.orElse(null);
	}

	/**
	 * Creates an index with every fixture declared.
	 *
	 * @return the index
	 */
	@Nonnull
	static VirtualReferencePartitionIndex declaredIndex() {
		final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
		FIXTURES.forEach(fixture -> fixture.declare(index));
		return index;
	}

	/**
	 * Closes a savepoint a failing test might have left bound to this thread.
	 */
	@AfterEach
	void closeLeakedSavepoint() {
		final WarmUpSavepoint leaked = WarmUpSavepoint.getIfOpen();
		if (leaked != null) {
			leaked.commit();
		}
	}

	@Nested
	@DisplayName("Declaring and retiring sort keys")
	class Declaration {

		@Test
		@DisplayName("a declared key has an empty sort index, its own slot, and keeps the index from being empty")
		void shouldDeclareKeyWithEmptySortIndex() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			assertFalse(index.getDeclaredSortKeys().contains(ORDER));
			index.declareSortKey(ORDER, INTEGER_ASC, 0);

			assertEquals(NO_VALUES, snapshotOf(index, ORDER));
			assertEquals(0, index.getDistinctSortValueCount(ORDER));
			assertFalse(index.isEmpty(), "A declared sort key is state the index must persist");
			final SortValueSlot slot = persistedSlot(flush(index), ORDER);
			assertNotNull(slot);
			assertEquals(VirtualReferencePartitionSlot.FIRST_DECLARED_SLOT_ID, slot.slotId());
			assertEquals(0, slot.valueIds().length);
		}

		@Test
		@DisplayName("declaring the same key again changes nothing, declaring it differently is refused")
		void shouldIgnoreRepeatedDeclarationAndRefuseConflictingOne() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.declareSortKey(ORDER, INTEGER_ASC, 0);
			index.putSortValue(ORDER, 1, 5);
			final SortSnapshot before = snapshotOf(index, ORDER);
			final int valueId = index.getSortValueId(ORDER, 1);
			index.resetDirty();

			index.declareSortKey(
				ORDER, new ComparatorSource[]{new ComparatorSource(Integer.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)}, 0
			);
			assertEquals(before, snapshotOf(index, ORDER));
			assertEquals(valueId, index.getSortValueId(ORDER, 1), "A repeated declaration must keep the value ids");
			assertTrue(flush(index).isEmpty(), "A repeated declaration must not dirty the index");

			assertThrows(
				GenericEvitaInternalError.class,
				() -> index.declareSortKey(
					ORDER,
					new ComparatorSource[]{new ComparatorSource(Integer.class, OrderDirection.DESC, OrderBehaviour.NULLS_LAST)},
					0
				)
			);
			assertThrows(GenericEvitaInternalError.class, () -> index.declareSortKey(ORDER, INTEGER_ASC, 2));
		}

		@Test
		@DisplayName("every declared key gets a distinct slot above the fixed ones")
		void shouldGiveEachKeyItsOwnDeclaredSlot() {
			final VirtualReferencePartitionIndex index = declaredIndex();
			final List<StoragePart> parts = flush(index);
			final List<Integer> slotIds = FIXTURES.stream()
				.map(fixture -> persistedSlot(parts, fixture.key()))
				.map(slot -> assertInstanceOf(SortValueSlot.class, slot).slotId())
				.toList();
			assertEquals(FIXTURES.size(), slotIds.stream().distinct().count(), "Slot ids must be distinct: " + slotIds);
			assertTrue(slotIds.stream().allMatch(it -> it >= VirtualReferencePartitionSlot.FIRST_DECLARED_SLOT_ID));
		}

		@Test
		@DisplayName("retiring a key removes its entries, its dictionary and its sort index, other keys stay")
		void shouldReclaimEverythingOfARetiredKey() {
			final VirtualReferencePartitionIndex index = declaredIndex();
			final Map<AttributeIndexKey, Map<Integer, Serializable>> models = new HashMap<>();
			churn(index, models, new Random(11), 2_000, 300);
			index.putPartitionOwner(1, 10, 100);
			final SortKeyFixture retired = FIXTURES.get(0);

			assertTrue(index.retireSortKey(retired.key()));
			assertFalse(index.retireSortKey(retired.key()), "A second retirement finds nothing");
			assertFalse(index.getDeclaredSortKeys().contains(retired.key()));
			assertThrows(GenericEvitaInternalError.class, () -> index.putSortValue(retired.key(), 1, 1));
			assertThrows(GenericEvitaInternalError.class, () -> index.removeSortValue(retired.key(), 1));

			final List<StoragePart> parts = flush(index);
			assertNull(persistedSlot(parts, retired.key()), "The root must not carry a retired key");
			final VirtualReferencePartitionIndexStoragePart root = assertInstanceOf(
				VirtualReferencePartitionIndexStoragePart.class,
				parts.stream().filter(VirtualReferencePartitionIndexStoragePart.class::isInstance).findFirst().orElseThrow()
			);
			if (!root.isPaged()) {
				final int retiredSlotId = VirtualReferencePartitionSlot.FIRST_DECLARED_SLOT_ID;
				assertTrue(
					Arrays.stream(root.getKeys()).noneMatch(key -> NumberUtils.unpackHigh(key) == retiredSlotId),
					"No entry of the retired slot may remain"
				);
			}
			for (final SortKeyFixture fixture : FIXTURES.subList(1, FIXTURES.size())) {
				assertMatchesModel(fixture, index, models.getOrDefault(fixture.key(), Map.of()));
			}

			// a key declared afresh starts empty
			retired.declare(index);
			assertEquals(NO_VALUES, snapshotOf(index, retired.key()));
		}

		@Test
		@DisplayName("an index holding only declared keys becomes empty again once they are retired")
		void shouldBecomeEmptyWhenTheLastKeyIsRetired() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.declareSortKey(ORDER, INTEGER_ASC, 0);
			index.putSortValue(ORDER, 4, 2);
			index.retireSortKey(ORDER);
			assertTrue(index.isEmpty());
		}
	}

	@Nested
	@DisplayName("Sort values")
	class SortValues {

		@Test
		@DisplayName("the sort index of every key shape follows random puts and removals")
		void shouldKeepEverySortIndexInStepWithTheSlots() {
			final VirtualReferencePartitionIndex index = declaredIndex();
			final Map<AttributeIndexKey, Map<Integer, Serializable>> models = new HashMap<>();
			final Random random = new Random(42);
			for (int round = 0; round < 5; round++) {
				churn(index, models, random, 1_000, 200);
				assertMatchesModels(index, models);
			}
		}

		@Test
		@DisplayName("composed and decomposed spellings of one word are one value")
		void shouldTreatCanonicallyEquivalentStringsAsOneValue() {
			final SortKeyFixture title = FIXTURES.get(1);
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			title.declare(index);
			title.put(index, 1, CAJ_COMPOSED);
			title.put(index, 2, CAJ_DECOMPOSED);

			final SortValueSlot slot = persistedSlot(flush(index), title.key());
			assertNotNull(slot);
			assertEquals(1, slot.values().length, "Both spellings must share one dictionary value");
			assertEquals(List.of(2), snapshotOf(index, title.key()).blockSizes());
		}

		@Test
		@DisplayName("a BigDecimal in a compound is one value whatever its scale, the first scale represents it")
		void shouldTreatBigDecimalsOfOneValueAsOneValueInACompound() {
			final SortKeyFixture compound = FIXTURES.get(5);
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			compound.declare(index);
			compound.put(index, 1, new Serializable[]{"video", new BigDecimal("1.0"), 1});
			compound.put(index, 2, new Serializable[]{"video", new BigDecimal("1.00"), 1});

			final SortValueSlot slot = persistedSlot(flush(index), compound.key());
			assertNotNull(slot);
			assertArrayEquals(
				new Serializable[]{new ComparableArray(new Serializable[]{"video", new BigDecimal("1.0"), 1})},
				slot.values()
			);
			assertEquals(List.of(2), snapshotOf(index, compound.key()).blockSizes());

			// switching a partition between the two scales is no change at all
			index.resetDirty();
			compound.put(index, 1, new Serializable[]{"video", new BigDecimal("1.000"), 1});
			assertTrue(flush(index).isEmpty(), "A value ordered as the current one must not dirty the index");
		}

		@Test
		@DisplayName("a BigDecimal attribute is scaled by its decimal places")
		void shouldScaleBigDecimalAttribute() {
			final SortKeyFixture weight = FIXTURES.get(2);
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			weight.declare(index);
			weight.put(index, 1, new BigDecimal("1.5"));
			weight.put(index, 2, new BigDecimal("1.50"));

			final SortValueSlot slot = persistedSlot(flush(index), weight.key());
			assertNotNull(slot);
			assertArrayEquals(new Serializable[]{150}, slot.values());
		}

		@Test
		@DisplayName("a Locale and a Currency are kept in their comparable wrappers and removed again")
		void shouldNormalizeLocaleAndCurrency() {
			final SortKeyFixture language = FIXTURES.get(3);
			final SortKeyFixture currency = FIXTURES.get(4);
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			language.declare(index);
			currency.declare(index);
			language.put(index, 1, Locale.GERMAN);
			language.put(index, 2, Locale.ENGLISH);
			currency.put(index, 1, Currency.getInstance("EUR"));

			final List<StoragePart> parts = flush(index);
			assertEquals(
				List.of("io.evitadb.dataType.ComparableLocale"),
				Arrays.stream(persistedSlot(parts, language.key()).values()).map(it -> it.getClass().getName()).distinct().toList()
			);
			assertEquals(
				"io.evitadb.dataType.ComparableCurrency",
				persistedSlot(parts, currency.key()).values()[0].getClass().getName()
			);
			// a locale orders by its language tag: `de` before `en`
			assertEquals(List.of(1, 2), snapshotOf(index, language.key()).records());

			// removal hands the sort index a value it can normalize again
			assertTrue(index.removeSortValue(language.key(), 1));
			assertTrue(index.removeSortValue(currency.key(), 1));
			language.put(index, 2, Locale.FRENCH);
			assertEquals(List.of(2), snapshotOf(index, language.key()).records());
			assertEquals(NO_VALUES, snapshotOf(index, currency.key()));
		}

		@Test
		@DisplayName("a compound with Locale and Currency elements is recorded, ordered and removed again")
		void shouldRecordAndRemoveACompoundWithLocaleAndCurrencyElements() {
			final SortKeyFixture languageCurrency = FIXTURES.get(7);
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			languageCurrency.declare(index);
			final Map<Integer, Serializable> model = new TreeMap<>();
			model.put(1, new Serializable[]{Locale.GERMAN, Currency.getInstance("CZK"), 1});
			model.put(2, new Serializable[]{Locale.ENGLISH, Currency.getInstance("EUR"), 2});
			model.put(3, new Serializable[]{Locale.GERMAN, null, null});
			model.forEach((partition, value) -> languageCurrency.put(index, partition, value));

			final SortValueSlot slot = persistedSlot(flush(index), languageCurrency.key());
			assertNotNull(slot);
			assertEquals(3, slot.values().length);
			for (final Serializable value : slot.values()) {
				final Serializable[] elements = assertInstanceOf(ComparableArray.class, value).array();
				assertInstanceOf(ComparableLocale.class, elements[0]);
				if (elements[1] != null) {
					assertInstanceOf(ComparableCurrency.class, elements[1]);
				}
			}
			// `de` before `en`, and the missing currency first among the `de` values
			assertEquals(List.of(3, 1, 2), snapshotOf(index, languageCurrency.key()).records());
			assertMatchesModel(languageCurrency, index, model);

			// removal hands the sort index values it can normalize again
			for (final int partition : List.of(1, 2, 3)) {
				assertTrue(index.removeSortValue(languageCurrency.key(), partition));
			}
			assertEquals(NO_VALUES, snapshotOf(index, languageCurrency.key()));
			assertEquals(0, persistedSlot(flush(index), languageCurrency.key()).values().length);
		}

		@Test
		@DisplayName("a value of the wrong type or shape is refused before anything changes")
		void shouldRefuseWrongValueBeforeWriting() {
			final VirtualReferencePartitionIndex index = declaredIndex();
			FIXTURES.get(0).put(index, 1, 5);
			index.resetDirty();
			final SortSnapshot before = snapshotOf(index, ORDER);

			assertThrows(GenericEvitaInternalError.class, () -> index.putSortValue(ORDER, 1, "five"));
			assertThrows(GenericEvitaInternalError.class, () -> index.putSortValue(ORDER, 1, new Serializable[]{5}));
			final AttributeIndexKey compoundKey = FIXTURES.get(5).key();
			assertThrows(GenericEvitaInternalError.class, () -> index.putSortValue(compoundKey, 1, "video"));
			assertThrows(
				GenericEvitaInternalError.class, () -> index.putSortValue(compoundKey, 1, new Serializable[]{"video"})
			);
			// the compound normalizer leaves the misplaced Integer as it is, the validating compound refuses it
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.putSortValue(compoundKey, 1, new Serializable[]{"video", 7, 1})
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> index.putSortValue(new AttributeIndexKey(REFERENCE_NAME, "undeclared", null), 1, 1)
			);

			assertEquals(before, snapshotOf(index, ORDER));
			assertEquals(NO_VALUES, snapshotOf(index, compoundKey));
			assertTrue(flush(index).isEmpty(), "A refused value must leave the index clean");
		}

		@Test
		@DisplayName("the caller's compound array is left untouched")
		void shouldNotModifyTheCallersCompoundArray() {
			final SortKeyFixture compound = FIXTURES.get(6);
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			compound.declare(index);
			final Serializable[] value = {CAJ_COMPOSED, 2};
			compound.put(index, 1, value);
			assertArrayEquals(new Serializable[]{CAJ_COMPOSED, 2}, value);
		}
	}

	@Nested
	@DisplayName("Value dictionary")
	class ValueDictionary {

		@Test
		@DisplayName("a value leaves the dictionary together with its last user")
		void shouldDropValueWithItsLastUser() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.declareSortKey(ORDER, INTEGER_ASC, 0);
			index.putSortValue(ORDER, 1, 7);
			index.putSortValue(ORDER, 2, 7);
			index.putSortValue(ORDER, 3, 9);
			assertArrayEquals(new Serializable[]{7, 9}, persistedSlot(flush(index), ORDER).values());

			assertTrue(index.removeSortValue(ORDER, 1));
			assertArrayEquals(
				new Serializable[]{7, 9}, persistedSlot(flush(index), ORDER).values(), "Partition 2 still holds 7"
			);
			assertTrue(index.removeSortValue(ORDER, 2));
			assertArrayEquals(new Serializable[]{9}, persistedSlot(flush(index), ORDER).values());
			assertFalse(index.removeSortValue(ORDER, 2), "A second removal finds nothing");
		}

		@Test
		@DisplayName("switching a partition to another value releases the previous one")
		void shouldReleaseThePreviousValueWhenSwitching() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.declareSortKey(ORDER, INTEGER_ASC, 0);
			index.putSortValue(ORDER, 1, 7);
			index.putSortValue(ORDER, 1, 9);
			assertArrayEquals(new Serializable[]{9}, persistedSlot(flush(index), ORDER).values());
			assertEquals(List.of(1), snapshotOf(index, ORDER).records());
		}

		@Test
		@DisplayName("re-recording the same value neither dirties the index nor adds a user")
		void shouldIgnoreTheSameValueRecordedAgain() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.declareSortKey(ORDER, INTEGER_ASC, 0);
			index.putSortValue(ORDER, 1, 7);
			index.resetDirty();

			index.putSortValue(ORDER, 1, 7);
			assertTrue(flush(index).isEmpty(), "An unchanged value must not make the index emit anything");

			// one removal must be enough to drop the value - a second recorded user would keep it alive
			index.removeSortValue(ORDER, 1);
			assertEquals(0, persistedSlot(flush(index), ORDER).values().length);
		}

		@Test
		@DisplayName("value ids are handed out in ascending order and never reused while the key is declared")
		void shouldNeverReuseValueIdsOfADeclaredKey() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.declareSortKey(ORDER, INTEGER_ASC, 0);
			index.putSortValue(ORDER, 1, 7);
			index.putSortValue(ORDER, 2, 9);
			final int[] firstIds = persistedSlot(flush(index), ORDER).valueIds();
			index.removeSortValue(ORDER, 2);
			index.putSortValue(ORDER, 3, 11);
			final SortValueSlot slot = persistedSlot(flush(index), ORDER);
			assertEquals(firstIds[0], slot.valueIds()[0], "Value 7 keeps its id");
			assertTrue(slot.valueIds()[1] > firstIds[1], "Value 11 must not take the id 9 held");
		}
	}

	@Nested
	@DisplayName("Transactional memory")
	@Tag(TRANSACTION)
	class TransactionalMemory {

		@Test
		@DisplayName("a commit produces a new instance with the new sort order and leaves the original untouched")
		void shouldCommitSortValuesIntoANewInstance() {
			final VirtualReferencePartitionIndex index = declaredIndex();
			final Map<AttributeIndexKey, Map<Integer, Serializable>> models = new HashMap<>();
			churn(index, models, new Random(5), 500, 100);
			index.resetDirty();
			final Map<AttributeIndexKey, SortSnapshot> before = new HashMap<>();
			FIXTURES.forEach(fixture -> before.put(fixture.key(), snapshotOf(index, fixture.key())));

			assertStateAfterCommit(
				index,
				original -> {
					churn(original, models, new Random(6), 500, 150);
					// the transaction sees its own writes
					assertMatchesModels(original, models);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					FIXTURES.forEach(
						fixture -> assertEquals(
							before.get(fixture.key()), snapshotOf(original, fixture.key()),
							"The original must not see the committed changes of " + fixture
						)
					);
					assertMatchesModels(committed, models);
				}
			);
		}

		@Test
		@DisplayName("a transaction that only records sort values commits every sort index it touched")
		void shouldCommitATransactionOfPutsAlone() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.declareSortKey(ORDER, INTEGER_ASC, 0);
			index.putSortValue(ORDER, 1, 3);
			index.resetDirty();

			// no removal, no declaration: only the puts themselves may enlist the sort index in the commit
			assertStateAfterCommit(
				index,
				original -> {
					original.putSortValue(ORDER, 2, 5);
					original.putSortValue(ORDER, 1, 7);
				},
				(original, committed) -> {
					assertEquals(List.of(1), snapshotOf(original, ORDER).records());
					assertEquals(
						new SortSnapshot(List.of(2, 1), List.of(5, 7), List.of(1, 1)),
						snapshotOf(committed, ORDER)
					);
				}
			);
		}

		@Test
		@DisplayName("a transaction that only removes sort values commits every sort index it touched")
		void shouldCommitATransactionOfRemovalsAlone() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.declareSortKey(ORDER, INTEGER_ASC, 0);
			index.putSortValue(ORDER, 1, 3);
			index.putSortValue(ORDER, 2, 3);
			index.resetDirty();

			assertStateAfterCommit(
				index,
				original -> original.removeSortValue(ORDER, 1),
				(original, committed) -> {
					assertEquals(List.of(1, 2), snapshotOf(original, ORDER).records());
					assertEquals(List.of(2), snapshotOf(committed, ORDER).records());
				}
			);
		}

		@Test
		@DisplayName("declaring and retiring keys inside a transaction commits cleanly")
		void shouldCommitDeclarationAndRetirement() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.declareSortKey(ORDER, INTEGER_ASC, 0);
			index.putSortValue(ORDER, 1, 3);
			index.resetDirty();
			final SortKeyFixture title = FIXTURES.get(1);

			assertStateAfterCommit(
				index,
				original -> {
					title.declare(original);
					title.put(original, 1, "hora");
					original.retireSortKey(ORDER);
					// declared, written and retired within the same transaction
					original.declareSortKey(ORDER, INTEGER_ASC, 0);
					original.putSortValue(ORDER, 2, 4);
					original.retireSortKey(ORDER);
				},
				(original, committed) -> {
					assertEquals(List.of(1), snapshotOf(original, ORDER).records());
					assertFalse(original.getDeclaredSortKeys().contains(title.key()));
					assertFalse(committed.getDeclaredSortKeys().contains(ORDER));
					assertEquals(List.of(1), snapshotOf(committed, title.key()).records());
				}
			);
		}

		@Test
		@DisplayName("a rollback leaves the original exactly as it was")
		void shouldLeaveTheOriginalOnRollback() {
			final VirtualReferencePartitionIndex index = declaredIndex();
			final Map<AttributeIndexKey, Map<Integer, Serializable>> models = new HashMap<>();
			churn(index, models, new Random(7), 500, 100);
			index.resetDirty();

			assertStateAfterRollback(
				index,
				original -> {
					churn(original, copyOf(models), new Random(8), 500, 100);
					original.retireSortKey(ORDER);
				},
				(original, committed) -> {
					assertNull(committed);
					assertMatchesModels(original, models);
					assertTrue(flush(original).isEmpty(), "A rolled-back index must not be dirty");
				}
			);
		}

		@Test
		@DisplayName("removing the layers discards every uncommitted sort value")
		void shouldDiscardUncommittedSortValuesWhenTheLayersAreRemoved() {
			final VirtualReferencePartitionIndex index = declaredIndex();
			final Map<AttributeIndexKey, Map<Integer, Serializable>> models = new HashMap<>();
			churn(index, models, new Random(9), 300, 100);
			index.resetDirty();

			assertStateAfterCommit(
				index,
				original -> {
					churn(original, copyOf(models), new Random(10), 300, 100);
					original.removeLayer(Transaction.getTransactionalLayerMaintainer());
					assertMatchesModels(original, models);
				},
				// the commit verifies that no layer of the index was left behind
				(original, committed) -> assertSame(original, committed)
			);
		}
	}

	@Nested
	@DisplayName("Warm-up savepoint")
	@Tag(TRANSACTION)
	class WarmUpSavepointRollback {

		@Test
		@DisplayName("a rollback restores the slots, the dictionaries and the sort indexes")
		void shouldRestoreSortValuesOnRollback() {
			final VirtualReferencePartitionIndex index = declaredIndex();
			final Map<AttributeIndexKey, Map<Integer, Serializable>> models = new HashMap<>();
			assertWarmUpSavepointRollbackRestores(
				index,
				baseline -> churn(baseline, models, new Random(12), 800, 150),
				restored -> {
					final Map<AttributeIndexKey, SortSnapshot> snapshots = new HashMap<>();
					FIXTURES.forEach(
						fixture -> snapshots.put(fixture.key(), snapshotOf(restored, fixture.key()))
					);
					return snapshots;
				},
				savepoint -> {
					churn(savepoint, copyOf(models), new Random(13), 800, 150);
					savepoint.retireSortKey(FIXTURES.get(2).key());
					savepoint.declareSortKey(new AttributeIndexKey(REFERENCE_NAME, "fresh", null), INTEGER_ASC, 0);
				}
			);
			assertMatchesModels(index, models);
			assertFalse(index.getDeclaredSortKeys().contains(new AttributeIndexKey(REFERENCE_NAME, "fresh", null)));
			// the restored dictionary counts its users again: removing every partition must drain it exactly
			final Map<Integer, Serializable> orderModel = models.getOrDefault(ORDER, Map.of());
			orderModel.keySet().forEach(partition -> assertTrue(index.removeSortValue(ORDER, partition)));
			assertEquals(0, persistedSlot(flush(index), ORDER).values().length);
		}
	}

	@Nested
	@DisplayName("Flush emission")
	class FlushEmission {

		@Test
		@DisplayName("the slot entries hold the value ids the persisted dictionary lists")
		void shouldPersistEntriesReferringToTheDictionary() {
			final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
			index.declareSortKey(ORDER, INTEGER_ASC, 0);
			index.putSortValue(ORDER, 2, 9);
			index.putSortValue(ORDER, 5, 7);

			final List<StoragePart> parts = flush(index);
			final SortValueSlot slot = persistedSlot(parts, ORDER);
			assertNotNull(slot);
			final VirtualReferencePartitionIndexStoragePart root = (VirtualReferencePartitionIndexStoragePart) parts.get(0);
			assertFalse(root.isPaged());
			assertArrayEquals(
				new long[]{NumberUtils.pack(slot.slotId(), 2), NumberUtils.pack(slot.slotId(), 5)}, root.getKeys()
			);
			final Map<Long, Serializable> valueById = new HashMap<>();
			for (int i = 0; i < slot.valueIds().length; i++) {
				valueById.put((long) slot.valueIds()[i], slot.values()[i]);
			}
			assertEquals(9, valueById.get(root.getPayloads()[0]));
			assertEquals(7, valueById.get(root.getPayloads()[1]));
			assertNotEquals(root.getPayloads()[0], root.getPayloads()[1]);
		}
	}

}
