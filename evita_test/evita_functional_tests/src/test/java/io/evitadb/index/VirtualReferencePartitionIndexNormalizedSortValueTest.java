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
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.SortKeyFixture;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.OwnerSortIndex;
import io.evitadb.index.attribute.SortIndex;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.attribute.SortIndexView;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.VirtualReferencePartitionIndexStoragePart;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
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
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.CAJ_COMPOSED;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.CAJ_DECOMPOSED;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.CZECH;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.NO_VALUES;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.REFERENCE_NAME;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.assertMatchesModel;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.snapshotOf;
import static io.evitadb.index.VirtualReferencePartitionIndexSortValueTest.storedValueOf;
import static io.evitadb.index.VirtualReferencePartitionIndexTest.flush;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link VirtualReferencePartitionIndex} records a sort value handed over in its normalized form - the
 * form a sort index stores and reads back by record, a scaled `Integer` for a `BigDecimal`, an `Instant` for an
 * `OffsetDateTime` or a `LocalDateTime`, a comparable wrapper for a `Locale` or a `Currency` - exactly as it records
 * the attribute value: the same entry, the same dictionary value, the same block of the sort index, through replacement
 * and removal.
 *
 * The normalized values are never made up by the test. Each one is read back from a real sort index the attribute
 * value was added to: an owner sort index, and for a plain attribute also a view over a value tree normalized the way a
 * filter index normalizes it. The two are required to agree, so the test also pins that a sort-only attribute and a
 * filterable and sortable one hold their values in one value space - the one the index holds them in, too.
 *
 * Every index recording normalized values is checked against a twin recording the attribute values, entry by entry
 * and block by block, and against a model of the raw values ordered by the very normalizer and comparator
 * {@link SortIndex} derives.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Virtual reference partition index sort values recorded in their normalized form")
