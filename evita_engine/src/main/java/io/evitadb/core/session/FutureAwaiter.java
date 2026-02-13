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

import io.evitadb.core.exception.SessionBusyException;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Utility class for awaiting completion of {@link CompletableFuture} instances with a timeout.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
final class FutureAwaiter {

	private FutureAwaiter() {
		// utility class
	}

	/**
	 * Waits for the specified future to complete within the given timeout period.
	 *
	 * @param future   the future to wait for, must not be null
	 * @param timeout  the maximum time to wait, in the given time unit
	 * @param timeUnit the time unit of the timeout parameter, must not be null
	 * @return {@code true} if the future completed within the timeout period, {@code false} if timeout elapsed
	 * @throws SessionBusyException if the thread is interrupted or an execution exception occurs
	 */
	static boolean awaitWithTimeout(
		@Nonnull CompletableFuture<Void> future,
		int timeout,
		@Nonnull TimeUnit timeUnit
	) {
		try {
			future.get(timeout, timeUnit);
			return true;
		} catch (TimeoutException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw SessionBusyException.INSTANCE;
		} catch (ExecutionException e) {
			throw SessionBusyException.INSTANCE;
		}
	}

}
