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
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.identity.IdentityExempt;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.identity.KeyAccumulator;

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
	/** Stable discriminator of the detached predicate identity. */
	private static final long CLASS_ID = -4695978231447111610L;
	/** Integer discriminator used by the legacy hash computation. */
	private static final int LEGACY_CLASS_ID = -550857430;
	private final int matchNodeId;
	@IdentityExempt(IdentityExempt.Reason.LEGACY_HASH)
	private final Long hash;

	public MatchNodeIdHierarchyFilteringPredicate(int matchNodeId) {
		this.matchNodeId = matchNodeId;
		this.hash = TransactionalDataRelatedStructure.HASH_FUNCTION.hashInts(
			new int[]{LEGACY_CLASS_ID, matchNodeId}
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

	/** Memoized detached holder key. */
	@IdentityExempt(IdentityExempt.Reason.STATE)
	private FormulaKey memoizedKey;

	/** Returns detached inputs without computing the bitmap. */
	@Nonnull
	@Override
	public IdentityRecord identity() {
		return new Identity(this.matchNodeId);
	}

	/** Returns the memoized detached key of this holder. */
	@Nonnull
	@Override
	public FormulaKey getKey() {
		if (this.memoizedKey == null) {
			this.memoizedKey = FormulaKey.of(identity(), getClassId(), FormulaKey.NO_CHILDREN, this);
		}
		return this.memoizedKey;
	}

	/** Returns the stable holder discriminator. */
	@Override
	public long getClassId() {
		return CLASS_ID;
	}

	/**
	 * Detached computation inputs.
	 *
	 * @param matchNodeId primary key of the matched node
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	public record Identity(int matchNodeId) implements IdentityRecord {
		/** Returns the declaring holder identifier. */
		@Override
		public long classId() {
			return CLASS_ID;
		}

		/** Includes every input in declaration order. */
		@Override
		public void describeTo(@Nonnull KeyAccumulator accumulator) {
			accumulator.add(this.matchNodeId);
		}
	}
}
