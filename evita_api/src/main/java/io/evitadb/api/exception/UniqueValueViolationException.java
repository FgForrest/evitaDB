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

package io.evitadb.api.exception;

import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;

/**
 * Exception thrown when attempting to insert or update an entity with an attribute value that violates
 * a unique constraint. evitaDB enforces attribute uniqueness at the index level when an attribute schema
 * is marked as {@link io.evitadb.api.requestResponse.schema.AttributeSchemaContract#isUnique()}.
 *
 * **Unique constraints can be:**
 *
 * - **Entity-scoped** - the attribute value must be unique within a single entity type (e.g., SKU per product)
 * - **Globally unique** - the attribute value must be unique across all entity types in the catalog
 * - **Locale-specific** - the uniqueness constraint applies per locale for localized attributes
 *
 * This exception occurs during entity indexing when:
 *
 * - Inserting a new entity with an attribute value that already exists in another entity
 * - Updating an entity to use an attribute value that conflicts with another entity
 * - The conflict is detected in {@link io.evitadb.index.attribute.UniqueIndex} or
 *   {@link io.evitadb.index.attribute.GlobalUniqueIndex}
 *
 * The exception provides full details about both the existing entity (that owns the value) and the new
 * entity (attempting to use it), allowing clients to resolve the conflict by either:
 *
 * 1. Choosing a different unique value for the new entity
 * 2. Removing the unique constraint from the attribute schema if uniqueness is not required
 * 3. Deleting or updating the existing entity first if appropriate
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2020
 */
public class UniqueValueViolationException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -3516490780028476047L;
	/**
	 * The name of the attribute that violated the unique constraint.
	 */
	@Getter private final String attributeName;
	/**
	 * The conflicting attribute value that is already present in another entity.
	 */
	@Getter private final Serializable value;
	/**
	 * The entity type of the record that already owns this unique value.
	 */
	@Getter private final String existingRecordType;
	/**
	 * The primary key of the existing entity that owns this unique value.
	 */
	@Getter private final int existingRecordId;
	/**
	 * The entity type of the record being inserted or updated that conflicts.
	 */
	@Getter private final String newRecordType;
	/**
	 * The primary key of the new entity attempting to use the conflicting value.
	 */
	@Getter private final int newRecordId;

	/**
	 * Creates a new exception with full details about the unique constraint violation.
	 *
	 * @param attributeName the name of the attribute with the unique constraint
	 * @param locale the locale for which the constraint applies, or null for non-localized attributes
	 * @param value the conflicting attribute value
	 * @param existingRecordType entity type of the record that already owns the value
	 * @param existingRecordId primary key of the existing record
	 * @param newRecordType entity type of the record attempting to use the value
	 * @param newRecordId primary key of the new record
	 */
	public UniqueValueViolationException(
		@Nonnull String attributeName,
		@Nullable Locale locale,
		@Nonnull Serializable value,
		@Nonnull String existingRecordType,
		int existingRecordId,
		@Nonnull String newRecordType,
		int newRecordId
	) {
		super(
			"Unique constraint violation: attribute `" + attributeName + "` value " + value + "`" + (locale == null ? "" : " in locale `" + locale.toLanguageTag() + "`") +
				" is already present for entity `" + existingRecordType + "` (existing entity PK: " + existingRecordId + ", " +
				"newly inserted " + (existingRecordType.compareTo(newRecordType) == 0 ? "" : "`" + newRecordType + "`") + " entity PK: " + newRecordId + ")!"
		);
		this.attributeName = attributeName;
		this.value = value;
		this.existingRecordId = existingRecordId;
		this.existingRecordType = existingRecordType;
		this.newRecordId = newRecordId;
		this.newRecordType = newRecordType;
	}
}
