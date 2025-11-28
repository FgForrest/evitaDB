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

package io.evitadb.core.transaction.stage.mutation;


import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class EntityRemoveMutationWithConflictKeys extends EntityRemoveMutation implements EntityMutation {
	@Serial private static final long serialVersionUID = -1035994756036407695L;
	@Getter private final EntityRemoveMutation delegate;
	@Getter private final Stream<ConflictKey> conflictKeyStream;


	public EntityRemoveMutationWithConflictKeys(
		@Nonnull EntityRemoveMutation delegate,
		@Nonnull Set<ConflictPolicy> conflictPolicy,
		@Nonnull List<? extends LocalMutation<?,?>> localMutations
	) {
		super(delegate.getEntityType(), delegate.getEntityPrimaryKey());
		this.delegate = delegate;
		final ConflictGenerationContext context = new ConflictGenerationContext();
		this.conflictKeyStream = context.withEntityType(
			delegate.getEntityType(),
			delegate.getEntityPrimaryKey(),
			ctx -> EntityMutation.getConflictKeyStream(
				delegate.getEntityType(), delegate.getEntityPrimaryKey(), localMutations, conflictPolicy, ctx
			)
		);
	}

	@Nonnull
	@Override
	public Stream<ConflictKey> collectConflictKeys(
		@Nonnull ConflictGenerationContext context,
		@Nonnull Set<ConflictPolicy> conflictPolicies
	) {
		return this.conflictKeyStream;
	}
}
