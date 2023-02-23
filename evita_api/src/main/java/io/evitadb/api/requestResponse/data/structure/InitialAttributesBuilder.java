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
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * Class supports intermediate mutable object that allows {@link Attributes} container rebuilding.
 * Due to performance reasons (see {@link DirectWriteOrOperationLog} microbenchmark) there is special implementation
 * for the situation when entity is newly created. In this case we know everything is new and we don't need to closely
 * monitor the changes so this can speed things up.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class InitialAttributesBuilder implements AttributesBuilder {
	@Serial private static final long serialVersionUID = 7714436064799237939L;
	/**
	 * Entity schema if available.
	 */
	private final EntitySchemaContract entitySchema;
	/**
	 * When this flag is set to true - verification on store is suppressed. It can be set to true only when verification
	 * is encured by calling logic.
	 */
	private final boolean suppressVerification;
	/**
	 * Contains locale insensitive attribute values - simple key → value association map.
	 */
	private final Map<AttributeKey, AttributeValue> attributeValues;

	static void verifyAttributeIsInSchemaAndTypeMatch(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String attributeName,
		@Nullable Class<? extends Serializable> aClass
	) {
		final AttributeSchemaContract attributeSchema = entitySchema.getAttribute(attributeName).orElse(null);
		verifyAttributeIsInSchemaAndTypeMatch(entitySchema, null, attributeName, aClass, null, attributeSchema);
	}

	static void verifyAttributeIsInSchemaAndTypeMatch(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String attributeName,
		@Nullable Class<? extends Serializable> aClass,
		@Nonnull Locale locale
	) {
		final AttributeSchemaContract attributeSchema = entitySchema.getAttribute(attributeName).orElse(null);
		verifyAttributeIsInSchemaAndTypeMatch(entitySchema, null, attributeName, aClass, locale, attributeSchema);
	}

	static void verifyAttributeIsInSchemaAndTypeMatch(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull String attributeName,
		@Nullable Class<? extends Serializable> aClass,
		@Nullable Locale locale,
		@Nullable AttributeSchemaContract attributeSchema
	) {
		Assert.isTrue(
			attributeSchema != null || entitySchema.allows(EvolutionMode.ADDING_ATTRIBUTES),
			() -> new InvalidMutationException(
				"Attribute " + attributeName + " is not configured in entity `" + entitySchema.getName() + "`" +
					(referenceSchema == null ? "" : " reference `" + referenceSchema.getName() + "`") +
					" schema and automatic evolution is not enabled for attributes!"
			)
		);
		if (attributeSchema != null) {
			if (aClass != null) {
				Assert.isTrue(
					attributeSchema.getType().isAssignableFrom(aClass) ||
						(attributeSchema.getType().isPrimitive() && EvitaDataTypes.getWrappingPrimitiveClass(attributeSchema.getType()).isAssignableFrom(aClass)),
					() -> new InvalidDataTypeMutationException(
						"Attribute " + attributeName + " in entity `" + entitySchema.getName() + "`" +
							(referenceSchema == null ? "" : " reference `" + referenceSchema.getName() + "`") +
							" schema accepts only type " + attributeSchema.getType().getName() +
							" - value type is different: " + aClass.getName() + "!",
						attributeSchema.getType(), aClass
					)
				);
				if (attributeSchema.isSortable()) {
					Assert.isTrue(
						!aClass.isArray(),
						() -> new InvalidDataTypeMutationException(
							"Attribute " + attributeName + " in entity `" + entitySchema.getName() + "`" +
								(referenceSchema == null ? "" : " reference `" + referenceSchema.getName() + "`") +
								" schema is sortable and can't hold arrays of " + aClass.getName() + "!",
							attributeSchema.getType(), aClass
						)
					);
				}
			}
			if (locale == null) {
				Assert.isTrue(
					!attributeSchema.isLocalized(),
					() -> new InvalidMutationException(
						"Attribute `" + attributeName + "` in entity `" + entitySchema.getName() + "`" +
							(referenceSchema == null ? "" : " reference `" + referenceSchema.getName() + "`") +
							" schema is localized and doesn't accept non-localized attributes!"
					)
				);
			} else {
				Assert.isTrue(
					attributeSchema.isLocalized(),
					() -> new InvalidMutationException(
						"Attribute `" + attributeName + "` in entity `" + entitySchema.getName() + "`" +
							(referenceSchema == null ? "" : " reference `" + referenceSchema.getName() + "`") +
							" schema is not localized and doesn't accept localized attributes!"
					)
				);
				Assert.isTrue(
					entitySchema.supportsLocale(locale) || entitySchema.allows(EvolutionMode.ADDING_LOCALES),
					() -> new InvalidMutationException(
						"Attribute `" + attributeName + "` in entity `" + entitySchema.getName() + "`" +
							(referenceSchema == null ? "" : " reference `" + referenceSchema.getName() + "`") +
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
					"Attribute `" + attributeName + "` in entity `" + entitySchema.getName() + "`" +
						(referenceSchema == null ? "" : " reference `" + referenceSchema.getName() + "`") +
						" schema is localized, but schema doesn't support locale " + locale + "! " +
						"Supported locales are: " +
						entitySchema.getLocales().stream().map(Locale::toString).collect(Collectors.joining(", "))
				)
			);
		}
	}

	/**
	 * AttributesBuilder constructor that will be used for building brand new {@link Attributes} container.
	 */
	InitialAttributesBuilder(@Nonnull EntitySchemaContract entitySchema) {
		this.entitySchema = entitySchema;
		this.attributeValues = new HashMap<>();
		this.suppressVerification = false;
	}

	/**
	 * AttributesBuilder constructor that will be used for building brand new {@link Attributes} container.
	 */
	InitialAttributesBuilder(@Nonnull EntitySchemaContract entitySchema, boolean suppressVerification) {
		this.entitySchema = entitySchema;
		this.attributeValues = new HashMap<>();
		this.suppressVerification = suppressVerification;
	}

	@Override
	@Nonnull
	public AttributesBuilder removeAttribute(@Nonnull String attributeName) {
		final AttributeKey attributeKey = new AttributeKey(attributeName);
		attributeValues.remove(attributeKey);
		return this;
	}

	@Override
	@Nonnull
	public <T extends Serializable> AttributesBuilder setAttribute(@Nonnull String attributeName, @Nullable T attributeValue) {
		if (attributeValue == null) {
			return removeAttribute(attributeName);
		} else {
			final AttributeKey attributeKey = new AttributeKey(attributeName);
			if (!suppressVerification) {
				verifyAttributeIsInSchemaAndTypeMatch(entitySchema, attributeName, attributeValue.getClass());
			}
			attributeValues.put(attributeKey, new AttributeValue(attributeKey, attributeValue));
			return this;
		}
	}

	@Override
	@Nonnull
	public <T extends Serializable> AttributesBuilder setAttribute(@Nonnull String attributeName, @Nullable T[] attributeValue) {
		if (attributeValue == null) {
			return removeAttribute(attributeName);
		} else {
			final AttributeKey attributeKey = new AttributeKey(attributeName);
			if (!suppressVerification) {
				verifyAttributeIsInSchemaAndTypeMatch(entitySchema, attributeName, attributeValue.getClass());
			}
			attributeValues.put(attributeKey, new AttributeValue(attributeKey, attributeValue));
			return this;
		}
	}

	@Override
	@Nonnull
	public AttributesBuilder removeAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		final AttributeKey attributeKey = new AttributeKey(attributeName, locale);
		this.attributeValues.remove(attributeKey);
		return this;
	}

	@Override
	@Nonnull
	public <T extends Serializable> AttributesBuilder setAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T attributeValue) {
		if (attributeValue == null) {
			return removeAttribute(attributeName, locale);
		} else {
			final AttributeKey attributeKey = new AttributeKey(attributeName, locale);
			if (!suppressVerification) {
				verifyAttributeIsInSchemaAndTypeMatch(entitySchema, attributeName, attributeValue.getClass(), locale);
			}
			this.attributeValues.put(attributeKey, new AttributeValue(attributeKey, attributeValue));
			return this;
		}
	}

	@Override
	@Nonnull
	public <T extends Serializable> AttributesBuilder setAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T[] attributeValue) {
		if (attributeValue == null) {
			return removeAttribute(attributeName, locale);
		} else {
			final AttributeKey attributeKey = new AttributeKey(attributeName, locale);
			if (!suppressVerification) {
				verifyAttributeIsInSchemaAndTypeMatch(entitySchema, attributeName, attributeValue.getClass(), locale);
			}
			this.attributeValues.put(attributeKey, new AttributeValue(attributeKey, attributeValue));
			return this;
		}
	}

	/*
		LOCALIZED ATTRIBUTES
	 */

	@Nonnull
	@Override
	public AttributesBuilder mutateAttribute(@Nonnull AttributeMutation mutation) {
		throw new UnsupportedOperationException("You cannot apply mutation when entity is just being created!");
	}

	@Override
	@Nullable
	public <T extends Serializable> T getAttribute(@Nonnull String attributeName) {
		//noinspection unchecked
		return (T) ofNullable(attributeValues.get(new AttributeKey(attributeName)))
			.map(AttributeValue::getValue)
			.orElse(null);
	}

	@Override
	@Nullable
	public <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName) {
		//noinspection unchecked
		return (T[]) ofNullable(attributeValues.get(new AttributeKey(attributeName)))
			.map(AttributeValue::getValue)
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName) {
		return ofNullable(attributeValues.get(new AttributeKey(attributeName)));
	}

	@Override
	@Nullable
	public <T extends Serializable> T getAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		//noinspection unchecked
		return (T) ofNullable(this.attributeValues.get(new AttributeKey(attributeName, locale)))
			.map(AttributeValue::getValue)
			.orElse(null);
	}

	@Override
	@Nullable
	public <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName, @Nonnull Locale locale) {
		//noinspection unchecked
		return (T[]) ofNullable(this.attributeValues.get(new AttributeKey(attributeName, locale)))
			.map(AttributeValue::getValue)
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName, @Nonnull Locale locale) {
		return ofNullable(this.attributeValues.get(new AttributeKey(attributeName, locale)));
	}

	@Nonnull
	@Override
	public Optional<AttributeSchemaContract> getAttributeSchema(@Nonnull String attributeName) {
		return this.entitySchema.getAttribute(attributeName);
	}

	@Nonnull
	@Override
	public Set<String> getAttributeNames() {
		return this.attributeValues
			.keySet()
			.stream()
			.map(AttributeKey::getAttributeName)
			.collect(Collectors.toSet());
	}

	@Nonnull
	@Override
	public Set<AttributeKey> getAttributeKeys() {
		return this.attributeValues.keySet();
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
			.filter(it -> attributeName.equals(it.getKey().getAttributeName()))
			.collect(Collectors.toList());
	}

	@Nonnull
	public Set<Locale> getAttributeLocales() {
		return this.attributeValues
			.keySet()
			.stream()
			.map(AttributesContract.AttributeKey::getLocale)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
	}

	@Nonnull
	@Override
	public Stream<? extends AttributeMutation> buildChangeSet() {
		throw new UnsupportedOperationException("Initial entity creation doesn't support change monitoring - it has no sense.");
	}

	@Nonnull
	@Override
	public Attributes build() {
		return new Attributes(
			this.entitySchema,
			this.attributeValues.values(),
			this.attributeValues
			.values()
			.stream()
			.map(this::createImplicitSchema)
			.collect(
				Collectors.toMap(
					AttributeSchemaContract::getName,
					Function.identity(),
					(attributeType, attributeType2) -> {
						Assert.isTrue(
							Objects.equals(attributeType, attributeType2),
							"Ambiguous situation - there are two attributes with the same name and different definition:\n" +
								attributeType + "\n" +
								attributeType2
						);
						return attributeType;
					}
				)
			)
		);
	}

}
