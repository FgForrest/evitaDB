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

package io.evitadb.driver.requestResponse.schema;

import io.evitadb.api.requestResponse.schema.CatalogSchemaDecorator;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;

/**
 * Extension of {@link CatalogSchemaDecorator} for {@link io.evitadb.driver.EvitaClient} so that entity schema accessor
 * with current active {@link io.evitadb.driver.EvitaClientSession} can be passed even though cached catalog schema is used.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class ClientCatalogSchemaDecorator extends CatalogSchemaDecorator {

	@Serial private static final long serialVersionUID = -4594732354632495741L;

	/**
	 * Override of accessor integrated in {@link CatalogSchema} itself. But that accessor cannot be overridden
	 * when the {@link CatalogSchema} is retrieved from cache on client and thus the integrated accessor could use
	 * expired session.
	 */
	@Nonnull private final EntitySchemaProvider entitySchemaAccessor;

	public ClientCatalogSchemaDecorator(@Nonnull CatalogSchema delegate,
	                                    @Nonnull EntitySchemaProvider entitySchemaAccessor) {
		super(delegate);
		this.entitySchemaAccessor = entitySchemaAccessor;
	}

	@Nonnull
	@Override
	public CatalogSchemaBuilder openForWrite() {
		return new InternalCatalogSchemaBuilder(
			this
		);
	}

	@Nonnull
	@Override
	public CatalogSchemaBuilder openForWriteWithMutations(@Nonnull LocalCatalogSchemaMutation... schemaMutations) {
		return new InternalCatalogSchemaBuilder(
			this, Arrays.asList(schemaMutations)
		);
	}

	@Nonnull
	@Override
	public CatalogSchemaBuilder openForWriteWithMutations(@Nonnull Collection<LocalCatalogSchemaMutation> schemaMutations) {
		return new InternalCatalogSchemaBuilder(
			this, schemaMutations
		);
	}

	@Nonnull
	@Override
	public Collection<EntitySchemaContract> getEntitySchemas() {
		return this.entitySchemaAccessor.getEntitySchemas();
	}

	@Nonnull
	@Override
	public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
		return this.entitySchemaAccessor.getEntitySchema(entityType);
	}

	@Nonnull
	@Override
	public EntitySchemaContract getEntitySchemaOrThrowException(@Nonnull String entityType) {
		return getEntitySchema(entityType)
			.orElseThrow(() -> new EvitaInvalidUsageException("Schema for entity with name `" + entityType + "` was not found!"));
	}
}
