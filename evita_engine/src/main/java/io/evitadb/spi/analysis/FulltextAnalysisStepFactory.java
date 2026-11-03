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

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * Marks a factory as usable in a fulltext analysis chain. The phase the step belongs to is its type, one of four:
 *
 * - {@link FulltextTextAnalysisStepFactory} acts on the whole text before it is split into words (markup stripping);
 * - {@link FulltextTokenizerAnalysisStepFactory} splits the text into words - the first step of a language's word
 *   splitting;
 * - {@link FulltextSplittingAnalysisStepFactory} splits the tokenizer's words again (`word-number-split`), the further
 *   steps of a language's word splitting;
 * - {@link FulltextWordsAnalysisStepFactory} acts on the words one at a time - lowercases, removes or stems them.
 *
 * The interface is sealed to these four, so a step cannot declare a phase its type does not deliver: a words step has
 * no way to return a tokenizer. A class implementing two of them is refused when the registry discovers it.
 *
 * The declarations below decide what a change of the step does to the data already indexed: a change that alters the
 * index side - the step's version, or a parameter in {@link #getIndexSideParameters()} - leaves every field analysed
 * the old way stale until it is reindexed, while a change of a query-side-only step or parameter applies at once.
 * They therefore have to be truthful: a parameter missing from {@link #getIndexSideParameters()} although it changes
 * the index terms makes queries look for terms the index never stored.
 *
 * The factory is discovered through the same `ServiceLoader` file as every {@link AnalysisStepFactory}; a words step
 * may also implement {@link NormalizingAnalysisStepFactory} when the same operation serves both a normalized value and
 * the words of a text, as `lowercase` and `ascii-folding` do.
 *
 * **The operations work on Lucene types on purpose.** Every step acts on Lucene's term buffer anyway, and a
 * `TokenStream` already carries what steps need beyond one word in, one word out: positions and their gaps, offsets,
 * the keyword flag of a protected word and several terms at one position. An abstraction of evitaDB's own would
 * re-model all of that and convert to Lucene and back for every token - an extra layer and extra allocations on the
 * path that is tuned for both. The price is that this interface follows Lucene: a Lucene release that changes these
 * types may change this interface too, and a step written against it may have to be rebuilt. It is a server-side
 * contract, weaker than the client API, which never depends on Lucene - a client describes steps by name and
 * parameters only (`AnalysisStep` in `evita_api`).
 */
public sealed interface FulltextAnalysisStepFactory extends AnalysisStepFactory
	permits FulltextTextAnalysisStepFactory, FulltextTokenizerAnalysisStepFactory, FulltextSplittingAnalysisStepFactory,
	FulltextWordsAnalysisStepFactory {

	/**
	 * Returns the parameters whose value changes the terms the index side produces. Empty for a step acting on the
	 * query side only.
	 *
	 * @return names of the parameters acting on the index side
	 */
	@Nonnull
	Set<String> getIndexSideParameters();

	/**
	 * Returns the version of the step's behaviour. It has to change whenever the step starts producing different index
	 * terms for the same input and parameters, because the fields analysed by the previous version go stale.
	 *
	 * @return the behaviour version, starting at 1
	 */
	int getBehaviourVersion();

}
