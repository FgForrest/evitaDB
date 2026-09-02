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

package io.evitadb.core.management;

import io.evitadb.core.engine.CatalogFolderContext;
import io.evitadb.core.engine.CatalogFolderReservation;
import io.evitadb.spi.store.engine.model.CatalogFolderId;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the exclusive folder claim a catalog restore takes, and hands it over exactly once.
 *
 * A restore is the only materialising path whose claim must outlive the call that took it: the folder is
 * allocated in the unpacking step, but the mutation that binds a catalog to it runs in a *later* task step and
 * resolves the folder by catalog **name**. For that lookup to be correct, nothing else may hold the name in
 * between — so the claim spans two task steps and the two must not both release it.
 *
 * That is what this type exists for. `SequentialTask#cancel()` is bookkeeping only: it completes the task's
 * future without stopping a step that is already running, so the completion hook can fire *while* the registering
 * step is mid-flight. Left to a plain "release on completion" hook, a cancellation landing in that window frees
 * the name while the registration is still resolving it, and a second restore of the same name can take the claim
 * and have the first restore bind its catalog to the second one's half-written folder — the exact corruption the
 * folder decoupling exists to prevent.
 *
 * Ownership transfer removes the window instead of narrowing it. Both the registering step and the completion
 * hook call {@link #takeClaim()}, which is a single atomic `getAndSet`, so exactly one of them can win. Three
 * orderings are reachable, and the claim is released exactly once in each:
 *
 * - **the step wins** — it holds the claim across the whole registration and releases it in a `finally`, so no
 *   concurrent operation can take the name while the binding is being resolved,
 * - **the hook wins, with a claim to take** — the task was cancelled or failed after the folder was allocated,
 *   the name is freed at once, and the step finds nothing and refuses to register. A cancelled restore that
 *   registers its catalog anyway would be a lie told to the client either way,
 * - **the hook wins, with nothing to take** — the task went terminal while {@link #allocate} was still inside
 *   `allocateFolderFor`. The hook fires once and never comes back, so a claim published after it would be one
 *   nobody ever releases, and refusing a claimed name is permanent: create, restore and duplicate would all
 *   fail on that name until the process restarts. {@link #takeClaim()} therefore parks a sentinel rather than
 *   leaving the holder empty, and the late publication loses its compare-and-set and releases on the spot.
 *
 * The folder token is deliberately kept in a **separate** field from the takeable claim. {@link #allocate} is
 * call-once by contract but answers idempotently, and that answer must survive the handover: overloading one
 * field with both "which folder" and "who owns the release" would turn a second read into a failure the moment
 * the claim changed hands.
 *
 * Not thread-safe to *allocate* concurrently — allocation happens once, on the restoring task's own thread.
 * Taking the claim is safe from any thread, which is the whole point.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class RestoreFolderClaim {

	/**
	 * Parked in {@link #claim} by every {@link #takeClaim()}, including one that found nothing to hand over.
	 * Telling "already handed over" apart from "not allocated yet" is what lets an allocation still in flight
	 * learn, at the moment it publishes, that nobody is coming back for its claim.
	 */
	private static final Object HANDED_OVER = new Object();

	/**
	 * The claim itself: `null` until the folder is allocated, then the reservation, then {@link #HANDED_OVER}
	 * once some party owns the release. One field rather than a claim beside a terminal flag, because the
	 * publication and the handover race each other and only a single atomic can order them.
	 */
	private final AtomicReference<Object> claim = new AtomicReference<>();

	/**
	 * The allocated folder token. Written once and **never** cleared, so `allocate` keeps answering after the
	 * claim has changed hands.
	 */
	private volatile CatalogFolderId folderId;

	/**
	 * Allocates the folder this restore writes into, taking an exclusive claim on the catalog name.
	 *
	 * Asked twice, this answers with the folder it already took — allocating again would both strand the first
	 * directory and refuse against this restore's own claim.
	 *
	 * @param folderContext context the folder is allocated through
	 * @param catalogName   name of the catalog being restored
	 * @return token identifying the folder the catalog is to be restored into
	 */
	@Nonnull
	CatalogFolderId allocate(@Nonnull CatalogFolderContext folderContext, @Nonnull String catalogName) {
		final CatalogFolderId alreadyAllocated = this.folderId;
		if (alreadyAllocated != null) {
			return alreadyAllocated;
		}
		final CatalogFolderReservation reservation = folderContext.allocateFolderFor(catalogName);
		// Compare-and-set rather than a plain publish, because the call above is the widest window in the whole
		// handover - it draws a generation and creates a directory - and a cancellation landing inside it fires
		// the completion hook against an empty holder. The hook fires once, so a claim published afterwards is
		// one nobody ever releases, and `allocateFolderFor` refuses a name that is still claimed: the name would
		// be un-materialisable for the lifetime of the process. Losing the swap means exactly that happened.
		if (!this.claim.compareAndSet(null, reservation)) {
			reservation.close();
		}
		// Answered either way, released or not. A cancellation frees the name while this restore goes on
		// unpacking, which is safe for a non-obvious reason worth writing down: cancelling transitions every
		// step's status away from QUEUED, and `SequentialTask#execute` runs a step only while it is QUEUED, so
		// the registering step is skipped entirely and nothing binds a catalog to this folder - and were it to
		// run anyway it would find no claim and refuse. The folder keeps its provisional marker and is reclaimed
		// at the next boot. What is lost is the unpacking work, because cancellation cannot interrupt a step
		// that is already running - a pre-existing limitation of task cancellation, not of this handover.
		this.folderId = reservation.folderId();
		return reservation.folderId();
	}

	/**
	 * Takes ownership of the claim, so that the caller — and only the caller — is responsible for releasing it.
	 *
	 * Marks the holder handed over even when there was nothing to hand over, so an allocation still in flight
	 * releases its own claim rather than publishing one this caller can no longer come back for.
	 *
	 * @return the claim, or `null` when it was never taken or somebody else already owns it
	 */
	@Nullable
	CatalogFolderReservation takeClaim() {
		final Object previouslyHeld = this.claim.getAndSet(HANDED_OVER);
		return previouslyHeld instanceof CatalogFolderReservation reservation ? reservation : null;
	}

}
