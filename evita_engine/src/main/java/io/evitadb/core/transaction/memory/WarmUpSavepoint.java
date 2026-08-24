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

package io.evitadb.core.transaction.memory;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.IdentityHashMap;
import java.util.Map.Entry;

/**
 * Per-entity savepoint for the WARM_UP (bulk indexing) write path — the non-transactional counterpart of
 * {@link TransactionalLayerMaintainer#openSavepoint()}.
 *
 * In an ALIVE-phase transaction every entity mutation writes to diff layers and the maintainer's savepoint reverts
 * exactly the layers that mutation touched. In WARM_UP there is no transaction and therefore no maintainer: writes go
 * straight to the delegate structures, so a mid-write failure used to leave the indexes half-mutated with no way back.
 * This class supplies the missing ownership: it is the context a warm-up write consults to learn that a savepoint is
 * open, and the place the inverse of every mutation made while it is open is recorded.
 *
 * The mechanism is the {@link UndoJournal} strategy the transactional savepoints already use, minus the maintainer:
 *
 * - {@link #recordFirstTouch(Snapshotable)} captures a participant's pre-mutation state ONCE, the first time it is
 *   write-touched inside the savepoint, and pushes a single journal entry that restores it absolutely. Every later
 *   touch of the same instance is an `O(1)`, allocation-free no-op — the identical lazy-capture-on-first-write-touch
 *   contract the maintainer implements in `recordSavepointSnapshotIfNeeded`.
 * - {@link #rollback()} replays the journal in strict reverse, then releases every captured memento.
 * - {@link #commit()} discards the journal entries without running them, then releases every captured memento.
 *
 * Because each recorded inverse is an ABSOLUTE restore of one participant's own state, participants are mutually
 * independent and the reverse-replay order between them carries no meaning — which is what lets first-touch mementos
 * and (from the later per-structure phases) per-operation inverses share a single journal.
 *
 * **Thread confinement.** The savepoint is held in a {@link ThreadLocal} rather than passed down the call chain: the
 * warm-up write path fans out through the whole index-mutation machinery, and plumbing a context parameter through it
 * is the structural scattering that made the historical hand-written undo actions unmaintainable. This is sound
 * because {@link io.evitadb.api.CatalogState#WARMING_UP} is contractually single-threaded — a catalog being bulk
 * loaded has exactly one writer. Concurrent warm-up writers are unsupported and are not newly defended here.
 *
 * **Cost when no savepoint is open** — the only state the hot path pays for — is a single {@link ThreadLocal} read
 * returning `null` on delegate write branches, the same order of cost warm-up already pays for its transaction
 * lookup.
 *
 * **Enablement.** The mechanism is gated by {@link #isEnabled()}, an internal flag defaulting to `false` (see
 * {@link #ENABLED_PROPERTY}). It stays internal until the warm-up throughput measurement decides whether per-entity
 * atomicity can be the public default.
 *
 * This type is deliberately NOT thread-safe, for the confinement reason above.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see Snapshotable
 * @see UndoJournal
 */
@NotThreadSafe
public final class WarmUpSavepoint {
	/**
	 * Name of the system property that switches per-entity atomicity of warm-up writes on. Absent or non-`true`, the
	 * warm-up write path behaves exactly as it did before this mechanism existed: a failed entity mutation is left
	 * partially applied.
	 */
	public static final String ENABLED_PROPERTY = "evitadb.warmUpAtomicity.enabled";
	/**
	 * The savepoint currently open on this thread, or `null` when none is. Read on every warm-up delegate write branch,
	 * so it is the single cost the mechanism imposes while switched off at a given moment.
	 */
	private static final ThreadLocal<WarmUpSavepoint> CURRENT = new ThreadLocal<>();
	/**
	 * Whether warm-up writes are bracketed by a savepoint at all. Initialized from {@link #ENABLED_PROPERTY} and
	 * otherwise changed only by {@link #setEnabled(boolean)}. Declared `volatile` so that a test flipping it (possibly
	 * from a different thread than the writer) is observed; a volatile read of a static boolean is negligible next to
	 * the work of the mutation it gates.
	 */
	private static volatile boolean enabled = Boolean.getBoolean(ENABLED_PROPERTY);

