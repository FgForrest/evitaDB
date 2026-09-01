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
	 * Reads the call's configured request timeout, to be captured **once before** a streaming loop and
	 * then passed to every {@link #reArmRequestTimeoutIfEnabled(ServiceRequestContext, long)} inside it.
	 *
	 * Calling {@link ServiceRequestContext#requestTimeoutMillis()} from inside the loop instead is the
	 * trap this method exists to close, and it is not a theoretical one - six call sites had it. That
	 * getter does **not** return the configured budget, nor the time remaining: Armeria stores the
	 * timeout relative to the request's start, and {@link TimeoutMode#SET_FROM_NOW} writes
	 * `elapsed + newTimeout` into that field (`DefaultCancellationScheduler#setTimeoutNanosFromNow0`),
	 * which `requestTimeoutMillis()` returns verbatim. Feeding it back into the next re-arm therefore
	 * *ratchets*: a 2 s budget re-armed every 100 ms becomes 2.1 s, then 2.3 s, then 2.6 s, growing
	 * without bound. The deadline stops being a rolling stall window and becomes an ever-receding
	 * horizon that a genuinely stalled client never reaches. (The accessor that does mean "time left" is
	 * `remainingTimeoutNanos()`, which is *also* not what a re-arm wants.)
	 *
	 * Capturing before the loop is what makes the value the configured one - at that point no re-arm has
	 * happened yet, so the stored budget is still the request's own.
	 *
	 * @param serviceContext Armeria service context of the call whose budget should be captured
	 * @return the configured request timeout in milliseconds, or `<= 0` when the timeout is disabled
	 */
	public static long captureRequestTimeoutMillis(@Nonnull ServiceRequestContext serviceContext) {
		return serviceContext.requestTimeoutMillis();
	}

	/**
	 * Resolves the budget a **streaming** call should re-arm to, capturing it once before the first
	 * message in the same way - and for the same reason - as
	 * {@link #captureRequestTimeoutMillis(ServiceRequestContext)}.
	 *
	 * Whether a call has a deadline at all stays the caller's decision: a client that asked for none, or
	 * for `grpc-timeout: 0`, keeps none, and this returns `0` so the re-arm is skipped. What the deadline
	 * is re-armed *to*, however, is the deployment's streaming budget rather than the call's own
	 * `requestTimeout`. Those are different quantities and conflating them is a category error:
	 * `requestTimeout` bounds a whole request, which suits a unary call, whereas a stream needs a bound
	 * on *silence*. Re-arming a stream to the whole-request budget imposes a minimum viable link speed -
	 * at `fetchFile`'s 1 MB chunk and the shipped 2 s, roughly 4 Mbit/s sustained - on transfers that are
	 * otherwise progressing perfectly well.
	 *
	 * This substitution is safe precisely because a gRPC deadline is enforced by the **client**: Armeria's
	 * client maps `withDeadlineAfter` onto its own response timeout and grpc-java runs a deadline timer, so
	 * a client that asked for 500 ms still sees its call end at 500 ms. What lengthening the server's copy
	 * changes is only how long a *live but silent* peer can pin a server worker - the capacity trade
	 * recorded against `GrpcOutboundGate`.
	 *
	 * @param serviceContext              Armeria service context of the streaming call
	 * @param streamingRequestTimeoutMillis the deployment's streaming budget, from the API configuration
	 * @return the budget to pass to every subsequent
	 *         {@link #reArmRequestTimeoutIfEnabled(ServiceRequestContext, long)}, or `0` when this call
	 *         has no request timeout to re-arm
	 */
	public static long resolveStreamingBudgetMillis(
		@Nonnull ServiceRequestContext serviceContext,
		long streamingRequestTimeoutMillis
	) {
		return captureRequestTimeoutMillis(serviceContext) <= 0 ? 0L : streamingRequestTimeoutMillis;
	}

	/**
	 * Re-arms the Armeria request timeout to `requestTimeoutMillis` from now, unless the timeout is
	 * disabled (`requestTimeoutMillis <= 0`). Armeria treats `0` as "disabled" everywhere else, but
	 * {@link TimeoutMode#SET_FROM_NOW} uniquely rejects a zero/negative duration with an
	 * {@link IllegalArgumentException}, so re-arming must be skipped in that case.
	 *
	 * A disabled timeout is *not* the default here, and it is worth being precise because the opposite
	 * is easy to assume. With `useClientTimeoutHeader(true)`, `FramedGrpcService` overrides the context's
	 * timeout **only when the request actually carries a `grpc-timeout` header**; absent one it leaves
	 * the server's own `api.requestTimeoutInMillis` in force (1 s code default, 2 s in the shipped
	 * configuration). So a client that sets no deadline at all - a browser over gRPC-Web, most obviously -
	 * gets the *shortest* budget of anyone, which is exactly why the re-arm cannot be treated as an
	 * optimisation for well-behaved clients. A genuinely disabled timeout arises from an explicit
	 * `grpc-timeout: 0` or from configuration.
	 *
	 * The budget passed here must come from {@link #captureRequestTimeoutMillis(ServiceRequestContext)}
	 * called *before* the loop - never from `serviceContext.requestTimeoutMillis()` read inside it. See
	 * that method for why the difference is not cosmetic.
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
