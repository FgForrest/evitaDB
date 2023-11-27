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

package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ComparatorUtils;
import io.evitadb.utils.NamingConvention;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.lang.reflect.Array;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * Internal implementation of {@link EntitySchemaContract}.
 */
@Immutable
@ThreadSafe
@EqualsAndHashCode(of = {"version", "name"})
public final class EntitySchema implements EntitySchemaContract {
	@Serial private static final long serialVersionUID = -209500573660545111L;

	private final int version;
	@Getter @Nonnull private final String name;
	@Getter @Nonnull private final Map<NamingConvention, String> nameVariants;
	@Getter @Nullable private final String description;
	@Getter @Nullable private final String deprecationNotice;
	@Getter private final boolean withGeneratedPrimaryKey;
	@Getter private final boolean withHierarchy;
	@Getter private final boolean withPrice;
	@Getter private final int indexedPricePlaces;
	@Getter @Nonnull private final Set<Locale> locales;
	@Getter @Nonnull private final Set<Currency> currencies;

	/**
	 * Contains index of all {@link SortableAttributeCompoundSchema} that could be used as sortable attribute compounds
	 * of entity of this type.
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
	private final Map<String, EntityAttributeSchemaContract> attributes;
	/**
	 * Index of attribute names that allows to quickly lookup attribute schemas by attribute name in specific naming
	 * convention. Key is the name in specific name convention, value is array of size {@link NamingConvention#values()}
	 * where reference to {@link AttributeSchema} is placed on index of naming convention that matches the key.
	 */
	private final Map<String, EntityAttributeSchemaContract[]> attributeNameIndex;
	/**
	 * Contains index of all {@link AssociatedDataSchema} that could be used as associated data of entity of this type.
	 */
	private final Map<String, AssociatedDataSchema> associatedData;
	/**
	 * Index of associated data names that allows to quickly lookup attribute schemas by associated data name in
	 * specific naming convention. Key is the name in specific name convention, value is array of size
	 * {@link NamingConvention#values()} where reference to {@link AssociatedDataSchema} is placed on index of naming
	 * convention that matches the key.
	 */
	private final Map<String, AssociatedDataSchema[]> associatedDataNameIndex;
	/**
	 * Contains index of all {@link ReferenceSchema} that could be used as references of entity of this type.
	 */
	private final Map<String, ReferenceSchema> references;
	/**
	 * Index of associated data names that allows to quickly lookup reference schemas by reference data name in
	 * specific naming convention. Key is the name in specific name convention, value is array of size
	 * {@link NamingConvention#values()} where reference to {@link ReferenceSchema} is placed on index of naming
	 * convention that matches the key.
	 */
	private final Map<String, ReferenceSchema[]> referenceNameIndex;
	/**
	 * Contains allowed evolution modes for the entity schema.
	 */
	@Getter private final Set<EvolutionMode> evolutionMode;
	/**
	 * Contains all definitions of the attributes that return false in method {@link AttributeSchema#isNullable()}.
	 */
	@Getter private final Collection<EntityAttributeSchemaContract> nonNullableAttributes;
	/**
	 * Contains all definitions of the associated data that return false in method {@link AssociatedDataSchema#isNullable()}.
	 */
	@Getter private final Collection<AssociatedDataSchema> nonNullableAssociatedData;
	/**
	 * Index contains collections of sortable attribute compounds that reference the attribute with the name equal
	 * to a key of this index.
	 */
	@Nonnull private final Map<String, Collection<SortableAttributeCompoundSchemaContract>> attributeToSortableAttributeCompoundIndex;

