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
 * Mutation that upgrades a catalog's on-disk storage protocol from `fromProtocolVersion` to `toProtocolVersion`.
 *
 * This mutation is the WAL-backed record of a per-catalog lazy format upgrade: instead of silently migrating a
 * catalog on startup, the engine emits this mutation so the upgrade is durable in the WAL, visible to CDC consumers,
 * and strictly ordered relative to other engine-level changes. The operator drives the state transitions
 * `OUT_OF_DATE → BEING_UPGRADED → <prior operational state>` and delegates the actual upgrade work to an injected
 * executor (`UpgradeExecutor`), which is wired separately so the Migration_* refactor and the external-API surface
 * can land in follow-up PRs.
 *
 * The `fromProtocolVersion` and `toProtocolVersion` fields are captured for observability and for CDC consumers that
 * want to correlate schema or data shape changes with the protocol bump.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class UpgradeCatalogFormatMutation implements TopLevelCatalogSchemaMutation<Void> {
	@Serial private static final long serialVersionUID = 4719253862831111137L;
	/**
	 * Name of the catalog whose storage protocol is being upgraded.
	 */
	@Nonnull @Getter private final String catalogName;
	/**
	 * Storage protocol version the catalog is being upgraded from. Captured purely for observability — the operator
	 * does not branch on this value, the upgrade executor does.
	 */
	@Getter private final int fromProtocolVersion;
	/**
	 * Storage protocol version the catalog is being upgraded to (typically the engine's current
	 * `STORAGE_PROTOCOL_VERSION`). Captured purely for observability.
	 */
	@Getter private final int toProtocolVersion;

	/**
	 * Creates a new mutation describing a per-catalog format upgrade.
	 *
	 * @param catalogName         name of the catalog to upgrade
	 * @param fromProtocolVersion storage protocol version currently present on disk
	 * @param toProtocolVersion   storage protocol version to upgrade to
	 */
	public UpgradeCatalogFormatMutation(
		@Nonnull String catalogName,
		int fromProtocolVersion,
		int toProtocolVersion
	) {
		this.catalogName = catalogName;
		this.fromProtocolVersion = fromProtocolVersion;
		this.toProtocolVersion = toProtocolVersion;
	}

	@Nonnull
	@Override
	public Class<Void> getProgressResultType() {
		return Void.class;
	}

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(@Nullable CatalogSchemaContract catalogSchema) {
		// Engine-level operation — the catalog schema itself is not modified by the protocol upgrade. If the schema
		// is still present in memory (e.g. during an OUT_OF_DATE → BEING_UPGRADED transition initiated from a
		// still-loaded catalog) we simply carry it through unchanged.
		return catalogSchema == null ? null : new CatalogSchemaWithImpactOnEntitySchemas(catalogSchema);
	}

	@Override
	public void verifyApplicability(@Nonnull EvitaContract evita) throws InvalidMutationException {
		if (!evita.getCatalogNames().contains(this.catalogName)) {
			throw new InvalidMutationException("Catalog `" + this.catalogName + "` doesn't exist!");
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
		return "Upgrade catalog `" + this.catalogName + "` format from protocol v" + this.fromProtocolVersion +
			" to v" + this.toProtocolVersion;
	}
}
