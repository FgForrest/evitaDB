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

package io.evitadb.index.attribute;

import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.dataType.Scope;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexKey;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * AttributeIndexContract describes the API of {@link AttributeIndex} that maintains data structures for fast accessing
 * filtered, unique and sorted entity attribute data. Interface describes both read and write access to the index.
 *
 * Purpose of this contract interface is to ease using {@link @lombok.experimental.Delegate} annotation
 * in {@link io.evitadb.index.EntityIndex} and minimize the amount of the code in this complex class by automatically
 * delegating all {@link AttributeIndexContract} methods to the {@link AttributeIndex} implementation that is part
 * of this index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface AttributeIndexContract extends AttributeIndexScopeSpecificContract {

	/**
	 * Method inserts new unique attribute to the index.
	 *
	 * @throws UniqueValueViolationException when value is not unique
	 */
	void insertUniqueAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	);

	/**
	 * Method removes existing unique attribute from the index.
	 *
	 * @throws IllegalArgumentException when passed value doesn't match the unique value associated with the record key
	 */
	void removeUniqueAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nonnull Scope scope,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	);

	/**
	 * Method inserts new filterable attribute to the index.
	 */
	void insertFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	);

	/**
	 * Method removes existing filterable attribute from the index.
	 *
	 * @throws IllegalArgumentException when passed value doesn't match the filterable value associated with the record key
	 */
	void removeFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchemaContract,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	);

	/**
	 * Method inserts or updates existing filterable attribute in the index. The method is used only for array type
	 * attributes and allows to extend existing array with new values without the need to remove the whole array and
	 * insert it again.
	 */
	void addDeltaFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	);

	/**
	 * Method updates existing filterable attribute and removes the values from the index. The method is used only for
	 * array type attributes and allows to extend existing array with new values without the need to remove the whole
	 * array and insert it again.
	 */
	void removeDeltaFilterAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	);

	/**
	 * Method inserts new sortable attribute to the index.
	 */
	void insertSortAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	);

	/**
	 * Method removes existing sortable attribute from the index.
	 *
	 * @throws IllegalArgumentException when passed value doesn't match the filterable value associated with the record key
	 */
	void removeSortAttribute(
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Set<Locale> allowedLocales,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		int recordId
	);

	/**
	 * Method inserts new sortable attribute compound to the index. Compound is an array of existing attribute values
	 * that are sorted according to {@link SortableAttributeCompoundSchemaContract#getAttributeElements()} descriptor.
	 */
	void insertSortAttributeCompound(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nonnull Function<String, Class<?>> attributeTypeProvider,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	);

	/**
	 * Method removes existing sortable attribute compound from the index. Compound is an array of existing attribute
	 * values that are sorted according to {@link SortableAttributeCompoundSchemaContract#getAttributeElements()}
	 * descriptor.
	 *
	 * @throws IllegalArgumentException when passed value doesn't match the value associated with the record key
	 */
	void removeSortAttributeCompound(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull SortableAttributeCompoundSchemaContract compoundSchema,
		@Nullable Locale locale,
		@Nonnull Serializable[] value,
		int recordId
	);

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
