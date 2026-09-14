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
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.MutationEntitySchemaAccessor;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
import io.evitadb.utils.NamingConvention;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.stream.Stream;

/**
 * Mutation is responsible for renaming an existing {@link CatalogSchemaContract}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class ModifyCatalogSchemaNameMutation implements TopLevelCatalogSchemaMutation<CommitVersions> {
	@Serial private static final long serialVersionUID = 2748912537205157142L;
	@Nonnull @Getter private final String catalogName;
	@Nonnull @Getter private final String newCatalogName;
	@Getter private final boolean overwriteTarget;

	/**
	 * Creates a new mutation that will rename the specified catalog.
	 *
	 * @param catalogName     name of the existing catalog to rename
	 * @param newCatalogName  new name for the catalog
	 * @param overwriteTarget whether to overwrite an existing catalog with the new name
	 */
	public ModifyCatalogSchemaNameMutation(
		@Nonnull String catalogName,
		@Nonnull String newCatalogName,
		boolean overwriteTarget
	) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.CATALOG, newCatalogName);
		this.catalogName = catalogName;
		this.newCatalogName = newCatalogName;
		this.overwriteTarget = overwriteTarget;
	}

	@Nonnull
	@Override
	public Class<CommitVersions> getProgressResultType() {
		return CommitVersions.class;
	}

	@Override
	public void verifyApplicability(@Nonnull EvitaContract evita) throws InvalidMutationException {
		if (!evita.getCatalogNames().contains(this.catalogName)) {
			throw new InvalidSchemaMutationException("Catalog `" + this.catalogName + "` doesn't exist!");
		}
		// Two different questions, and only the first of them is about what the caller intended. Whether an
		// occupied target may be taken over is the caller's to declare; whether the resulting name set still
		// holds unique names is a property of the state and cannot be waived by a flag.
		if (evita.getCatalogNames().contains(this.newCatalogName)) {
			if (!this.overwriteTarget) {
				throw new InvalidSchemaMutationException(
					"Catalog `" + this.newCatalogName + "` already exists! " +
						"Use `overwriteTarget` flag to overwrite existing catalog."
				);
			}
			// Nothing to check: the name is already held by the catalog being replaced, so its uniqueness was
			// settled when that catalog was created, and replacing it only ever *removes* a name from the set -
			// the old name of the catalog moving in. A set that was unique cannot stop being unique that way.
		} else {
			// A name nothing holds is a new name whatever `overwriteTarget` says, so it has to clear the same
			// bar a rename does. Skipping this on the strength of the flag alone is how an overwrite aimed at a
			// free name could introduce a catalog colliding with an existing one in some naming convention.
			//
			// Measured against every catalog *except* the one this mutation renames away. That catalog's name
			// leaves the set in the same act that introduces the new one, so counting it makes the operation
			// collide with itself: `myCatalog` to `my_catalog` is a legitimate rename that a check including
			// the source refuses, because the two agree in camel case.
			CatalogSchema.checkCatalogNameIsAvailable(evita, this.newCatalogName, this.catalogName);
		}
	}

	@Nonnull
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
					catalogSchema.getConflictResolution().orElse(null),
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

	@Nonnull
	@Override
	public Stream<ConflictKey> collectConflictKeys(
		@Nonnull ConflictGenerationContext context
	) {
		// the source name is given up rather than introduced, so it keeps a literal key; the target is introduced
		// - including when it takes over an existing catalog - and claims every name it would occupy
		return Stream.concat(
			Stream.of(new CatalogConflictKey(this.catalogName)),
			CatalogConflictKey.forIntroducedCatalogName(this.newCatalogName)
		);
	}

	@Override
	public String toString() {
		return (this.overwriteTarget ? "Replace catalog " : "Modify catalog name ") +
			"`" + this.catalogName + "`: newCatalogName='" + this.newCatalogName + '\'';
	}

}
