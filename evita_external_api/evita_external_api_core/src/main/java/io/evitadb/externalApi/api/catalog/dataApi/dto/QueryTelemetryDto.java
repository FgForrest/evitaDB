/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.externalApi.api.catalog.dataApi.dto;

import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * External API DTO for {@link QueryTelemetry}.
 *
 * Unlike {@link QueryTelemetry#getStart()}, which carries the raw server side {@link System#nanoTime()} reading,
 * the `start` of this DTO is normalized to the number of nanoseconds elapsed since the root step began - the root
 * therefore always reports `0`. The raw reading has no defined epoch and is taken on the server, which makes it
 * meaningless to a remote client; only the offset is.
 *
 * This DTO also derives `selfTime`, which the engine object does not carry. A parent's `spentTime` is *not* the sum of
 * its children's - the children do not tile the parent - so a client that assumes they do renders the profile wrong.
 * Deriving the difference here means every remote client gets it without the engine having to track it.
 *
 * Both derivations happen here rather than in the engine on purpose: they are presentation concerns of the remote
 * APIs, and paying for them on the embedded path - which needs neither - would tax every telemetry-enabled query.
 *
 * `metrics`, by contrast, is **not** derived - it is passed through from what the engine measured, merely reshaped
 * from the compact primitive array the engine stores into the named fields clients generate their code from. That
 * difference matters when reading the two: a derived value can be recomputed by anyone holding the tree, a measured
 * one cannot.
 *
 * @param operation          {@link QueryTelemetry.QueryPhase} this step measured, by its enum name
 * @param start              nanoseconds elapsed between the start of the root step and the start of this one, so the
 *                           root itself always reports `0`
 * @param steps              child steps this phase decomposed into, converted recursively and normalized against the
 *                           very same root; empty for a phase that was not decomposed further
 * @param arguments          human readable details of the phase - for example the index that was selected and its
 *                           estimated cost
 * @param spentTime          duration of this step in nanoseconds, covering the step and everything nested below it
 * @param formattedSpentTime `spentTime` rendered for humans (e.g. `16.6 ms`), so that clients do not each invent
 *                           their own nanosecond formatting
 * @param selfTime           duration in nanoseconds this step spent on its own work - see {@link #selfTimeOf}
 * @param formattedSelfTime  `selfTime` rendered for humans, in the same form as `formattedSpentTime`
 * @param metrics            typed numeric measurements recorded for this step, or `null` when it carries none - which
 *                           is the case for every node but the root today
 * @param startedAt          wall-clock instant the query began in ISO-8601 offset date-time form, carried by the
 *                           root step only and `null` on every other node
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public record QueryTelemetryDto(@Nonnull String operation,
								long start,
                                @Nonnull List<QueryTelemetryDto> steps,
                                @Nonnull List<String> arguments,
                                long spentTime,
                                @Nonnull String formattedSpentTime,
								long selfTime,
								@Nonnull String formattedSelfTime,
								@Nullable QueryTelemetryMetricsDto metrics,
                                @Nullable String startedAt) {

	/**
	 * Converts the passed telemetry tree to its DTO form.
	 *
	 * @param queryTelemetry **root** of the telemetry tree - the start of this very node becomes the zero point
	 *                       every other node in the tree is expressed against
	 * @return the converted tree, whose root reports a `start` of `0`
	 */
	@Nonnull
	public static QueryTelemetryDto from(@Nonnull QueryTelemetry queryTelemetry) {
		return from(queryTelemetry, queryTelemetry.getStart());
	}

	/**
	 * Recursively converts a single node of the telemetry tree, normalizing its start against the root step.
	 *
	 * The root start is threaded down through the whole recursion rather than re-read at each level, so that every
	 * node in the tree ends up expressed against the same zero point - normalizing against the immediate parent
	 * instead would make the offsets impossible to compare across branches.
	 *
	 * @param queryTelemetry node to be converted
	 * @param rootStart      raw `nanoTime` reading of the root step of the entire tree
	 * @return the converted node, together with everything nested below it
	 */
	@Nonnull
	private static QueryTelemetryDto from(@Nonnull QueryTelemetry queryTelemetry, long rootStart) {
		final long spentTime = queryTelemetry.getSpentTime();
		final long selfTime = selfTimeOf(queryTelemetry);
		return new QueryTelemetryDto(
			queryTelemetry.getOperation().toString(),
			queryTelemetry.getStart() - rootStart,
			queryTelemetry.getSteps().stream().map(it -> from(it, rootStart)).toList(),
			Arrays.stream(queryTelemetry.getArguments()).map(Object::toString).toList(),
			spentTime,
			StringUtils.formatNano(spentTime),
			selfTime,
			StringUtils.formatNano(selfTime),
			// null for every step the engine recorded no measurement on, which today is every step but the root
			QueryTelemetryMetricsDto.from(queryTelemetry),
			// only the root step carries the wall-clock stamp that anchors the whole tree in time
			queryTelemetry.getStartedAt() == null ?
				null : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(queryTelemetry.getStartedAt())
		);
	}

	/**
	 * Returns the time this step spent on its own work - its `spentTime` less the time accounted for by its direct
	 * children.
	 *
	 * Steps nest on a stack and therefore never overlap, so the children can never outlast the parent and the result
	 * cannot legitimately be negative. It is clamped at zero regardless, because a telemetry tree that was assembled
	 * by hand (deserialization, tests) carries no such guarantee and a negative duration would be nonsense to render.
	 *
	 * @param queryTelemetry node whose self time is computed
	 * @return nanoseconds this step spent outside of its direct children, never negative
	 */
	private static long selfTimeOf(@Nonnull QueryTelemetry queryTelemetry) {
		long childrenTime = 0;
		for (final QueryTelemetry step : queryTelemetry.getSteps()) {
			childrenTime += step.getSpentTime();
		}
		return Math.max(0, queryTelemetry.getSpentTime() - childrenTime);
	}
}
