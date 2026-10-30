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

package io.evitadb.performance.fulltext.state;

import javax.annotation.Nullable;

/**
 * How many documents a query word occurs in - one axis of the `TextMatchesQueryBenchmark` matrix.
 *
 * The bounds are the ones the P1 phase-1 measurement used (`FulltextScorerBenchmark`: rare up to 10 documents, medium
 * up to 1,000, high-frequency above that), so a cell of the end-to-end benchmark can be read next to the scorer cell
 * of the same class. One difference is deliberate: P1 classified one dictionary term of one field, while a word here
 * is classified by the documents it matches as a query token - the union over its variant stems and every searched
 * field - because that is the set a query through the planner actually computes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum FulltextFrequencyClass {

	/**
	 * A word matching at most 10 documents.
	 */
	RARE(1, 10),
	/**
	 * A word matching 11 to 1,000 documents.
	 */
	MEDIUM(11, 1_000),
	/**
	 * A word matching more than 1,000 documents.
	 */
	HIGH(1_001, Integer.MAX_VALUE);

	/**
	 * The smallest document count of the class, inclusive.
	 */
	private final int minimumDocumentCount;
	/**
	 * The largest document count of the class, inclusive.
	 */
	private final int maximumDocumentCount;

	/**
	 * Creates the class.
	 *
	 * @param minimumDocumentCount the smallest document count of the class, inclusive
	 * @param maximumDocumentCount the largest document count of the class, inclusive
	 */
	FulltextFrequencyClass(int minimumDocumentCount, int maximumDocumentCount) {
		this.minimumDocumentCount = minimumDocumentCount;
		this.maximumDocumentCount = maximumDocumentCount;
	}

	/**
	 * Returns the class of a word matching the given number of documents.
	 *
	 * @param documentCount how many documents the word matches as a query token
	 * @return the class, or `null` for a word matching no document, which belongs to none
	 */
	@Nullable
	public static FulltextFrequencyClass of(int documentCount) {
		for (final FulltextFrequencyClass frequencyClass : values()) {
			if (documentCount >= frequencyClass.minimumDocumentCount
				&& documentCount <= frequencyClass.maximumDocumentCount) {
				return frequencyClass;
			}
		}
		return null;
	}

}
