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

package io.evitadb.api.requestResponse.schema.analysis;

import java.io.Serializable;

/**
 * Content of a named fulltext resource in the catalog schema - the data a fulltext analysis step reads through a
 * parameter that names the resource, such as the words of a stop list or the groups of a synonym list.
 *
 * Each step parameter that reads resources accepts exactly one kind of content: `words` of
 * {@link AnalysisSteps#stopWords(FulltextResourceReference...)} and `protected-words` of the stemming steps read a
 * {@link WordList}, `synonyms` of {@link AnalysisSteps#synonyms(FulltextResourceReference...)} reads a
 * {@link SynonymList}. The catalog schema refuses a reference to a resource of the other kind.
 */
public sealed interface FulltextResourceContent extends Serializable permits WordList, SynonymList {

	/**
	 * Returns the kind of this content, which decides the step parameters that may read it.
	 *
	 * @return the kind of the content
	 */
	FulltextResourceKind kind();

	/**
	 * Kinds of fulltext resource content, one per implementation of {@link FulltextResourceContent}.
	 */
	enum FulltextResourceKind {
		/**
		 * A flat list of words - see {@link WordList}.
		 */
		WORD_LIST,
		/**
		 * A list of synonym groups - see {@link SynonymList}.
		 */
		SYNONYM_LIST
	}

}
