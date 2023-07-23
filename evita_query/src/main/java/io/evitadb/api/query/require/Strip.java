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
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.Value;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Optional;

/**
 * This `strip` query controls count of the entities in the query output. It allows specifying 2 arguments in following order:
 *
 * - **[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) offset**: number of the items that
 *   should be omitted in the result, must be greater than or equals to zero (mandatory)
 * - **[int](https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html) limit**: number of entities on
 *   that should be returned, must be greater than zero (mandatory)
 *
 * Example - return 24 records from index 52:
 *
 * ```
 * strip(52, 24)
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "strip",
	shortDescription = "The constraint specifies which strip (subset) of found entities will be returned."
)
public class Strip extends AbstractRequireConstraintLeaf implements GenericConstraint<RequireConstraint> {
	@Serial private static final long serialVersionUID = 1300354074537839696L;

	private Strip(Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public Strip(@Value Integer offset, @Value Integer limit) {
		super(
			Optional.ofNullable(offset).orElse(0),
			Optional.ofNullable(limit).orElse(20)
		);
		Assert.isTrue(
			offset == null || offset >= 0,
			"Record offset must be greater than or equal to zero."
		);

		Assert.isTrue(
			limit == null || limit >= 0,
			"Record limit must be greater than or equal to zero."
		);
	}

	/**
	 * Returns number of the items that should be omitted in the result.
	 */
	public int getOffset() {
		return (Integer) getArguments()[0];
	}

	/**
	 * Returns number of entities on that should be returned.
	 */
	public int getLimit() {
		return (Integer) getArguments()[1];
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length > 1;
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new Strip(newArguments);
	}

}
