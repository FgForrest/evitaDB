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
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicLong;

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
 * - {@link #claimFirstTouch(WarmUpTouchStamped)} is the same dedup for a participant that is NOT a
 *   {@link Snapshotable} — an index data structure whose delegate branch writes raw state in place. It only reports
 *   whether this is the first touch; the participant then captures its own pre-image and {@link #push(Runnable)}es
 *   the inverse itself.
 * - {@link #push(Runnable)} records one inverse without any dedup, for a participant whose pre-image must be captured
 *   per operation rather than once.
 * - {@link #writeLayer(TransactionalLayerCreator, boolean)} is the packaged form of the first bullet for a structure
 *   that is its OWN diff layer — the B+ tree nodes. It resolves the layer to write into exactly as before and folds
 *   the first-touch record into the branch where there is none, so a mutator reaches the savepoint without naming it.
 * - {@link #rollback()} replays the journal in strict reverse, then releases every captured memento.
 * - {@link #commit()} discards the journal entries without running them, then releases every captured memento.
 *
 * **How a repeat touch is recognised.** Every participant carries its own {@link WarmUpTouchStamped} mark holding the
 * stamp of the savepoint that last captured it, so "have I already captured this instance" is one field compare
 * against {@link #stamp}. It used to be a lookup in a per-savepoint {@link IdentityHashMap}, which cost a hash and a
 * probe on each of the roughly sixty participants a single entity write-touches — 461 ms per 100k entities on the
 * bulk-ingest profile, for a question each participant can answer about itself for eight bytes. The typed signatures
 * are what makes the replacement total rather than partial: a participant with no mark cannot reach the dedup APIs at
 * all, where the map accepted any `Object` and would silently have admitted one.
 *
 * **Enforcement.** Journalling is a per-structure obligation, and warm-up has no maintainer to enforce it centrally,
 * so {@link #verifyRollbackSupported(TransactionalLayerCreator)} is the backstop: every structure that takes its
 * delegate branch while a savepoint is open must declare {@link TransactionalLayerCreator#supportsWarmUpRollback()},
 * or the mutation fails immediately rather than being silently left un-rewindable by a rollback that reports success.
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
 *   capture the one slot each operation overwrites instead. `TransactionalBitmap` belongs here too, and is the
 *   cautionary case: it first took a copy-on-write `clone()`, which LOOKS `O(1)` because it only copies pointers,
 *   while the copying it defers — one container per subsequent write, up to 8 KB each — showed up as 13.2 % of all
 *   allocation on the bulk-ingest profile. "Cheap to capture" is not the test; "cheap in total" is.
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
 * **The mechanism is unconditional.** Per-entity atomicity of warm-up writes has no switch, internal or public:
 * every warm-up root entity mutation is bracketed by a savepoint. The throughput measurement that gated this
 * decision put the mechanism's cost at about 2 % of bulk-ingest CPU on the 972k-article reference corpus, and the
 * consistency it buys — a failed entity mutation reverts completely instead of leaving half-indexed state — was
 * judged worth that price everywhere, with no configuration surface for trading it away.
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
	 * The savepoint currently open on this thread, or `null` when none is. Read on every warm-up delegate write branch,
	 * so it is the single cost the mechanism imposes on writes outside a root entity mutation bracket.
	 */
	private static final ThreadLocal<WarmUpSavepoint> CURRENT = new ThreadLocal<>();
	/**
	 * Capacity the two capture lists are allocated at, so a savepoint that captures many participants does not have to
	 * grow them midway through.
	 *
	 * The number is the measured count of participants one root entity mutation write-touches on the 972k-article
	 * reference corpus. It is an upper bound on the captures rather than the exact figure — a participant that
	 * captures its own pre-image through {@link #claimFirstTouch(WarmUpTouchStamped)} never enters these lists — and
	 * it is kept at the measured touch count deliberately, because over-allocating a couple of dozen reference slots
	 * once per entity is cheaper than the array copies growing them would cost.
	 */
	private static final int EXPECTED_CAPTURED_PARTICIPANTS = 64;
	/**
	 * Hands every savepoint the {@link #stamp} that identifies it to the participants carrying a
	 * {@link WarmUpTouchStamped} mark. Starts at 0 so the first stamp drawn is 1, leaving 0 free as the "never
	 * captured" default of every mark.
	 *
	 * Uniqueness has to be GLOBAL and the sequence must never reuse a value: a stale mark that happened to equal a
	 * later savepoint's stamp would make that savepoint SKIP the participant's capture and report a successful
	 * rollback over state it never rewound. That is why this is a process-wide {@link AtomicLong} rather than a
	 * per-catalog counter (two catalogs may warm up on different threads and their counters would collide) and why
	 * the stamp is a `long` rather than an `int` (a wrapped sequence would eventually hand out a value some mark
	 * still holds). Even the `long` sequence wraps after 2^64 draws, so the constructor additionally fails closed on
	 * the first wrapped value rather than letting it circulate. One CAS per savepoint — i.e. per root entity
	 * mutation — is noise next to the ~64 identity-map operations per entity the mark replaced.
	 */
	private static final AtomicLong STAMP_SEQUENCE = new AtomicLong();

	/**
	 * Identifies this savepoint to the participants that carry their own first-touch mark. A participant whose
	 * {@link WarmUpTouchStamped#getWarmUpTouchStamp()} equals this value has already had its pre-image captured
	 * inside this savepoint; see {@link #isCaptured(WarmUpTouchStamped)} and {@link #STAMP_SEQUENCE}.
	 */
	private final long stamp = STAMP_SEQUENCE.incrementAndGet();
	/**
	 * The inverse operations recorded while this savepoint is open, replayed in strict reverse on {@link #rollback()}.
	 * A single journal is shared by all participants because every recorded inverse is an absolute restore of the state
	 * its own operation touched, so the interleaving between participants is irrelevant.
	 *
	 * **The journal must only ever be replayed COMPLETELY, in strict reverse, to position zero — a partial
	 * `rollbackTo(mark)` on it would corrupt state.** Two mechanisms depend on the totality: WITHIN one participant
	 * the entries are ordered (the earliest-pushed inverse for a slot must run last to win, and a bulk entry's
	 * companions — e.g. the leaf re-insertion that re-attaches a drained bitmap which that bitmap's own earlier entry
	 * then refills — must all run for either to be an absolute restore), and the per-slot/whole-node exclusivity
	 * deliberately journals NOTHING for a structure once its whole-state memento is captured, so any replay boundary
	 * other than zero would leave the suppressed writes applied while reporting success. {@link #rollback()} honours
	 * this by always draining to zero; a future feature needing partial rollback marks must not reuse this journal.
	 */
	private final UndoJournal undoJournal = new UndoJournal();
	/**
	 * The {@link Snapshotable} participants whose memento this savepoint still has to hand back, in capture order, with
	 * {@link #mementos} holding each one's memento at the same index.
	 *
	 * Two parallel lists rather than one map of participant to memento, and the reason is measured. This bookkeeping
	 * used to be read back by iterating the identity map that answered the first-touch question; an
	 * {@link IdentityHashMap}'s iterator walks its whole TABLE rather than its entries and allocates an `Entry` per
	 * step, so releasing a handful of participants out of a table sized for many meant walking every empty slot and
	 * allocating along the way — 0.87 % of the ON pass's allocation was `IdentityHashMap$EntryIterator$Entry`, with
	 * the walk itself around 0.6 % of ingest CPU. Indexed lists make {@link #releaseMementos()} `O(captures)` and
	 * allocation-free; the map itself has since gone too, replaced by the {@link WarmUpTouchStamped} mark.
	 *
	 * Participants that captured their own pre-image through {@link #claimFirstTouch(WarmUpTouchStamped)} hold no
	 * memento and are deliberately absent from both lists.
	 */
	private final ArrayList<Snapshotable<Object>> mementoOwners =
		new ArrayList<>(EXPECTED_CAPTURED_PARTICIPANTS);
	/**
	 * The memento captured for the {@link #mementoOwners} entry at the same index. A `null` element is legal — it is
	 * whatever that participant's {@link Snapshotable#snapshot()} returned — which is precisely why a capture is
	 * tracked by its PRESENCE in these lists rather than by a non-null memento value anywhere.
	 */
	private final ArrayList<Object> mementos = new ArrayList<>(EXPECTED_CAPTURED_PARTICIPANTS);

	/**
	 * Private — a savepoint is always obtained through {@link #open()}, which is what registers it as the thread's
	 * current one.
	 *
	 * The constructor is where stamp-sequence exhaustion FAILS CLOSED. {@link AtomicLong#incrementAndGet()} wraps
	 * modulo 2^64, and the wrapped sequence's first value is 0 — the "never captured" default every mark starts at, so
	 * a savepoint carrying it would treat every untouched participant as already captured and report a successful
	 * rollback over state it never rewound; each later wrapped value is equally poisonous, being one some mark may
	 * still hold. Refusing the 0 stamp stops the process at the FIRST reused value, which makes the uniqueness
	 * invariant total rather than merely astronomical (2^64 savepoints in one JVM lifetime is out of reach — this
	 * check is contract hygiene, not a reachable path, and its cost is one compare per root entity mutation).
	 */
	private WarmUpSavepoint() {
		Assert.isPremiseValid(
			this.stamp != 0,
			() -> new GenericEvitaInternalError(
				"The warm-up savepoint stamp sequence is exhausted - a wrapped stamp would silently skip first-touch " +
					"captures, so no further savepoint may be opened in this process."
			)
		);
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
	 * **A savepoint is open only where there is no transaction, and this method having a single call site is what
	 * makes that true.** The bracket in `LocalMutationExecutorCollector` opens one on the branch where the
	 * transactional maintainer is absent, and `Transaction#getTransactionalLayerMaintainer()` is `null` exactly when
	 * `Transaction#isTransactionAvailable()` is `false`. A great deal rests on this: every index mutator whose
	 * journalling sits behind an `if (!isTransactionAvailable())` gate is correct only because that gate is
	 * unconditionally taken while a savepoint is open, and the delegate-branch backstop in
	 * {@link #verifyRollbackSupported(TransactionalLayerCreator)} guards only the no-transaction path for the same
	 * reason. A second call site would invalidate all of it at once, so `WarmUpRollbackConformanceTest` asserts there
	 * is none.
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
	 *   about to write its own fields in place, so the structure's pre-mutation state is captured once per savepoint
	 *   when one is open. The nodes already implement {@link Snapshotable} with node-size-bounded mementos for the
	 *   transactional savepoints, so this reuses the exact machinery rather than adding a second one; the intersection
	 *   bound is what makes "every structure reached through here can be snapshotted" a compile-time fact instead of a
	 *   runtime check.
	 *
	 * The first-touch dedup is answered from the structure's own {@link WarmUpTouchStamped} mark — the third arm of the
	 * intersection bound, and what lets the tree nodes, by far the most numerous population the mechanism touches,
	 * settle the question with a field compare.
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
	public static <T, C extends TransactionalLayerCreator<T> & Snapshotable<?> & WarmUpTouchStamped> T writeLayer(
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
	 * Resolves the diff layer a self-layered structure's mutator must write into exactly as
	 * {@link #writeLayer(TransactionalLayerCreator, boolean)} does, but records NOTHING — the caller journals its own
	 * writes at PER-OPERATION granularity (see the type JavaDoc for which granularity a participant picks) and is
	 * responsible for pushing an inverse before each one.
	 *
	 * It exists for the leaf nodes of `TransactionalBucketBPlusTree`, whose whole-node memento duplicates both columns
	 * plus the overflow clone for a write that typically touches one or two of the leaf's slots — 551 ms per 100k
	 * entities and around a fifth of all allocation on the savepoint-bracketed bulk-ingest profile. A caller of this
	 * method must therefore honour the exclusivity the granularities have per structure per savepoint: it may push a
	 * per-operation
	 * inverse only while {@link #isCaptured(WarmUpTouchStamped)} answers `false` for it, because a whole-node memento
	 * replays LAST for its structure and would overwrite everything the finer inverses had refined.
	 *
	 * The obligation this method hands to the caller is not enforced here; what IS still enforced is
	 * {@link #verifyRollbackSupported(TransactionalLayerCreator)}, which
	 * {@link Transaction#getOrCreateTransactionalMemoryLayer(TransactionalLayerCreator)} runs on the branch that hands
	 * back `null` exactly as it does for {@link #writeLayer(TransactionalLayerCreator, boolean)}.
	 *
	 * @param node     the structure about to be mutated, which is its own diff layer type
	 * @param baseNode whether this instance is permitted to own a diff layer (see
	 *                 {@link #writeLayer(TransactionalLayerCreator, boolean)})
	 * @param <T>      the diff layer type — the node type itself
	 * @return the diff layer to write into, or `null` when the caller must mutate its own fields in place
	 */
	@Nullable
	public static <T> T perOperationWriteLayer(@Nonnull TransactionalLayerCreator<T> node, boolean baseNode) {
		return baseNode ? Transaction.getOrCreateTransactionalMemoryLayer(node) : null;
	}

	/**
	 * Reports whether this savepoint has already captured the WHOLE-STATE pre-image of a participant carrying its own
	 * first-touch mark — the read-only half of {@link #recordFirstTouch(Snapshotable)}, for a participant that ALSO
	 * journals some of its writes per operation.
	 *
	 * A `true` answer means the participant's memento is already in this savepoint's journal, and the participant must
	 * then journal NOTHING further for the write it is about to make: the memento is an absolute restore of its whole
	 * state pushed earlier than any per-operation inverse could be, so under strict reverse replay it runs LAST for
	 * that participant and wins. A finer inverse pushed on top of it would at best be redundant and at worst be
	 * replayed against a structurally different state. This exclusivity is what lets one participant mix the two
	 * granularities across the operations of a single savepoint.
	 *
	 * @param participant the structure about to be mutated
	 * @return `true` when this savepoint already holds a whole-state memento of `participant`
	 */
	public boolean isCaptured(@Nonnull WarmUpTouchStamped participant) {
		return participant.getWarmUpTouchStamp() == this.stamp;
	}

	/**
	 * Records the pre-mutation state of a participant on its FIRST write-touch inside this savepoint, and pushes the
	 * inverse that restores it. Must be called BEFORE the mutation is applied — for a participant whose own
	 * {@link Snapshotable} implementation is journal-backed (such as `DataStoreChanges`), the
	 * {@link Snapshotable#snapshot()} this triggers is also what activates that participant's own journal, so a touch
	 * recorded after the fact would capture a post-mutation state and lose the mutation's own inverse.
	 *
	 * Every subsequent touch of the same instance is a single field compare against the participant's own
	 * {@link WarmUpTouchStamped} mark, with no allocation: the memento already captured is an absolute pre-savepoint
	 * pre-image, so re-capturing would only overwrite it with a mid-savepoint state.
	 *
	 * The mark is set BEFORE {@link Snapshotable#snapshot()} runs. That is safe because this method is contractually
	 * called before the mutation: a throwing `snapshot()` leaves the participant marked-but-uncaptured over a write
	 * that never happened, so nothing applied can hide from a rollback.
	 *
	 * @param layer the participant about to be mutated
	 * @param <C>   the participant type, both snapshottable and carrying its own mark
	 */
	public <C extends Snapshotable<?> & WarmUpTouchStamped> void recordFirstTouch(@Nonnull C layer) {
		if (layer.getWarmUpTouchStamp() == this.stamp) {
			// already captured within this savepoint - the pre-savepoint pre-image is the one that must survive.
			// Answered from the mark rather than from a captured memento, so a participant whose snapshot()
			// legitimately returns null is still captured exactly once, as the contract above promises
			return;
		}
		layer.setWarmUpTouchStamp(this.stamp);
		// the participant declares its own memento type; this savepoint only shuttles the opaque value back to it
		@SuppressWarnings("unchecked") final Snapshotable<Object> snapshotable = (Snapshotable<Object>) layer;
		final Object memento = snapshotable.snapshot();
		this.mementoOwners.add(snapshotable);
		this.mementos.add(memento);
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
	 * **The `Snapshotable` rejection stays a runtime check even though the parameter is now typed.** The signature
	 * proves the participant carries a mark; it cannot prove the participant is not ALSO a {@link Snapshotable}, and a
	 * class that implements both shapes would compile against either API. Reaching a `Snapshotable` through the
	 * self-capture route would skip its own mechanism's activation — a journal-backed `snapshot()` is what ARMS that
	 * participant's journal — and leave {@link #releaseMementos()} with nothing to hand back, so its per-savepoint
	 * scratch state would never drain.
	 *
	 * @param participant the structure about to be mutated, which must NOT be a {@link Snapshotable} — the two APIs
	 *                    are mutually exclusive per participant
	 * @return `true` when this is its first write-touch inside this savepoint and its pre-image still has to be
	 *         captured, `false` when a pre-savepoint pre-image was already recorded for it
	 * @throws GenericEvitaInternalError when the participant is a {@link Snapshotable} and therefore belongs on
	 *                                   {@link #recordFirstTouch(Snapshotable)}
	 */
	public boolean claimFirstTouch(@Nonnull WarmUpTouchStamped participant) {
		if (participant.getWarmUpTouchStamp() != this.stamp) {
			// checked only on the first touch - the repeat touches this method exists to make cheap stay allocation-
			// and branch-free, and a participant cannot change its type between two touches
			if (participant instanceof Snapshotable<?>) {
				throw new GenericEvitaInternalError(
					"Participant " + participant.getClass().getName() + " implements Snapshotable but claimed a " +
						"self-captured first touch in a warm-up savepoint - a Snapshotable participant must be " +
						"recorded through recordFirstTouch(Snapshotable) instead, so its own memento mechanism is " +
						"the one that captures and releases the pre-image.",
					"A Snapshotable participant used the self-capture warm-up savepoint API."
				);
			}
			participant.setWarmUpTouchStamp(this.stamp);
			return true;
		}
		return false;
	}

	/**
	 * Verifies that a creator about to take its DELEGATE branch inside an open warm-up savepoint declares that branch
	 * rewindable (see {@link TransactionalLayerCreator#supportsWarmUpRollback()}), and fails loudly when it does not.
	 * This is the runtime backstop of the mechanism, called from
	 * {@link Transaction#getOrCreateTransactionalMemoryLayer(TransactionalLayerCreator)} at the moment it is about to
	 * hand back `null`.
	 *
	 * It exists because warm-up has no maintainer, and therefore no choke point equivalent to
	 * {@link TransactionalLayerMaintainer#recordSavepointSnapshotIfNeeded(long, Object)} — the defensive throw this one
	 * mirrors. Without it, a structure whose delegate branch was never ported to journal its writes would fail
	 * silently: the rollback would report success while leaving that structure's changes applied, which is strictly
	 * worse than the pre-mechanism behaviour because the failure is then invisible. A structure that legitimately has
	 * nothing to rewind says so by returning `true` (see the contract on the declaring method).
	 *
	 * **Cost.** Two short-circuits, in widening order of expense: the thread's savepoint (one {@link ThreadLocal}
	 * read, `null` exactly when no root entity mutation is in flight), then the declaration (an interface call on a
	 * handful of small final implementations). Both sat on the measured bulk-ingest write path — the mechanism's
	 * ~2 % ingest-CPU price already includes them — and outside a bracket the check is the single predicted-null
	 * {@link ThreadLocal} read every delegate write branch pays anyway.
	 *
	 * The check is live whenever a savepoint is open — which, the bracket being unconditional, is during every
	 * warm-up root entity mutation, and equally in the per-structure rollback unit tests that open a savepoint
	 * directly. The backstop's dedicated coverage is `WarmUpRollbackBackstopTest` (behaviour) and
	 * `WarmUpRollbackConformanceTest` (the declarations it reads).
	 *
	 * @param layerCreator the creator whose delegate branch is about to be taken
	 * @throws GenericEvitaInternalError when a savepoint is open and the creator does not declare rollback support
	 */
	public static void verifyRollbackSupported(@Nonnull TransactionalLayerCreator<?> layerCreator) {
		if (CURRENT.get() != null && !layerCreator.supportsWarmUpRollback()) {
			throw new GenericEvitaInternalError(
				"Structure " + layerCreator.getClass().getName() + " is modified inside a warm-up savepoint but does " +
					"not declare support for warm-up rollback - the changes it writes in place could not be reverted " +
					"on a per-entity rollback. Journal its delegate-branch writes into the savepoint and override " +
					"TransactionalLayerCreator#supportsWarmUpRollback().",
				"A structure modified inside a warm-up savepoint does not support warm-up rollback."
			);
		}
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
	 * Returns the journal's current position, which a participant compares against the position it saw right after its
	 * own {@link #push(Runnable)} to learn whether ANOTHER inverse has since landed on top of its own.
	 *
	 * It exists for the one participant whose journal entry is not a point-in-time capture: a bulk
	 * {@link io.evitadb.index.bitmap.TransactionalBitmap} write pushes ONE inverse covering every membership the write
	 * changes, but it pushes it before the first of those changes and keeps filling the entry as the walk proceeds.
	 * That is sound only while the entry stays at the top of the journal — reverse replay orders entries, not the
	 * captures inside one of them, so a capture made after a foreign entry was pushed would be replayed on the wrong
	 * side of it. Seeing the position move, the participant seals its entry and opens a fresh one, which restores the
	 * property that every entry's captures are contiguous in journal order.
	 *
	 * Nothing else needs this: every other participant captures its pre-image and pushes the matching inverse without
	 * yielding control in between, so no entry can slip between the two.
	 *
	 * @return the number of inverses recorded so far
	 */
	public int journalMark() {
		return this.undoJournal.mark();
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
		// indexed loop over the capture lists, which are already in capture order - see their field JavaDoc for what
		// iterating a participant map here used to cost
		for (int i = 0; i < this.mementoOwners.size(); i++) {
			this.mementoOwners.get(i).releaseMemento(this.mementos.get(i));
		}
		this.mementoOwners.clear();
		this.mementos.clear();
		// the participants' first-touch marks are deliberately NOT cleared: this savepoint's stamp is never handed out
		// again, so every mark it left behind is already stale for every future savepoint (see WarmUpTouchStamped)
	}

}
