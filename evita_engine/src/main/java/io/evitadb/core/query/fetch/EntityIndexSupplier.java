/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.query.fetch;


import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * This class lazily provides (and caches) index of entities by their primary key. The entities always
 * represent the original entity in the evitaDB and not the {@link EntityDecorator} wrapper.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
class EntityIndexSupplier<T extends SealedEntity> implements Supplier<Map<Integer, Entity>> {
	/**
	 * Contains the list of entities rich enough to provide all data required for further processing.
	 */
	private final List<T> richEnoughEntities;
	/**
	 * Contains lazily computed index of entities by their primary key.
	 */
	private Map<Integer, Entity> memoizedResult;

	@Override
	public Map<Integer, Entity> get() {
		if (this.memoizedResult == null) {
			this.memoizedResult = this.richEnoughEntities.stream()
				.collect(
					Collectors.toMap(
						EntityContract::getPrimaryKey,
						it -> it instanceof EntityDecorator entityDecorator ?
							entityDecorator.getDelegate() : (Entity) it
					)
				);
		}
		return this.memoizedResult;
	}
}
