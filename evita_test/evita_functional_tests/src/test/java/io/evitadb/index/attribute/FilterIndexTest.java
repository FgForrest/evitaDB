/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.ComparableCurrency;
import io.evitadb.dataType.ComparableLocale;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.LongNumberRange;
import io.evitadb.dataType.NumberRange;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.invertedIndex.InvertedIndexSubSet;
import io.evitadb.index.invertedIndex.ValueToRecordBitmap;
import io.evitadb.index.range.RangePoint;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.FILTER;

/**
 * This test verifies {@link FilterIndex} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("FilterIndex functionality")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@Tag(FILTER)
class FilterIndexTest {
	private final OwnerFilterIndex stringAttribute = new OwnerFilterIndex(new AttributeIndexKey(null, "a", null), String.class);
	private final OwnerFilterIndex rangeAttribute = new OwnerFilterIndex(new AttributeIndexKey(null, "b", null), NumberRange.class);

	@Test
	void filterIndexValidNowDelegatesToRangeIndexCachedPath() {
		final OwnerFilterIndex filterIndex = new OwnerFilterIndex(new AttributeIndexKey(null, "validity", null), DateTimeRange.class);
		filterIndex.addRecord(1, DateTimeRange.between(
			OffsetDateTime.parse("2026-01-01T00:00:00Z"),
			OffsetDateTime.parse("2026-12-31T23:59:59Z")
		));
		filterIndex.addRecord(2, DateTimeRange.between(
			OffsetDateTime.parse("2026-06-01T00:00:00Z"),
			OffsetDateTime.parse("2026-07-31T23:59:59Z")
		));
		final long now = OffsetDateTime.parse("2026-07-01T00:00:00Z").toEpochSecond();

		final var firstBitmap = filterIndex.getRecordsValidNowFormula(now).compute();
		final var secondBitmap = filterIndex.getRecordsValidNowFormula(now).compute();

		assertArrayEquals(new int[]{1, 2}, firstBitmap.getArray());
		assertArrayEquals(new int[]{1, 2}, secondBitmap.getArray());
		// cached path returns ConstantFormula wrapping the same memoized Bitmap reference
		assertSame(firstBitmap, secondBitmap);
	}

	@Test
	void filterIndexValidInUncachedPathProducesFreshBitmapEachCall() {
		final OwnerFilterIndex filterIndex = new OwnerFilterIndex(new AttributeIndexKey(null, "validity", null), DateTimeRange.class);
		filterIndex.addRecord(1, DateTimeRange.between(
			OffsetDateTime.parse("2026-01-01T00:00:00Z"),
			OffsetDateTime.parse("2026-12-31T23:59:59Z")
		));
		filterIndex.addRecord(2, DateTimeRange.between(
			OffsetDateTime.parse("2026-06-01T00:00:00Z"),
			OffsetDateTime.parse("2026-07-31T23:59:59Z")
		));
		final long moment = OffsetDateTime.parse("2026-07-01T00:00:00Z").toEpochSecond();

		final var firstBitmap = filterIndex.getRecordsValidInFormula(moment).compute();
		final var secondBitmap = filterIndex.getRecordsValidInFormula(moment).compute();

		assertArrayEquals(new int[]{1, 2}, firstBitmap.getArray());
		assertArrayEquals(new int[]{1, 2}, secondBitmap.getArray());
		// uncached path builds a fresh formula tree and bitmap each call
		assertNotSame(firstBitmap, secondBitmap);
	}

	@Test
	void getAllRecordsReturnsStableBitmapAcrossCalls() {
		this.stringAttribute.addRecord(1, "A");
		this.stringAttribute.addRecord(2, "B");
		this.stringAttribute.addRecord(3, "C");

		final var firstBitmap = this.stringAttribute.getAllRecords();
		final var secondBitmap = this.stringAttribute.getAllRecords();

		assertArrayEquals(new int[]{1, 2, 3}, firstBitmap.getArray());
		// AttributeIsTranslator wraps this bitmap directly in ConstantFormula so the planner
		// cannot redistribute the inverted-index OR via DeMorgan; the contract is reference stability.
		assertSame(firstBitmap, secondBitmap);
	}

	@Test
	void getAllRecordsInvalidatesOnNonTxMutation() {
		this.stringAttribute.addRecord(1, "A");
		this.stringAttribute.addRecord(2, "B");
		final var firstBitmap = this.stringAttribute.getAllRecords();
		assertArrayEquals(new int[]{1, 2}, firstBitmap.getArray());

		this.stringAttribute.addRecord(3, "C");
		final var afterMutationBitmap = this.stringAttribute.getAllRecords();

		assertArrayEquals(new int[]{1, 2, 3}, afterMutationBitmap.getArray());
		assertNotSame(firstBitmap, afterMutationBitmap);
	}

	@Test
	void shouldInsertNewStringRecordId() {
		this.stringAttribute.addRecord(1, "A");
		this.stringAttribute.addRecord(2, new String[] {"A", "B"});
		assertArrayEquals(new int[] {1, 2}, this.stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {2}, this.stringAttribute.getRecordsEqualTo("B").getArray());
		assertEquals(2, this.stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldInsertNewStringRecordIdInTheMiddle() {
		this.stringAttribute.addRecord(1, "A");
		this.stringAttribute.addRecord(3, "C");
		assertArrayEquals(new int[] {1}, this.stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {3}, this.stringAttribute.getRecordsEqualTo("C").getArray());
		assertEquals(2, this.stringAttribute.getAllRecords().size());
		this.stringAttribute.addRecord(2, "B");
		assertArrayEquals(new int[] {1}, this.stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {2}, this.stringAttribute.getRecordsEqualTo("B").getArray());
		assertArrayEquals(new int[] {3}, this.stringAttribute.getRecordsEqualTo("C").getArray());
		assertEquals(3, this.stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldInsertNewStringRecordIdInTheBeginning() {
		this.stringAttribute.addRecord(1, "C");
		this.stringAttribute.addRecord(2, "B");
		this.stringAttribute.addRecord(3, "A");

		assertArrayEquals(new int[] {3}, this.stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {2}, this.stringAttribute.getRecordsEqualTo("B").getArray());
		assertArrayEquals(new int[] {1}, this.stringAttribute.getRecordsEqualTo("C").getArray());
		assertEquals(3, this.stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldInsertNewRangeRecord() {
		this.rangeAttribute.addRecord(1, IntegerNumberRange.between(5, 10));
		this.rangeAttribute.addRecord(2, IntegerNumberRange.between(11, 20));
		this.rangeAttribute.addRecord(3, IntegerNumberRange.between(5, 15));

		assertArrayEquals(new int[] {1}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(5, 10)).getArray());
		assertArrayEquals(new int[] {2}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(11, 20)).getArray());
		assertArrayEquals(new int[] {3}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(5, 15)).getArray());
		assertEquals(3, this.rangeAttribute.getAllRecords().size());
	}

	@Test
	void shouldRemoveStringRecordId() {
		fillStringAttribute();
		this.stringAttribute.removeRecord(1, new String[] {"A", "C"});

		assertArrayEquals(new int[] {2}, this.stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {1, 2}, this.stringAttribute.getRecordsEqualTo("B").getArray());
		assertArrayEquals(new int[] {3}, this.stringAttribute.getRecordsEqualTo("C").getArray());
		assertArrayEquals(new int[] {4}, this.stringAttribute.getRecordsEqualTo("D").getArray());
		assertEquals(4, this.stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldRemoveStringRecordIdRemovingFirstBucket() {
		fillStringAttribute();
		this.stringAttribute.removeRecord(1, "A");
		this.stringAttribute.removeRecord(2, "A");

		assertArrayEquals(new int[] {1, 2}, this.stringAttribute.getRecordsEqualTo("B").getArray());
		assertArrayEquals(new int[] {1, 3}, this.stringAttribute.getRecordsEqualTo("C").getArray());
		assertArrayEquals(new int[] {4}, this.stringAttribute.getRecordsEqualTo("D").getArray());
		assertEquals(4, this.stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldRemoveStringRecordIdRemovingLastBucket() {
		fillStringAttribute();
		this.stringAttribute.removeRecord(4, "D");

		assertArrayEquals(new int[] {1, 2}, this.stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {1, 2}, this.stringAttribute.getRecordsEqualTo("B").getArray());
		assertArrayEquals(new int[] {1, 3}, this.stringAttribute.getRecordsEqualTo("C").getArray());
		assertEquals(3, this.stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldRemoveStringRecordIdRemovingMiddleBuckets() {
		fillStringAttribute();
		this.stringAttribute.removeRecord(1, new String[] {"B", "C"});
		this.stringAttribute.removeRecord(2, "B");
		this.stringAttribute.removeRecord(3, "C");

		assertArrayEquals(new int[] {1, 2}, this.stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[] {4}, this.stringAttribute.getRecordsEqualTo("D").getArray());
		assertEquals(3, this.stringAttribute.getAllRecords().size());
	}

	@Test
	void shouldRemoveRangeRecord() {
		fillRangeAttribute();
		this.rangeAttribute.removeRecord(1, IntegerNumberRange.between(5, 10));

		assertArrayEquals(new int[] {1}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(50, 90)).getArray());
		assertArrayEquals(new int[] {2}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(11, 20)).getArray());
		assertArrayEquals(new int[] {3}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(5, 15)).getArray());
		assertEquals(3, this.rangeAttribute.getAllRecords().size());
	}

	@Test
	void shouldRemoveRangeRecordRemovingBucket() {
		fillRangeAttribute();
		this.rangeAttribute.removeRecord(1, new IntegerNumberRange[] {IntegerNumberRange.between(5, 10), IntegerNumberRange.between(50, 90)});
		this.rangeAttribute.removeRecord(3, IntegerNumberRange.between(5, 15));

		assertArrayEquals(new int[] {2}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(11, 20)).getArray());
		assertEquals(1, this.rangeAttribute.getAllRecords().size());
	}

	@Test
	void shouldReturnAllRecords() {
		fillStringAttribute();
		assertArrayEquals(new int[] {1, 2, 3, 4}, this.stringAttribute.getAllRecords().getArray());
	}

	@Test
	void shouldReturnRecordsStartingWith() {
		// generate records to verify starts with function
		this.stringAttribute.addRecord(1, "Alfa");
		this.stringAttribute.addRecord(2, "AlfaBeta");
		this.stringAttribute.addRecord(3, "Alfeta");
		this.stringAttribute.addRecord(4, "Ab");
		this.stringAttribute.addRecord(5, "Beta");
		this.stringAttribute.addRecord(6, "Betaversion");
		this.stringAttribute.addRecord(7, "Bet");
		this.stringAttribute.addRecord(8, "Betamax");
		this.stringAttribute.addRecord(9, "Gamma");
		this.stringAttribute.addRecord(10, "GammaAlfa");
		this.stringAttribute.addRecord(11, "GammaBeta");

		assertArrayEquals(new int[] {1, 2}, this.stringAttribute.getRecordsWhoseValuesStartWith("Alfa").compute().getArray());
		assertArrayEquals(new int[] {4}, this.stringAttribute.getRecordsWhoseValuesStartWith("Ab").compute().getArray());
		assertArrayEquals(new int[] {5, 6, 7, 8}, this.stringAttribute.getRecordsWhoseValuesStartWith("Bet").compute().getArray());
		assertArrayEquals(new int[] {5, 6, 8}, this.stringAttribute.getRecordsWhoseValuesStartWith("Beta").compute().getArray());
		assertArrayEquals(new int[] {9, 10, 11}, this.stringAttribute.getRecordsWhoseValuesStartWith("Gamma").compute().getArray());
		assertArrayEquals(new int[] {11}, this.stringAttribute.getRecordsWhoseValuesStartWith("GammaBeta").compute().getArray());
	}

	@Test
	void shouldReturnRecordsGreaterThan() {
		fillStringAttribute();
		assertArrayEquals(new int[] {1, 3, 4}, this.stringAttribute.getRecordsGreaterThan("B").getArray());
	}

	@Test
	void shouldReturnRecordsGreaterThanEq() {
		fillStringAttribute();
		assertArrayEquals(new int[] {1, 3, 4}, this.stringAttribute.getRecordsGreaterThanEq("C").getArray());
	}

	@Test
	void shouldReturnRecordsLesserThan() {
		fillStringAttribute();
		assertArrayEquals(new int[] {1, 2}, this.stringAttribute.getRecordsLesserThan("C").getArray());
	}

	@Test
	void shouldReturnRecordsLesserThanEq() {
		fillStringAttribute();
		assertArrayEquals(new int[] {1, 2}, this.stringAttribute.getRecordsLesserThanEq("B").getArray());
	}

	@Test
	void shouldReturnRecordsLesserThanLocaleSpecific_Czech() {
		OwnerFilterIndex czechStringAttribute = new OwnerFilterIndex(new AttributeIndexKey(null, "a", new Locale("cs", "CZ")), String.class);
		czechStringAttribute.addRecord(1, "CH");
		czechStringAttribute.addRecord(2, "E");
		czechStringAttribute.addRecord(3, "K");
		czechStringAttribute.addRecord(4, "D");
		czechStringAttribute.addRecord(5, "C");
		czechStringAttribute.addRecord(6, "B");
		assertArrayEquals(new int[] {2, 4, 5, 6}, czechStringAttribute.getRecordsLesserThan("CH").getArray());
	}

	@Test
	void shouldReturnRecordsLesserThanLocaleSpecific_English() {
		OwnerFilterIndex czechStringAttribute = new OwnerFilterIndex(new AttributeIndexKey(null, "a", Locale.ENGLISH), String.class);
		czechStringAttribute.addRecord(1, "CH");
		czechStringAttribute.addRecord(2, "E");
		czechStringAttribute.addRecord(3, "K");
		czechStringAttribute.addRecord(4, "D");
		czechStringAttribute.addRecord(5, "C");
		czechStringAttribute.addRecord(6, "B");
		assertArrayEquals(new int[] {5, 6}, czechStringAttribute.getRecordsLesserThan("CH").getArray());
	}

	@Test
	void shouldReturnRecordsBetween() {
		fillStringAttribute();
		assertArrayEquals(new int[]{1, 3, 4}, this.stringAttribute.getRecordsBetween("C", "D").getArray());
	}

	@Test
	void shouldReturnRecordsValidIn() {
		fillRangeAttribute();
		assertArrayEquals(new int[]{1, 3}, this.rangeAttribute.getRecordsValidIn(8L).getArray());
	}

	@Test
	void shouldIndexDeltaRanges() {
		fillRangeAttribute();
		this.rangeAttribute.addRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(2, 5), IntegerNumberRange.between(20, 30)});
		this.rangeAttribute.addRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(90, 100)});
		this.rangeAttribute.addRecordDelta(2, new IntegerNumberRange[] {IntegerNumberRange.between(20, 30)});

		assertArrayEquals(new int[] {1, 2}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(20, 30)).getArray());
		final RangePoint<?>[] ranges = this.rangeAttribute.getRangeIndex().getRanges();
		assertEquals(
			"""
					TransactionalRangePoint{threshold=-9223372036854775808, starts=[], ends=[]}
					TransactionalRangePoint{threshold=2, starts=[1], ends=[]}
					TransactionalRangePoint{threshold=5, starts=[3], ends=[]}
					TransactionalRangePoint{threshold=10, starts=[], ends=[1]}
					TransactionalRangePoint{threshold=11, starts=[2], ends=[]}
					TransactionalRangePoint{threshold=15, starts=[], ends=[3]}
					TransactionalRangePoint{threshold=20, starts=[1], ends=[]}
					TransactionalRangePoint{threshold=30, starts=[], ends=[1, 2]}
					TransactionalRangePoint{threshold=50, starts=[1], ends=[]}
					TransactionalRangePoint{threshold=100, starts=[], ends=[1]}
					TransactionalRangePoint{threshold=9223372036854775807, starts=[], ends=[]}""",
			Arrays.stream(ranges).map(Object::toString).collect(Collectors.joining("\n"))
		);
	}

	@Test
	void shouldFailToRemoveNonExistingRange() {
		fillRangeAttribute();
		this.rangeAttribute.addRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(2, 5), IntegerNumberRange.between(20, 30)});
		this.rangeAttribute.addRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(90, 100)});
		this.rangeAttribute.addRecordDelta(2, new IntegerNumberRange[] {IntegerNumberRange.between(20, 30)});

		assertThrows(
			IllegalArgumentException.class,
			() -> this.rangeAttribute.removeRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(4, 6)})
		);
	}

	@Test
	void shouldRemoveIndexedDeltaRanges() {
		fillRangeAttribute();
		this.rangeAttribute.addRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(2, 5), IntegerNumberRange.between(20, 30)});
		this.rangeAttribute.addRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(90, 100)});
		this.rangeAttribute.addRecordDelta(2, new IntegerNumberRange[] {IntegerNumberRange.between(20, 30)});

		this.rangeAttribute.removeRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(5, 10)});
		this.rangeAttribute.removeRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(20, 30)});
		this.rangeAttribute.removeRecordDelta(1, new IntegerNumberRange[] {IntegerNumberRange.between(90, 100)});

		assertArrayEquals(new int[] {2}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(20, 30)).getArray());
		final RangePoint<?>[] ranges = this.rangeAttribute.getRangeIndex().getRanges();
		assertEquals(
			"""
			TransactionalRangePoint{threshold=-9223372036854775808, starts=[], ends=[]}
			TransactionalRangePoint{threshold=2, starts=[1], ends=[]}
			TransactionalRangePoint{threshold=5, starts=[3], ends=[1]}
			TransactionalRangePoint{threshold=11, starts=[2], ends=[]}
			TransactionalRangePoint{threshold=15, starts=[], ends=[3]}
			TransactionalRangePoint{threshold=30, starts=[], ends=[2]}
			TransactionalRangePoint{threshold=50, starts=[1], ends=[]}
			TransactionalRangePoint{threshold=90, starts=[], ends=[1]}
			TransactionalRangePoint{threshold=9223372036854775807, starts=[], ends=[]}""",
			Arrays.stream(ranges).map(Object::toString).collect(Collectors.joining("\n"))
		);
	}

	@Nested
	@DisplayName("STM invariants")
	class StmInvariantsTest {

		@Test
		@DisplayName("each instance has a unique id")
		void shouldHaveUniqueIdAcrossInstances() {
			final OwnerFilterIndex first = new OwnerFilterIndex(
				new AttributeIndexKey(null, "x", null), String.class
			);
			final OwnerFilterIndex second = new OwnerFilterIndex(
				new AttributeIndexKey(null, "y", null), String.class
			);

			assertNotEquals(first.getId(), second.getId());
		}

		@Test
		@DisplayName("committed copy is a new instance")
		void shouldReturnNewInstanceOnCommit() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			index.addRecord(1, "Alpha");

			assertStateAfterCommit(
				index,
				original -> original.addRecord(2, "Beta"),
				(original, committed) -> {
					assertNotSame(original, committed);
					assertArrayEquals(
						new int[]{1, 2},
						committed.getAllRecords().getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("original unchanged after commit")
		void shouldLeaveOriginalUnchangedAfterCommit() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			index.addRecord(1, "Alpha");

			assertStateAfterCommit(
				index,
				original -> original.addRecord(2, "Beta"),
				(original, committed) -> {
					// original should still have only the initial record
					assertArrayEquals(
						new int[]{1},
						original.getAllRecords().getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("rollback discards transactional mutations")
		void shouldDiscardMutationsOnRollback() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			index.addRecord(1, "Alpha");

			assertStateAfterRollback(
				index,
				original -> {
					original.addRecord(2, "Beta");
					original.addRecord(3, "Gamma");
				},
				(original, committed) -> {
					// committed should be null after rollback
					assertNull(committed);
					// original should be unmodified
					assertArrayEquals(
						new int[]{1},
						original.getAllRecords().getArray()
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Non-transactional cache invalidation")
	class NonTransactionalCacheTest {

		@Test
		@DisplayName("memoized formula is invalidated on non-tx write")
		void shouldInvalidateMemoizedFormulaOnNonTransactionalWrite() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			index.addRecord(1, "A");

			// first call caches the formula
			final Formula firstCall = index.getAllRecordsFormula();
			// second call returns same cached instance
			final Formula secondCall = index.getAllRecordsFormula();
			assertSame(firstCall, secondCall);

			// write invalidates the cache
			index.addRecord(2, "B");
			final Formula afterWrite = index.getAllRecordsFormula();
			assertNotSame(firstCall, afterWrite);
			assertArrayEquals(
				new int[]{1, 2},
				afterWrite.compute().getArray()
			);
		}
	}

	@Nested
	@DisplayName("Formula and memoization")
	class FormulaMemoizationTest {

		@Test
		@DisplayName("getAllRecordsFormula returns same instance when no writes")
		void shouldReturnSameFormulaInstanceWhenNoWrites() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			index.addRecord(1, "A");
			index.addRecord(2, "B");

			final Formula first = index.getAllRecordsFormula();
			final Formula second = index.getAllRecordsFormula();

			assertSame(first, second);
		}

		@Test
		@DisplayName(
			"getAllRecordsFormula bypasses cache in dirty transaction"
		)
		void shouldBypassCacheInDirtyTransaction() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			index.addRecord(1, "A");

			assertStateAfterCommit(
				index,
				original -> {
					original.addRecord(2, "B");
					// inside tx with dirty=true, formula should reflect change
					final Formula formula = original.getAllRecordsFormula();
					assertArrayEquals(
						new int[]{1, 2},
						formula.compute().getArray()
					);
				},
				(original, committed) -> {
					assertArrayEquals(
						new int[]{1, 2},
						committed.getAllRecords().getArray()
					);
				}
			);
		}

		@Test
		@DisplayName(
			"getRecordsEqualToFormula returns EmptyFormula for missing value"
		)
		void shouldReturnEmptyFormulaForMissingValue() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			index.addRecord(1, "A");

			final Formula formula = index.getRecordsEqualToFormula("NONEXISTENT");

			assertSame(EmptyFormula.INSTANCE, formula);
		}
	}

	@Nested
	@DisplayName("String query methods")
	class StringQueryMethodsTest {

		@Test
		@DisplayName("getRecordsWhoseValuesEndsWith finds matching records")
		void shouldReturnRecordsEndingWithSuffix() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			index.addRecord(1, "Hello");
			index.addRecord(2, "World");
			index.addRecord(3, "Jello");
			index.addRecord(4, "Test");

			final Formula result = index.getRecordsWhoseValuesEndsWith("llo");

			assertArrayEquals(
				new int[]{1, 3},
				result.compute().getArray()
			);
		}

		@Test
		@DisplayName(
			"getRecordsWhoseValuesEndsWith returns EmptyFormula when no match"
		)
		void shouldReturnEmptyFormulaForNoEndsWithMatch() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			index.addRecord(1, "Hello");

			final Formula result = index.getRecordsWhoseValuesEndsWith("xyz");

			assertSame(EmptyFormula.INSTANCE, result);
		}

		@Test
		@DisplayName("getRecordsWhoseValuesContains finds matching records")
		void shouldReturnRecordsContainingText() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			index.addRecord(1, "Alphabet");
			index.addRecord(2, "Beta");
			index.addRecord(3, "Alpha");
			index.addRecord(4, "Gamma");

			final Formula result =
				index.getRecordsWhoseValuesContains("lpha");

			assertArrayEquals(
				new int[]{1, 3},
				result.compute().getArray()
			);
		}

		@Test
		@DisplayName(
			"getRecordsWhoseValuesContains returns EmptyFormula when no match"
		)
		void shouldReturnEmptyFormulaForNoContainsMatch() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			index.addRecord(1, "Hello");

			final Formula result =
				index.getRecordsWhoseValuesContains("xyz");

			assertSame(EmptyFormula.INSTANCE, result);
		}

		@Test
		@DisplayName(
			"getRecordsWhoseValuesStartWith returns EmptyFormula for no match"
		)
		void shouldReturnEmptyFormulaForNoStartsWithMatch() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			index.addRecord(1, "Hello");
			index.addRecord(2, "World");

			final Formula result =
				index.getRecordsWhoseValuesStartWith("Xyz");

			assertSame(EmptyFormula.INSTANCE, result);
		}
	}

	@Nested
	@DisplayName("Range-index query methods")
	class RangeIndexQueryTest {

		@Test
		@DisplayName("getRecordsOverlapping finds overlapping ranges")
		void shouldReturnRecordsOverlappingRange() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "r", null),
				NumberRange.class
			);
			index.addRecord(1, IntegerNumberRange.between(5, 10));
			index.addRecord(2, IntegerNumberRange.between(15, 20));
			index.addRecord(3, IntegerNumberRange.between(8, 18));

			assertArrayEquals(
				new int[]{1, 3},
				index.getRecordsOverlapping(6L, 9L).getArray()
			);
		}

		@Test
		@DisplayName("getRecordsOverlappingFormula finds overlapping ranges")
		void shouldReturnOverlappingFormula() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "r", null),
				NumberRange.class
			);
			index.addRecord(1, IntegerNumberRange.between(5, 10));
			index.addRecord(2, IntegerNumberRange.between(15, 20));
			index.addRecord(3, IntegerNumberRange.between(8, 18));

			final Formula formula =
				index.getRecordsOverlappingFormula(6L, 9L);

			assertArrayEquals(
				new int[]{1, 3},
				formula.compute().getArray()
			);
		}

		@Test
		@DisplayName(
			"getRecordsValidIn on non-range index throws exception"
		)
		void shouldThrowWhenValidInCalledOnNonRangeIndex() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.getRecordsValidIn(10L)
			);
		}

		@Test
		@DisplayName(
			"getRecordsValidInFormula on non-range index throws exception"
		)
		void shouldThrowWhenValidInFormulaCalledOnNonRangeIndex() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.getRecordsValidInFormula(10L)
			);
		}

		@Test
		@DisplayName(
			"getRecordsOverlapping on non-range index throws exception"
		)
		void shouldThrowWhenOverlappingCalledOnNonRangeIndex() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.getRecordsOverlapping(1L, 5L)
			);
		}

		@Test
		@DisplayName(
			"getRecordsOverlappingFormula on non-range index throws"
		)
		void shouldThrowWhenOverlappingFormulaCalledOnNonRangeIndex() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.getRecordsOverlappingFormula(1L, 5L)
			);
		}
	}

	@Nested
	@DisplayName("Error handling for add/remove with wrong types")
	class ErrorHandlingTest {

		@Test
		@DisplayName(
			"addRecord with non-Range value on range index throws"
		)
		void shouldThrowWhenAddingNonRangeValueToRangeIndex() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "r", null),
				NumberRange.class
			);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.addRecord(1, "notARange")
			);
		}

		@Test
		@DisplayName(
			"removeRecord with non-Range value on range index throws"
		)
		void shouldThrowWhenRemovingNonRangeValueFromRangeIndex() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "r", null),
				NumberRange.class
			);
			index.addRecord(1, IntegerNumberRange.between(5, 10));

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.removeRecord(1, "notARange")
			);
		}

		@Test
		@DisplayName(
			"addRecordDelta with non-Range array on range index throws"
		)
		void shouldThrowWhenAddingNonRangeDeltaToRangeIndex() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "r", null),
				NumberRange.class
			);
			index.addRecord(1, IntegerNumberRange.between(5, 10));

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.addRecordDelta(
					1, new String[]{"notARange"}
				)
			);
		}

		@Test
		@DisplayName(
			"removeRecordDelta with non-Range array on range index throws"
		)
		void shouldThrowWhenRemovingNonRangeDeltaFromRangeIndex() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "r", null),
				NumberRange.class
			);
			index.addRecord(1, IntegerNumberRange.between(5, 10));

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.removeRecordDelta(
					1, new String[]{"notARange"}
				)
			);
		}
	}

	@SuppressWarnings("rawtypes")
	@Nested
	@DisplayName("Normalizer and comparator")
	class NormalizerComparatorTest {

		@Test
		@DisplayName(
			"getNormalizer for OffsetDateTime converts to Instant"
		)
		void shouldNormalizeOffsetDateTimeToInstant() {
			final Function<Object, Serializable> normalizer =
				FilterIndex.getNormalizer(OffsetDateTime.class);
			final OffsetDateTime odt =
				OffsetDateTime.of(2025, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC);

			final Serializable result = normalizer.apply(odt);

			assertInstanceOf(Instant.class, result);
			assertEquals(odt.toInstant(), result);
		}

		@Test
		@DisplayName(
			"getNormalizer for Currency wraps into ComparableCurrency"
		)
		void shouldNormalizeCurrencyToComparableCurrency() {
			final Function<Object, Serializable> normalizer =
				FilterIndex.getNormalizer(Currency.class);
			final Currency usd = Currency.getInstance("USD");

			final Serializable result = normalizer.apply(usd);

			assertInstanceOf(ComparableCurrency.class, result);
		}

		@Test
		@DisplayName(
			"getNormalizer for Locale wraps into ComparableLocale"
		)
		void shouldNormalizeLocaleToComparableLocale() {
			final Function<Object, Serializable> normalizer =
				FilterIndex.getNormalizer(Locale.class);

			final Serializable result = normalizer.apply(Locale.ENGLISH);

			assertInstanceOf(ComparableLocale.class, result);
		}

		@Test
		@DisplayName(
			"getNormalizer for Comparable type returns NO_NORMALIZATION"
		)
		void shouldReturnNoNormalizationForComparableType() {
			final Function<Object, Serializable> normalizer =
				FilterIndex.getNormalizer(Integer.class);

			assertSame(FilterIndex.NO_NORMALIZATION, normalizer);
		}

		@Test
		@DisplayName(
			"getNormalizer throws for unsupported non-Comparable type"
		)
		void shouldThrowForUnsupportedType() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> FilterIndex.getNormalizer(Object.class)
			);
		}

		@Test
		@DisplayName(
			"getComparator returns LocalizedStringComparator for localized"
		)
		void shouldReturnLocalizedComparatorForLocalizedString() {
			final AttributeIndexKey key =
				new AttributeIndexKey(null, "a", Locale.ENGLISH);

			final Comparator<? extends Comparable> comparator =
				FilterIndex.getComparator(key, String.class);

			assertInstanceOf(LocalizedStringComparator.class, comparator);
		}

		@Test
		@DisplayName(
			"getComparator returns default comparator for non-localized"
		)
		void shouldReturnDefaultComparatorForNonLocalizedString() {
			final AttributeIndexKey key =
				new AttributeIndexKey(null, "a", null);

			final Comparator<? extends Comparable> comparator =
				FilterIndex.getComparator(key, String.class);

			assertSame(FilterIndex.DEFAULT_COMPARATOR, comparator);
		}

		@Test
		@DisplayName(
			"getComparator returns default for non-String type with locale"
		)
		void shouldReturnDefaultComparatorForNonStringType() {
			final AttributeIndexKey key =
				new AttributeIndexKey(null, "a", Locale.ENGLISH);

			final Comparator<? extends Comparable> comparator =
				FilterIndex.getComparator(key, Integer.class);

			assertSame(FilterIndex.DEFAULT_COMPARATOR, comparator);
		}
	}

	@Nested
	@DisplayName("isEmpty and size on empty index")
	class EmptyIndexTest {

		@Test
		@DisplayName("isEmpty returns true for newly created index")
		void shouldReturnTrueForEmptyIndex() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);

			assertTrue(index.isEmpty());
		}

		@Test
		@DisplayName("size returns zero for newly created index")
		void shouldReturnZeroSizeForEmptyIndex() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);

			assertEquals(0, index.size());
		}

		@Test
		@DisplayName("isEmpty returns false after adding a record")
		void shouldReturnFalseAfterAddingRecord() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			index.addRecord(1, "A");

			assertFalse(index.isEmpty());
		}

		@Test
		@DisplayName("size returns correct count after adding records")
		void shouldReturnCorrectSizeAfterAddingRecords() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			index.addRecord(1, "A");
			index.addRecord(2, "B");

			assertEquals(2, index.size());
		}
	}

	@Nested
	@DisplayName("Storage part and dirty flag")
	class StoragePartTest {

		@Test
		@DisplayName("createStoragePart returns null when not dirty")
		void shouldReturnNullStoragePartWhenNotDirty() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);

			final StoragePart storagePart = index.createStoragePart(1);

			assertNull(storagePart);
		}

		@Test
		@DisplayName(
			"createStoragePart returns FilterIndexStoragePart when dirty"
		)
		void shouldReturnStoragePartWhenDirty() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			index.addRecord(1, "A");

			final StoragePart storagePart = index.createStoragePart(42);

			assertNotNull(storagePart);
			assertInstanceOf(FilterIndexStoragePart.class, storagePart);
			final FilterIndexStoragePart filterPart =
				(FilterIndexStoragePart) storagePart;
			assertEquals(42, filterPart.getEntityIndexPrimaryKey());
			assertEquals(
				new AttributeIndexKey(null, "a", null),
				filterPart.getAttributeIndexKey()
			);
		}

		@Test
		@DisplayName("resetDirty clears the dirty flag")
		void shouldResetDirtyFlag() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			index.addRecord(1, "A");

			// should be dirty now
			assertNotNull(index.createStoragePart(1));

			index.resetDirty();

			// after reset, should no longer be dirty
			assertNull(index.createStoragePart(1));
		}
	}

	private void fillStringAttribute() {
		this.stringAttribute.addRecord(1, new String[]{"A", "B", "C"});
		this.stringAttribute.addRecord(2, new String[]{"A", "B"});
		this.stringAttribute.addRecord(3, "C");
		this.stringAttribute.addRecord(4, "D");
		assertArrayEquals(new int[]{1, 2}, this.stringAttribute.getRecordsEqualTo("A").getArray());
		assertArrayEquals(new int[]{1, 2}, this.stringAttribute.getRecordsEqualTo("B").getArray());
		assertArrayEquals(new int[]{1, 3}, this.stringAttribute.getRecordsEqualTo("C").getArray());
		assertArrayEquals(new int[]{4}, this.stringAttribute.getRecordsEqualTo("D").getArray());
		assertFalse(this.stringAttribute.isEmpty());
	}

	private void fillRangeAttribute() {
		this.rangeAttribute.addRecord(1, new IntegerNumberRange[] {IntegerNumberRange.between(5, 10), IntegerNumberRange.between(50, 90)});
		this.rangeAttribute.addRecord(2, IntegerNumberRange.between(11, 20));
		this.rangeAttribute.addRecord(3, IntegerNumberRange.between(5, 15));
		assertArrayEquals(new int[] {1}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(5, 10)).getArray());
		assertArrayEquals(new int[] {1}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(50, 90)).getArray());
		assertArrayEquals(new int[] {2}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(11, 20)).getArray());
		assertArrayEquals(new int[] {3}, this.rangeAttribute.getRecordsEqualTo(IntegerNumberRange.between(5, 15)).getArray());
		assertEquals(3, this.rangeAttribute.getAllRecords().size());
		assertFalse(this.rangeAttribute.isEmpty());
	}

	/**
	 * Exercises the range-aware histogram sweep that backs reference histograms over `NumberRange`
	 * source attributes. The sweep walks the leaf's `RangeIndex` companion, skips the `Long.MIN_VALUE`
	 * / `Long.MAX_VALUE` sentinels, and emits a `ValueToRecordBitmap` per non-sentinel threshold
	 * carrying every record whose stored range covers that threshold (closed-interval semantics).
	 */
	@Nested
	@DisplayName("Range-aware histogram sweep over RangeIndex companion")
	@Tag(ENGINE)
	@Tag(INDEXING)
	@Tag(HISTOGRAM)
	@Tag(ATTRIBUTE)
	class RangeHistogramSweep {

		@Test
		@DisplayName("empty range index emits no buckets and skips sentinels")
		void shouldReturnEmptyArrayWhenRangeIndexHasNoRecords() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "score", null), IntegerNumberRange.class
			);

			final InvertedIndexSubSet subset = index.getRangeHistogramOfAllRecords(Integer.class, 0);

			assertEquals(0, subset.getHistogramBuckets().length);
		}

		@Test
		@DisplayName("single multi-bucket sweep covers every endpoint and respects closed intervals")
		void shouldProduceClosedIntervalBucketsForOverlappingRanges() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "score", null), IntegerNumberRange.class
			);
			index.addRecord(1, IntegerNumberRange.between(10, 20));
			index.addRecord(2, IntegerNumberRange.between(15, 25));
			index.addRecord(3, IntegerNumberRange.between(20, 30));

			final InvertedIndexSubSet subset = index.getRangeHistogramOfAllRecords(Integer.class, 0);
			final ValueToRecordBitmap[] buckets = subset.getHistogramBuckets();

			// RangeIndex consolidates same-threshold start/end into one point — so the five distinct
			// thresholds 10, 15, 20, 25, 30 produce five buckets. At threshold 20, record 3 starts
			// and record 1 ends; the closed-interval emission rule (add starts → snapshot → remove
			// ends) puts all three records into the bucket at 20 before stripping record 1
			final Integer[] keys = new Integer[buckets.length];
			for (int i = 0; i < buckets.length; i++) {
				keys[i] = (Integer) buckets[i].getValue();
			}
			assertArrayEquals(new Integer[] {10, 15, 20, 25, 30}, keys);
			assertArrayEquals(new int[] {1}, buckets[0].getRecordIds().getArray());
			assertArrayEquals(new int[] {1, 2}, buckets[1].getRecordIds().getArray());
			assertArrayEquals(new int[] {1, 2, 3}, buckets[2].getRecordIds().getArray());
			assertArrayEquals(new int[] {2, 3}, buckets[3].getRecordIds().getArray());
			assertArrayEquals(new int[] {3}, buckets[4].getRecordIds().getArray());
		}

		@Test
		@DisplayName("point ranges (from == to) emit a bucket containing the record")
		void shouldEmitBucketContainingRecordForPointRange() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "score", null), IntegerNumberRange.class
			);
			index.addRecord(1, IntegerNumberRange.between(7, 7));
			index.addRecord(2, IntegerNumberRange.between(3, 7));

			final ValueToRecordBitmap[] buckets = index
				.getRangeHistogramOfAllRecords(Integer.class, 0)
				.getHistogramBuckets();

			// distinct thresholds: 3 (record 2 starts) and 7 (record 1 starts and ends, record 2 ends)
			final Integer[] keys = new Integer[buckets.length];
			for (int i = 0; i < buckets.length; i++) {
				keys[i] = (Integer) buckets[i].getValue();
			}
			assertArrayEquals(new Integer[] {3, 7}, keys);
			// the bucket at threshold 7 must contain BOTH record 1 (point range) and record 2 — the
			// snapshot must happen after adding starts but BEFORE removing ends; an inverse ordering
			// would silently miss record 1 entirely
			assertArrayEquals(new int[] {2}, buckets[0].getRecordIds().getArray());
			assertArrayEquals(new int[] {1, 2}, buckets[1].getRecordIds().getArray());
		}

		@Test
		@DisplayName("Byte/Short/Long inner numeric types materialize matching bucket keys")
		void shouldEmitBucketKeysOfMatchingNumericType() {
			final OwnerFilterIndex longIndex = new OwnerFilterIndex(
				new AttributeIndexKey(null, "value", null), LongNumberRange.class
			);
			longIndex.addRecord(1, LongNumberRange.between(1_000_000_000_000L, 2_000_000_000_000L));

			final ValueToRecordBitmap[] longBuckets = longIndex
				.getRangeHistogramOfAllRecords(Long.class, 0)
				.getHistogramBuckets();
			assertEquals(2, longBuckets.length);
			assertEquals(1_000_000_000_000L, longBuckets[0].getValue());
			assertEquals(2_000_000_000_000L, longBuckets[1].getValue());
		}

		@Test
		@DisplayName("BigDecimal inner numeric type recovers scaled decimal bucket keys")
		void shouldEmitScaledBigDecimalBucketKeys() {
			final OwnerFilterIndex bdIndex = new OwnerFilterIndex(
				new AttributeIndexKey(null, "price", null), BigDecimalNumberRange.class
			);
			// BigDecimalNumberRange encodes the threshold as `value.scaleByPowerOfTen(scale).longValueExact()`;
			// passing retainedDecimalPlaces=2 here reproduces the exact stored thresholds.
			bdIndex.addRecord(1, BigDecimalNumberRange.between(
				new BigDecimal("10.50"), new BigDecimal("20.75"), 2
			));

			final ValueToRecordBitmap[] buckets = bdIndex
				.getRangeHistogramOfAllRecords(BigDecimal.class, 2)
				.getHistogramBuckets();
			assertEquals(2, buckets.length);
			// `BigDecimal.valueOf(threshold, 2)` yields a scale-2 decimal; `compareTo` is required because
			// BigDecimal equality is scale-sensitive
			assertEquals(0, ((BigDecimal) buckets[0].getValue()).compareTo(new BigDecimal("10.50")));
			assertEquals(0, ((BigDecimal) buckets[1].getValue()).compareTo(new BigDecimal("20.75")));
			assertArrayEquals(new int[] {1}, buckets[0].getRecordIds().getArray());
			assertArrayEquals(new int[] {1}, buckets[1].getRecordIds().getArray());
		}

		@Test
		@DisplayName("memoization returns the same subset until a non-tx mutation invalidates it")
		void shouldMemoizeUntilNonTxMutation() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "score", null), IntegerNumberRange.class
			);
			index.addRecord(1, IntegerNumberRange.between(10, 20));

			final InvertedIndexSubSet first = index.getRangeHistogramOfAllRecords(Integer.class, 0);
			final InvertedIndexSubSet second = index.getRangeHistogramOfAllRecords(Integer.class, 0);
			assertSame(first, second);

			index.addRecord(2, IntegerNumberRange.between(5, 30));
			final InvertedIndexSubSet third = index.getRangeHistogramOfAllRecords(Integer.class, 0);
			assertNotSame(first, third);
			assertEquals(4, third.getHistogramBuckets().length);
		}

		@Test
		@DisplayName("invocation on a non-range FilterIndex throws GenericEvitaInternalError")
		void shouldThrowOnNonRangeFilterIndex() {
			final OwnerFilterIndex scalarIndex = new OwnerFilterIndex(
				new AttributeIndexKey(null, "name", null), String.class
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> scalarIndex.getRangeHistogramOfAllRecords(Integer.class, 0)
			);
		}

		@Test
		@DisplayName("unbounded-from range (IntegerNumberRange.to(X)) participates in every bucket V <= X")
		void shouldIncludeUnboundedFromRangeInAllBucketsUpToUpperBound() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "score", null), IntegerNumberRange.class
			);
			// record 1: range [null, 50] — `getFrom()` returns `Long.MIN_VALUE`, so its
			// recordId lands in the MIN sentinel's `starts` bitmap. The sweep must still
			// account for it when emitting buckets at thresholds <= 50.
			index.addRecord(1, IntegerNumberRange.to(50));
			index.addRecord(2, IntegerNumberRange.between(20, 80));
			index.addRecord(3, IntegerNumberRange.between(60, 100));

			final ValueToRecordBitmap[] buckets = index
				.getRangeHistogramOfAllRecords(Integer.class, 0)
				.getHistogramBuckets();

			final Integer[] keys = new Integer[buckets.length];
			for (int i = 0; i < buckets.length; i++) {
				keys[i] = (Integer) buckets[i].getValue();
			}
			assertArrayEquals(new Integer[] {20, 50, 60, 80, 100}, keys);
			// closed-interval semantics: record 1 (`[null, 50]`) participates in every
			// bucket V where V <= 50 — i.e. buckets at 20 and 50
			assertArrayEquals(new int[] {1, 2}, buckets[0].getRecordIds().getArray());
			assertArrayEquals(new int[] {1, 2}, buckets[1].getRecordIds().getArray());
			assertArrayEquals(new int[] {2, 3}, buckets[2].getRecordIds().getArray());
			assertArrayEquals(new int[] {2, 3}, buckets[3].getRecordIds().getArray());
			assertArrayEquals(new int[] {3}, buckets[4].getRecordIds().getArray());
		}

		@Test
		@DisplayName("unbounded-to range (IntegerNumberRange.from(X)) participates in every bucket V >= X")
		void shouldIncludeUnboundedToRangeInAllBucketsFromLowerBound() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "score", null), IntegerNumberRange.class
			);
			// record 1: range [50, null] — `getTo()` returns `Long.MAX_VALUE`, so its
			// recordId lands in the MAX sentinel's `ends` bitmap. The record's `starts`
			// is at the real threshold 50, so the sweep correctly carries it through
			// every subsequent real threshold even when the MAX sentinel is sentinel-skipped.
			index.addRecord(1, IntegerNumberRange.from(50));
			index.addRecord(2, IntegerNumberRange.between(20, 80));
			index.addRecord(3, IntegerNumberRange.between(10, 30));

			final ValueToRecordBitmap[] buckets = index
				.getRangeHistogramOfAllRecords(Integer.class, 0)
				.getHistogramBuckets();

			final Integer[] keys = new Integer[buckets.length];
			for (int i = 0; i < buckets.length; i++) {
				keys[i] = (Integer) buckets[i].getValue();
			}
			assertArrayEquals(new Integer[] {10, 20, 30, 50, 80}, keys);
			assertArrayEquals(new int[] {3}, buckets[0].getRecordIds().getArray());
			assertArrayEquals(new int[] {2, 3}, buckets[1].getRecordIds().getArray());
			assertArrayEquals(new int[] {2, 3}, buckets[2].getRecordIds().getArray());
			// at threshold 50: record 1 (`[50, null]`) becomes active and is carried forward
			assertArrayEquals(new int[] {1, 2}, buckets[3].getRecordIds().getArray());
			assertArrayEquals(new int[] {1, 2}, buckets[4].getRecordIds().getArray());
		}

		@Test
		@DisplayName("resolveRangeInnerNumericType maps each NumberRange subtype to its primitive wrapper")
		void shouldMapEachNumberRangeSubtypeToInnerType() {
			assertSame(Integer.class, EvitaDataTypes.resolveRangeInnerNumericType(IntegerNumberRange.class));
			assertSame(Long.class, EvitaDataTypes.resolveRangeInnerNumericType(LongNumberRange.class));
			assertSame(BigDecimal.class, EvitaDataTypes.resolveRangeInnerNumericType(BigDecimalNumberRange.class));
			assertNull(EvitaDataTypes.resolveRangeInnerNumericType(Integer.class));
			assertNull(EvitaDataTypes.resolveRangeInnerNumericType(DateTimeRange.class));
		}

	}

}
