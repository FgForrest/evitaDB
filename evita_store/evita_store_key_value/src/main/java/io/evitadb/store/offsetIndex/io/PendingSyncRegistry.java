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

package io.evitadb.store.offsetIndex.io;

import javax.annotation.Nonnull;

/**
 * Collects the write handles whose bytes have reached the operating system page cache but have not yet been forced
 * to the physical device, so that something else can make them durable later.
 *
 * A {@link WriteOnlyHandle} constructed with a registry stops issuing its own `fsync` at the end of a write and calls
 * {@link #noteSyncPending(WriteOnlyHandle)} instead. The buffer flush still happens - only the device flush moves. Whoever
 * owns the registry is then responsible for calling {@link WriteOnlyHandle#forceDurable()} on everything noted here
 * **before** writing any record that points at those bytes.
 *
 * Handles register themselves rather than being enumerated by the checkpoint, which is what keeps the set from
 * drifting: a file cannot be written through a deferring handle without landing in the registry.
 *
 * Implementations must be safe to call from any thread - the read path forces a soft flush when a caller reads
 * a record that is still sitting in the write buffer, so this is reached from request threads and not only from
 * the thread that drives trunk incorporation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@FunctionalInterface
public interface PendingSyncRegistry {

	/**
	 * Records that the given handle has written bytes that are not yet on the physical device.
	 *
	 * Called after the buffer has been flushed, so registration always implies the bytes are at least in the page
	 * cache. Registering a handle that is already registered is a no-op.
	 *
	 * @param handle the handle whose target file needs forcing before anything may point at its newest records
	 */
	void noteSyncPending(@Nonnull WriteOnlyHandle handle);

}
