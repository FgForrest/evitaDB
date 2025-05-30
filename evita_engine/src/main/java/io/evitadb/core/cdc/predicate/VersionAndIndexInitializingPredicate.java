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

package io.evitadb.core.cdc.predicate;

import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;

import javax.annotation.Nonnull;

/**
 * This implementation is not really a predicate but rather a utility class that initializes the version and index
 * in the {@link MutationPredicateContext} when processing a {@link TransactionMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class VersionAndIndexInitializingPredicate extends MutationPredicate {

	public VersionAndIndexInitializingPredicate(@Nonnull MutationPredicateContext context) {
		super(context);
	}

	@Override
	public boolean test(Mutation mutation) {
		if (mutation instanceof TransactionMutation transactionMutation) {
			this.context.setVersion(transactionMutation.getVersion(), transactionMutation.getMutationCount());
		} else {
			this.context.advance();
		}
		return true;
	}

}
