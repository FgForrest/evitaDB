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
import io.evitadb.api.requestResponse.data.AssociatedDataEditor.AssociatedDataBuilder;
import io.evitadb.api.requestResponse.data.mutation.associatedData.AssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.dataType.data.ComplexDataObjectConverter;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.ReflectionLookup;

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
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * Class supports intermediate mutable object that allows {@link AssociatedData} container rebuilding.
 * Due to performance reasons (see {@link DirectWriteOrOperationLog} microbenchmark) there is special implementation
 * for the situation when entity is newly created. In this case we know everything is new and we don't need to closely
 * monitor the changes so this can speed things up.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class InitialAssociatedDataBuilder implements AssociatedDataBuilder {
	/**
	 * Stable class version identifier used during Java serialization.
	 */
	@Serial private static final long serialVersionUID = 7714436064799237939L;
	/**
	 * Entity schema if available.
	 */
	private final EntitySchemaContract entitySchema;
	/**
	 * Contains locale insensitive associatedData values - simple key → value association map.
	 */
	private final Map<AssociatedDataKey, AssociatedDataValue> associatedDataValues;

	/**
	 * Creates a brand new mutable builder for constructing an {@link AssociatedData} container
	 * from scratch.
	 *
	 * - Verifies all written entries against the provided {@link EntitySchemaContract}.
	 * - Optimized for newly created entities where no change tracking is required.
	 *
	 * @param entitySchema non-null entity schema used for validation and locale support
	 */
	InitialAssociatedDataBuilder(@Nonnull EntitySchemaContract entitySchema) {
		this.entitySchema = entitySchema;
		this.associatedDataValues = CollectionUtils.createHashMap(8);
	}

	/**
	 * Creates a builder pre-populated with the provided associated data values.
	 *
	 * Each value is re-inserted via {@code setAssociatedData(...)} to ensure proper schema/type
	 * verification and to respect localization.
	 *
	 * @param schema non-null entity schema used for validation
	 * @param associatedDataValues initial values to populate this builder with
	 */
	InitialAssociatedDataBuilder(
		@Nonnull EntitySchemaContract schema,
		@Nonnull Collection<AssociatedDataValue> associatedDataValues
	) {
		this.entitySchema = schema;
		this.associatedDataValues = CollectionUtils.createHashMap(associatedDataValues.size());
		for (AssociatedDataValue associatedDataValue : associatedDataValues) {
			final AssociatedDataKey associatedDataKey = associatedDataValue.key();
			if (associatedDataKey.localized()) {
				this.setAssociatedData(
					associatedDataKey.associatedDataName(),
					associatedDataKey.localeOrThrowException(),
					associatedDataValue.value()
				);
			} else {
				this.setAssociatedData(
					associatedDataKey.associatedDataName(),
					associatedDataValue.value()
				);
			}
		}
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataSchemaContract> getAssociatedDataSchema(@Nonnull String associatedDataName) {
		return this.entitySchema.getAssociatedData(associatedDataName);
	}

	@Nonnull
	@Override
	public Set<String> getAssociatedDataNames() {
		return this.associatedDataValues
				.keySet()
				.stream()
				.map(AssociatedDataKey::associatedDataName)
				.collect(Collectors.toSet());
	}

	@Nonnull
	@Override
	public Set<AssociatedDataKey> getAssociatedDataKeys() {
		return this.associatedDataValues.keySet();
	}

	@Nonnull
	@Override
	public Collection<AssociatedDataValue> getAssociatedDataValues() {
		return this.associatedDataValues.values();
	}

	@Override
	@Nonnull
	public AssociatedDataBuilder removeAssociatedData(@Nonnull String associatedDataName) {
		final AssociatedDataKey associatedDataKey = new AssociatedDataKey(associatedDataName);
		this.associatedDataValues.remove(associatedDataKey);
		return this;
	}

	@Override
	@Nonnull
	public <T extends Serializable> AssociatedDataBuilder setAssociatedData(@Nonnull String associatedDataName, @Nullable T associatedDataValue) {
		if (associatedDataValue == null || associatedDataValue instanceof Object[] arr && ArrayUtils.isEmpty(arr)) {
			return removeAssociatedData(associatedDataName);
		} else {
			final Serializable valueToStore = ComplexDataObjectConverter.getSerializableForm(associatedDataValue);
			final AssociatedDataKey associatedDataKey = new AssociatedDataKey(associatedDataName);
			verifyAssociatedDataIsInSchemaAndTypeMatch(this.entitySchema, associatedDataName, valueToStore.getClass());
			this.associatedDataValues.put(associatedDataKey, new AssociatedDataValue(associatedDataKey, valueToStore));
			return this;
		}
	}

	@Override
	@Nonnull
	public <T extends Serializable> AssociatedDataBuilder setAssociatedData(@Nonnull String associatedDataName, @Nonnull T[] associatedDataValue) {
		final Serializable valueToStore = ComplexDataObjectConverter.getSerializableForm(associatedDataValue);
		final AssociatedDataKey associatedDataKey = new AssociatedDataKey(associatedDataName);
		verifyAssociatedDataIsInSchemaAndTypeMatch(this.entitySchema, associatedDataName, valueToStore.getClass());
		this.associatedDataValues.put(associatedDataKey, new AssociatedDataValue(associatedDataKey, valueToStore));
		return this;
	}

	@Override
	@Nullable
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName) {
		//noinspection unchecked
		return (T) ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName)))
				.map(AssociatedDataValue::value)
				.orElse(null);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Class<T> dtoType, @Nonnull ReflectionLookup reflectionLookup) {
		return ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName)))
				.map(AssociatedDataValue::value)
				.map(it -> ComplexDataObjectConverter.getOriginalForm(it, dtoType, reflectionLookup))
				.orElse(null);
	}

	@Override
	@Nullable
	public <T extends Serializable> T[] getAssociatedDataArray(@Nonnull String associatedDataName) {
		//noinspection unchecked
		return (T[]) ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName)))
			.map(AssociatedDataValue::value)
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull String associatedDataName) {
		return ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName)));
	}

	@Nonnull
	@Override
	public Collection<AssociatedDataValue> getAssociatedDataValues(@Nonnull String associatedDataName) {
		return this.associatedDataValues
			.entrySet()
			.stream()
			.filter(it -> associatedDataName.equals(it.getKey().associatedDataName()))
			.map(Entry::getValue)
			.collect(Collectors.toList());
	}

	/*
		LOCALIZED AssociatedDataS
	 */

	@Override
	@Nonnull
	public AssociatedDataBuilder removeAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		final AssociatedDataKey associatedDataKey = new AssociatedDataKey(associatedDataName, locale);
		this.associatedDataValues.remove(associatedDataKey);
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
			verifyAssociatedDataIsInSchemaAndTypeMatch(this.entitySchema, associatedDataName, valueToStore.getClass(), locale);
			this.associatedDataValues.put(associatedDataKey, new AssociatedDataValue(associatedDataKey, valueToStore));
			return this;
		}
	}

	@Override
	@Nonnull
	public <T extends Serializable> AssociatedDataBuilder setAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nullable T[] associatedDataValue) {
		if (associatedDataValue == null) {
			return removeAssociatedData(associatedDataName, locale);
		} else {
			final Serializable valueToStore = ComplexDataObjectConverter.getSerializableForm(associatedDataValue);
			final AssociatedDataKey associatedDataKey = new AssociatedDataKey(associatedDataName, locale);
			verifyAssociatedDataIsInSchemaAndTypeMatch(this.entitySchema, associatedDataName, valueToStore.getClass(), locale);
			this.associatedDataValues.put(associatedDataKey, new AssociatedDataValue(associatedDataKey, valueToStore));
			return this;
		}
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
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		//noinspection unchecked
		return (T) ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName, locale)))
				.map(AssociatedDataValue::value)
				.orElse(null);
	}

	@Nullable
	@Override
	public <T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nonnull Class<T> dtoType, @Nonnull ReflectionLookup reflectionLookup) {
		return ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName, locale)))
				.map(AssociatedDataValue::value)
				.map(it -> ComplexDataObjectConverter.getOriginalForm(it, dtoType, reflectionLookup))
				.orElse(null);
	}

	@Override
	@Nullable
	public <T extends Serializable> T[] getAssociatedDataArray(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		//noinspection unchecked
		return (T[]) ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName, locale)))
				.map(AssociatedDataValue::value)
				.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull String associatedDataName, @Nonnull Locale locale) {
		return ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataName, locale)));
	}

	@Nonnull
	@Override
	public Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull AssociatedDataKey associatedDataKey) {
		return ofNullable(this.associatedDataValues.get(associatedDataKey))
			.or(() -> associatedDataKey.localized() ?
				ofNullable(this.associatedDataValues.get(new AssociatedDataKey(associatedDataKey.associatedDataName()))) :
				empty()
			);
	}

 @Nonnull
 /**
  * Returns the set of all locales that appear on any localized associated data currently
  * stored in this builder.
  *
  * Non-localized entries are ignored.
  *
  * @return non-null set of locales present among localized associated data keys
  */
 public Set<Locale> getAssociatedDataLocales() {
 	return this.associatedDataValues
 			.keySet()
 			.stream()
 			.map(AssociatedDataKey::locale)
 			.filter(Objects::nonNull)
 			.collect(Collectors.toSet());
 }

	@Nonnull
	@Override
	public AssociatedDataBuilder mutateAssociatedData(@Nonnull AssociatedDataMutation mutation) {
		throw new UnsupportedOperationException("You cannot apply mutation when entity is just being created!");
	}

	@Nonnull
	@Override
	public Stream<? extends AssociatedDataMutation> buildChangeSet() {
		return getAssociatedDataValues()
			.stream()
			.map(ad -> new UpsertAssociatedDataMutation(ad.key(), ad.valueOrThrowException()));
	}

	@Nonnull
	@Override
	public AssociatedData build() {
		// let's check whether there are compatible attributes
		final Map<String, AssociatedDataSchemaContract> associatedDataTypes = this.associatedDataValues
			.values()
			.stream()
			.map(AssociatedDataBuilder::createImplicitSchema)
			.collect(
				Collectors.toMap(
					AssociatedDataSchemaContract::getName,
					Function.identity(),
					(associatedDataType, associatedDataType2) -> {
						Assert.isTrue(
							Objects.equals(associatedDataType, associatedDataType2),
							"Ambiguous situation - there are two associated data with the same name and different definition:\n" +
								associatedDataType + "\n" +
								associatedDataType2
						);
						return associatedDataType;
					}
				)
			);

		return new AssociatedData(
			this.entitySchema,
			this.associatedDataValues.values(),
			associatedDataTypes
		);
	}

 /**
 * Verifies that the associated data with the given name exists in the schema (or that evolution allows
 * adding it) and that the provided value type is compatible with the configured type.
 *
 * This variant validates a non-localized value.
 *
 * @param entitySchema non-null entity schema used for validation
 * @param associatedDataName non-null associated data name
 * @param aClass nullable runtime type of the value to verify; when {@code null} only schema presence is checked
 * @throws InvalidMutationException when the associated data is not present and evolution does not allow adding it
 * @throws InvalidDataTypeMutationException when the value type is incompatible with the schema
 */
