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

package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.SchemaAlteringException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.requestResponse.schema.dto.EntitySchema._internalGenerateNameVariantIndex;
import static io.evitadb.api.requestResponse.schema.dto.EntitySchema.toReferenceAttributeSchema;
import static io.evitadb.api.requestResponse.schema.dto.EntitySchema.toSortableAttributeCompoundSchema;
import static java.util.Optional.ofNullable;

/**
 * Internal implementation of {@link ReferenceSchemaContract}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @see ReferenceSchemaContract
 */
@Immutable
@ThreadSafe
public sealed class ReferenceSchema implements ReferenceSchemaContract permits ReflectedReferenceSchema {
	@Serial private static final long serialVersionUID = 6899584103779653340L;
	@Getter @Nonnull protected final String name;
	@Getter @Nonnull protected final Cardinality cardinality;
	@Getter @Nullable protected final String deprecationNotice;
	@Getter @Nullable protected final String description;
	protected final Map<Scope, ReferenceIndexType> indexedInScopes;
	@Getter protected final Set<Scope> facetedInScopes;
	@Getter @Nonnull protected final Map<NamingConvention, String> nameVariants;
	@Getter @Nonnull protected final String referencedEntityType;
	@Nonnull protected final Map<NamingConvention, String> entityTypeNameVariants;
	@Getter protected final boolean referencedEntityTypeManaged;
	@Getter @Nullable protected final String referencedGroupType;
	@Nonnull protected final Map<NamingConvention, String> groupTypeNameVariants;
	@Getter protected final boolean referencedGroupTypeManaged;
	/**
	 * Contains index of all {@link SortableAttributeCompoundSchema} that could be used as sortable attribute compounds
	 * of reference of this type.
	 */
	@Nonnull private final Map<String, SortableAttributeCompoundSchema> sortableAttributeCompounds;
	/**
	 * Index of attribute names that allows to quickly lookup sortable attribute compound schemas by name in specific
	 * naming convention. Key is the name in specific name convention, value is array of size {@link NamingConvention#values()}
	 * where reference to {@link SortableAttributeCompoundSchema} is placed on index of naming convention that matches
	 * the key.
	 */
	private final Map<String, SortableAttributeCompoundSchema[]> sortableAttributeCompoundNameIndex;

	/**
	 * Contains index of all {@link AttributeSchema} that could be used as attributes of entity of this type.
	 */
	@Nonnull private final Map<String, AttributeSchema> attributes;
	/**
	 * Index of attribute names that allows to quickly lookup attribute schemas by attribute name in specific naming
	 * convention. Key is the name in specific name convention, value is array of size {@link NamingConvention#values()}
	 * where reference to {@link AttributeSchema} is placed on index of naming convention that matches the key.
	 */
	@Nonnull private final Map<String, AttributeSchema[]> attributeNameIndex;
	/**
	 * Contains all definitions of the attributes that contain default value.
	 */
	@Getter @Nonnull private final Map<String, AttributeSchema> nonNullableOrDefaultValueAttributes;
	/**
	 * Index contains collections of sortable attribute compounds that reference the attribute with the name equal
	 * to a key of this index.
	 */
	@Nonnull private final Map<String, Collection<SortableAttributeCompoundSchemaContract>> attributeToSortableAttributeCompoundIndex;

