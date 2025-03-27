/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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


import io.evitadb.api.requestResponse.EvitaRequest.ResultForm;

import javax.annotation.Nonnull;

/**
 * The Slicer interface defines the contract for calculating offset and limit values based on an EvitaRequest and
 * the total record count. It is intended to facilitate pagination by computing the necessary parameters
 * for retrieving a subset of records from a larger dataset.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface Slicer {

	/**
	 * Calculates the offset and limit for pagination based on the provided EvitaRequest and the total record count.
	 *
	 * @param resultForm       the expected result form of the paginated result
	 * @param offset           initial offset value (in case of page it contains page number).
	 * @param limit            initial limit value (in case of page it contains page size).
	 * @param totalRecordCount the total number of records from which a subset is to be retrieved.
	 * @return an OffsetAndLimit object containing the calculated offset, limit, and last page number.
	 */
	@Nonnull
	OffsetAndLimit calculateOffsetAndLimit(@Nonnull ResultForm resultForm, int offset, int limit, int totalRecordCount);

}
