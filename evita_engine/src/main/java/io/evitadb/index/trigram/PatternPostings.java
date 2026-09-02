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
 * One pattern's trigram postings, read out of the index exactly once.
 *
 * The carrier exists so that the two questions an accelerated substring query asks - *is this index worth taking at
 * all?* and *which value ids could hold the pattern?* - are answered from ONE set of tree descents. They used to be
 * answered from two: the gate priced the pattern by looking up every trigram to find the cheapest posting, discarded
 * what it had found, and the intersection then looked every one of them up again.
 *
 * ## Why the postings arrive unordered
 *
 * {@link TrigramIndex#resolveCandidateValueIds(PatternPostings)} needs them ordered ascending by cardinality, but the
 * gate does not - it needs only the cheapest cardinality, which one linear pass finds. Ordering is therefore left to
 * the intersection, which is the only step that benefits from it and the only step a declined pattern never reaches.
 * Sorting here instead would charge every declined query for work it then throws away, and the sort is an insertion
 * sort - quadratic on a descending input - so on a long pattern that charge is not a rounding error.
 *
 * ## Why the element type is `Object`
 *
 * A posting is either a sorted `int[]` or a {@link io.evitadb.roaringbitmap.PersistentRoaringBitmap}, chosen per
 * trigram by {@link TrigramPostings#SMALL_POSTING_THRESHOLD} - and one pattern routinely holds both, since its rare
 * trigrams post small while its common ones post large. Java cannot name that union: `int[]` is a primitive array, so
 * it can implement no interface, which leaves `Object` as the only type the two arms share. Generics do not reach it
 * either - a type parameter is fixed per carrier, while this array is heterogeneous inside one.
 *
 * Boxing the small arm into a wrapper under a sealed interface would name the union, at the price of one allocation
 * and one dereference per posting per query - paid on the hot path, and paid precisely on the majority case the
 * `int[]` arm exists to keep cheap (see {@link TrigramPostings}). The array therefore stays untyped, and the two arms
 * are separated by `instanceof` at the three sites in {@link TrigramIndex} that read them. That looseness is confined
 * by this type being package-private, so no `Object[]` of postings is reachable from outside.
 *
 * ## Ownership and mutability
 *
 * Both arrays are **scratch belonging to the query that produced them**, and the intersection reorders them in place.
 * The postings they point at are NOT scratch: those are the index's own, shared by reference with every index version
 * that has not rewritten them, and are strictly read-only.
 * {@link TrigramIndex#resolveCandidateValueIds(PatternPostings)} honours that - the small-posting path copies the
 * cheapest posting before compacting it - so a carrier can be consumed safely, but it must never be cached, shared
 * between queries, or retained past the query that built it.
 *
 * This type is package-private for that reason: everything that may legitimately hold one lives beside it, and a
 * caller outside this package cannot be handed direct references to postings it could corrupt.
 *
 * Being scratch is also why value equality is deliberately left undefined: this is a parameter-passing device, not a
 * value. The record's generated `equals`/`hashCode` compare the arrays by reference, which is meaningless here and is
 * never relied upon.
 *
 * @param postings            the postings themselves - each a sorted `int[]` or a `PersistentRoaringBitmap`, see
 *                            above - in trigram order until the intersection reorders them, never empty
 * @param cardinalities       each posting's cardinality, in the same order and of the same length
 * @param candidateUpperBound the most candidates an intersection over these postings could produce - the cheapest
 *                            posting's cardinality, since an intersection cannot yield more ids than its smallest
 *                            input holds. Always positive: a trigram posting against nothing means the pattern occurs
 *                            in no value, which {@link TrigramIndex#pricePattern} reports by answering `null` rather
 *                            than by building a carrier
 */
record PatternPostings(
	@Nonnull Object[] postings,
	@Nonnull int[] cardinalities,
	int candidateUpperBound
) {

	PatternPostings {
		Assert.isPremiseValid(postings.length > 0, "A pattern with no posting must be reported as no match instead!");
		Assert.isPremiseValid(
			postings.length == cardinalities.length,
			"The postings and their cardinalities must be parallel!"
		);
		Assert.isPremiseValid(
			candidateUpperBound > 0,
			"A pattern that cannot produce a candidate must be reported as no match instead!"
		);
	}

}
