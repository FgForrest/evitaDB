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
 * methods allowing to consult total items count, total page count, current page and so on.
 *
 * This transfer object could be used to carry any Java object from maps, arrays to complex POJO objects.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode
public final class PaginatedList<T extends Serializable> implements DataChunk<T> {
	@Serial private static final long serialVersionUID = -480346362810326452L;
	private static final PaginatedList<? extends Serializable> EMPTY_LIST = new PaginatedList<>(1, 1, 20, 0, Collections.emptyList());
	private final int pageSize;
	private final int pageNumber;
	private final int lastPageNumber;
	private final int totalRecordCount;
	private final List<T> data;

	/**
	 * Returns empty paginated list.
	 */
	public static <T extends Serializable> PaginatedList<T> emptyList() {
		//noinspection unchecked
		return (PaginatedList<T>) EMPTY_LIST;
	}

	/**
	 * Returns true if page exceeds maximal number of pages in connection with current total record count and considered
	 * page size.
	 */
	public static boolean isRequestedResultBehindLimit(int pageNumber, int pageSize, int totalRecordCount) {
		return ((pageNumber - 1) * pageSize) + 1 > totalRecordCount;
	}

	/**
	 * Returns offset of the first record for specified page with specified record count on each page. Offset is indexed
	 * from 0 as this is usually required by databases.
	 */
	public static int getFirstItemNumberForPage(int pageNumber, int pageSize) {
		int firstRecord = (pageNumber - 1) * pageSize;
		return Math.max(firstRecord, 0);
	}

	/**
	 * Calculates the number of the last page for a paginated list based on the
	 * provided page size and total record count.
	 *
	 * @param pageSize the number of records per page. If this value is 0,
	 *                 the result will be 0 to avoid division by zero.
	 * @param totalRecordCount the total number of records in the dataset.
	 * @return the number of the last page. If the page size is 0, it returns 0.
	 *         Otherwise, it calculates the ceiling of totalRecordCount divided
	 *         by pageSize.
	 */
	public static int getLastPageNumber(int pageSize, int totalRecordCount) {
		return pageSize == 0 ? 0 : (int) Math.ceil((float) (totalRecordCount) / (float) pageSize);
	}

	/**
	 * Constructor. This one doesn't require target data to be known. It is handy if you want to compute pagination
	 * data for execution via methods {@link #getFirstPageItemNumber()} and {@link #getLastPageItemNumber()}.
	 *
	 * @param pageNumber       current page number (indexed from 1)
	 * @param pageSize         number of records per page
	 * @param totalRecordCount total number of records
	 */
	public PaginatedList(int pageNumber, int pageSize, int totalRecordCount) {
		this(pageNumber, getLastPageNumber(pageSize, totalRecordCount), pageSize, totalRecordCount);
	}

	/**
	 * Constructor. This one doesn't require target data to be known. It is handy if you want to compute pagination
	 * data for execution via methods {@link #getFirstPageItemNumber()} and {@link #getLastPageItemNumber()}.
	 *
	 * @param pageNumber       current page number (indexed from 1)
	 * @param pageSize         number of records per page
	 * @param totalRecordCount total number of records
	 */
	public PaginatedList(int pageNumber, int lastPageNumber, int pageSize, int totalRecordCount) {
		this.pageNumber = pageNumber;
		this.lastPageNumber = lastPageNumber;
		this.pageSize = pageSize;
		this.totalRecordCount = totalRecordCount;
		this.data = Collections.emptyList();
	}

	/**
	 * Constructor that completely initializes the DTO object.
	 *
	 * @param pageNumber       current page number (indexed from 1)
	 * @param pageSize         number of records per page
	 * @param totalRecordCount total number of records
	 * @param data             list of records
	 */
	public PaginatedList(int pageNumber, int pageSize, int totalRecordCount, @Nonnull List<T> data) {
		this(pageNumber, getLastPageNumber(pageSize, totalRecordCount), pageSize, totalRecordCount, data);
	}

	/**
	 * Constructor that completely initializes the DTO object.
	 *
	 * @param pageNumber       current page number (indexed from 1)
	 * @param pageSize         number of records per page
	 * @param totalRecordCount total number of records
	 * @param data             list of records
	 */
	public PaginatedList(int pageNumber, int lastPageNumber, int pageSize, int totalRecordCount, @Nonnull List<T> data) {
		this.pageSize = pageSize;
		this.lastPageNumber = lastPageNumber;
		this.pageNumber = pageNumber;
		this.totalRecordCount = totalRecordCount;
		this.data = data;
	}

	/**
	 * Returns number of records per single page.
	 */
	public int getPageSize() {
		return this.pageSize;
	}

	/**
	 * Returns current page number (indexed from 1).
	 */
	public int getPageNumber() {
		return this.pageNumber;
	}

	/**
	 * Returns number of the last page that can be accessed with current number of records.
	 * Returns -1 when offset/limit was used for creating paginated list.
	 */
	public int getLastPageNumber() {
		return this.lastPageNumber;
	}

	/**
	 * Returns offset of the first record of current page with current pageSize.
	 */
	public int getFirstPageItemNumber() {
		if (isRequestedResultBehindLimit(this.pageNumber, this.pageSize, this.totalRecordCount)) {
			return 0;
		}
		return getFirstItemNumberForPage(this.pageNumber, this.pageSize);
	}

	/**
	 * Returns offset of the last record of current page with current pageSize.
	 */
	public int getLastPageItemNumber() {
		int result = ((this.pageNumber) * this.pageSize) - 1;
		return Math.min(result, this.totalRecordCount);
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
		return this.pageNumber == 1;
	}

	@Override
	public boolean isLast() {
		return this.pageNumber >= getLastPageNumber();
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
		final int lastPageNumber = getLastPageNumber();
		return "Page " + this.pageNumber + " of " + (lastPageNumber == 0 ? 1 : lastPageNumber) +
			" (" + this.totalRecordCount + " recs. found)\n" +
			this.data.stream().map(it -> "\t- " + it.toString()).collect(Collectors.joining("\n"));
	}

}
