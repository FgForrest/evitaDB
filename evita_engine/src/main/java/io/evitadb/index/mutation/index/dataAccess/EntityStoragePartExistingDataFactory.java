/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.index.mutation.index.dataAccess;


import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.store.spi.model.storageParts.accessor.WritableEntityStorageContainerAccessor;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * This implementation provides access to memoized instances of {@link EntityStoragePartAccessorAttributeValueSupplier} and
 * {@link ReferenceEntityStoragePartAccessorAttributeValueSupplier} instances that retrieve informations from
 * appropriate storage parts fetched from {@link WritableEntityStorageContainerAccessor}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public final class EntityStoragePartExistingDataFactory implements ExistingDataSupplierFactory {
	private final WritableEntityStorageContainerAccessor containerAccessor;
	private final String entityType;
	private final int entityPrimaryKey;
	private EntityStoragePartAccessorAttributeValueSupplier entityAttributeValueSupplier;
	private PriceStoragePartSupplier priceStoragePartSupplier;
	private ReferencesStoragePartSupplier referenceStoragePartSupplier;
	private Map<ReferenceKey, ReferenceEntityStoragePartAccessorAttributeValueSupplier> referenceAttributeValueSuppliers;

	@Nonnull
	@Override
	public ExistingAttributeValueSupplier getEntityAttributeValueSupplier() {
		if (this.entityAttributeValueSupplier == null) {
			this.entityAttributeValueSupplier = new EntityStoragePartAccessorAttributeValueSupplier(
				this.containerAccessor, this.entityType, this.entityPrimaryKey
			);
		}
		return this.entityAttributeValueSupplier;
	}

	@Nonnull
	@Override
	public ReferenceSupplier getReferenceSupplier() {
		if (this.referenceStoragePartSupplier == null) {
			this.referenceStoragePartSupplier = new ReferencesStoragePartSupplier(
				this.containerAccessor.getReferencesStoragePart(this.entityType, this.entityPrimaryKey)
			);
		}
		return this.referenceStoragePartSupplier;
	}

	@Nonnull
	@Override
	public ExistingAttributeValueSupplier getReferenceAttributeValueSupplier(@Nonnull ReferenceKey referenceKey) {
		this.referenceAttributeValueSuppliers = this.referenceAttributeValueSuppliers == null ?
			CollectionUtils.createHashMap(16) :
			this.referenceAttributeValueSuppliers;
		return this.referenceAttributeValueSuppliers.computeIfAbsent(
			referenceKey,
			theRefKey -> new ReferenceEntityStoragePartAccessorAttributeValueSupplier(
				this.containerAccessor, theRefKey, this.entityType, this.entityPrimaryKey
			)
		);
	}

	@Nonnull
	@Override
	public ExistingPriceSupplier getPriceSupplier() {
		if (this.priceStoragePartSupplier == null) {
			this.priceStoragePartSupplier = new PriceStoragePartSupplier(
				this.containerAccessor.getPriceStoragePart(this.entityType, this.entityPrimaryKey)
			);
		}
		return this.priceStoragePartSupplier;
	}
}
