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

package io.evitadb.index.trigram;

import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;

/**
 * One pattern's trigram postings, read out of the index exactly once and ordered ascending by cardinality.
 *
 * The carrier exists so that the two questions an accelerated substring query asks - *is this index worth taking at
 * all?* and *which value ids could hold the pattern?* - are answered from ONE set of tree descents. They used to be
 * answered from two: the gate priced the pattern by looking up every trigram to find the cheapest posting, discarded
 * what it had found, and the intersection then looked every one of them up again.
 *
 * ## Ownership and mutability
 *
 * Both arrays are **scratch belonging to the query that produced them**. The postings they point at are not: those are
 * the index's own, shared by reference with every index version that has not rewritten them, and are strictly
 * read-only. {@link TrigramIndex#resolveCandidateValueIds(PatternPostings)} honours that - the small-posting path
 * copies the cheapest posting before compacting it - so a carrier can be consumed safely, but it must never be cached,
 * shared between queries, or retained past the query that built it.
 *
 * Being scratch is also why value equality is deliberately left undefined: this is a parameter-passing device, not a
 * value. The record's generated `equals`/`hashCode` compare the arrays by reference, which is meaningless here and is
 * never relied upon.
 *
 * @param postings      the postings themselves, ordered ascending by cardinality, never empty
 * @param cardinalities each posting's cardinality, in the same order and of the same length
 */
public record PatternPostings(
	@Nonnull Object[] postings,
	@Nonnull int[] cardinalities
) {

	public PatternPostings {
		Assert.isPremiseValid(postings.length > 0, "A pattern with no posting must be reported as no match instead!");
		Assert.isPremiseValid(
			postings.length == cardinalities.length,
			"The postings and their cardinalities must be parallel!"
		);
	}

	/**
	 * The most candidates an intersection over these postings could produce.
	 *
	 * An intersection cannot yield more ids than its smallest input holds, and the carrier is ordered, so the bound is
	 * `cardinalities[0]` - read rather than computed. It is never zero: a trigram posting against nothing means the
	 * pattern occurs in no value, which {@link TrigramIndex#pricePattern} reports by answering `null` rather than by
	 * building a carrier.
	 *
	 * @return the cheapest posting's cardinality, always positive
	 */
	public int candidateUpperBound() {
		return this.cardinalities[0];
	}

}
