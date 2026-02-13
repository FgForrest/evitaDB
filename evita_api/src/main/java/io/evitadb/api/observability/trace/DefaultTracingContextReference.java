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
 * No-op implementation of {@link TracingContextReference} used when no active tracing system is
 * configured. This class uses `Void` as the context type and returns `null` from
 * {@link #getContext()}, indicating the absence of any real tracing context.
 *
 * **Design Purpose:**
 * Serves as a null object pattern for {@link DefaultTracingContext}, allowing code to safely call
 * context reference methods without null checks or special-case handling. This reference is
 * stateless and reusable.
 *
 * **Usage Context:**
 * - Returned by {@link DefaultTracingContext#getCurrentContext()}
 * - Reused as a singleton instance in {@link DefaultTracingContext#EMPTY_CONTEXT_HOLDER}
 * - Safe to pass to `executeWithinBlockWithParentContext` methods (which ignore it in no-op mode)
 *
 * **Thread-Safety:**
 * This class is stateless and thread-safe.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public class DefaultTracingContextReference implements TracingContextReference<Void> {

	/**
	 * Returns `Void.class` to indicate that no real context type exists.
	 *
	 * @return `Void.class`
	 */
	@Nonnull
	@Override
	public Class<Void> getType() {
		return Void.class;
	}

	/**
	 * Returns `null` since no actual tracing context is maintained. The `@Nonnull` annotation is
	 * inherited from the interface but cannot be satisfied for `Void` types.
	 *
	 * @return `null` (always)
	 */
	@Nonnull
	@Override
	public Void getContext() {
		return null;
	}
}
