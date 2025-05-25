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

import io.evitadb.api.exception.AssociatedDataNotFoundException;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.query.require.AssociatedDataContent;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AssociatedDataEditor.AssociatedDataBuilder;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.data.ComplexDataObjectConverter;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.utils.CollectionUtils.createLinkedHashMap;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * Associated data carry additional data entries that are never used for filtering / sorting but may be needed to be fetched
 * along with entity in order to present data to the target consumer (i.e. user / API / bot). Associated data may be stored
 * in slower storage and may contain wide range of data types - from small ones (i.e. numbers, strings, dates) up to large
 * binary arrays representing entire files (i.e. pictures, documents).
 *
 * The search query must contain specific {@link AssociatedDataContent} requirement in order
 * associated data are fetched along with the entity. Associated data are stored and fetched separately by their name.
 *
 * Class is immutable on purpose - we want to support caching the entities in a shared cache and accessed by many threads.
 * For altering the contents use {@link AssociatedDataBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode
@Immutable
@ThreadSafe
public class AssociatedData implements AssociatedDataContract {
	@Serial private static final long serialVersionUID = 4916435515883999950L;
	/**
	 * Definition of the entity schema.
	 */
	final EntitySchemaContract entitySchema;
	/**
	 * Contains locale insensitive associatedData values - simple key → value association map.
	 */
	final Map<AssociatedDataKey, AssociatedDataValue> associatedDataValues;
	/**
	 * Contains associatedData definition that is built up along way with associatedData adding or it may be directly filled
	 * in from the engine when entity with associated data is loaded from persistent storage.
	 */
	final Map<String, AssociatedDataSchemaContract> associatedDataTypes;
	/**
	 * Optimization that ensures that expensive associatedData name resolving happens only once.
	 */
	private Set<String> associatedDataNames;
	/**
	 * Optimization that ensures that expensive associatedData name resolving happens only once.
	 */
	private Set<AssociatedDataKey> associatedDataKeys;
	/**
	 * Optimization that ensures that expensive associatedData name resolving happens only once.
	 */
	private List<AssociatedDataValue> filteredAssociatedDataValues;
	/**
	 * Optimization that ensures that expensive associatedData locale resolving happens only once.
	 */
	private Set<Locale> associatedDataLocales;

	/**
	 * Constructor should be used only when associated data are loaded from persistent storage.
	 * Constructor is meant to be internal to the Evita engine.
	 */
	public AssociatedData(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Collection<AssociatedDataValue> associatedDataValues,
		@Nonnull Map<String, AssociatedDataSchemaContract> associatedDataTypes
	) {
		this.entitySchema = entitySchema;
		this.associatedDataValues = createLinkedHashMap(associatedDataValues.size());
		for (AssociatedDataValue associatedDataValue : associatedDataValues) {
			this.associatedDataValues.put(associatedDataValue.key(), associatedDataValue);
		}
		this.associatedDataTypes = associatedDataTypes;
	}

	/**
	 * Constructor should be used only when associated data are loaded from persistent storage.
	 * Constructor is meant to be internal to the Evita engine.
	 */
	public AssociatedData(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable Stream<AssociatedDataValue> associatedDataValues
	) {
		this.entitySchema = entitySchema;
		this.associatedDataValues = ofNullable(associatedDataValues)
			.map(it -> it.collect(
					Collectors.toMap(
						AssociatedDataValue::key,
						Function.identity(),
						(attributeValue, attributeValue2) -> {
							throw new EvitaInvalidUsageException("Duplicated attribute " + attributeValue.key() + "!");
						},
						() -> (Map<AssociatedDataKey, AssociatedDataValue>) new LinkedHashMap<AssociatedDataKey, AssociatedDataValue>()
					)
				)
			)
			.orElse(Collections.emptyMap());
		this.associatedDataTypes = entitySchema.getAssociatedData();
	}

	/**
	 * Constructor should be used only when associated data are reconstructed in APIs.
	 */
	public AssociatedData(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull LinkedHashMap<AssociatedDataKey, AssociatedDataValue> associatedDataValues
	) {
		this.entitySchema = entitySchema;
		this.associatedDataValues = associatedDataValues;
		this.associatedDataTypes = entitySchema.getAssociatedData();
	}

	/**
	 * Constructor should be used when new associated data are added to the entity.
	 *
	 * @param entitySchema entity schema
	 */
	public AssociatedData(@Nonnull EntitySchemaContract entitySchema) {
		this.entitySchema = entitySchema;
		this.associatedDataValues = Collections.emptyMap();
		this.associatedDataTypes = entitySchema.getAssociatedData();
	}

	@Override
	public boolean associatedDataAvailable() {
		return true;
	}

	@Override
	public boolean associatedDataAvailable(@Nonnull Locale locale) {
		return true;
	}

	@Override
	public boolean associatedDataAvailable(@Nonnull String associatedDataName) {
		return true;
	}

	@Override
	public boolean associatedDataAvailable(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		return true;
	}

	@Override
	@Nullable
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName) {
		final AssociatedDataSchemaContract associatedDataSchema = ofNullable(this.associatedDataTypes.get(associatedDataName))
			.orElseThrow(() -> new AssociatedDataNotFoundException(associatedDataName, this.entitySchema));
		Assert.isTrue(
			!associatedDataSchema.isLocalized(),
			() -> ContextMissingException.localeForAssociatedDataContextMissing(associatedDataName)
		);
		//noinspection unchecked
		return (T) ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName)))
			.map(AssociatedDataContract.AssociatedDataValue::value)
			.orElse(null);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Class<T> dtoType, @Nonnull ReflectionLookup reflectionLookup) {
		final AssociatedDataSchemaContract associatedDataSchema = ofNullable(this.associatedDataTypes.get(associatedDataName))
			.orElseThrow(() -> new AssociatedDataNotFoundException(associatedDataName, this.entitySchema));
		Assert.isTrue(
			!associatedDataSchema.isLocalized(),
			() -> ContextMissingException.localeForAssociatedDataContextMissing(associatedDataName)
		);
		return ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName)))
			.map(AssociatedDataContract.AssociatedDataValue::value)
			.map(it -> ComplexDataObjectConverter.getOriginalForm(it, dtoType, reflectionLookup))
			.orElse(null);
	}

	@Override
	@Nullable
	public <T extends Serializable> T[] getAssociatedDataArray(@Nonnull String associatedDataName) {
		final AssociatedDataSchemaContract associatedDataSchema = ofNullable(this.associatedDataTypes.get(associatedDataName))
			.orElseThrow(() -> new AssociatedDataNotFoundException(associatedDataName, this.entitySchema));
		Assert.isTrue(
			!associatedDataSchema.isLocalized(),
			() -> ContextMissingException.localeForAssociatedDataContextMissing(associatedDataName)
		);
		//noinspection unchecked
		return (T[]) ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName)))
			.map(AssociatedDataContract.AssociatedDataValue::value)
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull String associatedDataName) {
		final AssociatedDataSchemaContract associatedDataSchema = ofNullable(this.associatedDataTypes.get(associatedDataName))
			.orElseThrow(() -> new AssociatedDataNotFoundException(associatedDataName, this.entitySchema));
		if (associatedDataSchema.isLocalized()) {
			return empty();
		} else {
			return ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName)));
		}
	}

	@Override
	@Nullable
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		final AssociatedDataSchemaContract schema = ofNullable(this.associatedDataTypes.get(associatedDataName))
			.orElseThrow(() -> new AssociatedDataNotFoundException(associatedDataName, this.entitySchema));
		//noinspection unchecked
		return (T) (schema.isLocalized() ?
			ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName, locale))) :
			ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName))))
			.map(AssociatedDataContract.AssociatedDataValue::value)
			.orElse(null);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nonnull Class<T> dtoType, @Nonnull ReflectionLookup reflectionLookup) {
		final AssociatedDataSchemaContract schema = ofNullable(this.associatedDataTypes.get(associatedDataName))
			.orElseThrow(() -> new AssociatedDataNotFoundException(associatedDataName, this.entitySchema));
		return (T) (schema.isLocalized() ?
			ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName, locale))) :
			ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName))))
			.map(AssociatedDataContract.AssociatedDataValue::value)
			.map(it -> ComplexDataObjectConverter.getOriginalForm(it, dtoType, reflectionLookup))
			.orElse(null);
	}

	@Override
	@Nullable
	public <T extends Serializable> T[] getAssociatedDataArray(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		final AssociatedDataSchemaContract schema = ofNullable(this.associatedDataTypes.get(associatedDataName))
			.orElseThrow(() -> new AssociatedDataNotFoundException(associatedDataName, this.entitySchema));
		//noinspection unchecked,ConstantConditions
		return (T[]) (schema.isLocalized() ?
			ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName, locale))) :
			ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName))))
			.map(AssociatedDataContract.AssociatedDataValue::value)
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		final AssociatedDataSchemaContract schema = ofNullable(this.associatedDataTypes.get(associatedDataName))
			.orElseThrow(() -> new AssociatedDataNotFoundException(associatedDataName, this.entitySchema));
		return schema.isLocalized() ?
			ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName, locale))) :
			ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName)));
	}

	@Override
	@Nonnull
	public Optional<AssociatedDataSchemaContract> getAssociatedDataSchema(@Nonnull String associatedDataName) {
		return ofNullable(this.associatedDataTypes.get(associatedDataName));
	}

	@Override
	@Nonnull
	public Set<String> getAssociatedDataNames() {
		if (this.associatedDataNames == null) {
			this.associatedDataNames = this.associatedDataValues
				.values()
				.stream()
				.filter(ad -> ad.value() != null)
				.map(AssociatedDataValue::key)
				.map(AssociatedDataKey::associatedDataName)
				.filter(dataName -> this.associatedDataTypes.get(dataName) != null)
				.collect(Collectors.toSet());
		}
		return this.associatedDataNames;
	}

	@Nonnull
	@Override
	public Set<AssociatedDataKey> getAssociatedDataKeys() {
		if (this.associatedDataKeys == null) {
			this.associatedDataKeys = this.associatedDataValues
				.values()
				.stream()
				.filter(ad -> ad.value() != null)
				.map(AssociatedDataValue::key)
				.filter(key -> this.associatedDataTypes.get(key.associatedDataName()) != null)
				.collect(Collectors.toUnmodifiableSet());
		}
		return this.associatedDataKeys;
	}

	/**
	 * Returns collection of all associated data of the entity.
	 */
	@Override
	@Nonnull
	public Collection<AssociatedDataValue> getAssociatedDataValues() {
		if (this.filteredAssociatedDataValues == null) {
			this.filteredAssociatedDataValues = this.associatedDataValues
				.values()
				.stream()
				.filter(ad -> this.associatedDataTypes.get(ad.key().associatedDataName()) != null)
				.collect(Collectors.toList());
		}
		return this.filteredAssociatedDataValues;
	}

	@Nonnull
	@Override
	public Collection<AssociatedDataValue> getAssociatedDataValues(@Nonnull String associatedDataName) {
		if (this.associatedDataTypes.get(associatedDataName) == null) {
			throw new AssociatedDataNotFoundException(associatedDataName, this.entitySchema);
		} else {
			return this.associatedDataValues
				.entrySet()
				.stream().filter(it -> associatedDataName.equals(it.getKey().associatedDataName()))
				.map(Entry::getValue)
				.collect(Collectors.toList());
		}
	}

	@Nonnull
	@Override
	public Set<Locale> getAssociatedDataLocales() {
		if (this.associatedDataLocales == null) {
			this.associatedDataLocales = this.associatedDataValues
				.keySet()
				.stream()
				.map(AssociatedDataKey::locale)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
		}
		return this.associatedDataLocales;
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull AssociatedDataKey associatedDataKey) {
		final String associatedDataName = associatedDataKey.associatedDataName();
		final AssociatedDataSchemaContract schema = ofNullable(this.associatedDataTypes.get(associatedDataName))
			.orElseThrow(() -> new AssociatedDataNotFoundException(associatedDataName, this.entitySchema));
		return schema.isLocalized() ?
			ofNullable(this.associatedDataValues.get(associatedDataKey)) :
			ofNullable(this.associatedDataValues.get(associatedDataKey.localized() ? new AssociatedDataKey(associatedDataName) : associatedDataKey));
	}

	/**
	 * Returns attribute by business key without checking if the attribute is defined in the schema.
	 * Method is part of PRIVATE API.
	 */
	@Nonnull
	public Optional<AssociatedDataValue> getAssociatedDataValueWithoutSchemaCheck(@Nonnull AssociatedDataKey associatedDataKey) {
		return ofNullable(this.associatedDataValues.get(associatedDataKey))
			.or(() -> associatedDataKey.localized() ? ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataKey.associatedDataName()))) : empty());
	}

	/**
	 * Returns true if there is no associated data set.
	 */
	public boolean isEmpty() {
		return this.associatedDataValues.isEmpty();
	}

	@Override
	public String toString() {
		return getAssociatedDataValues()
			.stream()
			.map(AssociatedDataValue::toString)
			.collect(Collectors.joining("; "));
	}

}
