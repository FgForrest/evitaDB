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

import io.evitadb.api.CatalogState;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;

/**
 * Host-local, non-replicable, transient event about the live view of catalogs on the
 * current evitaDB host. Rides on the system CDC stream as the body of a
 * {@link ChangeSystemCapture} when the subscriber has explicitly opted into the
 * {@link SystemCaptureArea#HOST} area via {@link ChangeSystemCaptureCriteria}.
 *
 * **Semantics shared by every variant:**
 * - **Host-local.** Reflects this host's current view of the catalog; not synchronized
 *   across cluster members.
 * - **Transient.** Not persisted to the WAL. Cannot be re-read from history.
 * - **Strictly ordered with mutations.** Emitted through the same engine-state lock
 *   as engine mutations, so a host event that follows a mutation lands strictly after
 *   that mutation in the stream.
 * - **Live-tail only.** Late subscribers that pass `sinceVersion` will receive
 *   historical engine mutations but **no** historical host events.
 * - **Does not advance the engine version counter.** {@link #currentEngineVersion()} is
 *   a snapshot for correlation, never an increment.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public sealed interface HostSystemEvent extends SystemCaptureBody
	permits HostSystemEvent.CatalogInstalledIntoLiveView,
	HostSystemEvent.CatalogRemovedFromLiveView,
	HostSystemEvent.CatalogSchemaUpdated {

	/**
	 * Returns the name of the catalog this event refers to.
	 *
	 * @return the catalog name; never `null`
	 */
	@Nonnull
	String catalogName();

	/**
	 * Returns a snapshot of the engine version at the moment this event was emitted.
	 * The value is provided **for correlation only** — it is the version of the most
	 * recent engine mutation observed by this host at the time the host event fired.
	 * Receiving a host event does **not** advance the engine version counter.
	 *
	 * @return the snapshot engine version
	 */
	long currentEngineVersion();

	/**
	 * Fires when a catalog's local reference settles into a non-transient state on this
	 * host (e.g. `ALIVE`, `WARMING_UP`, `INACTIVE`, `OUT_OF_DATE`, `CORRUPTED`,
	 * `MISSING`). Subscribers can treat this as the authoritative "catalog X is now
	 * usable / now in state Y on this host" signal regardless of the underlying
	 * mutation that drove the transition (boot load, post-upgrade retry,
	 * post-activation load, deactivation, mark-missing, etc.).
	 *
	 * **Properties** (in addition to the common {@link HostSystemEvent} contract):
	 * - Host-local — the same catalog may be in a different state on a different host.
	 * - Transient — not persisted; only delivered to live subscribers.
	 * - Strictly ordered with mutations — lands after the mutation that triggered
	 *   the settlement.
	 * - Live-tail only — late subscribers do not get historical occurrences.
	 *
	 * The compact constructor defensively rejects transitional states ({@code BEING_*},
	 * {@code GOING_ALIVE}) — emitting this event for a transient state would violate
	 * its "settled" contract and is treated as a programming error.
	 *
	 * @param catalogName          the name of the catalog whose reference settled
	 * @param observedState        the non-transient state the catalog settled into;
	 *                             must satisfy `!observedState.isTransitional()`
	 * @param currentEngineVersion snapshot of the engine version at emit time
	 */
	record CatalogInstalledIntoLiveView(
		@Nonnull String catalogName,
		@Nonnull CatalogState observedState,
		long currentEngineVersion
	) implements HostSystemEvent {

		public CatalogInstalledIntoLiveView {
			Assert.isPremiseValid(
				catalogName != null && !catalogName.isEmpty(),
				"Catalog name must be provided!"
			);
			Assert.isPremiseValid(
				observedState != null,
				"Observed state must be provided!"
			);
			Assert.isPremiseValid(
				!observedState.isTransitional(),
				() -> "CatalogInstalledIntoLiveView requires a non-transient state, " +
					"got: " + observedState
			);
		}

	}

	/**
	 * Fires when a catalog is fully removed from the live view on this host — i.e.
	 * after the `BEING_DELETED` transition completes and the entry is gone from the
	 * engine state map. The catalog is no longer addressable on this host.
	 *
	 * **Properties** (in addition to the common {@link HostSystemEvent} contract):
	 * - Host-local — removal is a host-side fact about the live view, independent
	 *   of whether the on-disk storage has been wiped.
	 * - Transient — not persisted; only delivered to live subscribers.
	 * - Strictly ordered with mutations — lands after the mutation that triggered
	 *   the removal.
	 * - Live-tail only — late subscribers do not get historical occurrences.
	 *
	 * @param catalogName          the name of the catalog that was removed
	 * @param currentEngineVersion snapshot of the engine version at emit time
	 */
	record CatalogRemovedFromLiveView(
		@Nonnull String catalogName,
		long currentEngineVersion
	) implements HostSystemEvent {

		public CatalogRemovedFromLiveView {
			Assert.isPremiseValid(
				catalogName != null && !catalogName.isEmpty(),
				"Catalog name must be provided!"
			);
		}

	}

	/**
	 * Fires when a catalog's schema version increases on this host — coalesced exactly once
	 * per session close (WARMING_UP) or per transaction commit (ALIVE) regardless of how
	 * many `ModifyCatalogSchemaMutation`s were applied. Replaces the per-mutation refresh
	 * storm previously observed by GraphQL / REST managers.
	 *
	 * **Properties** (in addition to the common {@link HostSystemEvent} contract):
	 * - Host-local — different hosts may observe different schema versions at the same
	 *   wall-clock moment; this event is about THIS host's view.
	 * - Transient — not persisted; only delivered to live subscribers.
	 * - Strictly ordered with mutations — lands after the last engine mutation that drove
	 *   the schema-version bump.
	 * - Live-tail only — late subscribers do not get historical occurrences.
	 *
	 * The compact constructor defensively rejects empty `catalogName` and negative
	 * `newSchemaVersion`.
	 *
	 * @param catalogName          the name of the catalog whose schema version increased
	 * @param newSchemaVersion     the new (current) catalog schema version on this host;
	 *                             must be `>= 0`
	 * @param currentEngineVersion snapshot of the engine version at emit time
	 */
	record CatalogSchemaUpdated(
		@Nonnull String catalogName,
		int newSchemaVersion,
		long currentEngineVersion
	) implements HostSystemEvent {

		public CatalogSchemaUpdated {
			Assert.isPremiseValid(
				catalogName != null && !catalogName.isEmpty(),
				"Catalog name must be provided!"
			);
			Assert.isPremiseValid(
				newSchemaVersion >= 0,
				() -> "New schema version must be non-negative, got: " + newSchemaVersion
			);
		}

	}

}
