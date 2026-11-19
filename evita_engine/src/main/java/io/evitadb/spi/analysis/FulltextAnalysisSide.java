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


package io.evitadb.spi.analysis;

/**
 * The side of the fulltext pipeline a chain is built for. One analyzer serves both sides and each step decides what
 * it does on which: `html-strip` acts on the index side only (a query is what the user typed), `synonyms` on the
 * query side only (so that a synonym list can change without touching the index), and the folding stemming steps act
 * on both, differently - the index side stems and then folds, the query side folds and then expands to every stem
 * variant.
 */
public enum FulltextAnalysisSide {

	/**
	 * The chain turning the text of a searchable field into the terms stored in the index.
	 */
	INDEX,
	/**
	 * The chain turning the text of a query into the terms looked up in the index.
	 */
	QUERY

}
