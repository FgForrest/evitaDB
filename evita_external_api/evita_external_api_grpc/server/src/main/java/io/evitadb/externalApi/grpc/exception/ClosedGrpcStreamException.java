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

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Raised when an attempt is made to push onto a gRPC stream whose internal
 * `cancelled` flag has already been set — typically because the client sent an
 * HTTP/2 RST_STREAM CANCEL (or the request deadline elapsed) before our
 * server-side callback got a chance to run.
 *
 * gRPC itself signals this condition with a plain {@link IllegalStateException}
 * identified only by its message string. This exception wraps that raw
 * exception so the rest of the codebase — notably
 * {@link io.evitadb.externalApi.grpc.services.interceptors.GlobalExceptionHandlerInterceptor} —
 * can recognise client-side stream termination by type instead of by string
 * matching on the message.
 *
 * A `ClosedGrpcStreamException` is a benign race, not a server error. It
 * should be logged at DEBUG level at most and never translated to a non-`CANCELLED`
 * gRPC status.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class ClosedGrpcStreamException extends RuntimeException {
	@Serial private static final long serialVersionUID = 6471127128349186129L;

	public ClosedGrpcStreamException(@Nonnull Throwable cause) {
		super(cause.getMessage(), cause);
	}
}
