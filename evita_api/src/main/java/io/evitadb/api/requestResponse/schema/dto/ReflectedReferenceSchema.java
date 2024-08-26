/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
import io.evitadb.utils.NamingConvention;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Internal implementation of {@link ReflectedReferenceSchemaContract}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 * @see ReflectedReferenceSchemaContract
 */
@Immutable
@ThreadSafe
public final class ReflectedReferenceSchema extends ReferenceSchema implements ReflectedReferenceSchemaContract {
	@Serial private static final long serialVersionUID = 4857683151308476440L;

	/**
	 * Contains name of the original reference of the {@link #getReferencedEntityType()} this reference reflects.
	 */
	private final String reflectedReferenceName;
	/**
	 * Contains the original reference this one reflects. Might be null for instances created in builders. If the schema
	 * instance is present on the server side, it may by null only if referenced entity doesn't (yet) have the reflected
	 * reference by that name.
	 */
	@Nullable
	private final ReferenceSchemaContract reflectedReference;
	/**
	 * Contains TRUE if the description of the reflected reference is inherited from the target reference.
	 */
	private final boolean descriptionInherited;
	/**
	 * Contains TRUE if the deprecated flag of the reflected reference is inherited from the target reference.
	 */
	private final boolean deprecatedInherited;
	/**
	 * Contains TRUE if the cardinality of the reflected reference is inherited from the target reference.
	 */
	private final boolean cardinalityInherited;
	/**
	 * Contains TRUE if the indexed flag of the reflected reference is inherited from the target reference.
	 */
	private final boolean indexedInherited;
	/**
	 * Contains TRUE if the faceted flag of the reflected reference is inherited from the target reference.
	 */
	private final boolean facetedInherited;
	/**
	 * Contains TRUE if the attributes of the reflected reference are inherited from the target reference.
	 */
	private final boolean attributesInherited;
	/**
	 * Contains the names of the attributes that are excluded from inheritance.
	 */
	@Nonnull
	private final String[] attributesExcludedFromInheritance;

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of ReferenceSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static ReflectedReferenceSchema _internalBuild(
		@Nonnull String name,
		@Nonnull String entityType,
		@Nonnull String reflectedReferenceName
	) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, entityType);

		return new ReflectedReferenceSchema(
			name, NamingConvention.generate(name),
			null, null, null,
			entityType,
			reflectedReferenceName,
			null,
			null,
			Collections.emptyMap(),
			Collections.emptyMap(),
			true,
			new String[0],
			null
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of ReferenceSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static ReflectedReferenceSchema _internalBuild(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nonnull String entityType,
		@Nonnull String reflectedReferenceName,
		@Nullable Cardinality cardinality,
		@Nullable Boolean indexed,
		@Nullable Boolean faceted,
		@Nonnull Map<String, AttributeSchemaContract> attributes,
		@Nonnull Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds,
		boolean attributesInherited,
		@Nullable String[] attributesExcludedFromInheritance
	) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, entityType);
		return new ReflectedReferenceSchema(
			name, NamingConvention.generate(name),
			description, deprecationNotice, cardinality,
			entityType,
			reflectedReferenceName,
			indexed,
			faceted,
			attributes,
			sortableAttributeCompounds,
			attributesInherited,
			attributesExcludedFromInheritance == null ? new String[0] : attributesExcludedFromInheritance,
			null
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of ReferenceSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static ReflectedReferenceSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nonnull String entityType,
		@Nonnull String reflectedReferenceName,
		@Nullable Cardinality cardinality,
		@Nullable Boolean indexed,
		@Nullable Boolean faceted,
		@Nonnull Map<String, AttributeSchemaContract> attributes,
		@Nonnull Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds,
		boolean attributesInherited,
		@Nullable String[] attributesExcludedFromInheritance
	) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, entityType);
		return new ReflectedReferenceSchema(
			name, nameVariants,
			description, deprecationNotice, cardinality,
			entityType,
			reflectedReferenceName,
			indexed,
			faceted,
			attributes,
			sortableAttributeCompounds,
			attributesInherited,
			attributesExcludedFromInheritance == null ? new String[0] : attributesExcludedFromInheritance,
			null
		);
	}

	/**
	 * Returns new map that contains all the attributes of the reference and the reflected reference except the ones
	 * that are excluded from inheritance.
	 *
	 * @param attributes                        the attributes of the reference
	 * @param reflectedAttributes               the attributes of the reflected reference (inherited ones)
	 * @param attributesExcludedFromInheritance the attributes excluded from inheritance
	 * @param <V>                               the type of the attributes
	 * @return the union of the attributes
	 */
	@Nonnull
	private static <V> Map<String, V> union(
		@Nonnull Map<String, V> attributes,
		@Nonnull Map<String, V> reflectedAttributes,
		@Nonnull String[] attributesExcludedFromInheritance
	) {
		final Set<String> excludedAttributes = Set.of(attributesExcludedFromInheritance);
		final HashMap<String, V> result = new HashMap<>(attributes);
		for (Entry<String, V> reflectedEntry : reflectedAttributes.entrySet()) {
			if (!excludedAttributes.contains(reflectedEntry.getKey())) {
				Assert.isPremiseValid(
					!result.containsKey(reflectedEntry.getKey()),
					"Attribute `" + reflectedEntry.getKey() + "` is inherited from the reflected reference, " +
						"but it is already defined!"
				);
				result.put(reflectedEntry.getKey(), reflectedEntry.getValue());
			}
		}
		return result;
	}

	/**
	 * Returns a new map that contains the attributes from the "unionAttributes" map that satisfy the given predicate
	 * "isOriginalPredicate". This method is used to inverse the effect of {@link #union(Map, Map, String[])} method.
	 *
	 * @param unionAttributes     the map containing the attributes
	 * @param isOriginalPredicate the predicate to filter the attributes
	 * @param <V>                 the type of the attributes
	 * @return a new map with the intersected attributes
	 */
	@Nonnull
	private static <V> Map<String, V> intersect(@Nonnull Map<String, V> unionAttributes, @Nonnull Predicate<String> isOriginalPredicate) {
		return unionAttributes
			.entrySet()
			.stream()
			.filter(entry -> !isOriginalPredicate.test(entry.getKey()))
			.collect(Collectors.toMap(Entry::getKey, Entry::getValue));
	}

	public ReflectedReferenceSchema(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Cardinality cardinality,
		@Nonnull String referencedEntityType,
		@Nonnull String reflectedReferenceName,
		@Nullable Boolean indexed,
		@Nullable Boolean faceted,
		@Nonnull Map<String, AttributeSchemaContract> attributes,
		@Nonnull Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds,
		boolean attributesInherited,
		@Nullable String[] attributesExcludedFromInheritance,
		@Nullable ReferenceSchemaContract reflectedReference
	) {
		super(
			name, nameVariants,
			reflectedReference != null && description == null ? reflectedReference.getDescription() : description,
			reflectedReference != null && deprecationNotice == null ? reflectedReference.getDeprecationNotice() : deprecationNotice,
			reflectedReference != null && cardinality == null ? reflectedReference.getCardinality() : cardinality,
			referencedEntityType,
			Map.of(), true,
			reflectedReference != null ? reflectedReference.getReferencedGroupType() : null,
			Map.of(),
			reflectedReference != null && reflectedReference.isReferencedGroupTypeManaged(),
			indexed == null ? reflectedReference != null && reflectedReference.isIndexed() : indexed,
			faceted == null ? reflectedReference != null && reflectedReference.isFaceted() : faceted,
			reflectedReference != null && attributesInherited ? union(attributes, reflectedReference.getAttributes(), attributesExcludedFromInheritance) : attributes,
			reflectedReference != null && attributesInherited ? union(sortableAttributeCompounds, reflectedReference.getSortableAttributeCompounds(), attributesExcludedFromInheritance) : sortableAttributeCompounds
		);
		Assert.isTrue(
			reflectedReference == null || reflectedReference.getName().equals(reflectedReferenceName),
			() -> "Reflected reference name `" + referencedEntityType + "` must have the same name as the target reference (`" + reflectedReference.getName() + "`)!"
		);
		Assert.isTrue(
			reflectedReference == null || reflectedReference.isReferencedEntityTypeManaged(),
			() -> "Reflected reference name `" + referencedEntityType + "` must refer to a managed entity type!"
		);
		/* TODO JNO - add verification that reflected reference entity schema name, is consistent with reflected referenced entity type */
		this.reflectedReferenceName = reflectedReferenceName;
		this.reflectedReference = reflectedReference;
		this.descriptionInherited = description == null;
		this.deprecatedInherited = deprecationNotice == null;
		this.cardinalityInherited = cardinality == null;
		Assert.isTrue(
			this.cardinalityInherited || cardinality != null,
			"Cardinality must be either inherited or specified explicitly!"
		);
		this.indexedInherited = indexed == null;
		Assert.isTrue(
			this.indexedInherited || indexed != null,
			"Indexed must be either inherited or specified explicitly!"
		);
		this.facetedInherited = faceted == null;
		Assert.isTrue(
			this.facetedInherited || faceted != null,
			"Faceted must be either inherited or specified explicitly!"
		);
		this.attributesInherited = attributesInherited;
		this.attributesExcludedFromInheritance = attributesExcludedFromInheritance == null ? new String[0] : attributesExcludedFromInheritance;
	}

	@Nonnull
	@Override
	public String getReflectedReferenceName() {
		return this.reflectedReferenceName;
	}

	@Override
	public boolean isDescriptionInherited() {
		return this.descriptionInherited;
	}

	@Override
	public boolean isDeprecatedInherited() {
		return this.deprecatedInherited;
	}

	@Override
	public boolean isCardinalityInherited() {
		return this.cardinalityInherited;
	}

	@Override
	public boolean isAttributesInherited() {
		return this.attributesInherited;
	}

	@Nonnull
	@Override
	public String[] getAttributesExcludedFromInheritance() {
		return this.attributesExcludedFromInheritance;
	}

	@Override
	public boolean isIndexedInherited() {
		return this.indexedInherited;
	}

	@Override
	public boolean isFacetedInherited() {
		return this.facetedInherited;
	}

	@Nullable
	@Override
	public String getDescription() {
		Assert.isTrue(
			!this.descriptionInherited || this.reflectedReference != null,
			"Description of the reflected reference is inherited from the target reference, but the reflected reference is not available!"
		);
		return super.getDescription();
	}

	@Nullable
	@Override
	public String getDeprecationNotice() {
		Assert.isTrue(
			!this.deprecatedInherited || this.reflectedReference != null,
			"Deprecation notice of the reflected reference is inherited from the target reference, but the reflected reference is not available!"
		);
		return super.getDeprecationNotice();
	}

	@Nonnull
	@Override
	public Cardinality getCardinality() {
		Assert.isTrue(
			!this.cardinalityInherited || this.reflectedReference != null,
			"Cardinality of the reflected reference is inherited from the target reference, but the reflected reference is not available!"
		);
		return super.getCardinality();
	}

	@Nullable
	@Override
	public String getReferencedGroupType() {
		Assert.isTrue(
			this.reflectedReference != null,
			"Reflected reference must be available to return a referenced group type!"
		);
		return super.getReferencedGroupType();
	}

	@Override
	public boolean isReferencedGroupTypeManaged() {
		Assert.isTrue(
			this.reflectedReference != null,
			"Reflected reference must be available to return if the referenced group type is managed!"
		);
		return super.isReferencedGroupTypeManaged();
	}

	@Override
	public boolean isIndexed() {
		Assert.isTrue(
			!this.indexedInherited || this.reflectedReference != null,
			"Indexed property of the reflected reference is inherited from the target reference, but the reflected reference is not available!"
		);
		return super.isIndexed();
	}

	@Override
	public boolean isFaceted() {
		Assert.isTrue(
			!this.facetedInherited || this.reflectedReference != null,
			"Faceted property of the reflected reference is inherited from the target reference, but the reflected reference is not available!"
		);
		return super.isFaceted();
	}

	@Nonnull
	@Override
	public Collection<AttributeSchema> getNonNullableAttributes() {
		assertAttributes();
		return super.getNonNullableAttributes();
	}

	@Nonnull
	@Override
	public Optional<AttributeSchemaContract> getAttribute(@Nonnull String attributeName) {
		assertAttributes();
		return super.getAttribute(attributeName);
	}

	@Nonnull
	public Optional<AttributeSchemaContract> getDeclaredAttribute(String attributeName) {
		// when attribute is inherited - return empty result
		if (this.reflectedReference != null && this.reflectedReference.getAttribute(attributeName).isPresent()) {
			return Optional.empty();
		}
		return super.getAttribute(attributeName);
	}

	@Nonnull
	@Override
	public Optional<AttributeSchemaContract> getAttributeByName(@Nonnull String attributeName, @Nonnull NamingConvention namingConvention) {
		assertAttributes();
		return super.getAttributeByName(attributeName, namingConvention);
	}

	@Nonnull
	@Override
	public Map<String, AttributeSchemaContract> getAttributes() {
		assertAttributes();
		return super.getAttributes();
	}

	@Nonnull
	public Map<String, AttributeSchemaContract> getDeclaredAttributes() {
		// when attribute is inherited - return empty result
		if (this.reflectedReference != null) {
			// this method always creates new map and is suboptimal, if it becomes bottleneck, we should memoize the product
			return intersect(
				super.getAttributes(),
				attributeName -> this.reflectedReference.getAttribute(attributeName).isPresent()
			);
		} else {
			return super.getAttributes();
		}
	}

	@Nonnull
	@Override
	public Map<String, SortableAttributeCompoundSchemaContract> getSortableAttributeCompounds() {
		assertAttributes();
		return super.getSortableAttributeCompounds();
	}

	@Nonnull
	public Map<String, SortableAttributeCompoundSchemaContract> getDeclaredSortableAttributeCompounds() {
		// when attribute is inherited - return empty result
		if (this.reflectedReference != null) {
			// this method always creates new map and is suboptimal, if it becomes bottleneck, we should memoize the product
			return intersect(
				super.getSortableAttributeCompounds(),
				attributeName -> this.reflectedReference.getSortableAttributeCompound(attributeName).isPresent()
			);
		} else {
			return super.getSortableAttributeCompounds();
		}
	}

	@Nonnull
	@Override
	public Optional<SortableAttributeCompoundSchemaContract> getSortableAttributeCompound(@Nonnull String name) {
		assertAttributes();
		return super.getSortableAttributeCompound(name);
	}

	@Nonnull
	public Optional<SortableAttributeCompoundSchemaContract> getDeclaredSortableAttributeCompound(@Nonnull String name) {
		// when sortable attribute compound is inherited - return empty result
		if (this.reflectedReference != null && this.reflectedReference.getSortableAttributeCompound(name).isPresent()) {
			return Optional.empty();
		}
		return super.getSortableAttributeCompound(name);
	}

	@Nonnull
	@Override
	public Optional<SortableAttributeCompoundSchemaContract> getSortableAttributeCompoundByName(@Nonnull String name, @Nonnull NamingConvention namingConvention) {
		assertAttributes();
		return super.getSortableAttributeCompoundByName(name, namingConvention);
	}

	@Nonnull
	@Override
	public Collection<SortableAttributeCompoundSchemaContract> getSortableAttributeCompoundsForAttribute(@Nonnull String attributeName) {
		assertAttributes();
		return super.getSortableAttributeCompoundsForAttribute(attributeName);
	}

	/**
	 * Creates a copy of this instance with different settings for:
	 *
	 * @param name new value of name property
	 * @return copy of the schema with applied changes
	 */
	@Nonnull
	public ReflectedReferenceSchemaContract withName(@Nullable String name) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, name);
		return new ReflectedReferenceSchema(
			name,
			NamingConvention.generate(name),
			this.descriptionInherited ? null : this.getDescription(),
			this.deprecatedInherited ? null : this.getDeprecationNotice(),
			this.cardinalityInherited ? null : this.getCardinality(),
			this.getReferencedEntityType(),
			this.reflectedReferenceName,
			this.indexedInherited ? null : this.isIndexed(),
			this.facetedInherited ? null : this.isFaceted(),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getAttributes() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getAttributes(),
					attributeName -> this.reflectedReference.getAttribute(attributeName).isPresent()
				),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getSortableAttributeCompounds() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getSortableAttributeCompounds(),
					sortableAttributeCompoundName -> this.reflectedReference.getSortableAttributeCompound(sortableAttributeCompoundName).isPresent()
				),
			this.attributesInherited,
			this.attributesExcludedFromInheritance,
			this.reflectedReference
		);
	}

	/**
	 * Creates a copy of this instance with different settings for:
	 *
	 * @param description new value of description property
	 * @return copy of the schema with applied changes
	 */
	@Nonnull
	public ReflectedReferenceSchemaContract withDescription(@Nullable String description) {
		return new ReflectedReferenceSchema(
			this.getName(),
			this.getNameVariants(),
			description,
			this.deprecatedInherited ? null : this.getDeprecationNotice(),
			this.cardinalityInherited ? null : this.getCardinality(),
			this.getReferencedEntityType(),
			this.reflectedReferenceName,
			this.indexedInherited ? null : this.isIndexed(),
			this.facetedInherited ? null : this.isFaceted(),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getAttributes() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getAttributes(),
					attributeName -> this.reflectedReference.getAttribute(attributeName).isPresent()
				),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getSortableAttributeCompounds() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getSortableAttributeCompounds(),
					sortableAttributeCompoundName -> this.reflectedReference.getSortableAttributeCompound(sortableAttributeCompoundName).isPresent()
				),
			this.attributesInherited,
			this.attributesExcludedFromInheritance,
			this.reflectedReference
		);
	}

	/**
	 * Creates a copy of this instance with different settings for:
	 *
	 * @param deprecationNotice new value of deprecation notice property
	 * @return copy of the schema with applied changes
	 */
	@Nonnull
	public ReflectedReferenceSchemaContract withDeprecationNotice(@Nullable String deprecationNotice) {
		return new ReflectedReferenceSchema(
			this.getName(),
			this.getNameVariants(),
			this.descriptionInherited ? null : this.getDescription(),
			deprecationNotice,
			this.cardinalityInherited ? null : this.getCardinality(),
			this.getReferencedEntityType(),
			this.reflectedReferenceName,
			this.indexedInherited ? null : this.isIndexed(),
			this.facetedInherited ? null : this.isFaceted(),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getAttributes() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getAttributes(),
					attributeName -> this.reflectedReference.getAttribute(attributeName).isPresent()
				),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getSortableAttributeCompounds() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getSortableAttributeCompounds(),
					sortableAttributeCompoundName -> this.reflectedReference.getSortableAttributeCompound(sortableAttributeCompoundName).isPresent()
				),
			this.attributesInherited,
			this.attributesExcludedFromInheritance,
			this.reflectedReference
		);
	}

	/**
	 * Creates a copy of this instance with different settings for:
	 *
	 * @param cardinality new value of cardinality property
	 * @return copy of the schema with applied changes
	 */
	@Nonnull
	public ReflectedReferenceSchemaContract withCardinality(@Nullable Cardinality cardinality) {
		return new ReflectedReferenceSchema(
			this.getName(),
			this.getNameVariants(),
			this.descriptionInherited ? null : this.getDescription(),
			this.deprecatedInherited ? null : this.getDeprecationNotice(),
			cardinality,
			this.getReferencedEntityType(),
			this.reflectedReferenceName,
			this.indexedInherited ? null : this.isIndexed(),
			this.facetedInherited ? null : this.isFaceted(),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getAttributes() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getAttributes(),
					attributeName -> this.reflectedReference.getAttribute(attributeName).isPresent()
				),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getSortableAttributeCompounds() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getSortableAttributeCompounds(),
					sortableAttributeCompoundName -> this.reflectedReference.getSortableAttributeCompound(sortableAttributeCompoundName).isPresent()
				),
			this.attributesInherited,
			this.attributesExcludedFromInheritance,
			this.reflectedReference
		);
	}

	/**
	 * Creates a copy of this instance with different settings for:
	 *
	 * @param indexed new value of indexed property
	 * @return copy of the schema with applied changes
	 */
	@Nonnull
	public ReflectedReferenceSchemaContract withIndexed(@Nullable Boolean indexed) {
		return new ReflectedReferenceSchema(
			this.getName(),
			this.getNameVariants(),
			this.descriptionInherited ? null : this.getDescription(),
			this.deprecatedInherited ? null : this.getDeprecationNotice(),
			this.cardinalityInherited ? null : this.getCardinality(),
			this.getReferencedEntityType(),
			this.reflectedReferenceName,
			indexed,
			this.facetedInherited ? null : this.isFaceted(),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getAttributes() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getAttributes(),
					attributeName -> this.reflectedReference.getAttribute(attributeName).isPresent()
				),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getSortableAttributeCompounds() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getSortableAttributeCompounds(),
					sortableAttributeCompoundName -> this.reflectedReference.getSortableAttributeCompound(sortableAttributeCompoundName).isPresent()
				),
			this.attributesInherited,
			this.attributesExcludedFromInheritance,
			this.reflectedReference
		);
	}

	/**
	 * Creates a copy of this instance with different settings for:
	 *
	 * @param faceted new value of faceted property
	 * @return copy of the schema with applied changes
	 */
	@Nonnull
	public ReflectedReferenceSchemaContract withFaceted(@Nullable Boolean faceted) {
		return new ReflectedReferenceSchema(
			this.getName(),
			this.getNameVariants(),
			this.descriptionInherited ? null : this.getDescription(),
			this.deprecatedInherited ? null : this.getDeprecationNotice(),
			this.cardinalityInherited ? null : this.getCardinality(),
			this.getReferencedEntityType(),
			this.reflectedReferenceName,
			this.indexedInherited ? null : this.isIndexed(),
			faceted,
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getAttributes() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getAttributes(),
					attributeName -> this.reflectedReference.getAttribute(attributeName).isPresent()
				),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getSortableAttributeCompounds() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getSortableAttributeCompounds(),
					sortableAttributeCompoundName -> this.reflectedReference.getSortableAttributeCompound(sortableAttributeCompoundName).isPresent()
				),
			this.attributesInherited,
			this.attributesExcludedFromInheritance,
			this.reflectedReference
		);
	}

	/**
	 * Creates a copy of this instance with different settings for:
	 *
	 * @param attributesInherited               attribute inheritance property
	 * @param attributesExcludedFromInheritance set of attributes excluded from inheritance
	 * @return copy of the schema with applied changes
	 */
	@Nonnull
	public ReflectedReferenceSchemaContract withAttributeInheritance(
		boolean attributesInherited,
		@Nonnull String... attributesExcludedFromInheritance
	) {
		return new ReflectedReferenceSchema(
			this.getName(),
			this.getNameVariants(),
			this.descriptionInherited ? null : this.getDescription(),
			this.deprecatedInherited ? null : this.getDeprecationNotice(),
			this.cardinalityInherited ? null : this.getCardinality(),
			this.getReferencedEntityType(),
			this.reflectedReferenceName,
			this.indexedInherited ? null : this.isIndexed(),
			this.facetedInherited ? null : this.isFaceted(),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getAttributes() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getAttributes(),
					attributeName -> this.reflectedReference.getAttribute(attributeName).isPresent()
				),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getSortableAttributeCompounds() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getSortableAttributeCompounds(),
					sortableAttributeCompoundName -> this.reflectedReference.getSortableAttributeCompound(sortableAttributeCompoundName).isPresent()
				),
			attributesInherited,
			attributesExcludedFromInheritance,
			this.reflectedReference
		);
	}

	/**
	 * Creates a copy of this instance with different settings for:
	 *
	 * @param newlyDeclaredAttributes new set of attributes declared on reflected reference itself
	 * @return copy of the schema with applied changes
	 */
	@Nonnull
	public ReflectedReferenceSchemaContract withDeclaredAttributes(
		@Nonnull Map<String, AttributeSchemaContract> newlyDeclaredAttributes
	) {
		return new ReflectedReferenceSchema(
			this.getName(),
			this.getNameVariants(),
			this.descriptionInherited ? null : this.getDescription(),
			this.deprecatedInherited ? null : this.getDeprecationNotice(),
			this.cardinalityInherited ? null : this.getCardinality(),
			this.getReferencedEntityType(),
			this.reflectedReferenceName,
			this.indexedInherited ? null : this.isIndexed(),
			this.facetedInherited ? null : this.isFaceted(),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				newlyDeclaredAttributes :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				union(
					newlyDeclaredAttributes,
					this.reflectedReference.getAttributes(),
					this.attributesExcludedFromInheritance
				),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getSortableAttributeCompounds() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getSortableAttributeCompounds(),
					sortableAttributeCompoundName -> this.reflectedReference.getSortableAttributeCompound(sortableAttributeCompoundName).isPresent()
				),
			attributesInherited,
			attributesExcludedFromInheritance,
			this.reflectedReference
		);
	}

	/**
	 * Creates a copy of this instance with different settings for:
	 *
	 * @param newlyDeclaredSortableAttributeCompounds new set of sortable attribute compounds declared on reflected reference itself
	 * @return copy of the schema with applied changes
	 */
	@Nonnull
	public ReflectedReferenceSchemaContract withDeclaredSortableAttributeCompounds(
		@Nonnull Map<String, SortableAttributeCompoundSchemaContract> newlyDeclaredSortableAttributeCompounds
	) {
		return new ReflectedReferenceSchema(
			this.getName(),
			this.getNameVariants(),
			this.descriptionInherited ? null : this.getDescription(),
			this.deprecatedInherited ? null : this.getDeprecationNotice(),
			this.cardinalityInherited ? null : this.getCardinality(),
			this.getReferencedEntityType(),
			this.reflectedReferenceName,
			this.indexedInherited ? null : this.isIndexed(),
			this.facetedInherited ? null : this.isFaceted(),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getAttributes() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getAttributes(),
					attributeName -> this.reflectedReference.getAttribute(attributeName).isPresent()
				),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				newlyDeclaredSortableAttributeCompounds :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				union(
					newlyDeclaredSortableAttributeCompounds,
					this.reflectedReference.getSortableAttributeCompounds(),
					this.attributesExcludedFromInheritance
				),
			attributesInherited,
			attributesExcludedFromInheritance,
			this.reflectedReference
		);
	}

	@Nonnull
	@Override
	public ReferenceSchemaContract withUpdatedReferencedEntityType(@Nonnull String newReferencedEntityType) {
		return new ReflectedReferenceSchema(
			this.getName(),
			this.getNameVariants(),
			this.descriptionInherited ? null : this.getDescription(),
			this.deprecatedInherited ? null : this.getDeprecationNotice(),
			this.cardinalityInherited ? null : this.getCardinality(),
			newReferencedEntityType,
			this.reflectedReferenceName,
			this.indexedInherited ? null : this.isIndexed(),
			this.facetedInherited ? null : this.isFaceted(),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getAttributes() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getAttributes(),
					attributeName -> this.reflectedReference.getAttribute(attributeName).isPresent()
				),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getSortableAttributeCompounds() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getSortableAttributeCompounds(),
					sortableAttributeCompoundName -> this.reflectedReference.getSortableAttributeCompound(sortableAttributeCompoundName).isPresent()
				),
			attributesInherited,
			attributesExcludedFromInheritance,
			this.reflectedReference
		);
	}

	@Nonnull
	@Override
	public ReferenceSchemaContract withUpdatedReferencedGroupType(@Nonnull String newReferencedGroupType) {
		throw new GenericEvitaInternalError(
			"Referenced group type cannot be modified on reflected reference schema!"
		);
	}

	/**
	 * Creates a copy of this instance with different settings for:
	 *
	 * @param reflectedReference the schema this schema reflects
	 * @return copy of the schema with applied changes
	 */
	@Nonnull
	public ReflectedReferenceSchema withReferencedSchema(@Nonnull ReferenceSchemaContract reflectedReference) {
		return new ReflectedReferenceSchema(
			this.getName(),
			this.getNameVariants(),
			this.descriptionInherited ? null : this.getDescription(),
			this.deprecatedInherited ? null : this.getDeprecationNotice(),
			this.cardinalityInherited ? null : this.getCardinality(),
			this.getReferencedEntityType(),
			this.reflectedReferenceName,
			this.indexedInherited ? null : this.isIndexed(),
			this.facetedInherited ? null : this.isFaceted(),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getAttributes() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getAttributes(),
					attributeName -> this.reflectedReference.getAttribute(attributeName).isPresent()
				),
			this.reflectedReference == null ?
				// when reflected reference is not present, only attributes unique for reflected ones are present
				super.getSortableAttributeCompounds() :
				// if the reflected reference is present, attributes are merged using union function and we need to
				// filter them out to leave attributes added on the reflected reference only
				intersect(
					this.getSortableAttributeCompounds(),
					sortableAttributeCompoundName -> this.reflectedReference.getSortableAttributeCompound(sortableAttributeCompoundName).isPresent()
				),
			this.attributesInherited,
			this.attributesExcludedFromInheritance,
			reflectedReference
		);
	}

	/**
	 * Returns true if the internal field to the reflected reference is not empty.
	 * @return true if the reflected reference is available
	 */
	public boolean isReflectedReferenceAvailable() {
		return this.reflectedReference != null;
	}

	@Override
	public void validate(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchema entitySchema) throws SchemaAlteringException {
		final String referencedEntityType = this.getReferencedEntityType();
		final Optional<EntitySchemaContract> referencedEntityTypeSchema = catalogSchema.getEntitySchema(referencedEntityType);
		Stream<String> referenceErrors = Stream.empty();
		if (referencedEntityTypeSchema.isEmpty()) {
			referenceErrors = Stream.concat(
				referenceErrors,
				Stream.of("Referenced entity type `" + referencedEntityType + "` is not present in catalog `" + catalogSchema.getName() + "` schema!"));
		}
		final Optional<ReferenceSchemaContract> originalReference = referencedEntityTypeSchema.flatMap(it -> it.getReference(this.reflectedReferenceName));
		if (originalReference.isEmpty()) {
			referenceErrors = Stream.concat(
				referenceErrors,
				Stream.of(
					"Referenced entity type `" + referencedEntityType + "` " +
						"doesn't contain reference `" + this.reflectedReferenceName + "`, " +
						"which is reflected in reference `" + this.getName() + "`!")
			);
		} else if (!originalReference.get().getReferencedEntityType().equals(entitySchema.getName())) {
			referenceErrors = Stream.concat(
				referenceErrors,
				Stream.of(
					"Referenced entity type `" + referencedEntityType + "` " +
						"contains reference `" + this.reflectedReferenceName + "`, " +
						"but it targets different entity type `" + originalReference.get().getReferencedEntityType() +  "` (expected `" + entitySchema.getName() + "`)!")
			);
		} else if (!originalReference.get().isReferencedEntityTypeManaged()) {
			referenceErrors = Stream.concat(
				referenceErrors,
				Stream.of(
					"Referenced entity type `" + referencedEntityType + "` " +
						"contains reference `" + this.reflectedReferenceName + "`, " +
						"but it's not managed entity type!")
			);
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

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		if (!super.equals(o)) return false;

		ReflectedReferenceSchema that = (ReflectedReferenceSchema) o;
		return descriptionInherited == that.descriptionInherited && deprecatedInherited == that.deprecatedInherited && cardinalityInherited == that.cardinalityInherited && indexedInherited == that.indexedInherited && facetedInherited == that.facetedInherited && attributesInherited == that.attributesInherited && reflectedReferenceName.equals(that.reflectedReferenceName) && Arrays.equals(attributesExcludedFromInheritance, that.attributesExcludedFromInheritance);
	}

	@Override
	public int hashCode() {
		int result = super.hashCode();
		result = 31 * result + reflectedReferenceName.hashCode();
		result = 31 * result + Boolean.hashCode(descriptionInherited);
		result = 31 * result + Boolean.hashCode(deprecatedInherited);
		result = 31 * result + Boolean.hashCode(cardinalityInherited);
		result = 31 * result + Boolean.hashCode(indexedInherited);
		result = 31 * result + Boolean.hashCode(facetedInherited);
		result = 31 * result + Boolean.hashCode(attributesInherited);
		result = 31 * result + Arrays.hashCode(attributesExcludedFromInheritance);
		return result;
	}

	/**
	 * Verifies that the attributes of the reflected reference are available.
	 */
	private void assertAttributes() {
		Assert.isTrue(
			!this.attributesInherited || this.reflectedReference != null,
			"Attributes of the reflected reference are inherited from the target reference, but the reflected reference is not available!"
		);
	}
}
