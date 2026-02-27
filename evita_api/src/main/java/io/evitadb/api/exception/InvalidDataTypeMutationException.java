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

import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when attempting to assign a value whose type does not match the type declared in the schema.
 *
 * This exception enforces type safety for entity attributes and associated data. It is thrown during entity
 * construction or mutation when client code provides a value whose Java type is incompatible with the type
 * defined in the corresponding schema definition.
 *
 * The exception carries both the expected type (from the schema) and the actual type (from the provided value)
 * to facilitate debugging and error reporting.
 *
 * **Common Causes:**
 * - Setting an attribute with a type different from its {@link io.evitadb.api.requestResponse.schema.AttributeSchemaContract#getType()}
 * - Providing associated data with a type different from its {@link io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract#getType()}
 * - Passing array values to sortable attributes (which require single values)
 *
 * **Usage Context:**
 * - {@link io.evitadb.api.requestResponse.data.structure.InitialAttributesBuilder}: validates attribute types during entity creation
 * - {@link io.evitadb.api.requestResponse.data.structure.InitialAssociatedDataBuilder}: validates associated data types
 * - Entity builders: enforces schema contracts during entity mutation
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class InvalidDataTypeMutationException extends InvalidMutationException {
	@Serial private static final long serialVersionUID = -333068314618284721L;
	/**
	 * The type expected by the schema definition.
	 */
	@Getter private final Class<?> expectedType;
	/**
	 * The actual type of the value provided by the client.
	 */
	@Getter private final Class<?> actualType;

	/**
	 * Creates a new exception indicating a type mismatch between schema and provided value.
	 *
	 * @param message detailed error message explaining the context and mismatch
	 * @param expectedType the type declared in the schema
	 * @param actualType the type of the value provided by the client
	 */
	public InvalidDataTypeMutationException(@Nonnull String message, @Nonnull Class<?> expectedType, @Nonnull Class<?> actualType) {
		super(message);
		this.expectedType = expectedType;
		this.actualType = actualType;
	}

}
