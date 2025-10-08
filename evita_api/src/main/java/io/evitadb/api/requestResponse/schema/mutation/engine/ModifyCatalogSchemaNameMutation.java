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

package io.evitadb.api.requestResponse.schema.mutation.engine;

import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.conflict.CatalogConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.MutationEntitySchemaAccessor;
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
import java.util.stream.Stream;

/**
 * Mutation is responsible for renaming an existing {@link CatalogSchemaContract}.
 * Mutation implements {@link CombinableCatalogSchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
@RequiredArgsConstructor
public class ModifyCatalogSchemaNameMutation implements TopLevelCatalogSchemaMutation<CommitVersions> {
	@Serial private static final long serialVersionUID = -5779012919587623154L;
	@Getter @Nonnull private final String catalogName;
	@Getter @Nonnull private final String newCatalogName;
	@Getter private final boolean overwriteTarget;

	@Nonnull
	@Override
	public Class<CommitVersions> getProgressResultType() {
		return CommitVersions.class;
	}

	@Nonnull
	@Override
	public Stream<ConflictKey> getConflictKeys() {
		return Stream.of(
			new CatalogConflictKey(this.catalogName),
			new CatalogConflictKey(this.newCatalogName)
		);
	}

	@Override
	public void verifyApplicability(@Nonnull EvitaContract evita) throws InvalidMutationException {
		if (!evita.getCatalogNames().contains(this.catalogName)) {
			throw new InvalidSchemaMutationException("Catalog `" + this.catalogName + "` doesn't exist!");
		}
		if (!this.overwriteTarget) {
			if (evita.getCatalogNames().contains(this.newCatalogName)) {
				throw new InvalidSchemaMutationException(
					"Catalog `" + this.newCatalogName + "` already exists! " +
						"Use `overwriteTarget` flag to overwrite existing catalog."
				);
			}
			// check the names in all naming conventions are unique in the entity schema
			CatalogSchema.checkCatalogNameIsAvailable(evita, this.newCatalogName);
		}
	}

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(@Nullable CatalogSchemaContract catalogSchema) {
		Assert.notNull(
			catalogSchema,
			() -> new InvalidSchemaMutationException("Catalog doesn't exist!")
		);
		if (this.newCatalogName.equals(catalogSchema.getName())) {
			// nothing has changed - we can return existing schema
			return new CatalogSchemaWithImpactOnEntitySchemas(catalogSchema);
		} else {
			return new CatalogSchemaWithImpactOnEntitySchemas(
				CatalogSchema._internalBuild(
					catalogSchema.version() + 1,
					this.newCatalogName,
					NamingConvention.generate(this.newCatalogName),
					catalogSchema.getDescription(),
					catalogSchema.getCatalogEvolutionMode(),
					catalogSchema.getAttributes(),
					MutationEntitySchemaAccessor.INSTANCE
				)
			);
		}
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return (this.overwriteTarget ? "Replace catalog " : "Modify catalog name") + "`" + this.catalogName + "`: " +
			"newCatalogName='" + this.newCatalogName + '\'';
	}

}
