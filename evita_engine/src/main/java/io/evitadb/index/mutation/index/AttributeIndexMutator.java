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

package io.evitadb.index.mutation.index;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.mutation.attribute.ApplyDeltaAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
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
import io.evitadb.index.mutation.index.EntityIndexLocalMutationExecutor.Target;
import io.evitadb.index.mutation.index.dataAccess.ExistingAttributeValueSupplier;
import io.evitadb.utils.ArrayUtils;
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
 * Co-locates the procedural, index-maintenance routines for attribute mutations, keeping
 * {@link EntityIndexLocalMutationExecutor} focused on orchestration rather than low-level index bookkeeping.
 *
 * ## Responsibility
 *
 * Each public static method in this interface corresponds to one logical attribute-mutation operation:
 * - `executeAttributeUpsert` — insert or replace an attribute value in all relevant index structures
 * - `executeAttributeRemoval` — remove an attribute value from all relevant index structures
 * - `executeAttributeDelta` — atomically apply a numeric delta to an indexed attribute value
 * - `insertInitialSuiteOfSortableAttributeCompounds` — bootstrap compound sort indexes for a new entity or locale
 * - `removeEntireSuiteOfSortableAttributeCompounds` — tear down compound sort indexes when an entity or locale is removed
 *
 * ## Index types maintained
 *
 * Depending on the attribute schema, the operations may update any combination of the following index structures
 * inside the target {@link EntityIndex}:
 * - **Unique index** — enforces value uniqueness per entity type and optional locale
 * - **Filter index** — supports equality and range filtering on attribute values
 * - **Sort index** — supports ordered result sets on a single attribute
 * - **Sort compound index** — supports ordered result sets on multiple attributes combined into one key
 * - **Catalog-level global unique index** — enforces cross-entity-type uniqueness for
 *   {@link io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema} attributes marked as globally unique
 *
 * ## Undo / rollback support
 *
 * All public methods accept an optional `undoActionConsumer`. When provided, each index mutation registers a
 * compensating action (a `Runnable`) with this consumer so that the entire batch of changes can be rolled back
 * if a later step fails. Callers that do not need rollback support may pass `null`.
 *
 * ## Scope awareness
 *
 * Attribute indexing is scope-aware: an attribute that is filterable only in `LIVE` scope will not be indexed
 * in an `ARCHIVE` scope index. The active scope is derived from the `indexForRemoval` / `indexForUpsert`
 * pair at call time, and both indices must agree on their scope — a mismatch is treated as an internal error.
 *
 * ## Usage
 *
 * All methods are `static` — the interface serves purely as a namespace and is implemented only to make those
 * static methods accessible via `implements AttributeIndexMutator`. Direct callers include
 * {@link EntityIndexLocalMutationExecutor} (for entity-level attributes) and
 * {@link ReferenceIndexMutator} (for reference-level attributes).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface AttributeIndexMutator {

	/**
	 * Applies an {@link UpsertAttributeMutation} to the relevant attribute indexes.
	 *
	 * The method first converts the raw `attributeValue` to the canonical target type declared by the attribute
	 * schema (including decimal-place scaling for `BigDecimal` values). It then conditionally updates each of the
	 * following index structures, depending on what the schema requires in the active scope:
	 *
	 * - **Unique index** — if the attribute is unique in scope: removes the previously indexed value (if any) and
	 *   inserts the new value. Because a unique attribute also participates in filter queries, it is additionally
	 *   inserted into the filter index unless the attribute is *also* separately filterable (in which case the
	 *   filterable path handles that).
	 * - **Filter index** — if the attribute is filterable in scope: removes the old value and inserts the new one.
	 * - **Sort index** — if the attribute is sortable in scope: removes the old sort entry and inserts a new one.
	 * - **Catalog global unique index** — when `updateGlobalIndex` is `true` and the attribute is an instance of
	 *   {@link io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema} that is globally unique in scope,
	 *   the catalog-level unique constraint is also maintained.
	 * - **Sortable attribute compounds** — when `updateCompounds` is `true`, every compound sort schema that
	 *   references this attribute is updated: its old compound key is removed and a new one is built using the
	 *   updated attribute value.
	 *
	 * When the `existingValueSupplier` returns no prior value, the removal half of the update is simply skipped.
	 *
	 * @param executor                the executor that owns the target indexes and provides schema / primary-key context
	 * @param referenceSchema         the reference schema when the attribute belongs to a reference, `null` for entity
	 *                                attributes
	 * @param attributeSchemaProvider supplies the {@link io.evitadb.api.requestResponse.schema.AttributeSchemaContract}
	 *                                for the attribute being upserted, as well as compound schemas that reference it
	 * @param existingValueSupplier   provides the attribute's current (pre-mutation) value; used to remove the old
	 *                                entry from each index before inserting the new one
	 * @param indexForRemoval         the {@link EntityIndex} from which the old attribute value is removed; typically
	 *                                the same instance as `indexForUpsert` but may differ during entity scope transitions
	 * @param indexForUpsert          the {@link EntityIndex} into which the new attribute value is inserted
	 * @param attributeKey            identifies the attribute by name and optional locale
	 * @param attributeValue          the raw new value — will be converted to the schema-declared type automatically
	 * @param updateGlobalIndex       when `true`, the catalog-level global unique index is updated for globally-unique
	 *                                attributes; pass `false` when the caller handles global-index updates separately
	 * @param updateCompounds         when `true`, all sortable attribute compounds that include this attribute are
	 *                                rebuilt; pass `false` to skip compound maintenance (e.g. during bulk reindexing
	 *                                where compounds are rebuilt in a separate pass)
	 * @param undoActionConsumer      optional collector of compensating `Runnable` lambdas for rollback; may be `null`
	 * @throws io.evitadb.exception.EvitaInvalidUsageException if the attribute is not defined in the schema
	 * @throws io.evitadb.api.exception.UniqueValueViolationException if inserting the new value would violate a
	 *                                unique or global-unique constraint
	 * @throws io.evitadb.exception.GenericEvitaInternalError if `indexForRemoval` and `indexForUpsert` belong to
	 *                                different scopes
	 */
	static void executeAttributeUpsert(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull ExistingAttributeValueSupplier existingValueSupplier,
		@Nonnull EntityIndex indexForRemoval,
		@Nonnull EntityIndex indexForUpsert,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Serializable attributeValue,
		boolean updateGlobalIndex,
		boolean updateCompounds,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final AttributeSchemaContract attributeDefinition = attributeSchemaProvider.getAttributeSchema(
			attributeKey.attributeName());
		Assert.notNull(attributeDefinition, "Attribute `" + attributeKey.attributeName() + "` not defined in schema!");

		final Serializable valueToInsert = Objects.requireNonNull(
			EvitaDataTypes.toTargetType(
				attributeValue, attributeDefinition.getType(), attributeDefinition.getIndexedDecimalPlaces())
		);

		final Scope scope = getIndexedScope(indexForRemoval, indexForUpsert);
		if (
			attributeDefinition.isUniqueInScope(scope) ||
				attributeDefinition.isFilterableInScope(scope) ||
				attributeDefinition.isSortableInScope(scope)
		) {
			final EntitySchema entitySchema = executor.getEntitySchema();
			final Set<Locale> allowedLocales = entitySchema.getLocales();
			final Locale locale = attributeKey.locale();

			if (attributeDefinition.isUniqueInScope(scope)) {
				final int epkForRemoval = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_UNIQUE_INDEX, Target.EXISTING);
				final Optional<AttributeValue> existingValue = existingValueSupplier.getAttributeValue(attributeKey);
				if (existingValue.isPresent()) {
					final AttributeValue theValue = existingValue.get();
					final Serializable theValueToRemove = Objects.requireNonNull(theValue.value());
					indexForRemoval.removeUniqueAttribute(
						referenceSchema, attributeDefinition, allowedLocales, scope, locale, theValueToRemove,
						epkForRemoval
					);
					if (undoActionConsumer != null) {
						undoActionConsumer.accept(
							() -> indexForUpsert.insertUniqueAttribute(
								referenceSchema, attributeDefinition, allowedLocales, scope, locale, theValue.value(),
								epkForRemoval
							)
						);
					}
					if (!attributeDefinition.isFilterableInScope(scope)) {
						// TOBEDONE JNO this should be replaced with RadixTree (for String values)
						indexForRemoval.removeFilterAttribute(
							referenceSchema, attributeDefinition, allowedLocales, locale, theValue.value(),
							epkForRemoval
						);
						if (undoActionConsumer != null) {
							undoActionConsumer.accept(
								() -> indexForUpsert.insertFilterAttribute(
									referenceSchema, attributeDefinition, allowedLocales, locale, theValue.value(),
									epkForRemoval
								)
							);
						}
					}
				}
				final int epkForUpsert = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_UNIQUE_INDEX, Target.NEW);
				indexForUpsert.insertUniqueAttribute(
					referenceSchema, attributeDefinition, allowedLocales, scope, locale, valueToInsert,
					epkForUpsert
				);
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(
						() -> indexForRemoval.removeUniqueAttribute(
							referenceSchema, attributeDefinition, allowedLocales, scope, locale, valueToInsert,
							epkForUpsert
						)
					);
				}
				if (!attributeDefinition.isFilterableInScope(scope)) {
					// TOBEDONE JNO this should be replaced with RadixTree (for String values)
					indexForUpsert.insertFilterAttribute(
						referenceSchema, attributeDefinition, allowedLocales, locale, valueToInsert, epkForUpsert
					);
					if (undoActionConsumer != null) {
						undoActionConsumer.accept(
							() -> indexForRemoval.removeFilterAttribute(
								referenceSchema, attributeDefinition, allowedLocales, locale, valueToInsert,
								epkForUpsert
							)
						);
					}
				}
			}
			if (attributeDefinition.isFilterableInScope(scope)) {
				final int epkForRemoval = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_FILTER_INDEX, Target.EXISTING);
				final Optional<AttributeValue> existingValue = existingValueSupplier.getAttributeValue(attributeKey);
				if (existingValue.isPresent()) {
					final AttributeValue theValue = existingValue.get();
					final Serializable theValueToRemove = Objects.requireNonNull(theValue.value());
					indexForRemoval.removeFilterAttribute(
						referenceSchema, attributeDefinition, allowedLocales, locale, theValueToRemove,
						epkForRemoval
					);
					if (undoActionConsumer != null) {
						undoActionConsumer.accept(
							() -> indexForUpsert.insertFilterAttribute(
								referenceSchema, attributeDefinition, allowedLocales, locale, theValue.value(),
								epkForRemoval
							)
						);
					}
				}
				final int epkForUpsert = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_FILTER_INDEX, Target.NEW);
				indexForUpsert.insertFilterAttribute(
					referenceSchema, attributeDefinition, allowedLocales, locale, valueToInsert, epkForUpsert
				);
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(
						() -> indexForRemoval.removeFilterAttribute(
							referenceSchema, attributeDefinition,
							allowedLocales, locale, valueToInsert,
							epkForUpsert
						)
					);
				}
			}
			if (attributeDefinition.isSortableInScope(scope)) {
				final Optional<AttributeValue> existingValue = existingValueSupplier.getAttributeValue(attributeKey);
				if (existingValue.isPresent()) {
					final AttributeValue theValue = existingValue.get();
					final int epkForRemoval = executor.getPrimaryKeyToIndex(
						IndexType.ATTRIBUTE_SORT_INDEX, Target.EXISTING
					);
					final Serializable theValueToRemove = Objects.requireNonNull(theValue.value());
					indexForRemoval.removeSortAttribute(
						referenceSchema, attributeDefinition, allowedLocales, locale, theValueToRemove,
						epkForRemoval
					);
					if (undoActionConsumer != null) {
						undoActionConsumer.accept(
							() -> indexForUpsert.insertSortAttribute(
								referenceSchema, attributeDefinition, allowedLocales, locale, theValue.value(),
								epkForRemoval
							)
						);
					}
				}

				final int epkForUpsert = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_SORT_INDEX, Target.NEW);
				indexForUpsert.insertSortAttribute(
					referenceSchema, attributeDefinition, allowedLocales, locale, valueToInsert, epkForUpsert
				);
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(
						() -> indexForRemoval.removeSortAttribute(
							referenceSchema, attributeDefinition, allowedLocales,
							locale, valueToInsert, epkForUpsert
						)
					);
				}
			}

			if (updateGlobalIndex && attributeDefinition instanceof GlobalAttributeSchema globalAttributeSchema &&
				globalAttributeSchema.isUniqueGloballyInScope(scope)) {
				// use the same scope as used in the entity index
				final CatalogIndex catalogIndex = executor.getCatalogIndex(scope);
				final int epkForRemoval = executor.getPrimaryKeyToIndex(
					IndexType.ATTRIBUTE_UNIQUE_INDEX, Target.EXISTING
				);

				final Optional<AttributeValue> existingValue = existingValueSupplier.getAttributeValue(attributeKey);
				if (existingValue.isPresent()) {
					final AttributeValue theValue = existingValue.get();
					final Serializable theValueToRemove = Objects.requireNonNull(theValue.value());
					catalogIndex.removeUniqueAttribute(
						entitySchema, globalAttributeSchema, allowedLocales, locale,
						theValueToRemove, epkForRemoval
					);
					if (undoActionConsumer != null) {
						undoActionConsumer.accept(
							() -> catalogIndex.insertUniqueAttribute(
								entitySchema, globalAttributeSchema, allowedLocales, locale, theValue.value(),
								epkForRemoval
							)
						);
					}
				}

				final int epkForUpsert = executor.getPrimaryKeyToIndex(
					IndexType.ATTRIBUTE_UNIQUE_INDEX, Target.NEW
				);
				catalogIndex.insertUniqueAttribute(
					entitySchema, globalAttributeSchema, allowedLocales, locale, valueToInsert, epkForUpsert
				);
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(
						() -> catalogIndex.removeUniqueAttribute(
							entitySchema, globalAttributeSchema, allowedLocales, locale, valueToInsert, epkForUpsert
						)
					);
				}
			}
		}

		// now update the compounds
		if (updateCompounds) {
			updateSortableAttributeCompounds(
				executor, referenceSchema, attributeSchemaProvider, existingValueSupplier,
				indexForRemoval, indexForUpsert,
				valueToInsert, attributeKey.locale(), attributeDefinition.getName(),
				undoActionConsumer
			);
		}
	}

	/**
	 * Applies a {@link RemoveAttributeMutation} to the relevant attribute indexes.
	 *
	 * The method looks up the attribute's current value via `existingValueSupplier` (fetching it lazily and caching the
	 * result in an `AtomicReference` so the storage is read at most once even when several index types need the same
	 * value). It then removes that value from every applicable index structure in the active scope:
	 *
	 * - **Unique index** — if the attribute is unique in scope, the existing value is removed. The filter-index entry
	 *   that shadows unique values is also removed unless the attribute is separately filterable.
	 * - **Filter index** — if the attribute is filterable in scope, the existing filter entry is removed.
	 * - **Sort index** — if the attribute is sortable in scope, the existing sort entry is removed.
	 * - **Catalog global unique index** — when `updateGlobalIndex` is `true` and the attribute is a globally-unique
	 *   {@link io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema}, the catalog-level entry is removed.
	 * - **Sortable attribute compounds** — when `updateCompounds` is `true`, every compound sort schema that references
	 *   this attribute is updated: its old compound key is removed and, if remaining component values are all non-null,
	 *   a new compound key is inserted without the removed attribute (treated as `null`).
	 *
	 * @param executor                the executor that owns the target indexes and provides schema / primary-key context
	 * @param referenceSchema         the reference schema when the attribute belongs to a reference, `null` for entity
	 *                                attributes
	 * @param attributeSchemaProvider supplies the {@link io.evitadb.api.requestResponse.schema.AttributeSchemaContract}
	 *                                for the attribute being removed, as well as compound schemas that reference it
	 * @param existingValueSupplier   provides the attribute's current (pre-mutation) value; the value is mandatory —
	 *                                its absence causes an internal error because removal of a non-existent value
	 *                                signals index corruption
	 * @param indexForRemoval         the {@link EntityIndex} from which the attribute value is removed
	 * @param indexForUpsert          the {@link EntityIndex} used when rebuilding compound sort keys (may equal
	 *                                `indexForRemoval`)
	 * @param attributeKey            identifies the attribute by name and optional locale
	 * @param updateGlobalIndex       when `true`, the catalog-level global unique index is updated for globally-unique
	 *                                attributes
	 * @param updateCompounds         when `true`, all sortable attribute compounds referencing this attribute are
	 *                                rebuilt
	 * @param undoActionConsumer      optional collector of compensating `Runnable` lambdas for rollback; may be `null`
	 * @throws io.evitadb.exception.EvitaInvalidUsageException if the attribute is not defined in the schema
	 * @throws io.evitadb.exception.GenericEvitaInternalError if the existing attribute value cannot be found in the
	 *                                storage container, indicating index/storage inconsistency
	 */
	static <T extends Serializable & Comparable<T>> void executeAttributeRemoval(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull ExistingAttributeValueSupplier existingValueSupplier,
		@Nonnull EntityIndex indexForRemoval,
		@Nonnull EntityIndex indexForUpsert,
		@Nonnull AttributeKey attributeKey,
		boolean updateGlobalIndex,
		boolean updateCompounds,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final String attributeName = attributeKey.attributeName();
		final AttributeSchemaContract attributeDefinition = attributeSchemaProvider.getAttributeSchema(attributeName);
		Assert.notNull(attributeDefinition, "Attribute `" + attributeName + "` not defined in schema!");

		final Set<Locale> allowedLocales = entitySchema.getLocales();
		final Locale locale = attributeKey.locale();

		final AtomicReference<T> valueToRemove = new AtomicReference<>();
		final Supplier<T> valueToRemoveSupplier = () -> valueToRemove.updateAndGet(
			alreadyKnownOldValue -> {
			if (alreadyKnownOldValue == null) {
				final AttributeValue existingValue = existingValueSupplier.getAttributeValue(attributeKey).orElse(null);
				Assert.notNull(
					existingValue,
					"Attribute `" + attributeDefinition.getName() + "` is unexpectedly not found in " +
						"a container for entity `" + entitySchema.getName() + "` record " +
						executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_INDEX, Target.EXISTING) + "!"
				);
				//noinspection unchecked
				return (T) existingValue.value();
			} else {
				return alreadyKnownOldValue;
			}
		});

		final Scope scope = indexForRemoval.getIndexKey().scope();
		if (attributeDefinition.isUniqueInScope(scope) || attributeDefinition.isFilterableInScope(
			scope) || attributeDefinition.isSortableInScope(scope)) {
			if (attributeDefinition.isUniqueInScope(scope)) {
				final int epkForRemoval = executor.getPrimaryKeyToIndex(
					IndexType.ATTRIBUTE_UNIQUE_INDEX, Target.EXISTING
				);
				indexForRemoval.removeUniqueAttribute(
					referenceSchema, attributeDefinition, allowedLocales, scope, locale, valueToRemoveSupplier.get(),
					epkForRemoval
				);
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(
						() -> indexForRemoval.insertUniqueAttribute(
							referenceSchema, attributeDefinition, allowedLocales, scope, locale,
							valueToRemoveSupplier.get(),
							epkForRemoval
						)
					);
				}
				if (!attributeDefinition.isFilterableInScope(scope)) {
					// TOBEDONE JNO this should be replaced with RadixTree (for String values)
					final int epkForUpsert = executor.getPrimaryKeyToIndex(
						IndexType.ATTRIBUTE_FILTER_INDEX, Target.NEW
					);
					indexForRemoval.removeFilterAttribute(
						referenceSchema, attributeDefinition, allowedLocales, locale, valueToRemoveSupplier.get(),
						epkForUpsert
					);
					if (undoActionConsumer != null) {
						undoActionConsumer.accept(
							() -> indexForRemoval.insertFilterAttribute(
								referenceSchema, attributeDefinition, allowedLocales, locale,
								valueToRemoveSupplier.get(),
								epkForUpsert
							)
						);
					}
				}

				if (updateGlobalIndex && attributeDefinition instanceof GlobalAttributeSchema globalAttributeSchema &&
					globalAttributeSchema.isUniqueGloballyInScope(scope)
				) {
					// use the same scope as used in the entity index
					final CatalogIndex catalogIndex = executor.getCatalogIndex(scope);
					catalogIndex.removeUniqueAttribute(
						entitySchema, globalAttributeSchema, allowedLocales, locale, valueToRemoveSupplier.get(),
						epkForRemoval
					);
					if (undoActionConsumer != null) {
						undoActionConsumer.accept(
							() -> catalogIndex.insertUniqueAttribute(
								entitySchema, globalAttributeSchema, allowedLocales, locale,
								valueToRemoveSupplier.get(),
								epkForRemoval
							)
						);
					}
				}
			}
			if (attributeDefinition.isFilterableInScope(scope)) {
				final int epkForRemoval = executor.getPrimaryKeyToIndex(
					IndexType.ATTRIBUTE_FILTER_INDEX, Target.EXISTING
				);
				indexForRemoval.removeFilterAttribute(
					referenceSchema, attributeDefinition, allowedLocales, locale, valueToRemoveSupplier.get(),
					epkForRemoval
				);
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(
						() -> indexForRemoval.insertFilterAttribute(
							referenceSchema, attributeDefinition, allowedLocales, locale, valueToRemoveSupplier.get(),
							epkForRemoval
						)
					);
				}
			}
			if (attributeDefinition.isSortableInScope(scope)) {
				final int epkForRemoval = executor.getPrimaryKeyToIndex(
					IndexType.ATTRIBUTE_SORT_INDEX, Target.EXISTING
				);
				indexForRemoval.removeSortAttribute(
					referenceSchema, attributeDefinition, allowedLocales, locale, valueToRemoveSupplier.get(),
					epkForRemoval
				);
				if (undoActionConsumer != null) {
					undoActionConsumer.accept(
						() -> indexForRemoval.insertSortAttribute(
							referenceSchema, attributeDefinition, allowedLocales, locale, valueToRemoveSupplier.get(),
							epkForRemoval
						)
					);
				}
			}
		}

		// now update the compounds
		if (updateCompounds) {
			updateSortableAttributeCompounds(
				executor, referenceSchema, attributeSchemaProvider, existingValueSupplier,
				indexForRemoval, indexForUpsert, null, locale, attributeName, undoActionConsumer
			);
		}
	}

	/**
	 * Applies an {@link ApplyDeltaAttributeMutation} to the relevant attribute indexes.
	 *
	 * A delta mutation atomically increments or decrements a numeric attribute value. Because indexes record the
	 * concrete value (not deltas), this method must replace the old indexed value with the computed new value.
	 * Both "old value" and "new value" are resolved lazily and cached in `AtomicReference` objects to avoid
	 * repeated storage reads when multiple index types (unique, filter, sort) each need the same value.
	 *
	 * The new value is computed as: `oldValue + toTargetType(delta, attributeType, indexedDecimalPlaces)`.
	 * The type conversion respects the schema's declared numeric type and fixed-decimal-place scaling.
	 *
	 * Depending on the attribute schema, the following index structures are updated:
	 *
	 * - **Unique index** — old value removed, new value inserted.
	 * - **Filter index** — old value removed, new value inserted.
	 * - **Sort index** — old value removed, new value inserted.
	 * - **Sortable attribute compounds** — all compound sort schemas that reference this attribute are rebuilt
	 *   with the new attribute value. Unlike `executeAttributeUpsert`, compound updates are always performed
	 *   (there is no `updateCompounds` flag on this method).
	 *
	 * Note that `executeAttributeDelta` does **not** update the catalog-level global unique index. Delta mutations
	 * are not permitted on globally-unique attributes by schema validation, so that path is intentionally omitted.
	 *
	 * @param executor                the executor that owns the target indexes and provides schema / primary-key context
	 * @param referenceSchema         the reference schema when the attribute belongs to a reference, `null` for entity
	 *                                attributes
	 * @param attributeSchemaProvider supplies the {@link io.evitadb.api.requestResponse.schema.AttributeSchemaContract}
	 *                                for the attribute being modified, as well as compound schemas that reference it
	 * @param existingValueSupplier   provides the attribute's current (pre-mutation) value; the value is mandatory —
	 *                                its absence causes an internal error
	 * @param indexForRemoval         the {@link EntityIndex} from which the old numeric value is removed
	 * @param indexForUpsert          the {@link EntityIndex} into which the new numeric value is inserted
	 * @param attributeKey            identifies the attribute by name and optional locale
	 * @param delta                   the amount to add to the current value; may be negative for a decrement;
	 *                                will be converted to the attribute's declared numeric type before summing
	 * @param undoActionConsumer      optional collector of compensating `Runnable` lambdas for rollback; may be `null`
	 * @throws io.evitadb.exception.GenericEvitaInternalError if the existing attribute value is not found or is not
	 *                                a numeric type
	 * @throws io.evitadb.api.exception.UniqueValueViolationException if inserting the computed new value would violate
	 *                                a unique constraint
	 */
	static <T extends Serializable & Comparable<T>> void executeAttributeDelta(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull ExistingAttributeValueSupplier existingValueSupplier,
		@Nonnull EntityIndex indexForRemoval,
		@Nonnull EntityIndex indexForUpsert,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Number delta,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final String attributeName = attributeKey.attributeName();
		final AttributeSchemaContract attributeDefinition = attributeSchemaProvider.getAttributeSchema(attributeName);

		final Set<Locale> allowedLocales = entitySchema.getLocales();
		final Locale locale = attributeKey.locale();
		final AtomicReference<T> valueToRemove = new AtomicReference<>();
		final Supplier<T> valueToRemoveSupplier = () -> valueToRemove.updateAndGet(
			alreadyKnownOldAttributeValue -> {
				if (alreadyKnownOldAttributeValue == null) {
					final AttributeValue oldAttributeValue = existingValueSupplier.getAttributeValue(attributeKey)
						.orElse(null);
					Assert.notNull(
						oldAttributeValue,
						"Attribute `" + attributeDefinition.getName() + "` is unexpectedly not found in indexes " +
							"for entity `" + entitySchema.getName() + "` record " +
							executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_INDEX, Target.EXISTING) + "!"
					);
					//noinspection unchecked
					final T theOldValue = (T) oldAttributeValue.value();
					Assert.isTrue(
						theOldValue instanceof Number,
						"Attribute `" + attributeDefinition.getName() + "` in entity `" + entitySchema.getName() + "` is not a number type!"
					);
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

					final Number newValue = Objects.requireNonNull(
						(Number) EvitaDataTypes.toTargetType(
							delta,
							attributeDefinition.getType(),
							attributeDefinition.getIndexedDecimalPlaces()
						)
					);
					//noinspection unchecked
					return (T) NumberUtils.sum(
						(Number) oldValue,
						newValue
					);
				} else {
					return existingValue;
				}
			});

		final Scope scope = getIndexedScope(indexForRemoval, indexForUpsert);

		if (attributeDefinition.isUniqueInScope(scope)) {
			final int epkForRemoval = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_UNIQUE_INDEX, Target.EXISTING);
			final int epkForUpsert = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_UNIQUE_INDEX, Target.NEW);
			indexForRemoval.removeUniqueAttribute(
				referenceSchema, attributeDefinition, allowedLocales, scope, locale, valueToRemoveSupplier.get(),
				epkForRemoval
			);
			indexForUpsert.insertUniqueAttribute(
				referenceSchema, attributeDefinition, allowedLocales, scope, locale, valueToUpdateSupplier.get(),
				epkForUpsert
			);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(
					() -> indexForUpsert.insertUniqueAttribute(
						referenceSchema, attributeDefinition, allowedLocales, scope, locale,
						valueToRemoveSupplier.get(), epkForRemoval
					)
				);
				undoActionConsumer.accept(
					() -> indexForRemoval.removeUniqueAttribute(
						referenceSchema, attributeDefinition, allowedLocales, scope, locale,
						valueToUpdateSupplier.get(), epkForUpsert
					)
				);
			}
		}
		if (attributeDefinition.isFilterableInScope(scope)) {
			final int epkForRemoval = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_FILTER_INDEX, Target.EXISTING);
			final int epkForUpsert = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_FILTER_INDEX, Target.NEW);
			indexForRemoval.removeFilterAttribute(
				referenceSchema, attributeDefinition, allowedLocales, locale, valueToRemoveSupplier.get(),
				epkForRemoval
			);
			indexForUpsert.insertFilterAttribute(
				referenceSchema, attributeDefinition, allowedLocales, locale, valueToUpdateSupplier.get(),
				epkForUpsert
			);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(
					() -> indexForUpsert.insertFilterAttribute(
						referenceSchema, attributeDefinition, allowedLocales, locale, valueToRemoveSupplier.get(),
						epkForRemoval
					)
				);
				undoActionConsumer.accept(
					() -> indexForRemoval.removeFilterAttribute(
						referenceSchema, attributeDefinition, allowedLocales, locale, valueToUpdateSupplier.get(),
						epkForUpsert
					)
				);
			}
		}
		if (attributeDefinition.isSortableInScope(scope)) {
			final int epkForRemove = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_SORT_INDEX, Target.EXISTING);
			final int epkForUpsert = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_SORT_INDEX, Target.NEW);
			indexForRemoval.removeSortAttribute(
				referenceSchema, attributeDefinition, allowedLocales, locale, valueToRemoveSupplier.get(),
				epkForRemove
			);
			indexForUpsert.insertSortAttribute(
				referenceSchema, attributeDefinition, allowedLocales, locale, valueToUpdateSupplier.get(),
				epkForUpsert
			);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(
					() -> indexForUpsert.insertSortAttribute(
						referenceSchema, attributeDefinition, allowedLocales, locale, valueToRemoveSupplier.get(),
						epkForRemove
					)
				);
				undoActionConsumer.accept(
					() -> indexForRemoval.removeSortAttribute(
						referenceSchema, attributeDefinition, allowedLocales, locale, valueToUpdateSupplier.get(),
						epkForUpsert
					)
				);
			}
		}
		// now update the compounds
		updateSortableAttributeCompounds(
			executor, referenceSchema, attributeSchemaProvider, existingValueSupplier,
			indexForRemoval, indexForUpsert, valueToUpdateSupplier.get(), locale, attributeName, undoActionConsumer
		);
	}

	/**
	 * Bootstraps the complete set of sortable attribute compound entries for an entity in the given `entityIndex`.
	 *
	 * A **sortable attribute compound** is a multi-attribute sort key defined in the schema. This method inserts one
	 * compound entry per matching compound schema so that the entity is immediately sortable by those compound keys
	 * even when none of the constituent attributes have been individually mutated yet. Compound entries whose component
	 * values are all `null` are skipped (not inserted).
	 *
	 * The `locale` parameter controls which compounds are created:
	 * - When `locale` is `null` — only **non-localized** compounds (those whose constituent attributes are all
	 *   non-localized) are inserted. This path is taken when a new entity is created or when a new entity-level
	 *   index is established.
	 * - When `locale` is non-null — only **localized** compounds (those that include at least one localized
	 *   attribute) are inserted, using the specified locale. This path is taken when a new locale is added to an
	 *   entity.
	 *
	 * Only compound schemas that are indexed in the active scope (derived from `entityIndex`) are considered.
	 *
	 * This method is designed to be called exactly once per lifecycle event:
	 * - entity creation (locale = null)
	 * - new locale addition (locale = the new locale)
	 * - entity placement into a new reduced index
	 *
	 * @param executor                      the executor that provides schema and primary-key context
	 * @param referenceSchema               the reference schema when the compound belongs to a reference, `null` for
	 *                                      entity-level compounds
	 * @param entityIndex                   the index into which compound sort entries are inserted; its scope
	 *                                      determines which compound schemas are eligible
	 * @param locale                        when non-null, only localized compounds for this locale are created;
	 *                                      when null, only non-localized compounds are created
	 * @param attributeSchemaProvider       supplies individual attribute schemas (needed to resolve attribute types
	 *                                      and localization flags for each compound element)
	 * @param compoundProvider              supplies the complete set of sortable attribute compound schemas to iterate
	 * @param entityAttributeValueSupplier  provides the current attribute values used to build each compound key
	 * @param undoActionConsumer            optional collector of compensating `Runnable` lambdas for rollback;
	 *                                      may be `null`
	 */
	static void insertInitialSuiteOfSortableAttributeCompounds(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull EntityIndex entityIndex,
		@Nullable Locale locale,
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull SortableAttributeCompoundSchemaProvider<?, ? extends SortableAttributeCompoundSchemaContract> compoundProvider,
		@Nonnull ExistingAttributeValueSupplier entityAttributeValueSupplier,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final Map<String, ? extends SortableAttributeCompoundSchemaContract> sortableAttributeCompounds = compoundProvider.getSortableAttributeCompounds();
		if (sortableAttributeCompounds.isEmpty()) {
			return;
		}

		final EntitySchema entitySchema = executor.getEntitySchema();
		final Scope scope = entityIndex.getIndexKey().scope();
		final Stream<SortableAttributeCompoundSchema> allCompounds = sortableAttributeCompounds
			.values()
			.stream()
			// we retrieve schemas from EntitySchema, so we can safely cast them here
			.map(SortableAttributeCompoundSchema.class::cast)
			.filter(it -> it.isIndexedInScope(scope));

		final Stream<SortableAttributeCompoundSchema> filteredCompounds = locale == null ?
			allCompounds.filter(it -> !it.isLocalized(attributeSchemaProvider::getAttributeSchema)) :
			// filter only localized compound schemas
			allCompounds.filter(it -> it.isLocalized(attributeSchemaProvider::getAttributeSchema));

		final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_SORT_INDEX, Target.NEW);
		filteredCompounds.forEach(
			it -> insertNewCompound(
				entityPrimaryKey, entityIndex, it,
				null, null,
				locale,
				entitySchema,
				referenceSchema,
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
	 * Removes the complete set of sortable attribute compound entries for an entity from the given `entityIndex`.
	 *
	 * This is the inverse of {@link #insertInitialSuiteOfSortableAttributeCompounds}: it reconstructs each compound
	 * key from the entity's current attribute values and removes it from the sort compound index. Compound entries
	 * whose component values are all `null` are ignored (they were never inserted, so there is nothing to remove).
	 *
	 * The `locale` parameter controls which compounds are removed, using the same rule as the insert counterpart:
	 * - When `locale` is `null` — only **non-localized** compounds are removed.
	 * - When `locale` is non-null — only **localized** compounds for that locale are removed.
	 *
	 * Only compound schemas that are indexed in the active scope (derived from `entityIndex`) are considered.
	 *
	 * This method is designed to be called exactly once per lifecycle event:
	 * - entity removal (locale = null, removes all non-localized compounds)
	 * - locale removal (locale = the removed locale)
	 * - entity eviction from a reduced index
	 *
	 * @param executor                      the executor that provides schema and primary-key context
	 * @param referenceSchema               the reference schema when the compound belongs to a reference, `null` for
	 *                                      entity-level compounds
	 * @param entityIndex                   the index from which compound sort entries are removed; its scope
	 *                                      determines which compound schemas are eligible
	 * @param locale                        when non-null, only localized compounds for this locale are removed;
	 *                                      when null, only non-localized compounds are removed
	 * @param attributeSchemaProvider       supplies individual attribute schemas (needed to resolve localization flags
	 *                                      for each compound element)
	 * @param compoundProvider              supplies the complete set of sortable attribute compound schemas to iterate
	 * @param entityAttributeValueSupplier  provides the current attribute values used to reconstruct each compound key
	 *                                      that should be removed
	 * @param undoActionConsumer            optional collector of compensating `Runnable` lambdas for rollback;
	 *                                      may be `null`
	 */
	static void removeEntireSuiteOfSortableAttributeCompounds(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull EntityIndex entityIndex,
		@Nullable Locale locale,
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull SortableAttributeCompoundSchemaProvider<?, ? extends SortableAttributeCompoundSchemaContract> compoundProvider,
		@Nonnull ExistingAttributeValueSupplier entityAttributeValueSupplier,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final Scope scope = entityIndex.getIndexKey().scope();
		final Stream<SortableAttributeCompoundSchema> allCompounds = compoundProvider.getSortableAttributeCompounds()
			.values()
			.stream()
			// we retrieve schemas from EntitySchema, so we can safely cast them here
			.map(SortableAttributeCompoundSchema.class::cast)
			.filter(it -> it.isIndexedInScope(scope));

		final Stream<SortableAttributeCompoundSchema> filteredCompounds = locale == null ?
			allCompounds.filter(it -> !it.isLocalized(attributeSchemaProvider::getAttributeSchema)) :
			// filter only localized compound schemas
			allCompounds.filter(it -> it.isLocalized(attributeSchemaProvider::getAttributeSchema));

		final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_SORT_INDEX, Target.EXISTING);
		filteredCompounds.forEach(
			it -> removeOldCompound(
				entityPrimaryKey, entityIndex, it,
				locale,
				entitySchema,
				referenceSchema,
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
	 * Returns the {@link Scope} of `indexForUpsert` and asserts that it equals the scope of `indexForRemoval` when
	 * the two index instances are distinct.
	 *
	 * Every attribute-mutation operation targets a single scope; mixing indexes from different scopes within one
	 * mutation call is an internal programming error.
	 *
	 * @param indexForRemoval the index from which old values are removed
	 * @param indexForUpsert  the index into which new values are inserted
	 * @return the scope shared by both indexes
	 * @throws io.evitadb.exception.GenericEvitaInternalError if the two indexes belong to different scopes
	 */
	@Nonnull
	private static Scope getIndexedScope(@Nonnull EntityIndex indexForRemoval, @Nonnull EntityIndex indexForUpsert) {
		final Scope scope = indexForUpsert.getIndexKey().scope();
		Assert.isPremiseValid(
			indexForUpsert == indexForRemoval || scope == indexForRemoval.getIndexKey().scope(),
			"Index scope mismatch!"
		);
		return scope;
	}

	/**
	 * Updates all sortable attribute compounds that include the named attribute after an attribute mutation.
	 *
	 * The method iterates over every compound schema returned by
	 * {@link AttributeAndCompoundSchemaProvider#getCompoundAttributeSchemas(String)} for `updatedAttributeName`,
	 * filtering to those indexed in the active scope. For each eligible compound it delegates to
	 * {@link #updateCompound} with the appropriate locale:
	 *
	 * - If the compound is **localized** and the mutation was on a **global** (non-localized) attribute
	 *   (`locale == null`), the update must be fanned out across all locales that the entity currently has
	 *   attribute values for, because the global attribute participates in every locale's compound.
	 * - Otherwise a single `updateCompound` call is made for the provided locale (which may be `null` for
	 *   non-localized compounds).
	 *
	 * @param executor                the executor providing schema and primary-key context
	 * @param referenceSchema         the reference schema for reference attributes, `null` for entity attributes
	 * @param attributeSchemaProvider supplies compound schemas that reference the updated attribute
	 * @param existingValueSupplier   provides current attribute values and the set of existing attribute locales
	 * @param indexForRemoval         the index from which the old compound key is removed
	 * @param indexForUpsert          the index into which the new compound key is inserted
	 * @param valueToUpdate           the new value for `updatedAttributeName` within each compound; `null` when the
	 *                                attribute is being removed (the compound element is treated as absent)
	 * @param locale                  the locale of the mutation, or `null` for non-localized attribute mutations
	 * @param updatedAttributeName    the name of the attribute whose value changed
	 * @param undoActionConsumer      optional collector of compensating `Runnable` lambdas for rollback; may be `null`
	 */
	private static void updateSortableAttributeCompounds(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull ExistingAttributeValueSupplier existingValueSupplier,
		@Nonnull EntityIndex indexForRemoval,
		@Nonnull EntityIndex indexForUpsert,
		@Nullable Serializable valueToUpdate,
		@Nullable Locale locale,
		@Nonnull String updatedAttributeName,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final Scope scope = getIndexedScope(indexForRemoval, indexForUpsert);

		attributeSchemaProvider.getCompoundAttributeSchemas(updatedAttributeName)
			.filter(it -> it.isIndexedInScope(scope))
			.forEach(
				compound -> {
					final Function<AttributeKey, AttributeValue> existingAttributeValueProvider =
						it -> existingValueSupplier.getAttributeValue(it).orElse(null);

					boolean isCompoundLocalized = compound.isLocalized(attributeSchemaProvider::getAttributeSchema);
					final Set<Locale> entityAttributeLocales = existingValueSupplier.getEntityExistingAttributeLocales();
					if (isCompoundLocalized && locale == null) {
						// if the compound is localized and the locale is null, it means the global attribute is updated
						// and we need to update all localized compounds using that global attribute
						entityAttributeLocales
							.forEach(
								attributeLocale -> updateCompound(
									executor,
									indexForRemoval, indexForUpsert,
									entitySchema, referenceSchema, compound,
									attributeSchemaProvider, entityAttributeLocales, attributeLocale,
									updatedAttributeName, valueToUpdate,
									existingAttributeValueProvider,
									undoActionConsumer
								)
							);
					} else {
						// otherwise we just update the compound with particular locale or global if locale is null
						updateCompound(
							executor,
							indexForRemoval, indexForUpsert,
							entitySchema, referenceSchema, compound,
							attributeSchemaProvider, entityAttributeLocales, locale,
							updatedAttributeName, valueToUpdate,
							existingAttributeValueProvider,
							undoActionConsumer
						);
					}
				}
			);
	}

	/**
	 * Replaces a single sortable attribute compound entry in the index after an attribute mutation.
	 *
	 * The operation is guarded: when `locale` is non-null, the update is only performed when the entity actually
	 * has attribute values for that locale (i.e. the locale is present in `availableAttributeLocales`), preventing
	 * insertion of compound entries for locales the entity does not participate in.
	 *
	 * The old compound key is reconstructed from the *pre-mutation* attribute values (via `existingAttributeValueProvider`)
	 * and removed. The new key is built by substituting `valueToUpdate` for `updatedAttributeName` while retaining
	 * all other component values from the pre-mutation snapshot.
	 *
	 * @param executor                       the executor providing primary-key context
	 * @param indexForRemoval                the index from which the old compound sort key is removed
	 * @param indexForUpsert                 the index into which the new compound sort key is inserted
	 * @param entitySchema                   the entity schema (used for index persistence metadata)
	 * @param referenceSchema                the reference schema for reference attributes, `null` for entity attributes
	 * @param compound                       the specific compound schema whose index entry is being updated
	 * @param attributeSchemaProvider        supplies individual attribute schemas for localization checks
	 * @param availableAttributeLocales      the set of locales for which the entity currently has attribute values
	 * @param locale                         the locale of the mutation; when non-null the update is skipped unless the
	 *                                       locale is in `availableAttributeLocales`
	 * @param updatedAttributeName           the name of the attribute that changed; its slot in the compound receives
	 *                                       `valueToUpdate` instead of the stored value
	 * @param valueToUpdate                  the new value for the changed attribute element; `null` when removing
	 * @param existingAttributeValueProvider resolves pre-mutation attribute values for all other compound elements
	 * @param undoActionConsumer             optional collector of compensating `Runnable` lambdas for rollback;
	 *                                       may be `null`
	 */
	private static void updateCompound(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull EntityIndex indexForRemoval,
		@Nonnull EntityIndex indexForUpsert,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchema compound,
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull Set<Locale> availableAttributeLocales,
		@Nullable Locale locale,
		@Nullable String updatedAttributeName,
		@Nullable Serializable valueToUpdate,
		@Nonnull Function<AttributeKey, AttributeValue> existingAttributeValueProvider,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		if (locale == null || availableAttributeLocales.contains(locale)) {
			final Function<AttributeElement, AttributeValue> attributeElementValueProvider = createAttributeElementToAttributeValueProvider(
				attributeSchemaProvider, existingAttributeValueProvider, locale
			);

			removeOldCompound(
				executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_SORT_INDEX, Target.EXISTING),
				indexForRemoval, compound, locale,
				entitySchema, referenceSchema, attributeSchemaProvider, attributeElementValueProvider,
				undoActionConsumer
			);

			insertNewCompound(
				executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_SORT_INDEX, Target.NEW),
				indexForUpsert, compound, updatedAttributeName, valueToUpdate, locale,
				entitySchema, referenceSchema, attributeSchemaProvider, attributeElementValueProvider,
				undoActionConsumer
			);
		}
	}

	/**
	 * Builds the new compound sort key and inserts it into the entity index.
	 *
	 * The compound key is assembled by mapping each {@link AttributeElement} in `compound.getAttributeElements()`:
	 * - When the element's attribute name equals `updatedAttributeName`, `valueToUpdate` is used directly (which
	 *   may be `null` when the attribute is being removed).
	 * - Otherwise, the element's value is looked up from `attributeElementValueProvider`.
	 *
	 * If the resulting array is empty or all entries are `null`, the insertion is skipped — a compound index entry
	 * is only meaningful when at least one component has a concrete value.
	 *
	 * @param entityPrimaryKey            the entity primary key to associate with the compound sort entry
	 * @param entityIndex                 the index into which the compound entry is inserted
	 * @param compound                    the compound schema that defines the key structure
	 * @param updatedAttributeName        the attribute whose new value (`valueToUpdate`) overrides the stored value
	 *                                    in the compound; may be `null` when all values come from `attributeElementValueProvider`
	 * @param valueToUpdate               the new value for `updatedAttributeName`; may be `null` (attribute removed)
	 * @param locale                      the locale of the compound entry; `null` for non-localized compounds
	 * @param entitySchema                the entity schema (required for index persistence metadata)
	 * @param referenceSchema             the reference schema for reference attributes, `null` for entity attributes
	 * @param attributeSchemaProvider     supplies attribute schemas for type resolution during index insertion
	 * @param attributeElementValueProvider resolves current attribute values for compound elements other than the
	 *                                    one being updated
	 * @param undoActionConsumer          optional collector of compensating `Runnable` lambdas for rollback;
	 *                                    may be `null`
	 */
	private static void insertNewCompound(
		int entityPrimaryKey,
		@Nonnull EntityIndex entityIndex,
		@Nonnull SortableAttributeCompoundSchema compound,
		@Nullable String updatedAttributeName,
		@Nullable Serializable valueToUpdate,
		@Nullable Locale locale,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull Function<AttributeElement, AttributeValue> attributeElementValueProvider,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final Serializable[] newCompoundValues = compound.getAttributeElements()
			.stream()
			.map(
				it -> Objects.equals(it.attributeName(), updatedAttributeName) ?
					valueToUpdate :
					ofNullable(attributeElementValueProvider.apply(it))
						.map(AttributeValue::value)
						.orElse(null)
			)
			.toArray(Serializable[]::new);

		if (!ArrayUtils.isEmptyOrItsValuesNull(newCompoundValues)) {
			entityIndex.insertSortAttributeCompound(
				entitySchema,
				referenceSchema,
				compound,
				theAttributeName -> attributeSchemaProvider.getAttributeSchema(theAttributeName).getPlainType(),
				locale,
				newCompoundValues, entityPrimaryKey
			);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(
					() -> entityIndex.removeSortAttributeCompound(
						entitySchema, referenceSchema, compound, locale, newCompoundValues, entityPrimaryKey
					)
				);
			}
		}
	}

	/**
	 * Reconstructs the existing compound sort key from stored attribute values and removes it from the index.
	 *
	 * Each {@link AttributeElement} in `compound.getAttributeElements()` is resolved via
	 * `attributeElementValueProvider`. If all resolved values are `null` the removal is skipped, because a
	 * compound entry with all-null values was never inserted in the first place.
	 *
	 * @param entityPrimaryKey             the entity primary key whose compound sort entry should be removed
	 * @param entityIndex                  the index from which the compound entry is removed
	 * @param compound                     the compound schema that defines the key structure
	 * @param locale                       the locale of the compound entry; `null` for non-localized compounds
	 * @param entitySchema                 the entity schema (required for index persistence metadata)
	 * @param referenceSchema              the reference schema for reference attributes, `null` for entity attributes
	 * @param attributeSchemaProvider      supplies attribute schemas for type resolution during undo registration
	 * @param attributeElementValueProvider resolves pre-mutation attribute values for each compound element
	 * @param undoActionConsumer           optional collector of compensating `Runnable` lambdas for rollback;
	 *                                     may be `null`
	 */
	private static void removeOldCompound(
		int entityPrimaryKey,
		@Nonnull EntityIndex entityIndex,
		@Nonnull SortableAttributeCompoundSchema compound,
		@Nullable Locale locale,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull Function<AttributeElement, AttributeValue> attributeElementValueProvider,
		@Nullable Consumer<Runnable> undoActionConsumer
	) {
		final Serializable[] oldCompoundValues = compound.getAttributeElements()
			.stream()
			.map(
				it -> ofNullable(attributeElementValueProvider.apply(it))
					.map(AttributeValue::value)
					.orElse(null)
			)
			.toArray(Serializable[]::new);

		if (!ArrayUtils.isEmptyOrItsValuesNull(oldCompoundValues)) {
			entityIndex.removeSortAttributeCompound(
				entitySchema, referenceSchema, compound, locale, oldCompoundValues, entityPrimaryKey
			);
			if (undoActionConsumer != null) {
				undoActionConsumer.accept(
					() -> entityIndex.insertSortAttributeCompound(
						entitySchema,
						referenceSchema,
						compound,
						theAttributeName -> attributeSchemaProvider.getAttributeSchema(theAttributeName).getPlainType(),
						locale,
						oldCompoundValues, entityPrimaryKey
					)
				);
			}
		}
	}

	/**
	 * Builds a function that resolves the stored {@link AttributeValue} for each {@link AttributeElement} in a
	 * sortable attribute compound.
	 *
	 * The returned function automatically selects the correct {@link io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey}
	 * variant based on whether the attribute is localized:
	 * - **Localized attribute** — the key includes the provided `locale`.
	 * - **Non-localized attribute** — the key omits the locale.
	 *
	 * This abstraction keeps the callers (`insertNewCompound`, `removeOldCompound`) free from locale-resolution logic.
	 *
	 * @param attributeSchemaProvider       supplies individual attribute schemas to determine localization
	 * @param existingAttributeValueProvider resolves the stored attribute value for a given key
	 * @param locale                        the locale to use for localized attributes; may be `null` when processing
	 *                                      non-localized compounds
	 * @return a function mapping each `AttributeElement` to its current `AttributeValue`, or `null` if absent
	 */
	@Nonnull
	private static Function<AttributeElement, AttributeValue> createAttributeElementToAttributeValueProvider(
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull Function<AttributeKey, AttributeValue> existingAttributeValueProvider,
		@Nullable Locale locale
	) {
		return it -> {
			final AttributeSchema attributeSchema = attributeSchemaProvider.getAttributeSchema(it.attributeName());
			return existingAttributeValueProvider.apply(
				attributeSchema.isLocalized() ?
					new AttributeKey(it.attributeName(), locale) :
					new AttributeKey(it.attributeName())
			);
		};
	}

}
