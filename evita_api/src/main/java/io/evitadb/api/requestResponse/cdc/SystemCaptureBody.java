/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.cdc;

import io.evitadb.api.requestResponse.mutation.EngineMutation;

/**
 * Marker for everything that can ride on the system CDC stream as the body of a
 * {@link ChangeSystemCapture}. Either an {@link EngineMutation} (durable, WAL-replicated,
 * `ENGINE` area) or a {@link HostSystemEvent} (host-local, non-replicable,
 * `HOST` area).
 *
 * The interface is deliberately empty — it exists purely as a typing fence so the
 * `body` slot of a system capture can hold either kind without falling back to a
 * raw `Object` type. Sits below the broader {@link ChangeCaptureBody} marker that
 * unifies catalog-stream and system-stream bodies on {@link ChangeCapture#body()}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public sealed interface SystemCaptureBody extends ChangeCaptureBody
	permits EngineMutation, HostSystemEvent {
}
