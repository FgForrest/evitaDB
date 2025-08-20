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

package io.evitadb.spike.mock;

import com.carrotsearch.hppc.IntObjectHashMap;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.priceIndex.PriceIdContainerFormula;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.price.PriceListAndCurrencyPriceIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecord;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.store.model.StoragePart;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.time.OffsetDateTime;

/**
 * Mock PriceListAndCurrencyPriceIndex implementation to be used in perf. tests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class MockPriceListAndCurrencyPriceIndex implements PriceListAndCurrencyPriceIndex<Void, MockPriceListAndCurrencyPriceIndex> {
	@Serial private static final long serialVersionUID = -1343396298549809991L;
	private final transient IntObjectHashMap<int[]> priceIdsIndex;
	private int[] priceRecordIds;
	private PriceRecordContract[] priceRecords;

	public MockPriceListAndCurrencyPriceIndex(int entityCount) {
		this.priceIdsIndex = new IntObjectHashMap<>(entityCount);
		this.priceRecordIds = new int[0];
	}

	public void recordPrice(PriceRecordContract price) {
		this.priceRecords = this.priceRecords == null ?
			new PriceRecordContract[]{price} : ArrayUtils.insertRecordIntoOrderedArray(price, this.priceRecords,
			                                                                           PriceRecordContract.PRICE_RECORD_COMPARATOR
		);
		this.priceRecordIds = ArrayUtils.insertIntIntoOrderedArray(price.innerRecordId(), this.priceRecordIds);

		final int entityId = price.entityPrimaryKey();
		final int[] existingPriceIds = this.priceIdsIndex.get(entityId);
		this.priceIdsIndex.put(
			entityId,
			existingPriceIds == null ?
				new int[]{price.internalPriceId()} :
				ArrayUtils.insertIntIntoOrderedArray(price.internalPriceId(), existingPriceIds)
		);
	}

	@Override
	public void resetDirty() {
		throw new UnsupportedOperationException();
	}

	@Override
	public long getId() {
		return 0;
	}

	@Override
	public Void createLayer() {
		throw new UnsupportedOperationException();
	}

	@Nonnull
	@Override
	public MockPriceListAndCurrencyPriceIndex createCopyWithMergedTransactionalMemory(@Nullable Void layer, @Nonnull TransactionalLayerMaintainer transactionalLayer) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		throw new UnsupportedOperationException();
	}

	@Nonnull
	@Override
	public PriceIndexKey getPriceIndexKey() {
		throw new UnsupportedOperationException();
	}

	@Nonnull
	@Override
	public Bitmap getIndexedPriceEntityIds() {
		throw new UnsupportedOperationException();
	}

	@Nonnull
	@Override
	public Formula getIndexedPriceEntityIdsFormula() {
		throw new UnsupportedOperationException();
	}

	@Nonnull
	@Override
	public PriceIdContainerFormula getIndexedRecordIdsValidInFormula(@Nonnull OffsetDateTime theMoment) {
		throw new UnsupportedOperationException();
	}

	@Nullable
	@Override
	public int[] getInternalPriceIdsForEntity(int entityId) {
		return this.priceIdsIndex.get(entityId);
	}

	@Nullable
	@Override
	public PriceRecord[] getLowestPriceRecordsForEntity(int entityId) {
		throw new UnsupportedOperationException();
	}

	@Nonnull
	@Override
	public PriceRecordContract[] getPriceRecords() {
		return this.priceRecords;
	}

	@Nonnull
	@Override
	public int[] getIndexedPriceIds() {
		return this.priceRecordIds;
	}

	@Nonnull
	@Override
	public Formula createPriceIndexFormulaWithAllRecords() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isEmpty() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isTerminated() {
		return false;
	}

	@Override
	public void terminate() {

	}

	@Nullable
	@Override
	public StoragePart createStoragePart(int entityIndexPrimaryKey) {
		throw new UnsupportedOperationException();
	}

}
