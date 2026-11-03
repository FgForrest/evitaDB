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
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The only implementation of {@link AnalysisStep}: the name and the parameters, validated and copied, with equality
 * by both and never by the type. One final subclass per combination of places a built-in step serves, so that a
 * factory can return exactly the types its step may be used as; the subclasses add nothing else. Package-private -
 * descriptions are created through {@link AnalysisSteps} and {@link AnalysisStep#of}.
 */
abstract sealed class AnalysisStepDescription implements AnalysisStep
	permits AnalysisStepDescription.Untyped, AnalysisStepDescription.Text, AnalysisStepDescription.NormalizingText,
	AnalysisStepDescription.Tokenizer, AnalysisStepDescription.Splitting, AnalysisStepDescription.Words,
	AnalysisStepDescription.NormalizingWords, AnalysisStepDescription.Normalizing {
	@Serial private static final long serialVersionUID = -5300373434322493906L;
	/**
	 * Nonblank step name.
	 */
	@Nonnull private final String name;
	/**
	 * Unmodifiable copy of the named parameters, in declaration order.
	 */
	@Nonnull private final Map<String, Serializable> parameters;

	/**
	 * Validates names and parameter types and defensively copies the supplied map and its array values.
	 *
	 * @param name       nonblank step name
	 * @param parameters named parameters using supported evitaDB data types
	 */
	AnalysisStepDescription(@Nonnull String name, @Nonnull Map<String, Serializable> parameters) {
		Assert.notNull(name, "Analysis step name must not be null.");
		Assert.isTrue(!name.isBlank(), "Analysis step name must not be blank.");
		Assert.notNull(parameters, "Analysis step parameters must not be null.");
		final Map<String, Serializable> copy = CollectionUtils.createLinkedHashMap(parameters.size());
		parameters.forEach((key, value) -> {
			final AnalysisStepParameter parameter = new AnalysisStepParameter(key, value);
			copy.put(parameter.name(), parameter.value());
		});
		this.name = name;
		this.parameters = Collections.unmodifiableMap(copy);
	}

	@Nonnull
	@Override
	public final String name() {
		return this.name;
	}

	/**
	 * Returns unmodifiable parameters, defensively copying any array values.
	 */
	@Nonnull
	@Override
	public final Map<String, Serializable> parameters() {
		final Map<String, Serializable> copy = CollectionUtils.createLinkedHashMap(this.parameters.size());
		this.parameters.forEach((key, value) -> copy.put(key, AnalysisStepParameter.copyValue(value)));
		return Collections.unmodifiableMap(copy);
	}

	/**
	 * Compares step descriptions by name and parameters, array contents included, whatever their type - a step read
	 * back from storage is equal to the one its factory created.
	 */
	@Override
	public final boolean equals(@Nullable Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof AnalysisStepDescription that) || !this.name.equals(that.name) ||
			!this.parameters.keySet().equals(that.parameters.keySet())) {
			return false;
		}
		for (final Map.Entry<String, Serializable> entry : this.parameters.entrySet()) {
			if (!Objects.deepEquals(entry.getValue(), that.parameters.get(entry.getKey()))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Hashes the description consistently with the type-blind, array-aware equality.
	 */
	@Override
	public final int hashCode() {
		int parametersHash = 0;
		for (final Map.Entry<String, Serializable> entry : this.parameters.entrySet()) {
			parametersHash += entry.getKey().hashCode() ^
				(Arrays.deepHashCode(new Object[]{entry.getValue()}) - 31);
		}
		return 31 * this.name.hashCode() + parametersHash;
	}

	/**
	 * Renders a concise description, for example `remove-characters(characters='- ')`.
	 */
	@Nonnull
	@Override
	public final String toString() {
		return this.parameters.isEmpty() ? this.name : this.name + "(" + this.parameters.entrySet().stream()
			.map(entry -> entry.getKey() + "=" + formatValue(entry.getValue()))
			.collect(Collectors.joining(", ")) + ")";
	}

	/**
	 * Quotes textual values and renders array contents for diagnostics.
	 *
	 * @param value parameter value to render, never `null`
	 * @return the value in the form used by {@link #toString()}: quoted and escaped for strings and characters,
	 *         bracketed for arrays, otherwise its plain string form
	 */
	@Nonnull
	private static String formatValue(@Nonnull Serializable value) {
		if (value instanceof String || value instanceof Character) {
			return "'" + value.toString().replace("\\", "\\\\").replace("'", "\\'") + "'";
		}
		if (value.getClass().isArray()) {
			final String wrapped = Arrays.deepToString(new Object[]{value});
			return wrapped.substring(1, wrapped.length() - 1);
		}
		return value.toString();
	}

	/** A description whose place is not known - read back from storage or received over an external API. */
	static final class Untyped extends AnalysisStepDescription {
		@Serial private static final long serialVersionUID = 4529735209391424618L;

		Untyped(@Nonnull String name, @Nonnull Map<String, Serializable> parameters) {
			super(name, parameters);
		}
	}

	/** A text phase step. */
	static final class Text extends AnalysisStepDescription implements TextAnalysisStep {
		@Serial private static final long serialVersionUID = -2016322187040474046L;

		Text(@Nonnull String name, @Nonnull Map<String, Serializable> parameters) {
			super(name, parameters);
		}
	}

	/** A step serving both the text phase and a normalization, as `remove-characters` does. */
	static final class NormalizingText extends AnalysisStepDescription implements NormalizingTextAnalysisStep {
		@Serial private static final long serialVersionUID = 8125466785934262331L;

		NormalizingText(@Nonnull String name, @Nonnull Map<String, Serializable> parameters) {
			super(name, parameters);
		}
	}

	/** A tokenizer. */
	static final class Tokenizer extends AnalysisStepDescription implements TokenizerAnalysisStep {
		@Serial private static final long serialVersionUID = 6914539917738458771L;

		Tokenizer(@Nonnull String name, @Nonnull Map<String, Serializable> parameters) {
			super(name, parameters);
		}
	}

	/** A further splitting step. */
	static final class Splitting extends AnalysisStepDescription implements SplittingAnalysisStep {
		@Serial private static final long serialVersionUID = -4370418905924003562L;

		Splitting(@Nonnull String name, @Nonnull Map<String, Serializable> parameters) {
			super(name, parameters);
		}
	}

	/** A words phase step. */
	static final class Words extends AnalysisStepDescription implements WordsAnalysisStep {
		@Serial private static final long serialVersionUID = 2311447372213985367L;

		Words(@Nonnull String name, @Nonnull Map<String, Serializable> parameters) {
			super(name, parameters);
		}
	}

	/** A step serving both the words phase and a normalization, as `lowercase` and `ascii-folding` do. */
	static final class NormalizingWords extends AnalysisStepDescription implements NormalizingWordsAnalysisStep {
		@Serial private static final long serialVersionUID = -7468953113390632047L;

		NormalizingWords(@Nonnull String name, @Nonnull Map<String, Serializable> parameters) {
			super(name, parameters);
		}
	}

	/** A normalization step only, as `trim` is. */
	static final class Normalizing extends AnalysisStepDescription implements NormalizingAnalysisStep {
		@Serial private static final long serialVersionUID = 1650861939436045716L;

		Normalizing(@Nonnull String name, @Nonnull Map<String, Serializable> parameters) {
			super(name, parameters);
		}
	}

}
