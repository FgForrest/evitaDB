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

package io.evitadb.index.hierarchy.predicate;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * This predicate matches only a hierarchy node with specific id. For all other nodes it returns false.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class MatchNodeIdHierarchyFilteringPredicate implements HierarchyFilteringPredicate, Serializable {
	@Serial private static final long serialVersionUID = -785434923550857430L;
	private static final int CLASS_ID = -550857430;
	private final int matchNodeId;
	private final Long hash;

	public MatchNodeIdHierarchyFilteringPredicate(int matchNodeId) {
		this.matchNodeId = matchNodeId;
		this.hash = TransactionalDataRelatedStructure.HASH_FUNCTION.hashInts(
			new int[]{CLASS_ID, matchNodeId}
		);
	}

	@Override
	public void initializeIfNotAlreadyInitialized(@Nonnull QueryExecutionContext executionContext) {
		// do nothing
	}

	@Override
	public long getHash() {
		return this.hash;
	}

	@Override
	public boolean test(int nodeId) {
		return nodeId == this.matchNodeId;
	}

	@Override
	public String toString() {
		return "MATCH NODE " + this.matchNodeId;
	}
}
