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

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.FilterIndexCapability;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaContract;
import io.evitadb.api.requestResponse.schema.NamedSchemaWithDeprecationContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.annotation.SerializableCreator;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CreateMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.dataType.Scope.NO_SCOPE;

/**
 * Mutation is responsible for setting up a new {@link AttributeSchemaContract} in the {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link AttributeSchemaContract} alone.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with
 * {@link RemoveAttributeSchemaMutation} mutation (if such is found in mutation pipeline).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class CreateAttributeSchemaMutation extends AbstractAttributeSchemaMutation
	implements ReferenceAttributeSchemaMutation, CombinableLocalEntitySchemaMutation, CreateMutation {
	@Serial private static final long serialVersionUID = -469815390440407268L;

	@Getter @Nullable private final String description;
	@Getter @Nullable private final String deprecationNotice;
	@Getter @Nonnull private final ScopedAttributeUniquenessType[] uniqueInScopes;
	@Getter @Nonnull private final Scope[] filterableInScopes;
	/**
	 * Optional accelerations the filter index should maintain for the newly created attribute, per scope. Never `null`
	 * after construction - the field is optional on the wire, and an older client that never sends it lands on the
	 * empty array, i.e. plain filterability.
	 */
	@Getter @Nonnull private final ScopedFilterCapabilities[] filterCapabilitiesInScopes;
	@Getter @Nonnull private final Scope[] sortableInScopes;
	@Getter private final boolean localized;
	@Getter private final boolean nullable;
	@Getter private final boolean representative;
	@Getter @Nonnull private final Class<? extends Serializable> type;
	@Getter @Nullable private final Serializable defaultValue;
	@Getter private final int indexedDecimalPlaces;
	@Getter @Nonnull private final ConflictResolutionOverride conflictResolutionOverride;

	public CreateAttributeSchemaMutation(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable AttributeUniquenessType unique,
		boolean filterable,
		boolean sortable,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<? extends Serializable> type,
		@Nullable Serializable defaultValue,
		int indexedDecimalPlaces
	) {
		this(
			name, description, deprecationNotice,
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(
					Scope.DEFAULT_SCOPE,
					unique == null ? AttributeUniquenessType.NOT_UNIQUE : unique
				)
			},
			filterable ? Scope.DEFAULT_SCOPES : NO_SCOPE,
			sortable ? Scope.DEFAULT_SCOPES : NO_SCOPE,
			localized, nullable, representative, type, defaultValue, indexedDecimalPlaces
		);
	}

	/**
	 * Creates a mutation that will set up a new attribute schema with the given properties. The conflict
	 * resolution override defaults to {@link ConflictResolutionOverride#INHERITED}.
	 *
	 * @param name                 unique name of the attribute
	 * @param description          optional human-readable description of the attribute
	 * @param deprecationNotice    optional deprecation notice if the attribute is deprecated
	 * @param uniqueInScopes       the scopes in which the attribute must be unique (may be `null`)
	 * @param filterableInScopes   the scopes in which the attribute is filterable (may be `null`)
	 * @param sortableInScopes     the scopes in which the attribute is sortable (may be `null`)
	 * @param localized            whether the attribute values are locale-specific
	 * @param nullable             whether the attribute value can be null
	 * @param representative       whether the attribute is representative for the entity
	 * @param type                 the data type stored in this attribute (must be a supported evitaDB
	 *                             type or its array)
	 * @param defaultValue         optional default value for the attribute
	 * @param indexedDecimalPlaces number of decimal places indexed for number-based attribute types
	 * @throws InvalidSchemaMutationException if the type is not allowed in attributes
	 */
	public CreateAttributeSchemaMutation(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable ScopedAttributeUniquenessType[] uniqueInScopes,
		@Nullable Scope[] filterableInScopes,
		@Nullable Scope[] sortableInScopes,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<? extends Serializable> type,
		@Nullable Serializable defaultValue,
		int indexedDecimalPlaces
	) {
		this(
			name, description, deprecationNotice,
			uniqueInScopes, filterableInScopes, null, sortableInScopes,
			localized, nullable, representative, type, defaultValue, indexedDecimalPlaces,
			ConflictResolutionOverride.INHERITED
		);
	}

	/**
	 * Creates a mutation that will set up a new attribute schema with the given properties, without any optional
	 * {@link io.evitadb.api.requestResponse.schema.FilterIndexCapability filter index capability}.
	 *
	 * This is the signature that was public before capabilities existed. It is kept as a delegating overload so that
	 * an integration compiled against it keeps both source and binary compatibility - the capability argument the
	 * canonical constructor gained is optional by design, and omitting it means "no acceleration", which is exactly
	 * what every schema written before this feature meant.
	 *
	 * @param name                       unique name of the attribute
	 * @param description                optional human-readable description of the attribute
	 * @param deprecationNotice          optional deprecation notice if the attribute is deprecated
	 * @param uniqueInScopes             the scopes in which the attribute must be unique (may be `null`)
	 * @param filterableInScopes         the scopes in which the attribute is filterable (may be `null`)
	 * @param sortableInScopes           the scopes in which the attribute is sortable (may be `null`)
	 * @param localized                  whether the attribute values are locale-specific
	 * @param nullable                   whether the attribute value can be null
	 * @param representative             whether the attribute is representative for the entity
	 * @param type                       the data type stored in this attribute (must be a supported evitaDB type or
	 *                                   its array)
	 * @param defaultValue               optional default value for the attribute
	 * @param indexedDecimalPlaces       number of decimal places indexed for number-based attribute types
	 * @param conflictResolutionOverride the per-item override of the conflict resolution granularity
	 * @throws InvalidSchemaMutationException if the type is not allowed in attributes
	 */
	public CreateAttributeSchemaMutation(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable ScopedAttributeUniquenessType[] uniqueInScopes,
		@Nullable Scope[] filterableInScopes,
		@Nullable Scope[] sortableInScopes,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<? extends Serializable> type,
		@Nullable Serializable defaultValue,
		int indexedDecimalPlaces,
		@Nonnull ConflictResolutionOverride conflictResolutionOverride
	) {
		this(
			name, description, deprecationNotice,
			uniqueInScopes, filterableInScopes, null, sortableInScopes,
			localized, nullable, representative, type, defaultValue, indexedDecimalPlaces,
			conflictResolutionOverride
		);
	}

	/**
	 * Creates a mutation that will set up a new attribute schema with the given properties.
	 *
	 * @param name                       unique name of the attribute
	 * @param description                optional human-readable description of the attribute
	 * @param deprecationNotice          optional deprecation notice if the attribute is deprecated
	 * @param uniqueInScopes             the scopes in which the attribute must be unique (may be `null`)
	 * @param filterableInScopes         the scopes in which the attribute is filterable (may be `null`)
	 * @param filterCapabilitiesInScopes the capability carriers the mutation transports (may be `null`)
	 * @param sortableInScopes           the scopes in which the attribute is sortable (may be `null`)
	 * @param localized                  whether the attribute values are locale-specific
	 * @param nullable                   whether the attribute value can be null
	 * @param representative             whether the attribute is representative for the entity
	 * @param type                       the data type stored in this attribute (must be a supported
	 *                                   evitaDB type or its array)
	 * @param defaultValue               optional default value for the attribute
	 * @param indexedDecimalPlaces       number of decimal places indexed for number-based attribute types
	 * @param conflictResolutionOverride the per-item override of the conflict resolution granularity
	 *                                   applied to this attribute (never `null`; use
	 *                                   {@link ConflictResolutionOverride#INHERITED} to follow the
	 *                                   resolved conflict resolution)
	 * @throws InvalidSchemaMutationException if the type is not allowed in attributes, if a carrier in
	 *                                         {@code filterCapabilitiesInScopes} names a scope outside
	 *                                         {@code filterableInScopes} (see
	 *                                         {@link #verifyCapabilityScopesAreFilterable}), or if a carried
	 *                                         capability does not apply to {@code type} (see
	 *                                         {@link #verifyCapabilitiesApplicableToType})
	 */
	@SerializableCreator
	public CreateAttributeSchemaMutation(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable ScopedAttributeUniquenessType[] uniqueInScopes,
		@Nullable Scope[] filterableInScopes,
		@Nullable ScopedFilterCapabilities[] filterCapabilitiesInScopes,
		@Nullable Scope[] sortableInScopes,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<? extends Serializable> type,
		@Nullable Serializable defaultValue,
		int indexedDecimalPlaces,
		@Nonnull ConflictResolutionOverride conflictResolutionOverride
	) {
		super(name);
		ClassifierUtils.validateClassifierFormat(ClassifierType.ATTRIBUTE, name);
		if (!EvitaDataTypes.isSupportedTypeOrItsArray(type)) {
			throw new InvalidSchemaMutationException("The type `" + type + "` is not allowed in attributes!");
		}
		this.description = description;
		this.deprecationNotice = deprecationNotice;
		this.uniqueInScopes = uniqueInScopes == null ?
			new ScopedAttributeUniquenessType[] { new ScopedAttributeUniquenessType(Scope.DEFAULT_SCOPE, AttributeUniquenessType.NOT_UNIQUE)} :
			uniqueInScopes;
		this.filterableInScopes = filterableInScopes == null ? NO_SCOPE : filterableInScopes;
		this.filterCapabilitiesInScopes = filterCapabilitiesInScopes == null ?
			ScopedFilterCapabilities.EMPTY : filterCapabilitiesInScopes;
		// a create mutation carries the attribute type itself, so both capability checks can run right here rather
		// than waiting for the schema - which is what closes the gap for a mutation assembled field by field over the
		// wire, where no set-filterable mutation follows to validate on its behalf
		verifyCapabilityScopesAreFilterable(this.name, this.filterableInScopes, this.filterCapabilitiesInScopes);
		verifyCapabilitiesApplicableToType(
			this.name, type, AttributeSchema.toFilterCapabilitiesEnumMap(this.filterCapabilitiesInScopes)
		);
		this.sortableInScopes = sortableInScopes == null ? NO_SCOPE : sortableInScopes;
		this.localized = localized;
		this.nullable = nullable;
		this.representative = representative;
		this.type = type;
		this.defaultValue = defaultValue;
		this.indexedDecimalPlaces = indexedDecimalPlaces;
		this.conflictResolutionOverride = conflictResolutionOverride;
	}

	@Nonnull
	public AttributeUniquenessType getUnique() {
		return Arrays.stream(this.uniqueInScopes)
			.filter(it -> it.scope() == Scope.DEFAULT_SCOPE)
			.findFirst()
			.map(ScopedAttributeUniquenessType::uniquenessType)
			.orElse(AttributeUniquenessType.NOT_UNIQUE);
	}

	public boolean isFilterable() {
		return !ArrayUtils.isEmptyOrItsValuesNull(this.filterableInScopes);
	}

	public boolean isSortable() {
		return !ArrayUtils.isEmptyOrItsValuesNull(this.sortableInScopes);
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		// when the attribute schema was removed before and added again, we may remove both operations
		// and leave only operations that reset the original settings do defaults
		if (existingMutation instanceof RemoveAttributeSchemaMutation removeAttributeSchema && Objects.equals(removeAttributeSchema.getName(), this.name)) {
			final EntityAttributeSchemaContract createdVersion = mutate(currentCatalogSchema, null, EntityAttributeSchemaContract.class);
			final EntityAttributeSchemaContract existingSchema = currentEntitySchema.getAttribute(this.name).orElseThrow();
			return new MutationCombinationResult<>(
				null,
				Stream.of(
						makeMutationIfDifferent(
							AttributeSchemaContract.class,
							createdVersion, existingSchema,
							NamedSchemaContract::getDescription,
							newValue -> new ModifyAttributeSchemaDescriptionMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							AttributeSchemaContract.class,
							createdVersion, existingSchema,
							NamedSchemaWithDeprecationContract::getDeprecationNotice,
							newValue -> new ModifyAttributeSchemaDeprecationNoticeMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							AttributeSchemaContract.class,
							createdVersion, existingSchema,
							AttributeSchemaContract::getType,
							newValue -> new ModifyAttributeSchemaTypeMutation(this.name, newValue, this.indexedDecimalPlaces)
						),
						makeMutationIfDifferent(
							AttributeSchemaContract.class,
							createdVersion, existingSchema,
							AttributeSchemaContract::getDefaultValue,
							newValue -> new ModifyAttributeSchemaDefaultValueMutation(this.name, this.defaultValue)
						),
						makeMutationIfDifferent(
							AttributeSchemaContract.class,
							createdVersion, existingSchema,
							// one carrier per filterable scope - the carriers encode both which scopes are filterable
							// and what each accelerates, so a capability-only change is a difference too
							schema -> Arrays.stream(Scope.values())
								.filter(schema::isFilterableInScope)
								.map(
									scope -> new ScopedFilterCapabilities(
										scope,
										schema.getFilterCapabilitiesInScope(scope)
											.toArray(FilterIndexCapability[]::new)
									)
								)
								.toArray(ScopedFilterCapabilities[]::new),
							newValue -> SetAttributeSchemaFilterableMutation.fromCapabilities(this.name, newValue)
						),
						makeMutationIfDifferent(
							AttributeSchemaContract.class,
							createdVersion, existingSchema,
							schema -> Arrays.stream(Scope.values())
								.map(scope -> new ScopedAttributeUniquenessType(scope, schema.getUniquenessType(scope)))
								// filter out default values
								.filter(it -> it.uniquenessType() != AttributeUniquenessType.NOT_UNIQUE)
								.toArray(ScopedAttributeUniquenessType[]::new),
							newValue -> new SetAttributeSchemaUniqueMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							AttributeSchemaContract.class,
							createdVersion, existingSchema,
							schema -> Arrays.stream(Scope.values())
								.filter(schema::isSortableInScope)
								.toArray(Scope[]::new),
							newValue -> new SetAttributeSchemaSortableMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							AttributeSchemaContract.class,
							createdVersion, existingSchema,
							AttributeSchemaContract::isLocalized,
							newValue -> new SetAttributeSchemaLocalizedMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							AttributeSchemaContract.class,
							createdVersion, existingSchema,
							AttributeSchemaContract::isNullable,
							newValue -> new SetAttributeSchemaNullableMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							AttributeSchemaContract.class,
							createdVersion, existingSchema,
							AttributeSchemaContract::isRepresentative,
							newValue -> new SetAttributeSchemaRepresentativeMutation(this.name, newValue)
						),
						makeMutationIfDifferent(
							AttributeSchemaContract.class,
							createdVersion, existingSchema,
							AttributeSchemaContract::getConflictResolutionOverride,
							newValue -> new SetAttributeSchemaConflictResolutionOverrideMutation(this.name, newValue)
						)
					)
					.filter(Objects::nonNull)
					.toArray(LocalEntitySchemaMutation[]::new)
			);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public <S extends AttributeSchemaContract> S mutate(@Nullable CatalogSchemaContract catalogSchema, @Nullable S attributeSchema, @Nonnull Class<S> schemaType) {
		if (EntityAttributeSchemaContract.class.isAssignableFrom(schemaType)) {
			//noinspection unchecked,rawtypes
			return (S) EntityAttributeSchema._internalBuild(
				this.name, this.description, this.deprecationNotice,
				this.uniqueInScopes, this.filterableInScopes, this.filterCapabilitiesInScopes, this.sortableInScopes,
				this.localized, this.nullable, this.representative,
				(Class) this.type, this.defaultValue,
				this.indexedDecimalPlaces,
				this.conflictResolutionOverride
			);
		} else if (AttributeSchemaContract.class.isAssignableFrom(schemaType)) {
			//noinspection unchecked,rawtypes
			return (S) AttributeSchema._internalBuild(
				this.name, this.description, this.deprecationNotice,
				this.uniqueInScopes, this.filterableInScopes, this.filterCapabilitiesInScopes, this.sortableInScopes,
				this.localized, this.nullable, this.representative,
				(Class) this.type, this.defaultValue,
				this.indexedDecimalPlaces,
				this.conflictResolutionOverride
			);
		} else {
			throw new InvalidSchemaMutationException("Unsupported schema type: " + schemaType);
		}
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nullable EntitySchemaContract entitySchema) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final EntityAttributeSchemaContract newAttributeSchema = mutate(catalogSchema, null, EntityAttributeSchemaContract.class);
		final EntityAttributeSchemaContract existingAttributeSchema = entitySchema.getAttribute(this.name).orElse(null);
		if (existingAttributeSchema == null) {
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
				Stream.concat(
						entitySchema.getAttributes().values().stream(),
						Stream.of(newAttributeSchema)
					)
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
		} else if (existingAttributeSchema.equals(newAttributeSchema)) {
			// the mutation must have been applied previously - return the schema we don't need to alter
			return entitySchema;
		} else {
			// ups, there is conflict in attribute settings
			throw new InvalidSchemaMutationException(
				"The attribute `" + this.name + "` already exists in entity `" + entitySchema.getName() + "` schema and" +
					" it has different definition. To alter existing attribute schema you need to use different mutations."
			);
		}
	}

	@Nullable
	@Override
	public ReferenceSchemaContract mutate(@Nonnull EntitySchemaContract entitySchemaContract, @Nullable ReferenceSchemaContract referenceSchema, @Nonnull ConsistencyChecks consistencyChecks) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		// the constructor already refused a capability on the wrong type or in a non-filterable scope, but only here
		// does the mutation learn it is targeting a reference - the same mutation class serves both locations
		verifyCapabilityNotOnReferenceAttribute(
			this.name, referenceSchema.getName(), entitySchemaContract.getName(), this.filterCapabilitiesInScopes
		);
		@SuppressWarnings({"unchecked", "rawtypes"}) final AttributeSchema newAttributeSchema = AttributeSchema._internalBuild(
			this.name, this.description, this.deprecationNotice,
			this.uniqueInScopes, this.filterableInScopes, this.filterCapabilitiesInScopes, this.sortableInScopes,
			this.localized, this.nullable, this.representative,
			(Class) this.type, this.defaultValue,
			this.indexedDecimalPlaces,
			this.conflictResolutionOverride
		);
		final Optional<AttributeSchemaContract> existingAttributeSchema = getReferenceAttributeSchema(referenceSchema, this.name);
		if (existingAttributeSchema.isEmpty()) {
			if (referenceSchema instanceof ReflectedReferenceSchema reflectedReferenceSchema) {
				return reflectedReferenceSchema
					.withDeclaredAttributes(
						Stream.concat(
								reflectedReferenceSchema.getDeclaredAttributes().values().stream(),
								Stream.of(newAttributeSchema)
							)
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
					referenceSchema.isReferencedEntityTypeManaged() ? Collections.emptyMap() : referenceSchema.getEntityTypeNameVariants(s -> null),
					referenceSchema.isReferencedEntityTypeManaged(),
					referenceSchema.getReferencedGroupType(),
					referenceSchema.isReferencedGroupTypeManaged() ? Collections.emptyMap() : referenceSchema.getGroupTypeNameVariants(s -> null),
					referenceSchema.isReferencedGroupTypeManaged(),
					referenceSchema.getReferenceIndexTypeInScopes(),
					referenceSchema.getIndexedComponentsInScopes(),
					referenceSchema.getFacetedInScopes(),
					referenceSchema.getFacetedPartiallyInScopes(),
					referenceSchema.getAllHistogramIndexDefinitions(),
					referenceSchema.getBucketedPartiallyInScopes(),
					Stream.concat(
							referenceSchema.getAttributes().values().stream(),
							Stream.of(newAttributeSchema)
						)
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
		} else if (existingAttributeSchema.get().equals(newAttributeSchema)) {
			// the mutation must have been applied previously - return the schema we don't need to alter
			return referenceSchema;
		} else {
			// ups, there is conflict in attribute settings
			throw new InvalidSchemaMutationException(
				"The attribute `" + this.name + "` already exists in entity `" + entitySchemaContract.getName() + "`" +
					" reference `" + referenceSchema.getName() + "` schema and" +
					" it has different definition. To alter existing attribute schema you need to use different mutations."
			);
		}
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return "Create attribute schema: " +
			"name='" + this.name + '\'' +
			", description='" + this.description + '\'' +
			", deprecationNotice='" + this.deprecationNotice + '\'' +
			", unique=(" + join(this.uniqueInScopes) + ")" +
			", filterable=" + (isFilterable() ? "(in scopes: " + Arrays.toString(this.filterableInScopes) + ")" : "no") +
			(this.filterCapabilitiesInScopes.length == 0 ?
				"" : ", capabilities=(" + join(this.filterCapabilitiesInScopes) + ")") +
			", sortable=" + (isSortable() ? "(in scopes: " + Arrays.toString(this.sortableInScopes) + ")" : "no") +
			", localized=" + this.localized +
			", nullable=" + this.nullable +
			", representative=" + this.representative +
			", type=" + this.type +
			", defaultValue=" + this.defaultValue +
			", indexedDecimalPlaces=" + this.indexedDecimalPlaces +
			", conflictResolutionOverride=" + this.conflictResolutionOverride;
	}

}
