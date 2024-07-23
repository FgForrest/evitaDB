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

import io.evitadb.exception.GenericEvitaInternalError;

import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;

import static io.evitadb.utils.CollectionUtils.createLinkedHashMap;

/**
 * Implementation of {@link Collector} that is able to combine correctly lists of {@link EntityContentRequire} into
 * single list.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
class EntityContentRequireCombiningCollector implements Collector<EntityContentRequire, Map<Class<? extends EntityContentRequire>, EntityContentRequire>, EntityContentRequire[]> {

	@Override
	public Supplier<Map<Class<? extends EntityContentRequire>, EntityContentRequire>> supplier() {
		return () -> createLinkedHashMap(10);
	}

	@Override
	public BiConsumer<Map<Class<? extends EntityContentRequire>, EntityContentRequire>, EntityContentRequire> accumulator() {
		return (entityContentRequireIndex, entityContentRequire) -> entityContentRequireIndex.merge(
			entityContentRequire.getClass(),
			entityContentRequire,
			EntityContentRequire::combineWith
		);
	}

	@Override
	public BinaryOperator<Map<Class<? extends EntityContentRequire>, EntityContentRequire>> combiner() {
		return (entityContentRequireIndex, entityContentRequireIndex2) -> {
			throw new GenericEvitaInternalError("Cannot combine multiple content require indexes.");
		};
	}

	@Override
	public Function<Map<Class<? extends EntityContentRequire>, EntityContentRequire>, EntityContentRequire[]> finisher() {
		return entityContentRequireIndex -> entityContentRequireIndex.values()
			.stream()
			.map(EntityContentRequire.class::cast)
			.toArray(EntityContentRequire[]::new);
	}

	@Override
	public Set<Characteristics> characteristics() {
		return Set.of();
	}
}
