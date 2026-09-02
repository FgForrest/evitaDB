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

/**
 * What an exact string predicate needs from an occurrence of the pattern, as far as the substring accelerator is
 * concerned.
 *
 * The trigram index answers exactly one question - *which values contain this pattern somewhere?* - and serves
 * `attributeContains`, `attributeStartsWith` and `attributeEndsWith` from that one answer, each of them narrowing it
 * afterwards with its own exact predicate. This enum is the accelerator's whole view of the difference between them:
 * whether the predicate is satisfied by a mere occurrence, or additionally demands that the occurrence sit at a
 * particular end of the value.
 *
 * It exists so that {@link TrigramSubstringSearch#match} can tell the one case where verification is provably
 * redundant from the cases where it is load-bearing, without inspecting a `BiPredicate` it cannot introspect. A
 * caller states the shape of the predicate it is handing over; a `boolean` in that position would read as
 * `match(..., true)` at the call site and would silently return wrong answers if it were ever paired with the wrong
 * predicate.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum StringSearchShape {

	/**
	 * The predicate accepts the pattern **anywhere** in the value - plain containment, `attributeContains`.
	 *
	 * This is the shape that makes the accelerator's candidate set and the query's answer coincide when the pattern
	 * is exactly one trigram wide; see {@link TrigramSubstringSearch#match}.
	 */
	CONTAINMENT,

	/**
	 * The predicate demands the occurrence sit at a particular end of the value - `attributeStartsWith` and
	 * `attributeEndsWith`.
	 *
	 * Containing the pattern is necessary but not sufficient here, so every candidate the intersection nominates must
	 * still be verified exactly, however narrow the pattern is. This is the safe answer whenever a caller is unsure:
	 * it costs the verification pass it would otherwise have paid anyway, and it can never widen an answer.
	 */
	ANCHORED

}
