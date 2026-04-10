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

package io.evitadb.api.query.require;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;

/**
 * A Java {@link Collector} that reduces a stream of {@link EntityContentRequire} instances into the minimal
 * non-redundant array of requirements by applying the same merging logic as
 * {@link DefaultPrefetchRequirementCollector}.
 *
 * This collector is used whenever a stream of heterogeneous content requirements needs to be collapsed into a
 * deduplicated set. The primary use-case is combining the requirements from two {@link EntityFetchRequire}
 * instances when computing their union via {@link EntityFetch#combineWith} / {@link EntityGroupFetch#combineWith}:
 *
 * ```java
 * EntityContentRequire[] combined = Stream.concat(
 *     Arrays.stream(firstFetch.getRequirements()),
 *     Arrays.stream(secondFetch.getRequirements())
 * ).collect(new EntityContentRequireCombiningCollector());
 * ```
 *
 * **Accumulator:** delegates to `FetchRequirementCollector::addRequirementsToPrefetch`, which handles merging
 * and deduplication internally via `DefaultPrefetchRequirementCollector`.
 *
 * **Combiner:** merges two partial collectors by adding all requirements from the second into the first (suitable
 * for use with parallel streams, though most callers use sequential streams).
 *
 * **Characteristics:** none — the collector is not concurrent, does not preserve encounter order after merging,
 * and does not support `IDENTITY_FINISH` (the finisher converts the collector to an array).
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NotThreadSafe
class EntityContentRequireCombiningCollector implements Collector<EntityContentRequire, FetchRequirementCollector, EntityContentRequire[]> {

	/**
	 * Creates a new collector instance to accumulate requirements.
	 *
	 * @return a supplier that creates a new FetchRequirementCollector
	 */
	@Override
	public Supplier<FetchRequirementCollector> supplier() {
		return DefaultPrefetchRequirementCollector::new;
	}

	/**
	 * Returns the accumulator function that adds a requirement to the collector.
	 *
	 * @return a bi-consumer that adds a requirement to the collector
	 */
	@Override
	public BiConsumer<FetchRequirementCollector, EntityContentRequire> accumulator() {
		return FetchRequirementCollector::addRequirementsToPrefetch;
	}

	/**
	 * Returns the combiner function that merges two collectors into one.
	 *
	 * @return a binary operator that combines two collectors
	 */
	@Override
	public BinaryOperator<FetchRequirementCollector> combiner() {
		return (collector1, collector2) -> {
			collector1.addRequirementsToPrefetch(collector2.getRequirementsToPrefetch());
			return collector1;
		};
	}

	/**
	 * Returns the finisher function that extracts the final array of requirements.
	 *
	 * @return a function that converts the collector to an array of requirements
	 */
	@Override
	public Function<FetchRequirementCollector, EntityContentRequire[]> finisher() {
		return FetchRequirementCollector::getRequirementsToPrefetch;
	}

	/**
	 * Returns the set of characteristics for this collector.
	 *
	 * @return an empty set, as this collector has no special characteristics
	 */
	@Override
	public Set<Characteristics> characteristics() {
		return Set.of();
	}

}
