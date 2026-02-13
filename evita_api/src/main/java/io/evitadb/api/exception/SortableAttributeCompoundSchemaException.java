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

import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when attempting to create or modify a sortable attribute compound schema in a way that
 * violates schema definition rules. Sortable attribute compounds allow clients to define multi-attribute
 * sort orders, but they must reference attributes that exist in the entity schema.
 *
 * **Common violations include:**
 *
 * - Referencing an attribute that doesn't exist in the entity schema
 * - Including an attribute that is not marked as sortable
 * - Attempting to modify a compound in a way that would break existing queries
 *
 * This exception extends {@link SchemaAlteringException} because it occurs during schema evolution operations
 * and includes the problematic compound schema definition for inspection.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class SortableAttributeCompoundSchemaException extends SchemaAlteringException {
	@Serial private static final long serialVersionUID = 3314676345576059184L;
	/**
	 * The compound schema that caused the validation failure, useful for debugging and error reporting.
	 */
	@Getter private final SortableAttributeCompoundSchemaContract sortableAttributeCompoundSchema;

	/**
	 * Creates a new exception with details about the invalid sortable attribute compound schema.
	 *
	 * @param message description of the validation failure
	 * @param sortableAttributeCompoundSchema the compound schema definition that failed validation
	 */
	public SortableAttributeCompoundSchemaException(
		@Nonnull String message,
		@Nonnull SortableAttributeCompoundSchemaContract sortableAttributeCompoundSchema
	) {
		super(message + " Compound schema: " + sortableAttributeCompoundSchema);
		this.sortableAttributeCompoundSchema = sortableAttributeCompoundSchema;
	}
}
