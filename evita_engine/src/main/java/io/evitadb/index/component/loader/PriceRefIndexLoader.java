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

import io.evitadb.dataType.Scope;
import io.evitadb.index.price.PriceListAndCurrencyPriceRefIndex;
import io.evitadb.index.price.model.PriceIndexKey;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencyRefIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.PriceListAndCurrencySuperIndexStoragePart;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;

import static io.evitadb.utils.Assert.isPremiseValid;

/**
 * Reloads the reference-price index map carried by `ReducedEntityIndex` and
 * `ReducedGroupEntityIndex`. The scope is read from `LoadContext.entityIndexKey()` because
 * reference-price indexes are scope-aware (LIVE / ARCHIVE) and propagate the scope down to every
 * individual `PriceListAndCurrencyPriceRefIndex`.
 *
 * The unique part-id is computed with
 * `PriceListAndCurrencySuperIndexStoragePart.computeUniquePartId` — both super and ref price
 * storage parts share the same key compressor entry so the on-disk layout remains stable when
 * an index toggles between flavours during schema migration.
 */
public final class PriceRefIndexLoader implements ComponentLoader {

	@Override
	@Nonnull
	public LoadedComponentBundle load(@Nonnull LoadContext context) {
		final EntityIndexStoragePart manifest = context.entityIndexStoragePart();
		final Set<PriceIndexKey> priceIndexes = manifest.getPriceIndexes();
		final StoragePartPersistenceService<?> service = context.storagePartService();
		final int entityIndexId = context.entityIndexId();
		final long catalogVersion = context.catalogVersion();
		final Scope scope = context.entityIndexKey().scope();

		final Map<PriceIndexKey, PriceListAndCurrencyPriceRefIndex> result =
			CollectionUtils.createHashMap(priceIndexes.size());
		for (final PriceIndexKey priceIndexKey : priceIndexes) {
			final long primaryKey = PriceListAndCurrencySuperIndexStoragePart.computeUniquePartId(
				entityIndexId, priceIndexKey, service.getReadOnlyKeyCompressor()
			);
			final PriceListAndCurrencyRefIndexStoragePart part = service.getStoragePart(
				catalogVersion, primaryKey, PriceListAndCurrencyRefIndexStoragePart.class
			);
			isPremiseValid(
				part != null,
				"Price index with id " + entityIndexId + " with key " + priceIndexKey +
					" was not found in persistent storage!"
			);
			result.put(
				priceIndexKey,
				new PriceListAndCurrencyPriceRefIndex(
					scope, priceIndexKey, part.getValidityIndex(), part.getPriceIds()
				)
			);
		}
		return new LoadedComponentBundle.PriceRef(result);
	}

}
