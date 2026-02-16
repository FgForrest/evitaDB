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

package io.evitadb.api.observability.trace;

import javax.annotation.Nonnull;

/**
 * No-op implementation of {@link TracingBlockReference} used when no active tracing system is
 * configured. This class serves as a null object pattern to avoid null checks in client code.
 *
 * **Design Purpose:**
 * When observability is disabled or no OpenTelemetry tracing context is available, this
 * implementation allows code to execute normally without incurring tracing overhead. All methods
 * are no-ops that do nothing but satisfy the interface contract.
 *
 * **Usage Context:**
 * - Returned by {@link DefaultTracingContext#createAndActivateBlock} methods
 * - Used as a fallback when {@link TracingContextProvider} finds no real tracing implementation
 * - Safe to use with try-with-resources — `close()` is a no-op
 *
 * **Thread-Safety:**
 * This class is stateless and thread-safe.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public class DefaultTracingBlockReference implements TracingBlockReference {

	/**
	 * No-op implementation that does nothing. The error is not recorded anywhere.
	 *
	 * @param error the exception to record (ignored in this implementation)
	 */
	@Override
	public void setError(@Nonnull Throwable error) {
		// noop
	}

	/**
	 * No-op implementation that does nothing. Safe to call multiple times.
	 */
	@Override
	public void close() {
		// noop
	}
}
