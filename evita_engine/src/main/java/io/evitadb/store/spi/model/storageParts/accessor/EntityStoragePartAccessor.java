/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.store.spi.model.storageParts.accessor;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.store.entity.model.entity.AssociatedDataStoragePart;
import io.evitadb.store.entity.model.entity.AttributesStoragePart;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * This interface contains method that allows accessing storage container objects in current index version. All accesses
 * are cached so that multiple calls of the same method performs only single I/O access.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface EntityStoragePartAccessor {

	/**
	 * Fetches entity container.
	 */
	@Nonnull
	EntityBodyStoragePart getEntityStoragePart(@Nonnull String entityType, int entityPrimaryKey, @Nonnull EntityExistence expects);

	/**
	 * Fetches global attributes container.
	 */
	@Nonnull
	AttributesStoragePart getAttributeStoragePart(@Nonnull String entityType, int entityPrimaryKey);

	/**
	 * Fetches localized attributes container.
	 */
	@Nonnull
	AttributesStoragePart getAttributeStoragePart(@Nonnull String entityType, int entityPrimaryKey, @Nonnull Locale locale);

	/**
	 * Fetches associated data container.
	 */
	@Nonnull
	AssociatedDataStoragePart getAssociatedDataStoragePart(@Nonnull String entityType, int entityPrimaryKey, @Nonnull AssociatedDataKey key);

	/**
	 * Fetches reference container.
	 */
	@Nonnull
	ReferencesStoragePart getReferencesStoragePart(@Nonnull String entityType, int entityPrimaryKey);

	/**
	 * Fetches prices container.
	 */
	@Nonnull
	PricesStoragePart getPriceStoragePart(@Nonnull String entityType, int entityPrimaryKey);

}
