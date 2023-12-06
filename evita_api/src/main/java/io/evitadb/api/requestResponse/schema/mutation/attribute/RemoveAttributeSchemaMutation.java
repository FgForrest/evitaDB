/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.api.requestResponse.schema.mutation.attribute;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.SchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mutation is responsible for removing an existing {@link AttributeSchemaContract} in the
 * {@link EntitySchemaContract} or {@link GlobalAttributeSchemaContract} in the {@link CatalogSchemaContract}.
 * Mutation can be used for altering also the existing {@link AttributeSchemaContract}
 * or {@link GlobalAttributeSchemaContract} alone.
 * Mutation implements {@link CombinableEntitySchemaMutation} allowing to resolve conflicts with
 * {@link CreateAttributeSchemaMutation} mutation (if such is found in mutation pipeline).
 *
 * TOBEDONE JNO - write tests
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class RemoveAttributeSchemaMutation implements
	GlobalAttributeSchemaMutation, ReferenceAttributeSchemaMutation,
	CombinableEntitySchemaMutation, CombinableCatalogSchemaMutation {

	@Serial private static final long serialVersionUID = 6927903538683404070L;
	@Getter @Nonnull private final String name;

	public RemoveAttributeSchemaMutation(@Nonnull String name) {
		this.name = name;
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull LocalCatalogSchemaMutation existingMutation) {
		if (existingMutation instanceof AttributeSchemaMutation attributeSchemaMutation && Objects.equals(name, attributeSchemaMutation.getName())) {
			return new MutationCombinationResult<>(true, null, this);
		} else {
			return null;
		}
	}

	@Nullable
	@Override
	public MutationCombinationResult<EntitySchemaMutation> combineWith(@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull EntitySchemaContract currentEntitySchema, @Nonnull EntitySchemaMutation existingMutation) {
		final SchemaMutation mutationToExamine = existingMutation instanceof ModifyReferenceAttributeSchemaMutation wrappingMutation ? wrappingMutation.getAttributeSchemaMutation() : existingMutation;
		if (mutationToExamine instanceof AttributeSchemaMutation attributeSchemaMutation && Objects.equals(name, attributeSchemaMutation.getName())) {
			if (mutationToExamine instanceof CreateAttributeSchemaMutation || mutationToExamine instanceof CreateGlobalAttributeSchemaMutation) {
				return new MutationCombinationResult<>(null);
			} else {
				return new MutationCombinationResult<>(null, existingMutation);
			}
		} else {
			return null;
		}
	}

	@Nullable
	@Override
	public <S extends AttributeSchemaContract> S mutate(@Nullable CatalogSchemaContract catalogSchema, @Nullable S attributeSchema, @Nonnull Class<S> schemaType) {
		Assert.isPremiseValid(attributeSchema != null, "Attribute schema is mandatory!");
		return null;
	}

	@Nullable
	@Override
	public CatalogSchemaContract mutate(@Nullable CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaProvider entitySchemaAccessor) {
		Assert.isPremiseValid(catalogSchema != null, "Catalog schema is mandatory!");
		final Optional<GlobalAttributeSchemaContract> existingAttributeSchema = catalogSchema.getAttribute(name);
		if (existingAttributeSchema.isEmpty()) {
			// the attribute schema was already removed - or just doesn't exist,
			// so we can simply return current schema
			return catalogSchema;
		} else {
			return CatalogSchema._internalBuild(
				catalogSchema.getVersion() + 1,
				catalogSchema.getName(),
				catalogSchema.getNameVariants(),
				catalogSchema.getDescription(),
				catalogSchema.getCatalogEvolutionMode(),
				catalogSchema.getAttributes().values()
					.stream()
					.filter(it -> !it.getName().equals(name))
					.collect(
						Collectors.toMap(
							AttributeSchemaContract::getName,
							Function.identity()
						)
					),
				entitySchemaAccessor
			);
		}
	}

	@Nullable
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final Optional<EntityAttributeSchemaContract> existingAttributeSchema = entitySchema.getAttribute(name);
		if (existingAttributeSchema.isEmpty()) {
			// the attribute schema was already removed - or just doesn't exist,
			// so we can simply return current schema
			return entitySchema;
		} else {
			return EntitySchema._internalBuild(
				entitySchema.version() + 1,
				entitySchema.getName(),
				entitySchema.getNameVariants(),
				entitySchema.getDescription(),
				entitySchema.getDeprecationNotice(),
				entitySchema.isWithGeneratedPrimaryKey(),
				entitySchema.isWithHierarchy(),
				entitySchema.isWithPrice(),
				entitySchema.getIndexedPricePlaces(),
				entitySchema.getLocales(),
				entitySchema.getCurrencies(),
				entitySchema.getAttributes().values()
					.stream()
					.filter(it -> !it.getName().equals(name))
					.collect(
						Collectors.toMap(
							AttributeSchemaContract::getName,
							Function.identity()
						)
					),
				entitySchema.getAssociatedData(),
				entitySchema.getReferences(),
				entitySchema.getEvolutionMode(),
				entitySchema.getSortableAttributeCompounds()
			);
		}
	}

	@Nullable
	@Override
	public ReferenceSchemaContract mutate(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceSchemaContract referenceSchema) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		final Optional<AttributeSchemaContract> existingAttributeSchema = referenceSchema.getAttribute(name);
		if (existingAttributeSchema.isEmpty()) {
			// the attribute schema was already removed - or just doesn't exist,
			// so we can simply return current schema
			return referenceSchema;
		} else {
			return ReferenceSchema._internalBuild(
				referenceSchema.getName(),
				referenceSchema.getNameVariants(),
				referenceSchema.getDescription(),
				referenceSchema.getDeprecationNotice(),
				referenceSchema.getReferencedEntityType(),
				referenceSchema.getEntityTypeNameVariants(entityType -> null),
				referenceSchema.isReferencedEntityTypeManaged(),
				referenceSchema.getCardinality(),
				referenceSchema.getReferencedGroupType(),
				referenceSchema.getGroupTypeNameVariants(entityType -> null),
				referenceSchema.isReferencedGroupTypeManaged(),
				referenceSchema.isIndexed(),
				referenceSchema.isFaceted(),
				referenceSchema.getAttributes().values()
					.stream()
					.filter(it -> !it.getName().equals(name))
					.collect(
						Collectors.toMap(
							AttributeSchemaContract::getName,
							Function.identity()
						)
					),
				referenceSchema.getSortableAttributeCompounds()
			);
		}
	}

	@Override
	public String toString() {
		return "Remove attribute schema: " +
			"name='" + name + '\'';
	}

}
