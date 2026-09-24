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

package io.evitadb.externalApi.grpc.exception;

import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Raised when a server-streaming RPC has been waiting for the client to consume already-sent data for
 * longer than the configured stall timeout, while the connection itself is still alive.
 *
 * This is the "the client stopped reading but never hung up" case — a paused SSH/`kubectl port-forward`
 * tunnel, a suspended browser tab, a consumer blocked on its own downstream. The transport never
 * cancels, so nothing else would ever end the call: without this exception the producing worker thread
 * would park indefinitely and the RPC would hang forever from both sides.
 *
 * It is a client/network condition rather than a server fault, so it is logged at WARN and translated
 * to `DEADLINE_EXCEEDED` by
 * {@link io.evitadb.externalApi.grpc.services.interceptors.GlobalExceptionHandlerInterceptor} — a
 * status the client can act on, unlike the bare `UNKNOWN` that an exhausted allocator used to produce.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class StalledGrpcStreamException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = -3255905625734017402L;

	public StalledGrpcStreamException(@Nonnull String methodName, long stallTimeoutMillis) {
		super(
			"Client of `" + methodName + "` has not consumed any data for " + stallTimeoutMillis +
				" ms while the stream stayed open; abandoning the response stream.",
			"The client stopped consuming the response stream and the transfer timed out."
		);
	}

}
