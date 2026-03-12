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
 * Mutable extension of {@link EntityStoragePartAccessor} used during entity mutation processing.
 *
 * In addition to the read-only storage-part access provided by {@link EntityStoragePartAccessor}, this interface
 * exposes write operations required while applying {@link io.evitadb.api.requestResponse.data.mutation.LocalMutation}s
 * to an entity:
 *
 * - **internal price ID management** — evitaDB uses compact `int` identifiers for prices in its bitmap indexes
 *   (rather than the external `priceId` + `innerRecordId` pair, which would require 64-bit keys). These internal IDs
 *   are assigned once and must be remembered within a mutation session so that subsequent mutations can look them
 *   up without re-reading the price storage part. See {@link PriceInternalIdContainer} for the full rationale.
 * - **locale change tracking** — index mutation machinery needs to know which locales were added or removed by the
 *   current mutation batch so it can trigger targeted attribute index recalculations. The identity hash
 *   ({@link #getLocalesIdentityHash()}) lets consumers cheaply detect whether their cached view of the locale
 *   set is still valid.
 * - **entity removal sentinel** — indicates whether the entity body is in a "removed entirely" state so that
 *   downstream index mutators can skip work that would be immediately undone.
 *
 * The primary implementation is
 * {@link io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor}, which is instantiated
 * per-entity during transaction processing and is **not thread-safe**.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @see EntityStoragePartAccessor
 * @see PriceInternalIdContainer
 * @see io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor
 */
public interface WritableEntityStorageContainerAccessor extends EntityStoragePartAccessor {

	/**
	 * Returns `true` if the entity has been fully removed during the current mutation session, `false` if it still
	 * exists (possibly in a modified form).
	 *
	 * Index mutators use this flag to skip expensive index rebuild work for entities that are about to disappear.
	 */
	boolean isEntityRemovedEntirely();

	/**
	 * Records the mapping from a price identified by `priceKey` to an internally assigned `int` identifier used
	 * in bitmap price indexes.
	 *
	 * evitaDB maps each externally identified price (by {@link PriceKey}) to a compact internal `int` ID so that
	 * price-related bitmaps can stay in `int` space, avoiding the slower `Roaring64Bitmap`. This method is called
	 * by index mutation code immediately after a new internal ID is allocated, ensuring the ID is visible to
	 * subsequent calls to {@link #findExistingInternalId} within the same mutation session without requiring
	 * another read from the {@link io.evitadb.spi.store.catalog.persistence.storageParts.entity.PricesStoragePart}.
	 *
	 * Attempting to re-register a different ID for the same `priceKey` within the same session is treated as a
	 * programming error and throws an exception in the production implementation.
	 *
	 * @param entityPrimaryKey the primary key of the entity that owns the price; must match the entity this accessor
	 *                         was created for
	 * @param priceKey         the external price key (price list + currency + price ID) identifying the price
	 * @param internalPriceId  the newly allocated internal `int` identifier to associate with `priceKey`
	 */
	void registerAssignedPriceId(
		int entityPrimaryKey,
		@Nonnull PriceKey priceKey,
		int internalPriceId
	);

	/**
	 * Looks up the internal `int` price identifier previously assigned to the price identified by `priceKey`.
	 *
	 * The lookup follows a two-level strategy:
	 *
	 * 1. Check whether an internal ID was registered **within the current mutation session** via
	 *    {@link #registerAssignedPriceId} (covers prices inserted in the same transaction).
	 * 2. If not found in-session, fall back to reading the ID from the persisted
	 *    {@link io.evitadb.spi.store.catalog.persistence.storageParts.entity.PricesStoragePart} (covers prices
	 *    that were assigned an ID in a prior transaction).
	 *
	 * Returns an empty `OptionalInt` if the price has never been stored and no in-session ID was registered.
	 *
	 * @param entityType       the entity collection name
	 * @param entityPrimaryKey the primary key of the entity that owns the price
	 * @param priceKey         the external price key to resolve
	 * @return the internal price ID if known, or an empty `OptionalInt` if the price is brand-new to storage
	 */
	@Nonnull
	OptionalInt findExistingInternalId(
		@Nonnull String entityType,
		int entityPrimaryKey,
		@Nonnull PriceKey priceKey
	);

	/**
	 * Returns all locales that have been added to the entity during the current mutation session, together with the
	 * scope that describes whether the addition is driven by an attribute value in that locale ({@link LocaleScope#ATTRIBUTE})
	 * or by a direct entity locale assignment ({@link LocaleScope#ENTITY}).
	 *
	 * Consumers such as {@link io.evitadb.index.mutation.index.dataAccess.MemoizedLocalesObsoleteChecker} use this
	 * information to reconstruct the pre-mutation locale set by reversing the changes — i.e., temporarily removing
	 * these added locales — so that existing attribute index entries can be correctly evaluated against the
	 * state that existed before the mutation.
	 *
	 * @return snapshot array of all locales added in this session, or an empty array if none were added
	 */
	@Nonnull
	LocaleWithScope[] getAddedLocales();

	/**
	 * Returns all locales that have been removed from the entity during the current mutation session, together with
	 * the scope that describes whether the removal is driven by dropping the last attribute value in that locale
	 * ({@link LocaleScope#ATTRIBUTE}) or by a direct entity locale removal ({@link LocaleScope#ENTITY}).
	 *
	 * Consumers such as {@link io.evitadb.index.mutation.index.dataAccess.MemoizedLocalesObsoleteChecker} use this
	 * information to reconstruct the pre-mutation locale set by reversing the changes — i.e., temporarily re-adding
	 * these removed locales — so that existing attribute index entries can be correctly evaluated against the
	 * state that existed before the mutation.
	 *
	 * @return snapshot array of all locales removed in this session, or an empty array if none were removed
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
	 * Pairs a locale with the set of scopes in which a change to that locale occurred within the current mutation
	 * session. A single locale may appear in multiple scopes simultaneously — for example when an attribute mutation
	 * both adds a localized attribute (implying {@link LocaleScope#ATTRIBUTE}) and is the first locale-carrying
	 * attribute on the entity (implying {@link LocaleScope#ENTITY} as well).
	 *
	 * @param locale the locale that was added or removed
	 * @param scope  one or more {@link LocaleScope} values describing which parts of the entity model were affected
	 */
	record LocaleWithScope (
		@Nonnull Locale locale,
		@Nonnull EnumSet<LocaleScope> scope
	) {

	}

	/**
	 * Defines the part of the entity model that a locale change affects.
	 *
	 * The distinction matters because attribute locales and entity locales drive different index recalculations:
	 * an {@link #ATTRIBUTE} change triggers attribute-index updates (sortable compound recalculation, etc.), while
	 * an {@link #ENTITY} change affects the entity-level locale membership tracked in global and reduced indexes.
	 */
	enum LocaleScope {
		/**
		 * The locale change is driven by an attribute value: a localized attribute was added or the last localized
		 * attribute in this locale was removed, causing the locale to appear in or disappear from the entity's
		 * attribute-locale set.
		 */
		ATTRIBUTE,
		/**
		 * The locale change is driven directly by the entity's locale membership: the entity was explicitly assigned
		 * to or removed from this locale, independently of any attribute values.
		 */
		ENTITY
	}

}