	/**
	 * Converts an array of ScopedReferenceIndexType objects into a Map linking Scope to ReferenceIndexType.
	 * If the input array is null, it initializes the map with a default value of Scope.DEFAULT_SCOPE mapped to ReferenceIndexType.NONE.
	 *
	 * @param indexedInScopes An array of ScopedReferenceIndexType to be converted. Can be null.
	 * @return A Map where each Scope is associated with its corresponding ReferenceIndexType.
	 */
	@Nonnull
	public static Map<Scope, ReferenceIndexType> toReferenceIndexEnumMap(@Nullable ScopedReferenceIndexType[] indexedInScopes) {
		final Map<Scope, ReferenceIndexType> theIndexType = new EnumMap<>(Scope.class);
		if (indexedInScopes != null) {
			for (ScopedReferenceIndexType indexedInScope : indexedInScopes) {
				theIndexType.put(indexedInScope.scope(), indexedInScope.indexType());
			}
		}
		return theIndexType;
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of ReferenceSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static ReferenceSchema _internalBuild(
		@Nonnull String name,
		@Nonnull String entityType,
		boolean referencedEntityTypeManaged,
		@Nonnull Cardinality cardinality,
		@Nullable String groupType,
		boolean referencedGroupTypeManaged,
		@Nullable ScopedReferenceIndexType[] indexedInScopes,
		@Nullable Scope[] facetedInScopes
	) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, entityType);
		if (groupType != null) {
			ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, groupType);
		}

		final Map<Scope, ReferenceIndexType> indexedScopesMap = toReferenceIndexEnumMap(indexedInScopes);
		final EnumSet<Scope> facetedScopes = ArrayUtils.toEnumSet(Scope.class, facetedInScopes);
		validateScopeSettings(facetedScopes, indexedScopesMap);

		return new ReferenceSchema(
			name, NamingConvention.generate(name),
			null, null, cardinality,
			entityType,
			referencedEntityTypeManaged ? Collections.emptyMap() : NamingConvention.generate(entityType),
			referencedEntityTypeManaged,
			groupType,
			groupType != null && groupType.isBlank() && !referencedGroupTypeManaged ?
				NamingConvention.generate(groupType) : Collections.emptyMap(),
			referencedGroupTypeManaged,
			indexedScopesMap,
			facetedScopes,
			Collections.emptyMap(),
			Collections.emptyMap()
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of ReferenceSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static ReferenceSchema _internalBuild(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nonnull String entityType,
		boolean referencedEntityTypeManaged,
		@Nonnull Cardinality cardinality,
		@Nullable String groupType,
		boolean referencedGroupTypeManaged,
		@Nonnull ScopedReferenceIndexType[] indexedInScopes,
		@Nonnull Scope[] facetedInScopes,
		@Nonnull Map<String, AttributeSchemaContract> attributes,
		@Nonnull Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds
	) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, entityType);
		if (groupType != null) {
			ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, groupType);
		}

		final Map<Scope, ReferenceIndexType> indexedScopesMap = toReferenceIndexEnumMap(indexedInScopes);
		final EnumSet<Scope> facetedScopes = ArrayUtils.toEnumSet(Scope.class, facetedInScopes);
		validateScopeSettings(facetedScopes, indexedScopesMap);

		return new ReferenceSchema(
			name, NamingConvention.generate(name),
			description, deprecationNotice, cardinality,
			entityType,
			referencedEntityTypeManaged ? Collections.emptyMap() : NamingConvention.generate(entityType),
			referencedEntityTypeManaged,
			groupType,
			groupType != null && groupType.isBlank() && !referencedGroupTypeManaged ?
				NamingConvention.generate(groupType) : Collections.emptyMap(),
			referencedGroupTypeManaged,
			indexedScopesMap,
			facetedScopes,
			attributes,
			sortableAttributeCompounds
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of ReferenceSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static ReferenceSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Cardinality cardinality,
		@Nonnull String referencedEntityType,
		@Nonnull Map<NamingConvention, String> entityTypeNameVariants,
		boolean referencedEntityTypeManaged,
		@Nullable String referencedGroupType,
		@Nonnull Map<NamingConvention, String> groupTypeNameVariants,
		boolean referencedGroupTypeManaged,
		@Nonnull Map<Scope, ReferenceIndexType> indexedInScopes,
		@Nonnull Set<Scope> facetedInScopes,
		@Nonnull Map<String, AttributeSchemaContract> attributes,
		@Nonnull Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds
	) {
		return new ReferenceSchema(
			name, nameVariants,
			description, deprecationNotice, cardinality,
			referencedEntityType,
			entityTypeNameVariants,
			referencedEntityTypeManaged,
			referencedGroupType,
			ofNullable(groupTypeNameVariants).orElse(Collections.emptyMap()),
			referencedGroupTypeManaged,
			indexedInScopes,
			facetedInScopes,
			attributes,
			sortableAttributeCompounds
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of ReferenceSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static ReferenceSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nonnull String entityType,
		@Nonnull Map<NamingConvention, String> entityTypeNameVariants,
		boolean referencedEntityTypeManaged,
		@Nonnull Cardinality cardinality,
		@Nullable String groupType,
		@Nullable Map<NamingConvention, String> groupTypeNameVariants,
		boolean referencedGroupTypeManaged,
		@Nonnull ScopedReferenceIndexType[] indexedInScopes,
		@Nonnull Scope[] facetedInScopes,
		@Nonnull Map<String, AttributeSchemaContract> attributes,
		@Nonnull Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds
	) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, entityType);
		if (groupType != null) {
			ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, groupType);
		}

		final Map<Scope, ReferenceIndexType> indexedScopesMap = toReferenceIndexEnumMap(indexedInScopes);
		final EnumSet<Scope> facetedScopes = ArrayUtils.toEnumSet(Scope.class, facetedInScopes);
		validateScopeSettings(facetedScopes, indexedScopesMap);

		return new ReferenceSchema(
			name, nameVariants,
			description, deprecationNotice, cardinality,
			entityType,
			entityTypeNameVariants,
			referencedEntityTypeManaged,
			groupType,
			ofNullable(groupTypeNameVariants).orElse(Collections.emptyMap()),
			referencedGroupTypeManaged,
			indexedScopesMap,
			facetedScopes,
			attributes,
			sortableAttributeCompounds
		);
	}

	/**
	 * Validates the consistency between the sets of faceted and indexed scopes.
	 * Ensures that any scope marked as faceted is also marked as indexed.
	 *
	 * @param facetedScopes the set of scopes where faceting is enabled; must not be null
	 * @param indexedScopes the set of scopes where indexing is enabled; must not be null
	 */
	static void validateScopeSettings(
		@Nonnull Set<Scope> facetedScopes,
		@Nonnull Map<Scope, ReferenceIndexType> indexedScopes
	) {
		final Scope[] scopes = Scope.values();
		for (Scope scope : scopes) {
			if (facetedScopes.contains(scope)) {
				Assert.isTrue(
					indexedScopes.get(scope) != ReferenceIndexType.NONE,
					() -> new InvalidSchemaMutationException(
						"When reference is marked as faceted in scope `" + scope + "`, it needs also to be indexed for the same scope."
					)
				);
			}
		}
	}

	protected ReferenceSchema(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Cardinality cardinality,
		@Nonnull String referencedEntityType,
		@Nonnull Map<NamingConvention, String> entityTypeNameVariants,
		boolean referencedEntityTypeManaged,
		@Nullable String referencedGroupType,
		@Nonnull Map<NamingConvention, String> groupTypeNameVariants,
		boolean referencedGroupTypeManaged,
		@Nonnull Map<Scope, ReferenceIndexType> indexedInScopes,
		@Nonnull Set<Scope> facetedInScopes,
		@Nonnull Map<String, AttributeSchemaContract> attributes,
		@Nonnull Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds
	) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, referencedEntityType);
		this.name = name;
		this.nameVariants = Collections.unmodifiableMap(nameVariants);
		this.description = description;
		this.deprecationNotice = deprecationNotice;
		this.cardinality = cardinality == null ? Cardinality.ZERO_OR_MORE : cardinality;
		this.referencedEntityType = referencedEntityType;
		this.entityTypeNameVariants = Collections.unmodifiableMap(entityTypeNameVariants);
		this.referencedEntityTypeManaged = referencedEntityTypeManaged;
		this.referencedGroupType = referencedGroupType;
		this.groupTypeNameVariants = Collections.unmodifiableMap(groupTypeNameVariants);
		this.referencedGroupTypeManaged = referencedGroupTypeManaged;
		this.indexedInScopes = CollectionUtils.toUnmodifiableMap(indexedInScopes);
		this.facetedInScopes = CollectionUtils.toUnmodifiableSet(facetedInScopes);
		this.attributes = Collections.unmodifiableMap(
			attributes.entrySet()
				.stream()
				.collect(
					Collectors.toMap(
						Entry::getKey,
						it -> toReferenceAttributeSchema(it.getValue())
					)
				)
		);
		this.attributeNameIndex = _internalGenerateNameVariantIndex(
			this.attributes.values(), AttributeSchemaContract::getNameVariants
		);
		this.nonNullableOrDefaultValueAttributes = this.attributes
			.values()
			.stream()
			.filter(it -> !it.isNullable() || it.getDefaultValue() != null)
			.collect(
				Collectors.toMap(
					AttributeSchema::getName,
					Function.identity()
				)
			);
		this.sortableAttributeCompounds = Collections.unmodifiableMap(
			sortableAttributeCompounds.entrySet()
				.stream()
				.collect(
					Collectors.toMap(
						Entry::getKey,
						it -> toSortableAttributeCompoundSchema(it.getValue())
					)
				)
		);
		this.sortableAttributeCompoundNameIndex = _internalGenerateNameVariantIndex(
			this.sortableAttributeCompounds.values(), SortableAttributeCompoundSchemaContract::getNameVariants
		);
		this.attributeToSortableAttributeCompoundIndex = this.sortableAttributeCompounds
			.values()
			.stream()
			.flatMap(it -> it.getAttributeElements().stream().map(attribute -> new AttributeToCompound(attribute, it)))
			.collect(
				Collectors.groupingBy(
					rec -> rec.attribute().attributeName(),
					Collectors.mapping(
						AttributeToCompound::compoundSchema,
						Collectors.toCollection(ArrayList::new)
					)
				)
			);
	}

	@Override
	@Nonnull
	public String getNameVariant(@Nonnull NamingConvention namingConvention) {
		return this.nameVariants.get(namingConvention);
	}

	@Override
	@Nonnull
	public String getReferencedEntityTypeNameVariant(@Nonnull NamingConvention namingConvention, @Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher) {
		return this.referencedEntityTypeManaged ?
			Objects.requireNonNull(entitySchemaFetcher.apply(this.referencedEntityType)).getNameVariant(namingConvention) :
			this.entityTypeNameVariants.get(namingConvention);
	}

	@Override
	@Nonnull
	public String getReferencedGroupTypeNameVariant(@Nonnull NamingConvention namingConvention, @Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher) {
		return this.referencedGroupTypeManaged ?
			Objects.requireNonNull(entitySchemaFetcher.apply(this.referencedGroupType)).getNameVariant(namingConvention) :
			this.groupTypeNameVariants.get(namingConvention);
	}

	@Nonnull
	@Override
	public Map<NamingConvention, String> getEntityTypeNameVariants(@Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher) {
		return this.referencedEntityTypeManaged ?
			Objects.requireNonNull(entitySchemaFetcher.apply(this.referencedEntityType)).getNameVariants() :
			this.entityTypeNameVariants;
	}

	@Nonnull
	@Override
	public Map<NamingConvention, String> getGroupTypeNameVariants(@Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher) {
		return this.referencedGroupTypeManaged ?
			Objects.requireNonNull(entitySchemaFetcher.apply(this.referencedGroupType)).getNameVariants() :
			this.groupTypeNameVariants;
	}

	@Override
	public boolean isIndexedInScope(@Nonnull Scope scope) {
		return this.indexedInScopes.containsKey(scope) && this.indexedInScopes.get(scope) != ReferenceIndexType.NONE;
	}

	@Nonnull
	@Override
	public Set<Scope> getIndexedInScopes() {
		return this.indexedInScopes.entrySet()
			.stream()
			.filter(entry -> entry.getValue() != ReferenceIndexType.NONE)
			.map(Map.Entry::getKey)
			.collect(Collectors.toSet());
	}

	@Nonnull
	@Override
	public ReferenceIndexType getReferenceIndexType(@Nonnull Scope scope) {
		return this.indexedInScopes.getOrDefault(scope, ReferenceIndexType.NONE);
	}

	@Nonnull
	@Override
	public Map<Scope, ReferenceIndexType> getReferenceIndexTypeInScopes() {
		return this.indexedInScopes;
	}

	@Override
	public boolean isFacetedInScope(@Nonnull Scope scope) {
		return this.facetedInScopes.contains(scope);
	}

	@Override
	public void validate(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchema entitySchema) throws SchemaAlteringException {
		final Optional<EntitySchemaContract> referencedEntityTypeSchema = catalogSchema.getEntitySchema(this.referencedEntityType);
		Stream<String> referenceErrors = Stream.empty();
		if (this.referencedEntityTypeManaged && referencedEntityTypeSchema.isEmpty()) {
			referenceErrors = Stream.concat(
				referenceErrors,
				Stream.of("Referenced entity type `" + this.referencedEntityType + "` is not present in catalog `" + catalogSchema.getName() + "` schema!"));
		} else if (!this.referencedEntityTypeManaged && referencedEntityTypeSchema.isPresent()) {
			referenceErrors = Stream.concat(
				referenceErrors,
				Stream.of("Referenced entity type `" + this.referencedEntityType + "` is present in catalog `" + catalogSchema.getName() + "` schema, but it's marked as not managed!"));
		}
		if (this.referencedGroupTypeManaged) {
			if (this.referencedGroupType == null) {
				referenceErrors = Stream.concat(
					referenceErrors,
					Stream.of("Referenced group entity is not defined even though it's declared as managed!"));
			} else if (catalogSchema.getEntitySchema(this.referencedGroupType).isEmpty()) {
				referenceErrors = Stream.concat(
					referenceErrors,
					Stream.of("Referenced group entity type `" + this.referencedGroupType + "` is not present in catalog `" + catalogSchema.getName() + "` schema!"));
			}
		} else if (this.referencedGroupType != null) {
			if (catalogSchema.getEntitySchema(this.referencedGroupType).isPresent()) {
				referenceErrors = Stream.concat(
					referenceErrors,
					Stream.of("Referenced group entity type `" + this.referencedGroupType + "` is present in catalog `" + catalogSchema.getName() + "` schema, but it's marked as not managed!"));
			}
		}

		referenceErrors = Stream.concat(
			referenceErrors,
			validateAttributes(this.getAttributes())
		);

		final List<String> errors = referenceErrors.map(it -> "\t" + it).toList();
		if (!errors.isEmpty()) {
			throw new InvalidSchemaMutationException(
				"Reference schema `" + this.name + "` contains validation errors:\n" + String.join("\n", errors)
			);
		}
	}

	@Nonnull
	@Override
	public Map<String, AttributeSchemaContract> getAttributes() {
		// we need EntitySchema to provide access to internal representations - i.e. whoever has
		// reference to EntitySchema should have access to other internal schema representations as well
		// unfortunately, the Generics in Java is just stupid, and we cannot provide subtype at the place of supertype
		// collection, so we have to work around that issue using generics stripping
		//noinspection unchecked,rawtypes
		return (Map) this.attributes;
	}

	@Nonnull
	@Override
	public Optional<AttributeSchemaContract> getAttribute(@Nonnull String attributeName) {
		return ofNullable(this.attributes.get(attributeName));
	}

	@Nonnull
	@Override
	public Optional<AttributeSchemaContract> getAttributeByName(@Nonnull String attributeName, @Nonnull NamingConvention namingConvention) {
		return ofNullable(this.attributeNameIndex.get(attributeName))
			.map(it -> it[namingConvention.ordinal()]);
	}

	@Nonnull
	@Override
	public Map<String, SortableAttributeCompoundSchemaContract> getSortableAttributeCompounds() {
		// we need EntitySchema to provide access to internal representations - i.e. whoever has
		// reference to EntitySchema should have access to other internal schema representations as well
		// unfortunately, the Generics in Java is just stupid, and we cannot provide subtype at the place of supertype
		// collection, so we have to work around that issue using generics stripping
		//noinspection unchecked,rawtypes
		return (Map) this.sortableAttributeCompounds;
	}

	@Nonnull
	@Override
	public Optional<SortableAttributeCompoundSchemaContract> getSortableAttributeCompound(@Nonnull String name) {
		return ofNullable(this.sortableAttributeCompounds.get(name));
	}

	@Nonnull
	@Override
	public Optional<SortableAttributeCompoundSchemaContract> getSortableAttributeCompoundByName(@Nonnull String name, @Nonnull NamingConvention namingConvention) {
		return ofNullable(this.sortableAttributeCompoundNameIndex.get(name))
			.map(it -> it[namingConvention.ordinal()]);
	}

	@Nonnull
	@Override
	public Collection<SortableAttributeCompoundSchemaContract> getSortableAttributeCompoundsForAttribute(@Nonnull String attributeName) {
		return ofNullable(this.attributeToSortableAttributeCompoundIndex.get(attributeName))
			.orElse(Collections.emptyList());
	}

	/**
	 * Updates the referenced entity type for managed entity types, but leaves all other properties unchanged.
	 *
	 * @param newReferencedEntityType the new referenced entity type, must not be null
	 * @return a new ReferenceSchema with the updated referenced entity type, never null
	 * @throws EvitaInternalError if the referenced entity type is not managed
	 */
	@Nonnull
	public ReferenceSchemaContract withUpdatedReferencedEntityType(@Nonnull String newReferencedEntityType) {
		Assert.isPremiseValid(
			this.referencedEntityTypeManaged,
			"The new referenced entity type can be changed only for managed entity types!"
		);
		return new ReferenceSchema(
			this.name,
			this.nameVariants,
			this.description,
			this.deprecationNotice,
			this.cardinality,
			newReferencedEntityType,
			// is always empty for managed types
			Map.of(),
			true,
			this.referencedGroupType,
			this.groupTypeNameVariants,
			this.referencedGroupTypeManaged,
			this.indexedInScopes,
			this.facetedInScopes,
			this.getAttributes(),
			this.getSortableAttributeCompounds()
		);
	}

	/**
	 * Updates the referenced group type for managed group types, but leaves all other properties unchanged.
	 *
	 * @param newReferencedGroupType the new referenced group type, must not be null
	 * @return a new ReferenceSchema with the updated referenced group type, never null
	 * @throws EvitaInternalError if the referenced group type is not managed
	 */
	@Nonnull
	public ReferenceSchemaContract withUpdatedReferencedGroupType(@Nonnull String newReferencedGroupType) {
		Assert.isPremiseValid(
			this.referencedGroupTypeManaged,
			"The new referenced entity group type can be changed only for managed entity types!"
		);
		return new ReferenceSchema(
			this.name,
			this.nameVariants,
			this.description,
			this.deprecationNotice,
			this.cardinality,
			this.referencedEntityType,
			this.entityTypeNameVariants,
			this.referencedEntityTypeManaged,
			newReferencedGroupType,
			// is always empty for managed types
			Map.of(),
			true,
			this.indexedInScopes,
			this.facetedInScopes,
			this.getAttributes(),
			this.getSortableAttributeCompounds()
		);
	}

	@Override
	public int hashCode() {
		int result = this.name.hashCode();
		result = 31 * result + this.nameVariants.hashCode();
		result = 31 * result + Objects.hashCode(this.description);
		result = 31 * result + Objects.hashCode(this.deprecationNotice);
		result = 31 * result + Objects.hashCode(this.cardinality);
		result = 31 * result + this.referencedEntityType.hashCode();
		result = 31 * result + this.entityTypeNameVariants.hashCode();
		result = 31 * result + Boolean.hashCode(this.referencedEntityTypeManaged);
		result = 31 * result + Objects.hashCode(this.referencedGroupType);
		result = 31 * result + this.groupTypeNameVariants.hashCode();
		result = 31 * result + Boolean.hashCode(this.referencedGroupTypeManaged);
		result = 31 * result + this.indexedInScopes.hashCode();
		result = 31 * result + this.facetedInScopes.hashCode();
		result = 31 * result + this.sortableAttributeCompounds.hashCode();
		result = 31 * result + this.attributes.hashCode();
		return result;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		ReferenceSchema that = (ReferenceSchema) o;
		return this.referencedEntityTypeManaged == that.referencedEntityTypeManaged &&
			this.referencedGroupTypeManaged == that.referencedGroupTypeManaged &&
			this.indexedInScopes.equals(that.indexedInScopes) &&
			this.facetedInScopes.equals(that.facetedInScopes) &&
			this.name.equals(that.name) &&
			this.nameVariants.equals(that.nameVariants) &&
			Objects.equals(this.description, that.description) &&
			Objects.equals(this.deprecationNotice, that.deprecationNotice) &&
			this.cardinality == that.cardinality &&
			this.referencedEntityType.equals(that.referencedEntityType) &&
			this.entityTypeNameVariants.equals(that.entityTypeNameVariants) &&
			Objects.equals(this.referencedGroupType, that.referencedGroupType) &&
			this.groupTypeNameVariants.equals(that.groupTypeNameVariants) &&
			this.sortableAttributeCompounds.equals(that.sortableAttributeCompounds) &&
			this.attributes.equals(that.attributes);
	}

	/**
	 * Collects errors for reference attributes.
	 *
	 * @param attributes a map of attribute schemas
	 * @return returns errors for reference attribute schemas as a stream
	 */
	@Nonnull
	protected Stream<String> validateAttributes(@Nonnull Map<String, AttributeSchemaContract> attributes) {
		Stream<String> attributeErrors = Stream.empty();
		for (Scope scope : Scope.values()) {
			if (!this.isIndexedInScope(scope)) {
				for (AttributeSchemaContract attribute : attributes.values()) {
					if (attribute.isFilterableInScope(scope)) {
						attributeErrors = Stream.concat(
							attributeErrors,
							Stream.of("Attribute `" + attribute.getName() + "` of reference schema `" + this.name + "` is filterable but reference schema is not indexed!")
						);
					}
					if (attribute.isSortableInScope(scope)) {
						attributeErrors = Stream.concat(
							attributeErrors,
							Stream.of("Attribute `" + attribute.getName() + "` of reference schema `" + this.name + "` is sortable but reference schema is not indexed!")
						);
					}
					if (attribute.isUniqueInScope(scope)) {
						attributeErrors = Stream.concat(
							attributeErrors,
							Stream.of("Attribute `" + attribute.getName() + "` of reference schema `" + this.name + "` is unique but reference schema is not indexed!")
						);
					}
				}
			}
		}
		return attributeErrors;
	}

	/**
	 * Helper DTO to envelope relation between {@link AttributeElement} and {@link SortableAttributeCompoundSchemaContract}.
	 *
	 * @param attribute      {@link SortableAttributeCompoundSchemaContract#getAttributeElements()} item
	 * @param compoundSchema {@link SortableAttributeCompoundSchemaContract} enveloping compound
	 */
	private record AttributeToCompound(
		@Nonnull AttributeElement attribute,
		@Nonnull SortableAttributeCompoundSchema compoundSchema
	) {
	}

}
