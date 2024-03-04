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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.index.mutation;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.store.entity.model.entity.AssociatedDataStoragePart;
import io.evitadb.store.entity.model.entity.AttributesStoragePart;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;
import io.evitadb.store.entity.model.entity.price.MinimalPriceInternalIdContainer;
import io.evitadb.store.entity.model.entity.price.PriceInternalIdContainer;
import io.evitadb.store.spi.model.storageParts.accessor.WritableEntityStorageContainerAccessor;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * This mock object is used in tests to provide container object without necessity to load them from persistent data
 * storage.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class MockStorageContainerAccessor implements WritableEntityStorageContainerAccessor {
	private final Map<Locale, AttributesStoragePart> localizedAttributesStorageContainer = new HashMap<>();
	private final Map<AssociatedDataKey, AssociatedDataStoragePart> associatedDataStorageContainer = new HashMap<>();
	private EntityBodyStoragePart entityStorageContainer;
	private AttributesStoragePart attributesStorageContainer;
	private ReferencesStoragePart referencesStorageContainer;
	private PricesStoragePart pricesStorageContainer;
	private Map<PriceKey, Integer> assignedInternalPriceIdIndex;
	@Getter private Set<Locale> addedLocales = new HashSet<>();
	@Getter private Set<Locale> removedLocales = new HashSet<>();

	@Override
	public void registerAssignedPriceId(@Nonnull String entityType, int entityPrimaryKey, @Nonnull PriceKey priceKey, @Nullable Integer innerRecordId, @Nonnull PriceInternalIdContainer priceId) {
		if (assignedInternalPriceIdIndex == null) {
			assignedInternalPriceIdIndex = new HashMap<>();
		}
		assignedInternalPriceIdIndex.put(priceKey, Objects.requireNonNull(priceId.getInternalPriceId()));
	}

	@Nonnull
	@Override
	public PriceInternalIdContainer findExistingInternalIds(@Nonnull String entityType, int entityPrimaryKey, @Nonnull PriceKey priceKey, @Nullable Integer innerRecordId) {
		Integer internalPriceId = assignedInternalPriceIdIndex == null ? null : assignedInternalPriceIdIndex.get(priceKey);
		if (internalPriceId == null) {
			final PricesStoragePart priceStorageContainer = getPriceStoragePart(entityType, entityPrimaryKey);
			final PriceInternalIdContainer existingInternalIds = priceStorageContainer.findExistingInternalIds(priceKey);
			return new MinimalPriceInternalIdContainer(existingInternalIds.getInternalPriceId());
		} else {
			return new MinimalPriceInternalIdContainer(internalPriceId);
		}
	}

	@Nonnull
	@Override
	public EntityBodyStoragePart getEntityStoragePart(@Nonnull String entityType, int entityPrimaryKey, @Nonnull EntityExistence expects) {
		if (entityStorageContainer == null) {
			entityStorageContainer = new EntityBodyStoragePart(entityPrimaryKey);
		}
		return entityStorageContainer;
	}

	@Nonnull
	@Override
	public AttributesStoragePart getAttributeStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
		if (attributesStorageContainer == null) {
			attributesStorageContainer = new AttributesStoragePart(entityPrimaryKey);
		}
		return attributesStorageContainer;
	}

	@Nonnull
	@Override
	public AttributesStoragePart getAttributeStoragePart(@Nonnull String entityType, int entityPrimaryKey, @Nullable Locale locale) {
		return localizedAttributesStorageContainer.computeIfAbsent(locale, loc -> new AttributesStoragePart(entityPrimaryKey, loc));
	}

	@Nonnull
	@Override
	public AssociatedDataStoragePart getAssociatedDataStoragePart(@Nonnull String entityType, int entityPrimaryKey, @Nonnull AssociatedDataKey key) {
		return associatedDataStorageContainer.computeIfAbsent(key, keyRef -> new AssociatedDataStoragePart(entityPrimaryKey, keyRef));
	}

	@Nonnull
	@Override
	public ReferencesStoragePart getReferencesStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
		if (referencesStorageContainer == null) {
			referencesStorageContainer = new ReferencesStoragePart(entityPrimaryKey);
		}
		return referencesStorageContainer;
	}

	@Nonnull
	@Override
	public PricesStoragePart getPriceStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
		if (pricesStorageContainer == null) {
			pricesStorageContainer = new PricesStoragePart(entityPrimaryKey);
		}
		return pricesStorageContainer;
	}

	public void reset() {
		this.entityStorageContainer = null;
		this.attributesStorageContainer = null;
		this.localizedAttributesStorageContainer.clear();
		this.associatedDataStorageContainer.clear();
		this.referencesStorageContainer = null;
		this.pricesStorageContainer = null;
	}
}
