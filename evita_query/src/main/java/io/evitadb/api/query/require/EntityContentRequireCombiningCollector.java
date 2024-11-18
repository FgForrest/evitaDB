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

import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;

/**
 * Implementation of {@link Collector} that is able to combine correctly lists of {@link EntityContentRequire} into
 * single list.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class EntityContentRequireCombiningCollector implements Collector<EntityContentRequire, FetchRequirementCollector, EntityContentRequire[]> {

	@Override
	public Supplier<FetchRequirementCollector> supplier() {
		return DefaultPrefetchRequirementCollector::new;
	}

	@Override
	public BiConsumer<FetchRequirementCollector, EntityContentRequire> accumulator() {
		return FetchRequirementCollector::addRequirementsToPrefetch;
	}

	@Override
	public BinaryOperator<FetchRequirementCollector> combiner() {
		return (collector1, collector2) -> {
			collector1.addRequirementsToPrefetch(collector2.getRequirementsToPrefetch());
			return collector1;
		};
	}

	@Override
	public Function<FetchRequirementCollector, EntityContentRequire[]> finisher() {
		return FetchRequirementCollector::getRequirementsToPrefetch;
	}

	@Override
	public Set<Characteristics> characteristics() {
		return Set.of();
	}

}
