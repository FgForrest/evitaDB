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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.analysis.FulltextWordsPlacement.PlacedStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies what a words step sees of its place: the steps before and after it on the side and their traits.
 */
@DisplayName("Place of a words step")
@Tag(ENGINE)
@Tag(FULLTEXT)
class FulltextWordsPlacementTest {
	/** A step without traits. */
	private static final PlacedStep LOWERCASE = new PlacedStep("lowercase", Set.of());
	/** A folding step. */
	private static final PlacedStep FOLDING =
		new PlacedStep("ascii-folding", Set.of(FulltextAnalysisTrait.FOLDS_DIACRITICS));
	/** A stemmer that folds too. */
	private static final PlacedStep STEMMING = new PlacedStep(
		"czech-stemming", Set.of(FulltextAnalysisTrait.FOLDS_DIACRITICS, FulltextAnalysisTrait.STEMS)
	);
	/** The phase the placements here are taken from. */
	private static final List<PlacedStep> PHASE = List.of(LOWERCASE, FOLDING, STEMMING);

	/** The steps before and after the checked one are split at its position, the checked one in neither. */
	@Test
	@DisplayName("should split the phase at the checked step")
	void shouldSplitPhaseAtCheckedStep() {
		final FulltextWordsPlacement placement = new FulltextWordsPlacement(FulltextAnalysisSide.QUERY, PHASE, 1);

		assertEquals(List.of(LOWERCASE), placement.preceding());
		assertEquals(List.of(STEMMING), placement.following());
		assertFalse(placement.isLast());
		assertTrue(new FulltextWordsPlacement(FulltextAnalysisSide.QUERY, PHASE, 2).isLast());
	}

	/** The first preceding step with the trait is found; the checked step and the following ones are not looked at. */
	@Test
	@DisplayName("should find the first preceding step with a trait")
	void shouldFindFirstPrecedingStepWithTrait() {
		final FulltextWordsPlacement last = new FulltextWordsPlacement(FulltextAnalysisSide.INDEX, PHASE, 2);
		final FulltextWordsPlacement middle = new FulltextWordsPlacement(FulltextAnalysisSide.INDEX, PHASE, 1);

		assertEquals(Optional.of(FOLDING), last.findPreceding(FulltextAnalysisTrait.FOLDS_DIACRITICS));
		assertEquals(Optional.empty(), last.findPreceding(FulltextAnalysisTrait.STEMS));
		assertEquals(Optional.empty(), middle.findPreceding(FulltextAnalysisTrait.FOLDS_DIACRITICS));
	}

	/** A position outside the phase is a fault of the compiler, never of a schema. */
	@Test
	@DisplayName("should refuse a position outside the phase")
	void shouldRefusePositionOutsidePhase() {
		assertThrows(
			GenericEvitaInternalError.class,
			() -> new FulltextWordsPlacement(FulltextAnalysisSide.INDEX, PHASE, 3)
		);
		assertThrows(
			GenericEvitaInternalError.class,
			() -> new FulltextWordsPlacement(FulltextAnalysisSide.INDEX, PHASE, -1)
		);
	}

	/** A step changing the list it was handed does not change what the next step sees. */
	@Test
	@DisplayName("should keep an unmodifiable copy of the phase")
	void shouldKeepUnmodifiableCopyOfPhase() {
		final List<PlacedStep> source = new ArrayList<>(PHASE);
		final FulltextWordsPlacement placement = new FulltextWordsPlacement(FulltextAnalysisSide.INDEX, source, 0);
		source.clear();

		assertEquals(PHASE, placement.steps());
		assertThrows(UnsupportedOperationException.class, () -> placement.steps().add(LOWERCASE));
	}
}
