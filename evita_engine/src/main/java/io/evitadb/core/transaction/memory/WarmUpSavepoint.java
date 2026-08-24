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

import io.evitadb.core.transaction.Transaction;
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
 * - {@link #claimFirstTouch(Object)} is the same dedup for a participant that is NOT a {@link Snapshotable} — an
 *   index data structure whose delegate branch writes raw state in place. It only reports whether this is the first
 *   touch; the participant then captures its own pre-image and {@link #push(Runnable)}es the inverse itself.
 * - {@link #push(Runnable)} records one inverse without any dedup, for a participant whose pre-image must be captured
 *   per operation rather than once.
 * - {@link #writeLayer(TransactionalLayerCreator, boolean)} is the packaged form of the first bullet for a structure
 *   that is its OWN diff layer — the B+ tree nodes. It resolves the layer to write into exactly as before and folds
 *   the first-touch record into the branch where there is none, so a mutator reaches the savepoint without naming it.
 * - {@link #rollback()} replays the journal in strict reverse, then releases every captured memento.
 * - {@link #commit()} discards the journal entries without running them, then releases every captured memento.
 *
 * Because each recorded inverse is an ABSOLUTE restore of the state its own operation touched, participants are
 * mutually independent and the reverse-replay order between them carries no meaning — which is what lets first-touch
 * mementos and per-operation inverses share a single journal. Within one participant the ordering DOES carry meaning
 * and is what makes per-operation inverses correct: replayed newest-first, the earliest-pushed inverse for a given
 * slot runs last and wins, so it is the pre-savepoint value that survives however many times the slot was rewritten.
 *
 * **Which of the two granularities a participant picks** follows from the cost of its pre-image, not from its shape:
 *
 * - **First touch**, when the participant's ENTIRE mutable state has an `O(1)` pre-image — a scalar, or a wrapper
 *   whose writes replace an array reference rather than mutating the array in place. One capture then covers every
 *   write in the savepoint, and the journal stays bounded by participants rather than by the number of writes.
 * - **Per operation**, when it does not. The collection wrappers mutate a large delegate `HashMap` / `HashSet` /
 *   `ArrayList` in place, so their whole-state pre-image is a deep copy of the accumulated base structure — the
 *   `O(N²)`-per-transaction rollback cliff that the journal strategy exists to avoid (see {@link UndoJournal}). They
 *   capture the one slot each operation overwrites instead.
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
	 * Placeholder stored in {@link #firstTouches} for a participant that captured its own pre-image (see
	 * {@link #claimFirstTouch(Object)}) and therefore has no memento for this savepoint to hand back. It keeps both
	 * kinds of first touch in a single map — one allocation per savepoint instead of two — while still letting
	 * {@link #releaseMementos()} tell the entries apart.
	 */
	private static final Object SELF_CAPTURED = new Object();

	/**
	 * The inverse operations recorded while this savepoint is open, replayed in strict reverse on {@link #rollback()}.
	 * A single journal is shared by all participants because every recorded inverse is an absolute restore of the state
	 * its own operation touched, so the interleaving between participants is irrelevant.
	 */
	private final UndoJournal undoJournal = new UndoJournal();
	/**
	 * The participants write-touched inside this savepoint, keyed by INSTANCE. Identity is the right key here (and
	 * cheaper than {@link TransactionalLayerCreator#getId()}): a warm-up participant is a long-lived delegate structure
	 * reached by reference, never re-created per savepoint, and two distinct instances must never share an entry even
	 * if they happen to compare equal.
	 *
	 * The value is the memento a {@link Snapshotable} participant produced, or {@link #SELF_CAPTURED} for a participant
	 * that captured its own pre-image. Either way the entry is what makes the first-touch check `O(1)` and
	 * allocation-free from the second touch onwards.
	 */
	private final IdentityHashMap<Object, Object> firstTouches = new IdentityHashMap<>(16);

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
	 * Resolves the diff layer a self-layered structure's mutator must write into, and — when there is none and a
	 * savepoint is open — records the structure's first touch so a rollback can restore it. It is the single normalized
	 * form of the idiom the B+ tree node mutators repeat around a hundred times:
	 *
	 * ```
	 * final SELF layer = this.transactionalLayer ? Transaction.getOrCreateTransactionalMemoryLayer(this) : null;
	 * if (layer == null) { ...mutate own fields in place... } else { ...mutate the layer... }
	 * ```
	 *
	 * The `baseNode` flag it takes is NOT a transaction test: it says whether this instance is a BASE node, i.e. one
	 * permitted to own a diff layer. A diff layer of a self-layered structure is another instance of the same class
	 * carrying `false`, and it mutates itself in place rather than layering over yet another copy — which is why the
	 * layer-null branch serves two different populations.
	 *
	 * Behaviour is therefore:
	 *
	 * - **In a transaction over a base node** — unchanged: the diff layer is returned and the caller mutates it. The
	 *   maintainer's own savepoint captures that layer, so nothing is recorded here.
	 * - **On the layer-null branch** (no transaction at all, or a diff-layer instance mutating itself) — the caller is
	 *   about to write its own fields in place, so the structure's pre-mutation state is captured through
	 *   {@link #recordFirstTouch(Snapshotable)} when a savepoint is open. The nodes already implement
	 *   {@link Snapshotable} with node-size-bounded mementos for the transactional savepoints, so this reuses the exact
	 *   machinery rather than adding a second one; the intersection bound is what makes "every structure reached
	 *   through here can be snapshotted" a compile-time fact instead of a runtime check.
	 *
	 * The second population cannot actually occur while a savepoint is open — a diff-layer instance exists only inside
	 * a transaction, and a savepoint is only ever opened in WARM_UP where there is none — but recording its touch would
	 * be correct anyway (the memento is an absolute pre-image of its own fields), so the branch needs no special case.
	 *
	 * Must be called BEFORE the mutation, which is what the assignment-then-branch shape above guarantees. Outside a
	 * savepoint the layer-null branch costs one {@link ThreadLocal} read returning `null`; the in-transaction branch
	 * costs exactly what it did before.
	 *
	 * @param node     the structure about to be mutated, which is its own diff layer type
	 * @param baseNode whether this instance is permitted to own a diff layer (see above)
	 * @param <T>      the diff layer type — the node type itself
	 * @param <C>      the node type, constrained to be both its own layer creator and snapshottable
	 * @return the diff layer to write into, or `null` when the caller must mutate its own fields in place
	 */
	@Nullable
	public static <T, C extends TransactionalLayerCreator<T> & Snapshotable<?>> T writeLayer(
		@Nonnull C node,
		boolean baseNode
	) {
		if (baseNode) {
			final T layer = Transaction.getOrCreateTransactionalMemoryLayer(node);
			if (layer != null) {
				return layer;
			}
		}
		final WarmUpSavepoint savepoint = CURRENT.get();
		if (savepoint != null) {
			savepoint.recordFirstTouch(node);
		}
		return null;
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
		if (this.firstTouches.get(layer) != null) {
			// already captured within this savepoint - the pre-savepoint pre-image is the one that must survive
			return;
		}
		// the participant declares its own memento type; this savepoint only shuttles the opaque value back to it
		@SuppressWarnings("unchecked") final Snapshotable<Object> snapshotable = (Snapshotable<Object>) layer;
		final Object memento = snapshotable.snapshot();
		this.firstTouches.put(layer, memento);
		this.undoJournal.push(() -> snapshotable.restore(memento));
	}

	/**
	 * Reports whether a participant that captures its OWN pre-image is being write-touched for the first time inside
	 * this savepoint, and records the touch so every later one answers `false`. It is the {@link Snapshotable}-free
	 * half of {@link #recordFirstTouch(Snapshotable)}, for the index data structures whose delegate branch writes raw
	 * state in place and that therefore have no diff layer to snapshot.
	 *
	 * Use it only where the participant's ENTIRE mutable state has an `O(1)` pre-image (see the type JavaDoc); the
	 * caller must, on a `true` answer, capture that pre-image and {@link #push(Runnable)} the inverse restoring it
	 * BEFORE applying its mutation:
	 *
	 * ```
	 * final WarmUpSavepoint savepoint = WarmUpSavepoint.getIfOpen();
	 * if (savepoint != null && savepoint.claimFirstTouch(this)) {
	 *     final int[] preImage = this.delegate;
	 *     savepoint.push(() -> this.delegate = preImage);
	 * }
	 * ```
	 *
	 * Splitting the answer from the capture is what keeps a repeat touch allocation-free: a variant taking the inverse
	 * as an argument would have to build the capturing lambda on every call just to discard it.
	 *
	 * @param participant the structure about to be mutated
	 * @return `true` when this is its first write-touch inside this savepoint and its pre-image still has to be
	 *         captured, `false` when a pre-savepoint pre-image was already recorded for it
	 */
	public boolean claimFirstTouch(@Nonnull Object participant) {
		return this.firstTouches.putIfAbsent(participant, SELF_CAPTURED) == null;
	}

	/**
	 * Records one inverse operation to be replayed on {@link #rollback()}, with no first-touch dedup — the granularity
	 * a participant uses when its whole-state pre-image is too expensive to capture and it captures the individual slot
	 * each operation overwrites instead (see the type JavaDoc).
	 *
	 * Must be called BEFORE the forward mutation is applied, with the pre-mutation value already captured, and the
	 * inverse must be an ABSOLUTE restore of that slot rather than a semantic counter-operation: under the journal's
	 * reverse replay the earliest-pushed inverse for a slot runs last and wins, which is what makes a slot rewritten
	 * several times inside one savepoint end up at its pre-savepoint value.
	 *
	 * The inverse must also be TOTAL — it may never throw for a benign reason. A failing rollback leaves state that
	 * cannot be trusted and costs the whole bulk load (see the poison backstop in `LocalMutationExecutorCollector`).
	 *
	 * @param inverse the operation restoring the state the forward mutation is about to overwrite
	 */
	public void push(@Nonnull Runnable inverse) {
		this.undoJournal.push(inverse);
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
	 * Hands every {@link Snapshotable} participant its closed savepoint's memento back (see
	 * {@link Snapshotable#releaseMemento(Object)}) so it can drop the per-savepoint scratch state - typically its own
	 * undo journal - it kept to support a restore, and empties the bookkeeping. Participants that captured their own
	 * pre-image hold no such scratch state and are skipped. Called on both outcomes, exactly as the maintainer does for
	 * a transactional savepoint.
	 */
	private void releaseMementos() {
		for (final Entry<Object, Object> entry : this.firstTouches.entrySet()) {
			final Object memento = entry.getValue();
			if (memento != SELF_CAPTURED) {
				// the value was produced by this very participant's snapshot(), so it is its own memento type
				@SuppressWarnings("unchecked")
				final Snapshotable<Object> snapshotable = (Snapshotable<Object>) entry.getKey();
				snapshotable.releaseMemento(memento);
			}
		}
		this.firstTouches.clear();
	}

}
