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

import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import lombok.Getter;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Arrays;
import java.util.Collection;

/**
 * Catalog schema decorator is a mere implementation of the {@link SealedCatalogSchema} that creates an instance
 * of the {@link CatalogSchemaBuilder} on seal breaking operations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class CatalogSchemaDecorator implements SealedCatalogSchema {
	@Serial private static final long serialVersionUID = 8854250508519097535L;
	@Delegate(types = CatalogSchemaContract.class)
	@Getter
	private final CatalogSchema delegate;

	public CatalogSchemaDecorator(@Nonnull CatalogSchema delegate) {
		this.delegate = delegate;
	}

	@Nonnull
	@Override
	public CatalogSchemaBuilder openForWrite() {
		return new InternalCatalogSchemaBuilder(
			this.delegate
		);
	}

	@Nonnull
	@Override
	public CatalogSchemaBuilder openForWriteWithMutations(@Nonnull LocalCatalogSchemaMutation... schemaMutations) {
		return new InternalCatalogSchemaBuilder(
			this.delegate, Arrays.asList(schemaMutations)
		);
	}

	@Nonnull
	@Override
	public CatalogSchemaBuilder openForWriteWithMutations(@Nonnull Collection<LocalCatalogSchemaMutation> schemaMutations) {
		return new InternalCatalogSchemaBuilder(
			this.delegate, schemaMutations
		);
	}

}
