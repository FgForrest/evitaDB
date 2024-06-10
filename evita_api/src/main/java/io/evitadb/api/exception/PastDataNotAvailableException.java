/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import java.io.Serial;
import java.time.OffsetDateTime;

/**
 * Exception thrown when the user requests data from a time in the past that is not available.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class PastDataNotAvailableException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 2157655513551186466L;
	@Getter private final OffsetDateTime offsetDateTime;

	public PastDataNotAvailableException(OffsetDateTime offsetDateTime) {
		super("The latest data available is from " + offsetDateTime + ".");
		this.offsetDateTime = offsetDateTime;
	}

}
