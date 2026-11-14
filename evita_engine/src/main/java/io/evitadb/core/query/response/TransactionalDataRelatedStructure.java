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

package io.evitadb.core.query.response;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.deferred.BitmapSupplier;
import io.evitadb.core.query.algebra.identity.FormulaKey;

import javax.annotation.Nonnull;

/**
 * This interface unifies parts of the formula tree that are either {@link Formula} directly or objects that provide
 * access directly to bitmaps linked to transactional data store (such as {@link BitmapSupplier} extensions).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface TransactionalDataRelatedStructure {

	/** Returns the detached identity. */
	@Nonnull
	FormulaKey getKey();

	/**
	 * Prepares the structure for the given query execution: an implementation binds the execution context and
	 * initializes the structures it delegates to, where it has any. This method is necessary to be called prior to
	 * calling any of these methods:
	 *
	 * - {@link #getEstimatedCost()}
	 * - {@link #getCost()}
	 *
	 * @param queryExecutionContext context of the query execution
	 */
	void initialize(@Nonnull QueryExecutionContext queryExecutionContext);

	/**
	 * Estimated effort of the operation without prior the result of the computation. From bottom to up it's getting less
	 * useful because operation at the top of the formula doesn't count bitmap cardinality reduction that happens when
	 * formula is processed. For precise cost computation use {@link #getCost()}  operation that takes
	 * this into an account but also requires formula to be fully computed.
	 */
	long getEstimatedCost();

	/**
	 * Cost of the operation based on computation result. Sum of bitmap sizes of referenced bitmaps multiplied by known
	 * {@link #getOperationCost()} of this operation. This method triggers formula computation.
	 */
	long getCost();

	/**
	 * Returns estimated costs of this operation. The number should be product of real measurement of this operation
	 * compared to simple no operation formula that just rewrites all number to a new bitmap.
	 */
	long getOperationCost();

}
