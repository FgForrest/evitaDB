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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.fileOffsetIndex.exception;

import io.evitadb.store.exception.StorageException;
import lombok.Getter;

import java.io.Serial;

/**
 * Exception is thrown when record is found to be corrupted after fetching from the persistent storage. Corrupted error
 * is the record which CRC32C checksum doesn't match, or the expected record length differs from really read data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class CorruptedRecordException extends StorageException {
	@Serial private static final long serialVersionUID = -8235314570778971426L;
	/**
	 * Expected value (i.e. either checksum value or size in bytes).
	 */
	@Getter private final long expected;
	/**
	 * Real value (i.e. either checksum value or size in bytes).
	 */
	@Getter private final long real;

	public CorruptedRecordException(String message, long expected, long real) {
		super(message);
		this.expected = expected;
		this.real = real;
	}

	public CorruptedRecordException(String message, Throwable cause) {
		super(message, cause);
		this.expected = -1L;
		this.real = -1L;
	}

}
