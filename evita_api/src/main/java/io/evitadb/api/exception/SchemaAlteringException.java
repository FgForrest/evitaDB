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

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Abstract base exception for all errors that occur during schema definition or modification
 * operations. This exception categorizes failures related to schema evolution, validation, and
 * structural changes to entity or catalog schemas.
 *
 * Schema altering encompasses operations such as:
 *
 * - Defining new entity schemas or modifying existing ones
 * - Adding, removing, or updating attributes, references, or associated data definitions
 * - Changing cardinality, uniqueness, or indexing properties
 * - Validating schema constraints and compatibility
 * - Analyzing Java classes for automatic schema generation
 *
 * Subclasses of this exception represent specific failure modes during schema operations, such
 * as naming conflicts, invalid class structures, or attempts to replace existing schemas. By
 * extending {@link InvalidMutationException}, schema altering exceptions are treated as a
 * category of mutation failures, since schema changes are represented as mutations in evitaDB's
 * architecture.
 *
 * This exception is typically thrown during schema builder operations, schema mutation
 * application, or reflection-based schema generation from annotated classes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public abstract class SchemaAlteringException extends InvalidMutationException {
	@Serial private static final long serialVersionUID = 4307756062648183906L;

	/**
	 * Constructs a new schema altering exception with a descriptive message.
	 *
	 * @param message explanation of what schema operation failed and why
	 */
	protected SchemaAlteringException(@Nonnull String message) {
		super(message);
	}

	/**
	 * Constructs a new schema altering exception with a descriptive message and the underlying
	 * cause of the failure.
	 *
	 * @param message explanation of what schema operation failed and why
	 * @param cause   the underlying exception that caused the schema operation to fail
	 */
	protected SchemaAlteringException(@Nonnull String message, @Nonnull Throwable cause) {
		super(message, cause);
	}

}
