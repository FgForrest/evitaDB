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

package io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.SchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.SortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSortableAttributeCompoundSchemaMutation;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Mutation is responsible for removing an existing {@link SortableAttributeCompoundSchemaContract} in the
 * {@link EntitySchemaContract} or in the {@link ReferenceSchemaContract}.
 * Mutation can be used for altering also the existing {@link SortableAttributeCompoundSchemaContract} alone.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with
 * {@link CreateSortableAttributeCompoundSchemaMutation} mutation (if such is found in mutation pipeline).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class RemoveSortableAttributeCompoundSchemaMutation
	implements CombinableLocalEntitySchemaMutation, ReferenceSortableAttributeCompoundSchemaMutation {
	@Serial private static final long serialVersionUID = 7583003492609737038L;

	@Getter @Nonnull private final String name;

	public RemoveSortableAttributeCompoundSchemaMutation(@Nonnull String name) {
		this.name = name;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		final SchemaMutation mutationToExamine = existingMutation instanceof ModifyReferenceSortableAttributeCompoundSchemaMutation wrappingMutation ? wrappingMutation.getSortableAttributeCompoundSchemaMutation() : existingMutation;
		if (mutationToExamine instanceof SortableAttributeCompoundSchemaMutation compoundSchemaMutation && Objects.equals(name, compoundSchemaMutation.getName())) {
			return new MutationCombinationResult<>(true, null, this);
		} else {
			return null;
		}
	}

	@Nullable
	@Override
	public SortableAttributeCompoundSchemaContract mutate(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nullable SortableAttributeCompoundSchemaContract sortableAttributeCompoundSchema
	) {
		Assert.isPremiseValid(sortableAttributeCompoundSchema != null, "Sortable attribute compound schema is mandatory!");
		return null;
	}

	@Nullable
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final Optional<SortableAttributeCompoundSchemaContract> existingAttributeSchema = entitySchema.getSortableAttributeCompound(this.name);
		if (existingAttributeSchema.isEmpty()) {
			// the sortable attribute compound schema was already removed - or just doesn't exist,
			// so we can simply return current schema
			return entitySchema;
		} else {
			if (entitySchema instanceof EntitySchema theEntitySchema) {
				return EntitySchema._internalBuild(
					theEntitySchema.version() + 1,
					theEntitySchema.getName(),
					theEntitySchema.getNameVariants(),
					theEntitySchema.getDescription(),
					theEntitySchema.getDeprecationNotice(),
					theEntitySchema.isWithGeneratedPrimaryKey(),
					theEntitySchema.isWithHierarchy(),
					theEntitySchema.getHierarchyIndexedInScopes(),
					theEntitySchema.isWithPrice(),
					theEntitySchema.getPriceIndexedInScopes(),
					theEntitySchema.getIndexedPricePlaces(),
					theEntitySchema.getLocales(),
					theEntitySchema.getCurrencies(),
					theEntitySchema.getAttributes(),
					theEntitySchema.getAssociatedData(),
					theEntitySchema.getReferences(),
					theEntitySchema.getEvolutionMode(),
					theEntitySchema.getSortableAttributeCompounds()
						.entrySet()
						.stream()
						.filter(it -> !Objects.equals(this.name, it.getKey()))
						.collect(
							Collectors.toMap(
								Map.Entry::getKey,
								Map.Entry::getValue
							)
						)
				);
			} else {
				throw new InvalidSchemaMutationException(
					"Unsupported entity schema type: " + entitySchema.getClass().getName()
				);
			}
		}
	}

	@Nullable
	@Override
	public ReferenceSchemaContract mutate(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceSchemaContract referenceSchema, @Nonnull ConsistencyChecks consistencyChecks) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		final Optional<SortableAttributeCompoundSchemaContract> existingCompoundSchema = getReferenceSortableAttributeCompoundSchema(referenceSchema, this.name);
		if (existingCompoundSchema.isEmpty()) {
			// the attribute schema was already removed - or just doesn't exist,
			// so we can simply return current schema
			return referenceSchema;
		} else {
			if (referenceSchema instanceof ReflectedReferenceSchema reflectedReferenceSchema) {
				return reflectedReferenceSchema
					.withDeclaredSortableAttributeCompounds(
						reflectedReferenceSchema.getDeclaredSortableAttributeCompounds()
							.entrySet()
							.stream()
							.filter(it -> !Objects.equals(this.name, it.getKey()))
							.collect(
								Collectors.toMap(
									Map.Entry::getKey,
									Map.Entry::getValue
								)
							)
					);
			} else if (referenceSchema instanceof ReferenceSchema theReferenceSchema) {
				return ReferenceSchema._internalBuild(
					theReferenceSchema.getName(),
					theReferenceSchema.getNameVariants(),
					theReferenceSchema.getDescription(),
					theReferenceSchema.getDeprecationNotice(),
					theReferenceSchema.getCardinality(),
					theReferenceSchema.getReferencedEntityType(),
					theReferenceSchema.getEntityTypeNameVariants(entityType -> null),
					theReferenceSchema.isReferencedEntityTypeManaged(),
					theReferenceSchema.getReferencedGroupType(),
					theReferenceSchema.getGroupTypeNameVariants(entityType -> null),
					theReferenceSchema.isReferencedGroupTypeManaged(),
					theReferenceSchema.getIndexedInScopes(),
					theReferenceSchema.getFacetedInScopes(),
					theReferenceSchema.getAttributes(),
					theReferenceSchema.getSortableAttributeCompounds()
						.entrySet()
						.stream()
						.filter(it -> !Objects.equals(this.name, it.getKey()))
						.collect(
							Collectors.toMap(
								Map.Entry::getKey,
								Map.Entry::getValue
							)
						)
				);
			} else {
				throw new InvalidSchemaMutationException(
					"Unsupported reference schema type: " + referenceSchema.getClass().getName()
				);
			}
		}
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.REMOVE;
	}

	@Override
	public String toString() {
		return "Remove sortable attribute compound schema: " +
			"name='" + this.name + '\'';
	}

}
