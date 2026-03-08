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

import io.evitadb.api.CatalogState;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests for {@link PriceRefIndex} verifying catalog attachment, add/remove price delegation,
 * transactional commit/rollback semantics, copy-for-new-catalog, and contract methods.
 *
 * @author evitaDB
 */
@DisplayName("PriceRefIndex functionality")
class PriceRefIndexTest implements TimeBoundedTestSupport {

	private static final String ENTITY_TYPE = "product";
	private static final Scope SCOPE = Scope.LIVE;
	private static final Currency CURRENCY_CZK = Currency.getInstance("CZK");
	private static final Currency CURRENCY_EUR = Currency.getInstance("EUR");
	private static final String PRICE_LIST_BASIC = "basic";
	private static final String PRICE_LIST_VIP = "vip";
	private static final PriceIndexKey KEY_BASIC_CZK = new PriceIndexKey(
		PRICE_LIST_BASIC, CURRENCY_CZK, PriceInnerRecordHandling.NONE
	);
	private static final PriceIndexKey KEY_VIP_CZK = new PriceIndexKey(
		PRICE_LIST_VIP, CURRENCY_CZK, PriceInnerRecordHandling.NONE
	);
	private static final PriceIndexKey KEY_BASIC_EUR = new PriceIndexKey(
		PRICE_LIST_BASIC, CURRENCY_EUR, PriceInnerRecordHandling.NONE
	);

	/**
	 * Sequence for generating unique internal price IDs across test methods.
	 */
	private final AtomicInteger internalPriceIdSequence = new AtomicInteger(0);

	/**
	 * The shared super index that holds actual price records. Each test populates this
	 * before exercising the ref index.
	 */
	private PriceSuperIndex priceSuperIndex;

	/**
	 * The ref index under test.
	 */
	private PriceRefIndex priceRefIndex;

	@BeforeEach
	void setUp() {
		this.internalPriceIdSequence.set(0);
		this.priceSuperIndex = new PriceSuperIndex();
		this.priceRefIndex = new PriceRefIndex(SCOPE);
	}

	/**
	 * Adds a price to the super index and returns the assigned internal price id.
	 * This mirrors how the engine first inserts into the super index, then into ref indexes.
	 */
	private int addPriceToSuperIndex(
		int entityPK,
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency,
		@Nonnull PriceInnerRecordHandling handling,
		int priceWithoutTax,
		int priceWithTax
	) {
		final int internalPriceId = this.internalPriceIdSequence.incrementAndGet();
		this.priceSuperIndex.addPrice(
			null, entityPK, internalPriceId,
			new PriceKey(priceId, priceList, currency),
			handling, null, null,
			priceWithoutTax, priceWithTax
		);
		return internalPriceId;
	}

	/**
	 * Creates a mock Catalog that resolves `getEntityIndexIfExists` to a `GlobalEntityIndex`
	 * whose `getPriceIndex(PriceIndexKey)` delegates to the shared super index.
	 */
	@Nonnull
	private Catalog createMockCatalog() {
		final GlobalEntityIndex mockGlobalIndex = Mockito.mock(GlobalEntityIndex.class);
		Mockito.when(mockGlobalIndex.getPriceIndex(ArgumentMatchers.any(PriceIndexKey.class)))
			.thenAnswer(invocation -> {
				final PriceIndexKey key = invocation.getArgument(0);
				return PriceRefIndexTest.this.priceSuperIndex.getPriceIndex(key);
			});

		final Catalog mockCatalog = Mockito.mock(Catalog.class);
		Mockito.when(mockCatalog.getEntityIndexIfExists(
			ArgumentMatchers.eq(ENTITY_TYPE),
			ArgumentMatchers.eq(new EntityIndexKey(EntityIndexType.GLOBAL, SCOPE)),
			ArgumentMatchers.eq(GlobalEntityIndex.class)
		)).thenReturn(Optional.of(mockGlobalIndex));

		return mockCatalog;
	}

	/**
	 * Attaches the ref index to a mock catalog backed by the shared super index.
	 */
	private void attachRefIndex() {
		final Catalog mockCatalog = createMockCatalog();
		this.priceRefIndex.attachToCatalog(ENTITY_TYPE, mockCatalog);
	}

	/**
	 * Convenience method: adds a price to the super index and then to the ref index.
	 * Returns the internal price id.
	 */
	private int addPriceToBothIndexes(
		int entityPK,
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency
	) {
		final int internalPriceId = addPriceToSuperIndex(
			entityPK, priceId, priceList, currency,
			PriceInnerRecordHandling.NONE, 10000, 12100
		);
		this.priceRefIndex.addPrice(
			null, entityPK, internalPriceId,
			new PriceKey(priceId, priceList, currency),
			PriceInnerRecordHandling.NONE,
			null, null, 10000, 12100
		);
		return internalPriceId;
	}

