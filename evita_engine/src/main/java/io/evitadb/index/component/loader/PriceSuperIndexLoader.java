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
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexStoragePart;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;

import static io.evitadb.utils.Assert.isPremiseValid;

/**
 * Reloads the super-price index map carried by `GlobalEntityIndex`. Ports
 * `fetchPriceSuperIndexes` from `DefaultEntityCollectionPersistenceService`. The result map is
 * passed to the `GlobalEntityIndex` constructor wrapped in a `PriceSuperIndex`.
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
				new PriceListAndCurrencyPriceSuperIndex(
					priceIndexKey, part.getValidityIndex(), part.getPriceRecords()
				)
			);
		}
		return new LoadedComponentBundle.PriceSuper(result);
	}

}
