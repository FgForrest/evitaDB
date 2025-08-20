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

import io.evitadb.api.requestResponse.data.AssociatedDataEditor.AssociatedDataBuilder;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.RemoveAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.data.ComplexDataObjectConverter;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ReflectionLookup;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.requestResponse.data.structure.InitialAssociatedDataBuilder.verifyAssociatedDataIsInSchemaAndTypeMatch;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * Class supports intermediate mutable object that allows {@link AssociatedData} container rebuilding.
 * We need to closely monitor what associatedData is changed and how. These changes are wrapped in so called mutations
 * (see {@link AssociatedDataMutation} and its implementations) and mutations can be then processed transactionally by
 * the engine.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ExistingAssociatedDataBuilder implements AssociatedDataBuilder {
	@Serial private static final long serialVersionUID = 3382748927871753611L;

	/**
	 * Definition of the entity schema.
	 */
	private final EntitySchemaContract entitySchema;
	/**
	 * Initial set of associatedDatas that is going to be modified by this builder.
	 */
	private final AssociatedData baseAssociatedData;
	/**
	 * This predicate filters out associated data that were not fetched in query.
	 */
	@Getter private final SerializablePredicate<AssociatedDataValue> associatedDataPredicate;
	/**
	 * Contains locale insensitive associatedData values - simple key → value association map.
	 */
	private final Map<AssociatedDataKey, AssociatedDataMutation> associatedDataMutations;

	/**
	 * AssociatedDataBuilder constructor that will be used for building brand new {@link AssociatedData} container.
	 */
	public ExistingAssociatedDataBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull AssociatedData baseAssociatedData
	) {
		this.entitySchema = entitySchema;
		this.associatedDataMutations = new HashMap<>();
		this.baseAssociatedData = baseAssociatedData;
		this.associatedDataPredicate = Droppable::exists;
	}

	/**
	 * AssociatedDataBuilder constructor that will be used for building brand new {@link AssociatedData} container.
	 */
	public ExistingAssociatedDataBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull AssociatedData baseAssociatedData,
		@Nonnull SerializablePredicate<AssociatedDataValue> associatedDataPredicate
	) {
		this.entitySchema = entitySchema;
		this.associatedDataMutations = new HashMap<>();
		this.baseAssociatedData = baseAssociatedData;
		this.associatedDataPredicate = associatedDataPredicate;
	}

	/**
	 * Method allows adding specific mutation on the fly.
	 */
	public void addMutation(@Nonnull AssociatedDataMutation localMutation) {
		if (localMutation instanceof UpsertAssociatedDataMutation upsertAssociatedDataMutation) {
			final AssociatedDataKey associatedDataKey = upsertAssociatedDataMutation.getAssociatedDataKey();
			final Serializable associatedDataValue = upsertAssociatedDataMutation.getAssociatedDataValue();
			verifyAssociatedDataIsInSchemaAndTypeMatch(
				this.baseAssociatedData.entitySchema,
				associatedDataKey.associatedDataName(),
				associatedDataValue.getClass(),
				associatedDataKey.locale()
			);
			this.associatedDataMutations.put(associatedDataKey, upsertAssociatedDataMutation);
		} else if (localMutation instanceof RemoveAssociatedDataMutation removeAssociatedDataMutation) {
			final AssociatedDataKey associatedDataKey = removeAssociatedDataMutation.getAssociatedDataKey();
			if (this.baseAssociatedData.getAssociatedDataValueWithoutSchemaCheck(associatedDataKey).isEmpty()) {
				this.associatedDataMutations.remove(associatedDataKey);
			} else {
				this.associatedDataMutations.put(associatedDataKey, removeAssociatedDataMutation);
			}
		} else {
			throw new GenericEvitaInternalError("Unknown Evita price mutation: `" + localMutation.getClass() + "`!");
		}
	}

	@Override
	@Nonnull
	public AssociatedDataBuilder removeAssociatedData(@Nonnull String associatedDataName) {
		final AssociatedDataKey associatedDataKey = new AssociatedDataKey(associatedDataName);
		if (this.baseAssociatedData.getAssociatedDataValueWithoutSchemaCheck(associatedDataKey).filter(Droppable::exists).isEmpty()) {
			this.associatedDataMutations.remove(associatedDataKey);
		} else {
			this.associatedDataMutations.put(associatedDataKey, new RemoveAssociatedDataMutation(associatedDataKey));
		}
		return this;
	}

	@Override
	@Nonnull
	public <T extends Serializable> AssociatedDataBuilder setAssociatedData(
		@Nonnull String associatedDataName,
		@Nullable T associatedDataValue
	) {
		if (associatedDataValue == null || associatedDataValue instanceof Object[] arr && ArrayUtils.isEmpty(arr)) {
			return removeAssociatedData(associatedDataName);
		} else {
			final Serializable valueToStore = ComplexDataObjectConverter.getSerializableForm(associatedDataValue);
			final AssociatedDataKey associatedDataKey = new AssociatedDataKey(associatedDataName);
			verifyAssociatedDataIsInSchemaAndTypeMatch(this.baseAssociatedData.entitySchema, associatedDataName, valueToStore.getClass());
			this.associatedDataMutations.put(
				associatedDataKey,
				new UpsertAssociatedDataMutation(associatedDataKey, valueToStore)
			);
			return this;
		}
	}

	@Override
	@Nonnull
	public <T extends Serializable> AssociatedDataBuilder setAssociatedData(@Nonnull String associatedDataName, @Nonnull T[] associatedDataValue) {
		final Serializable[] valueToStore = new Serializable[associatedDataValue.length];
		for (int i = 0; i < associatedDataValue.length; i++) {
			final T dataItem = associatedDataValue[i];
			valueToStore[i] = ComplexDataObjectConverter.getSerializableForm(dataItem);
		}
		final AssociatedDataKey associatedDataKey = new AssociatedDataKey(associatedDataName);
		verifyAssociatedDataIsInSchemaAndTypeMatch(this.baseAssociatedData.entitySchema, associatedDataName, valueToStore.getClass());
		this.associatedDataMutations.put(
			associatedDataKey,
			new UpsertAssociatedDataMutation(associatedDataKey, valueToStore)
		);
		return this;
	}

	@Override
	@Nonnull
	public AssociatedDataBuilder removeAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		final AssociatedDataKey associatedDataKey = new AssociatedDataKey(associatedDataName, locale);
		if (this.baseAssociatedData.getAssociatedDataValueWithoutSchemaCheck(associatedDataKey).filter(Droppable::exists).isEmpty()) {
			this.associatedDataMutations.remove(associatedDataKey);
		} else {
			this.associatedDataMutations.put(associatedDataKey, new RemoveAssociatedDataMutation(associatedDataKey));
		}
		return this;
	}

	@Override
	@Nonnull
	public <T extends Serializable> AssociatedDataBuilder setAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nullable T associatedDataValue) {
		if (associatedDataValue == null || associatedDataValue instanceof Object[] arr && ArrayUtils.isEmpty(arr)) {
			return removeAssociatedData(associatedDataName, locale);
		} else {
			final Serializable valueToStore = ComplexDataObjectConverter.getSerializableForm(associatedDataValue);
			final AssociatedDataKey associatedDataKey = new AssociatedDataKey(associatedDataName, locale);
			verifyAssociatedDataIsInSchemaAndTypeMatch(this.baseAssociatedData.entitySchema, associatedDataName, valueToStore.getClass(), locale);
			this.associatedDataMutations.put(
				associatedDataKey,
				new UpsertAssociatedDataMutation(associatedDataKey, valueToStore)
			);
			return this;
		}
	}

	@Override
	@Nonnull
	public <T extends Serializable> AssociatedDataBuilder setAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nullable T[] associatedDataValue) {
		if (associatedDataValue == null) {
			return removeAssociatedData(associatedDataName, locale);
		} else {
			final Serializable[] valueToStore = new Serializable[associatedDataValue.length];
			for (int i = 0; i < associatedDataValue.length; i++) {
				final T dataItem = associatedDataValue[i];
				valueToStore[i] = ComplexDataObjectConverter.getSerializableForm(dataItem);
			}
			final AssociatedDataKey associatedDataKey = new AssociatedDataKey(associatedDataName, locale);
			verifyAssociatedDataIsInSchemaAndTypeMatch(this.baseAssociatedData.entitySchema, associatedDataName, valueToStore.getClass(), locale);
			this.associatedDataMutations.put(
				associatedDataKey,
				new UpsertAssociatedDataMutation(associatedDataKey, valueToStore)
			);
			return this;
		}
	}

	@Nonnull
	@Override
	public AssociatedDataBuilder mutateAssociatedData(@Nonnull AssociatedDataMutation mutation) {
		this.associatedDataMutations.put(mutation.getAssociatedDataKey(), mutation);
		return this;
	}

	@Override
	public boolean associatedDataAvailable() {
		return this.baseAssociatedData.associatedDataAvailable();
	}

	@Override
	public boolean associatedDataAvailable(@Nonnull Locale locale) {
		return this.baseAssociatedData.associatedDataAvailable(locale);
	}

	@Override
	public boolean associatedDataAvailable(@Nonnull String associatedDataName) {
		return this.baseAssociatedData.associatedDataAvailable(associatedDataName);
	}

	@Override
	public boolean associatedDataAvailable(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		return this.baseAssociatedData.associatedDataAvailable(associatedDataName, locale);
	}

	@Override
	@Nullable
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName) {
		//noinspection unchecked
		return (T) getAssociatedDataValueInternal(new AssociatedDataKey(associatedDataName))
			.filter(this.associatedDataPredicate)
			.map(AssociatedDataValue::value)
			.orElse(null);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Class<T> dtoType, @Nonnull ReflectionLookup reflectionLookup) {
		return getAssociatedDataValueInternal(new AssociatedDataKey(associatedDataName))
			.map(it -> ComplexDataObjectConverter.getOriginalForm(it.valueOrThrowException(), dtoType, reflectionLookup))
			.orElse(null);
	}

	@Override
	@Nullable
	public <T extends Serializable> T[] getAssociatedDataArray(@Nonnull String associatedDataName) {
		//noinspection unchecked
		return (T[]) getAssociatedDataValueInternal(new AssociatedDataKey(associatedDataName))
			.filter(this.associatedDataPredicate)
			.map(AssociatedDataValue::value)
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull String associatedDataName) {
		return getAssociatedDataValueInternal(new AssociatedDataKey(associatedDataName));
	}

	@Override
	@Nullable
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		//noinspection unchecked
		return (T) getAssociatedDataValueInternal(new AssociatedDataKey(associatedDataName, locale))
			.filter(this.associatedDataPredicate)
			.map(AssociatedDataValue::value)
			.orElse(null);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nonnull Class<T> dtoType, @Nonnull ReflectionLookup reflectionLookup) {
		return getAssociatedDataValueInternal(new AssociatedDataKey(associatedDataName, locale))
			.map(it -> ComplexDataObjectConverter.getOriginalForm(it.valueOrThrowException(), dtoType, reflectionLookup))
			.orElse(null);
	}

	@Override
	@Nullable
	public <T extends Serializable> T[] getAssociatedDataArray(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		//noinspection unchecked
		return (T[]) getAssociatedDataValueInternal(new AssociatedDataKey(associatedDataName, locale))
			.filter(this.associatedDataPredicate)
			.map(AssociatedDataValue::value)
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		return getAssociatedDataValueInternal(new AssociatedDataKey(associatedDataName, locale));
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull AssociatedDataKey associatedDataKey) {
		return getAssociatedDataValueInternal(associatedDataKey)
			.or(() -> associatedDataKey.localized() ?
				getAssociatedDataValueInternal(new AssociatedDataKey(associatedDataKey.associatedDataName())) :
				empty()
			);
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataSchemaContract> getAssociatedDataSchema(@Nonnull String associatedDataName) {
		return this.baseAssociatedData.getAssociatedDataSchema(associatedDataName);
	}

	@Nonnull
	@Override
	public Set<String> getAssociatedDataNames() {
		return getAssociatedDataValues()
			.stream()
			.map(it -> it.key().associatedDataName())
			.collect(Collectors.toSet());
	}

	@Nonnull
	@Override
	public Set<AssociatedDataKey> getAssociatedDataKeys() {
		return getAssociatedDataValues()
			.stream()
			.map(AssociatedDataValue::key)
			.collect(Collectors.toSet());
	}

	/**
	 * Builds associatedData list based on registered mutations and previous state.
	 */
	@Override
	@Nonnull
	public Collection<AssociatedDataValue> getAssociatedDataValues() {
		return getAssociatedDataValuesWithoutPredicate()
			.filter(this.associatedDataPredicate)
			.collect(Collectors.toList());
	}

	@Nonnull
	@Override
	public Collection<AssociatedDataValue> getAssociatedDataValues(@Nonnull String associatedDataName) {
		return getAssociatedDataValues()
			.stream()
			.filter(it -> associatedDataName.equals(it.key().associatedDataName()))
			.collect(Collectors.toList());
	}

	@Nonnull
	public Set<Locale> getAssociatedDataLocales() {
		// this is quite expensive, but should not be called frequently
		return getAssociatedDataValues()
			.stream()
			.map(it -> it.key().locale())
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
	}

	@Nonnull
	@Override
	public Stream<? extends AssociatedDataMutation> buildChangeSet() {
		final Map<AssociatedDataKey, AssociatedDataValue> builtDataValues = new HashMap<>(this.baseAssociatedData.associatedDataValues);
		return this.associatedDataMutations
			.values()
			.stream()
			.filter(it -> {
				final AssociatedDataValue existingValue = builtDataValues.get(it.getAssociatedDataKey());
				final AssociatedDataValue newAssociatedData = it.mutateLocal(this.entitySchema, existingValue);
				builtDataValues.put(it.getAssociatedDataKey(), newAssociatedData);
				return existingValue == null || newAssociatedData.version() > existingValue.version();
			});
	}

	@Nonnull
	@Override
	public AssociatedData build() {
		if (isThereAnyChangeInMutations()) {
			final List<AssociatedDataValue> newAssociatedDataValues = getAssociatedDataValuesWithoutPredicate().toList();
			final Map<String, AssociatedDataSchemaContract> newAssociatedDataTypes = Stream.concat(
					this.baseAssociatedData.associatedDataTypes.values().stream(),
					newAssociatedDataValues
						.stream()
						// filter out new associate data that has no type yet
						.filter(it -> !this.baseAssociatedData.associatedDataTypes.containsKey(it.key().associatedDataName()))
						// create definition for them on the fly
						.map(AssociatedDataBuilder::createImplicitSchema)
				)
				.collect(
					Collectors.toUnmodifiableMap(
						AssociatedDataSchemaContract::getName,
						Function.identity(),
						(associatedDataSchema, associatedDataSchema2) -> {
							Assert.isTrue(
								associatedDataSchema.equals(associatedDataSchema2),
								"Associated data " + associatedDataSchema.getName() + " has incompatible types in the same entity!"
							);
							return associatedDataSchema;
						}
					)
				);

			return new AssociatedData(
				this.baseAssociatedData.entitySchema,
				newAssociatedDataValues,
				newAssociatedDataTypes
			);
		} else {
			return this.baseAssociatedData;
		}
	}

	/**
	 * Builds associatedData list based on registered mutations and previous state without using predicate.
	 */
	@Nonnull
	private Stream<AssociatedDataValue> getAssociatedDataValuesWithoutPredicate() {
		return Stream.concat(
			// process all original associatedData values - they will be: either kept intact if there is no mutation
			// or mutated by the mutation - i.e. updated or removed
			this.baseAssociatedData.associatedDataValues
				.entrySet()
				.stream()
				// use old associatedData, or apply mutation on the associatedData and return the mutated associatedData
				.map(it -> ofNullable(this.associatedDataMutations.get(it.getKey()))
					.map(mutation -> {
						final AssociatedDataValue originValue = it.getValue();
						final AssociatedDataValue mutatedAssociatedData = mutation.mutateLocal(this.entitySchema, originValue);
						return mutatedAssociatedData.differsFrom(originValue) ? mutatedAssociatedData : originValue;
					})
					.orElse(it.getValue())
				),
			// all mutations that doesn't hit existing associatedData probably produce new ones
			// we have to process them as well
			this.associatedDataMutations
				.values()
				.stream()
				// we want to process only those mutations that have no associatedData to mutate in the original set
				.filter(it -> !this.baseAssociatedData.getAssociatedDataKeys().contains(it.getAssociatedDataKey()))
				// apply mutation
				.map(it -> it.mutateLocal(this.entitySchema, null))
		);
	}

	/**
	 * Returns true if there is single mutation in the local mutations.
	 */
	private boolean isThereAnyChangeInMutations() {
		return Stream.concat(
				// process all original attribute values - they will be: either kept intact if there is no mutation
				// or mutated by the mutation - i.e. updated or removed
				this.baseAssociatedData.associatedDataValues
					.entrySet()
					.stream()
					// use old attribute, or apply mutation on the attribute and return the mutated attribute
					.map(it -> ofNullable(this.associatedDataMutations.get(it.getKey()))
						.map(mutation -> {
							final AssociatedDataValue originValue = it.getValue();
							final AssociatedDataValue mutatedAttribute = mutation.mutateLocal(this.entitySchema, originValue);
							return mutatedAttribute.differsFrom(originValue);
						})
						.orElse(false)
					),
				// all mutations that doesn't hit existing attribute probably produce new ones
				// we have to process them as well
				this.associatedDataMutations
					.values()
					.stream()
					// we want to process only those mutations that have no attribute to mutate in the original set
					.filter(it -> !this.baseAssociatedData.getAssociatedDataKeys().contains(it.getAssociatedDataKey()))
					// apply mutation
					.map(it -> true)
			)
			.anyMatch(it -> it);
	}

	/**
	 * Returns either unchanged associatedData value, or associatedData value with applied mutation or even new associatedData value
	 * that is produced by the mutation.
	 */
	@Nonnull
	private Optional<AssociatedDataValue> getAssociatedDataValueInternal(AssociatedDataKey associatedDataKey) {
		final Optional<AssociatedDataValue> associatedDataValue = ofNullable(this.baseAssociatedData.associatedDataValues.get(associatedDataKey))
			.map(it ->
				ofNullable(this.associatedDataMutations.get(associatedDataKey))
					.map(mut -> {
						final AssociatedDataValue mutatedValue = mut.mutateLocal(this.entitySchema, it);
						return mutatedValue.differsFrom(it) ? mutatedValue : it;
					})
					.orElse(it)
			)
			.or(() ->
				ofNullable(this.associatedDataMutations.get(associatedDataKey))
					.map(it -> it.mutateLocal(this.entitySchema, null))
			);
		return associatedDataValue.filter(this.associatedDataPredicate);
	}

}