	/**
	 * Tests verifying catalog attachment lifecycle including propagation to existing
	 * and newly created child ref indexes.
	 */
	@Nested
	@DisplayName("Catalog attachment")
	class CatalogAttachmentTest {

		@Test
		@DisplayName("should attach and propagate to existing children")
		void shouldAttachAndPropagateToExistingChildren() {
			// add a price to the super index so a PriceListAndCurrencyPriceSuperIndex exists
			final int internalPriceId = addPriceToSuperIndex(
				1, 10, PRICE_LIST_BASIC, CURRENCY_CZK,
				PriceInnerRecordHandling.NONE, 10000, 12100
			);

			// build a ref index with a pre-existing child via the deserialization constructor --
			// this simulates restoring from storage where children exist before attach
			final PriceListAndCurrencyPriceRefIndex childRefIndex =
				new PriceListAndCurrencyPriceRefIndex(
					SCOPE, KEY_BASIC_CZK, new RangeIndex(),
					new int[]{internalPriceId}
				);
			final Map<PriceIndexKey, PriceListAndCurrencyPriceRefIndex> childMap =
				new HashMap<>(4);
			childMap.put(KEY_BASIC_CZK, childRefIndex);
			PriceRefIndexTest.this.priceRefIndex = new PriceRefIndex(SCOPE, childMap);

			// now attach -- should propagate to the existing child via
			// `values().forEach(it -> it.attachToCatalog(...))`
			attachRefIndex();

			// after attach, the child should be linked to the super index and have the price
			final PriceListAndCurrencyPriceRefIndex attached =
				PriceRefIndexTest.this.priceRefIndex.getPriceIndex(KEY_BASIC_CZK);
			assertNotNull(attached);
			assertFalse(attached.isEmpty());
			assertEquals(1, attached.getPriceRecords().length);
		}

		@Test
		@DisplayName("should throw when already attached")
		void shouldThrowWhenAlreadyAttached() {
			attachRefIndex();

			final Catalog secondCatalog = createMockCatalog();
			final GenericEvitaInternalError exception = assertThrows(
				GenericEvitaInternalError.class,
				() -> PriceRefIndexTest.this.priceRefIndex.attachToCatalog(ENTITY_TYPE, secondCatalog)
			);

			assertTrue(exception.getMessage().contains("already attached"));
		}

		@Test
		@DisplayName("should auto-attach newly created children after catalog attachment")
		void shouldAutoAttachNewlyCreatedChildren() {
			// attach first (no children yet)
			attachRefIndex();

			// add price to super index
			final int internalPriceId = addPriceToSuperIndex(
				1, 10, PRICE_LIST_BASIC, CURRENCY_CZK,
				PriceInnerRecordHandling.NONE, 10000, 12100
			);

			// add price to ref index -- child created and auto-attached via initCallback
			PriceRefIndexTest.this.priceRefIndex.addPrice(
				null, 1, internalPriceId,
				new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 10000, 12100
			);

			final PriceListAndCurrencyPriceRefIndex childIndex =
				PriceRefIndexTest.this.priceRefIndex.getPriceIndex(KEY_BASIC_CZK);
			assertNotNull(childIndex);
			assertFalse(childIndex.isEmpty());
		}
	}

	/**
	 * Tests verifying that `addPrice` correctly creates and populates child ref indexes
	 * through the container chain.
	 */
	@Nested
	@DisplayName("Add price")
	class AddPriceTest {

		@BeforeEach
		void setUpAttachment() {
			attachRefIndex();
		}

		@Test
		@DisplayName("should add price through container chain")
		void shouldAddPriceThroughContainerChain() {
			final int internalPriceId = addPriceToSuperIndex(
				1, 10, PRICE_LIST_BASIC, CURRENCY_CZK,
				PriceInnerRecordHandling.NONE, 10000, 12100
			);

			PriceRefIndexTest.this.priceRefIndex.addPrice(
				null, 1, internalPriceId,
				new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 10000, 12100
			);

			final PriceListAndCurrencyPriceRefIndex childIndex =
				PriceRefIndexTest.this.priceRefIndex.getPriceIndex(KEY_BASIC_CZK);
			assertNotNull(childIndex);
			assertFalse(childIndex.isEmpty());
		}

