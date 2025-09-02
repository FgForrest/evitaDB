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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.requestResponse.data.AttributesEditor.AttributesBuilder;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
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
abstract class ExistingAttributesBuilder<S extends AttributeSchemaContract, T extends ExistingAttributesBuilder<S, T>> implements AttributesBuilder<S> {
	@Serial private static final long serialVersionUID = 3382748927871753611L;

	/**
	 * Definition of the entity schema.
	 */
	final EntitySchemaContract entitySchema;
	/**
	 * Initial set of attributes that is going to be modified by this builder.
	 */
	final Attributes<S> baseAttributes;
	/**
	 * When this flag is set to true - verification on store is suppressed. It can be set to true only when verification
	 * is ensured by calling logic.
	 */
	final boolean suppressVerification;
	/**
	 * Contains locale insensitive attribute values - simple key → value association map.
	 */
	final Map<AttributeKey, AttributeMutation> attributeMutations;
	/**
	 * This predicate filters out attributes that were not fetched in query.
	 */
	final SerializablePredicate<AttributeValue> attributePredicate;

	/**
	 * AttributesBuilder constructor that will be used for building brand new {@link Attributes} container.
	 */
	public ExistingAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Attributes<S> baseAttributes
	) {
		this.entitySchema = entitySchema;
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
		@Nonnull Collection<AttributeValue> attributes,
		@Nonnull Map<String, S> attributeTypes
	) {
		this.entitySchema = entitySchema;
		this.attributeMutations = new HashMap<>();
		this.baseAttributes = createAttributesContainer(entitySchema, attributes, attributeTypes);
		this.suppressVerification = false;
		this.attributePredicate = Droppable::exists;
	}

	/**
	 * AttributesBuilder constructor that will be used for building brand new {@link Attributes} container.
	 */
	public ExistingAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Attributes<S> baseAttributes,
		@Nonnull SerializablePredicate<AttributeValue> attributePredicate
	) {
		this.entitySchema = entitySchema;
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
		@Nonnull Collection<AttributeValue> attributes,
		@Nonnull Map<String, S> attributeTypes,
		boolean suppressVerification
	) {
		this.entitySchema = entitySchema;
		this.attributeMutations = new HashMap<>();
		this.baseAttributes = createAttributesContainer(entitySchema, attributes, attributeTypes);
		this.suppressVerification = suppressVerification;
		this.attributePredicate = Droppable::exists;
	}

	/**
	 * AttributesBuilder constructor that will be used for building brand new {@link Attributes} container.
	 */
	ExistingAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Collection<AttributeValue> attributes,
		@Nonnull Map<String, S> attributeTypes,
		boolean suppressVerification,
		@Nonnull Collection<AttributeMutation> attributeMutations
	) {
		this.entitySchema = entitySchema;
		this.attributeMutations = new HashMap<>();
		for (AttributeMutation attributeMutation : attributeMutations) {
			this.attributeMutations.put(attributeMutation.getAttributeKey(), attributeMutation);
		}
		this.baseAttributes = createAttributesContainer(entitySchema, attributes, attributeTypes);
		this.suppressVerification = suppressVerification;
		this.attributePredicate = Droppable::exists;
	}

	/**
	 * AttributesBuilder constructor that will be used for building brand new {@link Attributes} container.
	 */
	ExistingAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Attributes<S> baseAttributes,
		boolean suppressVerification
	) {
		this.entitySchema = entitySchema;
		this.attributeMutations = new HashMap<>();
		this.baseAttributes = baseAttributes;
		this.suppressVerification = suppressVerification;
		this.attributePredicate = Droppable::exists;
	}

	/**
	 * Method allows adding specific mutation on the fly.
	 */
	@Nonnull
	public T addMutation(@Nonnull AttributeMutation localMutation) {
		if (localMutation instanceof UpsertAttributeMutation upsertAttributeMutation) {
			final AttributeKey attributeKey = upsertAttributeMutation.getAttributeKey();
			final Serializable attributeValue = upsertAttributeMutation.getAttributeValue();
			if (!this.suppressVerification) {
				InitialAttributesBuilder.verifyAttributeIsInSchemaAndTypeMatch(
					this.baseAttributes.entitySchema,
					attributeKey.attributeName(), attributeValue.getClass(), attributeKey.locale(), getLocationResolver()
				);
			}

			this.attributeMutations.put(attributeKey, upsertAttributeMutation);
		} else if (localMutation instanceof RemoveAttributeMutation removeAttributeMutation) {
			final AttributeKey attributeKey = removeAttributeMutation.getAttributeKey();
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
						.map(x -> x.mutateLocal(this.entitySchema, it))
						.orElse(it)
				)
				.orElseGet(
					() -> ofNullable(this.attributeMutations.get(attributeKey))
						.map(x -> x.mutateLocal(this.entitySchema, null))
						.orElseThrow(() -> new EvitaInvalidUsageException("Attribute with name `" + attributeKey + "` doesn't exist!"))
				);

			final AttributeValue updatedValue = applyDeltaAttributeMutation.mutateLocal(this.entitySchema, attributeValue);
			if (this.attributeMutations.get(attributeKey) == null) {
				this.attributeMutations.put(attributeKey, applyDeltaAttributeMutation);
			} else {
				this.attributeMutations.put(attributeKey, new UpsertAttributeMutation(attributeKey, Objects.requireNonNull(updatedValue.value())));
			}
		} else {
			throw new GenericEvitaInternalError("Unknown Evita price mutation: `" + localMutation.getClass() + "`!");
		}
		//noinspection unchecked
		return (T) this;
	}

	@Override
	@Nonnull
	public T removeAttribute(@Nonnull String attributeName) {
		final AttributeKey attributeKey = new AttributeKey(attributeName);
		assertAttributeAvailableAndMatchPredicate(new AttributeKey(attributeName));
		if (this.baseAttributes.getAttributeValueWithoutSchemaCheck(attributeKey).filter(Droppable::exists).isEmpty()) {
			this.attributeMutations.remove(attributeKey);
		} else {
			this.attributeMutations.put(attributeKey, new RemoveAttributeMutation(attributeName));
		}
		//noinspection unchecked
		return (T) this;
	}

	@Override
	@Nonnull
	public <U extends Serializable> T setAttribute(@Nonnull String attributeName, @Nullable U attributeValue) {
		if (attributeValue == null || attributeValue instanceof Object[] arr && ArrayUtils.isEmpty(arr)) {
			return removeAttribute(attributeName);
		} else {
			final AttributeKey attributeKey = new AttributeKey(attributeName);
			assertAttributeAvailableAndMatchPredicate(new AttributeKey(attributeName));
			if (!this.suppressVerification) {
				InitialAttributesBuilder.verifyAttributeIsInSchemaAndTypeMatch(
					this.baseAttributes.entitySchema, attributeName, attributeValue.getClass(), getLocationResolver()
				);
			}
			this.attributeMutations.put(
				attributeKey,
				new UpsertAttributeMutation(attributeKey, attributeValue)
			);
			//noinspection unchecked
			return (T) this;
		}
	}

	@Override
	@Nonnull
	public <U extends Serializable> T setAttribute(@Nonnull String attributeName, @Nullable U[] attributeValue) {
		if (ArrayUtils.isEmpty(attributeValue)) {
			return removeAttribute(attributeName);
		} else {
			final AttributeKey attributeKey = new AttributeKey(attributeName);
			assertAttributeAvailableAndMatchPredicate(new AttributeKey(attributeName));
			if (!this.suppressVerification) {
				InitialAttributesBuilder.verifyAttributeIsInSchemaAndTypeMatch(
					this.baseAttributes.entitySchema, attributeName, attributeValue.getClass(), getLocationResolver()
				);
			}
			this.attributeMutations.put(
				attributeKey,
				new UpsertAttributeMutation(attributeKey, attributeValue)
			);
			//noinspection unchecked
			return (T) this;
		}
	}

	@Override
	@Nonnull
	public T removeAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		final AttributeKey attributeKey = new AttributeKey(attributeName, locale);
		assertAttributeAvailableAndMatchPredicate(new AttributeKey(attributeName));
		if (this.baseAttributes.getAttributeValueWithoutSchemaCheck(attributeKey).filter(Droppable::exists).isEmpty()) {
			this.attributeMutations.remove(attributeKey);
		} else {
			this.attributeMutations.put(attributeKey, new RemoveAttributeMutation(attributeKey));
		}
		//noinspection unchecked
		return (T) this;
	}

	@Override
	@Nonnull
	public <U extends Serializable> T setAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nullable U attributeValue) {
		if (attributeValue == null || attributeValue instanceof Object[] arr && ArrayUtils.isEmpty(arr)) {
			return removeAttribute(attributeName, locale);
		} else {
			final AttributeKey attributeKey = new AttributeKey(attributeName, locale);
			assertAttributeAvailableAndMatchPredicate(new AttributeKey(attributeName));
			if (!this.suppressVerification) {
				InitialAttributesBuilder.verifyAttributeIsInSchemaAndTypeMatch(
					this.baseAttributes.entitySchema, attributeName, attributeValue.getClass(), locale, getLocationResolver()
				);
			}
			this.attributeMutations.put(
				attributeKey,
				new UpsertAttributeMutation(attributeKey, attributeValue)
			);
			//noinspection unchecked
			return (T) this;
		}
	}

	@Override
	@Nonnull
	public <U extends Serializable> T setAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nullable U[] attributeValue) {
		if (ArrayUtils.isEmpty(attributeValue)) {
			return removeAttribute(attributeName, locale);
		} else {
			final AttributeKey attributeKey = new AttributeKey(attributeName, locale);
			assertAttributeAvailableAndMatchPredicate(new AttributeKey(attributeName));
			if (!this.suppressVerification) {
				InitialAttributesBuilder.verifyAttributeIsInSchemaAndTypeMatch(
					this.baseAttributes.entitySchema, attributeName, attributeValue.getClass(), locale, getLocationResolver()
				);
			}
			this.attributeMutations.put(
				attributeKey,
				new UpsertAttributeMutation(attributeKey, attributeValue)
			);
			//noinspection unchecked
			return (T) this;
		}
	}

	@Nonnull
	@Override
	public T mutateAttribute(@Nonnull AttributeMutation mutation) {
		final AttributeKey attributeKey = mutation.getAttributeKey();
		assertAttributeAvailableAndMatchPredicate(attributeKey);
		this.attributeMutations.put(attributeKey, mutation);
		//noinspection unchecked
		return (T) this;
	}

	@Override
	public boolean attributesAvailable() {
		return this.baseAttributes.attributesAvailable();
	}

	@Override
	public boolean attributesAvailable(@Nonnull Locale locale) {
		return this.baseAttributes.attributesAvailable(locale);
	}

	@Override
	public boolean attributeAvailable(@Nonnull String attributeName) {
		return this.baseAttributes.attributeAvailable(attributeName);
	}

	@Override
	public boolean attributeAvailable(@Nonnull String attributeName, @Nonnull Locale locale) {
		return this.baseAttributes.attributeAvailable(attributeName, locale);
	}

	@Override
	@Nullable
	public <U extends Serializable> U getAttribute(@Nonnull String attributeName) {
		//noinspection unchecked
		return (U) getAttributeValueInternal(new AttributeKey(attributeName))
			.map(AttributeValue::value)
			.orElse(null);
	}

	@Override
	@Nullable
	public <U extends Serializable> U[] getAttributeArray(@Nonnull String attributeName) {
		//noinspection unchecked
		return (U[]) getAttributeValueInternal(new AttributeKey(attributeName))
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
	public <U extends Serializable> U getAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		//noinspection unchecked
		return (U) getAttributeValueInternal(new AttributeKey(attributeName, locale))
			.map(AttributeValue::value)
			.orElse(null);
	}

	@Override
	@Nullable
	public <U extends Serializable> U[] getAttributeArray(@Nonnull String attributeName, @Nonnull Locale locale) {
		//noinspection unchecked
		return (U[]) getAttributeValueInternal(new AttributeKey(attributeName, locale))
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
	public Optional<S> getAttributeSchema(@Nonnull String attributeName) {
		return this.baseAttributes.getAttributeSchema(attributeName);
	}

	@Nonnull
	@Override
	public Set<String> getAttributeNames() {
		return getAttributeValues()
			.stream()
			.filter(this.attributePredicate)
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
		if (!attributesAvailable()) {
			throw ContextMissingException.attributeContextMissing();
		}
		return getAttributeValuesWithoutPredicate()
			.filter(this.attributePredicate)
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
	public boolean differs(@Nonnull Attributes<S> attributes) {
		return this.baseAttributes != attributes;
	}

	@Nonnull
	@Override
	public Stream<? extends AttributeMutation> buildChangeSet() {
		final Map<AttributeKey, AttributeValue> builtAttributes = new HashMap<>(this.baseAttributes.attributeValues);
		return this.attributeMutations.values()
			.stream()
			.filter(it -> {
				final AttributeValue existingValue = builtAttributes.get(it.getAttributeKey());
				final AttributeValue newAttribute = it.mutateLocal(this.entitySchema, existingValue);
				builtAttributes.put(it.getAttributeKey(), newAttribute);
				return existingValue == null || newAttribute.version() > existingValue.version();
			});
	}

	/**
	 * Creates new container for attributes.
	 */
	@Nonnull
	protected abstract Attributes<S> createAttributesContainer(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Collection<AttributeValue> attributes,
		@Nonnull Map<String, S> attributeTypes
	);

	/**
	 * Returns true if there is single mutation in the local mutations.
	 */
	boolean isThereAnyChangeInMutations() {
		return Stream.concat(
				// process all original attribute values - they will be: either kept intact if there is no mutation
				// or mutated by the mutation - i.e. updated or removed
				this.baseAttributes.attributeValues
					.entrySet()
					.stream()
					// use old attribute, or apply mutation on the attribute and return the mutated attribute
					.map(it -> ofNullable(this.attributeMutations.get(it.getKey()))
						.map(mutation -> {
							final AttributeValue originValue = it.getValue();
							final AttributeValue mutatedAttribute = mutation.mutateLocal(this.entitySchema, originValue);
							return mutatedAttribute.differsFrom(originValue);
						})
						.orElse(false)
					),
				// all mutations that doesn't hit existing attribute probably produce new ones
				// we have to process them as well
				this.attributeMutations
					.values()
					.stream()
					// we want to process only those mutations that have no attribute to mutate in the original set
					.filter(it -> !this.baseAttributes.attributeValues.containsKey(it.getAttributeKey()))
					// apply mutation
					.map(it -> true)
			)
			.anyMatch(it -> it);
	}

	/**
	 * Builds attribute list based on registered mutations and previous state.
	 */
	@Nonnull
	protected Stream<AttributeValue> getAttributeValuesWithoutPredicate() {
		return Stream.concat(
			// process all original attribute values - they will be: either kept intact if there is no mutation
			// or mutated by the mutation - i.e. updated or removed
			this.baseAttributes.attributeValues
				.entrySet()
				.stream()
				// use old attribute, or apply mutation on the attribute and return the mutated attribute
				.map(it -> ofNullable(this.attributeMutations.get(it.getKey()))
					.map(mutation -> {
						final AttributeValue originValue = it.getValue();
						final AttributeValue mutatedAttribute = mutation.mutateLocal(this.entitySchema, originValue);
						return mutatedAttribute.differsFrom(originValue) ? mutatedAttribute : originValue;
					})
					.orElse(it.getValue())
				),
			// all mutations that doesn't hit existing attribute probably produce new ones
			// we have to process them as well
			this.attributeMutations
				.values()
				.stream()
				// we want to process only those mutations that have no attribute to mutate in the original set
				.filter(it -> !this.baseAttributes.attributeValues.containsKey(it.getAttributeKey()))
				// apply mutation
				.map(it -> it.mutateLocal(this.entitySchema, null))
		);
	}

	/**
	 * Returns either unchanged attribute value, or attribute value with applied mutation or even new attribute value
	 * that is produced by the mutation.
	 */
	@Nonnull
	private Optional<AttributeValue> getAttributeValueInternal(AttributeKey attributeKey) {
		assertAttributeAvailableAndMatchPredicate(attributeKey);
		final Optional<AttributeValue> attributeValue = ofNullable(this.baseAttributes.attributeValues.get(attributeKey))
			.map(it ->
				ofNullable(this.attributeMutations.get(attributeKey))
					.map(mut -> {
						final AttributeValue mutatedValue = mut.mutateLocal(this.entitySchema, it);
						return mutatedValue.differsFrom(it) ? mutatedValue : it;
					})
					.orElse(it)
			)
			.or(() ->
				ofNullable(this.attributeMutations.get(attributeKey))
					.map(it -> it.mutateLocal(this.entitySchema, null))
			);
		return attributeValue.filter(this.attributePredicate);
	}

	/**
	 * Checks if a specific attribute identified by the given {@code attributeKey} is available
	 * in the context and if it matches the predefined predicate.
	 * If the attribute is not available, throws a {@link ContextMissingException}.
	 * If the attribute does not match the predicate, an assertion error is thrown.
	 *
	 * @param attributeKey the key identifying the attribute to check for availability and
	 *                     compliance with the predicate; must not be null
	 */
	private void assertAttributeAvailableAndMatchPredicate(@Nonnull AttributeKey attributeKey) {
		if (!attributeAvailable(attributeKey.attributeName())) {
			throw ContextMissingException.attributeContextMissing(attributeKey.attributeName());
		}
		Assert.isTrue(
			this.attributePredicate.test(new AttributeValue(attributeKey, -1)),
			"Attribute `" + attributeKey + "` was not fetched and cannot be updated. Please enrich the entity first or load it with attributes."
		);
	}

}
