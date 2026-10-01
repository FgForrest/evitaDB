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
import java.util.List;

/**
 * The transactional diff layer of a {@link FulltextIndex}: the fields the transaction registered, in id order. Its
 * mere existence is also what marks the index as written - a commit merge finding no layer carries the index forward
 * as the same instance.
 *
 * Registration only ever appends, so a savepoint memento is just the count of fields registered when it was taken,
 * and restoring it is a truncation - an absolute restore, repeatable at will.
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

	@Nonnull
	@Override
	public FulltextIndexChangesMemento snapshot() {
		return new FulltextIndexChangesMemento(getAddedFields().size());
	}

	@Override
	public void restore(@Nonnull FulltextIndexChangesMemento memento) {
		final List<Field> fields = this.addedFields;
		if (fields != null) {
			while (fields.size() > memento.addedFieldCount()) {
				fields.remove(fields.size() - 1);
			}
		}
	}

	/**
	 * The savepoint memento.
	 *
	 * @param addedFieldCount how many fields the transaction had registered when the savepoint captured the layer
	 */
	record FulltextIndexChangesMemento(int addedFieldCount) {
	}

}
