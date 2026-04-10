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

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AssociatedDataStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.PricesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;

import javax.annotation.Nonnull;
import java.util.Locale;

/**
 * Read-only access layer for the storage containers (storage parts) that together represent a single entity
 * in evitaDB's persistence model.
 *
 * evitaDB decomposes entity data across several independently stored containers:
 *
 * - **entity body** ({@link EntityBodyStoragePart}) — primary key, version, scope, set of locales, and references
 *   to associated data keys
 * - **global attributes** ({@link AttributesStoragePart}, no locale) — locale-agnostic attribute values
 * - **localized attributes** ({@link AttributesStoragePart}, one per locale) — per-locale attribute values
 * - **associated data** ({@link AssociatedDataStoragePart}, one per {@link AssociatedDataKey}) — arbitrary
 *   binary/structured payload attached to the entity
 * - **references** ({@link ReferencesStoragePart}) — all reference entries for the entity in a single container
 * - **prices** ({@link PricesStoragePart}) — all price records for the entity in a single container
 *
 * Implementations are expected to cache every fetched container for the lifetime of the accessor instance so that
 * repeated calls for the same entity and partition result in exactly one I/O operation. This caching contract is
 * relied upon by callers such as {@link io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor}
 * and {@link io.evitadb.core.expression.proxy.ExpressionProxyInstantiator}, both of which may fetch the same
 * partition multiple times during a single operation.
 *
 * This interface exposes only read operations. For mutation scenarios that additionally need to track locale
 * changes and manage internal price identifiers, see the mutable extension
 * {@link WritableEntityStorageContainerAccessor}.
 *
 * Implementations are **not thread-safe** — a single instance must not be shared across concurrent threads.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @see WritableEntityStorageContainerAccessor
 * @see io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor
 */
public interface EntityStoragePartAccessor {

	/**
	 * Returns the entity body container for the given entity, verifying that the entity's existence matches
	 * the caller's expectation before returning.
	 *
	 * The `expects` parameter controls the validation behaviour:
	 *
	 * - `MUST_NOT_EXIST` — throws {@link io.evitadb.api.exception.InvalidMutationException} if a live (non-removed)
	 *   entity body is already present in storage; used when creating a brand-new entity
	 * - `MUST_EXIST` — throws {@link io.evitadb.api.exception.InvalidMutationException} if no entity body is found
	 *   or the stored body is already marked for removal; used when updating or deleting an existing entity
	 * - `MAY_EXIST` — no existence check is performed; used in contexts that are agnostic about whether the entity
	 *   already exists
	 *
	 * Subsequent calls with the same `entityPrimaryKey` return the cached instance without repeating I/O or
	 * existence validation.
	 *
	 * @param entityType       the entity collection name, used only for error messages
	 * @param entityPrimaryKey the primary key of the entity to fetch
	 * @param expects          the expected existence state; governs validation and exception throwing
	 * @return the (possibly newly created) entity body container, never `null`
	 * @throws io.evitadb.api.exception.InvalidMutationException if the stored state contradicts `expects`
	 */
	@Nonnull
	EntityBodyStoragePart getEntityStoragePart(@Nonnull String entityType, int entityPrimaryKey, @Nonnull EntityExistence expects);

	/**
	 * Returns the global (locale-agnostic) attribute container for the given entity.
	 *
	 * If no attributes container is found in storage, a fresh empty {@link AttributesStoragePart} is created,
	 * cached, and returned so that callers can add attribute values without null-checking.
	 *
	 * @param entityType       the entity collection name
	 * @param entityPrimaryKey the primary key of the entity whose global attributes are requested
	 * @return the global attributes container, never `null`
	 */
	@Nonnull
	AttributesStoragePart getAttributeStoragePart(@Nonnull String entityType, int entityPrimaryKey);

	/**
	 * Returns the locale-specific attribute container for the given entity and locale.
	 *
	 * Each locale is stored in a separate container. If no container for `locale` is found in storage, a fresh
	 * empty {@link AttributesStoragePart} is created, cached under that locale, and returned.
	 *
	 * @param entityType       the entity collection name
	 * @param entityPrimaryKey the primary key of the entity whose localized attributes are requested
	 * @param locale           the locale identifying which localized attribute partition to fetch
	 * @return the localized attributes container for `locale`, never `null`
	 */
	@Nonnull
	AttributesStoragePart getAttributeStoragePart(@Nonnull String entityType, int entityPrimaryKey, @Nonnull Locale locale);

	/**
	 * Returns the associated data container identified by the given key.
	 *
	 * Associated data items are each stored in an independent container keyed by
	 * {@link AssociatedDataKey} (name + optional locale). If the container for `key` is not found in
	 * storage, a fresh empty {@link AssociatedDataStoragePart} is created, cached, and returned.
	 *
	 * @param entityType       the entity collection name
	 * @param entityPrimaryKey the primary key of the owning entity
	 * @param key              the key identifying the specific associated data item (name and optional locale)
	 * @return the associated data container for `key`, never `null`
	 */
	@Nonnull
	AssociatedDataStoragePart getAssociatedDataStoragePart(@Nonnull String entityType, int entityPrimaryKey, @Nonnull AssociatedDataKey key);

	/**
	 * Returns the references container for the given entity.
	 *
	 * All references of an entity — regardless of their target entity type — are stored together in a single
	 * {@link ReferencesStoragePart}. If no container is found in storage, a fresh empty one is created, cached,
	 * and returned.
	 *
	 * @param entityType       the entity collection name
	 * @param entityPrimaryKey the primary key of the entity whose references are requested
	 * @return the references container, never `null`
	 */
	@Nonnull
	ReferencesStoragePart getReferencesStoragePart(@Nonnull String entityType, int entityPrimaryKey);

	/**
	 * Returns the prices container for the given entity.
	 *
	 * All prices of an entity are stored together in a single {@link PricesStoragePart}, even though query
	 * execution may filter them by price list or currency. If no container is found in storage, a fresh empty
	 * one is created, cached, and returned.
	 *
	 * @param entityType       the entity collection name
	 * @param entityPrimaryKey the primary key of the entity whose prices are requested
	 * @return the prices container, never `null`
	 */
	@Nonnull
	PricesStoragePart getPriceStoragePart(@Nonnull String entityType, int entityPrimaryKey);

}
