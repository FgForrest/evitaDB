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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
 * Exception is thrown when invalid sortable attribute compound schema is about to be created or made invalid.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class SortableAttributeCompoundSchemaException extends SchemaAlteringException {
	@Serial private static final long serialVersionUID = 3314676345576059184L;
	@Getter private final SortableAttributeCompoundSchemaContract sortableAttributeCompoundSchema;

	public SortableAttributeCompoundSchemaException(
		@Nonnull String message,
		@Nonnull SortableAttributeCompoundSchemaContract sortableAttributeCompoundSchema
	) {
		super(message + " Compound schema: " + sortableAttributeCompoundSchema);
		this.sortableAttributeCompoundSchema = sortableAttributeCompoundSchema;
	}
}
