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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when the return type specified in {@link EvitaSessionContract#query(Query, Class)} does
 * not match the actual result type determined by the query's `require` constraints. evitaDB supports
 * multiple output formats controlled by the query definition, and the expected type parameter must align
 * with the query's response structure.
 *
 * **Common mismatches:**
 *
 * - Expecting `SealedEntity` but query returns `EntityReference` (missing `entityFetch()` requirement)
 * - Expecting `EntityReference` but query includes `entityFetch()` producing full entity
 * - Expecting a custom proxy type but query doesn't specify the appropriate entity body requirements
 * - Type parameter doesn't match the configured entity type in the query
 *
 * **Resolution:**
 *
 * The exception message explicitly instructs clients to add the correct `require` constraint:
 *
 * ```java
 * // Wrong - expects SealedEntity but query returns EntityReference
 * session.query(query(collection("Product")), SealedEntity.class);
 *
 * // Correct - add entityFetch to get full entities
 * session.query(
 *     query(collection("Product"), require(entityFetch())),
 *     SealedEntity.class
 * );
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class UnexpectedResultException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 334947152990851707L;
	/**
	 * The Java class that the client expected to receive in the query results.
	 */
	@Getter private final Class<?> expectedType;
	/**
	 * The actual Java class that the query produced based on its requirements.
	 */
	@Getter private final Class<?> actualType;

	/**
	 * Creates a new exception with details about the type mismatch.
	 *
	 * @param expectedType the class the client expected (from query method parameter)
	 * @param actualType the class the query actually produced
	 */
	public UnexpectedResultException(@Nonnull Class<?> expectedType, @Nonnull Class<?> actualType) {
		super(
				"Evita response contains data of type " + actualType.getName() + " but client expects them " +
						"to be of type " + expectedType.getName() + "! Please correct the query by adding proper " +
						"require object that is responsible for controlling output object type."
		);
		this.expectedType = expectedType;
		this.actualType = actualType;
	}
}