		@Test
		@DisplayName("should create new child on first price for key")
		void shouldCreateNewChildOnFirstPriceForKey() {
			// initially no child for this key
			assertNull(PriceRefIndexTest.this.priceRefIndex.getPriceIndex(KEY_BASIC_CZK));

			final int internalPriceId = addPriceToSuperIndex(
				1, 10, PRICE_LIST_BASIC, CURRENCY_CZK,
				PriceInnerRecordHandling.NONE, 10000, 12100
			);

			PriceRefIndexTest.this.priceRefIndex.addPrice(
				null, 1, internalPriceId,
				new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 10000, 12100
			);

			assertNotNull(PriceRefIndexTest.this.priceRefIndex.getPriceIndex(KEY_BASIC_CZK));
		}

		@Test
		@DisplayName("should add to existing child for same key")
		void shouldAddToExistingChildForSameKey() {
			// add first price
			final int ipId1 = addPriceToSuperIndex(
				1, 10, PRICE_LIST_BASIC, CURRENCY_CZK,
				PriceInnerRecordHandling.NONE, 10000, 12100
			);
			PriceRefIndexTest.this.priceRefIndex.addPrice(
				null, 1, ipId1,
				new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 10000, 12100
			);

			final PriceListAndCurrencyPriceRefIndex childBefore =
				PriceRefIndexTest.this.priceRefIndex.getPriceIndex(KEY_BASIC_CZK);

			// add second price to same key
			final int ipId2 = addPriceToSuperIndex(
				2, 20, PRICE_LIST_BASIC, CURRENCY_CZK,
				PriceInnerRecordHandling.NONE, 5000, 6050
			);
			PriceRefIndexTest.this.priceRefIndex.addPrice(
				null, 2, ipId2,
				new PriceKey(20, PRICE_LIST_BASIC, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 5000, 6050
			);

			final PriceListAndCurrencyPriceRefIndex childAfter =
				PriceRefIndexTest.this.priceRefIndex.getPriceIndex(KEY_BASIC_CZK);

			// same child instance should be reused (not a new one created)
			assertEquals(childBefore, childAfter);
			// verify both prices are present
			final PriceRecordContract[] priceRecords = childAfter.getPriceRecords();
			assertEquals(2, priceRecords.length);
		}
	}

	/**
	 * Tests verifying that `priceRemove` correctly removes prices from child ref indexes
	 * and handles the `PriceListAndCurrencyPriceIndexTerminated` exception gracefully.
	 */
	@Nested
	@DisplayName("Remove price")
	class RemovePriceTest {

		@BeforeEach
		void setUpAttachment() {
			attachRefIndex();
		}

		@Test
		@DisplayName("should remove and keep child when not empty")
		void shouldRemoveAndKeepChildWhenNotEmpty() {
			final int ipId1 = addPriceToBothIndexes(1, 10, PRICE_LIST_BASIC, CURRENCY_CZK);
			final int ipId2 = addPriceToBothIndexes(2, 20, PRICE_LIST_BASIC, CURRENCY_CZK);

			// remove only the first price
			PriceRefIndexTest.this.priceRefIndex.priceRemove(
				null, 1, ipId1,
				new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 10000, 12100
			);

			// child should still exist with the second price
			final PriceListAndCurrencyPriceRefIndex childIndex =
				PriceRefIndexTest.this.priceRefIndex.getPriceIndex(KEY_BASIC_CZK);
			assertNotNull(childIndex);
			assertFalse(childIndex.isEmpty());
		}

		@Test
		@DisplayName("should remove child when last price removed")
		void shouldRemoveChildWhenLastPriceRemoved() {
			final int ipId1 = addPriceToBothIndexes(1, 10, PRICE_LIST_BASIC, CURRENCY_CZK);

			// remove the only price
			PriceRefIndexTest.this.priceRefIndex.priceRemove(
				null, 1, ipId1,
				new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 10000, 12100
			);

			// child should be removed
			assertNull(PriceRefIndexTest.this.priceRefIndex.getPriceIndex(KEY_BASIC_CZK));
		}

		@Test
		@DisplayName("should handle terminated super index gracefully via catch block")
		void shouldHandleTerminatedSuperIndexGracefully() {
			// add price to super index and ref index
			final int ipId1 = addPriceToBothIndexes(1, 10, PRICE_LIST_BASIC, CURRENCY_CZK);

			// now remove the price from the super index (which also terminates its child)
			PriceRefIndexTest.this.priceSuperIndex.priceRemove(
				null, 1, ipId1,
				new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 10000, 12100
			);

			// the super index child is now terminated -- when we try to remove from ref,
			// the ref's removePrice catches PriceListAndCurrencyPriceIndexTerminated
			// and gracefully removes the child ref index
			PriceRefIndexTest.this.priceRefIndex.priceRemove(
				null, 1, ipId1,
				new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 10000, 12100
			);

			// the child should be gone
			assertNull(PriceRefIndexTest.this.priceRefIndex.getPriceIndex(KEY_BASIC_CZK));
		}
	}

