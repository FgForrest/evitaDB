/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.index.result;

/**
 * Describes whether an `add` or `remove` operation on a cardinality-tracking index
 * crossed the existence boundary (i.e. the cardinality moved between zero and one).
 *
 * Replaces the historical `boolean` return on `addRecord` / `removeRecord` which was
 * highly ambiguous - the name said "add a record" while the boolean reported a
 * different concept: whether the operation transitioned a key into or out of the index.
 * Confusing the two semantics caused at least one real defect (see commit `d331d1db4`),
 * where a caller treated the result as "did we touch the index" instead of "did the key
 * just appear or just disappear".
 *
 * - `BOUNDARY_CROSSED` - the operation transitioned the cardinality of the key
 *   between zero and the non-zero range (i.e. on `addRecord` the cardinality became
 *   one, on `removeRecord` the cardinality became zero). Callers should propagate
 *   the event to downstream indexes that only track membership rather than
 *   cardinality.
 * - `NO_BOUNDARY_CROSSING` - the cardinality was incremented or decremented but the
 *   key was already / is still present. The downstream membership indexes do not
 *   need to be notified.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public enum CardinalityChange {

	/**
	 * The cardinality crossed the existence boundary (zero ↔ non-zero) as a result of
	 * this operation. Membership-only downstream indexes must be updated.
	 */
	BOUNDARY_CROSSED,

	/**
	 * The cardinality changed but did not cross the existence boundary. Membership-only
	 * downstream indexes are already in the correct state.
	 */
	NO_BOUNDARY_CROSSING

}
