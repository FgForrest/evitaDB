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

import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.dataType.ComparableCurrency;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.attribute.SortIndex.ComparatorSource;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link SortIndex#getValueOfRecord(int)}: the value a record is ordered by comes back exactly as the index
 * stores it - normalized - in owner and view mode, for plain values and compounds, and inside an open transaction,
 * while a record the index does not hold comes back as absence rather than as an exception.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("SortIndex value of a record")
class SortIndexValueOfRecordTest {
	private static final AttributeIndexKey KEY = new AttributeIndexKey(null, "a", null);

	@Nested
	@DisplayName("Absence")
	class AbsenceTest {

		@Test
		@DisplayName("an empty index holds no value for any record")
		void shouldReturnAbsenceFromAnEmptyIndex() {
			final SortIndex sortIndex = new OwnerSortIndex(
				Integer.class,
				null,
				KEY,
				0,
				SortIndex.createTypeLevelNormalizerFor(Integer.class, 0)
			);

			assertEquals(Optional.empty(), sortIndex.getValueOfRecord(1));
		}

		@Test
		@DisplayName("a record the index does not hold is absent, not an error")
		void shouldReturnAbsenceForARecordTheIndexDoesNotHold() {
			final SortIndex sortIndex = new OwnerSortIndex(
				Integer.class,
				null,
				KEY,
				0,
				SortIndex.createTypeLevelNormalizerFor(Integer.class, 0)
			);
			sortIndex.addRecord(10, 1);
			sortIndex.addRecord(20, 2);

			assertEquals(Optional.empty(), sortIndex.getValueOfRecord(3));
			assertEquals(Optional.empty(), sortIndex.getValueOfRecord(-1));
		}

		@Test
		@DisplayName("a removed record is absent")
		void shouldReturnAbsenceForARemovedRecord() {
			final SortIndex sortIndex = new OwnerSortIndex(
				Integer.class,
				null,
				KEY,
				0,
				SortIndex.createTypeLevelNormalizerFor(Integer.class, 0)
			);
			sortIndex.addRecord(10, 1);
			sortIndex.addRecord(10, 2);
			sortIndex.removeRecord(10, 1);

			assertEquals(Optional.empty(), sortIndex.getValueOfRecord(1));
			assertEquals(Optional.of(10), sortIndex.getValueOfRecord(2));
		}
	}

	@Nested
	@DisplayName("Owner mode")
	class OwnerModeTest {

		@Test
		@DisplayName("every record, in every block, reads the value of its own block")
		void shouldReturnTheValueOfEveryRecordsBlock() {
			final SortIndex sortIndex = new OwnerSortIndex(
				Integer.class,
				null,
				KEY,
				0,
				SortIndex.createTypeLevelNormalizerFor(Integer.class, 0)
			);
			sortIndex.addRecord(30, 5);
			sortIndex.addRecord(10, 3);
			sortIndex.addRecord(20, 1);
			sortIndex.addRecord(10, 4);
			sortIndex.addRecord(30, 2);

			assertEquals(Optional.of(20), sortIndex.getValueOfRecord(1));
			assertEquals(Optional.of(30), sortIndex.getValueOfRecord(2));
			assertEquals(Optional.of(10), sortIndex.getValueOfRecord(3));
			assertEquals(Optional.of(10), sortIndex.getValueOfRecord(4));
			assertEquals(Optional.of(30), sortIndex.getValueOfRecord(5));
		}

		@Test
		@DisplayName("a locale and a currency come back in their comparable form")
		void shouldReturnTheNormalizedFormOfWrappedValues() {
			final SortIndex locales = new OwnerSortIndex(
				Locale.class,
				null,
				KEY,
				0,
				SortIndex.createTypeLevelNormalizerFor(Locale.class, 0)
			);
			locales.addRecord(Locale.GERMAN, 1);
			locales.addRecord(Locale.ENGLISH, 2);
			final SortIndex currencies = new OwnerSortIndex(
				Currency.class,
				null,
				KEY,
				0,
				SortIndex.createTypeLevelNormalizerFor(Currency.class, 0)
			);
			currencies.addRecord(Currency.getInstance("EUR"), 1);
			currencies.addRecord(Currency.getInstance("CZK"), 2);

			assertEquals(Optional.of(new ComparableLocale(Locale.GERMAN)), locales.getValueOfRecord(1));
			assertEquals(Optional.of(new ComparableLocale(Locale.ENGLISH)), locales.getValueOfRecord(2));
			assertEquals(
				Optional.of(new ComparableCurrency(Currency.getInstance("CZK"))), currencies.getValueOfRecord(2)
			);
		}

		@Test
		@DisplayName("an offset date time comes back as the instant it is ordered by")
		void shouldReturnTheInstantOfAnOffsetDateTime() {
			final SortIndex sortIndex = new OwnerSortIndex(
				OffsetDateTime.class,
				null,
				KEY,
				0,
				SortIndex.createTypeLevelNormalizerFor(OffsetDateTime.class, 0)
			);
			final OffsetDateTime moment = OffsetDateTime.of(2026, 10, 2, 12, 0, 0, 0, ZoneOffset.ofHours(2));
			sortIndex.addRecord(moment, 1);

			assertEquals(Optional.of(moment.toInstant()), sortIndex.getValueOfRecord(1));
		}

