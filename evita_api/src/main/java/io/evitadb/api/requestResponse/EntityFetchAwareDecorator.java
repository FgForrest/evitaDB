/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.api.requestResponse;

/**
 * This interface allows questioning entity decorators about the
 * number of I/O fetches and bytes fetched from underlying storage.
 * This interface is intended to be implemented only by server-side
 * model decorators, because the data should primarily be used for
 * observability.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface EntityFetchAwareDecorator {

	/**
	 * What this entity would have cost had it been fetched on its own - the I/O fetches that produced its own
	 * storage records, plus those of every body the request caused to be read for it: the parent chain of a
	 * `hierarchyContent`, and the referenced and group entities of a `referenceContent`, named sets included, and
	 * recursively whatever those bodies reach in turn. Each distinct entity counts once, however many references
	 * point at it, however many paths lead to it, and however many views of it this entity exposes; where two
	 * views of one entity read different records, the union of what they read is counted.
	 *
	 * Bodies the request **caused to be read and then discarded** count too. An ordering that ranks references by
	 * a property of their group cannot rank anything until every candidate is known, so asking for the first five
	 * of a hundred reads a hundred; the page size changes what this entity exposes, not what obtaining it cost.
	 *
	 * This is deliberately **not** a share of the operation's real I/O and does not add up to one. A record that
	 * two returned entities both needed is reported by both, because an entity's cost must not move with whatever
	 * else happens to share its page - that is what makes the number comparable across the entities of one
	 * response, which is the only context it is read in. Records read for entities that were filtered out belong
	 * to no returned entity at all. The operation's physical I/O is counted where the reads happen and reported by
	 * {@link EvitaResponse#getIoFetchCount()}; the two are different metrics and are not expected to reconcile.
	 */
	int getIoFetchCount();

	/**
	 * The count of bytes fetched from the underlying storage to produce the data this entity exposes, on exactly
	 * the terms described by {@link #getIoFetchCount()}.
	 */
	int getIoFetchedBytes();

}
