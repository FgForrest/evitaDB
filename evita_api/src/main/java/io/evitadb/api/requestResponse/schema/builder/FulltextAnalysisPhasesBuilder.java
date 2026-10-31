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


package io.evitadb.api.requestResponse.schema.builder;

import io.evitadb.api.requestResponse.schema.FulltextAnalysisPhasesEditor;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Arrays;
import java.util.List;

/**
 * Collects the phases of a fulltext analyzer for every language or for one, for {@link FulltextAnalyzerSchemaBuilder}.
 * A phase never set stays undefined.
 */
@NotThreadSafe
final class FulltextAnalysisPhasesBuilder implements FulltextAnalysisPhasesEditor {
	/**
	 * The steps of the text phase, or `null` while the phase is undefined.
	 */
	@Nullable private List<AnalysisStep> text;
	/**
	 * The steps of the words phase, or `null` while the phase is undefined.
	 */
	@Nullable private List<AnalysisStep> words;

	@Nonnull
	@Override
	public FulltextAnalysisPhasesEditor text(@Nonnull AnalysisStep... steps) {
		this.text = toList(steps);
		return this;
	}

	@Nonnull
	@Override
	public FulltextAnalysisPhasesEditor words(@Nonnull AnalysisStep... steps) {
		this.words = toList(steps);
		return this;
	}

	/**
	 * Returns the collected phases.
	 *
	 * @return the phases, undefined where never set
	 */
	@Nonnull
	FulltextAnalysisPhases toPhases() {
		return new FulltextAnalysisPhases(this.text, this.words);
	}

	/**
	 * Validates the steps of one phase and copies them into a list.
	 *
	 * @param steps the steps in execution order
	 * @return the steps as a list
	 */
	@Nonnull
	static List<AnalysisStep> toList(@Nonnull AnalysisStep[] steps) {
		Assert.notNull(steps, "Steps must not be null.");
		for (final AnalysisStep step : steps) {
			Assert.notNull(step, "An analysis step must not be null.");
		}
		return List.copyOf(Arrays.asList(steps));
	}

}
