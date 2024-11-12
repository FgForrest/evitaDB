/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.index.mutation.index;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;
import io.evitadb.index.CatalogIndex;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.IndexType;
import io.evitadb.index.mutation.index.dataAccess.ExistingAttributeValueSupplier;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * This interface is used to co-locate attribute mutating routines which are rather procedural and long to avoid excessive
 * amount of code in {@link EntityIndexLocalMutationExecutor}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface AttributeIndexMutator {

	/**
	 * Handles {@link UpsertAttributeMutation} and alters {@link EntityIndex}
	 * data according this mutation.
	 */
	static void executeAttributeUpsert(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull Function<String, AttributeSchema> attributeSchemaProvider,
		@Nonnull Function<String, Stream<SortableAttributeCompoundSchema>> compoundsSchemaProvider,
		@Nonnull ExistingAttributeValueSupplier existingValueSupplier,
		@Nonnull EntityIndex entityIndex,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Serializable attributeValue,
		boolean updateGlobalIndex,
		boolean updateCompounds,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final AttributeSchemaContract attributeDefinition = attributeSchemaProvider.apply(attributeKey.attributeName());
		Assert.notNull(attributeDefinition, "Attribute `" + attributeKey.attributeName() + "` not defined in schema!");

		final Object valueToInsert = Objects.requireNonNull(
			EvitaDataTypes.toTargetType(attributeValue, attributeDefinition.getType(), attributeDefinition.getIndexedDecimalPlaces())
		);

		final Scope scope = entityIndex.getIndexKey().scope();
		if (attributeDefinition.isUnique(scope) || attributeDefinition.isFilterable(scope) || attributeDefinition.isSortable(scope)) {
			final EntitySchema entitySchema = executor.getEntitySchema();
			final Set<Locale> allowedLocales = entitySchema.getLocales();
			final Locale locale = attributeKey.locale();

			if (attributeDefinition.isUnique(scope)) {
				final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_UNIQUE_INDEX);
				final Optional<AttributeValue> existingValue = existingValueSupplier.getAttributeValue(attributeKey);
				existingValue.ifPresent(theValue -> {
					entityIndex.removeUniqueAttribute(attributeDefinition, allowedLocales, scope, locale, theValue.value(), entityPrimaryKey);
					if (undoActionConsumer != null) {
						undoActionConsumer.accept(() -> entityIndex.insertUniqueAttribute(attributeDefinition, allowedLocales, scope, locale, theValue.value(), entityPrimaryKey));
					}
					if (!attributeDefinition.isFilterable(scope)) {
						// TOBEDONE JNO this should be replaced with RadixTree (for String values)
						entityIndex.removeFilterAttribute(attributeDefinition, allowedLocales, locale, theValue.value(), entityPrimaryKey);
						if (undoActionConsumer != null) {
							undoActionConsumer.accept(() -> entityIndex.insertFilterAttribute(attributeDefinition, allowedLocales, locale, theValue.value(), entityPrimaryKey));
						}
					}
				});
				entityIndex.insertUniqueAttribute(attributeDefinition, allowedLocales, scope, locale, valueToInsert, entityPrimaryKey);
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(() -> entityIndex.removeUniqueAttribute(attributeDefinition, allowedLocales, scope, locale, valueToInsert, entityPrimaryKey));
				}
				if (!attributeDefinition.isFilterable(scope)) {
					// TOBEDONE JNO this should be replaced with RadixTree (for String values)
					entityIndex.insertFilterAttribute(attributeDefinition, allowedLocales, locale, valueToInsert, entityPrimaryKey);
					if (undoActionConsumer != null) {
						undoActionConsumer.accept(() -> entityIndex.removeFilterAttribute(attributeDefinition, allowedLocales, locale, valueToInsert, entityPrimaryKey));
					}
				}
			}
			if (attributeDefinition.isFilterable(scope)) {
				final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_FILTER_INDEX);
				final Optional<AttributeValue> existingValue = existingValueSupplier.getAttributeValue(attributeKey);
				existingValue.ifPresent(theValue -> {
					entityIndex.removeFilterAttribute(attributeDefinition, allowedLocales, locale, theValue.value(), entityPrimaryKey);
					if (undoActionConsumer != null) {
						undoActionConsumer.accept(() -> entityIndex.insertFilterAttribute(attributeDefinition, allowedLocales, locale, theValue.value(), entityPrimaryKey));
					}
				});
				entityIndex.insertFilterAttribute(attributeDefinition, allowedLocales, locale, valueToInsert, entityPrimaryKey);
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(() -> entityIndex.removeFilterAttribute(attributeDefinition, allowedLocales, locale, valueToInsert, entityPrimaryKey));
				}
			}
			if (attributeDefinition.isSortable(scope)) {
				final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_SORT_INDEX);
				final Optional<AttributeValue> existingValue = existingValueSupplier.getAttributeValue(attributeKey);
				existingValue.ifPresent(theValue -> {
					entityIndex.removeSortAttribute(attributeDefinition, allowedLocales, locale, theValue.value(), entityPrimaryKey);
					if (undoActionConsumer != null) {
						undoActionConsumer.accept(() -> entityIndex.insertSortAttribute(attributeDefinition, allowedLocales, locale, theValue.value(), entityPrimaryKey));
					}
				});
				entityIndex.insertSortAttribute(attributeDefinition, allowedLocales, locale, valueToInsert, entityPrimaryKey);
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(() -> entityIndex.removeSortAttribute(attributeDefinition, allowedLocales, locale, valueToInsert, entityPrimaryKey));
				}
			}

			if (updateGlobalIndex && attributeDefinition instanceof GlobalAttributeSchema globalAttributeSchema &&
				globalAttributeSchema.isUniqueGlobally(scope)) {
				// use the same scope as used in the entity index
				final CatalogIndex catalogIndex = executor.getCatalogIndex(scope);
				final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_UNIQUE_INDEX);

				final Optional<AttributeValue> existingValue = existingValueSupplier.getAttributeValue(attributeKey);
				existingValue.ifPresent(theValue -> {
					final Serializable value = Objects.requireNonNull(theValue.value());
					catalogIndex.removeUniqueAttribute(
						entitySchema, globalAttributeSchema, allowedLocales, locale,
						value, entityPrimaryKey
					);
					if (undoActionConsumer != null) {
						undoActionConsumer.accept(
							() -> catalogIndex.insertUniqueAttribute(
								entitySchema, globalAttributeSchema, allowedLocales, locale, value, entityPrimaryKey
							)
						);
					}
				});
				catalogIndex.insertUniqueAttribute(
					entitySchema, globalAttributeSchema, allowedLocales, locale, valueToInsert, entityPrimaryKey
				);
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(
						() -> catalogIndex.removeUniqueAttribute(
							entitySchema, globalAttributeSchema, allowedLocales, locale, valueToInsert, entityPrimaryKey
						)
					);
				}
			}
		}

		// now update the compounds
		if (updateCompounds) {
			updateSortableAttributeCompounds(
				executor, attributeSchemaProvider, compoundsSchemaProvider, existingValueSupplier,
				entityIndex, valueToInsert, attributeKey.locale(), attributeDefinition.getName(),
				undoActionConsumer
			);
		}
	}

	/**
	 * Handles {@link RemoveAttributeMutation} and alters {@link EntityIndex}
	 * data according this mutation.
	 */
	static <T extends Serializable & Comparable<T>> void executeAttributeRemoval(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull Function<String, AttributeSchema> attributeSchemaProvider,
		@Nonnull Function<String, Stream<SortableAttributeCompoundSchema>> compoundsSchemaProvider,
		@Nonnull ExistingAttributeValueSupplier existingValueSupplier,
		@Nonnull EntityIndex entityIndex,
		@Nonnull AttributeKey attributeKey,
		boolean updateGlobalIndex,
		boolean updateCompounds,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final String attributeName = attributeKey.attributeName();
		final AttributeSchemaContract attributeDefinition = attributeSchemaProvider.apply(attributeName);
		Assert.notNull(attributeDefinition, "Attribute `" + attributeName + "` not defined in schema!");

		final Set<Locale> allowedLocales = entitySchema.getLocales();
		final Locale locale = attributeKey.locale();

		final AtomicReference<T> valueToRemove = new AtomicReference<>();
		final Supplier<T> valueToRemoveSupplier = () -> valueToRemove.updateAndGet(alreadyKnownOldValue -> {
			if (alreadyKnownOldValue == null) {
				final AttributeValue existingValue = existingValueSupplier.getAttributeValue(attributeKey).orElse(null);
				Assert.notNull(existingValue, "Attribute `" + attributeDefinition.getName() + "` is unexpectedly not found in container for entity `" + entitySchema.getName() + "` record " + executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_INDEX) + "!");
				//noinspection unchecked
				return (T) existingValue.value();
			} else {
				return alreadyKnownOldValue;
			}
		});

		final Scope scope = entityIndex.getIndexKey().scope();
		if (attributeDefinition.isUnique(scope) || attributeDefinition.isFilterable(scope) || attributeDefinition.isSortable(scope)) {
			if (attributeDefinition.isUnique(scope)) {
				entityIndex.removeUniqueAttribute(
					attributeDefinition, allowedLocales, scope, locale, valueToRemoveSupplier.get(),
					executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_UNIQUE_INDEX)
				);
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(
						() -> entityIndex.insertUniqueAttribute(
							attributeDefinition, allowedLocales, scope, locale, valueToRemoveSupplier.get(),
							executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_UNIQUE_INDEX)
						)
					);
				}
				if (!attributeDefinition.isFilterable(scope)) {
					// TOBEDONE JNO this should be replaced with RadixTree (for String values)
					entityIndex.removeFilterAttribute(
						attributeDefinition, allowedLocales, locale, valueToRemoveSupplier.get(),
						executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_FILTER_INDEX)
					);
					if (undoActionConsumer != null) {
						undoActionConsumer.accept(
							() -> entityIndex.insertFilterAttribute(
								attributeDefinition, allowedLocales, locale, valueToRemoveSupplier.get(),
								executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_FILTER_INDEX)
							)
						);
					}
				}

				if (updateGlobalIndex && attributeDefinition instanceof GlobalAttributeSchema globalAttributeSchema &&
					globalAttributeSchema.isUniqueGlobally(scope)
				) {
					// use the same scope as used in the entity index
					final CatalogIndex catalogIndex = executor.getCatalogIndex(scope);
					catalogIndex.removeUniqueAttribute(
						entitySchema, globalAttributeSchema, allowedLocales, locale, valueToRemoveSupplier.get(),
						executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_UNIQUE_INDEX)
					);
					if (undoActionConsumer != null) {
						undoActionConsumer.accept(
							() -> catalogIndex.insertUniqueAttribute(
								entitySchema, globalAttributeSchema, allowedLocales, locale, valueToRemoveSupplier.get(),
								executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_UNIQUE_INDEX)
							)
						);
					}
				}
			}
			if (attributeDefinition.isFilterable(scope)) {
				entityIndex.removeFilterAttribute(
					attributeDefinition, allowedLocales, locale, valueToRemoveSupplier.get(),
					executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_FILTER_INDEX)
				);
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(
						() -> entityIndex.insertFilterAttribute(
							attributeDefinition, allowedLocales, locale, valueToRemoveSupplier.get(),
							executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_FILTER_INDEX)
						)
					);
				}
			}
			if (attributeDefinition.isSortable(scope)) {
				entityIndex.removeSortAttribute(
					attributeDefinition, allowedLocales, locale, valueToRemoveSupplier.get(),
					executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_SORT_INDEX)
				);
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(
						() -> entityIndex.insertSortAttribute(
							attributeDefinition, allowedLocales, locale, valueToRemoveSupplier.get(),
							executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_SORT_INDEX)
						)
					);
				}
			}
		}

		// now update the compounds
		if (updateCompounds) {
			updateSortableAttributeCompounds(
				executor, attributeSchemaProvider, compoundsSchemaProvider, existingValueSupplier,
				entityIndex, null, locale, attributeName, undoActionConsumer
			);
		}
	}

	/**
	 * Handles {@link ApplyDeltaAttributeMutation} and alters {@link EntityIndex}
	 * data according this mutation.
	 */
	static <T extends Serializable & Comparable<T>> void executeAttributeDelta(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull Function<String, AttributeSchema> attributeSchemaProvider,
		@Nonnull Function<String, Stream<SortableAttributeCompoundSchema>> compoundsSchemaProvider,
		@Nonnull ExistingAttributeValueSupplier existingValueSupplier,
		@Nonnull EntityIndex entityIndex,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Number delta,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final String attributeName = attributeKey.attributeName();
		final AttributeSchemaContract attributeDefinition = attributeSchemaProvider.apply(attributeName);

		final Set<Locale> allowedLocales = entitySchema.getLocales();
		final Locale locale = attributeKey.locale();
		final AtomicReference<T> valueToRemove = new AtomicReference<>();
		final Supplier<T> valueToRemoveSupplier = () -> valueToRemove.updateAndGet(
			alreadyKnownOldAttributeValue -> {
				if (alreadyKnownOldAttributeValue == null) {
					final AttributeValue oldAttributeValue = existingValueSupplier.getAttributeValue(attributeKey).orElse(null);
					Assert.notNull(oldAttributeValue, "Attribute `" + attributeDefinition.getName() + "` is unexpectedly not found in indexes for entity `" + entitySchema.getName() + "` record " + executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_INDEX) + "!");
					//noinspection unchecked
					final T theOldValue = (T) oldAttributeValue.value();
					Assert.isTrue(theOldValue instanceof Number, "Attribute `" + attributeDefinition.getName() + "` in entity `" + entitySchema.getName() + "` is not a number type!");
					return theOldValue;
				} else {
					return alreadyKnownOldAttributeValue;
				}
			}
		);
		final AtomicReference<T> valueToAdd = new AtomicReference<>();
		final Supplier<T> valueToUpdateSupplier = () -> valueToAdd.updateAndGet(
			existingValue -> {
				if (existingValue == null) {
					final T oldValue = valueToRemoveSupplier.get();

					//noinspection unchecked
					return (T) NumberUtils.sum(
						(Number) oldValue,
						(Number) EvitaDataTypes.toTargetType(
							delta,
							attributeDefinition.getType(),
							attributeDefinition.getIndexedDecimalPlaces()
						)
					);
				} else {
					return existingValue;
				}
			});

		final Scope scope = entityIndex.getIndexKey().scope();
		if (attributeDefinition.isUnique(scope)) {
			final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_UNIQUE_INDEX);
			entityIndex.removeUniqueAttribute(attributeDefinition, allowedLocales, scope, locale, valueToRemoveSupplier.get(), entityPrimaryKey);
			entityIndex.insertUniqueAttribute(attributeDefinition, allowedLocales, scope, locale, valueToUpdateSupplier.get(), entityPrimaryKey);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(() -> entityIndex.insertUniqueAttribute(attributeDefinition, allowedLocales, scope, locale, valueToRemoveSupplier.get(), entityPrimaryKey));
				undoActionConsumer.accept(() -> entityIndex.removeUniqueAttribute(attributeDefinition, allowedLocales, scope, locale, valueToUpdateSupplier.get(), entityPrimaryKey));
			}
		}
		if (attributeDefinition.isFilterable(scope)) {
			final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_FILTER_INDEX);
			entityIndex.removeFilterAttribute(attributeDefinition, allowedLocales, locale, valueToRemoveSupplier.get(), entityPrimaryKey);
			entityIndex.insertFilterAttribute(attributeDefinition, allowedLocales, locale, valueToUpdateSupplier.get(), entityPrimaryKey);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(() -> entityIndex.insertFilterAttribute(attributeDefinition, allowedLocales, locale, valueToRemoveSupplier.get(), entityPrimaryKey));
				undoActionConsumer.accept(() -> entityIndex.removeFilterAttribute(attributeDefinition, allowedLocales, locale, valueToUpdateSupplier.get(), entityPrimaryKey));
			}
		}
		if (attributeDefinition.isSortable(scope)) {
			final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_SORT_INDEX);
			entityIndex.removeSortAttribute(attributeDefinition, allowedLocales, locale, valueToRemoveSupplier.get(), entityPrimaryKey);
			entityIndex.insertSortAttribute(attributeDefinition, allowedLocales, locale, valueToUpdateSupplier.get(), entityPrimaryKey);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(() -> entityIndex.insertSortAttribute(attributeDefinition, allowedLocales, locale, valueToRemoveSupplier.get(), entityPrimaryKey));
				undoActionConsumer.accept(() -> entityIndex.removeSortAttribute(attributeDefinition, allowedLocales, locale, valueToUpdateSupplier.get(), entityPrimaryKey));
			}
		}
		// now update the compounds
		updateSortableAttributeCompounds(
			executor, attributeSchemaProvider, compoundsSchemaProvider, existingValueSupplier,
			entityIndex, valueToUpdateSupplier.get(), locale, attributeName, undoActionConsumer
		);
	}

	/**
	 * Sets up the initial set of sortable attribute compounds for the given entity and `entityIndex`. When the `locale`
	 * parameter is not null, only the compounds for the given locale are created. Otherwise, all compounds that don't
	 * contain localized attribute value are created.
	 *
	 * The method is called only once for each entity, when the entity is created, a new language for the entity is
	 * added, or the entity is set up in a brand new reduced index.
	 */
	static void insertInitialSuiteOfSortableAttributeCompounds(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex entityIndex,
		@Nullable Locale locale,
		@Nonnull SortableAttributeCompoundSchemaProvider<?> compoundProvider,
		@Nonnull ExistingAttributeValueSupplier entityAttributeValueSupplier,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final Map<String, SortableAttributeCompoundSchemaContract> sortableAttributeCompounds = compoundProvider.getSortableAttributeCompounds();
		if (sortableAttributeCompounds.isEmpty()) {
			return;
		}

		final Function<String, AttributeSchema> attributeSchemaProvider = attributeName -> compoundProvider.getAttribute(attributeName)
			.map(AttributeSchema.class::cast)
			.orElse(null);

		final Stream<SortableAttributeCompoundSchema> allCompounds = sortableAttributeCompounds
			.values()
			.stream()
			// we retrieve schemas from EntitySchema, so we can safely cast them here
			.map(SortableAttributeCompoundSchema.class::cast);

		final Stream<SortableAttributeCompoundSchema> filteredCompounds = locale == null ?
			allCompounds.filter(it -> !it.isLocalized(attributeSchemaProvider)) :
			// filter only localized compound schemas
			allCompounds.filter(it -> it.isLocalized(attributeSchemaProvider));

		final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_SORT_INDEX);
		filteredCompounds.forEach(
			it -> insertNewCompound(
				entityPrimaryKey, entityIndex, it,
				null, null,
				locale,
				attributeSchemaProvider,
				createAttributeElementToAttributeValueProvider(
					attributeSchemaProvider,
					attributeKey -> entityAttributeValueSupplier.getAttributeValue(attributeKey).orElse(null),
					locale
				),
				undoActionConsumer
			)
		);
	}

	/**
	 * Removes all sortable attribute compounds for the given entity and `entityIndex`. When the `locale`
	 * parameter is not null, only the compounds for the given locale are removed. Otherwise, all compounds that don't
	 * contain localized attribute value are removed.
	 *
	 * The method is called only once for each entity, when the entity is removed, a language for the entity is
	 * discarded, or the entity removed from the index.
	 */
	static void removeEntireSuiteOfSortableAttributeCompounds(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex entityIndex,
		@Nullable Locale locale,
		@Nonnull SortableAttributeCompoundSchemaProvider<?> compoundProvider,
		@Nonnull ExistingAttributeValueSupplier entityAttributeValueSupplier,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final Function<String, AttributeSchema> attributeSchemaProvider = attributeName -> compoundProvider.getAttribute(attributeName)
			.map(AttributeSchema.class::cast)
			.orElse(null);

		final Stream<SortableAttributeCompoundSchema> allCompounds = compoundProvider.getSortableAttributeCompounds()
			.values()
			.stream()
			// we retrieve schemas from EntitySchema, so we can safely cast them here
			.map(SortableAttributeCompoundSchema.class::cast);

		final Stream<SortableAttributeCompoundSchema> filteredCompounds = locale == null ?
			allCompounds.filter(it -> !it.isLocalized(attributeSchemaProvider)) :
			// filter only localized compound schemas
			allCompounds.filter(it -> it.isLocalized(attributeSchemaProvider));

		final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_SORT_INDEX);
		filteredCompounds.forEach(
			it -> removeOldCompound(
				entityPrimaryKey, entityIndex, it,
				locale,
				attributeSchemaProvider,
				createAttributeElementToAttributeValueProvider(
					attributeSchemaProvider,
					attributeKey -> entityAttributeValueSupplier.getAttributeValue(attributeKey).orElse(null),
					locale
				),
				undoActionConsumer
			)
		);
	}

	/**
	 * Method updates existing sortable attribute compounds that refer to the `updatedAttributeName` attribute.
	 * The previous compound containing this attribute is removed from the index and new is inserted using the
	 * `valueToUpdate` parameter as a new value for the attribute compound.
	 */
	private static void updateSortableAttributeCompounds(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull Function<String, AttributeSchema> attributeSchemaProvider,
		@Nonnull Function<String, Stream<SortableAttributeCompoundSchema>> compoundsSchemaProvider,
		@Nonnull ExistingAttributeValueSupplier existingValueSupplier,
		@Nonnull EntityIndex entityIndex,
		@Nullable Object valueToUpdate,
		@Nullable Locale locale,
		@Nonnull String updatedAttributeName,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		compoundsSchemaProvider.apply(updatedAttributeName)
			.forEach(
				compound -> {
					final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_SORT_INDEX);
					final Function<AttributeKey, AttributeValue> existingAttributeValueProvider =
						it -> existingValueSupplier.getAttributeValue(it).orElse(null);

					boolean isCompoundLocalized = compound.isLocalized(attributeSchemaProvider);
					final Set<Locale> entityAttributeLocales = existingValueSupplier.getEntityAttributeLocales();
					if (isCompoundLocalized && locale == null) {
						// if the compound is localized and the locale is null, it means the global attribute is updated
						// and we need to update all localized compounds using that global attribute
						entityAttributeLocales
							.forEach(
								attributeLocale -> updateCompound(
									entityIndex, compound,
									entityAttributeLocales, attributeLocale,
									updatedAttributeName, valueToUpdate,
									entityPrimaryKey, attributeSchemaProvider,
									existingAttributeValueProvider,
									undoActionConsumer
								)
							);
					} else {
						// otherwise we just update the compound with particular locale or global if locale is null
						updateCompound(
							entityIndex, compound,
							entityAttributeLocales, locale,
							updatedAttributeName, valueToUpdate,
							entityPrimaryKey, attributeSchemaProvider,
							existingAttributeValueProvider,
							undoActionConsumer
						);
					}
				}
			);
	}

	/**
	 * Method updates particular sortable attribute compound that refer to the `updatedAttributeName` attribute.
	 * The previous compound containing this attribute is removed from the index and new is inserted using the
	 * `valueToUpdate` parameter as a new value for the attribute compound.
	 */
	private static void updateCompound(
		@Nonnull EntityIndex entityIndex,
		@Nonnull SortableAttributeCompoundSchema compound,
		@Nonnull Set<Locale> availableAttributeLocales,
		@Nullable Locale locale,
		@Nullable String updatedAttributeName,
		@Nullable Object valueToUpdate,
		int entityPrimaryKey,
		@Nonnull Function<String, AttributeSchema> attributeSchemaProvider,
		@Nonnull Function<AttributeKey, AttributeValue> existingAttributeValueProvider,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		if (locale == null || availableAttributeLocales.contains(locale)) {
			final Function<AttributeElement, AttributeValue> attributeElementValueProvider = createAttributeElementToAttributeValueProvider(
				attributeSchemaProvider, existingAttributeValueProvider, locale
			);

			removeOldCompound(
				entityPrimaryKey, entityIndex, compound, locale,
				attributeSchemaProvider, attributeElementValueProvider, undoActionConsumer
			);

			insertNewCompound(
				entityPrimaryKey, entityIndex, compound, updatedAttributeName, valueToUpdate, locale,
				attributeSchemaProvider, attributeElementValueProvider, undoActionConsumer
			);
		}
	}

	/**
	 * Method calculates new version of the sortable attribute compound and inserts it into the index.
	 */
	private static void insertNewCompound(
		int entityPrimaryKey,
		@Nonnull EntityIndex entityIndex,
		@Nonnull SortableAttributeCompoundSchema compound,
		@Nullable String updatedAttributeName,
		@Nullable Object valueToUpdate,
		@Nullable Locale locale,
		@Nonnull Function<String, AttributeSchema> attributeSchemaProvider,
		@Nonnull Function<AttributeElement, AttributeValue> attributeElementValueProvider,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final Object[] newCompoundValues = compound.getAttributeElements()
			.stream()
			.map(
				it -> Objects.equals(it.attributeName(), updatedAttributeName) ?
					valueToUpdate :
					ofNullable(attributeElementValueProvider.apply(it))
						.map(AttributeValue::value)
						.orElse(null)
			)
			.toArray();

		entityIndex.insertSortAttributeCompound(
			compound,
			theAttributeName -> attributeSchemaProvider.apply(theAttributeName).getPlainType(),
			locale,
			newCompoundValues, entityPrimaryKey
		);
		if (undoActionConsumer != null) {
			undoActionConsumer.accept(
				() -> entityIndex.removeSortAttributeCompound(
					compound, locale, newCompoundValues, entityPrimaryKey
				)
			);
		}
	}

	/**
	 * Method calculates actual version of the sortable attribute compound and removes it from the index.
	 */
	private static void removeOldCompound(
		int entityPrimaryKey,
		@Nonnull EntityIndex entityIndex,
		@Nonnull SortableAttributeCompoundSchema compound,
		@Nullable Locale locale,
		@Nonnull Function<String, AttributeSchema> attributeSchemaProvider,
		@Nonnull Function<AttributeElement, AttributeValue> attributeElementValueProvider,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final Object[] oldCompoundValues = compound.getAttributeElements()
			.stream()
			.map(
				it -> ofNullable(attributeElementValueProvider.apply(it))
					.map(AttributeValue::value)
					.orElse(null)
			)
			.toArray();

		entityIndex.removeSortAttributeCompound(
			compound, locale, oldCompoundValues, entityPrimaryKey
		);
		if (undoActionConsumer != null) {
			undoActionConsumer.accept(
				() -> entityIndex.insertSortAttributeCompound(
					compound,
					theAttributeName -> attributeSchemaProvider.apply(theAttributeName).getPlainType(),
					locale,
					oldCompoundValues, entityPrimaryKey
				)
			);
		}
	}

	/**
	 * Method creates a conversion function that converts {@link AttributeElement} to {@link AttributeValue}
	 * using `existingAttributeValueProvider` and `locale` parameters.
	 */
	@Nonnull
	private static Function<AttributeElement, AttributeValue> createAttributeElementToAttributeValueProvider(
		@Nonnull Function<String, AttributeSchema> attributeSchemaProvider,
		@Nonnull Function<AttributeKey, AttributeValue> existingAttributeValueProvider,
		@Nullable Locale locale
	) {
		return it -> {
			final AttributeSchemaContract attributeSchema = attributeSchemaProvider.apply(it.attributeName());
			return existingAttributeValueProvider.apply(
				attributeSchema.isLocalized() ?
					new AttributeKey(it.attributeName(), locale) :
					new AttributeKey(it.attributeName())
			);
		};
	}

}
