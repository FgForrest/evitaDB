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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reads a single label's value out of the comma-delimited `name=value` label bag carried by
 * {@link io.evitadb.api.observability.annotation.ExportConfigurableLabels}-annotated JFR event fields (e.g.
 * `FinishedEvent#labels`).
 *
 * A given label is looked up on demand rather than the whole bag being parsed into a map: the lookup runs on the
 * background metric-recording thread (never on the query path), the bag is short, the configured set of exported
 * labels is tiny, and a targeted scan allocates nothing but the returned value - while avoiding any assumption about
 * recorded-event identity reuse that a cross-invocation cache would rely on.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class QueryLabelBag {

	private QueryLabelBag() {
		throw new UnsupportedOperationException("This class cannot be instantiated.");
	}

	/**
	 * Extracts the value of a single label from a comma-delimited `name=value` label bag.
	 *
	 * @param labelBag   the label bag, or {@code null}/empty when the query carried no labels
	 * @param targetName the exact (unsanitized) label name to look up
	 * @return the label's value, or {@code null} when the bag does not carry it
	 */
	@Nullable
	public static String extractValue(@Nullable String labelBag, @Nonnull String targetName) {
		if (labelBag == null || labelBag.isEmpty()) {
			return null;
		}
		final int length = labelBag.length();
		final int targetLength = targetName.length();
		int cursor = 0;
		while (cursor < length) {
			int pairEnd = labelBag.indexOf(',', cursor);
			if (pairEnd < 0) {
				pairEnd = length;
			}
			final int equals = labelBag.indexOf('=', cursor);
			// the name region is [cursor, equals); accept only when it matches targetName exactly and the '=' lies
			// within the current pair (a pair without '=' - or whose '=' belongs to the next pair - is not a match)
			if (equals >= 0 && equals < pairEnd
				&& equals - cursor == targetLength
				&& labelBag.regionMatches(cursor, targetName, 0, targetLength)) {
				return labelBag.substring(equals + 1, pairEnd);
			}
			cursor = pairEnd + 1;
		}
		return null;
	}

}
