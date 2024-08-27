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
import io.evitadb.dataType.ClassifierType;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
import io.evitadb.utils.NamingConvention;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
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
	@Serial private static final long serialVersionUID = 2018566260261489037L;

	@Getter @Nonnull protected final String name;
	@Getter @Nonnull protected final Map<NamingConvention, String> nameVariants;
	@Getter @Nullable protected final String description;
	@Getter @Nullable protected final String deprecationNotice;
	@Getter @Nonnull protected final Cardinality cardinality;
	@Getter @Nonnull protected final String referencedEntityType;
	@Nonnull protected final Map<NamingConvention, String> entityTypeNameVariants;
	@Getter protected final boolean referencedEntityTypeManaged;
	@Getter @Nullable protected final String referencedGroupType;
	@Nonnull protected final Map<NamingConvention, String> groupTypeNameVariants;
	@Getter protected final boolean referencedGroupTypeManaged;
	@Getter protected final boolean indexed;
	@Getter protected final boolean faceted;

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
	 * Contains all definitions of the attributes that return false in method {@link AttributeSchema#isNullable()}.
	 */
	@Getter @Nonnull private final Collection<AttributeSchema> nonNullableAttributes;
	/**
	 * Index contains collections of sortable attribute compounds that reference the attribute with the name equal
	 * to a key of this index.
	 */
	@Nonnull private final Map<String, Collection<SortableAttributeCompoundSchemaContract>> attributeToSortableAttributeCompoundIndex;

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
		boolean indexed,
		boolean faceted
	) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, entityType);
		if (groupType != null) {
			ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, groupType);
		}
		if (faceted) {
			Assert.isTrue(indexed, "When reference is marked as faceted, it needs also to be indexed.");
		}

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
			indexed,
			faceted,
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
		boolean indexed,
		boolean faceted,
		@Nonnull Map<String, AttributeSchemaContract> attributes,
		@Nonnull Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds
	) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, entityType);
		if (groupType != null) {
			ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, groupType);
		}
		if (faceted) {
			Assert.isTrue(indexed, "When reference is marked as faceted, it needs also to be indexed.");
		}

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
			indexed,
			faceted,
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
		boolean indexed,
		boolean faceted,
		@Nonnull Map<String, AttributeSchemaContract> attributes,
		@Nonnull Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds
	) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, entityType);
		if (groupType != null) {
			ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, groupType);
		}
		if (faceted) {
			Assert.isTrue(indexed, "When reference is marked as faceted, it needs also to be indexed.");
		}

		return new ReferenceSchema(
			name, nameVariants,
			description, deprecationNotice, cardinality,
			entityType,
			entityTypeNameVariants,
			referencedEntityTypeManaged,
			groupType,
			ofNullable(groupTypeNameVariants).orElse(Collections.emptyMap()),
			referencedGroupTypeManaged,
			indexed,
			faceted,
			attributes,
			sortableAttributeCompounds
		);
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
		boolean indexed,
		boolean faceted,
		@Nonnull Map<String, AttributeSchemaContract> attributes,
		@Nonnull Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds
	) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, referencedEntityType);
		this.name = name;
		this.nameVariants = Collections.unmodifiableMap(nameVariants);
		this.description = description;
		this.deprecationNotice = deprecationNotice;
		this.cardinality = cardinality;
		this.referencedEntityType = referencedEntityType;
		this.entityTypeNameVariants = Collections.unmodifiableMap(entityTypeNameVariants);
		this.referencedEntityTypeManaged = referencedEntityTypeManaged;
		this.referencedGroupType = referencedGroupType;
		this.groupTypeNameVariants = Collections.unmodifiableMap(groupTypeNameVariants);
		this.referencedGroupTypeManaged = referencedGroupTypeManaged;
		this.indexed = indexed;
		this.faceted = faceted;
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
		this.nonNullableAttributes = this.attributes
			.values()
			.stream()
			.filter(it -> !it.isNullable())
			.toList();
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

	@Nonnull
	@Override
	public Map<NamingConvention, String> getEntityTypeNameVariants(@Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher) {
		return referencedEntityTypeManaged ?
			Objects.requireNonNull(entitySchemaFetcher.apply(referencedEntityType)).getNameVariants() :
			this.entityTypeNameVariants;
	}

	@Override
	@Nonnull
	public String getReferencedEntityTypeNameVariant(@Nonnull NamingConvention namingConvention, @Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher) {
		return referencedEntityTypeManaged ?
			Objects.requireNonNull(entitySchemaFetcher.apply(referencedEntityType)).getNameVariant(namingConvention) :
			this.entityTypeNameVariants.get(namingConvention);
	}

	@Nonnull
	@Override
	public Map<NamingConvention, String> getGroupTypeNameVariants(@Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher) {
		return referencedGroupTypeManaged ?
			Objects.requireNonNull(entitySchemaFetcher.apply(referencedGroupType)).getNameVariants() :
			this.groupTypeNameVariants;
	}

	@Override
	@Nonnull
	public String getReferencedGroupTypeNameVariant(@Nonnull NamingConvention namingConvention, @Nonnull Function<String, EntitySchemaContract> entitySchemaFetcher) {
		return referencedGroupTypeManaged ?
			Objects.requireNonNull(entitySchemaFetcher.apply(referencedGroupType)).getNameVariant(namingConvention) :
			this.groupTypeNameVariants.get(namingConvention);
	}

	@Nonnull
	@Override
	public Optional<AttributeSchemaContract> getAttribute(@Nonnull String attributeName) {
		return ofNullable(this.attributes.get(attributeName));
	}

	@Nonnull
	@Override
	public Optional<AttributeSchemaContract> getAttributeByName(@Nonnull String attributeName, @Nonnull NamingConvention namingConvention) {
		return ofNullable(attributeNameIndex.get(attributeName))
			.map(it -> it[namingConvention.ordinal()]);
	}

	@Nonnull
	@Override
	public Map<String, AttributeSchemaContract> getAttributes() {
		// we need EntitySchema to provide access to internal representations - i.e. whoever has
		// reference to EntitySchema should have access to other internal schema representations as well
		// unfortunately, the Generics in Java is just stupid, and we cannot provide subtype at the place of supertype
		// collection, so we have to work around that issue using generics stripping
		//noinspection unchecked,rawtypes
		return (Map)this.attributes;
	}

	@Nonnull
	@Override
	public Map<String, SortableAttributeCompoundSchemaContract> getSortableAttributeCompounds() {
		// we need EntitySchema to provide access to internal representations - i.e. whoever has
		// reference to EntitySchema should have access to other internal schema representations as well
		// unfortunately, the Generics in Java is just stupid, and we cannot provide subtype at the place of supertype
		// collection, so we have to work around that issue using generics stripping
		//noinspection unchecked,rawtypes
		return (Map)this.sortableAttributeCompounds;
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
		if (this.referencedGroupTypeManaged &&
				catalogSchema.getEntitySchema(this.referencedGroupType).isEmpty()
		) {
			referenceErrors = Stream.concat(
				referenceErrors,
				Stream.of("Referenced group entity type `" + this.referencedGroupType + "` is not present in catalog `" + catalogSchema.getName() + "` schema!"));
		} else if (!this.referencedGroupTypeManaged &&
			catalogSchema.getEntitySchema(this.referencedGroupType).isPresent()
		) {
			referenceErrors = Stream.concat(
				referenceErrors,
				Stream.of("Referenced group entity type `" + this.referencedGroupType + "` is present in catalog `" + catalogSchema.getName() + "` schema, but it's marked as not managed!"));
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
			this.indexed,
			this.faceted,
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
			this.indexed,
			this.faceted,
			this.getAttributes(),
			this.getSortableAttributeCompounds()
		);
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
		if (!this.isIndexed()) {
			for (AttributeSchemaContract attribute : attributes.values()) {
				if (attribute.isFilterable()) {
					attributeErrors = Stream.concat(
						attributeErrors,
						Stream.of("Attribute `" + attribute.getName() + "` of reference schema `" + this.name + "` is filterable but reference schema is not indexed!")
					);
				}
				if (attribute.isSortable()) {
					attributeErrors = Stream.concat(
						attributeErrors,
						Stream.of("Attribute `" + attribute.getName() + "` of reference schema `" + this.name + "` is sortable but reference schema is not indexed!")
					);
				}
				if (attribute.isUnique()) {
					attributeErrors = Stream.concat(
						attributeErrors,
						Stream.of("Attribute `" + attribute.getName() + "` of reference schema `" + this.name + "` is unique but reference schema is not indexed!")
					);
				}
			}
		}
		return attributeErrors;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		ReferenceSchema that = (ReferenceSchema) o;
		return referencedEntityTypeManaged == that.referencedEntityTypeManaged && referencedGroupTypeManaged == that.referencedGroupTypeManaged && indexed == that.indexed && faceted == that.faceted && name.equals(that.name) && nameVariants.equals(that.nameVariants) && Objects.equals(description, that.description) && Objects.equals(deprecationNotice, that.deprecationNotice) && cardinality == that.cardinality && referencedEntityType.equals(that.referencedEntityType) && entityTypeNameVariants.equals(that.entityTypeNameVariants) && Objects.equals(referencedGroupType, that.referencedGroupType) && groupTypeNameVariants.equals(that.groupTypeNameVariants) && sortableAttributeCompounds.equals(that.sortableAttributeCompounds) && attributes.equals(that.attributes);
	}

	@Override
	public int hashCode() {
		int result = name.hashCode();
		result = 31 * result + nameVariants.hashCode();
		result = 31 * result + Objects.hashCode(description);
		result = 31 * result + Objects.hashCode(deprecationNotice);
		result = 31 * result + cardinality.hashCode();
		result = 31 * result + referencedEntityType.hashCode();
		result = 31 * result + entityTypeNameVariants.hashCode();
		result = 31 * result + Boolean.hashCode(referencedEntityTypeManaged);
		result = 31 * result + Objects.hashCode(referencedGroupType);
		result = 31 * result + groupTypeNameVariants.hashCode();
		result = 31 * result + Boolean.hashCode(referencedGroupTypeManaged);
		result = 31 * result + Boolean.hashCode(indexed);
		result = 31 * result + Boolean.hashCode(faceted);
		result = 31 * result + sortableAttributeCompounds.hashCode();
		result = 31 * result + attributes.hashCode();
		return result;
	}

	/**
	 * Helper DTO to envelope relation between {@link AttributeElement} and {@link SortableAttributeCompoundSchemaContract}.
	 *
	 * @param attribute {@link SortableAttributeCompoundSchemaContract#getAttributeElements()} item
	 * @param compoundSchema {@link SortableAttributeCompoundSchemaContract} enveloping compound
	 */
	private record AttributeToCompound(
		@Nonnull AttributeElement attribute,
		@Nonnull SortableAttributeCompoundSchema compoundSchema
	) {
	}

}
