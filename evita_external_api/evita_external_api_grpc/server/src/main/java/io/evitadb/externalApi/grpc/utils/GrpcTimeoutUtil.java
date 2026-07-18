/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.externalApi.grpc.utils;

import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.server.ServiceRequestContext;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.time.Duration;

/**
 * Utility methods for managing Armeria request timeouts on long-lived / streaming gRPC calls.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class GrpcTimeoutUtil {

	/**
	 * Re-arms the Armeria request timeout to `requestTimeoutMillis` from now, unless the timeout is
	 * disabled (`requestTimeoutMillis <= 0`). A disabled request timeout is the normal state for a
	 * gRPC call without a client-supplied deadline (e.g. `useClientTimeoutHeader(true)` with no
	 * `grpc-timeout` header, or an open-ended CDC subscription) — Armeria treats `0` as "disabled"
	 * everywhere else, but {@link TimeoutMode#SET_FROM_NOW} uniquely rejects a zero/negative duration
	 * with an {@link IllegalArgumentException}, so re-arming must be skipped in that case.
	 *
	 * @param serviceContext       Armeria service context whose request timeout should be re-armed
	 * @param requestTimeoutMillis the timeout to re-arm to, in milliseconds (`<= 0` means disabled)
	 */
	public static void reArmRequestTimeoutIfEnabled(@Nonnull ServiceRequestContext serviceContext, long requestTimeoutMillis) {
		if (requestTimeoutMillis > 0) {
			serviceContext.setRequestTimeout(TimeoutMode.SET_FROM_NOW, Duration.ofMillis(requestTimeoutMillis));
		}
	}

}
