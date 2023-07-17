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

import io.evitadb.api.query.require.AssociatedDataContent;
import io.evitadb.api.requestResponse.data.AssociatedDataContract;
import io.evitadb.api.requestResponse.data.AssociatedDataEditor.AssociatedDataBuilder;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.data.ComplexDataObjectConverter;
import io.evitadb.exception.EvitaInvalidUsageException;
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
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.utils.CollectionUtils.createHashMap;
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
	 * Optimization that ensures that expensive associatedData locale resolving happens only once.
	 */
	private Set<Locale> associatedDataLocales;

	/**
	 * Constructor should be used only when associated data are loaded from persistent storage.
	 * Constructor is meant to be internal to the Evita engine.
	 */
	public AssociatedData(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull Set<AssociatedDataKey> associatedDataKeys,
		@Nullable Collection<AssociatedDataValue> associatedDataValues
	) {
		this.entitySchema = entitySchema;
		this.associatedDataValues = new TreeMap<>();
		for (AssociatedDataKey associatedDataKey : associatedDataKeys) {
			this.associatedDataValues.put(associatedDataKey, null);
		}
		if (associatedDataValues != null) {
			for (AssociatedDataValue associatedDataValue : associatedDataValues) {
				this.associatedDataValues.put(associatedDataValue.key(), associatedDataValue);
			}
		}
		this.associatedDataTypes = entitySchema.getAssociatedData();
	}

	/**
	 * Constructor should be used only when associated data are loaded from persistent storage.
	 * Constructor is meant to be internal to the Evita engine.
	 */
	public AssociatedData(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable Collection<AssociatedDataValue> associatedDataValues
	) {
		this.entitySchema = entitySchema;
		this.associatedDataValues = ofNullable(associatedDataValues)
			.map(it -> it.stream()
				.collect(
					Collectors.toMap(
						AssociatedDataValue::key,
						Function.identity(),
						(attributeValue, attributeValue2) -> {
							throw new EvitaInvalidUsageException("Duplicated attribute " + attributeValue.key() + "!");
						},
						TreeMap::new
					)
				)
			)
			.orElse(new TreeMap<>());
		this.associatedDataTypes = entitySchema.getAssociatedData();
	}

	public AssociatedData(@Nonnull EntitySchemaContract entitySchema) {
		this.entitySchema = entitySchema;
		this.associatedDataValues = Collections.emptyMap();
		this.associatedDataTypes = entitySchema.getAssociatedData();
	}

	@Override
	@Nullable
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName) {
		//noinspection unchecked
		return (T) ofNullable(associatedDataValues.get(new AssociatedDataKey(associatedDataName)))
			.map(AssociatedDataValue::value)
			.orElse(null);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Class<T> dtoType, @Nonnull ReflectionLookup reflectionLookup) {
		return ofNullable(associatedDataValues.get(new AssociatedDataKey(associatedDataName)))
			.map(AssociatedDataValue::value)
			.map(it -> ComplexDataObjectConverter.getOriginalForm(it, dtoType, reflectionLookup))
			.orElse(null);
	}

	@Override
	@Nullable
	public <T extends Serializable> T[] getAssociatedDataArray(@Nonnull String associatedDataName) {
		//noinspection unchecked
		return (T[]) ofNullable(associatedDataValues.get(new AssociatedDataKey(associatedDataName)))
			.map(AssociatedDataValue::value)
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull String associatedDataName) {
		return ofNullable(associatedDataValues.get(new AssociatedDataKey(associatedDataName)));
	}

	@Override
	@Nullable
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		//noinspection unchecked
		return (T) ofNullable(associatedDataValues.get(new AssociatedDataKey(associatedDataName, locale)))
			.map(AssociatedDataValue::value)
			.orElseGet(() -> getAssociatedData(associatedDataName));
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nonnull Class<T> dtoType, @Nonnull ReflectionLookup reflectionLookup) {
		return ofNullable(associatedDataValues.get(new AssociatedDataKey(associatedDataName, locale)))
			.map(AssociatedDataValue::value)
			.map(it -> ComplexDataObjectConverter.getOriginalForm(it, dtoType, reflectionLookup))
			.orElseGet(() -> getAssociatedData(associatedDataName));
	}

	@Override
	@Nullable
	public <T extends Serializable> T[] getAssociatedDataArray(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		//noinspection unchecked
		return (T[]) ofNullable(associatedDataValues.get(new AssociatedDataKey(associatedDataName, locale)))
			.map(AssociatedDataValue::value)
			.orElseGet(() -> getAssociatedData(associatedDataName));
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		return ofNullable(
			ofNullable(associatedDataValues.get(new AssociatedDataKey(associatedDataName, locale)))
				.orElseGet(() -> associatedDataValues.get(new AssociatedDataKey(associatedDataName)))
		);
	}

	@Override
	@Nonnull
	public Optional<AssociatedDataSchemaContract> getAssociatedDataSchema(@Nonnull String associatedDataName) {
		return ofNullable(associatedDataTypes.get(associatedDataName));
	}

	@Override
	@Nonnull
	public Set<String> getAssociatedDataNames() {
		if (this.associatedDataNames == null) {
			this.associatedDataNames = this.associatedDataValues
				.keySet()
				.stream()
				.map(AssociatedDataKey::associatedDataName)
				.collect(Collectors.toCollection(TreeSet::new));
		}
		return this.associatedDataNames;
	}

	@Nonnull
	@Override
	public Set<AssociatedDataKey> getAssociatedDataKeys() {
		return this.associatedDataValues.keySet();
	}

	/**
	 * Returns collection of all associatedDatas of the entity.
	 */
	@Override
	@Nonnull
	public Collection<AssociatedDataValue> getAssociatedDataValues() {
		return this.associatedDataValues
			.values()
			.stream()
			.filter(Objects::nonNull)
			.collect(Collectors.toList());
	}

	@Nonnull
	@Override
	public Collection<AssociatedDataValue> getAssociatedDataValues(@Nonnull String associatedDataName) {
		return associatedDataValues
			.entrySet()
			.stream().filter(it -> associatedDataName.equals(it.getKey().associatedDataName()))
			.map(Entry::getValue)
			.collect(Collectors.toList());
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

	@Nullable
	public AssociatedDataValue getAssociatedDataValue(@Nonnull AssociatedDataKey associatedDataKey) {
		return associatedDataValues.get(associatedDataKey);
	}

	/**
	 * Returns associatedData value for passed key.
	 */
	@Nullable
	public AssociatedDataValue getAssociatedData(@Nonnull AssociatedDataKey associatedDataKey) {
		return this.associatedDataValues.get(associatedDataKey);
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
