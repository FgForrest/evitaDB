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

package io.evitadb.core.query.algebra.fulltext;

import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.index.bitmap.ArrayBitmap;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer;
import io.evitadb.index.fulltext.FulltextPhaseOneScorer.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.function.IntFunction;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies {@link ReferenceOwnerFulltextScoreAccessor} - how the scores of referenced entities are carried over to
 * the owners referencing them - over stub score sources, independently of any dataset.
 *
 * The targets of the fixture are 1, 2 and 3: target 1 is owned by 10 and 11, target 2 by 11 and 12, target 3 by
 * nobody. Target 2 scores better than target 1.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Fulltext scores of referenced entities carried over to their owners")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(FULLTEXT)
class ReferenceOwnerFulltextScoreAccessorTest {

	private static final long WORSE = 1L << 56;
	private static final long BETTER = 2L << 56;
	private static final long BEST = 3L << 56;
	private static final IntFunction<Bitmap> OWNERS = target -> switch (target) {
		case 1 -> new BaseBitmap(10, 11);
		case 2 -> new BaseBitmap(11, 12);
		default -> EmptyBitmap.INSTANCE;
	};

	@Nested
	@DisplayName("Creation")
	class Creation {

		@Test
		@DisplayName("a filter without a score source has nothing to carry over")
		void shouldCreateNothingWithoutScoreSource() {
			assertNull(ReferenceOwnerFulltextScoreAccessor.create(new ConstantFormula(new ArrayBitmap(1, 2)), OWNERS));
		}

		@Test
		@DisplayName("a score source that provides no scores has nothing to carry over")
		void shouldCreateNothingWhenNoSourceProvidesScores() {
			final ScoringFormula silent = new ScoringFormula(new ArrayBitmap(1, 2), Map.of(1, WORSE), false);

			assertNull(ReferenceOwnerFulltextScoreAccessor.create(silent, OWNERS));
		}

	}

	@Nested
	@DisplayName("Scoring")
	class Scoring {

		@Test
		@DisplayName("an owner keeps the best score of the targets it references, ties ordered by primary key")
		void shouldKeepBestScoreOfReferencedTargets() {
			final ReferenceOwnerFulltextScoreAccessor accessor = createAccessor(Map.of(1, WORSE, 2, BETTER, 3, BEST));

			final Result result = accessor.getFulltextScores(new int[]{10, 11, 12, 13}, 4);

			// 11 references both targets and keeps the better one; target 3 scores best but has no owner
			assertArrayEquals(new int[]{11, 12, 10}, result.primaryKeys());
			assertArrayEquals(new long[]{BETTER, BETTER, WORSE}, result.composites());
		}

		@Test
		@DisplayName("owners outside the candidates are dropped and only the top N are returned")
		void shouldReturnTopNOfCandidatesOnly() {
			final ReferenceOwnerFulltextScoreAccessor accessor = createAccessor(Map.of(1, WORSE, 2, BETTER));

			final Result result = accessor.getFulltextScores(new int[]{10, 12}, 1);

			assertArrayEquals(new int[]{12}, result.primaryKeys());
		}

		@Test
		@DisplayName("a matched target the source does not score contributes nothing")
		void shouldIgnoreUnscoredTarget() {
			final ReferenceOwnerFulltextScoreAccessor accessor = createAccessor(Map.of(2, BETTER));

			final Result result = accessor.getFulltextScores(new int[]{10, 11, 12}, 3);

			assertArrayEquals(new int[]{11, 12}, result.primaryKeys());
		}

		/**
		 * Creates the accessor over a target filter matching targets 1, 2 and 3 and scoring them as passed.
		 *
		 * @param composites the composite of each scored target
		 * @return the accessor
		 */
		@Nonnull
		private static ReferenceOwnerFulltextScoreAccessor createAccessor(@Nonnull Map<Integer, Long> composites) {
			final ReferenceOwnerFulltextScoreAccessor accessor = ReferenceOwnerFulltextScoreAccessor.create(
				new ScoringFormula(new ArrayBitmap(1, 2, 3), composites, true), OWNERS
			);
			assertNotNull(accessor);
			return accessor;
		}

	}

	/**
	 * A constant formula standing for the fulltext formula of a nested query: it matches the passed targets and scores
	 * them with fixed composites.
	 */
	private static final class ScoringFormula extends ConstantFormula implements FulltextScoreAccessor {
		/**
		 * The composite of each scored target; a target without one is not scored.
		 */
		@Nonnull private final Map<Integer, Long> composites;
		/**
		 * The answer to {@link #providesFulltextScores()}.
		 */
		private final boolean providesScores;

		/**
		 * Creates the formula.
		 *
		 * @param targets        the targets the formula matches
		 * @param composites     the composite of each scored target
		 * @param providesScores whether the formula has scores to give
		 */
		ScoringFormula(@Nonnull Bitmap targets, @Nonnull Map<Integer, Long> composites, boolean providesScores) {
			super(targets);
			this.composites = composites;
			this.providesScores = providesScores;
		}

		@Override
		public boolean providesFulltextScores() {
			return this.providesScores;
		}

		@Nonnull
		@Override
		public Result getFulltextScores(@Nonnull int[] candidates, int topN) {
			final long[] candidateComposites = new long[candidates.length];
			for (int i = 0; i < candidates.length; i++) {
				candidateComposites[i] = this.composites.getOrDefault(candidates[i], 0L);
			}
			return FulltextPhaseOneScorer.selectTopN(candidates, candidateComposites, topN, 0L);
		}
	}

}
