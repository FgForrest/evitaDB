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

package io.evitadb.index.mutation;

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.store.entity.model.entity.AssociatedDataStoragePart;
import io.evitadb.store.entity.model.entity.AttributesStoragePart;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.entity.model.entity.PricesStoragePart;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;
import io.evitadb.store.spi.model.storageParts.accessor.WritableEntityStorageContainerAccessor;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
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
	@Getter private final Set<Locale> addedLocales = new HashSet<>();
	@Getter private final Set<Locale> removedLocales = new HashSet<>();

	@Override
	public boolean isEntityRemovedEntirely() {
		return this.entityStorageContainer.isMarkedForRemoval();
	}

	@Override
	public void registerAssignedPriceId(int entityPrimaryKey, @Nonnull PriceKey priceKey, int internalPriceId) {
		if (this.assignedInternalPriceIdIndex == null) {
			this.assignedInternalPriceIdIndex = new HashMap<>();
		}
		this.assignedInternalPriceIdIndex.put(priceKey, internalPriceId);
	}

	@Nonnull
	@Override
	public OptionalInt findExistingInternalId(@Nonnull String entityType, int entityPrimaryKey, @Nonnull PriceKey priceKey) {
		Integer internalPriceId = this.assignedInternalPriceIdIndex == null ? null : this.assignedInternalPriceIdIndex.get(priceKey);
		if (internalPriceId == null) {
			final PricesStoragePart priceStorageContainer = getPriceStoragePart(entityType, entityPrimaryKey);
			return priceStorageContainer.findExistingInternalIds(priceKey);
		} else {
			return OptionalInt.of(internalPriceId);
		}
	}

	@Nonnull
	@Override
	public EntityBodyStoragePart getEntityStoragePart(@Nonnull String entityType, int entityPrimaryKey, @Nonnull EntityExistence expects) {
		if (this.entityStorageContainer == null) {
			this.entityStorageContainer = new EntityBodyStoragePart(entityPrimaryKey);
		}
		return this.entityStorageContainer;
	}

	@Nonnull
	@Override
	public AttributesStoragePart getAttributeStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
		if (this.attributesStorageContainer == null) {
			this.attributesStorageContainer = new AttributesStoragePart(entityPrimaryKey);
		}
		return this.attributesStorageContainer;
	}

	@Nonnull
	@Override
	public AttributesStoragePart getAttributeStoragePart(@Nonnull String entityType, int entityPrimaryKey, @Nullable Locale locale) {
		return this.localizedAttributesStorageContainer.computeIfAbsent(locale, loc -> new AttributesStoragePart(entityPrimaryKey, loc));
	}

	@Nonnull
	@Override
	public AssociatedDataStoragePart getAssociatedDataStoragePart(@Nonnull String entityType, int entityPrimaryKey, @Nonnull AssociatedDataKey key) {
		return this.associatedDataStorageContainer.computeIfAbsent(key, keyRef -> new AssociatedDataStoragePart(entityPrimaryKey, keyRef));
	}

	@Nonnull
	@Override
	public ReferencesStoragePart getReferencesStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
		if (this.referencesStorageContainer == null) {
			this.referencesStorageContainer = new ReferencesStoragePart(entityPrimaryKey);
		}
		return this.referencesStorageContainer;
	}

	@Nonnull
	@Override
	public PricesStoragePart getPriceStoragePart(@Nonnull String entityType, int entityPrimaryKey) {
		if (this.pricesStorageContainer == null) {
			this.pricesStorageContainer = new PricesStoragePart(entityPrimaryKey);
		}
		return this.pricesStorageContainer;
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