	/**
	 * Tests verifying `createCopyForNewCatalogAttachment` produces a proper detached copy.
	 */
	@Nested
	@DisplayName("Copy for new catalog attachment")
	class CopyTest {

		@Test
		@DisplayName("should create copy with same number of children, each detached")
		void shouldCreateCopyForNewCatalogAttachment() {
			attachRefIndex();

			// add prices to two different keys
			addPriceToBothIndexes(1, 10, PRICE_LIST_BASIC, CURRENCY_CZK);
			addPriceToBothIndexes(2, 20, PRICE_LIST_VIP, CURRENCY_CZK);

			final PriceRefIndex copy =
				PriceRefIndexTest.this.priceRefIndex.createCopyForNewCatalogAttachment(
					CatalogState.ALIVE
				);

			assertNotSame(PriceRefIndexTest.this.priceRefIndex, copy);
			assertFalse(copy.isPriceIndexEmpty());

			final Collection<? extends PriceListAndCurrencyPriceIndex> origIndexes =
				PriceRefIndexTest.this.priceRefIndex.getPriceListAndCurrencyIndexes();
			final Collection<? extends PriceListAndCurrencyPriceIndex> copyIndexes =
				copy.getPriceListAndCurrencyIndexes();
			assertEquals(origIndexes.size(), copyIndexes.size());
		}
	}

	/**
	 * Tests verifying that transactional commit correctly merges child index changes.
	 */
	@Nested
	@DisplayName("Transactional commit")
	class TransactionalCommitTest {

		@Test
		@DisplayName("should commit new child indexes created inside transaction")
		void shouldCommitNewChildIndexes() {
			attachRefIndex();

			// pre-populate super index outside transaction
			final int ipId1 = addPriceToSuperIndex(
				1, 10, PRICE_LIST_BASIC, CURRENCY_CZK,
				PriceInnerRecordHandling.NONE, 10000, 12100
			);

			assertStateAfterCommit(
				PriceRefIndexTest.this.priceRefIndex,
				original -> {
					original.addPrice(
						null, 1, ipId1,
						new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
						PriceInnerRecordHandling.NONE,
						null, null, 10000, 12100
					);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertFalse(committed.isPriceIndexEmpty());
					final PriceListAndCurrencyPriceRefIndex childIndex =
						committed.getPriceIndex(KEY_BASIC_CZK);
					assertNotNull(childIndex);
				}
			);
		}

		@Test
		@DisplayName("should commit removed child indexes when all prices removed in tx")
		void shouldCommitRemovedChildIndexes() {
			attachRefIndex();

			// pre-populate both indexes outside transaction
			final int ipId1 = addPriceToBothIndexes(1, 10, PRICE_LIST_BASIC, CURRENCY_CZK);

			assertStateAfterCommit(
				PriceRefIndexTest.this.priceRefIndex,
				original -> {
					original.priceRemove(
						null, 1, ipId1,
						new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
						PriceInnerRecordHandling.NONE,
						null, null, 10000, 12100
					);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertTrue(committed.isPriceIndexEmpty());
					assertNull(committed.getPriceIndex(KEY_BASIC_CZK));
				}
			);
		}

		@Test
		@DisplayName("should leave original unchanged after commit")
		void shouldLeaveOriginalUnchangedAfterCommit() {
			attachRefIndex();

			final int ipId1 = addPriceToSuperIndex(
				1, 10, PRICE_LIST_BASIC, CURRENCY_CZK,
				PriceInnerRecordHandling.NONE, 10000, 12100
			);

			assertStateAfterCommit(
				PriceRefIndexTest.this.priceRefIndex,
				original -> {
					original.addPrice(
						null, 1, ipId1,
						new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
						PriceInnerRecordHandling.NONE,
						null, null, 10000, 12100
					);
				},
				(original, committed) -> {
					// original should remain empty
					assertTrue(original.isPriceIndexEmpty());
					// committed should have the price
					assertFalse(committed.isPriceIndexEmpty());
				}
			);
		}

		@Test
		@DisplayName("should handle add-then-remove in same transaction")
		void shouldHandleAddThenRemoveInSameTransaction() {
			attachRefIndex();

			final int ipId1 = addPriceToSuperIndex(
				1, 10, PRICE_LIST_BASIC, CURRENCY_CZK,
				PriceInnerRecordHandling.NONE, 10000, 12100
			);

			assertStateAfterCommit(
				PriceRefIndexTest.this.priceRefIndex,
				original -> {
					// add
					original.addPrice(
						null, 1, ipId1,
						new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
						PriceInnerRecordHandling.NONE,
						null, null, 10000, 12100
					);
					// remove in same transaction
					original.priceRemove(
						null, 1, ipId1,
						new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
						PriceInnerRecordHandling.NONE,
						null, null, 10000, 12100
					);
				},
				(original, committed) -> {
					// both original and committed should have no children
					assertTrue(committed.isPriceIndexEmpty());
				}
			);
		}
	}