	/**
	 * The inverse operations recorded while this savepoint is open, replayed in strict reverse on {@link #rollback()}.
	 * A single journal is shared by all participants because every recorded inverse is an absolute restore of one
	 * participant's own state, so the interleaving between participants is irrelevant.
	 */
	private final UndoJournal undoJournal = new UndoJournal();
	/**
	 * The mementos captured on first write-touch, keyed by the participating layer INSTANCE. Identity is the right key
	 * here (and cheaper than {@link TransactionalLayerCreator#getId()}): a warm-up participant is a long-lived delegate
	 * structure reached by reference, never re-created per savepoint, and two distinct instances must never share an
	 * entry even if they happen to compare equal. Doubles as the first-touch dedup set.
	 */
	private final IdentityHashMap<Snapshotable<?>, Object> mementos = new IdentityHashMap<>(16);

	/**
	 * Private — a savepoint is always obtained through {@link #open()}, which is what registers it as the thread's
	 * current one.
	 */
	private WarmUpSavepoint() {
	}

	/**
	 * Returns whether warm-up writes should be bracketed by a savepoint. Callers that open savepoints must consult this
	 * before doing so; participants recording touches need not, because no savepoint is ever open while it is `false`.
	 *
	 * @return `true` when per-entity atomicity of warm-up writes is switched on
	 */
	public static boolean isEnabled() {
		return enabled;
	}

	/**
	 * Switches per-entity atomicity of warm-up writes on or off at runtime. Intended for tests, which need both
	 * behaviours in one JVM; production code configures the flag through {@link #ENABLED_PROPERTY} instead. A test that
	 * flips it must restore the previous value, since the flag is process-wide.
	 *
	 * @param newEnabled `true` to bracket warm-up root entity mutations with a savepoint
	 */
	public static void setEnabled(boolean newEnabled) {
		enabled = newEnabled;
	}

	/**
	 * Returns the savepoint currently open on this thread, or `null` when none is. This is the hook every warm-up
	 * mutator calls before touching its state; a `null` result means the mutation is not bracketed and nothing has to
	 * be recorded.
	 *
	 * @return the open savepoint, or `null` when the current mutation is not bracketed
	 */
	@Nullable
	public static WarmUpSavepoint getIfOpen() {
		return CURRENT.get();
	}

	/**
	 * Opens a savepoint on the current thread. While it is open, every participant write-touched for the first time has
	 * its pre-mutation state captured, so {@link #rollback()} can revert exactly the changes made since this call.
	 *
	 * Only one savepoint may be open per thread; nesting is rejected, mirroring
	 * {@link TransactionalLayerMaintainer#openSavepoint()}. A single active savepoint is sufficient because a savepoint
	 * brackets exactly one root entity mutation (including the cross-entity mutations it cascades into), and warm-up
	 * processes entity mutations one at a time.
	 *
	 * The caller must pair this with exactly one {@link #commit()} or {@link #rollback()}, in a `finally` block, or the
	 * savepoint leaks into the next mutation on this thread and its `open()` fails.
	 *
	 * @return the opened savepoint
	 * @throws GenericEvitaInternalError when a savepoint is already open on this thread
	 */
	@Nonnull
	public static WarmUpSavepoint open() {
		Assert.isPremiseValid(
			CURRENT.get() == null,
			() -> new GenericEvitaInternalError(
				"A warm-up savepoint is already open on this thread - nested savepoints are not supported!"
			)
		);
		final WarmUpSavepoint savepoint = new WarmUpSavepoint();
		CURRENT.set(savepoint);
		return savepoint;
	}

