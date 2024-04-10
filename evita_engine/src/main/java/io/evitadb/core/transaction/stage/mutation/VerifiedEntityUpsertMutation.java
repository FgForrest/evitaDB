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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.transaction.stage.mutation;

import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Optional;

/**
 * Represents a verified entity upsert mutation. This is used to mark the entity upsert mutation as verified
 * and thus it can be propagated to the "live view" of the evitaDB engine without primary key assignment
 * verifications and undo support.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class VerifiedEntityUpsertMutation extends EntityUpsertMutation {
	@Serial private static final long serialVersionUID = -5775248516292883577L;

	public VerifiedEntityUpsertMutation(@Nonnull EntityUpsertMutation entityUpsertMutation) {
		super(
			entityUpsertMutation.getEntityType(),
			entityUpsertMutation.getEntityPrimaryKey(),
			entityUpsertMutation.expects(),
			entityUpsertMutation.getLocalMutations()
		);
	}

	@Nonnull
	@Override
	public Optional<EntitySchemaMutation[]> verifyOrEvolveSchema(
		@Nonnull SealedCatalogSchema catalogSchema,
		@Nonnull SealedEntitySchema entitySchema,
		boolean entityCollectionEmpty
	) {
		// this has already happened in the transactional memory layer
		// and all schema mutations have been already recorded
		return Optional.empty();
	}
}
