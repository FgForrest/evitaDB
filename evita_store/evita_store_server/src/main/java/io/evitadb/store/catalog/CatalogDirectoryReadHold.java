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

package io.evitadb.store.catalog;

import javax.annotation.Nonnull;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A lease held for as long as a consumer is reading the catalog folder **by listing it** rather than by following
 * a bootstrap record. No file is removed from the folder while any lease is open.
 *
 * It is a lease rather than a pair of `acquire`/`release` calls because acquisition and release are separated by the
 * whole of a backup, and the two have repeatedly failed to pair up: a task that pinned in its constructor and was then
 * never scheduled leaked its pin for the catalog's lifetime, and a release routed through a mutable catalog reference
 * can reach a different instance than the acquisition did, drifting the counter with nothing to reconcile it. Both
 * failure modes are closed structurally here - the lease captures the exact maintainer it was taken on, and a second
 * (or late) `close()` does nothing.
 *
 * Use it with try-with-resources.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class CatalogDirectoryReadHold implements AutoCloseable {
	/**
	 * The maintainer the hold was taken on. Captured rather than resolved again at release time.
	 */
	private final ObsoleteFileMaintainer maintainer;
	/**
	 * Notified once the hold is given back, so that the work it turned away is picked up again rather than waiting for
	 * the next unrelated event to drive it.
	 */
	private final Runnable onRelease;
	/**
	 * Guards against a second release. `false` until the lease has been closed.
	 */
	private final AtomicBoolean released = new AtomicBoolean();

	CatalogDirectoryReadHold(@Nonnull ObsoleteFileMaintainer maintainer, @Nonnull Runnable onRelease) {
		this.maintainer = maintainer;
		this.onRelease = onRelease;
	}

	@Override
	public void close() {
		if (this.released.compareAndSet(false, true)) {
			this.maintainer.releaseDirectoryReadHold();
			this.onRelease.run();
		}
	}
}
