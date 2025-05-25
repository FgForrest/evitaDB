/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.requestResponse.extraResult;

import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This DTO contains detailed information about query processing time and its decomposition to single operations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
@EqualsAndHashCode
@NotThreadSafe
public class QueryTelemetry implements EvitaResponseExtraResult {
	@Serial private static final long serialVersionUID = 4135633155110416711L;

	/**
	 * Phase of the query processing.
	 */
	@Getter private final QueryPhase operation;
	/**
	 * Date and time of the start of this step in nanoseconds.
	 */
	@Getter private final long start;
	/**
	 * Internal steps of this telemetry step (operation decomposition).
	 */
	@Getter private final List<QueryTelemetry> steps = new LinkedList<>();
	/**
	 * Arguments of the processing phase.
	 */
	@Getter private String[] arguments;
	/**
	 * Duration in nanoseconds.
	 */
	@Getter private long spentTime;

	/**
	 * This constructor should be used when the query telemetry is built up from scratch.
	 */
	public QueryTelemetry(@Nonnull QueryPhase operation, @Nonnull String... arguments) {
		this.operation = operation;
		this.arguments = arguments;
		this.start = System.nanoTime();
	}

	/**
	 * This constructor should be used for query telemetry deserialization.
	 */
	public QueryTelemetry(@Nonnull QueryPhase operation, long start, long spentTime, @Nonnull String[] arguments, @Nonnull QueryTelemetry[] steps) {
		this.operation = operation;
		this.start = start;
		this.spentTime = spentTime;
		this.arguments = arguments;
		for (QueryTelemetry step : steps) {
			addStep(step);
		}
	}

	/**
	 * Finalizes current step of the query telemetry and stores the time spent.
	 */
	@Nonnull
	public QueryTelemetry finish(@Nonnull String... arguments) {
		this.spentTime += (System.nanoTime() - this.start);
		Assert.isTrue(ArrayUtils.isEmpty(this.arguments), "Arguments have been already set!");
		this.arguments = arguments;
		return this;
	}

	/**
	 * Adds internal step of query processing in current phase.
	 */
	@Nonnull
	public QueryTelemetry addStep(@Nonnull QueryPhase operation, @Nonnull String... arguments) {
		final QueryTelemetry step = new QueryTelemetry(operation, arguments);
		this.steps.add(step);
		return step;
	}

	/**
	 * Adds internal step of query processing in current phase.
	 */
	public void addStep(@Nonnull QueryTelemetry step) {
		this.steps.add(step);
	}

	/**
	 * Finalizes current step of the query telemetry and stores the time spent.
	 */
	@Nonnull
	public QueryTelemetry finish() {
		this.spentTime += (System.nanoTime() - this.start);
		return this;
	}

	@Override
	public String toString() {
		return toString(0);
	}

	/**
	 * Returns a string representation of the QueryTelemetry object with an indentation level.
	 *
	 * @param indent the number of spaces to indent the string
	 * @return a string representation of the QueryTelemetry object
	 */
	public String toString(int indent) {
		final StringBuilder sb = new StringBuilder(" ".repeat(indent));
		sb.append(this.operation);
		if (this.arguments.length > 0) {
			sb.append("(")
				.append(Arrays.stream(this.arguments).map(Object::toString).collect(Collectors.joining(", ")))
				.append(") ");
		}
		sb.append(": ").append(StringUtils.formatNano(this.spentTime)).append("\n");
		if (!this.steps.isEmpty()) {
			for (QueryTelemetry step : this.steps) {
				sb.append(step.toString(indent + 5));
			}
		}
		return sb.toString();
	}

	/**
	 * Enum contains all query execution phases, that leads from request to response.
	 */
	public enum QueryPhase {

		/**
		 * Entire query execution time.
		 */
		OVERALL,
		/**
		 * Entire planning phase of the query execution.
		 */
		PLANNING,
		/**
		 * Planning phase of the inner query execution.
		 */
		PLANNING_NESTED_QUERY,
		/**
		 * Determining which indexes should be used.
		 */
		PLANNING_INDEX_USAGE,
		/**
		 * Creating formula for filtering entities.
		 */
		PLANNING_FILTER,
		/**
		 * Creating formula for nested query.
		 */
		PLANNING_FILTER_NESTED_QUERY,
		/**
		 * Creating alternative formula for filtering entities.
		 */
		PLANNING_FILTER_ALTERNATIVE,
		/**
		 * Creating formula for sorting result entities.
		 */
		PLANNING_SORT,
		/**
		 * Creating alternative formula for sorting result entities.
		 */
		PLANNING_SORT_ALTERNATIVE,
		/**
		 * Creating factories for requested extra results.
		 */
		PLANNING_EXTRA_RESULT_FABRICATION,
		/**
		 * Creating factories for requested extra results based on alternative indexes.
		 */
		PLANNING_EXTRA_RESULT_FABRICATION_ALTERNATIVE,
		/**
		 * Entire query execution phase.
		 */
		EXECUTION,
		/**
		 * Prefetching entities that should be examined instead of consulting indexes.
		 */
		EXECUTION_PREFETCH,
		/**
		 * Computing entities that should be returned in output (filtering).
		 */
		EXECUTION_FILTER,
		/**
		 * Computing entities within nested query that should be returned in output (filtering).
		 */
		EXECUTION_FILTER_NESTED_QUERY,
		/**
		 * Sorting output entities and slicing requested page.
		 */
		EXECUTION_SORT_AND_SLICE,
		/**
		 * Fabricating requested extra results.
		 */
		EXTRA_RESULTS_FABRICATION,
		/**
		 * Fabricating requested single extra result.
		 */
		EXTRA_RESULT_ITEM_FABRICATION,
		/**
		 * Fetching rich data from the storage based on computed entity primary keys.
		 */
		FETCHING,
		/**
		 * Fetching referenced entities and entity groups from the storage based on referenced primary keys information.
		 */
		FETCHING_REFERENCES,
		/**
		 * Fetching parent entities from the storage based on parent primary keys information.
		 */
		FETCHING_PARENTS

	}
}
