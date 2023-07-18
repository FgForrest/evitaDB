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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.requestResponse.data.AttributesEditor.AttributesBuilder;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * Class supports intermediate mutable object that allows {@link Attributes} container rebuilding.
 * We need to closely monitor what attribute is changed and how. These changes are wrapped in so called mutations
 * (see {@link AttributeMutation} and its implementations) and mutations can be then processed transactionally by
 * the engine.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ExistingAttributesBuilder implements AttributesBuilder {
	@Serial private static final long serialVersionUID = 3382748927871753611L;

	/**
	 * Definition of the entity schema.
	 */
	private final EntitySchemaContract entitySchema;
	/**
	 * Definition of the reference schema.
	 */
	private final ReferenceSchemaContract referenceSchema;
	/**
	 * Initial set of attributes that is going to be modified by this builder.
	 */
	private final Attributes baseAttributes;
	/**
	 * When this flag is set to true - verification on store is suppressed. It can be set to true only when verification
	 * is ensured by calling logic.
	 */
	private final boolean suppressVerification;
	/**
	 * Contains locale insensitive attribute values - simple key → value association map.
	 */
	private final Map<AttributeKey, AttributeMutation> attributeMutations;
	/**
	 * This predicate filters out attributes that were not fetched in query.
	 */
	private final SerializablePredicate<AttributeValue> attributePredicate;

	/**
	 * AttributesBuilder constructor that will be used for building brand new {@link Attributes} container.
	 */
	public ExistingAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull Attributes baseAttributes
	) {
		this.entitySchema = entitySchema;
		this.referenceSchema = referenceSchema;
		this.attributeMutations = new HashMap<>();
		this.baseAttributes = baseAttributes;
		this.suppressVerification = false;
		this.attributePredicate = Droppable::exists;
	}

	/**
	 * AttributesBuilder constructor that will be used for building brand new {@link Attributes} container.
	 */
	public ExistingAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull Collection<AttributeValue> attributes,
		@Nonnull Map<String, AttributeSchemaContract> attributeTypes
	) {
		this.entitySchema = entitySchema;
		this.referenceSchema = referenceSchema;
		this.attributeMutations = new HashMap<>();
		this.baseAttributes = new Attributes(entitySchema, referenceSchema, attributes, attributeTypes);
		this.suppressVerification = false;
		this.attributePredicate = Droppable::exists;
	}

	/**
	 * AttributesBuilder constructor that will be used for building brand new {@link Attributes} container.
	 */
	public ExistingAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull Attributes baseAttributes,
		@Nonnull SerializablePredicate<AttributeValue> attributePredicate
	) {
		this.entitySchema = entitySchema;
		this.referenceSchema = referenceSchema;
		this.attributeMutations = new HashMap<>();
		this.baseAttributes = baseAttributes;
		this.suppressVerification = false;
		this.attributePredicate = attributePredicate;
	}

	/**
	 * AttributesBuilder constructor that will be used for building brand new {@link Attributes} container.
	 */
	ExistingAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull Collection<AttributeValue> attributes,
		@Nonnull Map<String, AttributeSchemaContract> attributeTypes,
		boolean suppressVerification
	) {
		this.entitySchema = entitySchema;
		this.referenceSchema = referenceSchema;
		this.attributeMutations = new HashMap<>();
		this.baseAttributes = new Attributes(entitySchema, referenceSchema, attributes, attributeTypes);
		this.suppressVerification = suppressVerification;
		this.attributePredicate = Droppable::exists;
	}

	/**
	 * AttributesBuilder constructor that will be used for building brand new {@link Attributes} container.
	 */
	ExistingAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull Attributes baseAttributes,
		boolean suppressVerification
	) {
		this.entitySchema = entitySchema;
		this.referenceSchema = referenceSchema;
		this.attributeMutations = new HashMap<>();
		this.baseAttributes = baseAttributes;
		this.suppressVerification = suppressVerification;
		this.attributePredicate = Droppable::exists;
	}

	/**
	 * Method allows adding specific mutation on the fly.
	 */
	@Nonnull
	public ExistingAttributesBuilder addMutation(@Nonnull AttributeMutation localMutation) {
		if (localMutation instanceof UpsertAttributeMutation upsertAttributeMutation) {
			final AttributeKey attributeKey = upsertAttributeMutation.getAttributeKey();
			final Serializable attributeValue = upsertAttributeMutation.getAttributeValue();
			if (!suppressVerification) {
				InitialAttributesBuilder.verifyAttributeIsInSchemaAndTypeMatch(
					baseAttributes.entitySchema,
					attributeKey.attributeName(), attributeValue.getClass(), attributeKey.locale()
				);
			}

			this.attributeMutations.put(attributeKey, upsertAttributeMutation);
		} else if (localMutation instanceof RemoveAttributeMutation removeAttributeMutation) {
			final AttributeKey attributeKey = removeAttributeMutation.getAttributeKey();
			verifyAttributeExists(attributeKey);
			if (this.baseAttributes.getAttributeValueWithoutSchemaCheck(attributeKey).isEmpty()) {
				this.attributeMutations.remove(attributeKey);
			} else {
				this.attributeMutations.put(attributeKey, removeAttributeMutation);
			}
		} else if (localMutation instanceof ApplyDeltaAttributeMutation<?> applyDeltaAttributeMutation) {
			final AttributeKey attributeKey = applyDeltaAttributeMutation.getAttributeKey();
			final AttributeValue attributeValue = this.baseAttributes.getAttributeValueWithoutSchemaCheck(attributeKey)
				.map(
					it -> ofNullable(this.attributeMutations.get(attributeKey))
						.map(x -> x.mutateLocal(entitySchema, it))
						.orElse(it)
				)
				.orElseGet(
					() -> ofNullable(this.attributeMutations.get(attributeKey))
						.map(x -> x.mutateLocal(entitySchema, null))
						.orElseThrow(() -> new EvitaInvalidUsageException("Attribute with name `" + attributeKey + "` doesn't exist!"))
				);

			final AttributeValue updatedValue = applyDeltaAttributeMutation.mutateLocal(entitySchema, attributeValue);
			if (attributeMutations.get(attributeKey) == null) {
				this.attributeMutations.put(attributeKey, applyDeltaAttributeMutation);
			} else {
				this.attributeMutations.put(attributeKey, new UpsertAttributeMutation(attributeKey, Objects.requireNonNull(updatedValue.value())));
			}
		} else {
			throw new EvitaInternalError("Unknown Evita price mutation: `" + localMutation.getClass() + "`!");
		}
		return this;
	}

	@Nonnull
	@Override
	public Attributes build() {
		if (isThereAnyChangeInMutations()) {
			final Collection<AttributeValue> newAttributeValues = getAttributeValuesWithoutPredicate().collect(Collectors.toList());
			final Map<String, AttributeSchemaContract> newAttributeTypes = Stream.concat(
					baseAttributes.attributeTypes.values().stream(),
					// we don't check baseAttributes.allowUnknownAttributeTypes here because it gets checked on adding a mutation
					newAttributeValues
						.stream()
						// filter out new attributes that has no type yet
						.filter(it -> !baseAttributes.attributeTypes.containsKey(it.key().attributeName()))
						// create definition for them on the fly
						.map(AttributesBuilder::createImplicitSchema)
				)
				.collect(
					Collectors.toUnmodifiableMap(
						AttributeSchemaContract::getName,
						Function.identity(),
						(attributeSchema, attributeSchema2) -> {
							Assert.isTrue(
								attributeSchema.equals(attributeSchema2),
								"Attribute " + attributeSchema.getName() + " has incompatible types in the same entity!"
							);
							return attributeSchema;
						}
					)
				);
			return new Attributes(
				baseAttributes.entitySchema,
				baseAttributes.referenceSchema,
				newAttributeValues,
				newAttributeTypes
			);
		} else {
			return baseAttributes;
		}
	}

	@Override
	@Nonnull
	public ExistingAttributesBuilder removeAttribute(@Nonnull String attributeName) {
		final AttributeKey attributeKey = new AttributeKey(attributeName);
		verifyAttributeExists(attributeKey);
		attributeMutations.put(
			attributeKey,
			new RemoveAttributeMutation(attributeKey)
		);
		return this;
	}

	@Override
	@Nonnull
	public <T extends Serializable> ExistingAttributesBuilder setAttribute(@Nonnull String attributeName, @Nullable T attributeValue) {
		if (attributeValue == null) {
			return removeAttribute(attributeName);
		} else {
			final AttributeKey attributeKey = new AttributeKey(attributeName);
			if (!suppressVerification) {
				InitialAttributesBuilder.verifyAttributeIsInSchemaAndTypeMatch(baseAttributes.entitySchema, attributeName, attributeValue.getClass());
			}
			attributeMutations.put(
				attributeKey,
				new UpsertAttributeMutation(attributeKey, attributeValue)
			);
			return this;
		}
	}

	@Override
	@Nonnull
	public <T extends Serializable> ExistingAttributesBuilder setAttribute(@Nonnull String attributeName, @Nullable T[] attributeValue) {
		if (attributeValue == null) {
			return removeAttribute(attributeName);
		} else {
			final AttributeKey attributeKey = new AttributeKey(attributeName);
			if (!suppressVerification) {
				InitialAttributesBuilder.verifyAttributeIsInSchemaAndTypeMatch(baseAttributes.entitySchema, attributeName, attributeValue.getClass());
			}
			attributeMutations.put(
				attributeKey,
				new UpsertAttributeMutation(attributeKey, attributeValue)
			);
			return this;
		}
	}

	@Override
	@Nonnull
	public ExistingAttributesBuilder removeAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		final AttributeKey attributeKey = new AttributeKey(attributeName, locale);
		verifyAttributeExists(attributeKey);
		attributeMutations.put(
			attributeKey,
			new RemoveAttributeMutation(attributeKey)
		);
		return this;
	}

	@Override
	@Nonnull
	public <T extends Serializable> ExistingAttributesBuilder setAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T attributeValue) {
		if (attributeValue == null) {
			return removeAttribute(attributeName, locale);
		} else {
			final AttributeKey attributeKey = new AttributeKey(attributeName, locale);
			if (!suppressVerification) {
				InitialAttributesBuilder.verifyAttributeIsInSchemaAndTypeMatch(baseAttributes.entitySchema, attributeName, attributeValue.getClass(), locale);
			}
			attributeMutations.put(
				attributeKey,
				new UpsertAttributeMutation(attributeKey, attributeValue)
			);
			return this;
		}
	}

	@Override
	@Nonnull
	public <T extends Serializable> ExistingAttributesBuilder setAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T[] attributeValue) {
		if (attributeValue == null) {
			return removeAttribute(attributeName, locale);
		} else {
			final AttributeKey attributeKey = new AttributeKey(attributeName, locale);
			if (!suppressVerification) {
				InitialAttributesBuilder.verifyAttributeIsInSchemaAndTypeMatch(baseAttributes.entitySchema, attributeName, attributeValue.getClass(), locale);
			}
			attributeMutations.put(
				attributeKey,
				new UpsertAttributeMutation(attributeKey, attributeValue)
			);
			return this;
		}
	}

	@Nonnull
	@Override
	public ExistingAttributesBuilder mutateAttribute(@Nonnull AttributeMutation mutation) {
		attributeMutations.put(mutation.getAttributeKey(), mutation);
		return this;
	}

	@Override
	public boolean attributesAvailable() {
		return this.baseAttributes.attributesAvailable();
	}

	@Override
	@Nullable
	public <T extends Serializable> T getAttribute(@Nonnull String attributeName) {
		//noinspection unchecked
		return (T) getAttributeValueInternal(new AttributeKey(attributeName))
			.map(AttributeValue::value)
			.orElse(null);
	}


	@Override
	@Nullable
	public <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName) {
		//noinspection unchecked
		return (T[]) getAttributeValueInternal(new AttributeKey(attributeName))
			.map(AttributeValue::value)
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName) {
		return getAttributeValueInternal(new AttributeKey(attributeName));
	}

	@Override
	@Nullable
	public <T extends Serializable> T getAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		//noinspection unchecked
		return (T) getAttributeValueInternal(new AttributeKey(attributeName, locale))
			.map(AttributeValue::value)
			.orElse(null);
	}

	@Override
	@Nullable
	public <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName, @Nonnull Locale locale) {
		//noinspection unchecked
		return (T[]) getAttributeValueInternal(new AttributeKey(attributeName, locale))
			.map(AttributeValue::value)
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName, @Nonnull Locale locale) {
		return getAttributeValueInternal(new AttributeKey(attributeName, locale));
	}

	@Nonnull
	@Override
	public Optional<AttributeSchemaContract> getAttributeSchema(@Nonnull String attributeName) {
		return baseAttributes.getAttributeSchema(attributeName);
	}

	@Nonnull
	@Override
	public Set<String> getAttributeNames() {
		return getAttributeValues()
			.stream()
			.filter(attributePredicate)
			.map(it -> it.key().attributeName())
			.collect(Collectors.toSet());
	}

	@Nonnull
	@Override
	public Set<AttributeKey> getAttributeKeys() {
		return getAttributeValues()
			.stream()
			.map(AttributeValue::key)
			.collect(Collectors.toSet());
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull AttributeKey attributeKey) {
		return getAttributeValueInternal(attributeKey)
			.or(() -> attributeKey.localized() ?
				getAttributeValueInternal(new AttributeKey(attributeKey.attributeName())) :
				empty()
			);
	}

	@Override
	@Nonnull
	public Collection<AttributeValue> getAttributeValues() {
		return getAttributeValuesWithoutPredicate()
			.filter(attributePredicate)
			.collect(Collectors.toList());
	}

	@Nonnull
	@Override
	public Collection<AttributeValue> getAttributeValues(@Nonnull String attributeName) {
		return getAttributeValues()
			.stream()
			.filter(it -> attributeName.equals(it.key().attributeName()))
			.collect(Collectors.toList());
	}

	@Override
	@Nonnull
	public Set<Locale> getAttributeLocales() {
		// this is quite expensive, but should not be called frequently
		return getAttributeValues()
			.stream()
			.map(it -> it.key().locale())
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
	}

	/**
	 * Method returns true if the passed attributes are not the same as internally held one.
	 * Passed attributes are expected to be output of the {@link #build()} method so that this method allows to verify
	 * whether anything in the attributes was changed.
	 */
	public boolean differs(@Nonnull Attributes attributes) {
		return this.baseAttributes != attributes;
	}

	@Nonnull
	@Override
	public Stream<? extends AttributeMutation> buildChangeSet() {
		final Map<AttributeKey, AttributeValue> builtAttributes = new HashMap<>(baseAttributes.attributeValues);
		return attributeMutations.values()
			.stream()
			.filter(it -> {
				final AttributeValue existingValue = builtAttributes.get(it.getAttributeKey());
				final AttributeValue newAttribute = it.mutateLocal(entitySchema, existingValue);
				builtAttributes.put(it.getAttributeKey(), newAttribute);
				return existingValue == null || newAttribute.version() > existingValue.version();
			});
	}

	/**
	 * Returns true if there is single mutation in the local mutations.
	 */
	boolean isThereAnyChangeInMutations() {
		return Stream.concat(
				// process all original attribute values - they will be: either kept intact if there is no mutation
				// or mutated by the mutation - i.e. updated or removed
				baseAttributes.attributeValues
					.entrySet()
					.stream()
					// use old attribute, or apply mutation on the attribute and return the mutated attribute
					.map(it -> ofNullable(attributeMutations.get(it.getKey()))
						.map(mutation -> {
							final AttributeValue originValue = it.getValue();
							final AttributeValue mutatedAttribute = mutation.mutateLocal(entitySchema, originValue);
							return mutatedAttribute.differsFrom(originValue);
						})
						.orElse(false)
					),
				// all mutations that doesn't hit existing attribute probably produce new ones
				// we have to process them as well
				attributeMutations
					.values()
					.stream()
					// we want to process only those mutations that have no attribute to mutate in the original set
					.filter(it -> !baseAttributes.attributeValues.containsKey(it.getAttributeKey()))
					// apply mutation
					.map(it -> true)
			)
			.anyMatch(it -> it);
	}

	/**
	 * Method verifies whether the removed attribute exists in the builder (or throws exception).
	 */
	private void verifyAttributeExists(AttributeKey attributeKey) {
		Assert.isTrue(
			baseAttributes.getAttributeValueWithoutSchemaCheck(attributeKey).isPresent() || attributeMutations.get(attributeKey) instanceof UpsertAttributeMutation,
			"Attribute `" + attributeKey + "` doesn't exist!"
		);
	}

	/**
	 * Builds attribute list based on registered mutations and previous state.
	 */
	@Nonnull
	private Stream<AttributeValue> getAttributeValuesWithoutPredicate() {
		return Stream.concat(
			// process all original attribute values - they will be: either kept intact if there is no mutation
			// or mutated by the mutation - i.e. updated or removed
			baseAttributes.attributeValues
				.entrySet()
				.stream()
				// use old attribute, or apply mutation on the attribute and return the mutated attribute
				.map(it -> ofNullable(attributeMutations.get(it.getKey()))
					.map(mutation -> {
						final AttributeValue originValue = it.getValue();
						final AttributeValue mutatedAttribute = mutation.mutateLocal(entitySchema, originValue);
						return mutatedAttribute.differsFrom(originValue) ? mutatedAttribute : originValue;
					})
					.orElse(it.getValue())
				),
			// all mutations that doesn't hit existing attribute probably produce new ones
			// we have to process them as well
			attributeMutations
				.values()
				.stream()
				// we want to process only those mutations that have no attribute to mutate in the original set
				.filter(it -> !baseAttributes.attributeValues.containsKey(it.getAttributeKey()))
				// apply mutation
				.map(it -> it.mutateLocal(entitySchema, null))
		);
	}

	/**
	 * Returns either unchanged attribute value, or attribute value with applied mutation or even new attribute value
	 * that is produced by the mutation.
	 */
	@Nonnull
	private Optional<AttributeValue> getAttributeValueInternal(AttributeKey attributeKey) {
		final Optional<AttributeValue> attributeValue = ofNullable(this.baseAttributes.attributeValues.get(attributeKey))
			.map(it ->
				ofNullable(this.attributeMutations.get(attributeKey))
					.map(mut -> {
						final AttributeValue mutatedValue = mut.mutateLocal(entitySchema, it);
						return mutatedValue.differsFrom(it) ? mutatedValue : it;
					})
					.orElse(it)
			)
			.or(() ->
				ofNullable(this.attributeMutations.get(attributeKey))
					.map(it -> it.mutateLocal(entitySchema, null))
			);
		return attributeValue.filter(attributePredicate);
	}

}