		@Test
		@DisplayName("a compound with a descending, a nulls-first and a missing element comes back whole")
		void shouldReturnTheWholeCompound() {
			final SortIndex sortIndex = new OwnerSortIndex(new ComparatorSource[]{
					new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
					new ComparatorSource(Integer.class, OrderDirection.DESC, OrderBehaviour.NULLS_LAST),
					new ComparatorSource(Locale.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
				}, null, KEY, SortIndex.createTypeLevelNormalizerFor(new ComparatorSource[]{
					new ComparatorSource(String.class, OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
					new ComparatorSource(Integer.class, OrderDirection.DESC, OrderBehaviour.NULLS_LAST),
					new ComparatorSource(Locale.class, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
				}, 0));
			sortIndex.addRecord(new Serializable[]{"B", 1, Locale.ENGLISH}, 1);
			sortIndex.addRecord(new Serializable[]{null, 3, null}, 2);
			sortIndex.addRecord(new Serializable[]{"B", 9, Locale.ENGLISH}, 3);
			sortIndex.addRecord(new Serializable[]{"B", 1, Locale.ENGLISH}, 4);

			assertEquals(
				Optional.of(new ComparableArray(new Serializable[]{"B", 1, new ComparableLocale(Locale.ENGLISH)})),
				sortIndex.getValueOfRecord(1)
			);
			assertEquals(
				Optional.of(new ComparableArray(new Serializable[]{null, 3, null})), sortIndex.getValueOfRecord(2)
			);
			assertEquals(
				Optional.of(new ComparableArray(new Serializable[]{"B", 9, new ComparableLocale(Locale.ENGLISH)})),
				sortIndex.getValueOfRecord(3)
			);
			assertEquals(sortIndex.getValueOfRecord(1), sortIndex.getValueOfRecord(4));
		}
	}

	@Nested
	@DisplayName("View mode")
	class ViewModeTest {

		@Test
		@DisplayName("a view reads the value of the shared tree's bucket, in the shared tree's value space")
		void shouldReturnTheSharedTreesValue() {
			final InvertedIndex shared = new InvertedIndex(
				FilterIndex.getNormalizer(OffsetDateTime.class, 0),
				FilterIndex.getComparator(KEY, OffsetDateTime.class)
			);
			final SortIndex view = SortIndex.create(
				OffsetDateTime.class,
				null,
				KEY,
				0,
				() -> shared,
				SortIndex.createTypeLevelNormalizerFor(OffsetDateTime.class, 0)
			);
			assertInstanceOf(SortIndexView.class, view);
			final OffsetDateTime first = OffsetDateTime.of(2026, 10, 2, 12, 0, 0, 0, ZoneOffset.UTC);
			final OffsetDateTime second = first.plusHours(1);
			// the coarse orchestration order: the sort block reads the pristine shared tree, the filter block writes it
			insert(view, shared, second, 7);
			insert(view, shared, first, 3);
			insert(view, shared, second.withOffsetSameInstant(ZoneOffset.ofHours(5)), 1);

			assertEquals(Optional.of(first.toInstant()), view.getValueOfRecord(3));
			assertEquals(Optional.of(second.toInstant()), view.getValueOfRecord(7));
			assertEquals(Optional.of(second.toInstant()), view.getValueOfRecord(1));
			assertEquals(Optional.empty(), view.getValueOfRecord(2));
			final Serializable value = view.getValueOfRecord(3).orElseThrow();
			assertInstanceOf(Instant.class, value);
		}

		/**
		 * Inserts a record into a view the way the entity index does: the view first, then the shared tree.
		 *
		 * @param view     the view
		 * @param shared   the shared tree
		 * @param value    the value
		 * @param recordId the record
		 */
		private static void insert(
			@Nonnull SortIndex view,
			@Nonnull InvertedIndex shared,
			@Nonnull OffsetDateTime value,
			int recordId
		) {
			view.addRecord(value, recordId);
			shared.addRecord(value, recordId);
		}
	}

	@Nested
	@Tag(TRANSACTION)
	@DisplayName("Inside a transaction")
	class TransactionTest {

		@Test
		@DisplayName("the open transaction reads its own writes, the committed index keeps its own values")
		void shouldReadTheTransactionsOwnWrites() {
			final SortIndex sortIndex = new OwnerSortIndex(
				Integer.class,
				null,
				KEY,
				0,
				SortIndex.createTypeLevelNormalizerFor(Integer.class, 0)
			);
			sortIndex.addRecord(10, 1);
			sortIndex.addRecord(20, 2);

			assertStateAfterCommit(
				sortIndex,
				original -> {
					original.removeRecord(10, 1);
					original.addRecord(5, 3);
					original.addRecord(20, 4);

					assertEquals(Optional.empty(), original.getValueOfRecord(1));
					assertEquals(Optional.of(20), original.getValueOfRecord(2));
					assertEquals(Optional.of(5), original.getValueOfRecord(3));
					assertEquals(Optional.of(20), original.getValueOfRecord(4));
				},
				(original, committed) -> {
					assertEquals(Optional.of(10), original.getValueOfRecord(1));
					assertEquals(Optional.empty(), original.getValueOfRecord(3));
					assertEquals(Optional.empty(), committed.getValueOfRecord(1));
					assertEquals(Optional.of(5), committed.getValueOfRecord(3));
					assertEquals(Optional.of(20), committed.getValueOfRecord(4));
					assertTrue(committed.getValueOfRecord(2).isPresent());
				}
			);
		}
	}
}
