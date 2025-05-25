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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.Supplier;

/**
 * Catalog schema decorator is a mere implementation of the {@link SealedEntitySchema} that creates an instance
 * of the {@link EntitySchemaBuilder} on seal breaking operations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class EntitySchemaDecorator implements SealedEntitySchema {
	@Serial private static final long serialVersionUID = -5581711006960936882L;

	private final Supplier<CatalogSchemaContract> catalogSchemaSupplier;
	@Delegate(types = EntitySchemaContract.class)
	@Getter private final EntitySchema delegate;

	public EntitySchemaDecorator(
		@Nonnull Supplier<CatalogSchemaContract> catalogSchemaSupplier,
		@Nonnull EntitySchema delegate
	) {
		this.catalogSchemaSupplier = catalogSchemaSupplier;
		this.delegate = delegate;
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder openForWrite() {
		final CatalogSchemaContract catalogSchema = this.catalogSchemaSupplier.get();
		return new InternalEntitySchemaBuilder(
			catalogSchema,
			this.delegate
		);
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withMutations(@Nonnull LocalEntitySchemaMutation... schemaMutations) {
		final CatalogSchemaContract catalogSchema = this.catalogSchemaSupplier.get();
		return new InternalEntitySchemaBuilder(
			catalogSchema,
			this.delegate,
			Arrays.asList(schemaMutations)
		);
	}

	@Nonnull
	@Override
	public EntitySchemaBuilder withMutations(@Nonnull Collection<LocalEntitySchemaMutation> schemaMutations) {
		final CatalogSchemaContract catalogSchema = this.catalogSchemaSupplier.get();
		return new InternalEntitySchemaBuilder(
			catalogSchema,
			this.delegate,
			schemaMutations
		);
	}

}