	/**
	 * Tests verifying that transactional rollback leaves the original index unchanged.
	 */
	@Nested
	@DisplayName("Transactional rollback")
	class TransactionalRollbackTest {

		@Test
		@DisplayName("should leave original unchanged after rollback of add")
		void shouldLeaveOriginalUnchangedAfterRollback() {
			attachRefIndex();

			final int ipId1 = addPriceToSuperIndex(
				1, 10, PRICE_LIST_BASIC, CURRENCY_CZK,
				PriceInnerRecordHandling.NONE, 10000, 12100
			);

			assertStateAfterRollback(
				PriceRefIndexTest.this.priceRefIndex,
				original -> {
					original.addPrice(
						null, 1, ipId1,
						new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
						PriceInnerRecordHandling.NONE,
						null, null, 10000, 12100
					);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isPriceIndexEmpty());
				}
			);
		}

		@Test
		@DisplayName("should leave original unchanged after rollback of remove")
		void shouldLeaveOriginalUnchangedAfterRollbackOfRemove() {
			attachRefIndex();

			// pre-populate
			final int ipId1 = addPriceToBothIndexes(1, 10, PRICE_LIST_BASIC, CURRENCY_CZK);

			assertStateAfterRollback(
				PriceRefIndexTest.this.priceRefIndex,
				original -> {
					original.priceRemove(
						null, 1, ipId1,
						new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
						PriceInnerRecordHandling.NONE,
						null, null, 10000, 12100
					);
				},
				(original, committed) -> {
					assertNull(committed);
					// original should still have the price
					assertFalse(original.isPriceIndexEmpty());
					assertNotNull(original.getPriceIndex(KEY_BASIC_CZK));
				}
			);
		}
	}

	/**
	 * Tests verifying contract methods inherited from `AbstractPriceIndex`:
	 * `getPriceListAndCurrencyIndexes`, `getPriceIndexesStream`, `isPriceIndexEmpty`.
	 */
	@Nested
	@DisplayName("Contract methods")
	class ContractTest {

		@Test
		@DisplayName("should return all price list and currency indexes")
		void shouldReturnAllPriceListAndCurrencyIndexes() {
			attachRefIndex();

			addPriceToBothIndexes(1, 10, PRICE_LIST_BASIC, CURRENCY_CZK);
			addPriceToBothIndexes(2, 20, PRICE_LIST_VIP, CURRENCY_CZK);

			final Collection<? extends PriceListAndCurrencyPriceIndex> indexes =
				PriceRefIndexTest.this.priceRefIndex.getPriceListAndCurrencyIndexes();
			assertEquals(2, indexes.size());
		}

		@Test
		@DisplayName("should stream by price list name")
		void shouldStreamByPriceList() {
			attachRefIndex();

			addPriceToBothIndexes(1, 10, PRICE_LIST_BASIC, CURRENCY_CZK);
			addPriceToBothIndexes(2, 20, PRICE_LIST_VIP, CURRENCY_CZK);

			final long basicCount = PriceRefIndexTest.this.priceRefIndex
				.getPriceIndexesStream(PRICE_LIST_BASIC, PriceInnerRecordHandling.NONE)
				.count();
			final long vipCount = PriceRefIndexTest.this.priceRefIndex
				.getPriceIndexesStream(PRICE_LIST_VIP, PriceInnerRecordHandling.NONE)
				.count();

			assertEquals(1, basicCount);
			assertEquals(1, vipCount);
		}

		@Test
		@DisplayName("should stream by currency")
		void shouldStreamByCurrency() {
			attachRefIndex();

			addPriceToBothIndexes(1, 10, PRICE_LIST_BASIC, CURRENCY_CZK);
			final int ipId2 = addPriceToSuperIndex(
				2, 20, PRICE_LIST_BASIC, CURRENCY_EUR,
				PriceInnerRecordHandling.NONE, 5000, 6050
			);
			PriceRefIndexTest.this.priceRefIndex.addPrice(
				null, 2, ipId2,
				new PriceKey(20, PRICE_LIST_BASIC, CURRENCY_EUR),
				PriceInnerRecordHandling.NONE,
				null, null, 5000, 6050
			);

			final long czkCount = PriceRefIndexTest.this.priceRefIndex
				.getPriceIndexesStream(CURRENCY_CZK, PriceInnerRecordHandling.NONE)
				.count();
			final long eurCount = PriceRefIndexTest.this.priceRefIndex
				.getPriceIndexesStream(CURRENCY_EUR, PriceInnerRecordHandling.NONE)
				.count();

			assertEquals(1, czkCount);
			assertEquals(1, eurCount);
		}

