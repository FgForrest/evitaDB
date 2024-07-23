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

package io.evitadb.store.dataType.exception;

import io.evitadb.store.exception.SerializationException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This exception is used in case that serialized form of the class is about to be deserialized, but the class was
 * serialized and marked with `serialVersionUID` for which there is no backward compatible deserializer. I.e. the stored
 * format of the object is too old to be supported in current version of the library.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class StoredVersionNotSupportedException extends SerializationException {
	@Serial private static final long serialVersionUID = -6363067960456231518L;
	@Getter private final Class<?> serializedClass;
	@Getter private final long encounteredVersion;
	@Getter private final Set<Long> currentlySupportedVersions;

	public StoredVersionNotSupportedException(@Nonnull Class<?> serializedClass, long encounteredVersion, @Nonnull Set<Long> currentlySupportedVersions) {
		super(
				"Cannot deserialize class " + serializedClass.getName() + " with serial version UID " + encounteredVersion + ". " +
					"Supported backward compatible versions for this class are: " +
					(currentlySupportedVersions.isEmpty() ? "none" : currentlySupportedVersions.stream().map(Object::toString).collect(Collectors.joining(", ")))
		);
		this.serializedClass = serializedClass;
		this.encounteredVersion = encounteredVersion;
		this.currentlySupportedVersions = currentlySupportedVersions;
	}
}
