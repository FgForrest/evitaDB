/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.index.mutation;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.dataType.Scope;
import io.evitadb.index.PartitionKeyMemo;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitionSplit;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Counterfactual input of the read-resolver rule (R6) of `ReferencePartitionAccessGuardTest`: it lives in the package
 * of the write path, so every resolution that honours the read switch of a collection written here must be reported -
 * a write reaches a partition only through its reduced entity index. One member per shape, next to the object-only
 * resolution the write path is meant to use, which must not be reported.
 *
 * Nothing here is ever executed; the members only have to compile into the bytecode shape they are named after.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@SuppressWarnings({"unused", "DataFlowIssue"})
public final class ReadResolverGuardFixture {

	private ReadResolverGuardFixture() {
		// never instantiated
	}

	/**
	 * A virtual resolution by primary key through the resolver itself.
	 *
	 * @param typeIndex any type index
	 * @return the partition
	 */
	@Nullable
	static ReferencePartition resolverReadShape(@Nonnull ReferencedTypeEntityIndex typeIndex) {
		return ReferencePartitions.resolveVirtualIfExists(typeIndex, 1);
	}

	/**
	 * A method reference to the split, whose only trace is the `invokedynamic` call site.
	 *
	 * @return the function
	 */
	@Nonnull
	static BiFunction<Bitmap, ReferencedTypeEntityIndex[], ReferencePartitionSplit> splitReferenceShape() {
		return ReferencePartitions::split;
	}

	/**
	 * A by-key resolution through a memo of the write path's own.
	 *
	 * @return the memo
	 */
	@Nonnull
	static Supplier<PartitionKeyMemo> memoShape() {
		return PartitionKeyMemo::new;
	}

	/**
	 * A read resolution through the planning context of a query.
	 *
	 * @param queryContext any planning context
	 * @return the partition
	 */
	@Nullable
	static ReferencePartition queryContextShape(@Nonnull QueryPlanningContext queryContext) {
		return queryContext.resolvePartitionForReadIfExists("any", Scope.LIVE, "any", 1);
	}

	/**
	 * A read resolution through another collection.
	 *
	 * @param collection any collection
	 * @return the partition
	 */
	@Nullable
	static ReferencePartition collectionShape(@Nonnull EntityCollection collection) {
		return collection.resolvePartitionForReadIfExists(Scope.LIVE, "any", 1, true);
	}

	/**
	 * The object-only resolution the write path is meant to use - never reported.
	 *
	 * @param collection any collection
	 * @return the partition
	 */
	@Nullable
	static ReferencePartition objectOnlyShape(@Nonnull EntityCollection collection) {
		return ReferencePartitions.resolveIfExists(
			collection::getIndexByKeyIfExists, Scope.LIVE, new RepresentativeReferenceKey(new ReferenceKey("any", 1))
		);
	}

}
