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


import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Optional;

import static java.util.Optional.empty;

/**
 * This implementation looks up to the passed `sealedEntity` for existing referenced entity bodies.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
class ExistingEntityDecoratorProvider implements ExistingEntityProvider {
	/**
	 * The entity decorator to search referenced rich entities in.
	 */
	@Nonnull
	private final EntityDecorator entityDecorator;

	@Nonnull
	@Override
	public Optional<SealedEntity> getExistingParentEntity(int primaryKey) {
		return this.entityDecorator.getParentEntityWithoutCheckingPredicate()
			.filter(SealedEntity.class::isInstance)
			.map(SealedEntity.class::cast);
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> getExistingEntity(@Nonnull String referenceName, int primaryKey) {
		final ReferenceKey referenceKey = new ReferenceKey(referenceName, primaryKey);
		return this.entityDecorator.getReferenceWithoutCheckingPredicate(referenceKey)
			.flatMap(ReferenceContract::getReferencedEntity);
	}

	@Nonnull
	@Override
	public Optional<SealedEntity> getExistingGroupEntity(@Nonnull String referenceName, int primaryKey) {
		final Collection<ReferenceContract> references = this.entityDecorator.getReferencesWithoutCheckingPredicate();
		for (ReferenceContract reference : references) {
			if (
				referenceName.equals(reference.getReferenceKey().referenceName()) &&
					reference.getGroup().map(it -> it.getPrimaryKey() == primaryKey).orElse(false)
			) {
				return reference.getGroupEntity();
			}
		}
		return empty();
	}
}
