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
	 * The count of I/O fetches used to load this entity itself from the underlying storage - the records read for
	 * entities it references are NOT included, because finding them would mean walking the whole reference graph.
	 * The aggregate for a whole operation is counted where the reads happen and reported by
	 * {@link EvitaResponse#getIoFetchCount()}.
	 */
	int getIoFetchCount();

	/**
	 * The count of bytes fetched from the underlying storage to load this entity itself - the records read for
	 * entities it references are NOT included; see {@link #getIoFetchCount()}.
	 */
	int getIoFetchedBytes();

}