	/**
	 * Records the pre-mutation state of a participant on its FIRST write-touch inside this savepoint, and pushes the
	 * inverse that restores it. Must be called BEFORE the mutation is applied — for a participant whose own
	 * {@link Snapshotable} implementation is journal-backed (such as `DataStoreChanges`), the
	 * {@link Snapshotable#snapshot()} this triggers is also what activates that participant's own journal, so a touch
	 * recorded after the fact would capture a post-mutation state and lose the mutation's own inverse.
	 *
	 * Every subsequent touch of the same instance is a single identity-map lookup with no allocation: the memento
	 * already captured is an absolute pre-savepoint pre-image, so re-capturing would only overwrite it with a
	 * mid-savepoint state.
	 *
	 * @param layer the participant about to be mutated
	 */
	public void recordFirstTouch(@Nonnull Snapshotable<?> layer) {
		if (this.mementos.get(layer) != null) {
			// already captured within this savepoint - the pre-savepoint pre-image is the one that must survive
			return;
		}
		// the participant declares its own memento type; this savepoint only shuttles the opaque value back to it
		@SuppressWarnings("unchecked") final Snapshotable<Object> snapshotable = (Snapshotable<Object>) layer;
		final Object memento = snapshotable.snapshot();
		this.mementos.put(layer, memento);
		this.undoJournal.push(() -> snapshotable.restore(memento));
	}

	/**
	 * Reverts every change made while this savepoint was open: the recorded inverses are replayed in strict reverse
	 * order, then every captured memento is released so participants drop the scratch state they kept for it. The
	 * warm-up write path continues afterwards as if the bracketed entity mutation had never run.
	 *
	 * A failure here means the state could not be rewound and is therefore untrustworthy; the caller is responsible for
	 * making sure such state can never be flushed (see the poison backstop in `LocalMutationExecutorCollector`).
	 *
	 * @throws GenericEvitaInternalError when this savepoint is not the one currently open on this thread
	 */
	public void rollback() {
		// detach FIRST so the restore operations below - which run through the participants' ordinary mutators - do not
		// re-record into the savepoint being closed (mirrors TransactionalLayerMaintainer#rollbackSavepoint)
		detach();
		this.undoJournal.rollbackTo(0);
		releaseMementos();
	}

	/**
	 * Accepts this savepoint: the changes made while it was open stay, so the recorded inverses are discarded without
	 * being run and every captured memento is released. No participant state is modified.
	 *
	 * @throws GenericEvitaInternalError when this savepoint is not the one currently open on this thread
	 */
	public void commit() {
		// detach FIRST so a participant's releaseMemento - which may drain state through its own mutators - cannot
		// re-record into the savepoint being closed (mirrors TransactionalLayerMaintainer#commitSavepoint)
		detach();
		this.undoJournal.releaseFrom(0);
		releaseMementos();
	}

	/**
	 * Verifies this savepoint is the one currently open on this thread and unbinds it. Unbinding happens before any
	 * restore / release work runs, so that work cannot re-record into a savepoint that is already closing, and so the
	 * thread is left clean even when that work throws.
	 *
	 * @throws GenericEvitaInternalError when this savepoint is not the one currently open on this thread
	 */
	private void detach() {
		Assert.isPremiseValid(
			CURRENT.get() == this,
			() -> new GenericEvitaInternalError(
				"The closed warm-up savepoint is not the one currently open on this thread!"
			)
		);
		CURRENT.remove();
	}

	/**
	 * Hands every participant its closed savepoint's memento back (see {@link Snapshotable#releaseMemento(Object)}) so
	 * it can drop the per-savepoint scratch state - typically its own undo journal - it kept to support a restore, and
	 * empties the bookkeeping. Called on both outcomes, exactly as the maintainer does for a transactional savepoint.
	 */
	private void releaseMementos() {
		for (final Entry<Snapshotable<?>, Object> entry : this.mementos.entrySet()) {
			// the value was produced by this very participant's snapshot(), so it is its own memento type
			@SuppressWarnings("unchecked")
			final Snapshotable<Object> snapshotable = (Snapshotable<Object>) entry.getKey();
			snapshotable.releaseMemento(entry.getValue());
		}
		this.mementos.clear();
	}

}
