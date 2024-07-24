/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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
import java.util.Comparator;

/**
 * Predicate filters out only mutations that are related to mutations before / after particular version or index within
 * the particular version. The direction of the comparison is determined by the provided comparators (before / after).
 * The predicate is optimized for matching also additional mutations in a row that are related to the same version
 * (i.e. transaction).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class VersionAndIndexPredicate extends MutationPredicate {
	private final long sinceVersion;
	private final int sinceIndex;
	private final Comparator<Long> versionComparator;
	private final Comparator<Integer> indexComparator;

	public VersionAndIndexPredicate(
		@Nonnull MutationPredicateContext context,
		long sinceVersion,
		int sinceIndex,
		@Nonnull Comparator<Long> versionComparator,
		@Nonnull Comparator<Integer> indexComparator
	) {
		super(context);
		this.sinceVersion = sinceVersion;
		this.sinceIndex = sinceIndex;
		this.versionComparator = versionComparator;
		this.indexComparator = indexComparator;
	}

	@Override
	public boolean test(Mutation mutation) {
		if (mutation instanceof TransactionMutation transactionMutation) {
			this.context.setVersion(transactionMutation.getCatalogVersion(), transactionMutation.getMutationCount());
		} else {
			this.context.advance();
		}

		final int versionComparison = this.versionComparator.compare(this.context.getVersion(), this.sinceVersion);
		return versionComparison >= 0 && (versionComparison != 0 || this.indexComparator.compare(this.context.getIndex(), this.sinceIndex) >= 0);
	}

}
