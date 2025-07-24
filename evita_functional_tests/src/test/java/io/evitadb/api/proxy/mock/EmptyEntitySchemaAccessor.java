/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.proxy.mock;

import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

/**
 * An implementation of the EntitySchemaProvider interface that provides an empty collection
 * of EntitySchemaContracts. It also returns an empty Optional when trying to retrieve an
 * EntitySchemaContract for a specific entity type.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class EmptyEntitySchemaAccessor implements EntitySchemaProvider {
	public static final EmptyEntitySchemaAccessor INSTANCE = new EmptyEntitySchemaAccessor();

	@Nonnull
	@Override
	public Collection<EntitySchemaContract> getEntitySchemas() {
		return Collections.emptyList();
	}

	@Nonnull
	@Override
	public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
		return Optional.empty();
	}
}