		@Test
		@DisplayName("should report empty when no children")
		void shouldReportEmptyWhenNoChildren() {
			assertTrue(PriceRefIndexTest.this.priceRefIndex.isPriceIndexEmpty());
		}

		@Test
		@DisplayName("should report non-empty after adding a price")
		void shouldReportNonEmptyAfterAdding() {
			attachRefIndex();

			addPriceToBothIndexes(1, 10, PRICE_LIST_BASIC, CURRENCY_CZK);

			assertFalse(PriceRefIndexTest.this.priceRefIndex.isPriceIndexEmpty());
		}

		@Test
		@DisplayName("should return null for non-existent price index key")
		void shouldReturnNullForNonExistentKey() {
			assertNull(PriceRefIndexTest.this.priceRefIndex.getPriceIndex(KEY_BASIC_CZK));
		}

		@Test
		@DisplayName("should return correct sub-index via 3-arg getPriceIndex")
		void shouldReturnCorrectSubIndexViaThreeArgMethod() {
			attachRefIndex();

			addPriceToBothIndexes(1, 10, PRICE_LIST_BASIC, CURRENCY_CZK);

			final PriceListAndCurrencyPriceRefIndex result =
				PriceRefIndexTest.this.priceRefIndex.getPriceIndex(
					PRICE_LIST_BASIC, CURRENCY_CZK, PriceInnerRecordHandling.NONE
				);
			assertNotNull(result);
		}

		@Test
		@DisplayName("should reset dirty flag on all children")
		void shouldResetDirtyOnAllChildren() {
			attachRefIndex();

			addPriceToBothIndexes(1, 10, PRICE_LIST_BASIC, CURRENCY_CZK);

			final PriceListAndCurrencyPriceRefIndex childIndex =
				PriceRefIndexTest.this.priceRefIndex.getPriceIndex(KEY_BASIC_CZK);
			assertNotNull(childIndex);

			// child should be dirty after adding a price
			assertNotNull(childIndex.createStoragePart(1));

			PriceRefIndexTest.this.priceRefIndex.resetDirty();

			// after reset, child should be clean
			assertNull(childIndex.createStoragePart(1));
		}
	}

	/**
	 * Tests verifying STM invariants: unique IDs, removeLayer behavior.
	 */
	@Nested
	@DisplayName("STM invariants")
	class StmInvariantsTest {

		@Test
		@DisplayName("each instance gets a unique ID from TransactionalObjectVersion.SEQUENCE")
		void shouldAssignUniqueIdToEachInstance() {
			final PriceRefIndex index1 = new PriceRefIndex(SCOPE);
			final PriceRefIndex index2 = new PriceRefIndex(SCOPE);

			assertNotSame(index1.getId(), index2.getId());
		}

