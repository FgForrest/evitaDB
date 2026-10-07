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

import io.evitadb.core.transaction.memory.Snapshotable;
import io.evitadb.index.fulltext.FulltextIndex.Field;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The transactional diff layer of a {@link FulltextIndex}: the fields the transaction registered, in id order, and the
 * ids of the fields it retired - committed ones and its own alike. Its mere existence is also what marks the index as
 * written - a commit merge finding no layer carries the index forward as the same instance.
 *
 * Registration and retirement only ever append, so a savepoint memento is just the two counts when it was taken, and
 * restoring it is a truncation of both - an absolute restore, repeatable at will.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
final class FulltextIndexChanges implements Snapshotable<FulltextIndexChanges.FulltextIndexChangesMemento> {

	/**
	 * Fields registered in this transaction, in id order; allocated on the first registration.
	 */
	@Nullable private List<Field> addedFields;

	/**
	 * Ids of the fields retired in this transaction, in retirement order, in the first {@link #retiredFieldCount}
	 * slots; allocated on the first retirement. A field is retired at most once, and an index retires a handful in its
	 * life, so a linear scan answers a lookup.
	 */
	@Nullable private int[] retiredFieldIds;

	/**
	 * How many slots of {@link #retiredFieldIds} hold a retired field id.
	 */
	private int retiredFieldCount;

	/**
	 * Returns the fields registered in this transaction.
	 *
	 * @return the fields in id order, empty when none was registered
	 */
	@Nonnull
	List<Field> getAddedFields() {
		return this.addedFields == null ? List.of() : this.addedFields;
	}

	/**
	 * Registers a field.
	 *
	 * @param field the field, whose id is the next one after every field registered so far
	 */
	void addField(@Nonnull Field field) {
		if (this.addedFields == null) {
			this.addedFields = new ArrayList<>(4);
		}
		this.addedFields.add(field);
	}

	/**
	 * Retires a field.
	 *
	 * @param fieldId id of a field neither committed retired nor retired in this transaction
	 */
	void retireField(int fieldId) {
		if (this.retiredFieldIds == null) {
			this.retiredFieldIds = new int[2];
		} else if (this.retiredFieldCount == this.retiredFieldIds.length) {
			this.retiredFieldIds = Arrays.copyOf(this.retiredFieldIds, this.retiredFieldCount * 2);
		}
		this.retiredFieldIds[this.retiredFieldCount++] = fieldId;
	}

	/**
	 * Returns whether this transaction retired a field.
	 *
	 * @param fieldId id of the field
	 * @return true when the transaction retired it
	 */
	boolean isRetired(int fieldId) {
		for (int i = 0; i < this.retiredFieldCount; i++) {
			if (this.retiredFieldIds[i] == fieldId) {
				return true;
			}
		}
		return false;
	}

	@Nonnull
	@Override
	public FulltextIndexChangesMemento snapshot() {
		return new FulltextIndexChangesMemento(getAddedFields().size(), this.retiredFieldCount);
	}

	@Override
	public void restore(@Nonnull FulltextIndexChangesMemento memento) {
		final List<Field> fields = this.addedFields;
		if (fields != null) {
			while (fields.size() > memento.addedFieldCount()) {
				fields.remove(fields.size() - 1);
			}
		}
		// the slots past the count are dead, so truncating the count is the whole restore
		this.retiredFieldCount = Math.min(this.retiredFieldCount, memento.retiredFieldCount());
	}

	/**
	 * The savepoint memento.
	 *
	 * @param addedFieldCount   how many fields the transaction had registered when the savepoint captured the layer
	 * @param retiredFieldCount how many fields the transaction had retired when the savepoint captured the layer
	 */
	record FulltextIndexChangesMemento(int addedFieldCount, int retiredFieldCount) {
	}

}
