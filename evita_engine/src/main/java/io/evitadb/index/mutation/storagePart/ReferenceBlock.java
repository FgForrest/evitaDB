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

package io.evitadb.index.mutation.storagePart;


import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaDecorator;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.index.bitmap.RoaringBitmapBackedBitmap.buildWriter;
import static java.util.Optional.of;

/**
 * The ReferenceBlock class contains all the key logic for accessing all referenced primary keys that relate to
 * (reflected) references of the entity being updated. It also provides a supplier for all attribute mutations that
 * needs to be applied when the counterpart references are created (if any). It also provides a set of missing mandated
 * (non-nullable) attributes that are not present in the reflected reference schema for creating error messages.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class ReferenceBlock {
	/**
	 * The bitmap of all referenced entity primary keys.
	 */
	@Getter private final RoaringBitmap referencedPrimaryKeys;
	/**
	 * The supplier of all attribute mutations that needs to be applied when the counterpart references are created.
	 */
	@Getter private final Supplier<ReferenceAttributeMutation[]> attributeSupplier;
	/**
	 * The set of missing mandated attributes.
	 */
	private Set<Object> missingMandatedAttributes;

	/**
	 * Constructs an instance of {@code ReferenceBlock} by initializing the reference primary keys and
	 * attribute supplier based on the provided parameters.
	 *
	 * @param catalogSchema          The catalog schema containing the entity schema definitions.
	 * @param locales                The set of locales for localized attributes processing.
	 * @param localReferenceSchema   The reference schema defining the reference attributes.
	 * @param attributeValueProvider The provider for fetching reference attribute values.
	 */
	public <T> ReferenceBlock(
		@Nonnull CatalogSchema catalogSchema,
		@Nonnull Set<Locale> locales,
		@Nonnull ReferenceSchema localReferenceSchema,
		@Nonnull ReflectedReferenceAttributeValueProvider<T> attributeValueProvider
	) {
		// first build the RoaringBitmap with all referenced primary keys
		final RoaringBitmapWriter<RoaringBitmap> writer = buildWriter();
		attributeValueProvider.getReferenceCarriers()
			.mapToInt(attributeValueProvider::getReferencedEntityPrimaryKey)
			.forEach(writer::add);
		this.referencedPrimaryKeys = writer.get();

		// now build the attribute supplier for all attribute mutations that needs to be applied
		final Optional<ReferenceSchema> theOtherReferenceSchema;
		final Optional<Set<String>> inheritedAttributes;

		// find reflected schema definition (if any)
		if (localReferenceSchema instanceof ReflectedReferenceSchema rrs) {
			theOtherReferenceSchema = catalogSchema.getEntitySchema(localReferenceSchema.getReferencedEntityType())
				.flatMap(it -> it.getReference(rrs.getReflectedReferenceName()))
				.map(ReferenceSchema.class::cast);
			inheritedAttributes = of(rrs.getInheritedAttributes());
		} else {
			final Optional<ReflectedReferenceSchema> rrs = catalogSchema.getEntitySchema(localReferenceSchema.getReferencedEntityType())
				.flatMap(it -> ((EntitySchemaDecorator) it).getDelegate().getReflectedReferenceFor(localReferenceSchema.getName()));
			theOtherReferenceSchema = rrs.map(ReferenceSchema.class::cast);
			inheritedAttributes = rrs.map(ReflectedReferenceSchema::getInheritedAttributes);
		}

		// if the target entity and reference schema exists, set-up all reflected references
		this.attributeSupplier = theOtherReferenceSchema
			.map(
				referencedEntitySchema ->
					(Supplier<ReferenceAttributeMutation[]>) () ->
						attributeValueProvider.getReferenceCarriers()
							.flatMap(
								reference ->
									// for each reference attribute schema
									attributeValueProvider.getAttributeSchemas(
											localReferenceSchema, referencedEntitySchema, inheritedAttributes.get()
										)
										.flatMap(
											attributeSchema -> getReferenceAttributeMutationStream(
												locales, attributeValueProvider, referencedEntitySchema,
												reference, attributeSchema, inheritedAttributes.get()
											)
										)
							).toArray(ReferenceAttributeMutation[]::new)
			)
			// if the other reference schema does not (yet) exist, return an empty array
			.orElse(() -> new ReferenceAttributeMutation[0]);
	}

	/**
	 * Retrieves a set of attributes that are mandated but currently missing.
	 *
	 * @return a set of missing mandated attributes. If no attributes are missing, an empty set is returned.
	 */
	@Nonnull
	public Set<Object> getMissingMandatedAttributes() {
		return missingMandatedAttributes == null ? Collections.emptySet() : missingMandatedAttributes;
	}

	/**
	 * Generates a stream of reference attribute mutations based on the provided locales, attribute value provider,
	 * referenced entity schema, reference, attribute schema, and inherited attributes.
	 *
	 * @param locales the set of all locales known to be used by created entity
	 * @param attributeValueProvider the provider for fetching reference attribute values
	 * @param referencedEntitySchema the schema defining the particular reference of the referenced entity
	 * @param reference the reference object for which attributes should be resolved
	 * @param attributeSchema the schema defining the attribute to be mutated
	 * @param inheritedAttributes the set of attribute names that are inherited
	 *
	 * @return a stream of reference attribute mutations
	 */
	@Nonnull
	private <T> Stream<ReferenceAttributeMutation> getReferenceAttributeMutationStream(
		@Nonnull Set<Locale> locales,
		@Nonnull ReflectedReferenceAttributeValueProvider<T> attributeValueProvider,
		@Nonnull ReferenceSchema referencedEntitySchema,
		@Nonnull T reference,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<String> inheritedAttributes
	) {
		// is the attribute inherited?
		final boolean inherited = inheritedAttributes.contains(attributeSchema.getName());
		// if so, retrieve the attribute values from the provider
		final Collection<AttributeValue> attributeValues;
		if (inherited) {
			attributeValues = attributeValueProvider.getAttributeValues(
				referencedEntitySchema, reference, attributeSchema.getName()
			);
		} else {
			attributeValues = Collections.emptyList();
		}

		// if the attribute has a default value
		final Serializable defaultValue = attributeSchema.getDefaultValue();
		if (defaultValue != null) {
			// function that primarily returns inherited value, if not present, returns default value
			final Function<AttributeKey, Serializable> valueLookup = attributeKey ->
				attributeValues.stream()
					.filter(attVal -> attributeKey.equals(attVal.key()))
					.map(AttributeValue::value)
					.findFirst()
					.orElse(defaultValue);
			// if the attribute is localized
			if (attributeSchema.isLocalized()) {
				// set-up a stream of reference attribute mutations for each locale
				return locales.stream()
					.map(locale -> {
						final AttributeKey attributeKey = new AttributeKey(attributeSchema.getName(), locale);
						return new ReferenceAttributeMutation(
							attributeValueProvider.getReferenceKey(referencedEntitySchema, reference),
							new UpsertAttributeMutation(
								attributeKey,
								valueLookup.apply(attributeKey)
							)
						);
					});
			} else {
				// set-up a stream with single reference attribute mutation
				final AttributeKey attributeKey = new AttributeKey(attributeSchema.getName());
				return Stream.of(
					new ReferenceAttributeMutation(
						attributeValueProvider.getReferenceKey(referencedEntitySchema, reference),
						new UpsertAttributeMutation(
							attributeKey,
							valueLookup.apply(attributeKey)
						)
					)
				);
			}
		} else {
			// function that primarily returns inherited value, or null if not present
			final Function<AttributeKey, Serializable> valueLookup = attributeKey ->
				attributeValues.stream()
					.filter(attVal -> attributeKey.equals(attVal.key()))
					.map(AttributeValue::value)
					.findFirst()
					.orElse(null);
			if (attributeSchema.isLocalized()) {
				// set-up a stream of reference attribute mutations for each locale
				return locales
					.stream()
					.map(locale -> {
						final AttributeKey attributeKey = new AttributeKey(attributeSchema.getName(), locale);
						final Serializable value = valueLookup.apply(attributeKey);
						if (value == null && !attributeSchema.isNullable()) {
							// if the value is missing and the attribute is not nullable, add it to the missing set
							getMissingMandatedAttributesForAdding().add(attributeKey);
							return null;
						} else {
							// otherwise, return a single reference attribute mutation
							return new ReferenceAttributeMutation(
								attributeValueProvider.getReferenceKey(referencedEntitySchema, reference),
								new UpsertAttributeMutation(attributeKey, value)
							);
						}
					})
					.filter(Objects::nonNull);
			} else {
				// set-up a stream with single reference attribute mutation
				final AttributeKey attributeKey = new AttributeKey(attributeSchema.getName());
				final Serializable value = valueLookup.apply(attributeKey);
				if (value == null && !attributeSchema.isNullable()) {
					// if the value is missing and the attribute is not nullable, add it to the missing set
					getMissingMandatedAttributesForAdding().add(attributeKey);
					return Stream.empty();
				} else {
					// otherwise, return a single reference attribute mutation
					return Stream.of(
						new ReferenceAttributeMutation(
							attributeValueProvider.getReferenceKey(referencedEntitySchema, reference),
							new UpsertAttributeMutation(attributeKey, value)
						)
					);
				}
			}
		}
	}

	/**
	 * Retrieves a set of missing mandated attributes that need to be added.
	 *
	 * @return a set of missing mandated attributes. If the set is uninitialized, it creates and returns a new set.
	 */
	@Nonnull
	private Set<Object> getMissingMandatedAttributesForAdding() {
		if (this.missingMandatedAttributes == null) {
			this.missingMandatedAttributes = CollectionUtils.createHashSet(16);
		}
		return this.missingMandatedAttributes;
	}
}