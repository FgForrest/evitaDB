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

package io.evitadb.core.transaction.memory;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;

/**
 * Append-only log of inverse (undo) operations that lets an accumulating transactional diff layer implement a cheap
 * savepoint {@link Snapshotable#snapshot()} / {@link Snapshotable#restore(Object)} without deep-copying its whole
 * accumulated delta.
 *
 * The rationale (see the `Snapshotable` contract and the savepoint machinery on
 * {@link TransactionalLayerMaintainer}): a per-entity savepoint is captured LAZILY, on the first write-touch of a
 * layer, so a mutation ALWAYS immediately follows {@link Snapshotable#snapshot()}. Any scheme that deep-copies the
 * accumulated delta therefore pays `O(accumulated-delta)` per savepoint — the "per-entity rollback cliff". Instead of
 * copying state, this journal records the INVERSE of each mutation while a savepoint is open:
 *
 * - {@link Snapshotable#snapshot()} captures only a {@link #mark()} (an `int` position) — `O(1)`, independent of the
 *   accumulated delta.
 * - Every mutator {@link #push(Runnable)}es a small inverse operation while the journal is active.
 * - {@link #rollbackTo(int)} pops entries down to the mark and runs each inverse in strict REVERSE order, exactly
 *   rewinding even non-local effects (e.g. index re-keying shifts un-shift correctly under reverse replay). Cost scales
 *   with the number of INTRA-savepoint mutations (one entity's delta — small), not the accumulated transaction delta.
 * - {@link #releaseFrom(int)} discards entries at or above the mark WITHOUT running them, used when a savepoint is
 *   committed (the changes are kept, so their inverses are never needed).
 *
 * The inverse operations are expressed as {@link Runnable}s so each layer can capture exactly the minimal pre-image its
 * mutation requires. Each inverse SHOULD be an absolute restore of the touched state (idempotent when several inverses
 * for the same key are replayed): with reverse replay the earliest-pushed inverse for a given key runs last and
 * therefore wins, restoring the pre-savepoint value regardless of how many times that key was touched in between.
 *
 * A single layer holds at most one journal at a time. The maintainer permits only ONE savepoint to be open at a time
 * (nested savepoints are rejected), so the mark of the active savepoint is always `0` in practice; the position-based
 * API nevertheless keeps the design correct under LIFO-nested marks should that restriction ever be relaxed.
 *
 * This type is deliberately NOT thread-safe — a transactional diff layer is confined to the thread that owns its
 * transaction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@NotThreadSafe
public final class UndoJournal implements Serializable {
	@Serial private static final long serialVersionUID = 7224686646802062799L;

	/**
	 * The recorded inverse operations, in the order they were pushed. The backing array is allocated lazily by
	 * {@link ArrayList} on the first {@link #push(Runnable)}, so an empty savepoint window (snapshot immediately
	 * followed by commit/rollback with no intervening mutation) allocates almost nothing.
	 */
	private final ArrayList<Runnable> inverseOperations = new ArrayList<>();

	/**
	 * Returns the current journal position to be used as a savepoint mark. A later {@link #rollbackTo(int)} /
	 * {@link #releaseFrom(int)} with this value rewinds exactly the operations pushed after this call.
	 *
	 * @return the current number of recorded inverse operations
	 */
	public int mark() {
		return this.inverseOperations.size();
	}

	/**
	 * Records an inverse (undo) operation to be replayed on {@link #rollbackTo(int)}. Should be called BEFORE (or right
	 * as) the corresponding forward mutation is applied, capturing the pre-mutation state the inverse needs.
	 *
	 * @param inverseOperation the operation that undoes the forward mutation about to be applied
	 */
	public void push(@Nonnull Runnable inverseOperation) {
		this.inverseOperations.add(inverseOperation);
	}

	/**
	 * Rewinds the journal to the given mark by running every inverse operation pushed after the mark, in strict reverse
	 * order (most recent first), and discarding them. Operations below the mark are left intact (they belong to an
	 * outer, still-open savepoint).
	 *
	 * @param mark a position previously obtained from {@link #mark()}
	 */
	public void rollbackTo(int mark) {
		Assert.isPremiseValid(
			mark >= 0 && mark <= this.inverseOperations.size(),
			() -> new GenericEvitaInternalError(
				"Illegal undo-journal mark " + mark + " for a journal of size " + this.inverseOperations.size() + "."
			)
		);
		for (int i = this.inverseOperations.size() - 1; i >= mark; i--) {
			this.inverseOperations.remove(i).run();
		}
	}

	/**
	 * Discards every inverse operation pushed after the given mark WITHOUT running it. Used when a savepoint is committed
	 * (accepted): the forward changes stay, so their inverses are no longer needed and the journal shrinks back to the
	 * mark.
	 *
	 * @param mark a position previously obtained from {@link #mark()}
	 */
	public void releaseFrom(int mark) {
		Assert.isPremiseValid(
			mark >= 0 && mark <= this.inverseOperations.size(),
			() -> new GenericEvitaInternalError(
				"Illegal undo-journal mark " + mark + " for a journal of size " + this.inverseOperations.size() + "."
			)
		);
		if (this.inverseOperations.size() > mark) {
			this.inverseOperations.subList(mark, this.inverseOperations.size()).clear();
		}
	}

	/**
	 * Returns whether the journal currently holds no inverse operations. A layer uses this to release (null out) the
	 * journal once a committed savepoint has drained it, so subsequent non-savepoint mutations pay nothing.
	 *
	 * @return `true` when there are no recorded inverse operations
	 */
	public boolean isEmpty() {
		return this.inverseOperations.isEmpty();
	}

	/**
	 * Validates that a memento with the given mark can be restored against the given (possibly `null`) journal: a
	 * missing journal is legal only for a mark of `0` (there is nothing to rewind). Restoring a positive mark without
	 * a journal means the journal was drained / released before the restore — a savepoint sequencing programming error
	 * that must surface immediately instead of silently skipping the rewind.
	 *
	 * @param undoJournal the journal the restoring layer currently holds, or `null` when it has none
	 * @param mark        the mark carried by the memento being restored
	 */
	public static void assertRestorable(@Nullable UndoJournal undoJournal, int mark) {
		Assert.isPremiseValid(
			undoJournal != null || mark == 0,
			() -> new GenericEvitaInternalError(
				"Undo journal was released before restoring a memento with mark " + mark + "."
			)
		);
	}

}
