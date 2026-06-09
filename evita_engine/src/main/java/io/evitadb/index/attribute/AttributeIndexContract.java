/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.index.attribute;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Set;

/**
 * AttributeIndexContract is the read surface of the attribute index — it prescribes the retrievers that expose the
 * filtered, unique, sorted and chainable attribute data structures maintained for the entities. The mutable surface
 * that creates and updates this data lives on {@link AttributeIndexEditorContract}, which extends this contract (the
 * same read-only / editor split used by {@link io.evitadb.api.requestResponse.data.AttributesContract} and
 * {@link io.evitadb.api.requestResponse.data.AttributesEditor}).
 *
 * Purpose of this contract interface is to ease using {@link @lombok.experimental.Delegate} annotation
 * in {@link io.evitadb.index.EntityIndex} and minimize the amount of the code in this complex class by automatically
 * delegating all {@link AttributeIndexContract} retrievers to the {@link AttributeIndex} implementation that is part
 * of this index. The scope-aware unique lookup is intentionally kept OUT of this surface — in the separate
 * {@link AttributeIndexScopeSpecificContract}, which only {@link AttributeIndex} implements — so that it is never
 * auto-forwarded to {@link io.evitadb.index.EntityIndex}; the entity index exposes only a scope-locked variant that
 * resolves the scope from its own index key.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface AttributeIndexContract {

	/**
	 * Returns collection of all unique indexes in this {@link AttributeIndex} instance.
	 */
	@Nonnull
	Set<AttributeIndexKey> getUniqueIndexes();

	/**
	 * Returns collection of all filter indexes in this {@link AttributeIndex} instance.
	 */
	@Nonnull
	Set<AttributeIndexKey> getFilterIndexes();

	/**
	 * Returns {@link FilterIndex} for passed lookup key.
	 */
	@Nullable
	FilterIndex getFilterIndex(@Nonnull AttributeIndexKey lookupKey);

	/**
	 * Returns index that maintains filterable attributes for records in the index.
	 *
	 * @param referenceSchema the reference schema that holds the attribute - might be null for entity level attributes
	 * @param attributeSchema the attribute schema to find the index for
	 * @param locale might not be passed for language agnostic attributes
	 * @return NULL value when there is no unique index associated with this `attributeName`
	 */
	@Nullable
	FilterIndex getFilterIndex(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale locale
	);

	/**
	 * Returns collection of all sort indexes in this {@link AttributeIndex} instance.
	 */
	@Nonnull
	Set<AttributeIndexKey> getSortIndexes();

	/**
	 * Returns {@link SortIndex} for passed lookup key.
	 */
	@Nullable
	SortIndex getSortIndex(@Nonnull AttributeIndexKey lookupKey);

	/**
	 * Returns index that maintains sortable attributes for records in the index.
	 *
	 * @param referenceSchema the reference schema that holds the attribute - might be null for entity level attributes
	 * @param attributeSchema the attribute schema to find the index for
	 * @param locale might not be passed for language agnostic attributes
	 * @return NULL value when there is no sort index associated with this `attributeName`
	 */
	@Nullable
	SortIndex getSortIndex(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale locale
	);

	/**
	 * Returns index that maintains sortable attributes for records in the index.
	 *
	 * @param entitySchema the entity schema to which the reference/attribute belongs
	 * @param referenceSchema the reference schema that holds the attribute - might be null for entity level attributes
	 * @param compoundSchema the attribute schema to find the index for
	 * @param locale might not be passed for language agnostic attributes
	 * @return NULL value when there is no sort index associated with this `attributeName`
	 */
	@Nullable
	SortIndex getSortIndex(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nullable Locale locale
	);

	/**
	 * Returns collection of all chain indexes in this {@link AttributeIndex} instance.
	 */
	@Nonnull
	Set<AttributeIndexKey> getChainIndexes();

	/**
	 * Returns {@link ChainIndex} for passed lookup key.
	 */
	@Nullable
	ChainIndex getChainIndex(@Nonnull AttributeIndexKey lookupKey);

	/**
	 * Returns index that maintains chainable attributes for records in the index.
	 *
	 * @param referenceSchema the reference schema that holds the attribute - might be null for entity level attributes
	 * @param attributeSchema the attribute schema to find the index for
	 * @param locale might not be passed for language agnostic attributes
	 * @return NULL value when there is no chain index associated with this `attributeName`
	 */
	@Nullable
	ChainIndex getChainIndex(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nullable Locale locale
	);

	/**
	 * Returns index that maintains chainable attributes for records in the index.
	 *
	 * @param entitySchema  the entity schema to which the reference/attribute belongs
	 * @param referenceSchema the reference schema that holds the attribute - might be null for entity level attributes
	 * @param compoundSchema the attribute schema to find the index for
	 * @param locale might not be passed for language agnostic attributes
	 * @return NULL value when there is no chain index associated with this `attributeName`
	 */
	@Nullable
	ChainIndex getChainIndex(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nullable Locale locale
	);

	/**
	 * Returns true when this index contains no data and may be safely purged.
	 */
	boolean isAttributeIndexEmpty();

	/**
	 * Method returns collection of all modified parts of this index that were modified and needs to be stored.
	 */
	void getModifiedStorageParts(int entityIndexPrimaryKey, @Nonnull TrappedChanges trappedChanges);

}
