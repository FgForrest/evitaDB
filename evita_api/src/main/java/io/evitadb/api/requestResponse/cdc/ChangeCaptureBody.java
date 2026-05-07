/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

import io.evitadb.api.requestResponse.mutation.Mutation;

import java.io.Serializable;

/**
 * Marker for everything that can ride on the CDC stream as the body of a
 * {@link ChangeCapture}. Either a {@link Mutation} (the catalog-stream body — including
 * the `EngineMutation` family on the system stream, since `EngineMutation` is also a
 * {@link Mutation}) or a {@link SystemCaptureBody} (the system-stream body — engine
 * mutations or {@link HostSystemEvent}s).
 *
 * The interface exists purely as a typing fence so the `body` slot of a generic
 * {@link ChangeCapture} can be expressed as something narrower than `Serializable`
 * without exposing implementation-specific subtypes. Sealed-of-sealed: both permitted
 * subtypes are themselves sealed, so the closed world below `ChangeCaptureBody` is a
 * union of `Mutation`'s permits and `SystemCaptureBody`'s permits.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public sealed interface ChangeCaptureBody extends Serializable
	permits Mutation, SystemCaptureBody {
}
