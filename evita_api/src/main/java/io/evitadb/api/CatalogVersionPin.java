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

package io.evitadb.api;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

/**
 * A lease held for as long as a consumer needs a particular catalog version to remain readable - a backup copying it,
 * or a session reading through it. While a lease is open, the retention floor cannot rise past the version it holds.
 *
 * It is a lease rather than a pair of `pin`/`unpin` calls because acquisition and release are separated by the whole
 * of the work being protected, and the two have failed to pair up in practice: a release routed through a mutable
 * catalog reference resolves that reference **again** at release time, so a rename or a replace in between lands it on
 * the instance that took over the name. The damage runs both ways - the version this consumer really held stays pinned
 * on an instance nothing will ever reconcile, and the decrement lands on a pin some *other* consumer holds, taking
 * their protection away. Capturing the release action at acquisition closes that structurally: whatever happens to the
 * name afterwards, {@link #close()} can only reach the instance that granted the pin.
 *
 * The release action passed to {@link #pinnedOn(long, LongConsumer)} must therefore be bound to the instance the pin
 * was taken on - `theCatalog::catalogVersionReleased`, never a lookup that resolves a catalog by name.
 *
 * A pin that could not be taken at all - the catalog was gone or in transition, which session registration tolerates
 * on purpose because the session is doomed anyway - is represented by {@link #NONE} rather than by remembering the
 * omission elsewhere. Closing it does nothing, which is what makes "we never pinned" and "we already released" the
 * same, harmless case.
 *
 * Use it with try-with-resources wherever the protected work is scoped; where it is not - a backup task pins in its
 * constructor and releases in its tear-down - hold it in a field and close it there.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class CatalogVersionPin implements AutoCloseable {
	/**
	 * A pin that holds nothing. Closing it is a no-op, so a consumer whose acquisition could not be honoured needs no
	 * `null` check and no separate record of the fact.
	 */
	public static final CatalogVersionPin NONE = new CatalogVersionPin(-1L, null);

	/**
	 * The catalog version held against reclamation, or `-1` for {@link #NONE}.
	 */
	private final long catalogVersion;
	/**
	 * Gives the pin back. Bound to the instance that granted it at acquisition time and never resolved again, which
	 * is the whole point of this class. `null` for {@link #NONE}.
	 */
	@Nullable private final LongConsumer releaseAction;
	/**
	 * Guards against a second release: decrementing a pin twice does not merely no-op, it takes away the protection
	 * of whichever consumer still holds that version.
	 */
	private final AtomicBoolean released = new AtomicBoolean();

	private CatalogVersionPin(long catalogVersion, @Nullable LongConsumer releaseAction) {
		this.catalogVersion = catalogVersion;
		this.releaseAction = releaseAction;
	}

	/**
	 * Creates a lease over a version that has **already been pinned** on a particular instance.
	 *
	 * @param catalogVersion the version that must remain readable
	 * @param releaseAction  gives that pin back; must be bound to the instance the pin was taken on, so that a rename
	 *                       or a replace cannot redirect the release to a different one
	 * @return the lease, to be closed exactly once when the version is no longer needed
	 */
	@Nonnull
	public static CatalogVersionPin pinnedOn(long catalogVersion, @Nonnull LongConsumer releaseAction) {
		return new CatalogVersionPin(catalogVersion, releaseAction);
	}

	/**
	 * Returns the catalog version this lease holds, or an empty value when it holds nothing.
	 *
	 * @return the held catalog version, or empty for {@link #NONE}
	 */
	@Nonnull
	public OptionalLong getCatalogVersion() {
		return this.releaseAction == null ? OptionalLong.empty() : OptionalLong.of(this.catalogVersion);
	}

	/**
	 * Returns TRUE when this lease has already been given back.
	 *
	 * @return TRUE when the pin was released
	 */
	public boolean isReleased() {
		return this.released.get();
	}

	@Override
	public void close() {
		// the flag is only touched when there is something to give back, so `NONE` stays reusable
		if (this.releaseAction != null && this.released.compareAndSet(false, true)) {
			this.releaseAction.accept(this.catalogVersion);
		}
	}
}
