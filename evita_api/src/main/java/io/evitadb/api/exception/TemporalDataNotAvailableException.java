/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import javax.annotation.Nonnull;
import java.io.Serial;
import java.time.OffsetDateTime;

/**
 * Exception thrown when the user requests data from a time in the past that is not available.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class TemporalDataNotAvailableException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 2157655513551186466L;
	@Getter private final OffsetDateTime offsetDateTime;
	@Getter private final Long catalogVersion;

	public TemporalDataNotAvailableException() {
		super("No historical data is available.");
		this.offsetDateTime = null;
		this.catalogVersion = null;
	}

	public TemporalDataNotAvailableException(@Nonnull OffsetDateTime offsetDateTime) {
		super("The oldest data available is from " + offsetDateTime + ".");
		this.offsetDateTime = offsetDateTime;
		this.catalogVersion = null;
	}

	public TemporalDataNotAvailableException(long catalogVersion) {
		super("The oldest data available is for catalog version " + catalogVersion + ".");
		this.offsetDateTime = null;
		this.catalogVersion = catalogVersion;
	}

}
