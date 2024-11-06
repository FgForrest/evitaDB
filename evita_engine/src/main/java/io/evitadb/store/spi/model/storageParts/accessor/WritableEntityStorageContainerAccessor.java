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

import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.store.entity.model.entity.price.PriceInternalIdContainer;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;

/**
 * This interface is an extension to {@link EntityStoragePartAccessor} that allows accepting and maintaining
 * assigned internal price identifiers. For purpose of internal identifiers see {@link PriceInternalIdContainer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface WritableEntityStorageContainerAccessor extends EntityStoragePartAccessor {

	/**
	 * Registers internal identifiers in `priceId` argument to `priceKey` and `innerRecordId` combination inside
	 * the entity with `entityPrimaryKey`.
	 */
	void registerAssignedPriceId(
		int entityPrimaryKey,
		@Nonnull PriceKey priceKey,
		int internalPriceId
	);

	/**
	 * Returns assigned identifiers for combination of `priceKey` and `innerRecordId` inside the entity with
	 * `entityPrimaryKey`.
	 */
	@Nonnull
	OptionalInt findExistingInternalId(
		@Nonnull String entityType,
		int entityPrimaryKey,
		@Nonnull PriceKey priceKey
	);

	/**
	 * Returns set of added locales that enriched the existing list of entity locales.
	 * @return set of added locales or empty set if no locales were added
	 */
	@Nonnull
	Set<Locale> getAddedLocales();

	/**
	 * Returns set of removed locales that were removed from the existing list of entity locales.
	 * @return set of removed locales or empty set if no locales were removed
	 */
	@Nonnull
	Set<Locale> getRemovedLocales();

}
