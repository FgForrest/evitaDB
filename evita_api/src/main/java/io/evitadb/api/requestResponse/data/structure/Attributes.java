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

import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.query.filter.AttributeContains;
import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * Entity (global / relative) attributes allows defining set of data that are fetched in bulk along with the entity body.
 * Attributes may be indexed for fast filtering ({@link AttributeSchemaContract#isFilterable()}) or can be used to sort along
 * ({@link AttributeSchemaContract#isSortable()}). Attributes are not automatically indexed in order not to waste precious
 * memory space for data that will never be used in search queries.
 *
 * Filtering in attributes is executed by using constraints like {@link io.evitadb.api.query.filter.And},
 * {@link io.evitadb.api.query.filter.Not}, {@link AttributeEquals}, {@link AttributeContains}
 * and many others. Sorting can be achieved with {@link AttributeNatural} or others.
 *
 * Attributes are not recommended for bigger data as they are all loaded at once when {@link AttributeContent}
 * requirement is used. Large data that are occasionally used store in {@link AssociatedData}.
 *
 * Class is immutable on purpose - we want to support caching the entities in a shared cache and accessed by many threads.
 * For altering the contents use {@link InitialAttributesBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode
@Immutable
@ThreadSafe
public class Attributes implements AttributesContract {
	@Serial private static final long serialVersionUID = -1474840271286135157L;

	/**
	 * Definition of the entity schema.
	 */
	final EntitySchemaContract entitySchema;
	/**
	 * Definition of the reference schema.
	 */
	final ReferenceSchemaContract referenceSchema;
	/**
	 * Contains locale insensitive attribute values - simple key → value association map.
	 */
	final Map<AttributeKey, AttributeValue> attributeValues;
	/**
	 * Contains attribute definition that is built up along way with attribute adding or it may be directly filled
	 * in from the engine when entity with attributes is loaded from persistent storage.
	 */
	@Getter final Map<String, AttributeSchemaContract> attributeTypes;
	/**
	 * Optimization that ensures that expensive attribute name resolving happens only once.
	 */
	private Set<String> attributeNames;
	/**
	 * Contains set of all locales that has at least one localized attribute.
	 */
	private Set<Locale> attributeLocales;

	/**
	 * Constructor should be used only when attributes are loaded from persistent storage.
	 * Constructor is meant to be internal to the Evita engine.
	 */
	public Attributes(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull Collection<AttributeValue> attributeValues,
		@Nonnull Map<String, AttributeSchemaContract> attributeTypes
	) {
		this.entitySchema = entitySchema;
		this.referenceSchema = referenceSchema;
		this.attributeValues = attributeValues
			.stream()
			.collect(
				Collectors.toMap(
					AttributesContract.AttributeValue::key,
					Function.identity(),
					(attributeValue, attributeValue2) -> {
						throw new EvitaInvalidUsageException("Duplicated attribute " + attributeValue.key() + "!");
					}
				)
			);
		this.attributeTypes = attributeTypes;
	}

	public Attributes(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema
	) {
		this.entitySchema = entitySchema;
		this.referenceSchema = referenceSchema;
		this.attributeValues = Collections.emptyMap();
		this.attributeTypes = entitySchema.getAttributes();
		this.attributeLocales = Collections.emptySet();
	}

	@Override
	@Nullable
	public <T extends Serializable> T getAttribute(@Nonnull String attributeName) {
		final AttributeSchemaContract attributeSchema = ofNullable(attributeTypes.get(attributeName))
			.orElseThrow(() -> referenceSchema == null ?
				new AttributeNotFoundException(attributeName, entitySchema) :
				new AttributeNotFoundException(attributeName, referenceSchema, entitySchema)
			);
		Assert.isTrue(
			!attributeSchema.isLocalized(),
			() -> ContextMissingException.attributeContextMissing(attributeName)
		);
		//noinspection unchecked
		return (T) ofNullable(attributeValues.get(new AttributeKey(attributeName)))
			.map(AttributesContract.AttributeValue::value)
			.orElse(null);
	}

	@Override
	@Nullable
	public <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName) {
		final AttributeSchemaContract attributeSchema = ofNullable(attributeTypes.get(attributeName))
			.orElseThrow(() -> referenceSchema == null ?
				new AttributeNotFoundException(attributeName, entitySchema) :
				new AttributeNotFoundException(attributeName, referenceSchema, entitySchema)
			);
		Assert.isTrue(
			!attributeSchema.isLocalized(),
			() -> ContextMissingException.attributeContextMissing(attributeName)
		);
		//noinspection unchecked
		return (T[]) ofNullable(attributeValues.get(new AttributeKey(attributeName)))
			.map(AttributesContract.AttributeValue::value)
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName) {
		final AttributeSchemaContract attributeSchema = ofNullable(attributeTypes.get(attributeName))
			.orElseThrow(() -> referenceSchema == null ?
				new AttributeNotFoundException(attributeName, entitySchema) :
				new AttributeNotFoundException(attributeName, referenceSchema, entitySchema)
			);
		if (attributeSchema.isLocalized()) {
			return empty();
		} else {
			return ofNullable(attributeValues.get(new AttributeKey(attributeName)));
		}
	}

	@Override
	@Nullable
	public <T extends Serializable> T getAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		ofNullable(attributeTypes.get(attributeName))
			.orElseThrow(() -> referenceSchema == null ?
				new AttributeNotFoundException(attributeName, entitySchema) :
				new AttributeNotFoundException(attributeName, referenceSchema, entitySchema)
			);
		//noinspection unchecked
		return (T) ofNullable(attributeValues.get(new AttributeKey(attributeName, locale)))
			.map(AttributesContract.AttributeValue::value)
			.orElseGet(() -> ofNullable(attributeValues.get(new AttributeKey(attributeName)))
				.map(AttributeValue::value)
				.orElse(null));
	}

	@Override
	@Nullable
	public <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName, @Nonnull Locale locale) {
		ofNullable(attributeTypes.get(attributeName))
			.orElseThrow(() -> referenceSchema == null ?
				new AttributeNotFoundException(attributeName, entitySchema) :
				new AttributeNotFoundException(attributeName, referenceSchema, entitySchema)
			);
		//noinspection unchecked,ConstantConditions
		return (T[]) ofNullable(attributeValues.get(new AttributeKey(attributeName, locale)))
			.map(AttributesContract.AttributeValue::value)
			.orElseGet(() -> ofNullable(attributeValues.get(new AttributeKey(attributeName)))
				.map(AttributeValue::value)
				.orElse(null));
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName, @Nonnull Locale locale) {
		ofNullable(attributeTypes.get(attributeName))
			.orElseThrow(() -> referenceSchema == null ?
				new AttributeNotFoundException(attributeName, entitySchema) :
				new AttributeNotFoundException(attributeName, referenceSchema, entitySchema)
			);
		return ofNullable(
			ofNullable(attributeValues.get(new AttributeKey(attributeName, locale)))
				.orElseGet(() -> attributeValues.get(new AttributeKey(attributeName)))
		);
	}

	@Override
	@Nonnull
	public Optional<AttributeSchemaContract> getAttributeSchema(@Nonnull String attributeName) {
		return ofNullable(attributeTypes.get(attributeName));
	}

	@Override
	@Nonnull
	public Set<String> getAttributeNames() {
		if (this.attributeNames == null) {
			this.attributeNames = this.attributeValues
				.keySet()
				.stream()
				.map(AttributesContract.AttributeKey::attributeName)
				.collect(Collectors.toSet());
		}
		return this.attributeNames;
	}

	/**
	 * Returns set of all keys (combination of attribute name and locale) registered in this attribute set.
	 */
	@Nonnull
	@Override
	public Set<AttributeKey> getAttributeKeys() {
		return this.attributeValues.keySet();
	}

	/**
	 * Returns collection of all values present in this object.
	 */
	@Nonnull
	public Collection<AttributeValue> getAttributeValues() {
		return this.attributeValues.values();
	}

	@Nonnull
	@Override
	public Collection<AttributeValue> getAttributeValues(@Nonnull String attributeName) {
		return attributeValues
			.entrySet()
			.stream()
			.filter(it -> attributeName.equals(it.getKey().attributeName()))
			.map(Entry::getValue)
			.collect(Collectors.toList());
	}

	@Nonnull
	@Override
	public Set<Locale> getAttributeLocales() {
		if (this.attributeLocales == null) {
			this.attributeLocales = this.attributeValues
				.values()
				.stream()
				.filter(Droppable::exists)
				.map(it -> it.key().locale())
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
		}
		return this.attributeLocales;
	}

	/**
	 * Returns attribute value for passed key.
	 */
	@Nonnull
	public Optional<AttributeValue> getAttributeValue(@Nonnull AttributeKey attributeKey) {
		return ofNullable(this.attributeValues.get(attributeKey));
	}

	/**
	 * Returns true if there is no attribute set.
	 */
	public boolean isEmpty() {
		return this.attributeValues.isEmpty();
	}

	@Override
	public String toString() {
		return isEmpty() ? "no attributes present" : getAttributeValues()
			.stream()
			.map(AttributeValue::toString)
			.collect(Collectors.joining("; "));
	}
}
