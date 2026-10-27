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

import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Map;

/**
 * Factories for analysis descriptions shared by attribute normalization and future analysis APIs.
 * These factories describe steps; implementations belong to the server.
 *
 * The constants are the only definition of the built-in step and parameter names: the server's built-in providers
 * report them, and a Java model can name them in `@AnalysisStepDef`, which needs a compile-time constant. A step from
 * another provider is described with {@link #step(String, AnalysisStepParameter...)}.
 */
public final class AnalysisSteps {
	/** Name of the lowercase step. */
	public static final String LOWERCASE = "lowercase";
	/** Name of the ASCII folding step. */
	public static final String ASCII_FOLDING = "ascii-folding";
	/** Name of the whitespace trimming step. */
	public static final String TRIM = "trim";
	/** Name of the character removal step. */
	public static final String REMOVE_CHARACTERS = "remove-characters";
	/** Parameter naming the characters to remove. */
	public static final String CHARACTERS = "characters";

	/** Prevents instantiation of this factory holder. */
	private AnalysisSteps() {
	}

	/**
	 * Describes lowercasing using the index locale, or the root locale for non-localized values.
	 *
	 * @return description of the `lowercase` step without parameters
	 */
	@Nonnull
	public static AnalysisStep lowercase() {
		return step(LOWERCASE);
	}

	/**
	 * Describes folding accented and other supported characters to their ASCII equivalents.
	 *
	 * @return description of the `ascii-folding` step without parameters
	 */
	@Nonnull
	public static AnalysisStep asciiFolding() {
		return step(ASCII_FOLDING);
	}

	/**
	 * Describes removing leading and trailing whitespace, including non-breaking spaces.
	 *
	 * @return description of the `trim` step without parameters
	 */
	@Nonnull
	public static AnalysisStep trim() {
		return step(TRIM);
	}

	/**
	 * Describes removing every occurrence of the supplied nonempty set of characters.
	 *
	 * @param characters the characters to remove, each of them independently; never `null` or empty
	 * @return description of the `remove-characters` step carrying the `characters` parameter
	 */
	@Nonnull
	public static AnalysisStep removeCharacters(@Nonnull String characters) {
		Assert.notNull(characters, "Characters must not be null.");
		Assert.isTrue(!characters.isEmpty(), "Characters must not be empty.");
		return step(REMOVE_CHARACTERS, parameter(CHARACTERS, characters));
	}

	/**
	 * Describes an installed step without executing it. The server checks provider availability, normalizer safety
	 * and provider-specific parameter restrictions when adopting a schema. Duplicate parameter names are refused here.
	 *
	 * @param name stable provider name, such as `lowercase` or `ascii-folding`
	 * @param parameters named evitaDB values in declaration order; array values are defensively copied
	 * @return immutable description suitable for a normalization chain
	 */
	@Nonnull
	public static AnalysisStep step(
		@Nonnull String name, @Nonnull AnalysisStepParameter... parameters
	) {
		Assert.notNull(parameters, "Analysis parameters must not be null.");
		final Map<String, Serializable> values = CollectionUtils.createLinkedHashMap(parameters.length);
		for (final AnalysisStepParameter parameter : parameters) {
			Assert.notNull(parameter, "Analysis parameter must not be null.");
			Assert.isTrue(!values.containsKey(parameter.name()), "Duplicate analysis parameter: " + parameter.name());
			values.put(parameter.name(), parameter.value());
		}
		return new AnalysisStep(name, values);
	}

	/**
	 * Creates a named parameter for {@link #step(String, AnalysisStepParameter...)}.
	 * Only supported evitaDB scalar values and arrays are accepted; arrays are defensively copied.
	 *
	 * @param name nonblank provider parameter name
	 * @param value non-null supported value
	 * @return immutable named parameter
	 */
	@Nonnull
	public static AnalysisStepParameter parameter(@Nonnull String name, @Nonnull Serializable value) {
		return new AnalysisStepParameter(name, value);
	}
}
