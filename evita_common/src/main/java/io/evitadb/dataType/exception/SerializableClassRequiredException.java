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
import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception is thrown when any of the fields or internally used types of the Java object that is converted to
 * {@link ComplexDataObject} doesn't implement {@link java.io.Serializable} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class SerializableClassRequiredException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -2246655193199206258L;
	@Getter private final Class<?> nonSerializableClass;

	public SerializableClassRequiredException(@Nonnull Class<?> nonSerializableClass) {
		super("Serializable classes are required in data objects. The class `" + nonSerializableClass + "` doesn't implement `Serializable` interface.");
		this.nonSerializableClass = nonSerializableClass;
	}

}
