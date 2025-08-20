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
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
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
public abstract class Attributes<S extends AttributeSchemaContract> implements AttributesContract<S> {
	@Serial private static final long serialVersionUID = -1474840271286135157L;

	/**
	 * Definition of the entity schema.
	 */
	final EntitySchemaContract entitySchema;
	/**
	 * Contains locale insensitive attribute values - simple key → value association map.
	 */
	final Map<AttributeKey, AttributeValue> attributeValues;
	/**
	 * Contains attribute definition that is built up along way with attribute adding or it may be directly filled
	 * in from the engine when entity with attributes is loaded from persistent storage.
	 */
	@Getter final Map<String, S> attributeTypes;
	/**
	 * Optimization that ensures that expensive attribute name resolving happens only once.
	 */
	private Set<String> attributeNames;
	/**
	 * Optimization that ensures that expensive attribute name resolving happens only once.
	 */
	private Set<AttributeKey> attributeKeys;
	/**
	 * Optimization that ensures that expensive attribute name resolving happens only once.
	 */
	private List<AttributeValue> filteredAttributeValues;
	/**
	 * Contains set of all locales that has at least one localized attribute.
	 */
	private Set<Locale> attributeLocales;

	/**
	 * Constructor should be used only when attributes are loaded from persistent storage.
	 * Constructor is meant to be internal to the Evita engine.
	 */
	protected Attributes(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Collection<AttributeValue> attributeValues,
		@Nonnull Map<String, S> attributeTypes
	) {
		this.entitySchema = entitySchema;
		this.attributeValues = attributeValues
			.stream()
			.collect(
				Collectors.toMap(
					AttributesContract.AttributeValue::key,
					Function.identity(),
					(attributeValue, attributeValue2) -> {
						throw new EvitaInvalidUsageException("Duplicated attribute " + attributeValue.key() + "!");
					},
					LinkedHashMap::new
				)
			);
		this.attributeTypes = attributeTypes;
		this.attributeLocales = attributeValues.stream()
			.filter(Droppable::exists)
			.map(it -> it.key().locale())
			.filter(Objects::nonNull)
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	/**
	 * Constructor should be used only when attributes are loaded from persistent storage.
	 * Constructor is meant to be internal to the Evita engine.
	 */
	protected Attributes(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Map<AttributeKey, AttributeValue> attributeValues,
		@Nonnull Map<String, S> attributeTypes
	) {
		this.entitySchema = entitySchema;
		this.attributeValues = attributeValues;
		this.attributeTypes = attributeTypes;
		this.attributeLocales = attributeValues.values().stream()
			.filter(Droppable::exists)
			.map(it -> it.key().locale())
			.filter(Objects::nonNull)
			.collect(Collectors.toCollection(LinkedHashSet::new));
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
	public <T extends Serializable> T getAttribute(@Nonnull String attributeName) {
		final AttributeSchemaContract attributeSchema = ofNullable(this.attributeTypes.get(attributeName))
			.orElseThrow(() -> createAttributeNotFoundException(attributeName));
		Assert.isTrue(
			!attributeSchema.isLocalized(),
			() -> ContextMissingException.localeForAttributeContextMissing(attributeName)
		);
		//noinspection unchecked
		return (T) ofNullable(this.attributeValues.get(new AttributeKey(attributeName)))
			.map(AttributesContract.AttributeValue::value)
			.orElse(null);
	}

	@Override
	@Nullable
	public <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName) {
		final AttributeSchemaContract attributeSchema = ofNullable(this.attributeTypes.get(attributeName))
			.orElseThrow(() -> createAttributeNotFoundException(attributeName));
		Assert.isTrue(
			!attributeSchema.isLocalized(),
			() -> ContextMissingException.localeForAttributeContextMissing(attributeName)
		);
		//noinspection unchecked
		return (T[]) ofNullable(this.attributeValues.get(new AttributeKey(attributeName)))
			.map(AttributesContract.AttributeValue::value)
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName) {
		final AttributeSchemaContract attributeSchema = ofNullable(this.attributeTypes.get(attributeName))
			.orElseThrow(() -> createAttributeNotFoundException(attributeName));
		if (attributeSchema.isLocalized()) {
			return empty();
		} else {
			return ofNullable(this.attributeValues.get(new AttributeKey(attributeName)));
		}
	}

	@Override
	@Nullable
	public <T extends Serializable> T getAttribute(@Nonnull String attributeName, @Nonnull Locale locale) {
		final AttributeSchemaContract schema = ofNullable(this.attributeTypes.get(attributeName))
			.orElseThrow(() -> createAttributeNotFoundException(attributeName));
		//noinspection unchecked
		return (T) (schema.isLocalized() ?
			ofNullable(this.attributeValues.get(new AttributeKey(attributeName, locale))) :
			ofNullable(this.attributeValues.get(new AttributeKey(attributeName))))
			.map(AttributesContract.AttributeValue::value)
			.orElse(null);
	}

	@Override
	@Nullable
	public <T extends Serializable> T[] getAttributeArray(@Nonnull String attributeName, @Nonnull Locale locale) {
		final AttributeSchemaContract schema = ofNullable(this.attributeTypes.get(attributeName))
			.orElseThrow(() -> createAttributeNotFoundException(attributeName));
		//noinspection unchecked,ConstantConditions
		return (T[]) (schema.isLocalized() ?
			ofNullable(this.attributeValues.get(new AttributeKey(attributeName, locale))) :
			ofNullable(this.attributeValues.get(new AttributeKey(attributeName))))
			.map(AttributesContract.AttributeValue::value)
			.orElse(null);
	}

	@Override
	@Nonnull
	public Optional<AttributeValue> getAttributeValue(@Nonnull String attributeName, @Nonnull Locale locale) {
		final AttributeSchemaContract schema = ofNullable(this.attributeTypes.get(attributeName))
			.orElseThrow(() -> createAttributeNotFoundException(attributeName));
		return schema.isLocalized() ?
			ofNullable(this.attributeValues.get(new AttributeKey(attributeName, locale))) :
			ofNullable(this.attributeValues.get(new AttributeKey(attributeName)));
	}

	@Override
	@Nonnull
	public Optional<S> getAttributeSchema(@Nonnull String attributeName) {
		return ofNullable(this.attributeTypes.get(attributeName));
	}

	@Override
	@Nonnull
	public Set<String> getAttributeNames() {
		if (this.attributeNames == null) {
			this.attributeNames = this.attributeValues
				.values()
				.stream()
				.filter(attributeValue -> attributeValue.value() != null)
				.map(attributeValue -> attributeValue.key().attributeName())
				.filter(key -> this.attributeTypes.get(key) != null)
				.collect(
					Collectors.toCollection(
						() -> CollectionUtils.createLinkedHashSet(this.attributeValues.size())
					)
				);
		}
		return this.attributeNames;
	}

	/**
	 * Returns set of all keys (combination of attribute name and locale) registered in this attribute set.
	 */
	@Nonnull
	@Override
	public Set<AttributeKey> getAttributeKeys() {
		if (this.attributeKeys == null) {
			this.attributeKeys = this.attributeValues
				.values()
				.stream()
				.filter(attributeValue -> attributeValue.value() != null)
				.map(AttributeValue::key)
				.filter(key -> this.attributeTypes.get(key.attributeName()) != null)
				.collect(Collectors.toUnmodifiableSet());
		}
		return this.attributeKeys;
	}

	@Override
	@Nonnull
	public Optional<AttributeValue> getAttributeValue(@Nonnull AttributeKey attributeKey) {
		final String attributeName = attributeKey.attributeName();
		final AttributeSchemaContract schema = ofNullable(this.attributeTypes.get(attributeName))
			.orElseThrow(() -> createAttributeNotFoundException(attributeName));
		return schema.isLocalized() ?
			ofNullable(this.attributeValues.get(attributeKey)) :
			ofNullable(this.attributeValues.get(attributeKey.localized() ? new AttributeKey(attributeName) : attributeKey));
	}

	/**
	 * Returns collection of all values present in this object.
	 */
	@Nonnull
	public Collection<AttributeValue> getAttributeValues() {
		if (this.filteredAttributeValues == null) {
			this.filteredAttributeValues = this.attributeValues
				.values()
				.stream()
				.filter(attributeValue -> this.attributeTypes.get(attributeValue.key().attributeName()) != null)
				.toList();
		}
		return this.filteredAttributeValues;
	}

	@Nonnull
	@Override
	public Collection<AttributeValue> getAttributeValues(@Nonnull String attributeName) {
		if (this.attributeTypes.get(attributeName) == null) {
			throw createAttributeNotFoundException(attributeName);
		} else {
			return this.attributeValues
				.entrySet()
				.stream()
				.filter(it -> attributeName.equals(it.getKey().attributeName()))
				.map(Entry::getValue)
				.collect(Collectors.toList());
		}
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
	 * Returns attribute by business key without checking if the attribute is defined in the schema.
	 * Method is part of PRIVATE API.
	 */
	@Nonnull
	public Optional<AttributeValue> getAttributeValueWithoutSchemaCheck(@Nonnull AttributeKey attributeKey) {
		return ofNullable(this.attributeValues.get(attributeKey))
			.or(() -> attributeKey.localized() ? ofNullable(this.attributeValues.get(new AttributeKey(attributeKey.attributeName()))) : empty());
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

	@Nonnull
	protected abstract AttributeNotFoundException createAttributeNotFoundException(@Nonnull String attributeName);

}
