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

package io.evitadb.index.mutation.local;

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
import io.evitadb.index.IndexWriteTarget;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor.Target;
import io.evitadb.index.mutation.local.dataAccess.ExistingAttributeValueSupplier;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

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
 * ## Write targets
 *
 * Every operation writes into an {@link IndexWriteTarget}. An {@link EntityIndex} receives the values into the
 * structures above, and when it is a reference partition the registry of single-owner partitions mirrors the sort
 * values and chain predecessors it received (see {@link VirtualReferencePartitionWriter}). A
 * {@link VirtualReferencePartition} has no index object: the values are computed exactly as for an index object - the
 * normalization, the rescaling to the indexed decimal places, the compound assembly and the locale selection are the
 * same - and the mirror is their only writer. A virtual partition keeps sort values and chain predecessors only, so
 * the filter and unique entries an index object would receive are not written for it.
 *
 * ## Scope awareness
 *
 * Attribute indexing is scope-aware: an attribute that is filterable only in `LIVE` scope will not be indexed
 * in an `ARCHIVE` scope index. The active scope is derived from the `targetForRemoval` / `targetForUpsert`
 * pair at call time - for a virtual partition from the key of its handle - and both targets must agree on their
 * scope — a mismatch is treated as an internal error.
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
	 * @param targetForRemoval        the target from which the old attribute value is removed; typically the same
	 *                                instance as `targetForUpsert` but may differ during entity scope transitions
	 * @param targetForUpsert         the target into which the new attribute value is inserted
	 * @param attributeKey            identifies the attribute by name and optional locale
	 * @param attributeValue          the raw new value — will be converted to the schema-declared type automatically
	 * @param updateGlobalIndex       when `true`, the catalog-level global unique index is updated for globally-unique
	 *                                attributes; pass `false` when the caller handles global-index updates separately
	 * @param updateCompounds         when `true`, all sortable attribute compounds that include this attribute are
	 *                                rebuilt; pass `false` to skip compound maintenance (e.g. during bulk reindexing
	 *                                where compounds are rebuilt in a separate pass)
	 * @throws io.evitadb.exception.EvitaInvalidUsageException if the attribute is not defined in the schema
	 * @throws io.evitadb.api.exception.UniqueValueViolationException if inserting the new value would violate a
	 *                                unique or global-unique constraint
	 * @throws io.evitadb.exception.GenericEvitaInternalError if `targetForRemoval` and `targetForUpsert` belong to
	 *                                different scopes
	 */
	static void executeAttributeUpsert(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull ExistingAttributeValueSupplier existingValueSupplier,
		@Nonnull IndexWriteTarget targetForRemoval,
		@Nonnull IndexWriteTarget targetForUpsert,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Serializable attributeValue,
		boolean updateGlobalIndex,
		boolean updateCompounds
	) {
		final AttributeSchemaContract attributeDefinition = attributeSchemaProvider.getAttributeSchema(
			attributeKey.attributeName());
		Assert.notNull(attributeDefinition, "Attribute `" + attributeKey.attributeName() + "` not defined in schema!");

		final Serializable valueToInsert = NumberUtils.normalizeForIndexing(
			Objects.requireNonNull(
				EvitaDataTypes.toTargetType(
					attributeValue, attributeDefinition.getType(), attributeDefinition.getIndexedDecimalPlaces())
			),
			attributeDefinition.getIndexedDecimalPlaces()
		);

		final Scope scope = getIndexedScope(targetForRemoval, targetForUpsert);
		if (
			attributeDefinition.isUniqueInScope(scope) ||
				attributeDefinition.isFilterableInScope(scope) ||
				attributeDefinition.isSortableInScope(scope)
		) {
			// report the maintenance, once per entity mutation - the executor throws away the repeats this call makes
			// as the write fans out over the indexes
			executor.reportAttributeTouched(
				attributeSchemaProvider.getContainerName(), attributeDefinition, scope
			);
			final EntitySchema entitySchema = executor.getEntitySchema();
			final Set<Locale> allowedLocales = entitySchema.getLocales();
			final Locale locale = attributeKey.locale();

			final int existingPk = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_INDEX, Target.EXISTING);
			final int newPk = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_INDEX, Target.NEW);
			final Optional<AttributeValue> priorValue = existingValueSupplier.getAttributeValue(attributeKey);
			priorValue.ifPresent(
				value -> {
					// normalize the stored value to the schema scale so the removal targets the same
					// index key the matching upsert produced (see `valueToInsert` above)
					final Serializable valueToRemove = NumberUtils.normalizeForIndexing(
						Objects.requireNonNull(value.value()), attributeDefinition.getIndexedDecimalPlaces()
					);
					if (targetForRemoval instanceof EntityIndex indexForRemoval) {
						indexForRemoval.removeAttribute(
							referenceSchema, attributeDefinition, allowedLocales, scope, locale, valueToRemove,
							existingPk
						);
					}
					VirtualReferencePartitionWriter.attributeValueRemoved(
						executor, referenceSchema, attributeDefinition, asPartition(targetForRemoval), locale,
						valueToRemove
					);
				}
			);
			if (targetForUpsert instanceof EntityIndex indexForUpsert) {
				indexForUpsert.upsertAttribute(
					referenceSchema, attributeDefinition, allowedLocales, scope, locale, valueToInsert, newPk
				);
			}
			VirtualReferencePartitionWriter.attributeValueAdded(
				executor, referenceSchema, attributeDefinition, asPartition(targetForUpsert), locale, valueToInsert
			);

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
					final Serializable theValueToRemove = NumberUtils.normalizeForIndexing(
						Objects.requireNonNull(theValue.value()), attributeDefinition.getIndexedDecimalPlaces()
					);
					catalogIndex.removeUniqueAttribute(
						entitySchema, globalAttributeSchema, allowedLocales, locale,
						theValueToRemove, epkForRemoval, executor.getEntityTypeClassifierResolver()
					);
				}

				final int epkForUpsert = executor.getPrimaryKeyToIndex(
					IndexType.ATTRIBUTE_UNIQUE_INDEX, Target.NEW
				);
				catalogIndex.insertUniqueAttribute(
					entitySchema, globalAttributeSchema, allowedLocales, locale, valueToInsert, epkForUpsert,
					executor.getEntityTypeClassifierResolver()
				);
			}
		}

		// now update the compounds
		if (updateCompounds) {
			updateSortableAttributeCompounds(
				executor, referenceSchema, attributeSchemaProvider, existingValueSupplier,
				targetForRemoval, targetForUpsert,
				valueToInsert, attributeKey.locale(), attributeDefinition.getName()
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
	 * @param targetForRemoval        the target from which the attribute value is removed
	 * @param targetForUpsert         the target used when rebuilding compound sort keys (may equal
	 *                                `targetForRemoval`)
	 * @param attributeKey            identifies the attribute by name and optional locale
	 * @param updateGlobalIndex       when `true`, the catalog-level global unique index is updated for globally-unique
	 *                                attributes
	 * @param updateCompounds         when `true`, all sortable attribute compounds referencing this attribute are
	 *                                rebuilt
	 * @throws io.evitadb.exception.EvitaInvalidUsageException if the attribute is not defined in the schema
	 * @throws io.evitadb.exception.GenericEvitaInternalError if the existing attribute value cannot be found in the
	 *                                storage container, indicating index/storage inconsistency
	 */
	static <T extends Serializable & Comparable<T>> void executeAttributeRemoval(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull ExistingAttributeValueSupplier existingValueSupplier,
		@Nonnull IndexWriteTarget targetForRemoval,
		@Nonnull IndexWriteTarget targetForUpsert,
		@Nonnull AttributeKey attributeKey,
		boolean updateGlobalIndex,
		boolean updateCompounds
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
				return (T) NumberUtils.normalizeForIndexing(
					Objects.requireNonNull(existingValue.value()), attributeDefinition.getIndexedDecimalPlaces()
				);
			} else {
				return alreadyKnownOldValue;
			}
		});

		final Scope scope = targetForRemoval.getIndexKey().scope();
		if (attributeDefinition.isUniqueInScope(scope) || attributeDefinition.isFilterableInScope(
			scope) || attributeDefinition.isSortableInScope(scope)) {
			// a removal maintains the same indexes an upsert does, and is counted the same way
			executor.reportAttributeTouched(
				attributeSchemaProvider.getContainerName(), attributeDefinition, scope
			);
			final int existingPk = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_INDEX, Target.EXISTING);
			// resolved once and handed to the mirror too, so the mirror costs no extra atomic read when it is off
			final T theValueToRemove = valueToRemoveSupplier.get();
			if (targetForRemoval instanceof EntityIndex indexForRemoval) {
				indexForRemoval.removeAttribute(
					referenceSchema, attributeDefinition, allowedLocales, scope, locale, theValueToRemove, existingPk
				);
			}
			VirtualReferencePartitionWriter.attributeValueRemoved(
				executor, referenceSchema, attributeDefinition, asPartition(targetForRemoval), locale,
				theValueToRemove
			);
			if (attributeDefinition.isUniqueInScope(scope)
				&& updateGlobalIndex && attributeDefinition instanceof GlobalAttributeSchema globalAttributeSchema
				&& globalAttributeSchema.isUniqueGloballyInScope(scope)
			) {
				// the catalog-level global-unique index is a separate index object, maintained directly here
				final CatalogIndex catalogIndex = executor.getCatalogIndex(scope);
				catalogIndex.removeUniqueAttribute(
					entitySchema, globalAttributeSchema, allowedLocales, locale, theValueToRemove,
					existingPk, executor.getEntityTypeClassifierResolver()
				);
			}
		}

		// now update the compounds
		if (updateCompounds) {
			updateSortableAttributeCompounds(
				executor, referenceSchema, attributeSchemaProvider, existingValueSupplier,
				targetForRemoval, targetForUpsert, null, locale, attributeName
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
	 * - **Unique index** — old value removed, new value inserted. Because a unique attribute also participates in
	 *   filter queries, its value is additionally shadowed into the filter index unless the attribute is *also*
	 *   separately filterable (in which case the filterable path handles that).
	 * - **Filter index** — old value removed, new value inserted.
	 * - **Sort index** — old value removed, new value inserted.
	 * - **Catalog global unique index** — when `updateGlobalIndex` is `true` and the attribute is a globally-unique
	 *   {@link io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema}, the catalog-level entry of the old
	 *   value is replaced by the new value, exactly as `executeAttributeUpsert` replaces it.
	 * - **Sortable attribute compounds** — all compound sort schemas that reference this attribute are rebuilt
	 *   with the new attribute value. Unlike `executeAttributeUpsert`, compound updates are always performed
	 *   (there is no `updateCompounds` flag on this method).
	 *
	 * @param executor                the executor that owns the target indexes and provides schema / primary-key context
	 * @param referenceSchema         the reference schema when the attribute belongs to a reference, `null` for entity
	 *                                attributes
	 * @param attributeSchemaProvider supplies the {@link io.evitadb.api.requestResponse.schema.AttributeSchemaContract}
	 *                                for the attribute being modified, as well as compound schemas that reference it
	 * @param existingValueSupplier   provides the attribute's current (pre-mutation) value; the value is mandatory —
	 *                                its absence causes an internal error
	 * @param targetForRemoval        the target from which the old compound keys are removed
	 * @param targetForUpsert         the target in which the old numeric value is replaced by the new one
	 * @param attributeKey            identifies the attribute by name and optional locale
	 * @param delta                   the amount to add to the current value; may be negative for a decrement;
	 *                                will be converted to the attribute's declared numeric type before summing
	 * @param updateGlobalIndex       when `true`, the catalog-level global unique index is updated for globally-unique
	 *                                attributes; pass `false` for the reduced indexes the same mutation fans out to,
	 *                                so the catalog-level entry is replaced once per mutation
	 * @throws io.evitadb.exception.GenericEvitaInternalError if the existing attribute value is not found or is not
	 *                                a numeric type
	 * @throws io.evitadb.api.exception.UniqueValueViolationException if inserting the computed new value would violate
	 *                                a unique or global-unique constraint
	 */
	static <T extends Serializable & Comparable<T>> void executeAttributeDelta(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull ExistingAttributeValueSupplier existingValueSupplier,
		@Nonnull IndexWriteTarget targetForRemoval,
		@Nonnull IndexWriteTarget targetForUpsert,
		@Nonnull AttributeKey attributeKey,
		@Nonnull Number delta,
		boolean updateGlobalIndex
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
					final Number sum = NumberUtils.sum((Number) oldValue, newValue);
					//noinspection unchecked
					return (T) NumberUtils.normalizeIfBigDecimal(sum);
				} else {
					return existingValue;
				}
			});

		final Scope scope = getIndexedScope(targetForRemoval, targetForUpsert);

		// a delta rewrites whatever the value is indexed in, so it maintains the same capabilities an upsert does
		executor.reportAttributeTouched(attributeSchemaProvider.getContainerName(), attributeDefinition, scope);

		final int recordId = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_INDEX, Target.NEW);
		// resolved once and handed to the mirror too, so the mirror costs no extra atomic read when it is off
		final T removedValue = valueToRemoveSupplier.get();
		final T addedValue = valueToUpdateSupplier.get();
		if (targetForUpsert instanceof EntityIndex indexForUpsert) {
			indexForUpsert.applyAttributeDelta(
				referenceSchema, attributeDefinition, allowedLocales, scope, locale, removedValue, addedValue, recordId
			);
		}
		// the delta replaced the value in the target it was applied to - the old one went, the new one came
		final ReferencePartition partitionForUpsert = asPartition(targetForUpsert);
		VirtualReferencePartitionWriter.attributeValueRemoved(
			executor, referenceSchema, attributeDefinition, partitionForUpsert, locale, removedValue
		);
		VirtualReferencePartitionWriter.attributeValueAdded(
			executor, referenceSchema, attributeDefinition, partitionForUpsert, locale, addedValue
		);
		if (updateGlobalIndex && attributeDefinition instanceof GlobalAttributeSchema globalAttributeSchema &&
			globalAttributeSchema.isUniqueGloballyInScope(scope)) {
			// the catalog-level global-unique index is a separate index object, so the delta moves its key there too;
			// the old key is normalized as the upsert that stored it normalized it, `addedValue` already is
			final CatalogIndex catalogIndex = executor.getCatalogIndex(scope);
			catalogIndex.removeUniqueAttribute(
				entitySchema, globalAttributeSchema, allowedLocales, locale,
				NumberUtils.normalizeForIndexing(removedValue, attributeDefinition.getIndexedDecimalPlaces()),
				executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_UNIQUE_INDEX, Target.EXISTING),
				executor.getEntityTypeClassifierResolver()
			);
			catalogIndex.insertUniqueAttribute(
				entitySchema, globalAttributeSchema, allowedLocales, locale, addedValue,
				executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_UNIQUE_INDEX, Target.NEW),
				executor.getEntityTypeClassifierResolver()
			);
		}
		// now update the compounds
		updateSortableAttributeCompounds(
			executor, referenceSchema, attributeSchemaProvider, existingValueSupplier,
			targetForRemoval, targetForUpsert, addedValue, locale, attributeName
		);
	}

	/**
	 * Bootstraps the complete set of sortable attribute compound entries for an entity in the given `target`.
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
	 * Only compound schemas that are indexed in the active scope (derived from `target`) are considered.
	 *
	 * This method is designed to be called exactly once per lifecycle event:
	 * - entity creation (locale = null)
	 * - new locale addition (locale = the new locale)
	 * - entity placement into a new reduced index
	 *
	 * @param executor                      the executor that provides schema and primary-key context
	 * @param referenceSchema               the reference schema when the compound belongs to a reference, `null` for
	 *                                      entity-level compounds
	 * @param target                        the target into which compound sort entries are inserted; its scope
	 *                                      determines which compound schemas are eligible
	 * @param locale                        when non-null, only localized compounds for this locale are created;
	 *                                      when null, only non-localized compounds are created
	 * @param attributeSchemaProvider       supplies individual attribute schemas (needed to resolve attribute types
	 *                                      and localization flags for each compound element)
	 * @param compoundProvider              supplies the complete set of sortable attribute compound schemas to iterate
	 * @param entityAttributeValueSupplier  provides the current attribute values used to build each compound key
	 */
	static void insertInitialSuiteOfSortableAttributeCompounds(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull IndexWriteTarget target,
		@Nullable Locale locale,
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull SortableAttributeCompoundSchemaProvider<?, ? extends SortableAttributeCompoundSchemaContract> compoundProvider,
		@Nonnull ExistingAttributeValueSupplier entityAttributeValueSupplier
	) {
		final Map<String, ? extends SortableAttributeCompoundSchemaContract> sortableAttributeCompounds = compoundProvider.getSortableAttributeCompounds();
		if (sortableAttributeCompounds.isEmpty()) {
			return;
		}

		final EntitySchema entitySchema = executor.getEntitySchema();
		final Scope scope = target.getIndexKey().scope();
		final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_SORT_INDEX, Target.NEW);
		// schema resolver and value provider are loop invariant - allocate them once instead of per compound
		final Function<String, ? extends AttributeSchemaContract> schemaResolver = attributeSchemaProvider::getAttributeSchema;
		final boolean wantLocalized = locale != null;
		final Function<AttributeElement, Serializable> attributeElementValueProvider = createAttributeElementValueProvider(
			attributeSchemaProvider,
			attributeKey -> entityAttributeValueSupplier.getAttributeValue(attributeKey).orElse(null),
			locale
		);
		for (final SortableAttributeCompoundSchemaContract rawCompound : sortableAttributeCompounds.values()) {
			// we retrieve schemas from EntitySchema, so we can safely cast them here
			final SortableAttributeCompoundSchema compound = (SortableAttributeCompoundSchema) rawCompound;
			// index only compounds active in this scope, matching the requested localization
			if (!compound.isIndexedInScope(scope) || compound.isLocalized(schemaResolver) != wantLocalized) {
				continue;
			}
			// a new entity (or a new locale, or a new reduced index) writes a compound entry, which is maintenance
			// this compound cost the mutation whether or not any of its attributes were themselves written
			executor.reportSortableCompoundTouched(
				attributeSchemaProvider.getContainerName(), compound.getName(), scope
			);
			insertNewCompound(
				executor, entityPrimaryKey, target, compound,
				null, null,
				locale,
				entitySchema,
				referenceSchema,
				attributeSchemaProvider,
				attributeElementValueProvider
			);
		}
	}

	/**
	 * Removes the complete set of sortable attribute compound entries for an entity from the given `target`.
	 *
	 * This is the inverse of {@link #insertInitialSuiteOfSortableAttributeCompounds}: it reconstructs each compound
	 * key from the entity's current attribute values and removes it from the sort compound index. Compound entries
	 * whose component values are all `null` are ignored (they were never inserted, so there is nothing to remove).
	 *
	 * The `locale` parameter controls which compounds are removed, using the same rule as the insert counterpart:
	 * - When `locale` is `null` — only **non-localized** compounds are removed.
	 * - When `locale` is non-null — only **localized** compounds for that locale are removed.
	 *
	 * Only compound schemas that are indexed in the active scope (derived from `target`) are considered.
	 *
	 * This method is designed to be called exactly once per lifecycle event:
	 * - entity removal (locale = null, removes all non-localized compounds)
	 * - locale removal (locale = the removed locale)
	 * - entity eviction from a reduced index
	 *
	 * @param executor                      the executor that provides schema and primary-key context
	 * @param referenceSchema               the reference schema when the compound belongs to a reference, `null` for
	 *                                      entity-level compounds
	 * @param target                        the target from which compound sort entries are removed; its scope
	 *                                      determines which compound schemas are eligible
	 * @param locale                        when non-null, only localized compounds for this locale are removed;
	 *                                      when null, only non-localized compounds are removed
	 * @param attributeSchemaProvider       supplies individual attribute schemas (needed to resolve localization flags
	 *                                      for each compound element)
	 * @param compoundProvider              supplies the complete set of sortable attribute compound schemas to iterate
	 * @param entityAttributeValueSupplier  provides the current attribute values used to reconstruct each compound key
	 *                                      that should be removed
	 */
	static void removeEntireSuiteOfSortableAttributeCompounds(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull IndexWriteTarget target,
		@Nullable Locale locale,
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull SortableAttributeCompoundSchemaProvider<?, ? extends SortableAttributeCompoundSchemaContract> compoundProvider,
		@Nonnull ExistingAttributeValueSupplier entityAttributeValueSupplier
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final Scope scope = target.getIndexKey().scope();
		final int entityPrimaryKey = executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_SORT_INDEX, Target.EXISTING);
		// schema resolver and value provider are loop invariant - allocate them once instead of per compound
		final Function<String, ? extends AttributeSchemaContract> schemaResolver = attributeSchemaProvider::getAttributeSchema;
		final boolean wantLocalized = locale != null;
		final Function<AttributeElement, Serializable> attributeElementValueProvider = createAttributeElementValueProvider(
			attributeSchemaProvider,
			attributeKey -> entityAttributeValueSupplier.getAttributeValue(attributeKey).orElse(null),
			locale
		);
		for (final SortableAttributeCompoundSchemaContract rawCompound : compoundProvider.getSortableAttributeCompounds().values()) {
			// we retrieve schemas from EntitySchema, so we can safely cast them here
			final SortableAttributeCompoundSchema compound = (SortableAttributeCompoundSchema) rawCompound;
			// remove only compounds active in this scope, matching the requested localization
			if (!compound.isIndexedInScope(scope) || compound.isLocalized(schemaResolver) != wantLocalized) {
				continue;
			}
			// tearing the entry down is the same maintenance the insert above is counted for
			executor.reportSortableCompoundTouched(
				attributeSchemaProvider.getContainerName(), compound.getName(), scope
			);
			removeOldCompound(
				executor, entityPrimaryKey, target, compound,
				locale,
				entitySchema,
				referenceSchema,
				attributeElementValueProvider
			);
		}
	}

	/**
	 * Returns the {@link Scope} of `targetForUpsert` and asserts that it equals the scope of `targetForRemoval` when
	 * the two targets are distinct.
	 *
	 * Every attribute-mutation operation targets a single scope; mixing targets from different scopes within one
	 * mutation call is an internal programming error.
	 *
	 * @param targetForRemoval the target from which old values are removed
	 * @param targetForUpsert  the target into which new values are inserted
	 * @return the scope shared by both targets
	 * @throws io.evitadb.exception.GenericEvitaInternalError if the two targets belong to different scopes
	 */
	@Nonnull
	private static Scope getIndexedScope(
		@Nonnull IndexWriteTarget targetForRemoval,
		@Nonnull IndexWriteTarget targetForUpsert
	) {
		final Scope scope = targetForUpsert.getIndexKey().scope();
		Assert.isPremiseValid(
			targetForUpsert == targetForRemoval || scope == targetForRemoval.getIndexKey().scope(),
			"Index scope mismatch!"
		);
		return scope;
	}

	/**
	 * Returns the target as a reference partition, the way the registry of single-owner partitions mirrors a write
	 * into it - a {@link io.evitadb.index.ReducedEntityIndex} or a {@link VirtualReferencePartition} - or `null` when
	 * the target is an index object of another kind, whose writes are never mirrored. A type test, so a write into an
	 * index object costs nothing more than it did before the mirror existed.
	 *
	 * @param target the target of the write
	 * @return the target as a partition, or `null` when it is none
	 */
	@Nullable
	private static ReferencePartition asPartition(@Nonnull IndexWriteTarget target) {
		return target instanceof ReferencePartition partition ? partition : null;
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
	 * @param targetForRemoval        the target from which the old compound key is removed
	 * @param targetForUpsert         the target into which the new compound key is inserted
	 * @param valueToUpdate           the new value for `updatedAttributeName` within each compound; `null` when the
	 *                                attribute is being removed (the compound element is treated as absent)
	 * @param locale                  the locale of the mutation, or `null` for non-localized attribute mutations
	 * @param updatedAttributeName    the name of the attribute whose value changed
	 */
	private static void updateSortableAttributeCompounds(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull ExistingAttributeValueSupplier existingValueSupplier,
		@Nonnull IndexWriteTarget targetForRemoval,
		@Nonnull IndexWriteTarget targetForUpsert,
		@Nullable Serializable valueToUpdate,
		@Nullable Locale locale,
		@Nonnull String updatedAttributeName
	) {
		final EntitySchema entitySchema = executor.getEntitySchema();
		final Scope scope = getIndexedScope(targetForRemoval, targetForUpsert);

		attributeSchemaProvider.getCompoundAttributeSchemas(updatedAttributeName)
			.filter(it -> it.isIndexedInScope(scope))
			.forEach(
				compound -> {
					// the compound's sort index is rebuilt because one of its attributes changed - maintenance the
					// compound cost this mutation, and attributed to the compound rather than to that attribute
					executor.reportSortableCompoundTouched(
						attributeSchemaProvider.getContainerName(), compound.getName(), scope
					);
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
									targetForRemoval, targetForUpsert,
									entitySchema, referenceSchema, compound,
									attributeSchemaProvider, entityAttributeLocales, attributeLocale,
									updatedAttributeName, valueToUpdate,
									existingAttributeValueProvider
								)
							);
					} else {
						// otherwise we just update the compound with particular locale or global if locale is null
						updateCompound(
							executor,
							targetForRemoval, targetForUpsert,
							entitySchema, referenceSchema, compound,
							attributeSchemaProvider, entityAttributeLocales, locale,
							updatedAttributeName, valueToUpdate,
							existingAttributeValueProvider
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
	 * @param targetForRemoval               the target from which the old compound sort key is removed
	 * @param targetForUpsert                the target into which the new compound sort key is inserted
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
	 */
	private static void updateCompound(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull IndexWriteTarget targetForRemoval,
		@Nonnull IndexWriteTarget targetForUpsert,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchema compound,
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull Set<Locale> availableAttributeLocales,
		@Nullable Locale locale,
		@Nullable String updatedAttributeName,
		@Nullable Serializable valueToUpdate,
		@Nonnull Function<AttributeKey, AttributeValue> existingAttributeValueProvider
	) {
		if (locale == null || availableAttributeLocales.contains(locale)) {
			final Function<AttributeElement, Serializable> attributeElementValueProvider = createAttributeElementValueProvider(
				attributeSchemaProvider, existingAttributeValueProvider, locale
			);

			removeOldCompound(
				executor, executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_SORT_INDEX, Target.EXISTING),
				targetForRemoval, compound, locale,
				entitySchema, referenceSchema, attributeElementValueProvider
			);

			insertNewCompound(
				executor, executor.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_SORT_INDEX, Target.NEW),
				targetForUpsert, compound, updatedAttributeName, valueToUpdate, locale,
				entitySchema, referenceSchema, attributeSchemaProvider, attributeElementValueProvider
			);
		}
	}

	/**
	 * Builds the new compound sort key and inserts it into the target.
	 *
	 * The compound key is assembled by mapping each {@link AttributeElement} in `compound.getAttributeElements()`:
	 * - When the element's attribute name equals `updatedAttributeName`, `valueToUpdate` is used directly (which
	 *   may be `null` when the attribute is being removed).
	 * - Otherwise, the element's value is looked up from `attributeElementValueProvider`.
	 *
	 * If the resulting array is empty or all entries are `null`, the insertion is skipped — a compound index entry
	 * is only meaningful when at least one component has a concrete value.
	 *
	 * @param executor                    the executor, whose write the registry of single-owner partitions mirrors
	 * @param entityPrimaryKey            the entity primary key to associate with the compound sort entry
	 * @param target                      the target into which the compound entry is inserted
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
	 */
	private static void insertNewCompound(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		int entityPrimaryKey,
		@Nonnull IndexWriteTarget target,
		@Nonnull SortableAttributeCompoundSchema compound,
		@Nullable String updatedAttributeName,
		@Nullable Serializable valueToUpdate,
		@Nullable Locale locale,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull Function<AttributeElement, Serializable> attributeElementValueProvider
	) {
		final List<AttributeElement> attributeElements = compound.getAttributeElements();
		final Serializable[] newCompoundValues = new Serializable[attributeElements.size()];
		for (int i = 0; i < attributeElements.size(); i++) {
			final AttributeElement element = attributeElements.get(i);
			if (Objects.equals(element.attributeName(), updatedAttributeName)) {
				newCompoundValues[i] = valueToUpdate;
			} else {
				newCompoundValues[i] = attributeElementValueProvider.apply(element);
			}
		}

		if (!ArrayUtils.isEmptyOrItsValuesNull(newCompoundValues)) {
			final Function<String, Class<?>> attributeTypeProvider =
				theAttributeName -> attributeSchemaProvider.getAttributeSchema(theAttributeName).getPlainType();
			final ReferencePartition partition = asPartition(target);
			if (target instanceof EntityIndex entityIndex) {
				// the sort index normalizes the array in place, the registry of single-owner partitions takes it as
				// it was
				final Serializable[] mirroredValues = VirtualReferencePartitionWriter.copyIfMirrored(
					executor, referenceSchema, partition, newCompoundValues
				);
				entityIndex.insertSortAttributeCompound(
					entitySchema,
					referenceSchema,
					compound,
					attributeTypeProvider,
					locale,
					newCompoundValues, entityPrimaryKey
				);
				if (mirroredValues != null) {
					VirtualReferencePartitionWriter.compoundValueAdded(
						executor, entitySchema, referenceSchema, compound, attributeTypeProvider, partition, locale,
						mirroredValues
					);
				}
			} else {
				// nothing normalizes the array of a virtual partition, so the registry takes the array itself
				VirtualReferencePartitionWriter.compoundValueAdded(
					executor, entitySchema, referenceSchema, compound, attributeTypeProvider, partition, locale,
					newCompoundValues
				);
			}
		}
	}

	/**
	 * Reconstructs the existing compound sort key from stored attribute values and removes it from the target.
	 *
	 * Each {@link AttributeElement} in `compound.getAttributeElements()` is resolved via
	 * `attributeElementValueProvider`. If all resolved values are `null` the removal is skipped, because a
	 * compound entry with all-null values was never inserted in the first place.
	 *
	 * @param executor                     the executor, whose write the registry of single-owner partitions mirrors
	 * @param entityPrimaryKey             the entity primary key whose compound sort entry should be removed
	 * @param target                       the target from which the compound entry is removed
	 * @param compound                     the compound schema that defines the key structure
	 * @param locale                       the locale of the compound entry; `null` for non-localized compounds
	 * @param entitySchema                 the entity schema (required for index persistence metadata)
	 * @param referenceSchema              the reference schema for reference attributes, `null` for entity attributes
	 * @param attributeElementValueProvider resolves pre-mutation attribute values for each compound element
	 */
	private static void removeOldCompound(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		int entityPrimaryKey,
		@Nonnull IndexWriteTarget target,
		@Nonnull SortableAttributeCompoundSchema compound,
		@Nullable Locale locale,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull Function<AttributeElement, Serializable> attributeElementValueProvider
	) {
		final List<AttributeElement> attributeElements = compound.getAttributeElements();
		final Serializable[] oldCompoundValues = new Serializable[attributeElements.size()];
		for (int i = 0; i < attributeElements.size(); i++) {
			oldCompoundValues[i] = attributeElementValueProvider.apply(attributeElements.get(i));
		}

		if (!ArrayUtils.isEmptyOrItsValuesNull(oldCompoundValues)) {
			final ReferencePartition partition = asPartition(target);
			if (target instanceof EntityIndex entityIndex) {
				// the sort index normalizes the array in place, the registry of single-owner partitions takes it as
				// it was
				final Serializable[] mirroredValues = VirtualReferencePartitionWriter.copyIfMirrored(
					executor, referenceSchema, partition, oldCompoundValues
				);
				entityIndex.removeSortAttributeCompound(
					entitySchema, referenceSchema, compound, locale, oldCompoundValues, entityPrimaryKey
				);
				if (mirroredValues != null) {
					VirtualReferencePartitionWriter.compoundValueRemoved(
						executor, entitySchema, referenceSchema, compound, partition, locale, mirroredValues
					);
				}
			} else {
				// nothing normalizes the array of a virtual partition, so the registry takes the array itself
				VirtualReferencePartitionWriter.compoundValueRemoved(
					executor, entitySchema, referenceSchema, compound, partition, locale, oldCompoundValues
				);
			}
		}
	}

	/**
	 * Builds a function that resolves the stored value of each {@link AttributeElement} in a sortable attribute
	 * compound, in the form the compound index holds it.
	 *
	 * The returned function automatically selects the correct {@link io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey}
	 * variant based on whether the attribute is localized:
	 * - **Localized attribute** — the key includes the provided `locale`.
	 * - **Non-localized attribute** — the key omits the locale.
	 *
	 * The stored value is then normalized with {@link NumberUtils#normalizeForIndexing(Serializable, int)} at the
	 * element's own `indexedDecimalPlaces` — the same normalization {@link #executeAttributeUpsert} applies to the
	 * value it hands to {@link #updateSortableAttributeCompounds}. The entity body keeps a `BigDecimalNumberRange` at
	 * the scale of its own bounds, and a range compares by bounds encoded at that scale, so without this step an entry
	 * built from the body (a new entity index, a removal, the unchanged elements of an updated compound) would encode
	 * the range differently from an entry built from a mutation: the two would neither order consistently nor match
	 * each other on removal.
	 *
	 * This abstraction keeps the callers (`insertNewCompound`, `removeOldCompound`) free from locale-resolution logic.
	 *
	 * @param attributeSchemaProvider       supplies individual attribute schemas to determine localization and the
	 *                                      indexed decimal places
	 * @param existingAttributeValueProvider resolves the stored attribute value for a given key
	 * @param locale                        the locale to use for localized attributes; may be `null` when processing
	 *                                      non-localized compounds
	 * @return a function mapping each `AttributeElement` to its current value in index form, or `null` if absent
	 */
	@Nonnull
	private static Function<AttributeElement, Serializable> createAttributeElementValueProvider(
		@Nonnull AttributeAndCompoundSchemaProvider attributeSchemaProvider,
		@Nonnull Function<AttributeKey, AttributeValue> existingAttributeValueProvider,
		@Nullable Locale locale
	) {
		return it -> {
			final AttributeSchema attributeSchema = attributeSchemaProvider.getAttributeSchema(it.attributeName());
			// canonical keys shared by the schema - they are only used to look the existing value up
			final AttributeValue attributeValue = existingAttributeValueProvider.apply(
				attributeSchema.isLocalized() ?
					attributeSchema.getAttributeKey(locale) :
					attributeSchema.getAttributeKey()
			);
			final Serializable value = attributeValue == null ? null : attributeValue.value();
			return value == null ?
				null : NumberUtils.normalizeForIndexing(value, attributeSchema.getIndexedDecimalPlaces());
		};
	}

}
