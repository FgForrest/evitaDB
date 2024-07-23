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

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * This exception is thrown when target type cannot be converted to requested type.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class InconvertibleDataTypeException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -117475978928554127L;
	@Getter private final Class<?> requestedType;
	@Getter private final Class<?> providedType;
	@Getter private final Serializable value;

	public InconvertibleDataTypeException(@Nonnull Class<?> requestedType, @Nonnull Serializable value) {
		super(
			"The value `" + value + "` cannot be converted to the type `" + requestedType.getName() + "`!"
		);
		this.requestedType = requestedType;
		this.providedType = value.getClass();
		this.value = value;
	}

	public InconvertibleDataTypeException(@Nonnull Class<?> requestedType, @Nonnull Class<?> providedType) {
		super(
			"The type `" + providedType.getName() + "` cannot be converted to the type `" + requestedType.getName() + "`!"
		);
		this.requestedType = requestedType;
		this.providedType = providedType;
		this.value = null;
	}
}
