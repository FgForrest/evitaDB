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

package io.evitadb.index.mutation.local;

import io.evitadb.exception.EvitaInvalidUsageException;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * What a write does when it has to withdraw a reference-histogram value that the index does not hold for the entity.
 *
 * Before a write changes a reference whose attribute feeds a reference histogram, the engine withdraws the value the
 * reference contributed so far, and it asserts that the value is there. A value that is missing means the derived
 * histogram index has drifted from the stored entities - the entity bodies themselves are intact. The engine is
 * expected to keep its histograms consistent, so such drift stops the write by default.
 *
 * Other histogram consistency checks - the final add of the values, the bookkeeping of the references awaiting it,
 * the shape of the values - are not governed by this behavior and always fail the write.
 *
 * **An undocumented escape hatch.** The behavior is chosen by the system property
 * `evitadb.referenceHistogram.missingValueBehavior` and nothing else: it is deliberately absent from the server
 * options, the server configuration file and the user documentation. It is read once per engine start by
 * {@link #fromSystemProperty()}, which the engine's constructor calls, and the value travels from the engine through
 * the catalog and the collection into every index executor, so it cannot change under a running engine. The
 * property is temporary and will be removed in a later release, together with this choice.
 *
 * What an operator who sets `REPORT` needs to know:
 *
 * - every value the engine cannot withdraw produces one error line starting with `Cannot withdraw histogram value`,
 *   which is the text to alert on;
 * - a line is logged per application of the write, and a write to an alive catalog is applied twice - once in its
 *   session and once more when its transaction is incorporated into the catalog - so it logs two lines per value;
 * - a write accepted under `REPORT` and not yet covered by a checkpoint is replayed from the write-ahead log when the
 *   engine starts after an unclean shutdown; an engine started without the property meets the same missing value in
 *   the replay and marks the catalog corrupted. After an unclean shutdown, start the engine with `REPORT` and drop
 *   the property only at a later start, once that engine has loaded the catalog and closed cleanly - the clean close
 *   publishes the replayed state.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
public enum MissingHistogramValueBehavior {

	/**
	 * The write fails with an error naming the entity, the reference, the histogram and the missing value. Chosen when
	 * the property is absent or blank.
	 */
	REFUSE,
	/**
	 * The write logs the same identification at error level, skips the withdrawal of that one value and continues.
	 * Where the index the histogram lives in is missing altogether, its values are reported too, and the histogram
	 * checks fail the write only if it goes on to add a value into that index - the reference's new value, typically -
	 * on a check this behavior does not govern; a write that adds nothing there, such as the removal of the reference,
	 * passes them. The exception is a group index that the catalog still lists for the group but no longer holds: a
	 * write reaching it fails under either behavior and nothing is reported for it. The price is a histogram that may
	 * stay wrong where the write itself does not repair it, until the catalog is rebuilt.
	 */
	REPORT;

	/**
	 * Undocumented escape hatch choosing the behavior: the name of a constant of this enum, matched case-insensitively
	 * after trimming. An absent or blank value means {@link #REFUSE}; any other value stops the engine start.
	 */
	static final String PROPERTY = "evitadb.referenceHistogram.missingValueBehavior";

	/**
	 * Resolves the behavior from the system property `evitadb.referenceHistogram.missingValueBehavior`. The engine
	 * calls it once, when it starts, and keeps the result for its whole life. When the result is {@link #REPORT}, one
	 * warning says so, naming the property, because the escape hatch is open and the operator must know it.
	 *
	 * @return the behavior the property names, or {@link #REFUSE} when it is absent or blank
	 * @throws EvitaInvalidUsageException when the property holds a value that names no behavior
	 */
	@Nonnull
	public static MissingHistogramValueBehavior fromSystemProperty() {
		final MissingHistogramValueBehavior behavior = resolve(System.getProperty(PROPERTY));
		if (behavior == REPORT) {
			log.warn(
				"The system property `{}` is `{}`: a write that cannot withdraw a reference-histogram value logs the " +
					"value at error level and continues instead of failing. The property is a temporary escape " +
					"hatch and will be removed in a later release.",
				PROPERTY, REPORT
			);
		}
		return behavior;
	}

	/**
	 * Resolves the behavior a value of the system property {@link #PROPERTY} names.
	 *
	 * @param value the value of the property, or `null` when it is absent
	 * @return {@link #REFUSE} for an absent or blank value, otherwise the constant whose name equals the trimmed value
	 * ignoring case
	 * @throws EvitaInvalidUsageException when the value names no behavior
	 */
	@Nonnull
	static MissingHistogramValueBehavior resolve(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return REFUSE;
		}
		final String trimmedValue = value.strip();
		for (final MissingHistogramValueBehavior behavior : values()) {
			if (behavior.name().equalsIgnoreCase(trimmedValue)) {
				return behavior;
			}
		}
		throw new EvitaInvalidUsageException(
			"The system property `" + PROPERTY + "` has the value `" + value + "`, which names no behavior on " +
				"a missing reference-histogram value: use `" + REFUSE + "` or `" + REPORT + "`."
		);
	}

}
