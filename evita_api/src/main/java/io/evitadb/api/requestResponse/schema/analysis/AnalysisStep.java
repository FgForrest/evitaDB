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
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Immutable description of an analysis step, without any executable implementation.
 * Step availability and step-specific parameter semantics are validated by the server.
 *
 * @param name nonblank step name
 * @param parameters named parameters using supported evitaDB data types
 */
public record AnalysisStep(
	@Nonnull String name,
	@Nonnull Map<String, Serializable> parameters
) implements Serializable {

	/**
	 * Validates names and parameter types and defensively copies the supplied map and its array values.
	 */
	public AnalysisStep {
		Assert.notNull(name, "Analysis step name must not be null.");
		Assert.isTrue(!name.isBlank(), "Analysis step name must not be blank.");
		Assert.notNull(parameters, "Analysis step parameters must not be null.");
		final Map<String, Serializable> copy = CollectionUtils.createLinkedHashMap(parameters.size());
		parameters.forEach((key, value) -> {
			final AnalysisStepParameter parameter = new AnalysisStepParameter(key, value);
			copy.put(parameter.name(), parameter.value());
		});
		parameters = Collections.unmodifiableMap(copy);
	}

	/**
	 * Returns unmodifiable parameters, defensively copying any array values.
	 */
	@Nonnull
	@Override
	public Map<String, Serializable> parameters() {
		final Map<String, Serializable> copy = CollectionUtils.createLinkedHashMap(this.parameters.size());
		this.parameters.forEach((key, value) -> copy.put(key, AnalysisStepParameter.copyValue(value)));
		return Collections.unmodifiableMap(copy);
	}

	/**
	 * Compares step descriptions, including array parameter contents.
	 */
	@Override
	public boolean equals(@Nullable Object object) {
		if (this == object) {
			return true;
		}
		if (!(object instanceof AnalysisStep that) || !this.name.equals(that.name) ||
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
	 * Hashes the description consistently with array-aware equality.
	 */
	@Override
	public int hashCode() {
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
	public String toString() {
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
}
