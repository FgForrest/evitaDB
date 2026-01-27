/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.session;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Record that provides access both to the latch and the close lambda.
 *
 * @param closeLambda  the close lambda that is executed when the session is closed
 * @param closedFuture the future that is completed when the session is closed
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
record ClosingSequence(
	@Nonnull Runnable closeLambda,
	@Nonnull CompletableFuture<Void> closedFuture
) {

	ClosingSequence(@Nonnull Runnable closeLambda) {
		this(closeLambda, new CompletableFuture<>());
	}

	/**
	 * Waits for the session to finish closing within the specified timeout period.
	 *
	 * @param timeout  the maximum time to wait for the session to finish, in the given time unit
	 * @param timeUnit the time unit of the timeout parameter
	 * @return {@code true} if the session finished closing within the timeout period,
	 * {@code false} if the timeout elapsed
	 */
	boolean awaitFinish(int timeout, @Nonnull TimeUnit timeUnit) {
		return FutureAwaiter.awaitWithTimeout(this.closedFuture, timeout, timeUnit);
	}

}
