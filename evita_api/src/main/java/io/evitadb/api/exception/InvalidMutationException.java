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

import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Base exception thrown when a {@link Mutation} fails validation or cannot be applied to the target data.
 *
 * This exception serves as the superclass for all mutation-related validation failures in evitaDB. It is thrown
 * when a mutation violates consistency rules, data integrity constraints, or schema contracts. Mutations are
 * validated both before and during application to ensure that database state remains consistent.
 *
 * **Common Subclasses:**
 * - {@link InvalidDataTypeMutationException}: type mismatch between provided value and schema
 * - {@link MandatoryAttributesNotProvidedException}: missing required attributes
 * - {@link MandatoryAssociatedDataNotProvidedException}: missing required associated data
 * - Other domain-specific mutation validation failures
 *
 * **Constructors:**
 * This exception provides multiple constructors to support different error reporting scenarios:
 * - Single message: for simple validation failures
 * - Private + public message: for separating detailed internal diagnostics from user-facing errors
 * - With cause: for wrapping underlying exceptions that caused the mutation to fail
 *
 * **Usage Context:**
 * - {@link Mutation} implementations: validate mutation preconditions
 * - Entity builders: enforce schema contracts during entity construction
 * - Mutation executors: validate and apply mutations to entity collections
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class InvalidMutationException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -7558310372832040516L;

	/**
	 * Creates a new exception with a single error message.
	 *
	 * @param message error message describing the validation failure
	 */
	public InvalidMutationException(@Nonnull String message) {
		super(message);
	}

	/**
	 * Creates a new exception with separate private and public messages.
	 *
	 * The private message may contain internal details for logging and debugging, while the public message
	 * is suitable for displaying to end users or external API clients.
	 *
	 * @param privateMessage detailed message for internal logging (may contain sensitive or technical details)
	 * @param publicMessage user-facing message suitable for external consumption
	 */
	public InvalidMutationException(@Nonnull String privateMessage, @Nonnull String publicMessage) {
		super(privateMessage, publicMessage);
	}

	/**
	 * Creates a new exception with separate private and public messages and an underlying cause.
	 *
	 * @param privateMessage detailed message for internal logging
	 * @param publicMessage user-facing message
	 * @param cause the underlying exception that caused this mutation to fail
	 */
	public InvalidMutationException(@Nonnull String privateMessage, @Nonnull String publicMessage, @Nonnull Throwable cause) {
		super(privateMessage, publicMessage, cause);
	}

	/**
	 * Creates a new exception with a message and an underlying cause.
	 *
	 * @param publicMessage error message describing the validation failure
	 * @param cause the underlying exception that caused this mutation to fail
	 */
	public InvalidMutationException(@Nonnull String publicMessage, @Nonnull Throwable cause) {
		super(publicMessage, cause);
	}

}
