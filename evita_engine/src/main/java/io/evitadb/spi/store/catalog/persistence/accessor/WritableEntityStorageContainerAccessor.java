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

package io.evitadb.spi.store.catalog.persistence.accessor;

import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.spi.store.catalog.shared.model.PriceInternalIdContainer;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.Locale;
import java.util.OptionalInt;

/**
 * This interface is an extension to {@link EntityStoragePartAccessor} that allows accepting and maintaining
 * assigned internal price identifiers. For purpose of internal identifiers see {@link PriceInternalIdContainer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface WritableEntityStorageContainerAccessor extends EntityStoragePartAccessor {

	/**
	 * Returns true, if the entity container is completely removed, false otherwise.
	 *
	 * @return true if the entity is completely removed, false otherwise
	 */
	boolean isEntityRemovedEntirely();

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
	 * Returns an array of added locales that enriched the existing list of entity attribute locales.
	 * @return array of added locales or empty set if no locales were added
	 */
	@Nonnull
	LocaleWithScope[] getAddedLocales();

	/**
	 * Returns an array of removed locales that were removed from the existing list of entity attribute locales.
	 * @return array of removed locales or empty set if no locales were removed
	 */
	@Nonnull
	LocaleWithScope[] getRemovedLocales();

	/**
	 * Returns identity hash code representing current state of locales (added + removed), which takes also difference
	 * in attribute locales into account (this is not reflected in added/removed locales in methods {@link #getAddedLocales()}
	 * and {@link #getRemovedLocales()}).
	 *
	 * @return identity hash code of a current locales state, which changes when locales are added/removed
	 */
	int getLocalesIdentityHash();

	/**
	 * Simple record encapsulating locale with its scope.
	 * @param locale the locale
	 * @param scope the scope
	 */
	record LocaleWithScope (
		@Nonnull Locale locale,
		@Nonnull EnumSet<LocaleScope> scope
	) {

	}

	/**
	 * Defines the scope of locale changes - whether they affect entity attribute locale or entity locale.
	 * Attribute locales affect attribute recalculation in indexes, while entity locale just the existence of
	 * particular locale in the entity.
	 */
	enum LocaleScope {
		ATTRIBUTE,
		ENTITY
	}

}
