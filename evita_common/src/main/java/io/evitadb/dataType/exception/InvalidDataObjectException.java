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

package io.evitadb.dataType.exception;

import io.evitadb.dataType.data.ComplexDataObjectConverter;
import io.evitadb.exception.EvitaInvalidUsageException;

import java.io.Serial;

/**
 * Exception is thrown when passed data object (or its class) is unusable for {@link ComplexDataObjectConverter}
 * processing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class InvalidDataObjectException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -4478493738887924742L;

	public InvalidDataObjectException(String message) {
		super(message);
	}

}
