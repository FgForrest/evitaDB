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

package io.evitadb.index.fulltext;

import com.carrotsearch.hppc.IntByteHashMap;
import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.core.transaction.memory.UndoJournal;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * The transactional diff layer of a {@link FieldLengthTable}: the encoded length every entity written in the
 * transaction ends up with, and how the entity count moved.
 *
 * An entity removed in the transaction is held as an explicit `0`, which is what tells it apart from an entity the
 * transaction never touched - only the second falls through to the committed table.
 *
 * ## Savepoints
 *
 * A per-entity savepoint is captured on the first write after it opens, so a snapshot that copied the overrides would
 * cost `O(overrides)` per entity - `O(n²)` over a transaction writing the same field on every entity. The layer
 * journals absolute per-entity inverses into an {@link UndoJournal} instead, the same way the collection layers do:
 * {@link #snapshot()} is a mark, {@link #restore} replays the inverses written since it, and the journal exists only
 * while a savepoint is open.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
final class FieldLengthTableChanges implements Snapshotable<FieldLengthTableChanges.FieldLengthTableChangesMemento> {

	/**
	 * Primary key to the encoded length it has in this transaction; `0` for an entity removed in it.
	 */
	@Nonnull private final IntByteHashMap overrides = new IntByteHashMap();

	/**
	 * How the entity count differs from the committed table's.
	 */
	private int sizeDelta;

	/**
	 * Inverses of the writes made while a savepoint is open; `null` while none is.
	 */
	@Nullable private UndoJournal undoJournal;

	/**
	 * Returns the encoded length the transaction gave an entity.
	 *
	 * @param primaryKey primary key of the entity
	 * @return the encoded length as an unsigned value, `0` for an entity removed in the transaction, or `-1` when the
	 * transaction never wrote the entity and the committed table answers
	 */
	int getEncoded(int primaryKey) {
		final int index = this.overrides.indexOf(primaryKey);
		return index >= 0 ? Byte.toUnsignedInt(this.overrides.indexGet(index)) : -1;
	}

	/**
	 * Returns how the entity count differs from the committed table's.
	 *
	 * @return the difference
	 */
	int getSizeDelta() {
		return this.sizeDelta;
	}

	/**
	 * Returns the overrides, for the commit merge to apply. Read-only by contract.
	 *
	 * @return primary key to encoded length, `0` meaning removed
	 */
	@Nonnull
	IntByteHashMap getOverrides() {
		return this.overrides;
	}

	/**
	 * Gives an entity a new encoded length in this transaction.
	 *
	 * @param primaryKey      primary key of the entity
	 * @param encoded         the new encoded length, `0` to remove the entity
	 * @param previousEncoded the encoded length the entity had as this transaction saw it until now, `0` when none
	 */
	void set(int primaryKey, int encoded, int previousEncoded) {
		final UndoJournal journal = this.undoJournal;
		if (journal != null) {
			// an absolute restore of the entity's override and of the count, captured before either changes
			final int index = this.overrides.indexOf(primaryKey);
			final boolean wasOverridden = index >= 0;
			final byte previousOverride = wasOverridden ? this.overrides.indexGet(index) : 0;
			final int previousSizeDelta = this.sizeDelta;
			journal.push(() -> {
				if (wasOverridden) {
					this.overrides.put(primaryKey, previousOverride);
				} else {
					this.overrides.remove(primaryKey);
				}
				this.sizeDelta = previousSizeDelta;
			});
		}
		this.overrides.put(primaryKey, (byte) encoded);
		this.sizeDelta += (encoded == 0 ? 0 : 1) - (previousEncoded == 0 ? 0 : 1);
	}

	@Nonnull
	@Override
	public FieldLengthTableChangesMemento snapshot() {
		if (this.undoJournal == null) {
			this.undoJournal = new UndoJournal();
		}
		return new FieldLengthTableChangesMemento(this.undoJournal.mark());
	}

	@Override
	public void restore(@Nonnull FieldLengthTableChangesMemento memento) {
		UndoJournal.assertRestorable(this.undoJournal, memento.mark());
		if (this.undoJournal != null) {
			this.undoJournal.rollbackTo(memento.mark());
		}
	}

	@Override
	public void releaseMemento(@Nonnull FieldLengthTableChangesMemento memento) {
		if (this.undoJournal != null) {
			this.undoJournal.releaseFrom(memento.mark());
			if (this.undoJournal.isEmpty()) {
				this.undoJournal = null;
			}
		}
	}

	/**
	 * The savepoint memento: only the journal position to rewind to - the overrides themselves are rewound by the
	 * journal, never copied.
	 *
	 * @param mark the {@link UndoJournal#mark()} taken when the savepoint captured this layer
	 */
	record FieldLengthTableChangesMemento(int mark) {
	}

}
