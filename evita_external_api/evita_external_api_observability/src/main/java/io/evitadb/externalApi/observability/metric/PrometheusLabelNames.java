/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.externalApi.observability.metric;

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Helper for turning an arbitrary, client-supplied query label name into a legal Prometheus dimension name.
 *
 * Prometheus label names must match `[a-zA-Z_][a-zA-Z0-9_]*`, whereas query labels (the `label` query head
 * constraint) may carry any string - including characters like `-` or `.` that are illegal in a dimension name.
 * This single source of truth is shared by the configuration layer (to detect two configured labels collapsing to
 * the same dimension) and by the metric handler (to name the dimension it registers), so the two never drift.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class PrometheusLabelNames {

	private PrometheusLabelNames() {
		throw new UnsupportedOperationException("This class cannot be instantiated.");
	}

	/**
	 * Maps each label name to its {@link #sanitize(String) sanitized} Prometheus dimension, rejecting any clash - both
	 * among the label names themselves and against the {@code reservedDimensions} already occupied by a metric's
	 * built-in dimensions (e.g. `entityType`, `prefetched`). A clash is a misconfiguration the operator must fix (two
	 * different label names cannot share a Prometheus dimension), hence {@link EvitaInvalidUsageException}.
	 *
	 * The configuration layer calls this with an empty {@code reservedDimensions} to validate the operator's list in
	 * isolation; the metric handler calls it again at registration seeded with the event's fixed dimension names,
	 * where a clash with a built-in dimension - undetectable at config-load time - is finally caught.
	 *
	 * @param labelNames         the label names to map, in the desired dimension order
	 * @param reservedDimensions dimension names already in use that a label must not collide with (may be empty)
	 * @return an insertion-ordered map from each label name to its Prometheus dimension
	 * @throws EvitaInvalidUsageException if two label names, or a label name and a reserved dimension, collide
	 */
	@Nonnull
	public static Map<String, String> assignDimensions(
		@Nonnull Collection<String> labelNames,
		@Nonnull Set<String> reservedDimensions
	) {
		final Map<String, String> dimensionByLabel = CollectionUtils.createLinkedHashMap(labelNames.size());
		final Set<String> usedDimensions = new HashSet<>(reservedDimensions);
		for (final String labelName : labelNames) {
			final String dimension = sanitize(labelName);
			if (!usedDimensions.add(dimension)) {
				final String reason = reservedDimensions.contains(dimension) ?
					"it is already a built-in dimension of this metric" :
					"another exported query label already maps to it";
				throw new EvitaInvalidUsageException(
					"Query label `" + labelName + "` cannot be exported to Prometheus: its dimension `" + dimension +
						"` clashes - " + reason + ". Rename or remove it from `exportedQueryLabels`."
				);
			}
			dimensionByLabel.put(labelName, dimension);
		}
		return dimensionByLabel;
	}

	/**
	 * Sanitizes an arbitrary label name into a legal Prometheus dimension name matching `[a-zA-Z_][a-zA-Z0-9_]*`.
	 * Every character outside `[a-zA-Z0-9_]` is replaced by an underscore, and a leading digit (or an empty result)
	 * is prefixed with an underscore so the name always starts with a letter or underscore.
	 *
	 * @param rawName the raw, possibly-illegal label name (as configured / as carried by the query)
	 * @return a legal Prometheus dimension name
	 */
	@Nonnull
	public static String sanitize(@Nonnull String rawName) {
		final StringBuilder result = new StringBuilder(rawName.length() + 1);
		for (int i = 0; i < rawName.length(); i++) {
			final char character = rawName.charAt(i);
			final boolean alphaNumeric =
				(character >= 'a' && character <= 'z') ||
					(character >= 'A' && character <= 'Z') ||
					(character >= '0' && character <= '9');
			result.append(alphaNumeric || character == '_' ? character : '_');
		}
		if (result.isEmpty() || (result.charAt(0) >= '0' && result.charAt(0) <= '9')) {
			result.insert(0, '_');
		}
		return result.toString();
	}

}
