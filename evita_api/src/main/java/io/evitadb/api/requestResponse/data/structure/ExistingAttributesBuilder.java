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
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.AttributesEditor.AttributesBuilder;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.dataType.map.LazyHashMap;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

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

import static io.evitadb.api.requestResponse.data.structure.InitialAttributesBuilder.verifyAttributeIsInSchemaAndTypeMatch;
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
abstract class ExistingAttributesBuilder<S extends AttributeSchemaContract, T extends ExistingAttributesBuilder<S, T>>
	implements AttributesBuilder<S> {
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
	 * Contains locale insensitive attribute values - simple key → value association map.
	 */
	final Map<AttributeKey, AttributeMutation> attributeMutations;
	/**
	 * This predicate filters out attributes that were not fetched in query.
	 */
	final SerializablePredicate<AttributeValue> attributePredicate;
	/**
	 * Map of attribute types for the reference shared for all references of the same type.
	 * Map is lazily initialized when the first implicit attribute schema is created.
	 */
	@Nullable
	Map<String, S> attributeTypes;

	/**
	 * Creates a builder over an existing {@link Attributes} container.
	 *
	 * - Mutations produced by this builder will be applied on top of the provided base attributes.
	 * - Only attributes fetched by the query (predicate {@link #attributePredicate}) can be mutated.
	 *
	 * @param entitySchema non-null entity schema definition used for validation and evolution checks
	 * @param baseAttributes non-null existing attributes to be wrapped by the builder
	 */
	public ExistingAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Attributes<S> baseAttributes
	) {
		this.entitySchema = entitySchema;
		this.attributeMutations = new LazyHashMap<>(8);
		this.baseAttributes = baseAttributes;
		this.attributePredicate = Droppable::exists;
	}

	/**
	 * Advanced constructor allowing to specify a custom attribute visibility predicate and
	 * pre-populated local attribute type map.
	 *
	 * The predicate is used to ensure that only fetched attributes are mutated. If an attribute is not
	 * visible by the predicate, attempts to mutate it will fail fast.
	 *
	 * @param entitySchema non-null entity schema definition
	 * @param baseAttributes non-null existing attributes to build on
	 * @param attributePredicate non-null predicate deciding whether a particular attribute can be seen/changed
	 * @param attributeTypes non-null local map with implicitly created attribute schemas (may be empty)
	 */
	public ExistingAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Attributes<S> baseAttributes,
		@Nonnull SerializablePredicate<AttributeValue> attributePredicate,
		@Nonnull Map<String, S> attributeTypes
	) {
		this.entitySchema = entitySchema;
		this.baseAttributes = baseAttributes;
		this.attributePredicate = attributePredicate;
		this.attributeMutations = new LazyHashMap<>(8);
		this.attributeTypes = attributeTypes;
	}

	/**
	 * Package-private helper used when rehydrating a builder from an existing change set.
	 *
	 * The provided mutations are validated and normalized via {@link #addMutation(AttributeMutation)} to ensure
	 * correct behavior on top of the given base attributes.
	 *
	 * @param entitySchema non-null entity schema definition
	 * @param baseAttributes non-null base attributes
	 * @param attributeTypes non-null local attribute schema map (may be empty)
	 * @param attributeMutations non-null collection of mutations to seed this builder with
	 */
	ExistingAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Attributes<S> baseAttributes,
		@Nonnull Map<String, S> attributeTypes,
		@Nonnull Collection<AttributeMutation> attributeMutations
	) {
		this.entitySchema = entitySchema;
		this.attributeMutations = new HashMap<>(attributeMutations.size());
		this.baseAttributes = baseAttributes;
		this.attributeTypes = attributeTypes;
		this.attributePredicate = Droppable::exists;
		for (AttributeMutation attributeMutation : attributeMutations) {
			addMutation(attributeMutation);
		}
	}

	/**
	 * Package-private constructor that initializes a builder with a local attribute schema map
	 * but without any pre-seeded mutations.
	 *
	 * @param entitySchema non-null entity schema definition
	 * @param baseAttributes non-null base attributes
	 * @param attributeTypes non-null local attribute schema map (may be empty)
	 */
	ExistingAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Attributes<S> baseAttributes,
		@Nonnull Map<String, S> attributeTypes
	) {
		this.entitySchema = entitySchema;
		this.attributeMutations = new LazyHashMap<>(8);
		this.baseAttributes = baseAttributes;
		this.attributeTypes = attributeTypes;
		this.attributePredicate = Droppable::exists;
	}

	/**
	 * Adds a single attribute {@link AttributeMutation} to this builder.
	 *
	 * Behavior:
	 * - Upsert: validates schema and type, creates implicit schema if allowed, stores mutation only
	 *   when it changes the current value; otherwise removes a previously stored mutation.
	 * - Remove: stores removal when there is an existing value or a prior change that would create one;
	 *   otherwise it is ignored.
	 * - ApplyDelta: computes new value from the current baseline plus any previously staged mutation and
	 *   converts it to an upsert if needed so that subsequent reads reflect the newest value.
	 *
	 * Fast-fail checks ensure that only attributes present in the attribute context and matching the
	 * current predicate {@link #attributePredicate} can be mutated.
	 *
	 * @param localMutation non-null mutation to apply
	 * @return this builder for fluent chaining
	 * @throws InvalidMutationException when mutation attempts to set null value where not allowed or when
	 *                                  schema evolution rules prohibit implicit attribute creation
	 */
	@Nonnull
	public T addMutation(@Nonnull AttributeMutation localMutation) {
		assertAttributeAvailableAndMatchPredicate(localMutation.getAttributeKey());
		if (localMutation instanceof UpsertAttributeMutation upsertAttributeMutation) {
			final AttributeKey attributeKey = upsertAttributeMutation.getAttributeKey();
			final String attributeName = attributeKey.attributeName();
			final Serializable attributeValue = upsertAttributeMutation.getAttributeValue();
			Assert.isTrue(
				attributeValue != null,
				() -> new InvalidMutationException("Attribute value cannot be null!")
			);
			createImplicitSchemaIfMissing(attributeKey, attributeValue);
			verifyAttributeIsInSchemaAndTypeMatch(
				this.baseAttributes.entitySchema,
				attributeName,
				attributeValue.getClass(),
				attributeKey.locale(),
				getAttributeSchemaFromSchemaOrLocally(attributeName),
				getLocationResolver()
			);

			if (isValueDiffers(upsertAttributeMutation)) {
				this.attributeMutations.put(attributeKey, upsertAttributeMutation);
			} else {
				this.attributeMutations.remove(attributeKey);
			}
		} else if (localMutation instanceof RemoveAttributeMutation removeAttributeMutation) {
			final AttributeKey attributeKey = removeAttributeMutation.getAttributeKey();
			if (this.baseAttributes.getAttributeValueWithoutSchemaCheck(attributeKey).isEmpty()) {
				this.attributeMutations.remove(attributeKey);
			} else {
				if (isValueDiffers(removeAttributeMutation)) {
					this.attributeMutations.put(attributeKey, removeAttributeMutation);
				} else {
					this.attributeMutations.remove(attributeKey);
				}
			}
		} else if (localMutation instanceof ApplyDeltaAttributeMutation<?> applyDeltaAttributeMutation) {
			final AttributeKey attributeKey = applyDeltaAttributeMutation.getAttributeKey();
			final AttributeValue attributeValue = this.baseAttributes
				.getAttributeValueWithoutSchemaCheck(attributeKey)
				.map(
					it -> ofNullable(
						this.attributeMutations.get(attributeKey))
						.map(x -> x.mutateLocal(
							this.entitySchema,
							it
						))
						.orElse(it)
				)
				.orElseGet(
					() -> ofNullable(
						this.attributeMutations.get(attributeKey))
						.map(x -> x.mutateLocal(
							this.entitySchema,
							null
						))
						.orElseThrow(
							() -> new EvitaInvalidUsageException(
								"Attribute with name `" + attributeKey + "` doesn't exist!"))
				);

			final AttributeValue updatedValue = applyDeltaAttributeMutation.mutateLocal(
				this.entitySchema, attributeValue
			);
			if (isValueDiffers(applyDeltaAttributeMutation)) {
				if (this.attributeMutations.get(attributeKey) == null) {
					this.attributeMutations.put(attributeKey, applyDeltaAttributeMutation);
				} else {
					this.attributeMutations.put(
						attributeKey, new UpsertAttributeMutation(
							attributeKey,
							Objects.requireNonNull(updatedValue.value())
						)
					);
				}
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
		final Optional<S> attributeSchema = getAttributeSchema(attributeName);
		final Serializable defaultValue = attributeSchema.map(AttributeSchemaContract::getDefaultValue).orElse(null);
		if (defaultValue != null) {
			addMutation(new UpsertAttributeMutation(attributeName, defaultValue));
		} else {
			addMutation(new RemoveAttributeMutation(attributeName));
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
			addMutation(new UpsertAttributeMutation(new AttributeKey(attributeName), attributeValue));
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
			addMutation(new UpsertAttributeMutation(new AttributeKey(attributeName), attributeValue));
			//noinspection unchecked
			return (T) this;
		}
	}

	@Override
	@Nonnull
	public T removeAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		final Optional<S> attributeSchema = getAttributeSchema(attributeName);
		final Serializable defaultValue = attributeSchema.map(AttributeSchemaContract::getDefaultValue).orElse(null);
		if (defaultValue != null) {
			addMutation(new UpsertAttributeMutation(attributeName, locale, defaultValue));
		} else {
			final AttributeKey attributeKey = new AttributeKey(attributeName, locale);
			if (
				this.baseAttributes.getAttributeValueWithoutSchemaCheck(attributeKey)
					.filter(Droppable::exists)
					.isEmpty()
			) {
				assertAttributeAvailableAndMatchPredicate(new AttributeKey(attributeName));
				this.attributeMutations.remove(attributeKey);
			} else {
				addMutation(new RemoveAttributeMutation(attributeKey));
			}
		}
		//noinspection unchecked
		return (T) this;
	}

	@Override
	@Nonnull
	public <U extends Serializable> T setAttribute(
		@Nonnull String attributeName, @Nonnull Locale locale, @Nullable U attributeValue) {
		if (attributeValue == null || attributeValue instanceof Object[] arr && ArrayUtils.isEmpty(arr)) {
			return removeAttribute(attributeName, locale);
		} else {
			addMutation(new UpsertAttributeMutation(new AttributeKey(attributeName, locale), attributeValue));
			//noinspection unchecked
			return (T) this;
		}
	}

	@Override
	@Nonnull
	public <U extends Serializable> T setAttribute(
		@Nonnull String attributeName, @Nonnull Locale locale, @Nullable U[] attributeValue) {
		if (ArrayUtils.isEmpty(attributeValue)) {
			return removeAttribute(attributeName, locale);
		} else {
			addMutation(new UpsertAttributeMutation(new AttributeKey(attributeName, locale), attributeValue));
			//noinspection unchecked
			return (T) this;
		}
	}

	@Nonnull
	@Override
	public T mutateAttribute(@Nonnull AttributeMutation mutation) {
		addMutation(mutation);
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
		return this.attributeMutations
			.values()
			.stream()
			.filter(it -> {
				final AttributeValue existingValue = builtAttributes.get(
					it.getAttributeKey());
				final AttributeValue newAttribute = it.mutateLocal(
					this.entitySchema, existingValue);
				builtAttributes.put(it.getAttributeKey(), newAttribute);
				return existingValue == null || newAttribute.version() > existingValue.version();
			});
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
	 * Creates an implicit attribute schema if it is missing for the specified attribute name and value.
	 * If an attribute schema already exists, verifies that the provided value matches the expected type.
	 * Otherwise, adds a new attribute schema if the entity schema allows adding attributes dynamically.
	 *
	 * @param <U>            The type of the attribute value, which must extend {@link Serializable}.
	 * @param attributeKey   The name of the attribute for which the schema needs to be created or verified. Must not be null.
	 * @param attributeValue The value of the attribute to be used for schema creation or type verification. Nullable.
	 */
	protected <U extends Serializable> void createImplicitSchemaIfMissing(
		@Nonnull AttributeKey attributeKey,
		@Nullable U attributeValue
	) {
		if (attributeValue != null) {
			final String attributeName = attributeKey.attributeName();
			final Locale locale = attributeKey.locale();
			final S attributeSchema = getAttributeSchemaFromSchemaOrLocally(attributeName);
			if (attributeSchema != null) {
				verifyAttributeIsInSchemaAndTypeMatch(
					this.entitySchema, attributeName, attributeValue.getClass(), locale, attributeSchema,
					getLocationResolver()
				);
			} else {
				Assert.isTrue(
					this.entitySchema.allows(EvolutionMode.ADDING_ATTRIBUTES),
					() -> new InvalidMutationException(
						"Cannot add new attribute `" + attributeName + "` to the " + getLocationResolver().get() +
							" because entity schema doesn't allow adding new attributes!"
					)
				);
				if (this.attributeTypes == null) {
					this.attributeTypes = CollectionUtils.createHashMap(8);
				}
				final AttributeValue theAttributeValue = new AttributeValue(
					locale == null ? new AttributeKey(attributeName) : new AttributeKey(attributeName, locale),
					attributeValue
				);
				this.attributeTypes.put(
					attributeName,
					createImplicitSchema(theAttributeValue)
				);
			}
		}
	}

	/**
	 * Creates an implicit schema for the given attribute value. This method is expected to be implemented
	 * in a way that ensures the appropriate schema is generated based on the provided attribute value.
	 * Typically used to dynamically handle schema generation in scenarios where attributes are added without
	 * predefined schemas.
	 *
	 * @param theAttributeValue The attribute value for which the implicit schema is to be created. Must not be null.
	 * @return The generated schema corresponding to the provided attribute value. Must not be null.
	 */
	@Nonnull
	protected abstract S createImplicitSchema(@Nonnull AttributeValue theAttributeValue);

	/**
	 * Returns whether there is at least one staged mutation.
	 *
	 * This is a fast-path that does not compute the final values; it only checks if any mutation
	 * has been registered so far.
	 *
	 * @return true when the builder contains any pending mutations, false otherwise
	 */
	boolean isThereAnyChangeInMutations() {
		return !this.attributeMutations.isEmpty();
	}

	/**
	 * Retrieves the schema for a specific attribute by first attempting to fetch it from the provided
	 * {@link AttributeSchemaProvider}. If the attribute is not found in the provider, it checks the local
	 * attribute types map (if available) for a matching schema.
	 *
	 * @param attributeName the name of the attribute whose schema is being retrieved. Must not be null.
	 * @return the schema of the attribute if found, or null if the schema is not present in either the
	 * provider or the local attribute types map.
	 */
	@Nullable
	S getAttributeSchemaFromSchemaOrLocally(
		@Nonnull String attributeName
	) {
		return getAttributeSchema(attributeName)
			.orElseGet(() -> this.attributeTypes == null ? null : this.attributeTypes.get(attributeName));
	}

	/**
	 * Checks whether applying the provided mutation would change the current value.
	 *
	 * The current value is taken from the base attributes. The mutation is applied locally and the
	 * result is compared to the original attribute value.
	 *
	 * @param attributeMutation non-null mutation to evaluate
	 * @return true if applying the mutation results in a different {@link AttributeValue}, false otherwise
	 */
	private boolean isValueDiffers(@Nonnull AttributeMutation attributeMutation) {
		final AttributeValue baseAttribute = this.baseAttributes
			.getAttributeValueWithoutSchemaCheck(attributeMutation.getAttributeKey())
            .orElse(null);
		return baseAttribute == null || !baseAttribute.equals(
			attributeMutation.mutateLocal(this.entitySchema, baseAttribute));
	}

	/**
	 * Resolves an attribute value by combining the base value with any staged mutation.
	 *
	 * - If a base value exists and a mutation is present, the mutation is applied and returned only when it differs.
	 * - If no base value exists but a mutation produces one, the produced value is returned.
	 * - The resulting value must also satisfy the {@link #attributePredicate} to be visible.
	 *
	 * @param attributeKey non-null attribute key
	 * @return optional value visible under current predicate after applying staged mutations
	 */
	@Nonnull
	private Optional<AttributeValue> getAttributeValueInternal(AttributeKey attributeKey) {
		assertAttributeAvailableAndMatchPredicate(attributeKey);
		final Optional<AttributeValue> attributeValue = ofNullable(
			this.baseAttributes.attributeValues.get(attributeKey))
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