static void verifyAssociatedDataIsInSchemaAndTypeMatch(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String associatedDataName,
		@Nullable Class<? extends Serializable> aClass
	) {
		verifyAssociatedDataIsInSchemaAndTypeMatch(
			entitySchema, associatedDataName, aClass, null,
			entitySchema.getAssociatedData(associatedDataName).orElse(null)
		);
	}

 /**
 * Verifies schema presence and type compatibility for a potentially localized associated data value.
 *
 * When {@code locale} is non-null, localization constraints and supported locales are validated.
 *
 * @param entitySchema non-null entity schema
 * @param associatedDataName non-null associated data name
 * @param aClass nullable runtime type of the value; when {@code null} only schema/localization is checked
 * @param locale nullable locale; when non-null validates localized schema and locale support
 * @throws InvalidMutationException when schema/localization constraints are violated
 * @throws InvalidDataTypeMutationException when the value type is incompatible with the schema
 */
static void verifyAssociatedDataIsInSchemaAndTypeMatch(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String associatedDataName,
		@Nullable Class<? extends Serializable> aClass,
		@Nullable Locale locale
	) {
		verifyAssociatedDataIsInSchemaAndTypeMatch(
			entitySchema, associatedDataName, aClass, locale,
			entitySchema.getAssociatedData(associatedDataName).orElse(null)
		);
	}


 /**
 * Low-level verification routine that performs full validation against an explicit
 * {@link AssociatedDataSchemaContract} instance (when available).
 *
 * It validates:
 * - presence or evolvability of the associated data in the schema
 * - value type compatibility (when {@code aClass} provided)
 * - localization rules and supported locales (when {@code locale} provided)
 *
 * @param entitySchema non-null entity schema
 * @param associatedDataName non-null associated data name
 * @param aClass nullable runtime type of the value
 * @param locale nullable locale to validate localization constraints
 * @param associatedDataSchema nullable explicit schema to validate against
 * @throws InvalidMutationException on schema or localization violations
 * @throws InvalidDataTypeMutationException on incompatible value type
 */
