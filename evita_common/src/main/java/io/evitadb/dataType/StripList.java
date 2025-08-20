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

package io.evitadb.dataType;

import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Data transfer object used to carry business data to the upper layers. Contains page (i.e. slice) of data with additional
 * methods allowing to consult total items count and other utility methods.
 *
 * This transfer object could be used to carry any Java object from maps, arrays to complex POJO objects.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode
public final class StripList<T extends Serializable> implements DataChunk<T> {
	@Serial private static final long serialVersionUID = -480346362810326452L;
	private static final StripList<? extends Serializable> EMPTY_LIST = new StripList<>(1, 20, 0, Collections.emptyList());
	private final int limit;
	private final int offset;
	private final int totalRecordCount;
	private final List<T> data;

	/**
	 * Returns empty paginated list.
	 */
	public static <T extends Serializable> StripList<T> emptyList() {
		//noinspection unchecked
		return (StripList<T>) EMPTY_LIST;
	}

	/**
	 * Constructor. This one doesn't require target data to be known.
	 *
	 * @param offset           current page number (indexed from 1)
	 * @param limit            number of records per page
	 * @param totalRecordCount total number of records
	 */
	public StripList(int offset, int limit, int totalRecordCount) {
		this.offset = offset;
		this.limit = limit;
		this.totalRecordCount = totalRecordCount;
		this.data = Collections.emptyList();
	}

	/**
	 * Constructor that completely initializes the DTO object.
	 *
	 * @param offset           current page number (indexed from 1)
	 * @param limit            number of records per page
	 * @param totalRecordCount total number of records
	 * @param data             list of records
	 */
	public StripList(int offset, int limit, int totalRecordCount, List<T> data) {
		this.limit = limit;
		this.offset = offset;
		this.totalRecordCount = totalRecordCount;
		this.data = data;
	}

	/**
	 * Returns current offset (indexed from 0).
	 */
	public int getOffset() {
		return this.offset;
	}

	/**
	 * Returns limit - i.e. maximal number of records that are requested after offset.
	 */
	public int getLimit() {
		return this.limit;
	}

	@Override
	@Nonnull
	public List<T> getData() {
		return this.data != null ? this.data : Collections.emptyList();
	}

	@Override
	public int getTotalRecordCount() {
		return this.totalRecordCount;
	}

	@Override
	public boolean isFirst() {
		return this.offset == 0;
	}

	@Override
	public boolean isLast() {
		return this.offset + this.limit >= this.totalRecordCount;
	}

	@Override
	public boolean hasPrevious() {
		return !isFirst();
	}

	@Override
	public boolean hasNext() {
		return !isLast();
	}

	@Override
	public boolean isSinglePage() {
		return isFirst() && isLast();
	}

	@Override
	public boolean isEmpty() {
		return this.totalRecordCount == 0;
	}

	@Nonnull
	@Override
	public Iterator<T> iterator() {
		return getData().iterator();
	}

	@Override
	public String toString() {
		return "Strip " + this.offset + " with limit " + this.limit + " (" + this.totalRecordCount + "recs. found)\n" +
			this.data.stream().map(it -> "\t- " + it.toString()).collect(Collectors.joining("\n"));
	}

}
