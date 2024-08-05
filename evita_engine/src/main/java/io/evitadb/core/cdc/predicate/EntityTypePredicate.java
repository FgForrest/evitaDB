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

package io.evitadb.core.cdc.predicate;

import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;

import javax.annotation.Nonnull;

/**
 * Predicate filters out only mutations that are related to data mutation related to a particular entity type.
 * The predicate is optimized for matching also {@link LocalMutation} mutations that are related to the same entity
 * type by remembering the last entity type.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class EntityTypePredicate extends MutationPredicate {
	private final String entityType;

	public EntityTypePredicate(@Nonnull MutationPredicateContext context, @Nonnull String entityType) {
		super(context);
		this.entityType = entityType;
	}

	@Override
	public boolean test(Mutation mutation) {
		if (mutation instanceof EntityUpsertMutation dataMutation) {
			this.context.setEntityType(dataMutation.getEntityType());
		} else if (mutation instanceof EntityRemoveMutation dataMutation) {
			this.context.setEntityType(dataMutation.getEntityType());
		} else if (!(mutation instanceof LocalMutation<?, ?>)) {
			this.context.resetEntityType();
		}
		return this.context.matchEntityType(this.entityType);
	}

}