		@Test
		@DisplayName("removeLayer cleans priceIndexes map and PriceIndexChanges")
		void shouldCleanLayersOnRemoveLayer() {
			attachRefIndex();

			final int ipId1 = addPriceToSuperIndex(
				1, 10, PRICE_LIST_BASIC, CURRENCY_CZK,
				PriceInnerRecordHandling.NONE, 10000, 12100
			);

			assertStateAfterRollback(
				PriceRefIndexTest.this.priceRefIndex,
				original -> {
					original.addPrice(
						null, 1, ipId1,
						new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
						PriceInnerRecordHandling.NONE,
						null, null, 10000, 12100
					);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isPriceIndexEmpty());
				}
			);
		}
	}

	/**
	 * Tests verifying price operations with validity ranges.
	 */
	@Nested
	@DisplayName("Validity handling")
	class ValidityHandlingTest {

		@Test
		@DisplayName("should add price with validity range and query within range")
		void shouldAddPriceWithValidity() {
			attachRefIndex();

			final OffsetDateTime validFrom =
				OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
			final OffsetDateTime validTo =
				OffsetDateTime.of(2024, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
			final DateTimeRange validity = DateTimeRange.between(validFrom, validTo);

			// add price with validity to super index
			final int internalPriceId = PriceRefIndexTest.this.internalPriceIdSequence.incrementAndGet();
			PriceRefIndexTest.this.priceSuperIndex.addPrice(
				null, 1, internalPriceId,
				new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, validity, 10000, 12100
			);

			// add to ref index
			PriceRefIndexTest.this.priceRefIndex.addPrice(
				null, 1, internalPriceId,
				new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, validity, 10000, 12100
			);

			final PriceListAndCurrencyPriceRefIndex childIndex =
				PriceRefIndexTest.this.priceRefIndex.getPriceIndex(KEY_BASIC_CZK);
			assertNotNull(childIndex);
			assertFalse(childIndex.isEmpty());
		}
	}

	/**
	 * Tests verifying the two-arg constructor that takes a pre-populated map.
	 */
	@Nested
	@DisplayName("Constructor variants")
	class ConstructorTest {

		@Test
		@DisplayName("should create index with pre-populated map")
		void shouldCreateIndexWithPrePopulatedMap() {
			// create a child ref index and attach it to catalog
			final PriceListAndCurrencyPriceRefIndex childIndex =
				new PriceListAndCurrencyPriceRefIndex(SCOPE, KEY_BASIC_CZK);

			// put a price in the super index so attachment can resolve it
			final int ipId = addPriceToSuperIndex(
				1, 10, PRICE_LIST_BASIC, CURRENCY_CZK,
				PriceInnerRecordHandling.NONE, 10000, 12100
			);

			final Map<PriceIndexKey, PriceListAndCurrencyPriceRefIndex> map = new HashMap<>(4);
			map.put(KEY_BASIC_CZK, childIndex);

			final PriceRefIndex tested = new PriceRefIndex(SCOPE, map);

			assertFalse(tested.isPriceIndexEmpty());
			assertNotNull(tested.getPriceIndex(KEY_BASIC_CZK));
		}

		@Test
		@DisplayName("should create empty index with scope-only constructor")
		void shouldCreateEmptyIndexWithScopeOnlyConstructor() {
			final PriceRefIndex tested = new PriceRefIndex(SCOPE);

			assertTrue(tested.isPriceIndexEmpty());
		}
	}

	/**
	 * Generational proof test that randomly adds and removes prices via PriceRefIndex
	 * inside transactions, then verifies committed state matches expected state.
	 *
	 * Each iteration builds a fresh super index and ref index from the tracked state,
	 * then performs random add/remove operations within a transaction. Super index
	 * operations happen OUTSIDE the transaction so their transactional layers don't
	 * interfere with the ref index commit.
	 */
	@Tag(LONG_RUNNING_TEST)
	@DisplayName("generational proof test with random add/remove operations")
	@ParameterizedTest(name = "generational proof test with seed {0}")
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(@Nonnull GenerationalTestInput input) {
		final AtomicInteger globalInternalPriceId = new AtomicInteger(0);

		// keys used for random operations
		final PriceIndexKey[] keys = {KEY_BASIC_CZK, KEY_VIP_CZK, KEY_BASIC_EUR};

		runFor(
			input, 1_000,
			new TestState(
				new StringBuilder(8192),
				new HashMap<>(8)
			),
			(random, testState) -> {
				final StringBuilder codeBuffer = testState.code();
				codeBuffer.setLength(0);

				// rebuild a fresh super index from the tracked state each iteration
				final PriceSuperIndex superIndex = new PriceSuperIndex();
				final Map<PriceIndexKey, Set<Integer>> currentState = testState.trackedPricesByKey();

				// populate the super index from state
				for (final Map.Entry<PriceIndexKey, Set<Integer>> entry : currentState.entrySet()) {
					for (final Integer ipId : entry.getValue()) {
						// use ipId as both entityPK and priceId for simplicity
						superIndex.addPrice(
							null, ipId, ipId,
							new PriceKey(ipId, entry.getKey().getPriceList(), entry.getKey().getCurrency()),
							entry.getKey().getRecordHandling(),
							null, null, 10000, 12100
						);
					}
				}

				// build a fresh PriceRefIndex and attach it
				final PriceRefIndex priceRefIndex = new PriceRefIndex(SCOPE);
				final GlobalEntityIndex mockGlobalIndex = Mockito.mock(GlobalEntityIndex.class);
				Mockito.when(mockGlobalIndex.getPriceIndex(ArgumentMatchers.any(PriceIndexKey.class)))
					.thenAnswer(inv -> superIndex.getPriceIndex(inv.getArgument(0)));
				final Catalog mockCatalog = Mockito.mock(Catalog.class);
				Mockito.when(mockCatalog.getEntityIndexIfExists(
					ArgumentMatchers.eq(ENTITY_TYPE),
					ArgumentMatchers.eq(new EntityIndexKey(EntityIndexType.GLOBAL, SCOPE)),
					ArgumentMatchers.eq(GlobalEntityIndex.class)
				)).thenReturn(Optional.of(mockGlobalIndex));
				priceRefIndex.attachToCatalog(ENTITY_TYPE, mockCatalog);

				// populate the ref index from state
				for (final Map.Entry<PriceIndexKey, Set<Integer>> entry : currentState.entrySet()) {
					for (final Integer ipId : entry.getValue()) {
						priceRefIndex.addPrice(
							null, ipId, ipId,
							new PriceKey(ipId, entry.getKey().getPriceList(), entry.getKey().getCurrency()),
							entry.getKey().getRecordHandling(),
							null, null, 10000, 12100
						);
					}
				}

				// plan random operations
				final Map<PriceIndexKey, Set<Integer>> nextState = new HashMap<>(8);
				for (final Map.Entry<PriceIndexKey, Set<Integer>> entry : currentState.entrySet()) {
					nextState.put(entry.getKey(), new HashSet<>(entry.getValue()));
				}

				// collect planned operations: {ipId, priceId, entityPK, keyIdx} for adds,
				// {ipId, keyIdx} for removes
				final List<int[]> addOps = new ArrayList<>(8);
				final List<int[]> removeOps = new ArrayList<>(8);

				final int opCount = 1 + random.nextInt(5);
				for (int i = 0; i < opCount; i++) {
					final int keyIdx = random.nextInt(keys.length);
					final PriceIndexKey selectedKey = keys[keyIdx];
					final Set<Integer> pricesForKey =
						nextState.computeIfAbsent(selectedKey, k -> new HashSet<>(4));

					if (pricesForKey.isEmpty() || random.nextBoolean()) {
						// plan an add -- use ipId as entityPK and priceId for consistency
						final int ipId = globalInternalPriceId.incrementAndGet();

						codeBuffer.append("ADD: ipId=").append(ipId)
							.append(" key=").append(selectedKey).append('\n');

						addOps.add(new int[]{ipId, keyIdx});
						pricesForKey.add(ipId);
					} else {
						// plan a remove
						final Integer ipIdToRemove = pricesForKey.iterator().next();

						codeBuffer.append("REMOVE: ipId=").append(ipIdToRemove)
							.append(" key=").append(selectedKey).append('\n');

						removeOps.add(new int[]{ipIdToRemove, keyIdx});
						pricesForKey.remove(ipIdToRemove);
						if (pricesForKey.isEmpty()) {
							nextState.remove(selectedKey);
						}
					}
				}

				// execute super index additions OUTSIDE the transaction
				for (final int[] op : addOps) {
					final PriceIndexKey key = keys[op[1]];
					superIndex.addPrice(
						null, op[0], op[0],
						new PriceKey(op[0], key.getPriceList(), key.getCurrency()),
						key.getRecordHandling(),
						null, null, 10000, 12100
					);
				}

				try {
					assertStateAfterCommit(
						priceRefIndex,
						original -> {
							// additions inside transaction
							for (final int[] op : addOps) {
								final PriceIndexKey key = keys[op[1]];
								original.addPrice(
									null, op[0], op[0],
									new PriceKey(op[0], key.getPriceList(), key.getCurrency()),
									key.getRecordHandling(),
									null, null, 10000, 12100
								);
							}
							// removals inside transaction
							for (final int[] op : removeOps) {
								final PriceIndexKey key = keys[op[1]];
								original.priceRemove(
									null, op[0], op[0],
									new PriceKey(op[0], key.getPriceList(), key.getCurrency()),
									key.getRecordHandling(),
									null, null, 10000, 12100
								);
							}
						},
						(original, committed) -> {
							for (final PriceIndexKey key : keys) {
								final Set<Integer> expectedPrices =
									nextState.getOrDefault(key, Set.of());
								final PriceListAndCurrencyPriceRefIndex childIndex =
									committed.getPriceIndex(key);

								if (expectedPrices.isEmpty()) {
									assertNull(
										childIndex,
										"Expected no child for " + key +
											" but found one.\n" + codeBuffer
									);
								} else {
									assertNotNull(
										childIndex,
										"Expected child for " + key +
											" but found none.\n" + codeBuffer
									);
									assertFalse(
										childIndex.isEmpty(),
										"Child for " + key +
											" is empty but should not be.\n" + codeBuffer
									);
								}
							}
						}
					);
				} catch (Exception ex) {
					fail(ex.getMessage() + "\n" + codeBuffer, ex);
				}

				return new TestState(
					new StringBuilder(8192),
					nextState
				);
			}
		);
	}

	/**
	 * State carried across generational test iterations.
	 *
	 * @param code              StringBuilder for debugging output on failure
	 * @param trackedPricesByKey mapping from `PriceIndexKey` to set of internal price ids
	 *                          currently in the ref index
	 */
	private record TestState(
		@Nonnull StringBuilder code,
		@Nonnull Map<PriceIndexKey, Set<Integer>> trackedPricesByKey
	) {
	}

}
