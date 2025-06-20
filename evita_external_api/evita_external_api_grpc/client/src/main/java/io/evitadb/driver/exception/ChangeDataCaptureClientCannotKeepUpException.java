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

package io.evitadb.driver.exception;


import io.evitadb.exception.EvitaInternalError;
import lombok.Getter;

import java.io.Serial;

/**
 * Exception thrown when a client consuming the Change Data Capture (CDC) stream cannot process
 * the incoming changes fast enough to keep up with the server's data flow rate.
 *
 * When this exception occurs, the CDC stream is terminated to prevent resource exhaustion.
 * The client application needs to create a new CDC stream connection, starting from the
 * version and index values provided in this exception to continue capturing changes
 * without missing any data.
 *
 * The `version` represents the transaction version number, and the `index` represents
 * the position within that transaction's change set where the client should resume.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ChangeDataCaptureClientCannotKeepUpException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -7614507587751227277L;
	@Getter private final long version;
	@Getter private final long index;

	public ChangeDataCaptureClientCannotKeepUpException(long version, long index) {
		super(
			"Client cannot keep up with the change data capture stream and was terminated. " +
				"To continue stream capture, create new capture starting with version: " + version + " and index: " + index
		);
		this.version = version;
		this.index = index;
	}
}
