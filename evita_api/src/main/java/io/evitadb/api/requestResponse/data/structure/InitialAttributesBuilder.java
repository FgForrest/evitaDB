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

import io.evitadb.api.exception.InvalidDataTypeMutationException;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.AttributesEditor.AttributesBuilder;
import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.dataType.EvitaDataTypes;
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
	 * When this flag is set to true - verification on store is suppressed. It can be set to true only when verification
	 * is encured by calling logic.
	 */
	final boolean suppressVerification;
	/**
	 * Contains locale insensitive attribute values - simple key → value association map.
	 */
	final Map<AttributeKey, AttributeValue> attributeValues;

	static void verifyAttributeIsInSchemaAndTypeMatch(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String attributeName,
		@Nullable Class<? extends Serializable> aClass,
		@Nonnull Supplier<String> locationResolver
	) {
		final AttributeSchemaContract attributeSchema = entitySchema.getAttribute(attributeName).orElse(null);
		verifyAttributeIsInSchemaAndTypeMatch(entitySchema, attributeName, aClass, null, attributeSchema, locationResolver);
	}

	static void verifyAttributeIsInSchemaAndTypeMatch(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String attributeName,
		@Nullable Class<? extends Serializable> aClass,
		@Nonnull Locale locale,
		@Nonnull Supplier<String> locationResolver
	) {
		final AttributeSchemaContract attributeSchema = entitySchema.getAttribute(attributeName).orElse(null);
		verifyAttributeIsInSchemaAndTypeMatch(entitySchema, attributeName, aClass, locale, attributeSchema, locationResolver);
	}

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
				if (attributeSchema.isSortable()) {
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
		this.attributeValues = new HashMap<>();
		this.suppressVerification = false;
	}

	/**
	 * AttributesBuilder constructor that will be used for building brand new {@link Attributes} container.
	 */
	InitialAttributesBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		boolean suppressVerification
	) {
		this.entitySchema = entitySchema;
		this.attributeValues = new HashMap<>();
		this.suppressVerification = suppressVerification;
	}

	@Override
	@Nonnull
	public T removeAttribute(@Nonnull String attributeName) {
		final AttributeKey attributeKey = new AttributeKey(attributeName);
		attributeValues.remove(attributeKey);
		//noinspection unchecked
		return (T) this;
	}

	@Override
	@Nonnull
	public <U extends Serializable> T setAttribute(@Nonnull String attributeName, @Nullable U attributeValue) {
		if (attributeValue == null) {
			return removeAttribute(attributeName);
		} else {
			final AttributeKey attributeKey = new AttributeKey(attributeName);
			if (!suppressVerification) {
				verifyAttributeIsInSchemaAndTypeMatch(
					entitySchema, attributeName, attributeValue.getClass(),
					getLocationResolver()
				);
			}
			attributeValues.put(attributeKey, new AttributeValue(attributeKey, attributeValue));
			//noinspection unchecked
			return (T) this;
		}
	}

	@Override
	@Nonnull
	public <U extends Serializable> T setAttribute(@Nonnull String attributeName, @Nullable U[] attributeValue) {
		if (attributeValue == null) {
			return removeAttribute(attributeName);
		} else {
			final AttributeKey attributeKey = new AttributeKey(attributeName);
			if (!suppressVerification) {
				verifyAttributeIsInSchemaAndTypeMatch(entitySchema, attributeName, attributeValue.getClass(), getLocationResolver());
			}
			attributeValues.put(attributeKey, new AttributeValue(attributeKey, attributeValue));
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
		if (attributeValue == null) {
			return removeAttribute(attributeName, locale);
		} else {
			final AttributeKey attributeKey = new AttributeKey(attributeName, locale);
			if (!suppressVerification) {
				verifyAttributeIsInSchemaAndTypeMatch(entitySchema, attributeName, attributeValue.getClass(), locale, getLocationResolver());
			}
			this.attributeValues.put(attributeKey, new AttributeValue(attributeKey, attributeValue));
			//noinspection unchecked
			return (T) this;
		}
	}

	@Override
	@Nonnull
	public <U extends Serializable> T setAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nullable U[] attributeValue) {
		if (attributeValue == null) {
			return removeAttribute(attributeName, locale);
		} else {
			final AttributeKey attributeKey = new AttributeKey(attributeName, locale);
			if (!suppressVerification) {
				verifyAttributeIsInSchemaAndTypeMatch(entitySchema, attributeName, attributeValue.getClass(), locale, getLocationResolver());
			}
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
		return (U) ofNullable(attributeValues.get(new AttributeKey(attributeName)))
			.map(AttributeValue::value)
			.orElse(null);
	}

	@Override
	@Nullable
	public <U extends Serializable> U[] getAttributeArray(@Nonnull String attributeName) {
		//noinspection unchecked
		return (U[]) ofNullable(attributeValues.get(new AttributeKey(attributeName)))
			.map(AttributeValue::value)
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName) {
		return ofNullable(attributeValues.get(new AttributeKey(attributeName)));
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
		throw new UnsupportedOperationException("Initial entity creation doesn't support change monitoring - it has no sense.");
	}

}
