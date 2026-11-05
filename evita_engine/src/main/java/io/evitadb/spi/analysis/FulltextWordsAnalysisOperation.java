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

import org.apache.lucene.analysis.TokenStream;

import javax.annotation.Nonnull;
import java.util.Set;

/**
 * What a {@link FulltextWordsAnalysisStepFactory words phase step} contributes to a chain: a filter of the stream of
 * words. Immutable and shared by every chain compiled from the same definition; the stream it returns is created anew
 * for every call.
 *
 * Besides the filter, an operation may declare its {@link FulltextAnalysisTrait traits} and check its place among the
 * other steps of the phase. The compiler calls {@link #verifyPlacement(FulltextWordsPlacement)} of every step of a
 * side once all of them are created, so a step refuses a neighbour it cannot work with before any text is analysed -
 * the Czech stemmer refuses a fold before it, because it decides by the diacritics.
 *
 * Operations after the first index stemming boundary must retain custom token attributes through Lucene
 * `captureState` / `restoreState` for the surface lane - the fulltext index's dictionary pairing each word as it
 * entered stemming with the stems it produced - to be complete. An independent attribute source
 * makes the lane unavailable for that value; clearing capture makes it unavailable for that token. Postings still
 * follow this operation unchanged: the lane degrades to incomplete instead of rejecting writes or inventing pairs.
 */
@FunctionalInterface
public interface FulltextWordsAnalysisOperation {

	/**
	 * Wraps the stream of words.
	 *
	 * @param words the words, as the preceding steps left them
	 * @param side  the side the chain is built for, always one the step declared - a step acting on both sides
	 *              differently, as the folding stemmers do, branches on it
	 * @return the wrapped stream
	 */
	@Nonnull
	TokenStream wrap(@Nonnull TokenStream words, @Nonnull FulltextAnalysisSide side);

	/**
	 * Wraps words with the lookup-key normalization embedded in a stemming operation, without the stemming. The
	 * compiler runs it on both sides of the surface lane - on the index words captured before stemming and on the query
	 * words it prepares for surface lookup - so equal spellings must yield byte-identical lookup keys.
	 * A custom composite stemmer overrides it with its normalization only, depending exclusively on index-fingerprinted
	 * parameters and resources. The hook must preserve one token per input; otherwise the index lane omits that token.
	 * Changing this normalization changes persisted lane keys and requires a bump of the factory's
	 * {@link FulltextAnalysisStepFactory#getBehaviourVersion() behaviour version} even when posting terms stay
	 * unchanged. The default is identity; ordinary non-stemming operations use `wrap`.
	 *
	 * @param words the surface words before this operation
	 * @return the normalized, unstemmed stream
	 */
	@Nonnull
	default TokenStream wrapQuerySurface(@Nonnull TokenStream words) {
		return words;
	}

	/**
	 * Returns what the step does to the words on the side, for the other steps of the phase to check against.
	 *
	 * @param side the side the chain is built for
	 * @return the traits of the step on the side, none by default
	 */
	@Nonnull
	default Set<FulltextAnalysisTrait> getTraits(@Nonnull FulltextAnalysisSide side) {
		return Set.of();
	}

	/**
	 * Checks that the step can work in its place: reads the traits of the steps before and after it and refuses a
	 * combination it cannot handle. Accepts any place by default.
	 *
	 * @param placement the words phase of the side and the position of this step in it
	 * @throws io.evitadb.exception.EvitaInvalidUsageException when the step cannot work in its place
	 */
	default void verifyPlacement(@Nonnull FulltextWordsPlacement placement) {
		// a step without requirements on its neighbours accepts any place
	}

}