@Tag(INDEXING)
@Tag(REFERENCE)
class VirtualReferencePartitionIndexNormalizedSortValueTest {
	static final AttributeIndexKey WEIGHT = new AttributeIndexKey(REFERENCE_NAME, "weight", null);
	static final AttributeIndexKey VALID_FROM = new AttributeIndexKey(REFERENCE_NAME, "validFrom", null);
	static final AttributeIndexKey PUBLISHED_AT = new AttributeIndexKey(REFERENCE_NAME, "publishedAt", null);
	static final AttributeIndexKey ROLE_WEIGHT = new AttributeIndexKey(REFERENCE_NAME, "roleWeight", null);
	static final AttributeIndexKey TITLE = new AttributeIndexKey(REFERENCE_NAME, "title", CZECH);
	/**
	 * The decimal places the `BigDecimal` attribute and the `BigDecimalNumberRange` attribute are indexed with -
	 * deliberately more than the intrinsic scale of the ranges, which are written with one decimal place.
	 */
	static final int DECIMAL_PLACES = 2;
	/**
	 * Every value form the sort keys of a partition can take, each with explicit attribute values - several of which
	 * normalize to, or are ordered as, one value.
	 */
	static final List<ValueFixture> FIXTURES = List.of(
		scalar(
			WEIGHT, BigDecimal.class, OrderDirection.DESC, DECIMAL_PLACES,
			new BigDecimal("1.5"), new BigDecimal("1.50"), new BigDecimal("2"), new BigDecimal("0.25"),
			new BigDecimal("-3.10"), new BigDecimal("-3.1"), new BigDecimal("-0.005"), new BigDecimal("1.235"),
			new BigDecimal("1.2349"), new BigDecimal("1.24"), new BigDecimal("-1.235"), BigDecimal.ZERO,
			new BigDecimal("21474836.47"), new BigDecimal("-21474836.48")
		),
		scalar(
			VALID_FROM, OffsetDateTime.class, OrderDirection.ASC, 0,
			OffsetDateTime.parse("2024-01-01T11:00:00+02:00"), OffsetDateTime.parse("2024-01-01T10:00:00+01:00"),
			OffsetDateTime.parse("2024-01-01T09:00:00.000999Z"), OffsetDateTime.parse("2024-01-01T08:00:00Z"),
			OffsetDateTime.parse("2024-01-01T09:00:00.001+00:00"), OffsetDateTime.parse("1969-12-31T23:59:59.999-05:00")
		),
		scalar(
			PUBLISHED_AT, LocalDateTime.class, OrderDirection.ASC, 0,
			LocalDateTime.parse("2024-01-01T09:00:00"), LocalDateTime.parse("2024-01-01T09:00:00.000500"),
			LocalDateTime.parse("2024-03-31T02:30:00"), LocalDateTime.parse("2024-10-27T02:30:00"),
			LocalDateTime.parse("1970-01-01T00:00:00"), LocalDateTime.parse("2024-01-01T08:59:59.999")
		),
		scalar(
			new AttributeIndexKey(REFERENCE_NAME, "openFrom", null), LocalTime.class, OrderDirection.ASC, 0,
			LocalTime.parse("09:00:00"), LocalTime.parse("09:00:00.000700"), LocalTime.parse("23:59:59.999999"),
			LocalTime.MIDNIGHT
		),
		scalar(
			TITLE, String.class, OrderDirection.ASC, 0,
			CAJ_COMPOSED, CAJ_DECOMPOSED, "chata", "cesta", "Česko", "Ábel", "abeceda"
		),
		scalar(
			new AttributeIndexKey(REFERENCE_NAME, "language", null), Locale.class, OrderDirection.ASC, 0,
			Locale.ENGLISH, Locale.GERMAN, Locale.forLanguageTag("cs-CZ"), Locale.FRENCH
		),
		scalar(
			new AttributeIndexKey(REFERENCE_NAME, "currency", null), Currency.class, OrderDirection.DESC, 0,
			Currency.getInstance("CZK"), Currency.getInstance("EUR"), Currency.getInstance("USD")
		),
		scalar(
			new AttributeIndexKey(REFERENCE_NAME, "mediaOrder", null), Integer.class, OrderDirection.ASC, 0,
			7, -2, 0, 7, Integer.MAX_VALUE
		),
		scalar(
			new AttributeIndexKey(REFERENCE_NAME, "sizeRange", null), BigDecimalNumberRange.class,
			OrderDirection.ASC, DECIMAL_PLACES,
			// rescaled to the schema's decimal places before it reaches any index, as the attribute index mutator does
			rangeAsIndexed("1.5", "2.5"), rangeAsIndexed("1.5", "2.5"), rangeAsIndexed("0.1", "9.9"),
			rangeAsIndexed("-4.0", "-0.5"), rangeAsIndexed("1.5", "2.6")
		),
		compound(
			ROLE_WEIGHT,
			new ComparatorSource[]{
				new ComparatorSource(String.class, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
				new ComparatorSource(BigDecimal.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				new ComparatorSource(OffsetDateTime.class, OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
				new ComparatorSource(Locale.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				new ComparatorSource(Currency.class, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
			},
			new Serializable[][]{
				{"gallery", "video", CAJ_COMPOSED, null},
				{new BigDecimal("1.0"), new BigDecimal("1.00"), new BigDecimal("1.234"), new BigDecimal("-2.5"), null},
				{
					OffsetDateTime.parse("2024-01-01T11:00:00+02:00"), OffsetDateTime.parse("2024-01-01T10:00:00+01:00"),
					OffsetDateTime.parse("2024-01-01T08:00:00.000400Z"), null
				},
				{Locale.ENGLISH, Locale.forLanguageTag("cs-CZ"), null},
				{Currency.getInstance("CZK"), Currency.getInstance("EUR"), null}
			}
		),
		compound(
			new AttributeIndexKey(REFERENCE_NAME, "titleWhen", CZECH),
			new ComparatorSource[]{
				new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				new ComparatorSource(LocalDateTime.class, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST),
				new ComparatorSource(LocalTime.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				new ComparatorSource(BigDecimalNumberRange.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST),
				new ComparatorSource(Integer.class, OrderDirection.DESC, OrderBehaviour.NULLS_FIRST)
			},
			new Serializable[][]{
				{CAJ_COMPOSED, CAJ_DECOMPOSED, "chata", null},
				{
					LocalDateTime.parse("2024-01-01T09:00:00"), LocalDateTime.parse("2024-01-01T09:00:00.000500"),
					LocalDateTime.parse("2024-03-31T02:30:00"), null
				},
				{LocalTime.parse("09:00:00"), LocalTime.parse("09:00:00.000700"), null},
				{
					BigDecimalNumberRange.between(new BigDecimal("1.5"), new BigDecimal("2.5"), 1),
					BigDecimalNumberRange.between(new BigDecimal("0.1"), new BigDecimal("9.9"), 1), null
				},
				{1, 2, null}
			}
		)
	);

	/**
	 * One sort key with the attribute values its partitions are given.
	 *
	 * @param sortKey the sort key
	 * @param values  the attribute values, element arrays for a compound
	 */
	record ValueFixture(@Nonnull SortKeyFixture sortKey, @Nonnull List<Serializable> values) {

		@Nonnull
		@Override
		public String toString() {
			return this.sortKey.toString();
		}
	}

	/**
	 * Creates the fixture of a plain attribute.
	 *
	 * @param key           the sort key
	 * @param type          the attribute type
	 * @param direction     the order direction
	 * @param decimalPlaces the decimal places a `BigDecimal` attribute is scaled by
	 * @param values        the attribute values
	 * @return the fixture
	 */
	@Nonnull
	private static ValueFixture scalar(
		@Nonnull AttributeIndexKey key,
		@Nonnull Class<?> type,
		@Nonnull OrderDirection direction,
		int decimalPlaces,
		@Nonnull Serializable... values
	) {
		final List<Serializable> valueList = List.of(values);
		return new ValueFixture(
			new SortKeyFixture(
				key, new ComparatorSource[]{new ComparatorSource(type, direction, OrderBehaviour.NULLS_LAST)},
				decimalPlaces, false, random -> valueList.get(random.nextInt(valueList.size()))
			),
			valueList
		);
	}

	/**
	 * Creates the fixture of a sortable attribute compound whose values combine the element options pseudo-randomly,
	 * with a fixed seed.
	 *
	 * @param key            the sort key
	 * @param comparatorBase one descriptor per element
	 * @param options        the options of every element, `null` among them for a missing element
	 * @return the fixture
	 */
	@Nonnull
	private static ValueFixture compound(
		@Nonnull AttributeIndexKey key,
		@Nonnull ComparatorSource[] comparatorBase,
		@Nonnull Serializable[][] options
	) {
		final Function<Random, Serializable> generator = random -> {
			final Serializable[] value = new Serializable[options.length];
			for (int i = 0; i < options.length; i++) {
				value[i] = options[i][random.nextInt(options[i].length)];
			}
			return value;
		};
		final Random random = new Random(1615);
		final List<Serializable> values = new ArrayList<>(40);
		for (int i = 0; i < 40; i++) {
			values.add(generator.apply(random));
		}
		return new ValueFixture(new SortKeyFixture(key, comparatorBase, 0, true, generator), List.copyOf(values));
	}

	/**
	 * Creates a range with one intrinsic decimal place, rescaled to {@link #DECIMAL_PLACES} as the attribute index
	 * mutator rescales a range before it hands it to any index.
	 *
	 * @param from the lower bound
	 * @param to   the upper bound
	 * @return the rescaled range
	 */
	@Nonnull
	private static Serializable rangeAsIndexed(@Nonnull String from, @Nonnull String to) {
		final BigDecimalNumberRange range = BigDecimalNumberRange.between(new BigDecimal(from), new BigDecimal(to), 1);
		final Serializable indexed = NumberUtils.normalizeForIndexing(range, DECIMAL_PLACES);
		assertNotEquals(
			range.getEffectiveRetainedDecimalPlaces(),
			((BigDecimalNumberRange) indexed).getEffectiveRetainedDecimalPlaces(),
			"The intrinsic scale of the range must differ from the indexed one"
		);
		return indexed;
	}

	/**
	 * Lists the fixtures for the parameterized tests.
	 *
	 * @return the fixtures
	 */
	@Nonnull
	static Stream<ValueFixture> fixtures() {
		return FIXTURES.stream();
	}

	/**
	 * Returns the comparator the sort index of the key orders normalized values by.
	 *
	 * @param sortKey the sort key
	 * @return the comparator
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	@Nonnull
	static Comparator<Serializable> comparatorOf(@Nonnull SortKeyFixture sortKey) {
		final ComparatorSource[] base = sortKey.comparatorBase();
		return base.length == 1 ?
			SortIndex.createComparatorFor(sortKey.key().locale(), base[0]) :
			(Comparator) SortIndex.createCombinedComparatorFor(sortKey.key().locale(), base);
	}

	/**
	 * Returns the normalized form of an attribute value as a real sort index stores it, read back by record. For a
	 * plain attribute the value is added both to an owner sort index and to a view over a value tree normalized as a
	 * filter index normalizes it, and the two must agree - they are the sort index of a sort-only and of a filterable
	 * and sortable attribute.
	 *
	 * @param sortKey the sort key
	 * @param value   the attribute value, the element values of a compound
	 * @return the normalized value
	 */
	@Nonnull
	static Serializable normalizedBySortIndex(@Nonnull SortKeyFixture sortKey, @Nonnull Serializable value) {
		final ComparatorSource[] base = sortKey.comparatorBase();
		if (sortKey.compound()) {
			final OwnerSortIndex owner = new OwnerSortIndex(
				base,
				null,
				sortKey.key(),
				SortIndex.createLegacyNormalizerFor(base, 0)
			);
			final Serializable[] elements = (Serializable[]) value;
			owner.addRecord(Arrays.copyOf(elements, elements.length), 1);
			return owner.getValueOfRecord(1).orElseThrow();
		}
		final Class<?> type = base[0].type();
		final OwnerSortIndex owner = new OwnerSortIndex(
			type,
			null,
			sortKey.key(),
			sortKey.decimalPlaces(),
			SortIndex.createLegacyNormalizerFor(type, sortKey.decimalPlaces())
		);
		owner.addRecord(value, 1);
		final Serializable ownerValue = owner.getValueOfRecord(1).orElseThrow();

		final InvertedIndex sharedTree = new InvertedIndex(
			type,
			FilterIndex.getNormalizer(type, sortKey.decimalPlaces()),
			FilterIndex.getComparator(sortKey.key(), type),
			sortKey.decimalPlaces()
		);
		final SortIndex view = SortIndex.create(
			type,
			null,
			sortKey.key(),
			sortKey.decimalPlaces(),
			() -> sharedTree,
			SortIndex.createLegacyNormalizerFor(type, sortKey.decimalPlaces())
		);
		assertInstanceOf(SortIndexView.class, view);
		// the order the entity index writes a filterable and sortable attribute in: the sort index, then the filter
		view.addRecord(value, 1);
		sharedTree.addRecord(value, 1);
		assertEquals(
			ownerValue, view.getValueOfRecord(1).orElseThrow(),
			"An owner and a view of " + sortKey + " must store `" + value + "` as one value"
		);
		return ownerValue;
	}

	/**
	 * Creates an index with the sort key declared.
	 *
	 * @param sortKey the sort key
	 * @return the index
	 */
	@Nonnull
	private static VirtualReferencePartitionIndex declared(@Nonnull SortKeyFixture sortKey) {
		final VirtualReferencePartitionIndex index = new VirtualReferencePartitionIndex();
		sortKey.declare(index);
		return index;
	}

	/**
	 * Records the attribute value of the partition in the raw twin and its normalized form in the normalized twin,
	 * and in the model.
	 *
	 * @param fixture    the sort key
	 * @param raw        the twin receiving attribute values
	 * @param normalized the twin receiving normalized values
	 * @param model      the attribute value of every partition holding one
	 * @param partition  the partition primary key
	 * @param value      the attribute value
	 */
	private static void putToBoth(
		@Nonnull ValueFixture fixture,
		@Nonnull VirtualReferencePartitionIndex raw,
		@Nonnull VirtualReferencePartitionIndex normalized,
		@Nonnull Map<Integer, Serializable> model,
		int partition,
		@Nonnull Serializable value
	) {
		fixture.sortKey().put(raw, partition, value);
		normalized.putNormalizedSortValue(
			fixture.sortKey().key(), partition, normalizedBySortIndex(fixture.sortKey(), value)
		);
		model.put(partition, value);
	}

	/**
	 * Asserts the twins are indistinguishable: equal sort indexes, equal stored values per partition, equal entry
	 * counts, and - after a flush - equal persisted entries and value dictionaries. The normalized twin must further
	 * follow the model, and every stored value must be the one a real sort index stores for the partition's attribute
	 * value.
	 *
	 * @param fixture    the sort key
	 * @param raw        the twin receiving attribute values
	 * @param normalized the twin receiving normalized values
	 * @param model      the attribute value of every partition holding one
	 * @param stage      describes the moment of the assertion
	 * @return the persisted slot of the normalized twin, or `null` when nothing changed since the previous flush
	 */
	@Nullable
	private static VirtualReferencePartitionIndexStoragePart.SortValueSlot assertTwinsAgree(
		@Nonnull ValueFixture fixture,
		@Nonnull VirtualReferencePartitionIndex raw,
		@Nonnull VirtualReferencePartitionIndex normalized,
		@Nonnull Map<Integer, Serializable> model,
		@Nonnull String stage
	) {
		final SortKeyFixture sortKey = fixture.sortKey();
		assertEquals(
			snapshotOf(raw, sortKey.key()), snapshotOf(normalized, sortKey.key()),
			"The sort index of " + sortKey + " must not tell the twins apart " + stage
		);
		assertMatchesModel(sortKey, normalized, model);
		assertEquals(
			VirtualReferencePartitionIndexEntries.count(raw), VirtualReferencePartitionIndexEntries.count(normalized),
			"Entry counts " + stage
		);

		final Comparator<Serializable> comparator = comparatorOf(sortKey);
		for (final Map.Entry<Integer, Serializable> entry : model.entrySet()) {
			final int partition = entry.getKey();
			final Serializable stored = storedValueOf(normalized, sortKey.key(), partition);
			assertNotNull(stored, "Partition " + partition + " of " + sortKey + " must hold a value " + stage);
			assertEquals(
				storedValueOf(raw, sortKey.key(), partition), stored,
				"Partition " + partition + " of " + sortKey + " " + stage
			);
			// the block keeps the first of the values it orders as one, so the value space is compared by order
			assertEquals(
				0, comparator.compare(normalizedBySortIndex(sortKey, entry.getValue()), stored),
				"Partition " + partition + " of " + sortKey + " must hold what a sort index holds for `" +
					entry.getValue() + "` " + stage
			);
		}

		final List<StoragePart> rawParts = flush(raw);
		raw.resetDirty();
		final List<StoragePart> normalizedParts = flush(normalized);
		normalized.resetDirty();
		assertEquals(rawParts.size(), normalizedParts.size(), "Flushed parts " + stage);
		if (normalizedParts.isEmpty()) {
			return null;
		}
		final VirtualReferencePartitionIndexStoragePart rawRoot = singleInlineRoot(rawParts);
		final VirtualReferencePartitionIndexStoragePart normalizedRoot = singleInlineRoot(normalizedParts);
		assertArrayEquals(rawRoot.getKeys(), normalizedRoot.getKeys(), "Persisted entry keys " + stage);
		assertArrayEquals(rawRoot.getPayloads(), normalizedRoot.getPayloads(), "Persisted value ids " + stage);
		final VirtualReferencePartitionIndexStoragePart.SortValueSlot rawSlot =
			VirtualReferencePartitionIndexSortValueTest.persistedSlot(rawParts, sortKey.key());
		final VirtualReferencePartitionIndexStoragePart.SortValueSlot normalizedSlot =
			VirtualReferencePartitionIndexSortValueTest.persistedSlot(normalizedParts, sortKey.key());
		assertNotNull(rawSlot);
		assertNotNull(normalizedSlot);
		assertArrayEquals(rawSlot.valueIds(), normalizedSlot.valueIds(), "Persisted dictionary ids " + stage);
		assertArrayEquals(rawSlot.values(), normalizedSlot.values(), "Persisted dictionary values " + stage);
		return normalizedSlot;
	}

	/**
	 * Returns the root of an emission, which must be the only part and hold its entries inline.
	 *
	 * @param parts the emission
	 * @return the root
	 */
	@Nonnull
	private static VirtualReferencePartitionIndexStoragePart singleInlineRoot(@Nonnull List<StoragePart> parts) {
		assertEquals(1, parts.size(), "A small index is flushed as its root alone: " + parts);
		final VirtualReferencePartitionIndexStoragePart root =
			assertInstanceOf(VirtualReferencePartitionIndexStoragePart.class, parts.getFirst());
		assertFalse(root.isPaged(), "A small index keeps its entries inline");
		return root;
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("fixtures")
	@DisplayName("a normalized value leaves what its attribute value leaves, through replacement and removal")
	void shouldRecordNormalizedValueAsItsAttributeValue(@Nonnull ValueFixture fixture) {
		final VirtualReferencePartitionIndex raw = declared(fixture.sortKey());
		final VirtualReferencePartitionIndex normalized = declared(fixture.sortKey());
		final Map<Integer, Serializable> model = new HashMap<>();
		final List<Serializable> values = fixture.values();
		final int partitions = 2 * values.size();

		for (int partition = 1; partition <= partitions; partition++) {
			putToBoth(fixture, raw, normalized, model, partition, values.get((partition - 1) % values.size()));
		}
		assertTwinsAgree(fixture, raw, normalized, model, "after the puts");

		// every partition switches to the next value, which releases the previous one from its block
		for (int partition = 1; partition <= partitions; partition++) {
			putToBoth(fixture, raw, normalized, model, partition, values.get(partition % values.size()));
		}
		assertTwinsAgree(fixture, raw, normalized, model, "after the replacements");

		// the same value put again changes nothing
		putToBoth(fixture, raw, normalized, model, 1, model.get(1));
		assertTwinsAgree(fixture, raw, normalized, model, "after the same value was put again");

		for (int partition = 1; partition <= partitions; partition += 3) {
			assertTrue(raw.removeSortValue(fixture.sortKey().key(), partition));
			assertTrue(normalized.removeSortValue(fixture.sortKey().key(), partition));
			model.remove(partition);
		}
		assertTwinsAgree(fixture, raw, normalized, model, "after the removals");

		for (final Integer partition : List.copyOf(model.keySet())) {
			assertTrue(normalized.removeSortValue(fixture.sortKey().key(), partition));
			assertTrue(raw.removeSortValue(fixture.sortKey().key(), partition));
			model.remove(partition);
		}
		final VirtualReferencePartitionIndexStoragePart.SortValueSlot emptied =
			assertTwinsAgree(fixture, raw, normalized, model, "after every value was removed");
		assertNotNull(emptied, "Retiring the last value rewrites the root");
		assertEquals(0, emptied.values().length, "The dictionary is emptied together with the slot");
		assertEquals(NO_VALUES, snapshotOf(normalized, fixture.sortKey().key()));
	}

	@Nested
	@DisplayName("Values a sort index orders as one")
	class OneValue {

		@Test
		@DisplayName("one moment written at different offsets and to the sub-millisecond is one block")
		void shouldTieEqualInstantsWrittenAtDifferentOffsets() {
			final SortKeyFixture sortKey = FIXTURES.get(1).sortKey();
			assertEquals(VALID_FROM, sortKey.key());
			final VirtualReferencePartitionIndex index = declared(sortKey);
			index.putNormalizedSortValue(
				VALID_FROM, 1, normalizedBySortIndex(sortKey, OffsetDateTime.parse("2024-01-01T11:00:00+02:00"))
			);
			index.putNormalizedSortValue(
				VALID_FROM, 2, normalizedBySortIndex(sortKey, OffsetDateTime.parse("2024-01-01T10:00:00+01:00"))
			);
			index.putSortValue(VALID_FROM, 3, OffsetDateTime.parse("2024-01-01T09:00:00.000999Z"));
			index.putNormalizedSortValue(
				VALID_FROM, 4, normalizedBySortIndex(sortKey, OffsetDateTime.parse("2024-01-01T08:00:00Z"))
			);

			final VirtualReferencePartitionIndexSortValueTest.SortSnapshot snapshot = snapshotOf(index, VALID_FROM);
			assertEquals(List.of(4, 1, 2, 3), snapshot.records());
			assertEquals(List.of(1, 3), snapshot.blockSizes(), "The three writings of 09:00Z are one block");
			assertEquals(
				List.of(Instant.parse("2024-01-01T08:00:00Z"), Instant.parse("2024-01-01T09:00:00Z")),
				snapshot.values()
			);
		}

		@Test
		@DisplayName("a BigDecimal with more digits than the decimal places joins the block of its rounded value")
		void shouldRoundBigDecimalAsItsAttributeValueIsRounded() {
			final SortKeyFixture sortKey = FIXTURES.getFirst().sortKey();
			assertEquals(WEIGHT, sortKey.key());
			final VirtualReferencePartitionIndex index = declared(sortKey);
			final Serializable roundedUp = normalizedBySortIndex(sortKey, new BigDecimal("1.235"));
			final Serializable roundedAwayFromZero = normalizedBySortIndex(sortKey, new BigDecimal("-1.235"));
			assertEquals(124, roundedUp);
			assertEquals(-124, roundedAwayFromZero);

			index.putNormalizedSortValue(WEIGHT, 1, roundedUp);
			index.putSortValue(WEIGHT, 2, new BigDecimal("1.24"));
			index.putNormalizedSortValue(WEIGHT, 3, roundedAwayFromZero);
			index.putSortValue(WEIGHT, 4, new BigDecimal("-1.24"));
			index.putNormalizedSortValue(WEIGHT, 5, normalizedBySortIndex(sortKey, new BigDecimal("-0.005")));

			final VirtualReferencePartitionIndexSortValueTest.SortSnapshot snapshot = snapshotOf(index, WEIGHT);
			// descending
			assertEquals(List.of(1, 2, 5, 3, 4), snapshot.records());
			assertEquals(List.of(124, -1, -124), snapshot.values());
			assertEquals(List.of(2, 1, 2), snapshot.blockSizes());
		}

		@Test
		@DisplayName("a BigDecimal compound element stays exact and joins only the block of its numeric value")
		void shouldKeepCompoundBigDecimalExact() {
			final SortKeyFixture sortKey = FIXTURES.get(9).sortKey();
			assertEquals(ROLE_WEIGHT, sortKey.key());
			final VirtualReferencePartitionIndex index = declared(sortKey);
			index.putNormalizedSortValue(
				ROLE_WEIGHT, 1, normalizedBySortIndex(sortKey, compoundOf(new BigDecimal("1.234")))
			);
			index.putSortValue(ROLE_WEIGHT, 2, compoundOf(new BigDecimal("1.23")));
			index.putNormalizedSortValue(
				ROLE_WEIGHT, 3, normalizedBySortIndex(sortKey, compoundOf(new BigDecimal("1.2340")))
			);

			final VirtualReferencePartitionIndexSortValueTest.SortSnapshot snapshot = snapshotOf(index, ROLE_WEIGHT);
			assertEquals(List.of(2, 1, 3), snapshot.records());
			assertEquals(List.of(1, 2), snapshot.blockSizes(), "1.234 and 1.2340 are one value, 1.23 is not");
		}

		/**
		 * Creates a compound value of {@link #ROLE_WEIGHT} varying only in its `BigDecimal` element.
		 *
		 * @param weight the `BigDecimal` element
		 * @return the element values
		 */
		@Nonnull
		private static Serializable[] compoundOf(@Nonnull BigDecimal weight) {
			return new Serializable[]{
				"gallery", weight, OffsetDateTime.parse("2024-01-01T11:00:00+02:00"), Locale.ENGLISH, null
			};
		}

		@Test
		@DisplayName("a composed String is held decomposed by an owner, a view and the partition index alike")
		void shouldHoldOneStringValueSpaceInEverySortIndex() {
			final SortKeyFixture sortKey = FIXTURES.get(4).sortKey();
			assertEquals(TITLE, sortKey.key());
			final String decomposed = Normalizer.normalize(CAJ_COMPOSED, Normalizer.Form.NFD);
			assertNotEquals(CAJ_COMPOSED, decomposed, "The fixture must hold a composed character");
			// asserts the owner and the view agree
			final Serializable stored = normalizedBySortIndex(sortKey, CAJ_COMPOSED);
			assertEquals(decomposed, stored);

			final VirtualReferencePartitionIndex index = declared(sortKey);
			index.putNormalizedSortValue(TITLE, 1, stored);
			index.putSortValue(TITLE, 2, CAJ_COMPOSED);
			assertEquals(decomposed, storedValueOf(index, TITLE, 1));
			assertEquals(List.of(2), snapshotOf(index, TITLE).blockSizes());
		}
	}

	@Nested
	@DisplayName("Refused values")
	class RefusedValues {

		@Test
		@DisplayName("a plain value for a compound key, a compound of another length or a wrong type change nothing")
		void shouldRefuseNormalizedValueOfWrongShapeBeforeWriting() {
			final SortKeyFixture compound = FIXTURES.get(9).sortKey();
			final SortKeyFixture plain = FIXTURES.get(0).sortKey();
			final VirtualReferencePartitionIndex index = declared(compound);
			plain.declare(index);
			index.putNormalizedSortValue(WEIGHT, 1, 150);
			final int entryCount = VirtualReferencePartitionIndexEntries.count(index, 1, 2);

			assertThrows(
				GenericEvitaInternalError.class, () -> index.putNormalizedSortValue(ROLE_WEIGHT, 2, "gallery")
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> index.putNormalizedSortValue(
					ROLE_WEIGHT, 2, new ComparableArray(new Serializable[]{"gallery", BigDecimal.ONE})
				)
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> index.putNormalizedSortValue(WEIGHT, 2, new ComparableArray(new Serializable[]{150}))
			);
			assertThrows(GenericEvitaInternalError.class, () -> index.putNormalizedSortValue(WEIGHT, 2, "1.50"));
			assertThrows(
				GenericEvitaInternalError.class,
				() -> index.putNormalizedSortValue(new AttributeIndexKey(REFERENCE_NAME, "undeclared", null), 2, 1)
			);

			assertEquals(
				entryCount, VirtualReferencePartitionIndexEntries.count(index, 1, 2),
				"A refused value must not leave an entry behind"
			);
			assertEquals(NO_VALUES, snapshotOf(index, ROLE_WEIGHT));
			assertEquals(List.of(1), snapshotOf(index, WEIGHT).records());
			assertEquals(new BigDecimal("1.50"), denormalizedWeightOf(index));
		}

		/**
		 * Reads the stored value of partition 1 of {@link #WEIGHT} back as the `BigDecimal` it scales.
		 *
		 * @param index the index
		 * @return the value at the scale of the decimal places
		 */
		@Nullable
		private static BigDecimal denormalizedWeightOf(@Nonnull VirtualReferencePartitionIndex index) {
			return Optional.ofNullable(storedValueOf(index, WEIGHT, 1))
				.map(Integer.class::cast)
				.map(scaled -> BigDecimal.valueOf(scaled, DECIMAL_PLACES))
				.orElse(null);
		}
	}
}
