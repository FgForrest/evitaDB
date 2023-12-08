/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.query.require;

import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Optional;

/**
 * The `page` requirement controls the number and slice of entities returned in the query response. If no page
 * requirement is used in the query, the default page 1 with the default page size 20 is used. If the requested page
 * exceeds the number of available pages, a result with the first page is returned. An empty result is only returned if
 * the query returns no result at all or the page size is set to zero. By automatically returning the first page result
 * when the requested page is exceeded, we try to avoid the need to issue a secondary request to fetch the data.
 *
 * The information about the actual returned page and data statistics can be found in the query response, which is
 * wrapped in a so-called data chunk object. In case of the page constraint, the {@link PaginatedList} is used as data
 * chunk object.
 *
 * Example:
 *
 * <pre>
 * page(1, 24)
 * </pre>
 * 
 * <a href="https://evitadb.io/documentation/query/requirements/paging#page">Visit detailed user documentation</a>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "page",
	shortDescription = "The constraint specifies which page of found entities will be returned.",
	userDocsLink = "/documentation/query/requirements/paging#page"
)
public class Page extends AbstractRequireConstraintLeaf implements GenericConstraint<RequireConstraint>, ChunkingRequireConstraint {
	@Serial private static final long serialVersionUID = 1300354074537839696L;

	private Page(Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public Page(@Nullable Integer number, @Nullable Integer size) {
		super(
			Optional.ofNullable(number).orElse(1),
			Optional.ofNullable(size).orElse(20)
		);
		Assert.isTrue(
			number == null || number > 0,
			"Page number must be greater than zero."
		);

		Assert.isTrue(
			size == null || size >= 0,
			"Page size must be greater than or equal to zero."
		);
	}

	/**
	 * Returns page number to return in the response.
	 */
	@AliasForParameter("number")
	public int getPageNumber() {
		return (Integer) getArguments()[0];
	}

	/**
	 * Returns page size to return in the response.
	 */
	@AliasForParameter("size")
	public int getPageSize() {
		return (Integer) getArguments()[1];
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 2;
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new Page(newArguments);
	}

}