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
 * This exception is thrown when client code tries to assign value of different type than defined in the schema
 * to the attribute.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class InvalidDataTypeMutationException extends InvalidMutationException {
	@Serial private static final long serialVersionUID = -333068314618284721L;
	@Getter private final Class<?> expectedType;
	@Getter private final Class<?> actualType;

	public InvalidDataTypeMutationException(@Nonnull String message, @Nonnull Class<?> expectedType, @Nonnull Class<?> actualType) {
		super(message);
		this.expectedType = expectedType;
		this.actualType = actualType;
	}

}
