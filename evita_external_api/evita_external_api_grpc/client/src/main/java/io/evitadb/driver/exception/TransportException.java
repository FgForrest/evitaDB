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

package io.evitadb.driver.exception;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Signals that a client-to-server call failed at the **transport** level — the underlying gRPC
 * connection was closed, cancelled, became unavailable, or the call deadline elapsed — rather than
 * the server rejecting the request with a business error. This is distinct from
 * {@link EvitaClientServerCallException} (an unexpected server-side error surfaced over a healthy
 * connection): here the connection itself did not deliver the outcome.
 *
 * When this exception is raised for a session call, the originating client session is marked
 * inactive locally and no further RPC is issued for it; the server-side session killer reaps the
 * now-orphaned session on inactivity.
 *
 * **Outcome ambiguity (at-most-once vs. at-least-once).** Because the failure happened in transit,
 * the outcome of the call is **indeterminate** for the client. A mutation that triggered this
 * exception MAY still have been applied on the server — or may complete shortly after the client
 * observed the failure, since the server-side invocation is not interrupted. Callers that require
 * exactly-once semantics must reconcile against server state before retrying; a blind retry can
 * duplicate an already-applied change.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class TransportException extends EvitaClientServerCallException {
	@Serial private static final long serialVersionUID = -6829977889216969455L;

	public TransportException(@Nonnull String publicMessage, @Nonnull Throwable cause) {
		super(publicMessage, cause);
	}

}
