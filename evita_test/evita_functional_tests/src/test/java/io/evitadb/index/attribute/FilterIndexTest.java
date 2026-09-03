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
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.core.buffer.TrappedChanges;
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
import io.evitadb.index.invertedIndex.ValueToRecord;
import io.evitadb.index.range.RangePoint;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.text.Normalizer;
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
import static io.evitadb.test.TestTags.DATA_TYPE;

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
	@DisplayName("range index thresholds of a scale-mismatched BigDecimal range honor the index scale")
	void rangeIndexHonorsIndexScaleForScaleMismatchedBigDecimalRange() {
		// `validRange` is indexed at scale 2 but the range is built without a places argument, so its bounds keep
		// their intrinsic scale of 1; the range-index thresholds must still be derived at the index scale so a
		// probe coerced to that scale lands inside the stored envelope
		final OwnerFilterIndex filterIndex = new OwnerFilterIndex(
			new AttributeIndexKey(null, "validRange", null), BigDecimalNumberRange.class, 2
		);
		filterIndex.addRecord(
			1, BigDecimalNumberRange.between(new BigDecimal("1.5"), new BigDecimal("2.5"))
		);

		// probe 2.0 coerces to 200 at the index scale; the stored envelope [150, 250] must contain it
		final long probeInside = BigDecimalNumberRange.toComparableLong(new BigDecimal("2.0"), 2);
		assertArrayEquals(
			new int[]{1}, filterIndex.getRecordsValidInFormula(probeInside).compute().getArray()
		);

		// inclusive boundaries 1.5 -> 150 and 2.5 -> 250 both match
		assertArrayEquals(
			new int[]{1},
			filterIndex.getRecordsValidInFormula(
				BigDecimalNumberRange.toComparableLong(new BigDecimal("1.5"), 2)
			).compute().getArray()
		);
		assertArrayEquals(
			new int[]{1},
			filterIndex.getRecordsValidInFormula(
				BigDecimalNumberRange.toComparableLong(new BigDecimal("2.5"), 2)
			).compute().getArray()
		);

		// a probe outside the envelope (3.0 -> 300) matches nothing
		assertArrayEquals(
			new int[0],
			filterIndex.getRecordsValidInFormula(
				BigDecimalNumberRange.toComparableLong(new BigDecimal("3.0"), 2)
			).compute().getArray()
		);
	}

	@Test
	@DisplayName("removing a scale-mismatched BigDecimal range clears its range-index envelope without a sanity throw")
	void removingScaleMismatchedBigDecimalRangeClearsEnvelope() {
		final OwnerFilterIndex filterIndex = new OwnerFilterIndex(
			new AttributeIndexKey(null, "validRange", null), BigDecimalNumberRange.class, 2
		);
		final BigDecimalNumberRange range =
			BigDecimalNumberRange.between(new BigDecimal("1.5"), new BigDecimal("2.5"));
		filterIndex.addRecord(1, range);
		final long probeInside = BigDecimalNumberRange.toComparableLong(new BigDecimal("2.0"), 2);
		assertArrayEquals(
			new int[]{1}, filterIndex.getRecordsValidInFormula(probeInside).compute().getArray()
		);

		// remove uses the same raw intrinsic-scale range; the symmetric canonicalization must clear the envelope
		filterIndex.removeRecord(1, range);
		assertArrayEquals(
			new int[0], filterIndex.getRecordsValidInFormula(probeInside).compute().getArray()
		);
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
		@DisplayName("memoized bitmap is invalidated on non-tx write")
		void shouldInvalidateMemoizedBitmapOnNonTransactionalWrite() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			index.addRecord(1, "A");

			// first call caches the bitmap
			final Bitmap firstCall = index.getAllRecords();
			// second call returns same cached instance
			final Bitmap secondCall = index.getAllRecords();
			assertSame(firstCall, secondCall);

			// write invalidates the cache
			index.addRecord(2, "B");
			final Bitmap afterWrite = index.getAllRecords();
			assertNotSame(firstCall, afterWrite);
			assertArrayEquals(
				new int[]{1, 2},
				afterWrite.getArray()
			);
		}
	}

	@Nested
	@DisplayName("Formula and memoization")
	class FormulaMemoizationTest {

		@Test
		@DisplayName("getAllRecordsFormula memoizes the bitmap but never the formula")
		void shouldMemoizeBitmapButHandOutFreshFormula() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			index.addRecord(1, "A");
			index.addRecord(2, "B");

			final Formula first = index.getAllRecordsFormula();
			final Formula second = index.getAllRecordsFormula();

			// a formula node carries per-query state once a plan initializes it, so an index-lifetime structure
			// must never hand out the same instance twice - see FilterIndex#memoizedAllRecords
			assertNotSame(first, second);
			// the expensive part - the merged bitmap - is still memoized and shared between them
			assertSame(
				((ConstantFormula) first).getDelegate(),
				((ConstantFormula) second).getDelegate()
			);
			assertSame(index.getAllRecords(), ((ConstantFormula) first).getDelegate());
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

		/**
		 * The index stores String keys in Unicode NFD (decomposed) form, but a user naturally types the search
		 * term in NFC (precomposed) form. `startsWith` must match across these canonically-equivalent encodings,
		 * exactly as the `=` operator already does. A pure-ASCII prefix on the same data is asserted alongside to
		 * guard the common path against regression.
		 */
		@Test
		@DisplayName("getRecordsWhoseValuesStartWith matches a precomposed (NFC) prefix")
		void shouldReturnRecordsStartingWithPrecomposedPrefix() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			// "Pâté" supplied in NFC (precomposed â = U+00E2, é = U+00E9)
			final String precomposedValue = Normalizer.normalize("Pâté", Normalizer.Form.NFC);
			index.addRecord(1, precomposedValue);
			index.addRecord(2, "Pasta");

			// user types the precomposed prefix "Pâ"
			final String precomposedPrefix = Normalizer.normalize("Pâ", Normalizer.Form.NFC);
			assertArrayEquals(
				new int[]{1},
				index.getRecordsWhoseValuesStartWith(precomposedPrefix).compute().getArray()
			);

			// pure-ASCII prefix still works on the same index
			assertArrayEquals(
				new int[]{1, 2},
				index.getRecordsWhoseValuesStartWith("P").compute().getArray()
			);
		}

		/**
		 * The index stores String keys in Unicode NFD (decomposed) form, but a user naturally types the search
		 * term in NFC (precomposed) form. `endsWith` must match across these canonically-equivalent encodings. A
		 * pure-ASCII suffix on the same data is asserted alongside to guard the common path against regression.
		 */
		@Test
		@DisplayName("getRecordsWhoseValuesEndsWith matches a precomposed (NFC) suffix")
		void shouldReturnRecordsEndingWithPrecomposedSuffix() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			final String precomposedValue = Normalizer.normalize("Pâté", Normalizer.Form.NFC);
			index.addRecord(1, precomposedValue);
			index.addRecord(2, "Latte");

			// user types the precomposed suffix "âté"
			final String precomposedSuffix = Normalizer.normalize("âté", Normalizer.Form.NFC);
			assertArrayEquals(
				new int[]{1},
				index.getRecordsWhoseValuesEndsWith(precomposedSuffix).compute().getArray()
			);

			// pure-ASCII suffix still works on the same index
			assertArrayEquals(
				new int[]{2},
				index.getRecordsWhoseValuesEndsWith("tte").compute().getArray()
			);
		}

		/**
		 * The index stores String keys in Unicode NFD (decomposed) form, but a user naturally types the search
		 * term in NFC (precomposed) form. `contains` must match across these canonically-equivalent encodings. A
		 * pure-ASCII substring on the same data is asserted alongside to guard the common path against regression.
		 */
		@Test
		@DisplayName("getRecordsWhoseValuesContains matches a precomposed (NFC) substring")
		void shouldReturnRecordsContainingPrecomposedText() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			final String precomposedValue = Normalizer.normalize("Pâté", Normalizer.Form.NFC);
			index.addRecord(1, precomposedValue);
			index.addRecord(2, "Plate");

			// user types the precomposed substring "ât"
			final String precomposedText = Normalizer.normalize("ât", Normalizer.Form.NFC);
			assertArrayEquals(
				new int[]{1},
				index.getRecordsWhoseValuesContains(precomposedText).compute().getArray()
			);

			// pure-ASCII substring still works on the same index
			assertArrayEquals(
				new int[]{2},
				index.getRecordsWhoseValuesContains("lat").compute().getArray()
			);
		}

		/**
		 * For a localized String attribute the inverted index is ordered by collation
		 * ({@link LocalizedStringComparator}), not by codepoint. Under collation order a codepoint-`startsWith`
		 * match may sort after non-matching values (a capitalized term collates together with its lowercase form,
		 * so it interleaves with the matches), so the contiguous forward-run assumption that lets
		 * `getRecordsWhoseValuesStartWith` early-break drops matches. Here the buckets collate in the order
		 * `apple`, `Apple pie`, `applesauce`, `banana`, `Banana split`; an early break stops at the non-matching
		 * `Apple pie` and never reaches `applesauce`, so the full predicate scan is required. `startsWith` is
		 * case-sensitive, hence `Apple pie` itself never matches the lowercase `app` prefix.
		 */
		@Test
		@DisplayName("getRecordsWhoseValuesStartWith finds all matches under collation ordering")
		void shouldReturnAllRecordsStartingWithUnderCollationOrdering() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", Locale.ENGLISH), String.class
			);
			// under English collation case is a tertiary difference so "Apple pie" collates between "apple" and
			// "applesauce", breaking the codepoint-order contiguity of the lowercase "app" matches
			index.addRecord(1, "apple");
			index.addRecord(2, "Apple pie");
			index.addRecord(3, "banana");
			index.addRecord(4, "applesauce");
			index.addRecord(5, "Banana split");

			assertArrayEquals(
				new int[]{1, 4},
				index.getRecordsWhoseValuesStartWith("app").compute().getArray()
			);
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
				FilterIndex.getNormalizer(OffsetDateTime.class, 0);
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
				FilterIndex.getNormalizer(Currency.class, 0);
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
				FilterIndex.getNormalizer(Locale.class, 0);

			final Serializable result = normalizer.apply(Locale.ENGLISH);

			assertInstanceOf(ComparableLocale.class, result);
		}

		@Test
		@DisplayName(
			"getNormalizer for Comparable type returns NO_NORMALIZATION"
		)
		void shouldReturnNoNormalizationForComparableType() {
			final Function<Object, Serializable> normalizer =
				FilterIndex.getNormalizer(Integer.class, 0);

			assertSame(FilterIndex.NO_NORMALIZATION, normalizer);
		}

		@Test
		@DisplayName(
			"getNormalizer throws for unsupported non-Comparable type"
		)
		void shouldThrowForUnsupportedType() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> FilterIndex.getNormalizer(Object.class, 0)
			);
		}

		@Test
		@DisplayName("getNormalizer for BigDecimal scales to an order-preserving int and is idempotent")
		void shouldScaleBigDecimalToInt() {
			final Function<Object, Serializable> normalizer =
				FilterIndex.getNormalizer(BigDecimal.class, 2);

			// 1.50 with two decimal places becomes the scaled int 150
			assertEquals(150, normalizer.apply(new BigDecimal("1.50")));
			// two BigDecimals equal under the schema's decimal places normalize to the same scaled int (same bucket)
			assertEquals(normalizer.apply(new BigDecimal("1.5")), normalizer.apply(new BigDecimal("1.50")));
			// idempotent: an already-scaled Integer (and null) passes through unchanged
			assertEquals(150, normalizer.apply(150));
			assertNull(normalizer.apply(null));
		}

		@Test
		@DisplayName("getNormalizer for BigDecimalNumberRange rescales thresholds to the schema's decimal places")
		void shouldRescaleBigDecimalNumberRangeToIndexedDecimalPlaces() {
			final Function<Object, Serializable> normalizer =
				FilterIndex.getNormalizer(BigDecimalNumberRange.class, 2);

			// a range whose bounds carry an intrinsic scale of 1 must be rebuilt at the schema's two decimal places,
			// so its comparable thresholds line up with what the source filter / range index stores (15/25 -> 150/250)
			final BigDecimalNumberRange raw =
				BigDecimalNumberRange.between(new BigDecimal("1.5"), new BigDecimal("2.5"));
			assertEquals(15L, raw.getFrom(), "precondition: raw range encodes at its intrinsic scale of 1");
			assertEquals(25L, raw.getTo(), "precondition: raw range encodes at its intrinsic scale of 1");

			final Serializable normalized = normalizer.apply(raw);
			final BigDecimalNumberRange rescaled = assertInstanceOf(BigDecimalNumberRange.class, normalized);
			assertEquals(2, rescaled.getRetainedDecimalPlaces());
			assertEquals(150L, rescaled.getFrom());
			assertEquals(250L, rescaled.getTo());

			// open-ended bounds survive the rescale
			final BigDecimalNumberRange toOnly =
				(BigDecimalNumberRange) normalizer.apply(BigDecimalNumberRange.to(new BigDecimal("9.9")));
			assertEquals(990L, toOnly.getTo());

			// idempotent: a range already at the target scale rescales to an equal range
			final BigDecimalNumberRange reNormalized = (BigDecimalNumberRange) normalizer.apply(rescaled);
			assertEquals(rescaled.getFrom(), reNormalized.getFrom());
			assertEquals(rescaled.getTo(), reNormalized.getTo());

			// a non-range value (and null) passes through untouched
			assertEquals("untouched", normalizer.apply("untouched"));
			assertNull(normalizer.apply(null));
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
		@DisplayName("appendStorageParts emits nothing when not dirty")
		void shouldEmitNothingWhenNotDirty() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);

			final TrappedChanges sink = new TrappedChanges();
			index.appendStorageParts(1, sink);

			assertEquals(0, sink.getTrappedChangesCount());
		}

		@Test
		@DisplayName(
			"appendStorageParts emits a FilterIndexStoragePart when dirty"
		)
		void shouldEmitStoragePartWhenDirty() {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "a", null), String.class
			);
			index.addRecord(1, "A");

			final TrappedChanges sink = new TrappedChanges();
			index.appendStorageParts(42, sink);

			assertEquals(1, sink.getTrappedChangesCount());
			final StoragePart storagePart = sink.getTrappedChangesIterator().next();
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
			final TrappedChanges beforeReset = new TrappedChanges();
			index.appendStorageParts(1, beforeReset);
			assertEquals(1, beforeReset.getTrappedChangesCount());

			index.resetDirty();

			// after reset, should no longer be dirty
			final TrappedChanges afterReset = new TrappedChanges();
			index.appendStorageParts(1, afterReset);
			assertEquals(0, afterReset.getTrappedChangesCount());
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

			assertEquals(0, subset.getBuckets().length);
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
			final ValueToRecord[] buckets = subset.getBuckets();

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

			final ValueToRecord[] buckets = index
				.getRangeHistogramOfAllRecords(Integer.class, 0)
				.getBuckets();

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

			final ValueToRecord[] longBuckets = longIndex
				.getRangeHistogramOfAllRecords(Long.class, 0)
				.getBuckets();
			assertEquals(2, longBuckets.length);
			assertEquals(1_000_000_000_000L, longBuckets[0].getValue());
			assertEquals(2_000_000_000_000L, longBuckets[1].getValue());
		}

		@Test
		@DisplayName("BigDecimal inner numeric type recovers scaled decimal bucket keys")
		void shouldEmitScaledBigDecimalBucketKeys() {
			// the index scale must match the range scale: the filter index canonicalizes every incoming range to
			// its own `indexedDecimalPlaces` before deriving the range-index thresholds, so an index built at
			// scale 2 keeps a scale-2 range's thresholds intact
			final OwnerFilterIndex bdIndex = new OwnerFilterIndex(
				new AttributeIndexKey(null, "price", null), BigDecimalNumberRange.class, 2
			);
			// BigDecimalNumberRange encodes the threshold as `value.scaleByPowerOfTen(scale).longValueExact()`;
			// passing retainedDecimalPlaces=2 here reproduces the exact stored thresholds.
			bdIndex.addRecord(1, BigDecimalNumberRange.between(
				new BigDecimal("10.50"), new BigDecimal("20.75"), 2
			));

			final ValueToRecord[] buckets = bdIndex
				.getRangeHistogramOfAllRecords(BigDecimal.class, 2)
				.getBuckets();
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
			assertEquals(4, third.getBuckets().length);
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

			final ValueToRecord[] buckets = index
				.getRangeHistogramOfAllRecords(Integer.class, 0)
				.getBuckets();

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

			final ValueToRecord[] buckets = index
				.getRangeHistogramOfAllRecords(Integer.class, 0)
				.getBuckets();

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

	/**
	 * Pins the index-side encoding of the temporal attribute types. `LocalDateTime` carries no offset of its own, so
	 * it is anchored at UTC before it becomes a bucket key — the same `Instant` space `OffsetDateTime` already uses,
	 * which is what lets the tree store it as epoch-millis in a single-`long` `LongValueColumn` instead of boxing it.
	 * Because the anchor is a *constant* offset the mapping is a lossless bijection and monotonic with
	 * `LocalDateTime`'s natural order, so equality lookup and ordered iteration are unaffected. `LocalDate` and
	 * `LocalTime` are deliberately left alone — each fits losslessly in a single `long` of its own.
	 *
	 * The normalizer additionally truncates to whole milliseconds, which is what keeps every key inside
	 * `LongKeyCodec.INSTANT`'s domain no matter where the value came from — see `FilterIndex#getNormalizer`.
	 */
	@Nested
	@DisplayName("Temporal attribute index encoding")
	@Tag(ENGINE)
	@Tag(INDEXING)
	@Tag(ATTRIBUTE)
	@Tag(DATA_TYPE)
	class TemporalNormalization {

		@Test
		@DisplayName("LocalDateTime is normalized to its UTC instant")
		void shouldNormalizeLocalDateTimeToUtcInstant() {
			final LocalDateTime value = LocalDateTime.of(2026, 5, 20, 12, 19, 26);

			assertEquals(
				value.toInstant(ZoneOffset.UTC),
				FilterIndex.getNormalizer(LocalDateTime.class, 0).apply(value)
			);
		}

		@Test
		@DisplayName("normalizing an already-normalized LocalDateTime value is a no-op")
		void shouldBeIdempotentForLocalDateTime() {
			// probe values are normalized more than once along a lookup path - a second pass must not throw
			final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(LocalDateTime.class, 0);
			final Serializable once = normalizer.apply(LocalDateTime.of(2026, 5, 20, 12, 19, 26));

			assertEquals(once, normalizer.apply(once));
		}

		@Test
		@DisplayName("LocalDateTime normalization preserves natural order")
		void shouldPreserveOrderForLocalDateTime() {
			// a constant offset cannot reorder values - this is precisely what a region time zone would not guarantee
			final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(LocalDateTime.class, 0);
			final LocalDateTime earlier = LocalDateTime.of(2026, 5, 20, 12, 19, 26);
			final LocalDateTime later = LocalDateTime.of(2026, 5, 20, 14, 19, 26);

			assertTrue(
				((Instant) normalizer.apply(earlier)).isBefore((Instant) normalizer.apply(later))
			);
		}

		@Test
		@DisplayName("OffsetDateTime is truncated to whole milliseconds on the way into the index")
		void shouldTruncateOffsetDateTimeToMilliseconds() {
			final OffsetDateTime value = LocalDateTime.of(2026, 5, 20, 12, 19, 26)
				.atOffset(ZoneOffset.ofHours(2)).plusNanos(123_456_789L);

			assertEquals(
				Instant.parse("2026-05-20T10:19:26.123Z"),
				FilterIndex.getNormalizer(OffsetDateTime.class, 0).apply(value)
			);
		}

		@Test
		@DisplayName("LocalDateTime is truncated to whole milliseconds on the way into the index")
		void shouldTruncateLocalDateTimeToMilliseconds() {
			final LocalDateTime value = LocalDateTime.of(2026, 5, 20, 12, 19, 26).plusNanos(123_456_789L);

			assertEquals(
				Instant.parse("2026-05-20T12:19:26.123Z"),
				FilterIndex.getNormalizer(LocalDateTime.class, 0).apply(value)
			);
		}

		@Test
		@DisplayName("a nano-precise Instant of any provenance is truncated too")
		void shouldTruncateARawInstant() {
			// this is the case `EvitaDataTypes#toSupportedType` cannot cover: a bucket value rehydrated from a catalog
			// written before millisecond truncation existed reaches the normalizer as an `Instant`, never as an
			// `OffsetDateTime`, and would otherwise be handed to the leaf column outside the codec's domain
			assertEquals(
				Instant.parse("2026-05-20T12:19:26.123Z"),
				FilterIndex.getNormalizer(OffsetDateTime.class, 0)
					.apply(Instant.parse("2026-05-20T12:19:26.123999999Z"))
			);
			assertEquals(
				Instant.parse("2026-05-20T12:19:26.123Z"),
				FilterIndex.getNormalizer(LocalDateTime.class, 0)
					.apply(Instant.parse("2026-05-20T12:19:26.123000001Z"))
			);
		}

		@Test
		@DisplayName("truncation floors on both sides of the epoch, so it can never reorder two values")
		void shouldFloorTemporalValuesBelowTheEpochToo() {
			// a truncate-toward-zero implementation would round a pre-1970 value UP, which is the one way this could
			// break the monotonicity `LongKeyCodec.INSTANT` and the tree's binary search rest on
			final Function<Object, Serializable> normalizer = FilterIndex.getNormalizer(OffsetDateTime.class, 0);
			final Instant lower = (Instant) normalizer.apply(
				OffsetDateTime.ofInstant(Instant.parse("1969-12-31T23:59:59.000999999Z"), ZoneOffset.UTC));
			final Instant higher = (Instant) normalizer.apply(
				OffsetDateTime.ofInstant(Instant.parse("1969-12-31T23:59:59.001000000Z"), ZoneOffset.UTC));

			assertEquals(Instant.parse("1969-12-31T23:59:59.000Z"), lower);
			assertEquals(Instant.parse("1969-12-31T23:59:59.001Z"), higher);
			assertTrue(lower.isBefore(higher));
		}

		@Test
		@DisplayName("an already-millisecond-exact value comes back as the very same instance")
		void shouldNotAllocateForAnAlreadyExactValue() {
			// the normalizer runs once per indexed value on the write path; after `EvitaDataTypes#toSupportedType`
			// almost every value is already exact, and re-deriving an equal `Instant` for each of them would be pure
			// allocation
			final Instant exact = Instant.parse("2026-05-20T12:19:26.123Z");

			assertSame(exact, FilterIndex.getNormalizer(OffsetDateTime.class, 0).apply(exact));
		}

		@Test
		@DisplayName("two sub-millisecond values reach one bucket end-to-end through a FilterIndex")
		void shouldMatchAtMillisecondGranularityThroughAFilterIndex() {
			final OwnerFilterIndex filterIndex = new OwnerFilterIndex(
				new AttributeIndexKey(null, "published", null), OffsetDateTime.class
			);
			final OffsetDateTime base = LocalDateTime.of(2026, 5, 20, 12, 19, 26).atOffset(ZoneOffset.ofHours(2));
			filterIndex.addRecord(1, base.plusNanos(123_000_001L));
			filterIndex.addRecord(2, base.plusNanos(123_999_999L));
			filterIndex.addRecord(3, base.plusNanos(124_000_000L));

			// a THIRD sub-millisecond value inside the same millisecond finds both records - a probe that merely
			// echoed one of the stored values back would prove nothing
			assertArrayEquals(
				new int[]{1, 2},
				filterIndex.getRecordsEqualTo(base.plusNanos(123_456_789L)).getArray()
			);
			// and the neighbouring millisecond stays separate, so the collapse is not simply "everything matches"
			assertArrayEquals(
				new int[]{3},
				filterIndex.getRecordsEqualTo(base.plusNanos(124_000_000L)).getArray()
			);
			assertEquals(2, filterIndex.getDistinctValueCount());
		}

		@Test
		@DisplayName("LocalDate and LocalTime pass through unnormalized")
		void shouldLeaveLocalDateAndLocalTimeAlone() {
			final LocalDate date = LocalDate.of(2026, 5, 20);
			final LocalTime time = LocalTime.of(12, 19, 26);

			assertSame(date, FilterIndex.getNormalizer(LocalDate.class, 0).apply(date));
			assertSame(time, FilterIndex.getNormalizer(LocalTime.class, 0).apply(time));
		}

	}

	@Nested
	@DisplayName("array-delta parity over a reconstructed range column")
	@Tag(DATA_TYPE)
	class RangeColumnDeltaParity {

		/**
		 * Renders a range index's thresholds, which is what the parity assertions compare.
		 *
		 * @param index the filter index whose range index is rendered
		 * @return the range index's points, one per line
		 */
		@Nonnull
		private String thresholdsOf(@Nonnull OwnerFilterIndex index) {
			final RangePoint<?>[] ranges = index.getRangeIndex().getRanges();
			return Arrays.stream(ranges).map(Object::toString).collect(Collectors.joining("\n"));
		}

		@Test
		@DisplayName("a date-time array mixing an open and a closed range at another offset leaves no threshold behind")
		void shouldLeaveNoThresholdBehindForAMixedDateTimeArray() {
			// this is the counterexample the `meta` word exists for, driven end to end. `addRecordDelta` consolidates
			// the ORIGINAL objects and inserts the resulting thresholds; `removeRecordDelta` reads the ranges back out
			// of the tree - now RECONSTRUCTED by the range column - consolidates those and removes the thresholds it
			// gets. An encoding that dropped the closed range's zone offset would consolidate to a lower bound five
			// hours away from the one that went in, and the removal would silently miss
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "validity", null), DateTimeRange.class);
			final ZoneOffset twoHours = ZoneOffset.ofHours(2);
			final ZoneOffset fiveHours = ZoneOffset.ofHours(5);
			final DateTimeRange[] mixed = {
				DateTimeRange.until(LocalDateTime.of(2024, 1, 10, 0, 0).atOffset(twoHours)),
				DateTimeRange.between(
					LocalDateTime.of(2024, 1, 5, 0, 0).atOffset(fiveHours),
					LocalDateTime.of(2024, 1, 20, 0, 0).atOffset(fiveHours)
				)
			};

			// a second record keeps the index non-empty throughout, so the parity assertion is about the delta rather
			// than about an index that happens to have been emptied
			final DateTimeRange resident = DateTimeRange.between(
				LocalDateTime.of(2030, 1, 1, 0, 0).atOffset(ZoneOffset.UTC),
				LocalDateTime.of(2030, 2, 1, 0, 0).atOffset(ZoneOffset.UTC)
			);
			index.addRecord(9, resident);
			final String before = thresholdsOf(index);

			index.addRecordDelta(1, mixed);
			assertNotEquals(before, thresholdsOf(index), "the delta must actually have changed the range index");
			assertArrayEquals(new int[]{1}, index.getRecordsEqualTo(mixed[0]).getArray());
			assertArrayEquals(new int[]{1}, index.getRecordsEqualTo(mixed[1]).getArray());

			index.removeRecordDelta(1, mixed);
			assertEquals(before, thresholdsOf(index), "the removal must retire exactly the thresholds it added");
			assertTrue(index.getRecordsEqualTo(mixed[0]).isEmpty());
			assertTrue(index.getRecordsEqualTo(mixed[1]).isEmpty());
			assertArrayEquals(new int[]{9}, index.getRecordsEqualTo(resident).getArray());
		}

		@Test
		@DisplayName("a numeric array mixing an open and a closed range leaves no threshold behind")
		void shouldLeaveNoThresholdBehindForAMixedNumericArray() {
			// the same parity for the two-array shape, whose open bounds ARE the constructor's own sentinels
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "quantity", null), IntegerNumberRange.class);
			final IntegerNumberRange[] mixed = {
				IntegerNumberRange.to(10),
				IntegerNumberRange.between(5, 40),
				IntegerNumberRange.between(60, 70)
			};
			index.addRecord(9, IntegerNumberRange.between(1_000, 2_000));
			final String before = thresholdsOf(index);

			index.addRecordDelta(1, mixed);
			assertNotEquals(before, thresholdsOf(index), "the delta must actually have changed the range index");
			for (final IntegerNumberRange range : mixed) {
				assertArrayEquals(new int[]{1}, index.getRecordsEqualTo(range).getArray());
			}

			index.removeRecordDelta(1, mixed);
			assertEquals(before, thresholdsOf(index), "the removal must retire exactly the thresholds it added");
			for (final IntegerNumberRange range : mixed) {
				assertTrue(index.getRecordsEqualTo(range).isEmpty());
			}
		}

		@Test
		@DisplayName("a big decimal array round-trips through the index scale rather than the intrinsic one")
		void shouldLeaveNoThresholdBehindForAScaledBigDecimalArray() {
			// the range column rebuilds a `BigDecimalNumberRange` at the index's `indexedDecimalPlaces` and carries
			// that scale into the object, which is what makes the removal's consolidation re-derive the same longs
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "price", null), BigDecimalNumberRange.class, 2);
			final BigDecimalNumberRange[] mixed = {
				BigDecimalNumberRange.to(new BigDecimal("10.50")),
				BigDecimalNumberRange.between(new BigDecimal("5.25"), new BigDecimal("40.75"))
			};
			index.addRecord(9, BigDecimalNumberRange.between(new BigDecimal("500.00"), new BigDecimal("600.00")));
			final String before = thresholdsOf(index);

			index.addRecordDelta(1, mixed);
			assertNotEquals(before, thresholdsOf(index), "the delta must actually have changed the range index");
			index.removeRecordDelta(1, mixed);
			assertEquals(before, thresholdsOf(index), "the removal must retire exactly the thresholds it added");
		}

		@Test
		@DisplayName("a concrete range attribute really is served by the reconstructing column, not the boxed one")
		void shouldServeAConcreteRangeAttributeFromTheReconstructingColumn() {
			// every parity assertion above would pass unchanged with the boxed column, which is what this seam used
			// to select - so nothing here fails if a future change routes a range attribute back to it. The range
			// column cannot be named from this package, so the pin is its two observable fingerprints: it MINTS the
			// value it hands back (the boxed column returns the very instance stored, a `DateTimeRange` attribute
			// being normalized by identity), and it rebuilds the bounds from whole epoch seconds
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "validity", null), DateTimeRange.class);
			final ZoneOffset offset = ZoneOffset.ofHours(3);
			final DateTimeRange stored = DateTimeRange.between(
				LocalDateTime.of(2024, 5, 6, 7, 8, 9, 123_456_789).atOffset(offset),
				LocalDateTime.of(2024, 6, 7, 8, 9, 10, 987_654_321).atOffset(offset)
			);
			index.addRecord(1, stored);

			final DateTimeRange[] readBack = index.getInvertedIndex().getValuesForRecord(1, DateTimeRange.class);
			assertEquals(1, readBack.length, "the record must be found under exactly one value");
			assertEquals(stored, readBack[0], "the reconstruction must be equal to the value that went in");
			assertNotSame(stored, readBack[0], "the boxed column would hand the stored instance straight back");
			assertEquals(0, readBack[0].getPreciseFrom().getNano(), "the bounds are rebuilt from whole epoch seconds");
			assertEquals(0, readBack[0].getPreciseTo().getNano(), "the bounds are rebuilt from whole epoch seconds");
			// and the `meta` word came back with them, which is what makes the reconstruction usable
			assertEquals(offset, readBack[0].getPreciseFrom().getOffset(), "at the stored zone offset");
			assertEquals(offset, readBack[0].getPreciseTo().getOffset(), "at the stored zone offset");
		}

		@Test
		@DisplayName("an index declared over the abstract NumberRange type still accepts array deltas")
		void shouldStillServeAnIndexDeclaredOverTheAbstractRangeType() {
			// `NumberRange.class` is not a supported schema attribute type and has no subtype to rebuild, so the
			// column selection falls through to the boxed column - the fallback this whole exact-class-equality
			// selection exists to preserve. The delta path must behave exactly as it always did
			final IntegerNumberRange[] mixed = {IntegerNumberRange.to(10), IntegerNumberRange.between(5, 40)};
			FilterIndexTest.this.rangeAttribute.addRecord(9, IntegerNumberRange.between(1_000, 2_000));
			final String before = thresholdsOf(FilterIndexTest.this.rangeAttribute);

			FilterIndexTest.this.rangeAttribute.addRecordDelta(1, mixed);
			assertNotEquals(before, thresholdsOf(FilterIndexTest.this.rangeAttribute));
			FilterIndexTest.this.rangeAttribute.removeRecordDelta(1, mixed);
			assertEquals(before, thresholdsOf(FilterIndexTest.this.rangeAttribute));
		}

		@Test
		@DisplayName("an array delta over a saturated long range matches the boxed index it replaced")
		void shouldLeaveNoThresholdBehindForASaturatedLongRangeArray() {
			// the delta paths are the only ones that feed values READ BACK out of the tree into
			// `Range.consolidateRange`, and every other parity case in this nest uses bounds nowhere near the two
			// sentinels the open-bound encoding spends. A range saturating BOTH of them is the shape that is not
			// covered - and `LongNumberRange.from(Long.MIN_VALUE)` is an ordinary way for a caller to write one.
			// `NumberRange.class` is the counterfactual: an abstract declared type falls through to the boxed
			// column, which hands the stored instances back and therefore never rebuilds a bound at all
			final LongNumberRange[] added = {LongNumberRange.between(10L, 20L)};
			final OwnerFilterIndex rebuilding = saturatedLongRangeIndex(LongNumberRange.class);
			final OwnerFilterIndex boxed = saturatedLongRangeIndex(NumberRange.class);
			final String before = thresholdsOf(rebuilding);
			assertEquals(before, thresholdsOf(boxed), "the two arms must start from one shape");

			rebuilding.addRecordDelta(1, added);
			boxed.addRecordDelta(1, added);
			assertEquals(
				thresholdsOf(boxed), thresholdsOf(rebuilding), "the added delta must agree with the boxed arm");
			rebuilding.removeRecordDelta(1, added);
			boxed.removeRecordDelta(1, added);
			assertEquals(before, thresholdsOf(rebuilding), "the removal must retire exactly the thresholds it added");
			assertEquals(thresholdsOf(boxed), thresholdsOf(rebuilding), "the removed delta must agree too");

			// the other arm: a removal that leaves the saturated range behind, which is what the consolidation of
			// the REMAINING ranges then has to clone
			final OwnerFilterIndex shrinking = saturatedLongRangeIndex(LongNumberRange.class);
			final OwnerFilterIndex shrinkingBoxed = saturatedLongRangeIndex(NumberRange.class);
			final LongNumberRange[] removed = {LongNumberRange.between(1L, 5L)};
			shrinking.removeRecordDelta(1, removed);
			shrinkingBoxed.removeRecordDelta(1, removed);
			assertEquals(thresholdsOf(shrinkingBoxed), thresholdsOf(shrinking));
			assertArrayEquals(
				new int[]{1}, shrinking.getRecordsEqualTo(LongNumberRange.between(Long.MIN_VALUE, Long.MAX_VALUE))
					.getArray(),
				"the saturated range must survive the removal of its sibling"
			);
		}

		/**
		 * Builds a range index holding one record whose value set contains a range saturating both open-bound
		 * sentinels plus an overlapping sibling — the shape a delta has to read back out of the tree and
		 * consolidate. A fresh index per arm keeps a half-applied delta from colouring the next one.
		 *
		 * @param attributeType the declared attribute type, which decides whether the keys are rebuilt or boxed
		 * @return the seeded index
		 */
		@Nonnull
		private OwnerFilterIndex saturatedLongRangeIndex(@Nonnull Class<?> attributeType) {
			final OwnerFilterIndex index = new OwnerFilterIndex(
				new AttributeIndexKey(null, "span", null), attributeType);
			index.addRecord(
				1,
				new LongNumberRange[]{
					LongNumberRange.between(Long.MIN_VALUE, Long.MAX_VALUE), LongNumberRange.between(1L, 5L)
				}
			);
			return index;
		}
	}

}
