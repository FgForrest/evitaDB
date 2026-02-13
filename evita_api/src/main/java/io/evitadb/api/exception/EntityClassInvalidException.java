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
import java.io.Serial;

/**
 * Exception thrown when the proxy factory fails to wrap a `SealedEntity` into a custom entity interface or class
 * due to an invalid type definition.
 *
 * This exception occurs during entity materialization when evitaDB attempts to map query results to custom Java types
 * (interfaces or classes annotated with entity-related annotations). Common causes include:
 *
 * - Invalid method signatures that cannot be mapped to entity properties
 * - Missing or conflicting annotations on interface methods
 * - Type mismatches between method return types and entity attribute types
 * - Violations of proxy generation constraints (e.g., attempting to set all prices when price list is fixed)
 * - Reflection or bytecode generation failures
 *
 * The exception preserves the problematic class type and the underlying cause for debugging purposes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntityClassInvalidException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 4659727444287535443L;
	/**
	 * The model class or interface that could not be used as a proxy target.
	 */
	@Getter private final Class<?> modelClass;

	/**
	 * Creates exception with the original cause that triggered the proxy generation failure.
	 *
	 * @param modelClass the class that could not be used as entity proxy target
	 * @param cause the underlying exception that caused the proxy generation to fail
	 */
	public EntityClassInvalidException(@Nonnull Class<?> modelClass, @Nonnull Throwable cause) {
		super(
			"Failed to wrap `SealedEntity` into class `" + modelClass + "` due to: " + cause.getMessage(),
			"Failed to wrap `SealedEntity`.",
			cause
		);
		this.modelClass = modelClass;
	}

	/**
	 * Creates exception with a descriptive error message explaining why the class is invalid.
	 *
	 * @param modelClass the class that could not be used as entity proxy target
	 * @param message detailed explanation of why the class definition is invalid
	 */
	public EntityClassInvalidException(@Nonnull Class<?> modelClass, @Nonnull String message) {
		super(
			"Failed to wrap `SealedEntity` into class `" + modelClass + "` due to: " + message,
			"Failed to wrap `SealedEntity`."
		);
		this.modelClass = modelClass;
	}
}
