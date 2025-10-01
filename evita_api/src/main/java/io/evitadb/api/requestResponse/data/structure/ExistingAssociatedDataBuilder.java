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
import io.evitadb.api.requestResponse.data.AssociatedDataEditor.AssociatedDataBuilder;
import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.RemoveAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.dataType.data.ComplexDataObjectConverter;
import io.evitadb.dataType.map.LazyHashMap;
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
	 * Definition of the entity schema used to validate associated data mutations.
	 * The schema is consulted to ensure names, types and localization constraints are respected.
	 */
	private final EntitySchemaContract entitySchema;
	/**
	 * Snapshot of the original associated data container that this builder mutates virtually.
	 * The builder never changes this instance directly; instead it records mutations on top of it.
	 */
	private final AssociatedData baseAssociatedData;
	/**
	 * Predicate that marks which associated data were fetched in the current context.
	 * Only values matching this predicate can be read/updated; others would cause a context error.
	 */
	@Getter private final SerializablePredicate<AssociatedDataValue> associatedDataPredicate;
	/**
	 * Collected mutations keyed by associated data key. When empty, no changes are pending.
	 */
	private final Map<AssociatedDataKey, AssociatedDataMutation> associatedDataMutations;

	/**
	 * Creates a builder that will apply mutations on an existing associated data container.
	 *
	 * @param entitySchema schema used to validate names, types and locales for mutations
	 * @param baseAssociatedData original container the mutations are virtually applied to
	 */
	public ExistingAssociatedDataBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull AssociatedData baseAssociatedData
	) {
		this.entitySchema = entitySchema;
		this.associatedDataMutations = new LazyHashMap<>(8);
		this.baseAssociatedData = baseAssociatedData;
		this.associatedDataPredicate = Droppable::exists;
	}

	/**
	 * Creates a builder with a custom fetch predicate limiting accessible associated data.
	 *
	 * @param entitySchema schema used to validate names, types and locales for mutations
	 * @param baseAssociatedData original container the mutations are virtually applied to
	 * @param associatedDataPredicate predicate defining which values are considered fetched/accessible
	 */
	public ExistingAssociatedDataBuilder(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull AssociatedData baseAssociatedData,
		@Nonnull SerializablePredicate<AssociatedDataValue> associatedDataPredicate
	) {
		this.entitySchema = entitySchema;
		this.associatedDataMutations = new LazyHashMap<>(8);
		this.baseAssociatedData = baseAssociatedData;
		this.associatedDataPredicate = associatedDataPredicate;
	}

	/**
	 * Adds a single associated data mutation to this builder.
	 *
	 * - Validates that the target key is available in the current fetch context
	 * - For upserts, validates type and locale against the schema
	 * - Deduplicates no-op mutations by removing them from the pending set
	 *
	 * @param localMutation mutation to add (remove or upsert)
	 * @throws ContextMissingException when the target key wasn't fetched in current context
	 * @throws IllegalArgumentException when the predicate prohibits updates of the key
	 */
	public void addMutation(@Nonnull AssociatedDataMutation localMutation) {
		final AssociatedDataKey associatedDataKey = localMutation.getAssociatedDataKey();
		assertAssociatedDataAvailableAndMatchPredicate(associatedDataKey);
		if (localMutation instanceof UpsertAssociatedDataMutation upsertAssociatedDataMutation) {
			final Serializable associatedDataValue = upsertAssociatedDataMutation.getAssociatedDataValue();
			verifyAssociatedDataIsInSchemaAndTypeMatch(
				this.baseAssociatedData.entitySchema,
				associatedDataKey.associatedDataName(),
				associatedDataValue.getClass(),
				associatedDataKey.locale()
			);
			if (isValueDiffers(upsertAssociatedDataMutation)) {
				this.associatedDataMutations.put(associatedDataKey, upsertAssociatedDataMutation);
			} else {
				this.associatedDataMutations.remove(associatedDataKey);
			}
		} else if (localMutation instanceof RemoveAssociatedDataMutation removeAssociatedDataMutation) {
			if (this.baseAssociatedData.getAssociatedDataValueWithoutSchemaCheck(associatedDataKey).isEmpty()) {
				this.associatedDataMutations.remove(associatedDataKey);
			} else {
				if (isValueDiffers(removeAssociatedDataMutation)) {
					this.associatedDataMutations.put(associatedDataKey, removeAssociatedDataMutation);
				}
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
			addMutation(new RemoveAssociatedDataMutation(associatedDataKey));
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
			addMutation(
				new UpsertAssociatedDataMutation(
					new AssociatedDataKey(associatedDataName),
					ComplexDataObjectConverter.getSerializableForm(associatedDataValue)
				)
			);
			return this;
		}
	}

	@Override
	@Nonnull
	public <T extends Serializable> AssociatedDataBuilder setAssociatedData(
		@Nonnull String associatedDataName, @Nonnull T[] associatedDataValue) {
		final AssociatedDataKey associatedDataKey = new AssociatedDataKey(associatedDataName);
		final Serializable[] valueToStore = new Serializable[associatedDataValue.length];
		for (int i = 0; i < associatedDataValue.length; i++) {
			final T dataItem = associatedDataValue[i];
			valueToStore[i] = ComplexDataObjectConverter.getSerializableForm(dataItem);
		}
		addMutation(new UpsertAssociatedDataMutation(associatedDataKey, valueToStore));
		return this;
	}

	@Override
	@Nonnull
	public AssociatedDataBuilder removeAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		final AssociatedDataKey associatedDataKey = new AssociatedDataKey(associatedDataName, locale);
		if (this.baseAssociatedData.getAssociatedDataValueWithoutSchemaCheck(associatedDataKey).filter(
			Droppable::exists).isEmpty()) {
			this.associatedDataMutations.remove(associatedDataKey);
		} else {
			addMutation(new RemoveAssociatedDataMutation(associatedDataKey));
		}
		return this;
	}

	@Override
	@Nonnull
	public <T extends Serializable> AssociatedDataBuilder setAssociatedData(
		@Nonnull String associatedDataName,
		@Nonnull Locale locale,
		@Nullable T associatedDataValue
	) {
		if (associatedDataValue == null || associatedDataValue instanceof Object[] arr && ArrayUtils.isEmpty(arr)) {
			return removeAssociatedData(associatedDataName, locale);
		} else {
			addMutation(
				new UpsertAssociatedDataMutation(
					new AssociatedDataKey(associatedDataName, locale),
					ComplexDataObjectConverter.getSerializableForm(associatedDataValue)
				)
			);
			return this;
		}
	}

	@Override
	@Nonnull
	public <T extends Serializable> AssociatedDataBuilder setAssociatedData(
		@Nonnull String associatedDataName, @Nonnull Locale locale, @Nullable T[] associatedDataValue) {
		if (associatedDataValue == null) {
			return removeAssociatedData(associatedDataName, locale);
		} else {
			final Serializable[] valueToStore = new Serializable[associatedDataValue.length];
			for (int i = 0; i < associatedDataValue.length; i++) {
				final T dataItem = associatedDataValue[i];
				valueToStore[i] = ComplexDataObjectConverter.getSerializableForm(dataItem);
			}
			addMutation(
				new UpsertAssociatedDataMutation(new AssociatedDataKey(associatedDataName, locale), valueToStore)
			);
			return this;
		}
	}

	@Nonnull
	@Override
	public AssociatedDataBuilder mutateAssociatedData(@Nonnull AssociatedDataMutation mutation) {
		addMutation(mutation);
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
	public <T extends Serializable> T getAssociatedData(
		@Nonnull String associatedDataName, @Nonnull Class<T> dtoType, @Nonnull ReflectionLookup reflectionLookup) {
		return getAssociatedDataValueInternal(new AssociatedDataKey(associatedDataName))
			.map(
				it -> ComplexDataObjectConverter.getOriginalForm(it.valueOrThrowException(), dtoType, reflectionLookup))
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
	public <T extends Serializable> T getAssociatedData(
		@Nonnull String associatedDataName, @Nonnull Locale locale, @Nonnull Class<T> dtoType,
		@Nonnull ReflectionLookup reflectionLookup
	) {
		return getAssociatedDataValueInternal(new AssociatedDataKey(associatedDataName, locale))
			.map(
				it -> ComplexDataObjectConverter.getOriginalForm(it.valueOrThrowException(), dtoType, reflectionLookup))
			.orElse(null);
	}

	@Override
	@Nullable
	public <T extends Serializable> T[] getAssociatedDataArray(
		@Nonnull String associatedDataName, @Nonnull Locale locale) {
		//noinspection unchecked
		return (T[]) getAssociatedDataValueInternal(new AssociatedDataKey(associatedDataName, locale))
			.filter(this.associatedDataPredicate)
			.map(AssociatedDataValue::value)
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataValue> getAssociatedDataValue(
		@Nonnull String associatedDataName, @Nonnull Locale locale) {
		return getAssociatedDataValueInternal(new AssociatedDataKey(associatedDataName, locale));
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

	@Nonnull
	@Override
	public Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull AssociatedDataKey associatedDataKey) {
		return getAssociatedDataValueInternal(associatedDataKey)
			.or(() -> associatedDataKey.localized() ?
				getAssociatedDataValueInternal(new AssociatedDataKey(associatedDataKey.associatedDataName())) :
				empty()
			);
	}

	/**
	 * Builds associatedData list based on registered mutations and previous state.
	 */
	@Override
	@Nonnull
	public Collection<AssociatedDataValue> getAssociatedDataValues() {
		if (!associatedDataAvailable()) {
			throw ContextMissingException.associatedDataContextMissing();
		}
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

	/**
	 * Returns the set of locales present in the current view of associated data, after applying
	 * pending mutations and the fetch predicate. This operation iterates all values and may be
	 * relatively expensive; it should not be called in tight loops.
	 *
	 * @return locales for which there is at least one associated data value
	 */
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
		final Map<AssociatedDataKey, AssociatedDataValue> builtDataValues = new HashMap<>(
			this.baseAssociatedData.associatedDataValues);
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
					                                                                               .filter(
						                                                                               it -> !this.baseAssociatedData.associatedDataTypes.containsKey(it.key().associatedDataName()))
					                                                                               // create definition for them on the fly
					                                                                               .map(AssociatedDataBuilder::createImplicitSchema)
			                                                                               )
			                                                                               .collect(
				                                                                               Collectors.toUnmodifiableMap(
					                                                                               AssociatedDataSchemaContract::getName,
					                                                                               Function.identity(),
					                                                                               (associatedDataSchema, associatedDataSchema2) -> {
						                                                                               Assert.isTrue(
							                                                                               associatedDataSchema.equals(
								                                                                               associatedDataSchema2),
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
	 * Checks whether there are any mutations present in the associated data.
	 *
	 * @return true if there are one or more mutations in the associated data, false otherwise.
	 */
	public boolean isThereAnyChangeInMutations() {
		return !this.associatedDataMutations.isEmpty();
	}

	/**
	 * Determines if the value produced by the mutation differs from the corresponding base associated data value.
	 *
	 * @param associatedDataMutation the associated data mutation to check
	 * @return true if the resulting value differs from the base value; false otherwise
	 */
	private boolean isValueDiffers(@Nonnull AssociatedDataMutation associatedDataMutation) {
		final AssociatedDataValue baseAssociatedData = this.baseAssociatedData
			.getAssociatedDataValueWithoutSchemaCheck(associatedDataMutation.getAssociatedDataKey())
			.orElse(null);
		return baseAssociatedData == null || !baseAssociatedData.equals(
			associatedDataMutation.mutateLocal(this.entitySchema, baseAssociatedData));
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
						final AssociatedDataValue mutatedAssociatedData = mutation.mutateLocal(
							this.entitySchema, originValue);
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
	 * Returns either unchanged associatedData value, or associatedData value with applied mutation or even new associatedData value
	 * that is produced by the mutation.
	 */
	@Nonnull
	private Optional<AssociatedDataValue> getAssociatedDataValueInternal(@Nonnull AssociatedDataKey associatedDataKey) {
		assertAssociatedDataAvailableAndMatchPredicate(associatedDataKey);
		final Optional<AssociatedDataValue> associatedDataValue = ofNullable(
			this.baseAssociatedData.associatedDataValues.get(associatedDataKey))
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

	/**
	 * Asserts that the associated data identified by the given key is available in the context and meets the
	 * conditions specified by the associated data predicate. Throws an exception if the associated data is
	 * not available or does not meet the predicate criteria.
	 *
	 * @param associatedDataKey the key identifying the associated data to be checked for availability
	 *                          and matching the predicate
	 * @throws ContextMissingException  if the associated data context is missing for the given name
	 * @throws IllegalArgumentException if the associated data does not meet the specified predicate criteria
	 */
	private void assertAssociatedDataAvailableAndMatchPredicate(@Nonnull AssociatedDataKey associatedDataKey) {
		final String associatedDataName = associatedDataKey.associatedDataName();
		if (!associatedDataAvailable(associatedDataName)) {
			throw ContextMissingException.associatedDataContextMissing(associatedDataName);
		}
		Assert.isTrue(
			this.associatedDataPredicate.test(new AssociatedDataValue(associatedDataKey, -1)),
			"Associated data `" + associatedDataKey + "` was not fetched and cannot be updated. " +
				"Please enrich the entity first or load it with the associated data."
		);
	}

}
