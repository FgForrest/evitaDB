/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.query.expression.visitor.PathItem;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.FilterIndexCapability;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.annotation.SerializableCreator;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition.AttributePathClassification;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition.AttributeSource;
import io.evitadb.api.requestResponse.schema.mutation.CombinableCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutator;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.api.query.expression.visitor.AccessedDataFinder.findAccessedPaths;
import static io.evitadb.dataType.Scope.NO_SCOPE;

/**
 * Mutation is responsible for setting value to a {@link AttributeSchemaContract#isFilterable()}
 * in {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link AttributeSchemaContract} or
 * {@link GlobalAttributeSchemaContract} alone.
 * Alongside the boolean filterability, the mutation can carry per-scope {@link FilterIndexCapability}
 * accelerations via {@link #getFilterCapabilitiesInScopes()} - a reader looking only at this summary should
 * not assume the mutation is a plain filterability toggle.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class SetAttributeSchemaFilterableMutation
	extends AbstractAttributeSchemaMutation
	implements EntityAttributeSchemaMutation, GlobalAttributeSchemaMutation, ReferenceAttributeSchemaMutation,
	CombinableLocalEntitySchemaMutation, CombinableCatalogSchemaMutation {
	@Serial private static final long serialVersionUID = -382658973541254820L;

	@Getter @Nonnull private final Scope[] filterableInScopes;
	/**
	 * Optional accelerations the filter index should maintain, per scope. Never `null` after construction - an absent
	 * field on the wire (an older client, an older WAL record) deserializes as the empty array, which is exactly the
	 * behaviour every schema had before capabilities existed.
	 */
	@Getter @Nonnull private final ScopedFilterCapabilities[] filterCapabilitiesInScopes;

	public SetAttributeSchemaFilterableMutation(@Nonnull String name, boolean filterable) {
		this(
			name,
			filterable ? Scope.DEFAULT_SCOPES : NO_SCOPE
		);
	}

	public SetAttributeSchemaFilterableMutation(
		@Nonnull String name,
		@Nullable Scope[] filterableInScopes
	) {
		this(name, filterableInScopes, null);
	}

	@SerializableCreator
	public SetAttributeSchemaFilterableMutation(
		@Nonnull String name,
		@Nullable Scope[] filterableInScopes,
		@Nullable ScopedFilterCapabilities[] filterCapabilitiesInScopes
	) {
		super(name);
		this.filterableInScopes = filterableInScopes == null ? NO_SCOPE : filterableInScopes;
		this.filterCapabilitiesInScopes = filterCapabilitiesInScopes == null ?
			ScopedFilterCapabilities.EMPTY : filterCapabilitiesInScopes;
		verifyCapabilityScopesAreFilterable(this.name, this.filterableInScopes, this.filterCapabilitiesInScopes);
	}

	/**
	 * Builds the mutation from carriers alone - each carrier both *names* a scope the attribute becomes filterable in
	 * and *lists* the capabilities maintained there, so the two halves cannot drift apart. This is the form schema
	 * diffing and the builder use; the wire form keeps them separate because the scope array predates capabilities and
	 * old clients still send only that.
	 *
	 * @param name                       name of the altered attribute
	 * @param filterCapabilitiesInScopes one carrier per scope the attribute should be filterable in
	 * @return the mutation making the attribute filterable in exactly the carriers' scopes
	 */
	@Nonnull
	public static SetAttributeSchemaFilterableMutation fromCapabilities(
		@Nonnull String name,
		@Nonnull ScopedFilterCapabilities... filterCapabilitiesInScopes
	) {
		final Scope[] scopes = new Scope[filterCapabilitiesInScopes.length];
		for (int i = 0; i < filterCapabilitiesInScopes.length; i++) {
			scopes[i] = filterCapabilitiesInScopes[i].scope();
		}
		return new SetAttributeSchemaFilterableMutation(name, scopes, filterCapabilitiesInScopes);
	}

	public boolean isFilterable() {
		return !ArrayUtils.isEmptyOrItsValuesNull(this.filterableInScopes);
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull LocalCatalogSchemaMutation existingMutation
	) {
		if (existingMutation instanceof SetAttributeSchemaFilterableMutation theExistingMutation &&
			this.name.equals(theExistingMutation.getName())
		) {
			return new MutationCombinationResult<>(null, this);
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
		if (existingMutation instanceof SetAttributeSchemaFilterableMutation theExistingMutation &&
			this.name.equals(theExistingMutation.getName())
		) {
			return new MutationCombinationResult<>(null, this);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public <S extends AttributeSchemaContract> S mutate(
		@Nullable CatalogSchemaContract catalogSchema, @Nullable S attributeSchema, @Nonnull Class<S> schemaType
	) {
		Assert.isPremiseValid(attributeSchema != null, "Attribute schema is mandatory!");
		final EnumSet<Scope> filterable = ArrayUtils.toEnumSet(Scope.class, this.filterableInScopes);
		final EnumMap<Scope, Set<FilterIndexCapability>> capabilities =
			AttributeSchema.toFilterCapabilitiesEnumMap(this.filterCapabilitiesInScopes);
		verifyCapabilitiesApplicableToType(this.name, attributeSchema.getType(), capabilities);
		if (attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema) {
			if (globalAttributeSchema.getFilterableInScopes().equals(filterable) &&
				globalAttributeSchema.getFilterCapabilitiesInScopes().equals(capabilities)) {
				return attributeSchema;
			} else {
				//noinspection unchecked,rawtypes
				return (S) GlobalAttributeSchema._internalBuild(
					this.name,
					globalAttributeSchema.getNameVariants(),
					globalAttributeSchema.getDescription(),
					globalAttributeSchema.getDeprecationNotice(),
					globalAttributeSchema.getUniquenessTypeInScopes(),
					globalAttributeSchema.getGlobalUniquenessTypeInScopes(),
					filterable,
					capabilities,
					globalAttributeSchema.getSortableInScopes(),
					globalAttributeSchema.isLocalized(),
					globalAttributeSchema.isNullable(),
					globalAttributeSchema.isRepresentative(),
					(Class) globalAttributeSchema.getType(),
					globalAttributeSchema.getDefaultValue(),
					globalAttributeSchema.getIndexedDecimalPlaces(),
					globalAttributeSchema.getConflictResolutionOverride()
				);
			}
		} else if (attributeSchema instanceof EntityAttributeSchemaContract entityAttributeSchema) {
			if (entityAttributeSchema.getFilterableInScopes().equals(filterable) &&
				entityAttributeSchema.getFilterCapabilitiesInScopes().equals(capabilities)) {
				return attributeSchema;
			} else {
				//noinspection unchecked,rawtypes
				return (S) EntityAttributeSchema._internalBuild(
					this.name,
					entityAttributeSchema.getNameVariants(),
					entityAttributeSchema.getDescription(),
					entityAttributeSchema.getDeprecationNotice(),
					entityAttributeSchema.getUniquenessTypeInScopes(),
					filterable,
					capabilities,
					entityAttributeSchema.getSortableInScopes(),
					entityAttributeSchema.isLocalized(),
					entityAttributeSchema.isNullable(),
					entityAttributeSchema.isRepresentative(),
					(Class) entityAttributeSchema.getType(),
					entityAttributeSchema.getDefaultValue(),
					entityAttributeSchema.getIndexedDecimalPlaces(),
					entityAttributeSchema.getConflictResolutionOverride()
				);
			}
		} else {
			if (attributeSchema.getFilterableInScopes().equals(filterable) &&
				attributeSchema.getFilterCapabilitiesInScopes().equals(capabilities)) {
				return attributeSchema;
			} else {
				//noinspection unchecked,rawtypes
				return (S) AttributeSchema._internalBuild(
					this.name,
					attributeSchema.getNameVariants(),
					attributeSchema.getDescription(),
					attributeSchema.getDeprecationNotice(),
					attributeSchema.getUniquenessTypeInScopes(),
					filterable,
					capabilities,
					attributeSchema.getSortableInScopes(),
					attributeSchema.isLocalized(),
					attributeSchema.isNullable(),
					attributeSchema.isRepresentative(),
					(Class) attributeSchema.getType(),
					attributeSchema.getDefaultValue(),
					attributeSchema.getIndexedDecimalPlaces(),
					attributeSchema.getConflictResolutionOverride()
				);
			}
		}
	}

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(
		@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaProvider entitySchemaAccessor
	) {
		return mutateGlobalAttributeSchema(catalogSchema, entitySchemaAccessor, this);
	}

	@Nullable
	@Override
	public ReferenceSchemaContract mutate(
		@Nonnull EntitySchemaContract entitySchema, @Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull ConsistencyChecks consistencyChecks
	) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		// deliberately outside the consistency-check guard: this is not a consistency question about the reference's
		// current state but a flat statement that the capability cannot be served here at all, so a SKIP caller must
		// not be able to write a declaration nothing will ever honour
		verifyCapabilityNotOnReferenceAttribute(
			this.name, referenceSchema.getName(), entitySchema.getName(), this.filterCapabilitiesInScopes
		);
		if (consistencyChecks != ReferenceSchemaMutator.ConsistencyChecks.SKIP) {
			final List<Scope> nonIndexedScopes = Arrays.stream(this.filterableInScopes)
				.filter(scope -> !referenceSchema.isIndexedInScope(scope))
				.toList();
			Assert.isTrue(
				nonIndexedScopes.isEmpty(),
				() -> new InvalidSchemaMutationException(
					"The reference `" + referenceSchema.getName() + "` is in entity `" + entitySchema.getName() +
						"` is not indexed in required scopes: " +
						nonIndexedScopes.stream().map(Enum::name).collect(Collectors.joining(", ")) + "! " +
						"Non-indexed references must not contain filterable attribute `" + this.name + "`!"
				)
			);
			verifyNotUsedAsHistogramValueSource(referenceSchema, entitySchema.getName());
		}
		return ReferenceAttributeSchemaMutation.super.mutate(entitySchema, referenceSchema, consistencyChecks);
	}

	/**
	 * Validates that the attribute is not used as a histogram value source when filterability is
	 * being removed, then delegates to the default entity attribute schema mutation logic.
	 */
	@Nonnull
	@Override
	public EntitySchemaContract mutate(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema
	) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		if (!isFilterable()) {
			verifyNotUsedAsReferencedEntityHistogramValueSource(
				catalogSchema, entitySchema.getName()
			);
		}
		return EntityAttributeSchemaMutation.super.mutate(catalogSchema, entitySchema);
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return "Set attribute `" + this.name + "` schema: " +
			"filterable=" + (isFilterable() ? "(in scopes: " + Arrays.toString(this.filterableInScopes) + ")" : "no") +
			(this.filterCapabilitiesInScopes.length == 0 ?
				"" : ", capabilities=(" + join(this.filterCapabilitiesInScopes) + ")");
	}

	/**
	 * Verifies that a reference attribute is not used as a histogram value source when its filterability
	 * is being removed. Scans all histogram index definitions on the given reference for value expressions
	 * of the form `$reference.attributes['x']` that match the attribute being mutated.
	 *
	 * @param referenceSchema the reference schema whose histogram definitions are checked
	 * @param entityTypeName  the entity type name for error messages
	 * @throws InvalidSchemaMutationException if the attribute is referenced by a histogram value expression
	 */
	private void verifyNotUsedAsHistogramValueSource(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull String entityTypeName
	) {
		if (isFilterable()) {
			return;
		}
		final Map<Scope, Map<String, HistogramIndexDefinition>> allDefs =
			referenceSchema.getAllHistogramIndexDefinitions();
		for (Map.Entry<Scope, Map<String, HistogramIndexDefinition>> scopeEntry : allDefs.entrySet()) {
			for (Map.Entry<String, HistogramIndexDefinition> defEntry : scopeEntry.getValue().entrySet()) {
				final Expression valueExpression = defEntry.getValue().valueExpression();
				if (valueExpression == null) {
					continue;
				}
				final String referencedAttr = extractAttributeNameBySource(
					valueExpression, AttributeSource.REFERENCE_ATTRIBUTE
				);
				if (this.name.equals(referencedAttr)) {
					throw new InvalidSchemaMutationException(
						"Cannot remove filterability from attribute `" + this.name +
							"` on reference `" + referenceSchema.getName() +
							"` in entity `" + entityTypeName +
							"` because it is used as the value source in histogram `" +
							defEntry.getKey() + "` (scope " + scopeEntry.getKey().name() +
							"). The attribute must remain filterable."
					);
				}
			}
		}
	}

	/**
	 * Verifies that an entity attribute is not used as a histogram value source via
	 * `$reference.referencedEntity?.attributes['x']` on any reference in any other entity schema that
	 * references the entity type being modified.
	 *
	 * @param catalogSchema  the catalog schema providing access to all entity schemas
	 * @param entityTypeName the name of the entity type whose attribute is being modified
	 * @throws InvalidSchemaMutationException if the attribute is referenced by a histogram value expression
	 */
	private void verifyNotUsedAsReferencedEntityHistogramValueSource(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull String entityTypeName
	) {
		for (EntitySchemaContract otherEntitySchema : catalogSchema.getEntitySchemas()) {
			for (ReferenceSchemaContract refSchema : otherEntitySchema.getReferences().values()) {
				if (!entityTypeName.equals(refSchema.getReferencedEntityType())) {
					continue;
				}
				final Map<Scope, Map<String, HistogramIndexDefinition>> allDefs =
					refSchema.getAllHistogramIndexDefinitions();
				for (Map.Entry<Scope, Map<String, HistogramIndexDefinition>> scopeEntry : allDefs.entrySet()) {
					for (Map.Entry<String, HistogramIndexDefinition> defEntry : scopeEntry.getValue().entrySet()) {
						final Expression valueExpression = defEntry.getValue().valueExpression();
						if (valueExpression == null) {
							continue;
						}
						final String referencedAttr = extractAttributeNameBySource(
							valueExpression, AttributeSource.REFERENCED_ENTITY_ATTRIBUTE
						);
						if (this.name.equals(referencedAttr)) {
							throw new InvalidSchemaMutationException(
								"Cannot remove filterability from attribute `" + this.name +
									"` on entity `" + entityTypeName +
									"` because it is used as the value source in histogram `" +
									defEntry.getKey() + "` (scope " + scopeEntry.getKey().name() +
									") on reference `" + refSchema.getName() +
									"` in entity `" + otherEntitySchema.getName() +
									"`. The attribute must remain filterable."
							);
						}
					}
				}
			}
		}
	}

	/**
	 * Extracts the attribute name from a value expression that matches the given
	 * {@link AttributeSource}. Delegates path classification to
	 * {@link HistogramIndexDefinition#classifyAttributePath(List)}.
	 *
	 * @param valueExpression the histogram value expression to analyze
	 * @param expectedSource  the expected attribute source to match
	 * @return the attribute name if found for the given source, null otherwise
	 */
	@Nullable
	private static String extractAttributeNameBySource(
		@Nonnull Expression valueExpression,
		@Nonnull AttributeSource expectedSource
	) {
		final List<List<PathItem>> paths = findAccessedPaths(valueExpression);
		for (final List<PathItem> path : paths) {
			final AttributePathClassification classification =
				HistogramIndexDefinition.classifyAttributePath(path);
			if (classification != null && classification.source() == expectedSource) {
				return classification.attributeName();
			}
		}
		return null;
	}
}
