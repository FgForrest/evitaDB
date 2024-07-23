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

import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import java.io.Serial;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exception is thrown when passed type doesn't represent a valid data type supported by EvitaDB.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class UnsupportedDataTypeException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -4608107879558419907L;
	@Getter private final Class<?> type;
	@Getter private final Set<Class<?>> supportedDataTypes;

	public UnsupportedDataTypeException(Class<?> type, Set<Class<?>> supportedDataTypes) {
		super(
				"Type " + type.getName() + " is not supported. Only these data types are known to Evita: " +
						supportedDataTypes.stream().map(Class::getSimpleName).collect(Collectors.joining(", "))
		);
		this.type = type;
		this.supportedDataTypes = supportedDataTypes;
	}
}
