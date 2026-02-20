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

package io.evitadb.core.executor;

import io.evitadb.api.requestResponse.progress.UnrejectableTask;

/**
 * Compound interface for callable tasks that are both cancellable and unrejectable. Combines
 * {@link CancellableCallable} (a {@link java.util.concurrent.Callable} supporting cancellation via thread
 * interruption) with {@link UnrejectableTask} (a marker that bypasses the executor's bounded queue rejection).
 * System-critical operations that produce a result (e.g. catalog loading, backup) should implement this interface
 * to ensure they are always accepted by the executor regardless of queue pressure.
 *
 * @param <V> the type of the result produced by this task
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface UnrejectableCallable<V> extends CancellableCallable<V>, UnrejectableTask {
}
