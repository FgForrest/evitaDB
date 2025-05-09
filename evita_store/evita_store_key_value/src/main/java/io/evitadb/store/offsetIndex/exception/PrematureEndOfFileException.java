/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

import java.io.Serial;

/**
 * This exception is thrown when there is not enough data in the file to read the requested record.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class PrematureEndOfFileException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -3281209255961879626L;

	public PrematureEndOfFileException(long realFileSize, long startOffset, long endOffset) {
		super(
			"Premature end of the file detected! Real file size is " + realFileSize +
				" bytes, but the requested record starts at " + startOffset + " bytes and ends at " + endOffset +
				" bytes. This indicates that the file is corrupted or truncated."
		);
	}

}
