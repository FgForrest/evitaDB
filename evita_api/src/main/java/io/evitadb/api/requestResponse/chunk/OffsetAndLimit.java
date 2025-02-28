/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api.requestResponse.chunk;


/**
 * The OffsetAndLimit record represents pagination parameters including offset, limit, and the last page number.
 *
 * @param offset           The starting point for fetching records.
 * @param limit            The number of records to fetch from the starting point.
 * @param pageNumber       The current page number based on the current pagination settings.
 * @param lastPageNumber   The last page number based on the current pagination settings.
 * @param totalRecordCount The total number of records available.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record OffsetAndLimit(int offset, int limit, int pageNumber, int lastPageNumber, int totalRecordCount) {

	public OffsetAndLimit(int offset, int limit, int totalRecordCount) {
		this(offset, limit, 1, 1, totalRecordCount);
	}

	/**
	 * Calculates the total length of the current page including the offset and limit values.
	 *
	 * @return the sum of offset and limit.
	 */
	public int length() {
		return this.offset + this.limit;
	}

}
