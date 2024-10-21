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

package io.evitadb.core.query.fetch;


import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Map;

/**
 * This implementation of {@link FetchRequirementCollector} is used to collect the requirements for fetching
 * the referenced entities. It is based on explicitly defined requirements, but allows adding other implicit
 * requirements from the additional ordering or filtering constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class DefaultPrefetchRequirementCollector implements FetchRequirementCollector {
	private Map<Class<? extends EntityContentRequire>, EntityContentRequire[]> requirements;

	public DefaultPrefetchRequirementCollector(@Nullable EntityFetch entityFetch) {
		if (entityFetch != null) {
			this.requirements = CollectionUtils.createHashMap(8);
			addRequirementToPrefetchInternal(entityFetch.getRequirements());
		}
	}

	@Override
	public void addRequirementToPrefetch(@Nonnull EntityContentRequire... require) {
		if (this.requirements == null) {
			this.requirements = CollectionUtils.createHashMap(8);
		}
		addRequirementToPrefetchInternal(require);
	}

	/**
	 * Retrieves the entity fetch requirements that have been refined and collected.
	 *
	 * @return an EntityFetch instance containing the refined requirements if present, otherwise null
	 */
	@Nullable
	public EntityFetch getEntityFetch() {
		return this.requirements == null ?
			null :
			new EntityFetch(
				this.requirements.values()
					.stream()
					.flatMap(Arrays::stream)
					.toArray(EntityContentRequire[]::new)
			);
	}

	/**
	 * Checks if there is any fetch requirement defined.
	 *
	 * @return true if the requirements is NULL
	 */
	public boolean isEmpty() {
		return this.requirements == null;
	}

	/**
	 * Adds the given array of {@link EntityContentRequire} requirements to the internal requirements map.
	 * If a requirement of the same class is already present and can be combined with the new requirement,
	 * they are combined. Otherwise, the new requirement is added to the array.
	 *
	 * @param require an array of {@link EntityContentRequire} requirements to be added
	 */
	private void addRequirementToPrefetchInternal(@Nonnull EntityContentRequire[] require) {
		for (EntityContentRequire theRequirement : require) {
			this.requirements.compute(
				theRequirement.getClass(),
				(aClass, existing) -> {
					if (existing == null) {
						return new EntityContentRequire[]{theRequirement};
					}
					for (int i = 0; i < existing.length; i++) {
						EntityContentRequire existingRequire = existing[i];
						if (existingRequire.isCombinableWith(theRequirement)) {
							existing[i] = existingRequire.combineWith(theRequirement);
							return existing;
						}
					}
					return ArrayUtils.insertRecordIntoArray(theRequirement, existing, existing.length);
				}
			);
		}
	}

}
