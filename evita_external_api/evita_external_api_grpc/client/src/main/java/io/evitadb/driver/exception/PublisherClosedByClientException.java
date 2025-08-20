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

package io.evitadb.driver.exception;

import io.evitadb.exception.EvitaInternalError;

import java.io.Serial;

/**
 * Informational exception thrown when client closes publisher manually using a gRPC server. This exception must
 * be translated to successful completion of the publisher.
 *
 * Note: this is because when client observer cancels the stream, the server doesn't know if it was because of
 * client's call or e.g. network error, so the server returns error to clients in both cases. This exception
 * exists to distinguish between these two cases.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class PublisherClosedByClientException extends EvitaInternalError {

	@Serial private static final long serialVersionUID = 7407542463586493972L;

	public PublisherClosedByClientException() {
		super("Publisher closed manually by client. This is information only, not an actual error.");
	}
}
