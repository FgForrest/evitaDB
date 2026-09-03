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

package io.evitadb.api.query.require;

import io.evitadb.dataType.SupportedEnum;

/**
 * Controls what {@link HierarchyContent} does with an ancestor whose **requested body** cannot be materialized.
 *
 * An ancestor is present in the hierarchy index and still fail to yield a body: it may hold no data in the locale
 * the query filters by, it may have been deleted, or the parent primary key may point at an entity that was never
 * created. The chain of ancestors is walked from the immediate parent upwards, and this enum decides what happens
 * when the walk reaches such a node.
 *
 * - `MATCHING` — the chain is cut below the first ancestor whose requested body cannot be materialized. Every
 *   element the caller receives therefore carries the body that was asked for, at the price of the ancestors above
 *   the cut, which are not reported at all. This is the default.
 * - `COMPLETE` — every ancestor is returned. One whose requested body cannot be materialized is returned as
 *   a bodyless pointer carrying nothing but its primary key, and the walk continues past it, so an ancestor with
 *   a body may well appear above a pointer.
 *
 * ## The no-body case is a no-op in both modes
 *
 * The definition is written in terms of the **requested** body on purpose. A `hierarchyContent()` that carries no
 * inner {@link EntityFetch} requests no body at all, so nothing can fail to materialize, so neither mode has
 * anything to act on and the complete chain of parent primary keys is returned. That is a consequence of the
 * definition rather than a special case, and it is what keeps `entityFetchAll()` — which emits a bare
 * `hierarchyContent()` — insensitive to the mode.
 *
 * ## Why `MATCHING` is the default
 *
 * `MATCHING` is the backward-compatible value: it is the closer of the two to the behaviour that preceded this
 * argument, and the only one that never introduces a new exception for an existing caller. Choosing `COMPLETE`
 * as the default would hand bodyless pointers to callers that have never had to expect them and whose code
 * dereferences the returned ancestors unconditionally.
 *
 * A caller that wants the ancestors above an unmaterializable one has to ask for `COMPLETE` explicitly, and has
 * to be prepared for a returned ancestor that carries no body.
 *
 * @see HierarchyContent
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@SupportedEnum
public enum HierarchyParentsBehaviour {

	/**
	 * Every ancestor is returned. An ancestor whose requested body cannot be materialized is returned as a bodyless
	 * pointer and the traversal continues above it, so an ancestor carrying a body may follow a bodyless one.
	 */
	COMPLETE,
	/**
	 * The chain is cut just below the first ancestor whose requested body cannot be materialized - that ancestor and
	 * everything above it is omitted. Every returned ancestor therefore carries the requested body. This is
	 * the default behaviour.
	 */
	MATCHING

}
