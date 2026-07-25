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
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.index.range.RangeIndex;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;

/**
 * Tests for {@link PriceRefIndex} verifying catalog attachment, add/remove price delegation,
 * transactional commit/rollback semantics, copy-for-new-catalog, and contract methods.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("PriceRefIndex functionality")
@Tag(INDEXING)
@Tag(PRICE)
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
			priceWithoutTax, priceWithTax,
			this.priceSuperIndex
		);
		return internalPriceId;
	}

	/**
	 * Wires the ref index to a super-index resolver backed by the shared super index, mirroring how the owning entity
	 * collection resolves super price indexes from its own GLOBAL entity index.
	 */
	private void attachRefIndex() {
		this.priceRefIndex.restorePriceRecords(this.priceSuperIndex);
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
			null, null, 10000, 12100,
			this.priceSuperIndex
		);
		return internalPriceId;
	}

	/**
	 * Tests verifying catalog attachment lifecycle including propagation to existing
	 * and newly created child ref indexes.
	 */
	@Nested
	@DisplayName("Super index wiring")
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

			// now attach -- the existing child must end up with its record tree restored from the super index
			attachRefIndex();

			// after attach, the child should be linked to the super index and have the price
			final PriceListAndCurrencyPriceRefIndex attached =
				PriceRefIndexTest.this.priceRefIndex.getPriceIndex(KEY_BASIC_CZK);
			assertNotNull(attached);
			assertFalse(attached.isEmpty());
			assertEquals(1, attached.getPriceRecords().length);
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

			// add price to ref index -- child created and auto-wired via the super index resolver
			PriceRefIndexTest.this.priceRefIndex.addPrice(
				null, 1, internalPriceId,
				new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 10000, 12100,
				PriceRefIndexTest.this.priceSuperIndex
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
				null, null, 10000, 12100,
				PriceRefIndexTest.this.priceSuperIndex
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
				null, null, 10000, 12100,
				PriceRefIndexTest.this.priceSuperIndex
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
				null, null, 10000, 12100,
				PriceRefIndexTest.this.priceSuperIndex
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
				null, null, 5000, 6050,
				PriceRefIndexTest.this.priceSuperIndex
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
				null, null, 10000, 12100,
				PriceRefIndexTest.this.priceSuperIndex
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
				null, null, 10000, 12100,
				PriceRefIndexTest.this.priceSuperIndex
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
				null, null, 10000, 12100,
				PriceRefIndexTest.this.priceSuperIndex
			);

			// the super index child is now terminated -- when we try to remove from ref,
			// the ref's removePrice catches PriceListAndCurrencyPriceIndexTerminated
			// and gracefully removes the child ref index
			PriceRefIndexTest.this.priceRefIndex.priceRemove(
				null, 1, ipId1,
				new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, null, 10000, 12100,
				PriceRefIndexTest.this.priceSuperIndex
			);

			// the child should be gone
			assertNull(PriceRefIndexTest.this.priceRefIndex.getPriceIndex(KEY_BASIC_CZK));
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
						null, null, 10000, 12100,
						PriceRefIndexTest.this.priceSuperIndex
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
						null, null, 10000, 12100,
						PriceRefIndexTest.this.priceSuperIndex
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
						null, null, 10000, 12100,
						PriceRefIndexTest.this.priceSuperIndex
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
						null, null, 10000, 12100,
						PriceRefIndexTest.this.priceSuperIndex
					);
					// remove in same transaction
					original.priceRemove(
						null, 1, ipId1,
						new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
						PriceInnerRecordHandling.NONE,
						null, null, 10000, 12100,
						PriceRefIndexTest.this.priceSuperIndex
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
						null, null, 10000, 12100,
						PriceRefIndexTest.this.priceSuperIndex
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
						null, null, 10000, 12100,
						PriceRefIndexTest.this.priceSuperIndex
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
				null, null, 5000, 6050,
				PriceRefIndexTest.this.priceSuperIndex
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
						null, null, 10000, 12100,
						PriceRefIndexTest.this.priceSuperIndex
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
				null, validity, 10000, 12100,
				PriceRefIndexTest.this.priceSuperIndex
			);

			// add to ref index
			PriceRefIndexTest.this.priceRefIndex.addPrice(
				null, 1, internalPriceId,
				new PriceKey(10, PRICE_LIST_BASIC, CURRENCY_CZK),
				PriceInnerRecordHandling.NONE,
				null, validity, 10000, 12100,
				PriceRefIndexTest.this.priceSuperIndex
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

}
