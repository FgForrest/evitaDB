/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.dataType;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serializable;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Data transfer object used to carry business data to the upper layers. Contains chunk of data with additional
 * methods allowing to consult total items count, next, previous records and so on.
 * <p>
 * This transfer object could be used to carry any Java object from maps, arrays to complex POJO objects as long
 * as they are {@link Serializable}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ThreadSafe
@Immutable
public sealed interface DataChunk<T extends Serializable> extends Iterable<T>, Serializable
	permits PaginatedList, StripList {

	/**
	 * Returns list of records for current page. List can contain any type of data.
	 *
	 * @return slice of data
	 */
	@Nonnull
	List<T> getData();

	/**
	 * Returns stream of the data.
	 */
	default Stream<T> stream() {
		return StreamSupport.stream(spliterator(), false);
	}

	/**
	 * Returns total number of records that are possible to fetch by paginating entire result stream.
	 */
	int getTotalRecordCount();

	/**
	 * Returns true if current page is the first page in the result set.
	 */
	boolean isFirst();

	/**
	 * Returns true if current page is the last page in the result set.
	 */
	boolean isLast();

	/**
	 * Returns true if there is previous page available.
	 */
	boolean hasPrevious();

	/**
	 * Returns true if there is next page available.
	 */
	boolean hasNext();

	/**
	 * Returns true if there is only single page available (i.e. total record count < record count on one page).
	 */
	boolean isSinglePage();

	/**
	 * Returns true if there are no data available.
	 */
	boolean isEmpty();

	/**
	 * Returns iterator for {@link Iterable} interface.
	 */
	@Nonnull
	@Override
	Iterator<T> iterator();

}
