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
 * histogram index has drifted from the stored entities - the entity bodies themselves are intact. A session write
 * uses {@link #REPORT} when the system property is absent or blank, logging the missing value and continuing.
 * Replay during catalog loading and live trunk incorporation always uses {@link #REPORT} because the write is
 * already committed to the WAL.
 *
 * Other histogram consistency checks - the final add of the values, the bookkeeping of the references awaiting it,
 * the shape of the values - are not governed by this behavior and always fail the write. Cross-entity expression
 * re-evaluation does not reach it either: it removes a contribution only when the index holds it and skips an absent
 * one without a report.
 *
 * **An undocumented escape hatch.** The behavior is chosen by the system property
 * `evitadb.referenceHistogram.missingValueBehavior` and nothing else: it is deliberately absent from the server
 * options, the server configuration file and the user documentation. It is read once per engine start by
 * {@link #fromSystemProperty()}, which the engine's constructor calls, and the value travels from the engine through
 * the catalog to every session write collector, so it cannot change under a running engine. The
 * property is temporary and will be removed in a later release, together with this choice.
 *
 * Operational effects of {@link #REPORT}:
 *
 * - every value the engine cannot withdraw produces one error line starting with `Cannot withdraw histogram value`,
 *   which is the text to alert on;
 * - an alive catalog applies a write in its session and replays it during incorporation. Each application reports
 *   the values missing when it runs;
 * - a write accepted under `REPORT` and not yet covered by a checkpoint is replayed from the write-ahead log when the
 *   engine starts after an unclean shutdown, and the replay reports the same missing value again under either
 *   behavior - no property is needed to load the catalog. The clean close of that engine publishes the replayed
 *   state, so a later start has nothing left to replay.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
public enum MissingHistogramValueBehavior {

	/**
	 * The session write fails with an error naming the entity, the reference, the histogram and the missing value.
	 * Selected by explicitly setting the property to `REFUSE`.
	 */
	REFUSE,
	/**
	 * The write logs the same identification at error level, skips the withdrawal of that one value and continues.
	 * Selected when the property is absent, blank or explicitly set to `REPORT`.
	 * Where the index holding the histograms of the reference is missing altogether - the histogram store of the
	 * group, or the referenced-type index of an ungrouped reference - every value the withdrawal expected there is
	 * reported too. The final add of the reference's new value then runs its own checks, which this behavior does not
	 * govern: it creates the missing store of a group the group-type index tracks, as the first value of a group
	 * does, and it fails the write when the group-type index is missing or does not track the group, or when the
	 * referenced-type index of an ungrouped reference is missing. A write that adds nothing there, such as the removal
	 * of the reference, passes. The price is a histogram that may stay wrong where the write itself does not repair
	 * it, until the catalog is rebuilt.
	 */
	REPORT;

	/**
	 * Undocumented escape hatch choosing the behavior: the name of a constant of this enum, matched case-insensitively
	 * after trimming. An absent or blank value selects {@link #REPORT}. An unrecognized value stops the engine start.
	 */
	static final String PROPERTY = "evitadb.referenceHistogram.missingValueBehavior";

	/**
	 * Resolves the behavior from the system property `evitadb.referenceHistogram.missingValueBehavior`. The engine
	 * calls it once, when it starts, and keeps the result for its whole life. A non-blank setting that names a
	 * behavior produces one INFO line naming the property and selected behavior. An absent or blank setting is silent.
	 *
	 * @return the behavior the property names, or {@link #REPORT} when it is absent or blank
	 * @throws EvitaInvalidUsageException when the property holds a value that names no behavior
	 */
	@Nonnull
	public static MissingHistogramValueBehavior fromSystemProperty() {
		final String value = System.getProperty(PROPERTY);
		final MissingHistogramValueBehavior behavior = resolve(value);
		if (value != null && !value.isBlank()) {
			log.info(
				"The system property `{}` selects `{}` for missing reference-histogram values.", PROPERTY, behavior
			);
		}
		return behavior;
	}

	/**
	 * Resolves the behavior a value of the system property {@link #PROPERTY} names.
	 *
	 * @param value the value of the property, or `null` when it is absent
	 * @return {@link #REPORT} for an absent or blank value, otherwise the constant whose name equals the trimmed value
	 * ignoring case
	 * @throws EvitaInvalidUsageException when the value names no behavior
	 */
	@Nonnull
	static MissingHistogramValueBehavior resolve(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return REPORT;
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
