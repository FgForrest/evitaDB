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
 * folder decoupling exists to prevent (#649).
 *
 * Ownership transfer removes the window instead of narrowing it. Both the registering step and the completion
 * hook call {@link #takeClaim()}, which is a single atomic `getAndSet(null)`, so exactly one of them can win:
 *
 * - **the step wins** — it holds the claim across the whole registration and releases it in a `finally`, so no
 *   concurrent operation can take the name while the binding is being resolved,
 * - **the hook wins** — the task was cancelled or failed before the step got there, the name is freed at once,
 *   and the step finds nothing and refuses to register. A cancelled restore that registers its catalog anyway
 *   would be a lie told to the client either way.
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
	 * The claim itself, cleared by whichever party takes ownership of releasing it.
	 */
	private final AtomicReference<CatalogFolderReservation> claim = new AtomicReference<>();

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
		this.claim.set(reservation);
		this.folderId = reservation.folderId();
		return reservation.folderId();
	}

	/**
	 * Takes ownership of the claim, so that the caller — and only the caller — is responsible for releasing it.
	 *
	 * @return the claim, or `null` when it was never taken or somebody else already owns it
	 */
	@Nullable
	CatalogFolderReservation takeClaim() {
		return this.claim.getAndSet(null);
	}

}
