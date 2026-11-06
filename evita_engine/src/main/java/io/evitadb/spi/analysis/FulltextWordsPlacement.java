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

import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The place of a words step in the words phase of one side: the whole phase as it acts on that side - every step
 * with its traits, in order - and the position of the step being checked. Handed to
 * {@link FulltextWordsAnalysisOperation#verifyPlacement(FulltextWordsPlacement)}; a step left out of the side (one
 * acting on the other side only) is not part of it.
 *
 * @param side     the side the chain is built for
 * @param steps    the steps of the words phase acting on the side, in order, the checked one included
 * @param position the index of the checked step in `steps`
 */
public record FulltextWordsPlacement(
	@Nonnull FulltextAnalysisSide side,
	@Nonnull List<PlacedStep> steps,
	int position
) {

	/**
	 * Validates the position and keeps an immutable copy of the steps.
	 *
	 * @param side     the side the chain is built for
	 * @param steps    the steps of the words phase acting on the side, in order, the checked one included
	 * @param position the index of the checked step in `steps`
	 */
	public FulltextWordsPlacement {
		Assert.notNull(side, "Side must not be null.");
		Assert.notNull(steps, "Steps must not be null.");
		Assert.isPremiseValid(
			position >= 0 && position < steps.size(), "Position " + position + " is outside the words phase."
		);
		steps = List.copyOf(steps);
	}

	/**
	 * Returns the steps after the checked one, in order.
	 *
	 * @return the following steps, possibly none
	 */
	@Nonnull
	public List<PlacedStep> following() {
		return this.steps.subList(this.position + 1, this.steps.size());
	}

	/**
	 * Tells whether the checked step is the last one of the words phase on the side.
	 *
	 * @return `true` when no step follows it
	 */
	public boolean isLast() {
		return this.position == this.steps.size() - 1;
	}

	/**
	 * Returns the first step before the checked one that has the trait.
	 *
	 * @param trait the trait looked for
	 * @return the first preceding step having it, empty when none has
	 */
	@Nonnull
	public Optional<PlacedStep> findPreceding(@Nonnull FulltextAnalysisTrait trait) {
		for (int i = 0; i < this.position; i++) {
			final PlacedStep step = this.steps.get(i);
			if (step.traits().contains(trait)) {
				return Optional.of(step);
			}
		}
		return Optional.empty();
	}

	/**
	 * One step of the words phase as its neighbours see it.
	 *
	 * @param name   the name of the step, as the analyzer names it
	 * @param traits the traits the step declares on the side
	 */
	public record PlacedStep(@Nonnull String name, @Nonnull Set<FulltextAnalysisTrait> traits) {

		/**
		 * Keeps an immutable copy of the traits.
		 *
		 * @param name   the name of the step, as the analyzer names it
		 * @param traits the traits the step declares on the side
		 */
		public PlacedStep {
			Assert.notNull(name, "Name must not be null.");
			Assert.notNull(traits, "Traits must not be null.");
			traits = Set.copyOf(traits);
		}
	}

}
