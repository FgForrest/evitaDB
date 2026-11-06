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

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Map;

/**
 * Immutable description of an analysis step, without any executable implementation: a step name and its named
 * parameters. Step availability and step-specific parameter semantics are validated by the server.
 *
 * **A description says where it may be used.** The factories in {@link AnalysisSteps} return the type of the place a
 * step belongs to - {@link TextAnalysisStep}, {@link TokenizerAnalysisStep}, {@link SplittingAnalysisStep} or
 * {@link WordsAnalysisStep} for the parts of a fulltext analyzer, {@link NormalizingAnalysisStep} for an attribute's
 * normalization - and every schema editor accepts only the type of its slot, so a step placed where it cannot run
 * does not compile. A step serving two places, such as `lowercase`, is of both types. A step of another provider is
 * described by the generic factory of the place it goes to ({@link AnalysisSteps#wordsStep}, ...): its author states
 * the place and the server verifies it.
 *
 * A description read back from storage or received over an external API carries no such type ({@link #of}); the
 * server checks it like any other. Two descriptions are equal when their names and parameters are, whatever their
 * type.
 */
public sealed interface AnalysisStep extends Serializable
	permits TextAnalysisStep, TokenizerAnalysisStep, SplittingAnalysisStep, WordsAnalysisStep, NormalizingAnalysisStep,
	AnalysisStepDescription {

	/**
	 * Returns the stable name of the provider the step resolves to on the server.
	 *
	 * @return nonblank step name, such as `lowercase` or `stop-words`
	 */
	@Nonnull
	String name();

	/**
	 * Returns the named parameters, defensively copied.
	 *
	 * @return unmodifiable parameters in declaration order
	 */
	@Nonnull
	Map<String, Serializable> parameters();

	/**
	 * Describes a step without saying where it may be used - the form of a description read back from storage or
	 * received over an external API, whose place the server checks.
	 *
	 * @param name       nonblank step name
	 * @param parameters named parameters using supported evitaDB data types; copied, array values included
	 * @return the description
	 */
	@Nonnull
	static AnalysisStep of(@Nonnull String name, @Nonnull Map<String, Serializable> parameters) {
		return new AnalysisStepDescription.Untyped(name, parameters);
	}

}
