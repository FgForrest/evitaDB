/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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


import io.grpc.stub.AbstractStub;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Objects;

/**
 * This proxy class is used to access gRPC stubs of particular type with a specific timeout. If the timeout is the same
 * as the last one used, then the last stub is returned. Otherwise, a new stub with extended timeout is created.
 *
 * It is expected that the stubs with same timeouts will be reused, so the optimization for reusing the last used stub
 * should be beneficial even if the stubs are quite cheap.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
class StubTimeoutProxy<T extends AbstractStub<T>> {
	/**
	 * The gRPC stub instance that is used to create new stubs with extended timeouts.
	 */
	private final T stub;
	/**
	 * The last used gRPC stub with its associated timeout configuration.
	 */
	private StubTimeout<T> lastUsedStub;

	/**
	 * Retrieves a gRPC stub with the specified timeout. If the timeout is different from the last used
	 * timeout, a new stub with the extended timeout is created. Otherwise, the previously used stub
	 * is returned.
	 *
	 * @param timeout The timeout configuration to be applied to the gRPC stub.
	 * @return A gRPC stub with the specified timeout configuration.
	 */
	public T get(@Nonnull Timeout timeout) {
		if (this.lastUsedStub == null || !Objects.equals(this.lastUsedStub.timeout(), timeout)) {
			final T stubWithExtendedTimeout = this.stub.withDeadlineAfter(timeout.timeout(), timeout.timeoutUnit());
			this.lastUsedStub = new StubTimeout<>(stubWithExtendedTimeout, timeout);
			return stubWithExtendedTimeout;
		} else {
			return this.lastUsedStub.stub();
		}
	}

	/**
	 * A record that holds a gRPC stub and its associated timeout configuration.
	 *
	 * @param <T> The type of the gRPC stub.
	 * @param stub The gRPC stub instance.
	 * @param timeout The timeout configuration to be applied to the gRPC stub.
	 */
	private record StubTimeout<T>(
		@Nonnull T stub,
		@Nonnull Timeout timeout
	) { }

}
