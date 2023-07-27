/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.api.requestResponse.schema.mutation.catalog;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.utils.Assert;
import io.evitadb.utils.NamingConvention;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;

/**
 * Mutation is responsible for renaming an existing {@link CatalogSchemaContract}.
 * Mutation implements {@link CombinableCatalogSchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * TOBEDONE JNO - write tests
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
@RequiredArgsConstructor
public class ModifyCatalogSchemaNameMutation implements TopLevelCatalogSchemaMutation {
	@Serial private static final long serialVersionUID = -5779012919587623154L;
	@Getter @Nonnull private final String catalogName;
	@Getter @Nonnull private final String newCatalogName;
	@Getter private final boolean overwriteTarget;

	@Nonnull
	@Override
	public Operation getOperation() {
		return Operation.UPDATE;
	}

	@Nullable
	@Override
	public CatalogSchemaContract mutate(@Nullable CatalogSchemaContract catalogSchema) {
		Assert.notNull(
			catalogSchema,
			() -> new InvalidSchemaMutationException("Catalog doesn't exist!")
		);
		if (newCatalogName.equals(catalogSchema.getName())) {
			// nothing has changed - we can return existing schema
			return catalogSchema;
		} else {
			return CatalogSchema._internalBuild(
				catalogSchema.version() + 1,
				newCatalogName,
				NamingConvention.generate(newCatalogName),
				catalogSchema.getDescription(),
				catalogSchema.getCatalogEvolutionMode(),
				catalogSchema.getAttributes(),
				entityType -> {
					throw new UnsupportedOperationException("Mutated catalog schema can't provide access to entity schemas!");
				}
			);
		}
	}

	@Override
	public String toString() {
		return (overwriteTarget ? "Replace catalog " : "Modify catalog name") + "`" + catalogName + "`: " +
			"newCatalogName='" + newCatalogName + '\'';
	}
}
