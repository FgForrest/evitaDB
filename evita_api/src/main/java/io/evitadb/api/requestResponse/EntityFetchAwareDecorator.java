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
	 * The count of I/O fetches the request performed to produce the data this entity exposes - its own storage
	 * records, plus those of every body hanging off it because the request asked for one: the parent chain of
	 * a `hierarchyContent`, and the referenced and group entities of a `referenceContent`, named sets included.
	 * Each such body is counted once, however many references point at it.
	 *
	 * This is not the aggregate for a whole operation and does not add up to one: records read for entities that
	 * were filtered out belong to no returned entity at all, and one physical read serving two entities is
	 * attributed to both. The operation's real I/O is counted where the reads happen and reported by
	 * {@link EvitaResponse#getIoFetchCount()}.
	 */
	int getIoFetchCount();

	/**
	 * The count of bytes fetched from the underlying storage to produce the data this entity exposes, on exactly
	 * the terms described by {@link #getIoFetchCount()}.
	 */
	int getIoFetchedBytes();

}
