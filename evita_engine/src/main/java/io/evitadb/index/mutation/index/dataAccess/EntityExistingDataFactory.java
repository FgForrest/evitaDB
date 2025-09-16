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


import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.RepresentativeAttributeDefinition;
import io.evitadb.index.RepresentativeReferenceKey;
import io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor;
import io.evitadb.utils.CollectionUtils;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;

/**
 * This implementation provides access to memoized instances of {@link EntityAttributeValueSupplier} and
 * {@link ReferenceAttributeValueSupplier} instances that retrieve informations directly from {@link Entity} instance.
 *
 * This factory is meant to be used when entity changes scope and we have fully deserialized entity at disposal.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public final class EntityExistingDataFactory implements ExistingDataSupplierFactory {
	private final Entity entity;
	private final EntitySchema entitySchema;
	private ExistingAttributeValueSupplier entityAttributeValueSupplier;
	private EntityPriceSupplier entityPriceSupplier;
	private ReferenceSupplier referenceSupplier;
	private Map<RepresentativeReferenceKey, ReferenceAttributeValueSupplier> referenceAttributeValueSuppliers;

	/**
	 * Registers the removal of the attribute specified by the given key.
	 * This is used to mimic behavior of making attribute dropped in {@link ContainerizedLocalMutationExecutor}, which
	 * is not used in this context (when entity changes scope).
	 *
	 * @param key the key of the attribute to be removed; must not be null
	 */
	public void registerRemoval(@Nonnull AttributeKey key) {
		if (getEntityAttributeValueSupplier() instanceof EntityAttributeValueSupplier eavs) {
			eavs.registerRemoval(key);
		}
	}

	@Nonnull
	@Override
	public ExistingAttributeValueSupplier getEntityAttributeValueSupplier() {
		if (this.entityAttributeValueSupplier == null) {
			this.entityAttributeValueSupplier = new EntityAttributeValueSupplier(this.entity);
		}
		return this.entityAttributeValueSupplier;
	}

	@Nonnull
	@Override
	public ReferenceSupplier getReferenceSupplier() {
		if (this.referenceSupplier == null) {
			this.referenceSupplier = new EntityReferenceSupplier(this.entity);
		}
		return this.referenceSupplier;
	}

	@Nonnull
	@Override
	public ExistingAttributeValueSupplier getReferenceAttributeValueSupplier(@Nonnull RepresentativeReferenceKey referenceKey) {
		this.referenceAttributeValueSuppliers = this.referenceAttributeValueSuppliers == null ?
			CollectionUtils.createHashMap(16) :
			this.referenceAttributeValueSuppliers;
		return this.referenceAttributeValueSuppliers.computeIfAbsent(
			referenceKey,
			rrk -> {
				final ReferenceSchema referenceSchema = this.entitySchema.getReferenceOrThrowException(rrk.referenceName());
				if (referenceSchema.getCardinality().allowsDuplicates()) {
					final List<ReferenceContract> allReferences = this.entity.getReferences(rrk.referenceKey());
					if (allReferences.isEmpty()) {
						throw new ReferenceNotFoundException(
							rrk.referenceName(),
							rrk.primaryKey(),
							this.entity
						);
					} else {
						final RepresentativeAttributeDefinition rad = referenceSchema.getRepresentativeAttributeDefinition();
						for (ReferenceContract theReference : allReferences) {
							if (new RepresentativeReferenceKey(rrk.referenceKey(), rad.getRepresentativeValues(theReference)).equals(rrk)) {
								return new ReferenceAttributeValueSupplier(this.entity, theReference);
							}
						}
						throw new ReferenceNotFoundException(
							rrk.referenceName(),
							rrk.primaryKey(),
							this.entity
						);
					}
				} else {
					return new ReferenceAttributeValueSupplier(
						this.entity,
						this.entity.getReference(rrk.referenceKey())
						           .orElseThrow(
							           () -> new ReferenceNotFoundException(
								           rrk.referenceName(),
								           rrk.primaryKey(),
								           this.entity
							           )
						           )
					);
				}
			}
		);
	}

	@Nonnull
	@Override
	public EntityPriceSupplier getPriceSupplier() {
		if (this.entityPriceSupplier == null) {
			this.entityPriceSupplier = new EntityPriceSupplier(this.entity);
		}
		return this.entityPriceSupplier;
	}
}
