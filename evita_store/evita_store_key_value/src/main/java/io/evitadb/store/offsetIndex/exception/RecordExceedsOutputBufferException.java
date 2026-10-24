/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.store.offsetIndex.exception;

import io.evitadb.exception.EvitaInternalError;
import lombok.Getter;

import java.io.Serial;

/**
 * Exception is thrown when a stored record is longer than the configured output buffer of the instance that has to
 * copy it - the record was written by an instance configured with a larger buffer. Every record an instance writes
 * fits its own buffer, and a raw copy (compaction) keeps records as they are, so the configured size is the limit of
 * what can be copied; it is deliberately not enlarged to fit the data. The stored data is intact - raising the
 * configured size to at least the record length resolves it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class RecordExceedsOutputBufferException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -2862650210457782347L;
	/**
	 * Length of the stored record in bytes, header and checksum included.
	 */
	@Getter private final int recordLength;
	/**
	 * The configured output buffer size in bytes.
	 */
	@Getter private final int outputBufferSize;

	/**
	 * Creates the exception for a record that does not fit the configured output buffer.
	 *
	 * @param recordPosition   position of the record in its file
	 * @param recordLength     length of the stored record in bytes
	 * @param outputBufferSize the configured output buffer size in bytes
	 */
	public RecordExceedsOutputBufferException(long recordPosition, int recordLength, int outputBufferSize) {
		super(
			"The stored record at position " + recordPosition + " is " + recordLength + " bytes long, more than " +
				"the configured `outputBufferSize` of " + outputBufferSize + " bytes - it was written by an instance " +
				"configured with a larger output buffer. The stored data is intact; set `storage.outputBufferSize` " +
				"to at least " + recordLength + " bytes."
		);
		this.recordLength = recordLength;
		this.outputBufferSize = outputBufferSize;
	}

}
