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

package io.evitadb.index.price;

import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.core.exception.PriceAlreadyAssignedToEntityException;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIdContainerFormula;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIndexContainerFormula;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex.PriceListAndCurrencyPriceIndexTerminated;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.entityPrices.EntityPrices;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexStoragePart;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Currency;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
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
 * This class verifies contract of {@link PriceListAndCurrencyPriceSuperIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("PriceListAndCurrencyPriceSuperIndex functionality")
class PriceListAndCurrencyPriceSuperIndexTest {
	private static final PriceIndexKey PRICE_INDEX_KEY = new PriceIndexKey("basic", Currency.getInstance("CZK"), PriceInnerRecordHandling.NONE);
	private static final Consumer<PriceRecordContract> NOOP_PRICE_RECORD_CALLBACK = priceRecordContract -> {};
	private static final IntConsumer NOOP_NOT_FOUND_CALLBACK = notFound -> {};
	private static PriceRecordContract[] PRICE_RECORDS;

	@BeforeAll
	static void beforeAll() {
		PRICE_RECORDS = generateRandomPriceRecords(5000);
	}

	@AfterAll
	static void afterAll() {
		PRICE_RECORDS = null;
	}

	/**
	 * Tests verifying `getPriceRecords(Bitmap)` lookup by internal price ID bitmap.
	 */
	@Nested
	@DisplayName("Price record lookup by ID")
	class PriceRecordLookupByIdTest {

		@Test
		@DisplayName("finds all price records when requesting all IDs")
		void shouldFindAllPricesById() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY, new RangeIndex(), PRICE_RECORDS);

			final BaseBitmap allIds = new BaseBitmap(
				Arrays.stream(PRICE_RECORDS).mapToInt(PriceRecordContract::internalPriceId).toArray()
			);
			final PriceRecordContract[] foundPriceRecords =
				tested.getPriceRecords(allIds, NOOP_PRICE_RECORD_CALLBACK, NOOP_NOT_FOUND_CALLBACK);

			assertArrayEquals(PRICE_RECORDS, foundPriceRecords);
		}

		@Test
		@DisplayName("finds only the first price record when requesting first ID")
		void shouldFindFirstPriceOnlyById() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY, new RangeIndex(), PRICE_RECORDS);

			final BaseBitmap firstPriceId = new BaseBitmap(PRICE_RECORDS[0].internalPriceId());
			final NotFoundCollector notFoundCollector = new NotFoundCollector();
			final PriceRecordContract[] foundPriceRecords =
				tested.getPriceRecords(firstPriceId, NOOP_PRICE_RECORD_CALLBACK, notFoundCollector);

			assertArrayEquals(Arrays.copyOfRange(PRICE_RECORDS, 0, 1), foundPriceRecords);
			assertEquals(0, notFoundCollector.getArray().length);
		}

		@Test
		@DisplayName("finds only the last price record when requesting last ID")
		void shouldFindLastPriceOnlyById() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY, new RangeIndex(), PRICE_RECORDS);

			final BaseBitmap lastPriceId =
				new BaseBitmap(PRICE_RECORDS[PRICE_RECORDS.length - 1].internalPriceId());
			final NotFoundCollector notFoundCollector = new NotFoundCollector();
			final PriceRecordContract[] foundPriceRecords =
				tested.getPriceRecords(lastPriceId, NOOP_PRICE_RECORD_CALLBACK, notFoundCollector);

			assertArrayEquals(
				Arrays.copyOfRange(PRICE_RECORDS, PRICE_RECORDS.length - 1, PRICE_RECORDS.length),
				foundPriceRecords
			);
			assertEquals(0, notFoundCollector.getArray().length);
		}

		@Test
		@DisplayName("finds random subset of prices and reports non-existing IDs via callback")
		void shouldFindRandomPricesWithin() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY, new RangeIndex(), PRICE_RECORDS);

			final Random random = new Random(42);
			for (int i = 0; i < 100; i++) {
				final int includeEach = 5 + random.nextInt(45);
				final int excludeCount = 5 + random.nextInt(300);
				final PriceRecordContract[] pickedRecords = Arrays.stream(PRICE_RECORDS)
					.filter(it -> random.nextInt(includeEach) == 0)
					.toArray(PriceRecordContract[]::new);

				final BaseBitmap randomExistingPrices = new BaseBitmap(
					Arrays.stream(pickedRecords)
						.mapToInt(PriceRecordContract::internalPriceId)
						.toArray()
				);
				final BaseBitmap randomNonExistingPrices = new BaseBitmap(
					IntStream.generate(() -> 1 + random.nextInt(PRICE_RECORDS.length * 2))
						.filter(it -> ArrayUtils.binarySearch(
							PRICE_RECORDS, it,
							(price, pid) -> Integer.compare(price.internalPriceId(), pid)
						) < 0)
						.limit(excludeCount)
						.toArray()
				);
				final Bitmap aggregate = new OrFormula(
					new ConstantFormula(randomExistingPrices),
					new ConstantFormula(randomNonExistingPrices)
				).compute();

				final NotFoundCollector notFoundCollector = new NotFoundCollector();
				final PriceRecordContract[] foundPriceRecords =
					tested.getPriceRecords(aggregate, NOOP_PRICE_RECORD_CALLBACK, notFoundCollector);

				assertArrayEquals(pickedRecords, foundPriceRecords);
				assertArrayEquals(randomNonExistingPrices.getArray(), notFoundCollector.getArray());
			}
		}
	}

	/**
	 * Tests verifying unique ID generation via `TransactionalObjectVersion.SEQUENCE`.
	 */
	@Nested
	@DisplayName("STM invariants")
	class StmInvariantsTest {

		@Test
		@DisplayName("each instance gets a unique ID from TransactionalObjectVersion.SEQUENCE")
		void shouldAssignUniqueIdToEachInstance() {
			final PriceListAndCurrencyPriceSuperIndex first =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			final PriceListAndCurrencyPriceSuperIndex second =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);

			assertNotEquals(first.getId(), second.getId());
		}

		@Test
		@DisplayName("removeLayer cleans all seven nested producers")
		void shouldRemoveLayerFromAllNestedProducers() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);

			// modify inside a transaction so layers are created, then rollback
			assertStateAfterRollback(
				tested,
				index -> {
					index.addPrice(createPriceRecord(1, 1, 100), null);
				},
				(original, committed) -> {
					// after rollback, the original should still be intact
					// (removeLayer was called on all nested producers)
					assertNull(committed);
					assertTrue(original.isEmpty());
				}
			);
		}

		@Test
		@DisplayName("dirty commit produces new instance with all inner fields merged")
		void shouldCreateNewInstanceOnDirtyCommit() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);

			assertStateAfterCommit(
				tested,
				index -> {
					index.addPrice(createPriceRecord(1, 1, 100), null);
					index.addPrice(createPriceRecord(2, 2, 200), null);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertNotNull(committed);
					// committed copy should contain the prices
					assertFalse(committed.isEmpty());
					assertEquals(2, committed.getPriceRecords().length);
					assertArrayEquals(
						new int[]{100, 200},
						committed.getIndexedPriceEntityIds().getArray()
					);
					assertArrayEquals(
						new int[]{1, 2},
						committed.getIndexedPriceIds()
					);
				}
			);
		}

		@Test
		@DisplayName("clean (not dirty) commit returns the same instance")
		void shouldReturnSameInstanceOnCleanCommit() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);

			assertStateAfterCommit(
				tested,
				index -> {
					// no mutations
				},
				(original, committed) -> {
					assertSame(original, committed);
				}
			);
		}
	}

	/**
	 * Tests verifying transactional commit semantics for the price super index.
	 */
	@Nested
	@DisplayName("Transactional commit")
	class TransactionalCommitTest {

		@Test
		@DisplayName("committed copy contains added price")
		void shouldContainAddedPriceAfterCommit() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			final PriceRecordContract priceRecord = createPriceRecord(10, 10, 1);

			assertStateAfterCommit(
				tested,
				index -> {
					index.addPrice(priceRecord, null);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertFalse(committed.isEmpty());
					final PriceRecordContract[] records = committed.getPriceRecords();
					assertEquals(1, records.length);
					assertEquals(priceRecord, records[0]);
				}
			);
		}

		@Test
		@DisplayName("committed copy does not contain removed price")
		void shouldNotContainRemovedPriceAfterCommit() {
			final PriceRecordContract priceA = createPriceRecord(10, 10, 1);
			final PriceRecordContract priceB = createPriceRecord(20, 20, 2);
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(
					PRICE_INDEX_KEY, new RangeIndex(),
					new PriceRecordContract[]{priceA, priceB}
				);

			assertStateAfterCommit(
				tested,
				index -> {
					index.removePrice(1, 10, null);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					final PriceRecordContract[] records = committed.getPriceRecords();
					assertEquals(1, records.length);
					assertEquals(priceB, records[0]);
				}
			);
		}

		@Test
		@DisplayName("original remains unchanged after commit")
		void shouldLeaveOriginalUnchangedAfterCommit() {
			final PriceRecordContract priceA = createPriceRecord(10, 10, 1);
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(
					PRICE_INDEX_KEY, new RangeIndex(),
					new PriceRecordContract[]{priceA}
				);

			assertStateAfterCommit(
				tested,
				index -> {
					index.addPrice(createPriceRecord(20, 20, 2), null);
				},
				(original, committed) -> {
					// original should still have only 1 record
					assertEquals(1, original.getPriceRecords().length);
					// committed should have 2
					assertEquals(2, committed.getPriceRecords().length);
				}
			);
		}

		@Test
		@DisplayName(
			"all nested fields are reflected in committed copy"
		)
		void shouldReflectAllNestedFieldsInCommittedCopy() {
			final OffsetDateTime validFrom =
				OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
			final OffsetDateTime validTo =
				OffsetDateTime.of(2024, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
			final DateTimeRange validity = DateTimeRange.between(validFrom, validTo);
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);

			assertStateAfterCommit(
				tested,
				index -> {
					index.addPrice(createPriceRecord(5, 5, 42), validity);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					// validityIndex: prices valid at a point within the range
					final OffsetDateTime midPoint =
						OffsetDateTime.of(
							2024, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC
						);
					final PriceIdContainerFormula validFormula =
						committed.getIndexedRecordIdsValidInFormula(midPoint);
					assertNotNull(validFormula);
					final int[] validIds = validFormula.compute().getArray();
					assertArrayEquals(new int[]{5}, validIds);

					// entityPrices
					final EntityPrices entityPrices =
						committed.getEntityPrices(42);
					assertNotNull(entityPrices);
					assertFalse(entityPrices.isEmpty());

					// indexedPriceEntityIds
					assertArrayEquals(
						new int[]{42},
						committed.getIndexedPriceEntityIds().getArray()
					);

					// indexedPriceIds
					assertArrayEquals(new int[]{5}, committed.getIndexedPriceIds());
				}
			);
		}
	}

	/**
	 * Tests verifying transactional rollback semantics for the price super index.
	 */
	@Nested
	@DisplayName("Transactional rollback")
	class TransactionalRollbackTest {

		@Test
		@DisplayName("add price + rollback leaves original unchanged")
		void shouldLeaveOriginalUnchangedAfterRollbackOfAdd() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);

			assertStateAfterRollback(
				tested,
				index -> {
					index.addPrice(createPriceRecord(10, 10, 1), null);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isEmpty());
					assertEquals(0, original.getPriceRecords().length);
				}
			);
		}

		@Test
		@DisplayName("remove price + rollback leaves original price present")
		void shouldLeaveOriginalPricePresentAfterRollbackOfRemove() {
			final PriceRecordContract priceA = createPriceRecord(10, 10, 1);
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(
					PRICE_INDEX_KEY, new RangeIndex(),
					new PriceRecordContract[]{priceA}
				);

			assertStateAfterRollback(
				tested,
				index -> {
					index.removePrice(1, 10, null);
				},
				(original, committed) -> {
					assertNull(committed);
					assertFalse(original.isEmpty());
					assertEquals(1, original.getPriceRecords().length);
					assertEquals(priceA, original.getPriceRecords()[0]);
				}
			);
		}
	}

	/**
	 * Tests verifying non-transactional direct operations (addPrice, removePrice).
	 */
	@Nested
	@DisplayName("Non-transactional mode")
	class NonTransactionalModeTest {

		@Test
		@DisplayName("addPrice directly adds to instance")
		void shouldAddPriceDirectly() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);

			tested.addPrice(createPriceRecord(1, 1, 100), null);

			assertFalse(tested.isEmpty());
			assertEquals(1, tested.getPriceRecords().length);
			assertArrayEquals(
				new int[]{100},
				tested.getIndexedPriceEntityIds().getArray()
			);
		}

		@Test
		@DisplayName("removePrice directly removes from instance")
		void shouldRemovePriceDirectly() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			tested.addPrice(createPriceRecord(1, 1, 100), null);

			tested.removePrice(100, 1, null);

			assertTrue(tested.isEmpty());
			assertEquals(0, tested.getPriceRecords().length);
		}

		@Test
		@DisplayName("addPrice invalidates memoizedIndexedPriceIds cache")
		void shouldInvalidateMemoizedCacheOnAdd() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			tested.addPrice(createPriceRecord(1, 1, 100), null);

			// read to populate cache
			final int[] firstRead = tested.getIndexedPriceIds();
			assertArrayEquals(new int[]{1}, firstRead);

			// add another price -- cache should be invalidated
			tested.addPrice(createPriceRecord(2, 2, 200), null);

			final int[] secondRead = tested.getIndexedPriceIds();
			assertArrayEquals(new int[]{1, 2}, secondRead);
		}

		@Test
		@DisplayName("removePrice invalidates memoizedIndexedPriceIds cache")
		void shouldInvalidateMemoizedCacheOnRemove() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			tested.addPrice(createPriceRecord(1, 1, 100), null);
			tested.addPrice(createPriceRecord(2, 2, 200), null);

			// read to populate cache
			final int[] firstRead = tested.getIndexedPriceIds();
			assertArrayEquals(new int[]{1, 2}, firstRead);

			// remove a price -- cache should be invalidated
			tested.removePrice(100, 1, null);

			final int[] secondRead = tested.getIndexedPriceIds();
			assertArrayEquals(new int[]{2}, secondRead);
		}

		@Test
		@DisplayName(
			"memoizedIndexedPriceIds re-populates from indexedPriceIds"
		)
		void shouldRepopulateMemoizedIndexedPriceIds() {
			final PriceRecordContract price = createPriceRecord(5, 5, 42);
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(
					PRICE_INDEX_KEY, new RangeIndex(),
					new PriceRecordContract[]{price}
				);

			// constructor pre-populates memoizedIndexedPriceIds
			final int[] firstRead = tested.getIndexedPriceIds();
			assertArrayEquals(new int[]{5}, firstRead);

			// second read should return the same cached array
			final int[] secondRead = tested.getIndexedPriceIds();
			assertSame(firstRead, secondRead);
		}
	}

	/**
	 * Tests verifying termination lifecycle behavior.
	 */
	@Nested
	@DisplayName("Termination lifecycle")
	class TerminationLifecycleTest {

		@Test
		@DisplayName("isTerminated() returns false initially")
		void shouldNotBeTerminatedInitially() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);

			assertFalse(tested.isTerminated());
		}

		@Test
		@DisplayName("isTerminated() returns true after terminate()")
		void shouldBeTerminatedAfterTerminate() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);

			tested.terminate();

			assertTrue(tested.isTerminated());
		}

		@Test
		@DisplayName(
			"terminate() followed by addPrice throws terminated exception"
		)
		void shouldThrowOnAddPriceAfterTermination() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			tested.terminate();

			assertThrows(
				PriceListAndCurrencyPriceIndexTerminated.class,
				() -> tested.addPrice(createPriceRecord(1, 1, 100), null)
			);
		}

		@Test
		@DisplayName(
			"terminate() followed by removePrice throws terminated exception"
		)
		void shouldThrowOnRemovePriceAfterTermination() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			tested.terminate();

			assertThrows(
				PriceListAndCurrencyPriceIndexTerminated.class,
				() -> tested.removePrice(1, 1, null)
			);
		}

		@Test
		@DisplayName(
			"terminate() followed by isEmpty throws terminated exception"
		)
		void shouldThrowOnIsEmptyAfterTermination() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			tested.terminate();

			assertThrows(
				PriceListAndCurrencyPriceIndexTerminated.class,
				() -> tested.isEmpty()
			);
		}

		@Test
		@DisplayName(
			"terminate() followed by getPriceRecords throws terminated exception"
		)
		void shouldThrowOnGetPriceRecordsAfterTermination() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			tested.terminate();

			assertThrows(
				PriceListAndCurrencyPriceIndexTerminated.class,
				() -> tested.getPriceRecords()
			);
		}

		@Test
		@DisplayName("toString() includes '(TERMINATED)' suffix after terminate")
		void shouldIncludeTerminatedSuffixInToString() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);

			final String beforeTerminate = tested.toString();
			assertFalse(beforeTerminate.contains("(TERMINATED)"));

			tested.terminate();

			final String afterTerminate = tested.toString();
			assertTrue(afterTerminate.contains("(TERMINATED)"));
		}
	}

	/**
	 * Tests verifying individual functional methods of the price super index.
	 */
	@Nested
	@DisplayName("Functional methods")
	class FunctionalMethodsTest {

		@Test
		@DisplayName(
			"getIndexedPriceEntityIdsFormula() returns EmptyFormula when empty"
		)
		void shouldReturnEmptyFormulaWhenNoEntities() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);

			final Formula formula = tested.getIndexedPriceEntityIdsFormula();

			assertSame(EmptyFormula.INSTANCE, formula);
		}

		@Test
		@DisplayName(
			"getIndexedPriceEntityIdsFormula() returns ConstantFormula when not empty"
		)
		void shouldReturnConstantFormulaWhenEntitiesExist() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			tested.addPrice(createPriceRecord(1, 1, 100), null);

			final Formula formula = tested.getIndexedPriceEntityIdsFormula();

			assertInstanceOf(ConstantFormula.class, formula);
			assertArrayEquals(
				new int[]{100}, formula.compute().getArray()
			);
		}

		@Test
		@DisplayName(
			"getIndexedRecordIdsValidInFormula() returns valid records"
		)
		void shouldReturnValidRecordsForGivenMoment() {
			final OffsetDateTime validFrom =
				OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
			final OffsetDateTime validTo =
				OffsetDateTime.of(2024, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
			final DateTimeRange validity = DateTimeRange.between(validFrom, validTo);

			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			tested.addPrice(createPriceRecord(10, 10, 1), validity);
			tested.addPrice(createPriceRecord(20, 20, 2), null);

			// moment within the range
			final OffsetDateTime withinRange =
				OffsetDateTime.of(2024, 6, 15, 12, 0, 0, 0, ZoneOffset.UTC);
			final PriceIdContainerFormula formula =
				tested.getIndexedRecordIdsValidInFormula(withinRange);
			final int[] validIds = formula.compute().getArray();

			// both price 10 (explicitly valid) and 20 (always valid, null range)
			assertTrue(validIds.length >= 1);
			// price 10 should be in the result
			assertTrue(
				Arrays.stream(validIds).anyMatch(id -> id == 10)
			);
			// price 20 with null validity gets MIN_VALUE..MAX_VALUE range, always valid
			assertTrue(
				Arrays.stream(validIds).anyMatch(id -> id == 20)
			);
		}

		@Test
		@DisplayName(
			"getInternalPriceIdsForEntity() returns ids for known entity"
		)
		void shouldReturnPriceIdsForKnownEntity() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			tested.addPrice(createPriceRecord(10, 10, 42), null);
			tested.addPrice(createPriceRecord(20, 20, 42), null);

			final int[] priceIds = tested.getInternalPriceIdsForEntity(42);

			assertNotNull(priceIds);
			assertEquals(2, priceIds.length);
		}

		@Test
		@DisplayName(
			"getInternalPriceIdsForEntity() returns null for unknown entity"
		)
		void shouldReturnNullForUnknownEntity() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			tested.addPrice(createPriceRecord(10, 10, 42), null);

			final int[] priceIds = tested.getInternalPriceIdsForEntity(999);

			assertNull(priceIds);
		}

		@Test
		@DisplayName("getLowestPriceRecordsForEntity() returns records for known entity")
		void shouldReturnLowestPriceRecordsForKnownEntity() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			tested.addPrice(createPriceRecord(10, 10, 42), null);

			final PriceRecordContract[] lowestRecords =
				tested.getLowestPriceRecordsForEntity(42);

			assertNotNull(lowestRecords);
			assertTrue(lowestRecords.length >= 1);
		}

		@Test
		@DisplayName("getLowestPriceRecordsForEntity() returns null for unknown entity")
		void shouldReturnNullLowestPriceRecordsForUnknownEntity() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);

			final PriceRecordContract[] lowestRecords =
				tested.getLowestPriceRecordsForEntity(999);

			assertNull(lowestRecords);
		}

		@Test
		@DisplayName("getEntityPrices() returns EntityPrices for known entity")
		void shouldReturnEntityPricesForKnownEntity() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			tested.addPrice(createPriceRecord(10, 10, 42), null);

			final EntityPrices entityPrices = tested.getEntityPrices(42);

			assertNotNull(entityPrices);
			assertFalse(entityPrices.isEmpty());
		}

		@Test
		@DisplayName("getEntityPrices() throws for unknown entity")
		void shouldThrowForUnknownEntityPrices() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);

			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				() -> tested.getEntityPrices(999)
			);
			assertTrue(ex.getMessage().contains("999"));
		}

		@Test
		@DisplayName("getPriceRecord() returns record for known internal price id")
		void shouldReturnPriceRecordForKnownId() {
			final PriceRecordContract price = createPriceRecord(10, 10, 42);
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(
					PRICE_INDEX_KEY, new RangeIndex(),
					new PriceRecordContract[]{price}
				);

			final PriceRecordContract found = tested.getPriceRecord(10);

			assertEquals(price, found);
		}

		@Test
		@DisplayName("getPriceRecord() throws for unknown internal price id")
		void shouldThrowForUnknownPriceRecord() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);

			final IllegalArgumentException ex = assertThrows(
				IllegalArgumentException.class,
				() -> tested.getPriceRecord(999)
			);
			assertTrue(ex.getMessage().contains("999"));
		}

		@Test
		@DisplayName("isEmpty() returns true for newly created index")
		void shouldReturnTrueForEmptyIndex() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);

			assertTrue(tested.isEmpty());
		}

		@Test
		@DisplayName("isEmpty() returns false when prices are present")
		void shouldReturnFalseForNonEmptyIndex() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			tested.addPrice(createPriceRecord(1, 1, 100), null);

			assertFalse(tested.isEmpty());
		}

		@Test
		@DisplayName(
			"createPriceIndexFormulaWithAllRecords() returns PriceIndexContainerFormula"
		)
		void shouldReturnPriceIndexContainerFormula() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			tested.addPrice(createPriceRecord(1, 1, 100), null);

			final Formula formula =
				tested.createPriceIndexFormulaWithAllRecords();

			assertInstanceOf(PriceIndexContainerFormula.class, formula);
		}
	}

	/**
	 * Tests verifying storage part creation and dirty flag management.
	 */
	@Nested
	@DisplayName("Storage part and dirty flag")
	class StoragePartTest {

		@Test
		@DisplayName("createStoragePart() returns part when dirty")
		void shouldReturnStoragePartWhenDirty() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			tested.addPrice(createPriceRecord(1, 1, 100), null);

			final StoragePart part = tested.createStoragePart(1);

			assertNotNull(part);
			assertInstanceOf(
				PriceListAndCurrencySuperIndexStoragePart.class, part
			);
		}

		@Test
		@DisplayName("createStoragePart() returns null when clean")
		void shouldReturnNullWhenClean() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);

			final StoragePart part = tested.createStoragePart(1);

			assertNull(part);
		}

		@Test
		@DisplayName("resetDirty() causes createStoragePart() to return null")
		void shouldReturnNullAfterResetDirty() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			tested.addPrice(createPriceRecord(1, 1, 100), null);

			// dirty now
			assertNotNull(tested.createStoragePart(1));

			tested.resetDirty();

			// clean now
			assertNull(tested.createStoragePart(1));
		}
	}

	/**
	 * Tests verifying error handling for duplicate prices, non-existent removals,
	 * and the `getPriceRecords(Bitmap)` default method error-throwing callback.
	 */
	@Nested
	@DisplayName("Error handling")
	class ErrorHandlingTest {

		@Test
		@DisplayName("addPrice throws PriceAlreadyAssignedToEntityException for duplicate")
		void shouldThrowOnDuplicatePriceAdd() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			tested.addPrice(createPriceRecord(10, 10, 42), null);

			final PriceAlreadyAssignedToEntityException ex = assertThrows(
				PriceAlreadyAssignedToEntityException.class,
				() -> tested.addPrice(createPriceRecord(10, 10, 42), null)
			);

			assertEquals(10, ex.getPriceId());
			assertEquals(42, ex.getEntityPrimaryKey());
		}

		@Test
		@DisplayName("removePrice throws for non-existent internal price id")
		void shouldThrowOnRemoveNonExistentPrice() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);

			assertThrows(
				IllegalArgumentException.class,
				() -> tested.removePrice(1, 999, null)
			);
		}

		@Test
		@DisplayName(
			"getPriceRecords(Bitmap) default method throws for missing id"
		)
		void shouldThrowOnMissingPriceIdInDefaultGetPriceRecords() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);
			tested.addPrice(createPriceRecord(1, 1, 100), null);

			// request a price id that does not exist
			final BaseBitmap requestedIds = new BaseBitmap(1, 999);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> tested.getPriceRecords(requestedIds)
			);
		}
	}

	/**
	 * Tests verifying the full addPrice/removePrice lifecycle using
	 * the empty constructor `PriceListAndCurrencyPriceSuperIndex(PriceIndexKey)`.
	 */
	@Nested
	@DisplayName("Empty constructor lifecycle")
	class EmptyConstructorLifecycleTest {

		@Test
		@DisplayName("full add-query-remove-query lifecycle")
		void shouldSupportFullLifecycleThroughEmptyConstructor() {
			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);

			assertTrue(tested.isEmpty());

			// add prices
			tested.addPrice(createPriceRecord(10, 10, 1), null);
			tested.addPrice(createPriceRecord(20, 20, 2), null);

			assertFalse(tested.isEmpty());
			assertEquals(2, tested.getPriceRecords().length);
			assertArrayEquals(
				new int[]{1, 2},
				tested.getIndexedPriceEntityIds().getArray()
			);
			assertArrayEquals(
				new int[]{10, 20},
				tested.getIndexedPriceIds()
			);

			// remove one price
			tested.removePrice(1, 10, null);

			assertEquals(1, tested.getPriceRecords().length);
			assertArrayEquals(
				new int[]{2},
				tested.getIndexedPriceEntityIds().getArray()
			);

			// remove last price
			tested.removePrice(2, 20, null);

			assertTrue(tested.isEmpty());
			assertEquals(0, tested.getPriceRecords().length);
		}

		@Test
		@DisplayName("add price with validity and remove with same validity")
		void shouldAddAndRemovePriceWithValidity() {
			final OffsetDateTime validFrom =
				OffsetDateTime.of(2024, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC);
			final OffsetDateTime validTo =
				OffsetDateTime.of(2024, 3, 31, 23, 59, 59, 0, ZoneOffset.UTC);
			final DateTimeRange validity = DateTimeRange.between(validFrom, validTo);

			final PriceListAndCurrencyPriceSuperIndex tested =
				new PriceListAndCurrencyPriceSuperIndex(PRICE_INDEX_KEY);

			tested.addPrice(createPriceRecord(10, 10, 1), validity);
			assertFalse(tested.isEmpty());

			// query within validity range
			final OffsetDateTime midPoint =
				OffsetDateTime.of(2024, 3, 15, 12, 0, 0, 0, ZoneOffset.UTC);
			final PriceIdContainerFormula formula =
				tested.getIndexedRecordIdsValidInFormula(midPoint);
			assertArrayEquals(
				new int[]{10}, formula.compute().getArray()
			);

			// remove with same validity
			tested.removePrice(1, 10, validity);
			assertTrue(tested.isEmpty());
		}
	}

	/**
	 * Creates a `PriceRecord` with internalPriceId equal to priceId, the given
	 * entityPrimaryKey, and derived tax values.
	 */
	@Nonnull
	private static PriceRecordContract createPriceRecord(
		int internalPriceId,
		int priceId,
		int entityPrimaryKey
	) {
		return new PriceRecord(
			internalPriceId,
			priceId,
			entityPrimaryKey,
			12100,
			10000
		);
	}

	private static PriceRecordContract[] generateRandomPriceRecords(int number) {
		final Random random = new Random(42);
		final PriceRecordContract[] result = new PriceRecordContract[number];
		int priceId = 0;
		for (int i = 0; i < number; i++) {
			final int price = Math.abs(random.nextInt());
			final int entityPrimaryKey = random.nextInt();
			priceId += 1 + random.nextInt(1000);
			result[i] = new PriceRecord(
				priceId,
				priceId,
				entityPrimaryKey,
				(int) (price * 1.21),
				price
			);
		}
		return result;
	}

	private static class NotFoundCollector implements IntConsumer {
		private final RoaringBitmapWriter<RoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();

		@Override
		public void accept(int value) {
			this.writer.add(value);
		}

		public int[] getArray() {
			return this.writer.get().toArray();
		}

	}
}
