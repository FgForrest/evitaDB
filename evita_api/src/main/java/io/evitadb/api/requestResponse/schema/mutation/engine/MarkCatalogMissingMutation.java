/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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
import io.evitadb.api.requestResponse.mutation.conflict.ConflictGenerationContext;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.TopLevelCatalogSchemaMutation;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Mutation that marks a catalog as `{@link CatalogState#MISSING}` because its on-disk folder is no longer present.
 *
 * This mutation is the WAL-backed record of the divergence between the engine's registered catalogs and the actual
 * folder contents on disk. Emitting it keeps the engine state and the WAL in lock-step rather than silently
 * rewriting the bootstrap file when the reconciliation detects a missing folder at startup.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class MarkCatalogMissingMutation implements TopLevelCatalogSchemaMutation<Void> {
	@Serial private static final long serialVersionUID = 6723584790128441137L;
	@Nonnull @Getter private final String catalogName;

	/**
	 * Creates a new mutation marking the specified catalog as MISSING.
	 *
	 * @param catalogName name of the catalog whose on-disk folder is no longer present
	 */
	public MarkCatalogMissingMutation(@Nonnull String catalogName) {
		this.catalogName = catalogName;
	}

	@Nonnull
	@Override
	public Class<Void> getProgressResultType() {
		return Void.class;
	}

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(@Nullable CatalogSchemaContract catalogSchema) {
		// Engine-level operation — the catalog schema itself is not modified. If the schema is
		// still present in memory (transient window before the engine drops the instance) we
		// simply carry it through unchanged.
		return catalogSchema == null ? null : new CatalogSchemaWithImpactOnEntitySchemas(catalogSchema);
	}

	@Override
	public void verifyApplicability(@Nonnull EvitaContract evita) throws InvalidMutationException {
		if (!evita.getCatalogNames().contains(this.catalogName)) {
			throw new InvalidMutationException("Catalog `" + this.catalogName + "` doesn't exist!");
		}
		final CatalogState catalogState = evita.getCatalogState(this.catalogName).orElse(null);
		if (catalogState == CatalogState.MISSING) {
			throw new InvalidMutationException(
				"Catalog `" + this.catalogName + "` is already marked as MISSING!"
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
		@Nonnull ConflictGenerationContext context,
		@Nonnull Set<ConflictPolicy> conflictPolicies
	) {
		return Stream.of(new CatalogConflictKey(this.catalogName));
	}

	@Override
	public String toString() {
		return "Mark catalog `" + this.catalogName + "` missing";
	}
}