static void verifyAssociatedDataIsInSchemaAndTypeMatch(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String associatedDataName,
		@Nullable Class<? extends Serializable> aClass,
		@Nullable Locale locale,
		@Nullable AssociatedDataSchemaContract associatedDataSchema
	) {
		Assert.isTrue(
				associatedDataSchema != null || entitySchema.allows(EvolutionMode.ADDING_ASSOCIATED_DATA),
				() -> new InvalidMutationException(
						"AssociatedData " + associatedDataName + " is not configured in entity " + entitySchema.getName() +
								" schema and automatic evolution is not enabled for associated data!"
				)
		);
		if (associatedDataSchema != null) {
			if (aClass != null) {
				Assert.isTrue(
						associatedDataSchema.getType().isAssignableFrom(aClass),
						() -> new InvalidDataTypeMutationException(
								"AssociatedData " + associatedDataName + " accepts only type " + associatedDataSchema.getType().getName() +
										" - value type is different: " + aClass.getName() + "!",
								associatedDataSchema.getType(), aClass
						)
				);
			}
			if (locale == null) {
				Assert.isTrue(
						!associatedDataSchema.isLocalized(),
						() -> new InvalidMutationException(
								"AssociatedData " + associatedDataName + " is localized and doesn't accept non-localized associated data!"
						)
				);
			} else {
				Assert.isTrue(
						associatedDataSchema.isLocalized(),
						() -> new InvalidMutationException(
								"AssociatedData " + associatedDataName + " is not localized and doesn't accept localized associated data!"
						)
				);
				Assert.isTrue(
						entitySchema.supportsLocale(locale) || entitySchema.allows(EvolutionMode.ADDING_LOCALES),
						() -> new InvalidMutationException(
								"AssociatedData " + associatedDataName + " is localized, but schema doesn't support locale " + locale + "! " +
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
							"AssociatedData " + associatedDataName + " is localized, but schema doesn't support locale " + locale + "! " +
									"Supported locales are: " +
									entitySchema.getLocales().stream().map(Locale::toString).collect(Collectors.joining(", "))
					)
			);
		}
	}

}
