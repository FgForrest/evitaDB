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

import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.data.ComplexDataObjectConverter;
import io.evitadb.exception.EvitaInvalidUsageException;

import java.io.Serial;

/**
 * Exception is thrown when {@link ComplexDataObjectConverter} fails to extract {@link ComplexDataObject}
 * from the unknown Java POJO. Typically, this exception represents an inner exception that occurred during calling a getter
 * method on the source object.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class SerializationFailedException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 1730199175730118094L;

	public SerializationFailedException(String message) {
		super(message);
	}

	public SerializationFailedException(String message, Throwable ex) {
		super(message, ex);
	}

}
