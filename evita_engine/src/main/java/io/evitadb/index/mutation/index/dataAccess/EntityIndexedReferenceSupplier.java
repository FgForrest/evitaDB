/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.index.mutation.index.dataAccess;


import io.evitadb.api.requestResponse.data.Droppable;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.dataType.Scope;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * EntityReferenceSupplier is responsible for providing access to references held in {@link Entity} data structure for
 * purpose of changing the entity scope. This implementation returns only those references that are marked as indexed
 * in the entity schema.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@RequiredArgsConstructor
public class EntityIndexedReferenceSupplier implements ReferenceSupplier {
	/**
	 * Entity instance that holds the references.
	 */
	private final Entity entity;
	/**
	 * The scope the "indexed" flag should be checked against.
	 */
	private final Scope scope;
	/**
	 * This field is initialized on first call to {@link #getReferenceKeys()} and memoizes the result.
	 */
	private Set<ReferenceKey> memoizedReferenceKeys;

	@Nonnull
	@Override
	public Stream<ReferenceKey> getReferenceKeys() {
		if (this.memoizedReferenceKeys == null) {
			this.memoizedReferenceKeys = this.entity.getReferences()
				.stream()
				.filter(Droppable::exists)
				.map(ReferenceContract::getReferenceKey)
				.filter(it -> this.entity.getSchema().getReference(it.referenceName()).map(ref -> ref.isIndexedInScope(this.scope)).orElse(false))
				.collect(Collectors.toSet());
		}
		return this.memoizedReferenceKeys.stream();
	}

}
