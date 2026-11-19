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
 * What a {@link FulltextWordsAnalysisStepFactory words phase step} does to the words, declared by its operation
 * ({@link FulltextWordsAnalysisOperation#getTraits(FulltextAnalysisSide)}) so that the steps around it can check their
 * place in the chain ({@link FulltextWordsAnalysisOperation#verifyPlacement(FulltextWordsPlacement)}). A step declares
 * the traits it has and reads those of its neighbours; a step without traits declares none and checks nothing.
 */
public enum FulltextAnalysisTrait {

	/**
	 * The words leave the step without diacritics. A step deciding by the diacritics, as the Czech stemmer does,
	 * refuses such a step before it.
	 */
	FOLDS_DIACRITICS,
	/**
	 * The words leave the step stemmed. A stemmer refuses such a step before it, because it would stem a stem.
	 */
	STEMS

}
