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

import io.evitadb.api.exception.InvalidDataTypeMutationException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.AttributesEditor.AttributesBuilder;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaProvider;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * Class supports intermediate mutable object that allows {@link Attributes} container rebuilding.
 * Due to performance reasons (see {@link DirectWriteOrOperationLog} microbenchmark) there is special implementation
 * for the situation when entity is newly created. In this case we know everything is new and we don't need to closely
 * monitor the changes so this can speed things up.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
abstract class InitialAttributesBuilder<S extends AttributeSchemaContract, T extends InitialAttributesBuilder<S, T>> implements AttributesBuilder<S> {
	@Serial private static final long serialVersionUID = 7714436064799237939L;
	/**
	 * Entity schema if available.
	 */
	final EntitySchemaContract entitySchema;
	/**
	 * Contains locale insensitive attribute values - simple key → value association map.
	 */
	final Map<AttributeKey, AttributeValue> attributeValues;
	/**
	 * Map of attribute types for the reference shared for all references of the same type.
	 * Map is lazily initialized when the first implicit attribute schema is created.
	 */
	@Nullable
	Map<String, S> attributeTypes;

	static void verifyAttributeIsInSchemaAndTypeMatch(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String attributeName,
		@Nullable Class<? extends Serializable> aClass,
		@Nullable Locale locale,
		@Nullable AttributeSchemaContract attributeSchema,
		@Nonnull Supplier<String> locationResolver
	) {
		Assert.isTrue(
			attributeSchema != null || entitySchema.allows(EvolutionMode.ADDING_ATTRIBUTES),
			() -> new InvalidMutationException(
				"Attribute `" + attributeName + "` is not configured in entity " + locationResolver.get() +
					" schema and automatic evolution is not enabled for attributes!"
			)
		);
		if (attributeSchema != null) {
			if (aClass != null) {
				Assert.isTrue(
					attributeSchema.getType().isAssignableFrom(aClass) ||
						(attributeSchema.getType().isPrimitive() && EvitaDataTypes.getWrappingPrimitiveClass(attributeSchema.getType()).isAssignableFrom(aClass)),
					() -> new InvalidDataTypeMutationException(
						"Attribute `" + attributeName + "` in entity " + locationResolver.get() +
							" schema accepts only type `" + attributeSchema.getType().getName() +
							"` - value type is different: " + aClass.getName() + "!",
						attributeSchema.getType(), aClass
					)
				);
				if (attributeSchema.isSortableInAnyScope()) {
					Assert.isTrue(
						!aClass.isArray(),
						() -> new InvalidDataTypeMutationException(
							"Attribute `" + attributeName + "` in entity " + locationResolver.get() +
								" schema is sortable and can't hold arrays of `" + aClass.getName() + "`!",
							attributeSchema.getType(), aClass
						)
					);
				}
			}
			if (locale == null) {
				Assert.isTrue(
					!attributeSchema.isLocalized(),
					() -> new InvalidMutationException(
						"Attribute `" + attributeName + "` in entity " + locationResolver.get() +
							" schema is localized and doesn't accept non-localized attributes!"
					)
				);
			} else {
				Assert.isTrue(
					attributeSchema.isLocalized(),
					() -> new InvalidMutationException(
						"Attribute `" + attributeName + "` in entity " + locationResolver.get() +
							" schema is not localized and doesn't accept localized attributes!"
					)
				);
				Assert.isTrue(
					entitySchema.supportsLocale(locale) || entitySchema.allows(EvolutionMode.ADDING_LOCALES),
					() -> new InvalidMutationException(
						"Attribute `" + attributeName + "` in entity " + locationResolver.get() +
							" schema is localized, but schema doesn't support locale " + locale + "! " +
							"Supported locales are: " +
							entitySchema.getLocales().stream().map(Locale::toString).collect(Collectors.joining(", "))
					)
				);
			}
		} else if (locale != null) {
			// at least verify supported locale
			Assert.isTrue(
				entitySchema.supportsLocale(locale) || entitySchema.allows(EvolutionMode.ADDING_LOCALES),
				() -> new InvalidMutationException(
					"Attribute `" + attributeName + "` in entity " + locationResolver.get() +
						" schema is localized, but schema doesn't support locale `" + locale + "`! " +
						"Supported locales are: " +
						entitySchema.getLocales().stream().map(Locale::toString).map(it -> "`" + it + "`").collect(Collectors.joining(", "))
				)
			);
		}
	}

