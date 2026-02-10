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

package io.evitadb.api.exception;

import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when a query or lookup method expected to return at most one result (0 or 1) matches
 * multiple records instead. This indicates either ambiguous query criteria or data modeling issues where
 * uniqueness constraints are not properly enforced.
 *
 * **Common scenarios:**
 *
 * - Calling {@link io.evitadb.api.requestResponse.data.PricesContract#getPrice(String, java.util.Currency)}
 *   when multiple prices match the criteria (should use unique price identifiers)
 * - Calling {@link io.evitadb.api.EvitaSessionContract#queryOne(io.evitadb.api.query.Query, Class)} when
 *   the query matches multiple entities (should add more restrictive filters)
 * - Using entity reference lookup with criteria that match multiple entities
 * - Proxy method calls that expect singular results but find multiple matches
 *
 * The exception captures the number of matches found, helping clients debug which query criteria need
 * to be made more specific. Clients should either:
 *
 * 1. Add additional filter constraints to narrow results to a single match
 * 2. Use collection-returning methods like `query()` instead of `queryOne()`
 * 3. Fix data model violations if uniqueness was expected but not enforced
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class UnexpectedResultCountException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 4687397430088399013L;
	/**
	 * The number of records that matched when 0 or 1 was expected.
	 */
	@Getter private final int matchedCount;

	/**
	 * Creates a new exception with a default message indicating how many records matched.
	 *
	 * @param matchedCount the number of records that matched the query
	 */
	public UnexpectedResultCountException(int matchedCount) {
		super("Call is expected to return either one or none record, but matched " + matchedCount + " records!");
		this.matchedCount = matchedCount;
	}

	/**
	 * Creates a new exception with a custom error message.
	 *
	 * @param matchedCount the number of records that matched the query
	 * @param publicMessage custom error message suitable for client consumption
	 */
	public UnexpectedResultCountException(int matchedCount, @Nonnull String publicMessage) {
		super(publicMessage);
		this.matchedCount = matchedCount;
	}
}
