/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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
import java.io.Serializable;
import java.util.Set;

/**
 * This exception is thrown when there are unmapped values left after {@link ComplexDataObjectConverter#getOriginalForm(Serializable)}
 * has been finished.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class IncompleteDeserializationException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 3710159236950227750L;

	public IncompleteDeserializationException(Set<String> unmappedProperties) {
		super(
				"These values were not deserialized because there is no valid property in the class for them: " +
				String.join(", ", unmappedProperties) + ". " +
				"Please use @DiscardedData annotation to allow forgetting such data or @RenamedData annotation to " +
						"map old data to a new property."
		);
	}
}
