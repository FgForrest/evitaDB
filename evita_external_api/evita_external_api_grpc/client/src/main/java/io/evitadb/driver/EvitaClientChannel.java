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

package io.evitadb.driver;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;

import javax.annotation.Nonnull;

/**
 * Names the three gRPC channels the driver opens, so that the differences between them are carried by the
 * *type system* rather than by a comment.
 *
 * The driver builds three {@link GrpcClientBuilder} instances that differ in ways nothing in the stub API can
 * see: whether a {@link com.linecorp.armeria.client.retry.RetryingClient} decorator is installed, and which
 * {@link com.linecorp.armeria.client.ClientFactory} - and therefore which connection and which event loop
 * thread - the stubs land on. Both differences are load-bearing, and getting either wrong is silent:
 *
 * - Building a **streaming** stub from the {@link Unary} channel reintroduces issue #1388. The retry decorator
 *   freezes the call's response-timeout budget when the call starts, so the per-message re-arm that long-lived
 *   streams depend on can no longer move it and the stream dies on a deadline it appears to be beating.
 * - Building a **capture** stub from the {@link Streaming} channel reintroduces issue #1387. Capture traffic
 *   returns to the connection that carries ordinary request/response calls, so one stalled capture callback
 *   takes every call on that connection down with it.
 *
 * Neither shows up in a functional test: request correctness is unaffected, only *which thread* and *which
 * deadline*. They surface in production, under load, as the two issues above. Passing three same-typed
 * builders around made both mistakes compile; these wrappers make them not compile.
 *
 * The wrappers are deliberately thin - they add no behaviour, only identity. See
 * {@link EvitaClient#createGrpcClientBuilder} for how each one is configured.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public sealed interface EvitaClientChannel {

	/**
	 * Returns the wrapped builder. Prefer {@link #stub(Class)} - reaching for the raw builder discards exactly
	 * the type information this interface exists to carry.
	 *
	 * @return the wrapped gRPC client builder
	 */
	@Nonnull
	GrpcClientBuilder builder();

	/**
	 * Builds a stub bound to this channel.
	 *
	 * @param stubType the generated gRPC stub class to instantiate
	 * @param <T>      type of the stub
	 * @return a stub issuing its calls on this channel
	 */
	@Nonnull
	default <T> T stub(@Nonnull Class<T> stubType) {
		return builder().build(stubType);
	}

	/**
	 * The ordinary request/response channel. Carries the retry decorator and therefore backs **unary stubs
	 * only** - `*FutureStub` and `*BlockingStub`.
	 *
	 * @param builder the retry-decorated builder bound to the main client factory
	 */
	record Unary(@Nonnull GrpcClientBuilder builder) implements EvitaClientChannel {
	}

	/**
	 * The streaming channel. Shares the main connection with {@link Unary} and differs from it in exactly one
	 * respect: no retry decorator, so a stream's response deadline stays re-armable (issue #1388).
	 *
	 * @param builder the undecorated builder bound to the main client factory
	 */
	record Streaming(@Nonnull GrpcClientBuilder builder) implements EvitaClientChannel {
	}

	/**
	 * The change data capture channel. Undecorated like {@link Streaming}, and additionally bound to
	 * a *separate* client factory - its own connection and its own single-threaded event loop group - so that
	 * a stalled capture callback degrades captures only (issue #1387).
	 *
	 * @param builder the undecorated builder bound to the dedicated CDC client factory
	 */
	record Cdc(@Nonnull GrpcClientBuilder builder) implements EvitaClientChannel {
	}

}
