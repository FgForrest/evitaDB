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

package io.evitadb.api.requestResponse.schema.mutation.attribute;

import io.evitadb.api.exception.SortableAttributeCompoundSchemaException;
import io.evitadb.api.requestResponse.cdc.Operation;
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
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.SchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Mutation is responsible for removing an existing {@link AttributeSchemaContract} in the
 * {@link EntitySchemaContract} or {@link GlobalAttributeSchemaContract} in the {@link CatalogSchemaContract}.
 * The removal is refused with {@link SortableAttributeCompoundSchemaException} when a sortable attribute compound of
 * the entity or of the reference is made of the attribute - the compound would name a missing attribute and every
 * later upsert into the collection would fail on it. A removal of a global attribute verifies every entity schema
 * using it before the catalog schema changes, so a refusal by one of them leaves all of them as they are.
 * Mutation can be used for altering also the existing {@link AttributeSchemaContract}
 * or {@link GlobalAttributeSchemaContract} alone.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with
 * {@link CreateAttributeSchemaMutation} mutation (if such is found in mutation pipeline).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class RemoveAttributeSchemaMutation
	extends AbstractAttributeSchemaMutation
	implements GlobalAttributeSchemaMutation, ReferenceAttributeSchemaMutation,
	CombinableLocalEntitySchemaMutation, CombinableCatalogSchemaMutation {

	@Serial private static final long serialVersionUID = 6927903538683404070L;

	public RemoveAttributeSchemaMutation(@Nonnull String name) {
		super(name);
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull LocalCatalogSchemaMutation existingMutation) {
		if (existingMutation instanceof AttributeSchemaMutation attributeSchemaMutation && Objects.equals(this.name, attributeSchemaMutation.getName())) {
			return new MutationCombinationResult<>(true, null, this);
		} else {
			return null;
		}
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		final SchemaMutation mutationToExamine = existingMutation instanceof ModifyReferenceAttributeSchemaMutation wrappingMutation ? wrappingMutation.getAttributeSchemaMutation() : existingMutation;
		if (mutationToExamine instanceof AttributeSchemaMutation attributeSchemaMutation && Objects.equals(this.name, attributeSchemaMutation.getName())) {
			return new MutationCombinationResult<>(true, null, this);
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

	/**
	 * Removes the global attribute from the catalog schema and cascades the removal into the entity schemas using it;
	 * an entity schema with an attribute of its own of that name keeps it. All of them are verified before the catalog
	 * schema changes. A global attribute the catalog schema does not hold leaves it as it is.
	 *
	 * @throws SortableAttributeCompoundSchemaException when a sortable attribute compound of an entity schema using
	 *                                                  the attribute is made of it
	 */
	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaProvider entitySchemaAccessor) {
		Assert.isPremiseValid(catalogSchema != null, "Catalog schema is mandatory!");
		final Optional<GlobalAttributeSchemaContract> existingAttributeSchema = catalogSchema.getAttribute(this.name);
		if (existingAttributeSchema.isEmpty()) {
			// the attribute schema was already removed - or just doesn't exist,
			// so we can simply return current schema
			return new CatalogSchemaWithImpactOnEntitySchemas(catalogSchema);
		} else {
			// every entity schema using the attribute is verified before the first changes, because the removal reaches
			// them one after another and a refusal by one of them would leave the ones before it without the attribute
			final List<EntitySchemaContract> usingEntitySchemas =
				AttributeSchemaMutationVerifier.entitySchemasUsingGlobalAttribute(entitySchemaAccessor, this.name);
			final ModifyEntitySchemaMutation[] entitySchemaMutations =
				new ModifyEntitySchemaMutation[usingEntitySchemas.size()];
			for (int i = 0; i < entitySchemaMutations.length; i++) {
				final EntitySchemaContract entitySchema = usingEntitySchemas.get(i);
				AttributeSchemaMutationVerifier.verifyRemovalInEntity(entitySchema, this.name);
				entitySchemaMutations[i] = new ModifyEntitySchemaMutation(entitySchema.getName(), this);
			}
			return new CatalogSchemaWithImpactOnEntitySchemas(
				CatalogSchema._internalBuild(
					catalogSchema.version() + 1,
					catalogSchema.getName(),
					catalogSchema.getNameVariants(),
					catalogSchema.getDescription(),
					catalogSchema.getConflictResolution().orElse(null),
					catalogSchema.getCatalogEvolutionMode(),
					catalogSchema.getAttributes().values()
						.stream()
						.filter(it -> !it.getName().equals(this.name))
						.collect(
							Collectors.toMap(
								AttributeSchemaContract::getName,
								Function.identity()
							)
						),
					entitySchemaAccessor
				),
				entitySchemaMutations
			);
		}
	}

	/**
	 * Refuses the removal when a sortable attribute compound of the entity is made of the attribute. An attribute the
	 * entity schema does not hold leaves it as it is, which is what the replay of a cascaded global removal relies on.
	 *
	 * @throws SortableAttributeCompoundSchemaException when a sortable attribute compound of the entity is made of the
	 *                                                  attribute
	 */
	@Nonnull
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final Optional<EntityAttributeSchemaContract> existingAttributeSchema = entitySchema.getAttribute(this.name);
		if (existingAttributeSchema.isEmpty()) {
			// the attribute schema was already removed - or just doesn't exist,
			// so we can simply return current schema
			return entitySchema;
		} else {
			AttributeSchemaMutationVerifier.verifyRemovalInEntity(entitySchema, this.name);
			return EntitySchema._internalBuild(
				entitySchema.version() + 1,
				entitySchema.getName(),
				entitySchema.getNameVariants(),
				entitySchema.getDescription(),
				entitySchema.getDeprecationNotice(),
				entitySchema.getConflictResolution().orElse(null),
				entitySchema.isWithGeneratedPrimaryKey(),
				entitySchema.isWithHierarchy(),
				entitySchema.getHierarchyIndexedInScopes(),
				entitySchema.isWithPrice(),
				entitySchema.getPriceIndexedInScopes(),
				entitySchema.getIndexedPricePlaces(),
				entitySchema.getLocales(),
				entitySchema.getCurrencies(),
				entitySchema.getAttributes().values()
					.stream()
					.filter(it -> !it.getName().equals(this.name))
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

	/**
	 * Refuses the removal when a sortable attribute compound of the reference is made of the attribute, unless
	 * `consistencyChecks` is {@link ConsistencyChecks#SKIP}. An attribute the reference does not hold leaves it as it
	 * is, while one a bound reflected reference inherits is refused with `InvalidSchemaMutationException` by the
	 * lookup of {@link #getReferenceAttributeSchema(ReferenceSchemaContract, String)}, as it can be removed only in the
	 * original reference.
	 *
	 * @throws SortableAttributeCompoundSchemaException when a sortable attribute compound of the reference is made of
	 *                                                  the attribute
	 */
	@Nullable
	@Override
	public ReferenceSchemaContract mutate(@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceSchemaContract referenceSchema, @Nonnull ConsistencyChecks consistencyChecks) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		final Optional<AttributeSchemaContract> existingAttributeSchema = getReferenceAttributeSchema(referenceSchema, this.name);
		if (existingAttributeSchema.isEmpty()) {
			// the attribute schema was already removed - or just doesn't exist,
			// so we can simply return current schema
			return referenceSchema;
		} else {
			if (consistencyChecks != ConsistencyChecks.SKIP) {
				// the reference schema builders skip the check and refuse the removal on their own; a reflected
				// reference whose original is not available yet skips it too, as the compounds it inherits are not
				// known before it is bound
				AttributeSchemaMutationVerifier.verifyRemovalInReference(entitySchema, referenceSchema, this.name);
			}
			if (referenceSchema instanceof ReflectedReferenceSchema reflectedReferenceSchema) {
				return reflectedReferenceSchema
					.withDeclaredAttributes(
						reflectedReferenceSchema.getDeclaredAttributes().values()
							.stream()
							.filter(it -> !it.getName().equals(this.name))
							.collect(
								Collectors.toMap(
									AttributeSchemaContract::getName,
									Function.identity()
								)
							)
					);
			} else {
				return ReferenceSchema._internalBuild(
					referenceSchema.getName(),
					referenceSchema.getNameVariants(),
					referenceSchema.getDescription(),
					referenceSchema.getDeprecationNotice(),
					referenceSchema.getCardinality(),
					referenceSchema.getReferencedEntityType(),
					referenceSchema.isReferencedEntityTypeManaged() ?
						Collections.emptyMap() : referenceSchema.getEntityTypeNameVariants(s -> null),
					referenceSchema.isReferencedEntityTypeManaged(),
					referenceSchema.getReferencedGroupType(),
					referenceSchema.isReferencedGroupTypeManaged() ?
						Collections.emptyMap() : referenceSchema.getGroupTypeNameVariants(s -> null),
					referenceSchema.isReferencedGroupTypeManaged(),
					referenceSchema.getReferenceIndexTypeInScopes(),
					referenceSchema.getIndexedComponentsInScopes(),
					referenceSchema.getFacetedInScopes(),
					referenceSchema.getFacetedPartiallyInScopes(),
					referenceSchema.getAllHistogramIndexDefinitions(),
					referenceSchema.getBucketedPartiallyInScopes(),
					referenceSchema.getAttributes().values()
						.stream()
						.filter(it -> !it.getName().equals(this.name))
						.collect(
							Collectors.toMap(
								AttributeSchemaContract::getName,
								Function.identity()
							)
						),
					referenceSchema.getSortableAttributeCompounds(),
					referenceSchema.getConflictResolutionOverride()
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
		return "Remove attribute schema: " +
			"name='" + this.name + '\'';
	}

}
