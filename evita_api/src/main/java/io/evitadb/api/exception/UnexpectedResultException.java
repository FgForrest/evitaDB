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

import java.io.Serial;

/**
 * This exception is thrown when {@link EvitaSessionContract#query(Query, Class)} contains different object
 * type in the result data than is expected by the other parameter.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class UnexpectedResultException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 334947152990851707L;
	@Getter private final Class<?> expectedType;
	@Getter private final Class<?> actualType;

	public UnexpectedResultException(Class<?> expectedType, Class<?> actualType) {
		super(
				"Evita response contains data of type " + actualType.getName() + " but client expects them " +
						"to be of type " + expectedType.getName() + "! Please correct the query by adding proper " +
						"require object that is responsible for controlling output object type."
		);
		this.expectedType = expectedType;
		this.actualType = actualType;
	}
}
