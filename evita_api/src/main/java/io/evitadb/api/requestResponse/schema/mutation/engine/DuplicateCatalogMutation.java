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

package io.evitadb.api.requestResponse.schema.mutation.engine;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.conflict.CatalogConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.stream.Stream;

/**
 * Mutation that duplicates a catalog with a new name, copying all contents from the source catalog.
 *
 * This mutation creates a new catalog with the specified name containing all the data and schema
 * from the source catalog. The source catalog must exist and be in a valid state for duplication.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class DuplicateCatalogMutation implements TopLevelCatalogSchemaMutation<Void> {
	@Serial private static final long serialVersionUID = -7575495323096487905L;
	@Nonnull @Getter private final String catalogName;
	@Nonnull @Getter private final String newCatalogName;

	/**
	 * Creates a new mutation that will duplicate the specified catalog with a new name.
	 *
	 * @param catalogName name of the source catalog to duplicate
	 * @param newCatalogName name of the new catalog to create with duplicated contents
	 */
	public DuplicateCatalogMutation(@Nonnull String catalogName, @Nonnull String newCatalogName) {
		this.catalogName = catalogName;
		this.newCatalogName = newCatalogName;
	}

	@Nonnull
	@Override
	public Class<Void> getProgressResultType() {
		return Void.class;
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
			throw new InvalidMutationException("Catalog `" + this.catalogName + "` doesn't exist!");
		} else {
			final CatalogState catalogState = evita.getCatalogState(this.catalogName).orElse(null);
			if (!(catalogState == CatalogState.ALIVE || catalogState == CatalogState.WARMING_UP)) {
				throw new InvalidMutationException("Catalog `" + this.catalogName + "` is not in a valid state for this operation! Current state: " + catalogState);
			}
		}

		if (evita.getCatalogNames().contains(this.newCatalogName)) {
			throw new InvalidMutationException("Catalog `" + this.newCatalogName + "` already exists!");
		}
		// check the names in all naming conventions are unique in the entity schema
		CatalogSchema.checkCatalogNameIsAvailable(evita, this.newCatalogName);
	}

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(@Nullable CatalogSchemaContract catalogSchema) {
		// This is an engine-level operation that doesn't modify the schema directly
		// The actual duplication is handled at the engine level
		return catalogSchema == null ? null : new CatalogSchemaWithImpactOnEntitySchemas(catalogSchema);
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Override
	public String toString() {
		return "Duplicate catalog `" + this.catalogName + "` to `" + this.newCatalogName + "`";
	}
}