	/**
	 * Method generates name variant index used for quickly looking up for schemas by name in specific name convention.
	 */
	public static <T> Map<String, T[]> _internalGenerateNameVariantIndex(
		@Nonnull Collection<T> items,
		@Nonnull Function<T, Map<NamingConvention, String>> nameVariantsFetcher
	) {
		if (items.isEmpty()) {
			return new HashMap<>();
		}
		final Map<String, T[]> nameIndex = CollectionUtils.createHashMap(NamingConvention.values().length * items.size());
		for (T schema : items) {
			_internalAddNameVariantsToIndex(nameIndex, schema, nameVariantsFetcher);
		}
		return Collections.unmodifiableMap(nameIndex);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of original Entity from different
	 * package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	public static EntitySchema _internalBuild(@Nonnull String name) {
		return new EntitySchema(
			1,
			name, NamingConvention.generate(name),
			null, null, false, false, false,
			2,
			Collections.emptySet(),
			Collections.emptySet(),
			Collections.emptyMap(),
			Collections.emptyMap(),
			Collections.emptyMap(),
			EnumSet.allOf(EvolutionMode.class),
			Collections.emptyMap()
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of original Entity from different
	 * package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	public static EntitySchema _internalBuild(
		int version,
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		boolean withGeneratedPrimaryKey,
		boolean withHierarchy,
		boolean withPrice,
		int indexedPricePlaces,
		@Nonnull Set<Locale> locales,
		@Nonnull Set<Currency> currencies,
		@Nonnull Map<String, EntityAttributeSchemaContract> attributes,
		@Nonnull Map<String, AssociatedDataSchemaContract> associatedData,
		@Nonnull Map<String, ReferenceSchemaContract> references,
		@Nonnull Set<EvolutionMode> evolutionMode,
		@Nonnull Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds
	) {
		return new EntitySchema(
			version, name, NamingConvention.generate(name),
			description, deprecationNotice,
			withGeneratedPrimaryKey, withHierarchy, withPrice,
			indexedPricePlaces,
			locales,
			currencies,
			attributes,
			associatedData,
			references,
			evolutionMode,
			sortableAttributeCompounds
		);
	}

	public static EntitySchema _internalBuild(
		int version,
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		boolean withGeneratedPrimaryKey,
		boolean withHierarchy,
		boolean withPrice,
		int indexedPricePlaces,
		@Nonnull Set<Locale> locales,
		@Nonnull Set<Currency> currencies,
		@Nonnull Map<String, EntityAttributeSchemaContract> attributes,
		@Nonnull Map<String, AssociatedDataSchemaContract> associatedData,
		@Nonnull Map<String, ReferenceSchemaContract> references,
		@Nonnull Set<EvolutionMode> evolutionMode,
		@Nonnull Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds
	) {
		return new EntitySchema(
			version, name, nameVariants,
			description, deprecationNotice,
			withGeneratedPrimaryKey, withHierarchy, withPrice,
			indexedPricePlaces,
			locales,
			currencies,
			attributes,
			associatedData,
			references,
			evolutionMode,
			sortableAttributeCompounds
		);
	}

	/**
	 * Method generates name variant index used for quickly looking up for schemas by name in specific name convention.
	 */
	static <T> void _internalAddNameVariantsToIndex(
		@Nonnull Map<String, T[]> nameIndex,
		@Nonnull T schema,
		@Nonnull Function<T, Map<NamingConvention, String>> nameVariantsFetcher
	) {
		for (Entry<NamingConvention, String> entry : nameVariantsFetcher.apply(schema).entrySet()) {
			nameIndex.compute(
				entry.getValue(),
				(theName, existingArray) -> {
					@SuppressWarnings("unchecked") final T[] result = existingArray == null ?
						(T[]) Array.newInstance(schema.getClass(), NamingConvention.values().length) : existingArray;
					result[entry.getKey().ordinal()] = schema;
					return result;
				}
			);
		}
	}

	/**
	 * Method converts the "unknown" contract implementation and converts it to the "known" {@link AttributeSchema}
	 * so that the entity schema can access the internal API of it.
	 */
	@Nonnull
	static EntityAttributeSchemaContract toEntityAttributeSchema(@Nonnull EntityAttributeSchemaContract attributeSchemaContract) {
		if (attributeSchemaContract instanceof EntityAttributeSchema attributeSchema) {
			return attributeSchema;
		} else if (attributeSchemaContract instanceof GlobalAttributeSchema globalAttributeSchema) {
			return globalAttributeSchema;
		} else {
			//noinspection unchecked,rawtypes
			return EntityAttributeSchema._internalBuild(
				attributeSchemaContract.getName(),
				attributeSchemaContract.getNameVariants(),
				attributeSchemaContract.getDescription(),
				attributeSchemaContract.getDeprecationNotice(),
				attributeSchemaContract.isUnique(),
				attributeSchemaContract.isFilterable(),
				attributeSchemaContract.isSortable(),
				attributeSchemaContract.isLocalized(),
				attributeSchemaContract.isNullable(),
				attributeSchemaContract.isRepresentative(),
				(Class) attributeSchemaContract.getType(),
				attributeSchemaContract.getDefaultValue(),
				attributeSchemaContract.getIndexedDecimalPlaces()
			);
		}
	}

	/**
	 * Method converts the "unknown" contract implementation and converts it to the "known" {@link AttributeSchema}
	 * so that the entity schema can access the internal API of it.
	 */
	@Nonnull
	static AttributeSchema toReferenceAttributeSchema(@Nonnull AttributeSchemaContract attributeSchemaContract) {
		//noinspection unchecked,rawtypes
		return attributeSchemaContract instanceof AttributeSchema attributeSchema ?
			attributeSchema :
			AttributeSchema._internalBuild(
				attributeSchemaContract.getName(),
				attributeSchemaContract.getNameVariants(),
				attributeSchemaContract.getDescription(),
				attributeSchemaContract.getDeprecationNotice(),
				attributeSchemaContract.isUnique(),
				attributeSchemaContract.isFilterable(),
				attributeSchemaContract.isSortable(),
				attributeSchemaContract.isLocalized(),
				attributeSchemaContract.isNullable(),
				(Class) attributeSchemaContract.getType(),
				attributeSchemaContract.getDefaultValue(),
				attributeSchemaContract.getIndexedDecimalPlaces()
			);
	}

	/**
	 * Method converts the "unknown" contract implementation and converts it to the "known"
	 * {@link SortableAttributeCompoundSchema} so that the entity schema can access the internal API of it.
	 */
	@Nonnull
	static SortableAttributeCompoundSchema toSortableAttributeCompoundSchema(@Nonnull SortableAttributeCompoundSchemaContract sortableAttributeCompoundSchemaContract) {
		return sortableAttributeCompoundSchemaContract instanceof SortableAttributeCompoundSchema sortableAttributeCompoundSchema ?
			sortableAttributeCompoundSchema :
			SortableAttributeCompoundSchema._internalBuild(
				sortableAttributeCompoundSchemaContract.getName(),
				sortableAttributeCompoundSchemaContract.getNameVariants(),
				sortableAttributeCompoundSchemaContract.getDescription(),
				sortableAttributeCompoundSchemaContract.getDeprecationNotice(),
				sortableAttributeCompoundSchemaContract.getAttributeElements()
			);
	}

	/**
	 * Method converts the "unknown" contract implementation and converts it to the "known" {@link AssociatedDataSchema}
	 * so that the entity schema can access the internal API of it.
	 */
	@Nonnull
	private static AssociatedDataSchema toAssociatedDataSchema(@Nonnull AssociatedDataSchemaContract associatedDataSchemaContract) {
		return associatedDataSchemaContract instanceof AssociatedDataSchema associatedDataSchema ?
			associatedDataSchema :
			AssociatedDataSchema._internalBuild(
				associatedDataSchemaContract.getName(),
				associatedDataSchemaContract.getNameVariants(),
				associatedDataSchemaContract.getDescription(),
				associatedDataSchemaContract.getDeprecationNotice(),
				associatedDataSchemaContract.getType(),
				associatedDataSchemaContract.isLocalized(),
				associatedDataSchemaContract.isNullable()
			);
	}

	/**
	 * Method converts the "unknown" contract implementation and converts it to the "known" {@link ReferenceSchema}
	 * so that the entity schema can access the internal API of it.
	 */
	@Nonnull
	private static ReferenceSchema toReferenceSchema(@Nonnull ReferenceSchemaContract referenceSchemaContract) {
		return referenceSchemaContract instanceof ReferenceSchema referenceSchema ?
			referenceSchema :
			ReferenceSchema._internalBuild(
				referenceSchemaContract.getName(),
				referenceSchemaContract.getNameVariants(),
				referenceSchemaContract.getDescription(),
				referenceSchemaContract.getDeprecationNotice(),
				referenceSchemaContract.getReferencedEntityType(),
				referenceSchemaContract.getEntityTypeNameVariants(entityType -> null),
				referenceSchemaContract.isReferencedEntityTypeManaged(),
				referenceSchemaContract.getCardinality(),
				referenceSchemaContract.getReferencedGroupType(),
				referenceSchemaContract.getGroupTypeNameVariants(entityType -> null),
				referenceSchemaContract.isReferencedGroupTypeManaged(),
				referenceSchemaContract.isIndexed(),
				referenceSchemaContract.isFaceted(),
				referenceSchemaContract.getAttributes(),
				referenceSchemaContract.getSortableAttributeCompounds()
			);
	}

	private EntitySchema(
		int version,
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		boolean withGeneratedPrimaryKey,
		boolean withHierarchy,
		boolean withPrice,
		int indexedPricePlaces,
		@Nonnull Set<Locale> locales,
		@Nonnull Set<Currency> currencies,
		@Nonnull Map<String, EntityAttributeSchemaContract> attributes,
		@Nonnull Map<String, AssociatedDataSchemaContract> associatedData,
		@Nonnull Map<String, ReferenceSchemaContract> references,
		@Nonnull Set<EvolutionMode> evolutionMode,
		@Nonnull Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds
	) {
		this.version = version;
		this.name = name;
		this.nameVariants = Collections.unmodifiableMap(nameVariants);
		this.description = description;
		this.deprecationNotice = deprecationNotice;
		this.withGeneratedPrimaryKey = withGeneratedPrimaryKey;
		this.withHierarchy = withHierarchy;
		this.withPrice = withPrice;
		this.indexedPricePlaces = indexedPricePlaces;
		this.locales = Collections.unmodifiableSet(locales.stream().collect(Collectors.toCollection(() -> new TreeSet<>(ComparatorUtils.localeComparator()))));
		this.currencies = Collections.unmodifiableSet(currencies.stream().collect(Collectors.toCollection(() -> new TreeSet<>(ComparatorUtils.currencyComparator()))));
		this.attributes = Collections.unmodifiableMap(
			attributes.entrySet()
				.stream()
				.collect(
					Collectors.toMap(
						Entry::getKey,
						it -> toEntityAttributeSchema(it.getValue()),
						(a, b) -> {
							throw new IllegalStateException("Duplicate key " + a);
						},
						LinkedHashMap::new
					)
				)
		);
		this.attributeNameIndex = _internalGenerateNameVariantIndex(this.attributes.values(), AttributeSchemaContract::getNameVariants);
		this.associatedData = Collections.unmodifiableMap(
			associatedData.entrySet()
				.stream()
				.collect(
					Collectors.toMap(
						Entry::getKey,
						it -> toAssociatedDataSchema(it.getValue()),
						(a, b) -> {
							throw new IllegalStateException("Duplicate key " + a.getName());
						},
						LinkedHashMap::new
					)
				)
		);
		this.associatedDataNameIndex = _internalGenerateNameVariantIndex(this.associatedData.values(), AssociatedDataSchemaContract::getNameVariants);
		this.references = Collections.unmodifiableMap(
			references.entrySet()
				.stream()
				.collect(
					Collectors.toMap(
						Entry::getKey,
						it -> toReferenceSchema(it.getValue()),
						(a, b) -> {
							throw new IllegalStateException("Duplicate key " + a.getName());
						},
						LinkedHashMap::new
					)
				)
		);
		;
		this.referenceNameIndex = _internalGenerateNameVariantIndex(this.references.values(), ReferenceSchemaContract::getNameVariants);
		this.evolutionMode = Collections.unmodifiableSet(evolutionMode);
		this.nonNullableAttributes = this.attributes
			.values()
			.stream()
			.filter(it -> !it.isNullable())
			.toList();
		this.nonNullableAssociatedData = this.associatedData
			.values()
			.stream()
			.filter(it -> !it.isNullable())
			.toList();
		this.sortableAttributeCompounds = Collections.unmodifiableMap(
			sortableAttributeCompounds
				.entrySet()
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

	@Nonnull
	@Override
	public String getNameVariant(@Nonnull NamingConvention namingConvention) {
		return this.nameVariants.get(namingConvention);
	}

	@Override
	@Nonnull
	public Map<String, EntityAttributeSchemaContract> getAttributes() {
		// we need EntitySchema to provide access to internal representations - i.e. whoever has
		// reference to EntitySchema should have access to other internal schema representations as well
		// unfortunately, the Generics in Java is just stupid, and we cannot provide subtype at the place of supertype
		// collection, so we have to work around that issue using generics stripping
		//noinspection unchecked,rawtypes
		return (Map) attributes;
	}

	@Nonnull
	@Override
	public Optional<EntityAttributeSchemaContract> getAttribute(@Nonnull String attributeName) {
		return ofNullable(this.attributes.get(attributeName));
	}

	@Nonnull
	@Override
	public Optional<EntityAttributeSchemaContract> getAttributeByName(@Nonnull String attributeName, @Nonnull NamingConvention namingConvention) {
		return ofNullable(attributeNameIndex.get(attributeName))
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
		return (Map) sortableAttributeCompounds;
	}

	@Nonnull
	@Override
	public Optional<SortableAttributeCompoundSchemaContract> getSortableAttributeCompound(@Nonnull String name) {
		return ofNullable(sortableAttributeCompounds.get(name));
	}

	@Nonnull
	@Override
	public Optional<SortableAttributeCompoundSchemaContract> getSortableAttributeCompoundByName(@Nonnull String name, @Nonnull NamingConvention namingConvention) {
		return ofNullable(sortableAttributeCompoundNameIndex.get(name))
			.map(it -> it[namingConvention.ordinal()]);
	}

	@Nonnull
	@Override
	public Collection<SortableAttributeCompoundSchemaContract> getSortableAttributeCompoundsForAttribute(@Nonnull String attributeName) {
		return ofNullable(attributeToSortableAttributeCompoundIndex.get(attributeName))
			.orElse(Collections.emptyList());
	}

	@Override
	public int version() {
		return version;
	}

	@Override
	public boolean isBlank() {
		return this.version == 1 && !this.withGeneratedPrimaryKey && !this.withHierarchy && !this.withPrice &&
			this.indexedPricePlaces == 2 && this.locales.isEmpty() && this.references.isEmpty() &&
			this.attributes.isEmpty() && this.associatedData.isEmpty() &&
			this.evolutionMode.size() == EvolutionMode.values().length;
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataSchemaContract> getAssociatedData(@Nonnull String dataName) {
		return ofNullable(this.associatedData.get(dataName));
	}

	@Nonnull
	@Override
	public AssociatedDataSchemaContract getAssociatedDataOrThrowException(@Nonnull String dataName) {
		return ofNullable(this.associatedData.get(dataName))
			.orElseThrow(() -> new EvitaInvalidUsageException("Associated data `" + dataName + "` is not known in entity `" + getName() + "` schema!"));
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataSchemaContract> getAssociatedDataByName(@Nonnull String dataName, @Nonnull NamingConvention namingConvention) {
		return ofNullable(associatedDataNameIndex.get(dataName))
			.map(it -> it[namingConvention.ordinal()]);
	}

	@Override
	@Nonnull
	public Map<String, AssociatedDataSchemaContract> getAssociatedData() {
		// we need EntitySchema to provide access to provide access to internal representations - i.e. whoever has
		// reference to EntitySchema should have access to other internal schema representations as well
		// unfortunately, the Generics in Java is just stupid, and we cannot provide subtype at the place of supertype
		// collection, so we have to work around that issue using generics stripping
		//noinspection unchecked,rawtypes
		return (Map) associatedData;
	}

	@Nonnull
	@Override
	public Optional<ReferenceSchemaContract> getReference(@Nonnull String referenceName) {
		return ofNullable(this.references.get(referenceName));
	}

	@Nonnull
	@Override
	public Optional<ReferenceSchemaContract> getReferenceByName(@Nonnull String referenceName, @Nonnull NamingConvention namingConvention) {
		return ofNullable(referenceNameIndex.get(referenceName))
			.map(it -> it[namingConvention.ordinal()]);
	}

	@Override
	@Nonnull
	public Map<String, ReferenceSchemaContract> getReferences() {
		// we need EntitySchema to provide access to provide access to internal representations - i.e. whoever has
		// reference to EntitySchema should have access to other internal schema representations as well
		// unfortunately, the Generics in Java is just stupid, and we cannot provide subtype at the place of supertype
		// collection, so we have to work around that issue using generics stripping
		//noinspection unchecked,rawtypes
		return (Map) references;
	}

	@Nonnull
	@Override
	public ReferenceSchema getReferenceOrThrowException(@Nonnull String referenceName) {
		return getReference(referenceName)
			.map(it -> (ReferenceSchema) it)
			.orElseThrow(() -> new ReferenceNotFoundException(referenceName, this));
	}

	/**
	 * Returns true if this schema differs in any way from other schema. Executes full comparison logic of all contents.
	 */
	@Override
	public boolean differsFrom(@Nullable EntitySchemaContract otherSchema) {
		if (this == otherSchema) return false;
		if (otherSchema == null) return true;

		if (version != otherSchema.version()) return true;
		if (withGeneratedPrimaryKey != otherSchema.isWithGeneratedPrimaryKey()) return true;
		if (withHierarchy != otherSchema.isWithHierarchy()) return true;
		if (withPrice != otherSchema.isWithPrice()) return true;
		if (!name.equals(otherSchema.getName())) return true;
		if (!locales.equals(otherSchema.getLocales())) return true;
		if (!currencies.equals(otherSchema.getCurrencies())) return true;

		if (attributes.size() != otherSchema.getAttributes().size()) return true;
		for (Entry<String, EntityAttributeSchemaContract> entry : attributes.entrySet()) {
			final Optional<EntityAttributeSchemaContract> otherAttributeSchema = otherSchema.getAttribute(entry.getKey());
			if (otherAttributeSchema.map(it -> !Objects.equals(it, entry.getValue())).orElse(true)) {
				return true;
			}
		}

		if (associatedData.size() != otherSchema.getAssociatedData().size()) return true;
		for (Entry<String, AssociatedDataSchema> entry : associatedData.entrySet()) {
			if (otherSchema.getAssociatedData(entry.getKey()).map(it -> !Objects.equals(entry.getValue(), it)).orElse(true)) {
				return true;
			}
		}

		if (references.size() != otherSchema.getReferences().size()) return true;
		for (Entry<String, ReferenceSchema> entry : references.entrySet()) {
			if (otherSchema.getReference(entry.getKey()).map(it -> !Objects.equals(entry.getValue(), it)).orElse(true)) {
				return true;
			}
		}

		return !evolutionMode.equals(otherSchema.getEvolutionMode());
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
