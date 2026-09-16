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

package io.evitadb.externalApi.grpc.services.interceptors;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.Status;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Minimal {@link ServerCall} stub shared by the interceptor tests in this package.
 *
 * It exists as one type rather than one per test class because the two properties the interceptor reads from a call -
 * its {@link MethodDescriptor} and its readiness - are read by different tests for different reasons, and two private
 * copies of the stub would drift apart exactly where a decorator defect hides: {@link ServerCall#isReady()} is not
 * abstract, so a stub that forgets to override it reports every transport as writable.
 *
 * @param <ReqT>  the request message type
 * @param <RespT> the response message type
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
class MockServerCall<ReqT, RespT> extends ServerCall<ReqT, RespT> {

	private final MethodDescriptor<ReqT, RespT> methodDescriptor;
	private final boolean ready;

	/**
	 * Creates a stub for the given method whose transport reports itself as writable.
	 *
	 * @param methodDescriptor the descriptor the interceptor is to observe
	 */
	MockServerCall(@Nonnull MethodDescriptor<ReqT, RespT> methodDescriptor) {
		this(methodDescriptor, true);
	}

	/**
	 * Creates a stub for the given method with an explicit transport readiness.
	 *
	 * @param methodDescriptor the descriptor the interceptor is to observe
	 * @param ready            what the underlying transport reports through {@link #isReady()}
	 */
	MockServerCall(@Nonnull MethodDescriptor<ReqT, RespT> methodDescriptor, boolean ready) {
		this.methodDescriptor = methodDescriptor;
		this.ready = ready;
	}

	@Override
	public void request(int numMessages) {
	}

	@Override
	public void sendHeaders(@Nullable Metadata headers) {
	}

	@Override
	public void sendMessage(RespT message) {
	}

	@Override
	public boolean isReady() {
		return this.ready;
	}

	@Override
	public void close(Status status, Metadata trailers) {
	}

	@Override
	public boolean isCancelled() {
		return false;
	}

	@Nonnull
	@Override
	public MethodDescriptor<ReqT, RespT> getMethodDescriptor() {
		return this.methodDescriptor;
	}
}
