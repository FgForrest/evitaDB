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

package io.evitadb.index;

import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.BucketBPlusTree;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.function.ToLongFunction;

/**
 * The sizers the index classes share when pricing what their sub-structures hold.
 *
 * The heap walks themselves live on the indexes, because ownership is a property of the owning class and nothing else
 * can decide it. What collects here is the small amount of *pricing* several of them would otherwise each restate —
 * and restate slightly differently, which is how one of them ends up silently reporting a zero.
 *
 * @author Claude (heap-size accounting), FG Forrest a.s. (c) 2026
 */
public final class IndexHeapSize {

	/**
	 * Prices one boxed key a {@link BucketBPlusTree} owns outright.
	 *
	 * Shared by every index built on such a tree — the inverted index, both unique indexes and the reference-type
	 * cardinality index — so that all of them answer identically for the same key.
	 *
	 * # Where a tree holds a boxed key
	 *
	 * Two places, and only the second depends on the column kind:
	 *
	 * - **Every separator key in an internal node**, always. That array is `M[]`, so a tree whose leaves keep their
	 *   keys inline as `long`s still boxes one key per separator, and nothing but the internal node holds it. Leaving
	 *   these at zero under-reports by one box per separator — a shortfall that **grows with the tree**, which is why
	 *   {@link BucketBPlusTree} has no sizer-less overload to fall into.
	 * - **The leaf keys**, but only when the leaves chose {@link io.evitadb.index.bPlusTree.BoxedObjectColumn}. The
	 *   front-coded and primitive columns store their keys as values and ignore the sizer entirely.
	 *
	 * A key that is not {@link Serializable} **throws** rather than being priced at zero. Every value entering these
	 * trees is verified `Serializable` on the way in, so reaching this branch means the invariant broke somewhere
	 * upstream — and a zero would hide that behind a plausible-looking total instead of surfacing it.
	 * {@link EvitaDataTypes#estimateSize} throws in the same spirit for a type evitaDB does not support.
	 */
	public static final ToLongFunction<Object> OWNED_KEY_SIZER = key -> {
		if (key instanceof final Serializable serializable) {
			return EvitaDataTypes.estimateSize(serializable);
		}
		throw new GenericEvitaInternalError(
			"Indexed key of type `" + key.getClass().getName() + "` is not Serializable, which every value entering " +
				"an index tree is verified to be - its heap footprint cannot be priced."
		);
	};

	private IndexHeapSize() {
		// utility class, never instantiated
	}

}