	/**
	 * AttributesBuilder constructor that will be used for building brand new {@link Attributes} container.
	 */
	InitialAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema
	) {
		this.entitySchema = entitySchema;
		this.attributeValues = CollectionUtils.createHashMap(32);
	}

	/**
	 * AttributesBuilder constructor that will be used for building brand new {@link Attributes} container.
	 */
	InitialAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Map<String, S> attributeTypes
	) {
		this.entitySchema = entitySchema;
		this.attributeValues = CollectionUtils.createHashMap(32);
		this.attributeTypes = attributeTypes;
	}

	@Override
	@Nonnull
	public T removeAttribute(@Nonnull String attributeName) {
		final AttributeKey attributeKey = new AttributeKey(attributeName);
		this.attributeValues.remove(attributeKey);
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
			createImplicitSchemaIfMissing(attributeName, attributeValue, null);
			this.attributeValues.put(attributeKey, new AttributeValue(attributeKey, attributeValue));
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
			createImplicitSchemaIfMissing(attributeName, attributeValue, null);
			this.attributeValues.put(attributeKey, new AttributeValue(attributeKey, attributeValue));
			//noinspection unchecked
			return (T) this;
		}
	}

	@Override
	@Nonnull
	public T removeAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		final AttributeKey attributeKey = new AttributeKey(attributeName, locale);
		this.attributeValues.remove(attributeKey);
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
			createImplicitSchemaIfMissing(attributeName, attributeValue, locale);
			this.attributeValues.put(attributeKey, new AttributeValue(attributeKey, attributeValue));
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
			createImplicitSchemaIfMissing(attributeName, attributeValue, locale);
			this.attributeValues.put(attributeKey, new AttributeValue(attributeKey, attributeValue));
			//noinspection unchecked
			return (T) this;
		}
	}

	@Nonnull
	@Override
	public T mutateAttribute(@Nonnull AttributeMutation mutation) {
		throw new UnsupportedOperationException("You cannot apply mutation when entity is just being created!");
	}

	@Override
	public boolean attributesAvailable() {
		return true;
	}

	@Override
	public boolean attributesAvailable(@Nonnull Locale locale) {
		return true;
	}

	@Override
	public boolean attributeAvailable(@Nonnull String attributeName) {
		return true;
	}

	@Override
	public boolean attributeAvailable(@Nonnull String attributeName, @Nonnull Locale locale) {
		return true;
	}

	@Override
	@Nullable
	public <U extends Serializable> U getAttribute(@Nonnull String attributeName) {
		//noinspection unchecked
		return (U) ofNullable(this.attributeValues.get(new AttributeKey(attributeName)))
			.map(AttributeValue::value)
			.orElse(null);
	}

	@Override
	@Nullable
	public <U extends Serializable> U[] getAttributeArray(@Nonnull String attributeName) {
		//noinspection unchecked
		return (U[]) ofNullable(this.attributeValues.get(new AttributeKey(attributeName)))
			.map(AttributeValue::value)
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName) {
		return ofNullable(this.attributeValues.get(new AttributeKey(attributeName)));
	}

	@Override
	@Nullable
	public <U extends Serializable> U getAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		//noinspection unchecked
		return (U) ofNullable(this.attributeValues.get(new AttributeKey(attributeName, locale)))
			.map(AttributeValue::value)
			.orElse(null);
	}

	@Override
	@Nullable
	public <U extends Serializable> U[] getAttributeArray(@Nonnull String attributeName, @Nonnull Locale locale) {
		//noinspection unchecked
		return (U[]) ofNullable(this.attributeValues.get(new AttributeKey(attributeName, locale)))
			.map(AttributeValue::value)
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName, @Nonnull Locale locale) {
		return ofNullable(this.attributeValues.get(new AttributeKey(attributeName, locale)));
	}

	@Nonnull
	@Override
	public Set<String> getAttributeNames() {
		return this.attributeValues
			.keySet()
			.stream()
			.map(AttributeKey::attributeName)
			.collect(Collectors.toSet());
	}

	@Nonnull
	@Override
	public Set<AttributeKey> getAttributeKeys() {
		return this.attributeValues.keySet();
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull AttributeKey attributeKey) {
		return ofNullable(this.attributeValues.get(attributeKey))
			.or(() -> attributeKey.localized() ?
				ofNullable(this.attributeValues.get(new AttributeKey(attributeKey.attributeName()))) :
				empty()
			);
	}

	@Nonnull
	@Override
	public Collection<AttributeValue> getAttributeValues() {
		return this.attributeValues.values();
	}

	@Nonnull
	@Override
	public Collection<AttributeValue> getAttributeValues(@Nonnull String attributeName) {
		return getAttributeValues()
			.stream()
			.filter(it -> attributeName.equals(it.key().attributeName()))
			.collect(Collectors.toList());
	}

	@Nonnull
	public Set<Locale> getAttributeLocales() {
		return this.attributeValues
			.keySet()
			.stream()
			.map(AttributesContract.AttributeKey::locale)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
	}

	@Nonnull
	@Override
	public Stream<? extends AttributeMutation> buildChangeSet() {
		return getAttributeValues()
			.stream()
			.filter(it -> it.value() != null)
			.map(it -> new UpsertAttributeMutation(it.key(), it.value()));
	}

	/**
	 * Retrieves a map of attribute types by combining the attribute definitions from the provided
	 * {@link AttributeSchemaProvider} with any additional attributes defined in this context.
	 * If no additional attributes are defined locally, it directly returns the attributes from the
	 * {@link AttributeSchemaProvider}.
	 *
	 * @param attributeSchemaProvider the provider from which the initial set of attribute definitions
	 *                                is obtained. Must not be null.
	 * @return a map containing the combined attribute types, with attribute names as keys and their
	 *         corresponding schema definitions as values. The resulting map is unmodifiable and must not be null.
	 */
	@Nonnull
	protected Map<String, S> getAttributeTypes(@Nonnull AttributeSchemaProvider<S> attributeSchemaProvider) {
		return this.attributeTypes == null ?
			attributeSchemaProvider.getAttributes() :
			Stream.concat(
				      attributeSchemaProvider.getAttributes().entrySet().stream(),
				      this.attributeTypes.entrySet().stream()
			      )
			      .collect(
				      Collectors.toUnmodifiableMap(
					      Entry::getKey,
					      Entry::getValue
				      )
			      );
	}

	/**
	 * Creates an implicit attribute schema if it is missing for the specified attribute name and value.
	 * If an attribute schema already exists, verifies that the provided value matches the expected type.
	 * Otherwise, adds a new attribute schema if the entity schema allows adding attributes dynamically.
	 *
	 * @param <U>          The type of the attribute value, which must extend {@link Serializable}.
	 * @param attributeName The name of the attribute for which the schema needs to be created or verified. Must not be null.
	 * @param attributeValue The value of the attribute to be used for schema creation or type verification. Nullable.
	 */
	protected <U extends Serializable> void createImplicitSchemaIfMissing(
		@Nonnull String attributeName,
		@Nullable U attributeValue,
		@Nullable Locale locale
	) {
		if (attributeValue != null) {
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
	 * Retrieves the schema for a specific attribute by first attempting to fetch it from the provided
	 * {@link AttributeSchemaProvider}. If the attribute is not found in the provider, it checks the local
	 * attribute types map (if available) for a matching schema.
	 *
	 * @param attributeName the name of the attribute whose schema is being retrieved. Must not be null.
	 * @return the schema of the attribute if found, or null if the schema is not present in either the
	 *         provider or the local attribute types map.
	 */
	@Nullable
	S getAttributeSchemaFromSchemaOrLocally(
		@Nonnull String attributeName
	) {
		return getAttributeSchema(attributeName)
			.orElseGet(() -> this.attributeTypes == null ? null : this.attributeTypes.get(attributeName));
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

}
