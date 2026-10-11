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

package io.evitadb.core.collection;

import io.evitadb.index.EntityIndexKey;

import javax.annotation.Nonnull;

/**
 * Lets a test outside this package plant a defect into the indexes of a collection warming up through the maintainer
 * its entity index mutation executors use - the maintainer is exposed to this package only (see
 * {@link EntityCollection#getEntityIndexMaintainer()}). The oracles of the virtual reference partitions prove with it
 * that they report an index object missing from the collection.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class EntityCollectionTestAccess {

	private EntityCollectionTestAccess() {
	}

	/**
	 * Removes an index of a collection warming up, as an executor retiring it does - its registration by key and by
	 * primary key and its persisted footprint - leaving everything that refers to it in place.
	 *
	 * @param collection the collection, warming up
	 * @param key        the key of the index to remove
	 */
	public static void removeIndex(@Nonnull EntityCollection collection, @Nonnull EntityIndexKey key) {
		collection.getEntityIndexMaintainer().removeIndex(key);
	}
}
