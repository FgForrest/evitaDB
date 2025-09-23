/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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


import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.store.spi.model.storageParts.accessor.WritableEntityStorageContainerAccessor;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Objects;

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
	private final EntitySchema entitySchema;
	private final int entityPrimaryKey;
	private EntityStoragePartAccessorAttributeValueSupplier entityAttributeValueSupplier;
	private PriceStoragePartSupplier priceStoragePartSupplier;
	private ReferencesStoragePartSupplier referenceStoragePartSupplier;
	private Map<RepresentativeReferenceKey, ReferenceEntityStoragePartAccessorAttributeValueSupplier> referenceAttributeValueSuppliers;
	@Nullable private RepresentativeReferenceKeyAlias representativeReferenceKeyAlias;

	@Nonnull
	@Override
	public ExistingAttributeValueSupplier getEntityAttributeValueSupplier() {
		if (this.entityAttributeValueSupplier == null) {
			this.entityAttributeValueSupplier = new EntityStoragePartAccessorAttributeValueSupplier(
				this.containerAccessor, this.entitySchema.getName(), this.entityPrimaryKey
			);
		}
		return this.entityAttributeValueSupplier;
	}

	@Nonnull
	@Override
	public ReferenceSupplier getReferenceSupplier() {
		if (this.referenceStoragePartSupplier == null) {
			this.referenceStoragePartSupplier = new ReferencesStoragePartSupplier(
				this.containerAccessor.getReferencesStoragePart(this.entitySchema.getName(), this.entityPrimaryKey)
			);
		}
		return this.referenceStoragePartSupplier;
	}

	@Nonnull
	@Override
	public ExistingAttributeValueSupplier getReferenceAttributeValueSupplier(@Nonnull RepresentativeReferenceKey referenceKey) {
		this.referenceAttributeValueSuppliers = this.referenceAttributeValueSuppliers == null ?
			CollectionUtils.createHashMap(16) :
			this.referenceAttributeValueSuppliers;

		final RepresentativeReferenceKey keyToUse = this.representativeReferenceKeyAlias == null ||
			!Objects.equals(this.representativeReferenceKeyAlias.representativeReferenceKey(), referenceKey) ?
				referenceKey : this.representativeReferenceKeyAlias.aliasRepresentativeReferenceKey();
		return this.referenceAttributeValueSuppliers.computeIfAbsent(
			keyToUse,
			rrk -> new ReferenceEntityStoragePartAccessorAttributeValueSupplier(
				this.containerAccessor,
				this.entitySchema.getReferenceOrThrowException(rrk.referenceName()),
				rrk,
				this.entitySchema.getName(),
				this.entityPrimaryKey
			)
		);
	}

	@Nonnull
	@Override
	public ExistingPriceSupplier getPriceSupplier() {
		if (this.priceStoragePartSupplier == null) {
			this.priceStoragePartSupplier = new PriceStoragePartSupplier(
				this.containerAccessor.getPriceStoragePart(this.entitySchema.getName(), this.entityPrimaryKey)
			);
		}
		return this.priceStoragePartSupplier;
	}

	/**
	 * Executes the provided {@link Runnable} within a context that temporarily establishes a mapping
	 * (alias) between a primary {@link RepresentativeReferenceKey} and its alias counterpart.
	 * This allows the same internal entity-related operations to temporarily recognize the alias as if
	 * it were the primary key. Once the operation is completed, the alias mapping is removed.
	 * Throws {@link IllegalStateException} if nested aliasing is attempted.
	 *
	 * @param representativeReferenceKey the primary {@link RepresentativeReferenceKey} to map.
	 * @param aliasRepresentativeReferenceKey the alias {@link RepresentativeReferenceKey} to associate with the primary key.
	 * @param runnable the task to execute within the context of the alias mapping.
	 */
	public void executeWithRepresentativeReferenceKeyAlias(
		@Nonnull RepresentativeReferenceKey representativeReferenceKey,
		@Nonnull RepresentativeReferenceKey aliasRepresentativeReferenceKey,
		@Nonnull Runnable runnable
	) {
		if (this.representativeReferenceKeyAlias != null) {
			throw new IllegalStateException("Nested aliases are not supported!");
		}
		try {
			this.representativeReferenceKeyAlias = new RepresentativeReferenceKeyAlias(representativeReferenceKey, aliasRepresentativeReferenceKey);
			runnable.run();
		} finally {
			this.representativeReferenceKeyAlias = null;
		}
	}

	/**
	 * Represents a mapping between two {@link RepresentativeReferenceKey} instances.
	 * This class is used to create an alias relationship where one {@link RepresentativeReferenceKey} can temporarily
	 * act as an alternative to another.
	 *
	 * It typically facilitates operations that require the application to treat an alias {@link RepresentativeReferenceKey}
	 * as the primary one within a defined context, such as when executing tasks that need temporary key remapping.
	 */
	private record RepresentativeReferenceKeyAlias(
		@Nonnull RepresentativeReferenceKey representativeReferenceKey,
		@Nonnull RepresentativeReferenceKey aliasRepresentativeReferenceKey
	) {
	}

}
