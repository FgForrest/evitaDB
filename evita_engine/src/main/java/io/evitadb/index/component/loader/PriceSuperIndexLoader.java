/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index.component.loader;

import io.evitadb.index.price.PriceListAndCurrencyPriceSuperIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.index.price.model.priceRecord.PriceRecordContract;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceLeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexStoragePart;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;

import static io.evitadb.utils.Assert.isPremiseValid;

/**
 * Reloads the super-price index map carried by `GlobalEntityIndex`. The result map is passed to
 * the `GlobalEntityIndex` constructor wrapped in a `PriceSuperIndex`.
 */
public final class PriceSuperIndexLoader implements ComponentLoader {

	@Override
	@Nonnull
	public LoadedComponentBundle load(@Nonnull LoadContext context) {
		final EntityIndexStoragePart manifest = context.entityIndexStoragePart();
		final Set<PriceIndexKey> priceIndexes = manifest.getPriceIndexes();
		final StoragePartPersistenceService<?> service = context.storagePartService();
		final int entityIndexId = context.entityIndexId();
		final long catalogVersion = context.catalogVersion();

		final Map<PriceIndexKey, PriceListAndCurrencyPriceSuperIndex> result =
			CollectionUtils.createHashMap(priceIndexes.size());
		for (final PriceIndexKey priceIndexKey : priceIndexes) {
			final long primaryKey = PriceListAndCurrencySuperIndexStoragePart.computeUniquePartId(
				entityIndexId, priceIndexKey, service.getReadOnlyKeyCompressor()
			);
			final PriceListAndCurrencySuperIndexStoragePart part = service.getStoragePart(
				catalogVersion, primaryKey, PriceListAndCurrencySuperIndexStoragePart.class
			);
			isPremiseValid(
				part != null,
				"Price index with id " + entityIndexId + " with key " + priceIndexKey +
					" was not found in persistent storage!"
			);
			result.put(
				priceIndexKey,
				part.isPaged()
					? loadPagedSuperIndex(service, catalogVersion, entityIndexId, priceIndexKey, part)
					: new PriceListAndCurrencyPriceSuperIndex(
						priceIndexKey, part.getValidityIndex(), part.getPriceRecords()
					)
			);
		}
		return new LoadedComponentBundle.PriceSuper(result);
	}

	/**
	 * Reassembles a `PAGED` super price index from its persisted leaf pages. Resolves the page-stream id from the
	 * sub-index identity (the dictionary id assigned when the index first went PAGED), reads each leaf page in ascending
	 * key order, and hands the per-page record arrays to
	 * {@link PriceListAndCurrencyPriceSuperIndex#fromPersistedPages} for boundary-stable reconstruction.
	 *
	 * @param service        the storage part persistence service
	 * @param catalogVersion the catalog version being loaded
	 * @param entityIndexId  the owning entity index pk
	 * @param priceIndexKey  the price list and currency identity
	 * @param part           the paged root storage part carrying the ordered leaf-page list and the high-water
	 * @return the reassembled, boundary-stable super price index
	 */
	@Nonnull
	private static PriceListAndCurrencyPriceSuperIndex loadPagedSuperIndex(
		@Nonnull StoragePartPersistenceService<?> service,
		long catalogVersion,
		int entityIndexId,
		@Nonnull PriceIndexKey priceIndexKey,
		@Nonnull PriceListAndCurrencySuperIndexStoragePart part
	) {
		final int streamId = service.getReadOnlyKeyCompressor().getId(
			new PriceLeafStreamKey(entityIndexId, priceIndexKey)
		);
		final int[] orderedPageSequences = part.getLeafPageSequences();
		final PriceRecordContract[][] perPagePriceRecords = new PriceRecordContract[orderedPageSequences.length][];
		for (int i = 0; i < orderedPageSequences.length; i++) {
			final PriceListAndCurrencySuperIndexLeafPagePart leafPage = service.getStoragePart(
				catalogVersion,
				PriceListAndCurrencySuperIndexLeafPagePart.computeUniquePartId(streamId, orderedPageSequences[i]),
				PriceListAndCurrencySuperIndexLeafPagePart.class
			);
			isPremiseValid(
				leafPage != null,
				"Price leaf page " + orderedPageSequences[i] + " for index " + entityIndexId + " with key " +
					priceIndexKey + " was not found in persistent storage!"
			);
			perPagePriceRecords[i] = leafPage.getPriceRecords();
		}
		return PriceListAndCurrencyPriceSuperIndex.fromPersistedPages(
			priceIndexKey, part.getValidityIndex(), orderedPageSequences, perPagePriceRecords,
			part.getHighWaterPageSequence()
		);
	}

}
