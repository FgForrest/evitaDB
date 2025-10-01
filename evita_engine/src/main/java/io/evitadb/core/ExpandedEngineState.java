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

package io.evitadb.core;


import io.evitadb.api.CatalogContract;
import io.evitadb.store.spi.model.EngineState;
import io.evitadb.store.spi.model.reference.LogFileRecordReference;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.utils.ArrayUtils.insertRecordIntoOrderedArray;
import static io.evitadb.utils.ArrayUtils.removeRecordFromOrderedArray;

/**
 * ExpandedEngineState represents a fully expanded, runtime view of the engine state.
 *
 * It combines two kinds of information:
 * - the persisted, compact {@link EngineState} snapshot (arrays of catalog names, version, WAL ref), and
 * - the in-memory {@code catalogs} map that holds actual {@code CatalogContract} instances keyed by name.
 *
 * This separation allows the engine to persist a minimal, immutable snapshot while still providing
 * fast access to live catalog objects when executing operations. Methods that change the engine
 * topology (adding/removing catalogs or toggling read-only flags) never mutate this record; they
 * return a new ExpandedEngineState with an updated {@link EngineState} and/or catalogs map.
 *
 * Concurrency and mutability notes:
 * - Instances of this record are intended to be published safely and treated as immutable snapshots.
 * - The two-argument constructor wraps the provided {@code catalogs} map with
 * {@link java.util.Collections#unmodifiableMap(Map)} to prevent accidental writes.
 * - The helper {@link #replaceCatalogReference(Catalog)} method refreshes pointer to the modified catalog
 * instance without changing the engine state or catalogs map structure.
 *
 * Invariants and interpretation:
 * - Presence of a catalog in the {@code catalogs} map implies its name exists in either
 * {@link EngineState#activeCatalogs()} or {@link EngineState#inactiveCatalogs()}.
 * - {@code readOnlyCatalogs} is a quick-access set derived from
 * {@link EngineState#readOnlyCatalogs()} to avoid repeated array scans.
 * - Passing an actual {@link Catalog} instance to {@link #withUpdatedCatalogInstance(CatalogContract)} marks the
 * catalog as active; passing a non-runtime representation keeps it inactive.
 *
 * @param startVersion     the version of the engine state at the time of the evitaDB startup
 * @param engineState      persisted snapshot of engine-level state
 * @param catalogs         map of catalog instances keyed by their names
 * @param readOnlyCatalogs names of catalogs considered read-only in this snapshot
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Immutable
public record ExpandedEngineState(
	long startVersion,
	@Nonnull EngineState engineState,
	@Nonnull Map<String, CatalogWrapper> catalogs,
	@Nonnull Set<String> readOnlyCatalogs
) {

	/**
	 * Returns a new builder initialized with values from the current instance.
	 */
	@Nonnull
	public static Builder builder(@Nonnull ExpandedEngineState base) {
		return new Builder(base);
	}

	/**
	 * Creates a new expanded view from a persisted {@link EngineState} and a map of catalogs.
	 *
	 * The provided {@code catalogs} map is wrapped with an unmodifiable view to guard this snapshot
	 * against accidental writes. The {@code readOnlyCatalogs} set is derived from the engine state
	 * to provide O(1) checks for catalog mutability.
	 *
	 * Prefer using this constructor when you want to expose a safe, read-only snapshot to other
	 * components. If you need to perform in-place swaps in the {@code catalogs} map, construct the
	 * record with the canonical three-argument constructor and supply a mutable map implementation.
	 *
	 * @param engineState persisted snapshot of engine-level state
	 * @param catalogs    catalog instances keyed by name (will be wrapped as unmodifiable)
	 */
	public static ExpandedEngineState create(
		@Nonnull EngineState engineState,
		@Nonnull Map<String, CatalogContract> catalogs
	) {
		return new ExpandedEngineState(
			engineState.version(),
			engineState,
			Collections.unmodifiableMap(
				catalogs.entrySet().stream()
				        .collect(
					        Collectors.toMap(
						        Map.Entry::getKey,
						        entry -> new CatalogWrapper(entry.getValue())
					        )
				        )
			),
			Set.copyOf(
				Arrays.asList(engineState.readOnlyCatalogs())
			)
		);
	}

	/**
	 * Creates a new expanded view from a persisted {@link EngineState} and a map of catalogs.
	 *
	 * The provided {@code catalogs} map is wrapped with an unmodifiable view to guard this snapshot
	 * against accidental writes. The {@code readOnlyCatalogs} set is derived from the engine state
	 * to provide O(1) checks for catalog mutability.
	 *
	 * Prefer using this constructor when you want to expose a safe, read-only snapshot to other
	 * components. If you need to perform in-place swaps in the {@code catalogs} map, construct the
	 * record with the canonical three-argument constructor and supply a mutable map implementation.
	 *
	 * @param engineState persisted snapshot of engine-level state
	 * @param catalogs    catalog instances keyed by name (will be wrapped as unmodifiable)
	 */
	private ExpandedEngineState(
		long startVersion,
		@Nonnull EngineState engineState,
		@Nonnull Map<String, CatalogWrapper> catalogs
	) {
		this(
			startVersion,
			engineState,
			Collections.unmodifiableMap(catalogs),
			Set.copyOf(
				Arrays.asList(engineState.readOnlyCatalogs())
			)
		);
	}

	/**
	 * Retrieves a collection of catalog contracts derived from the current state.
	 * The catalogs are extracted and converted using their respective wrappers.
	 *
	 * @return a {@code Collection} of {@code CatalogContract} instances representing the catalogs in the current state
	 */
	@Nonnull
	public Collection<CatalogContract> getCatalogCollection() {
		return this.catalogs.values().stream()
		                    .map(CatalogWrapper::catalog)
		                    .toList();
	}

	/**
	 * Returns the current version of the engine state.
	 *
	 * @return the current version of the engine state
	 */
	public long version() {
		return this.engineState.version();
	}

	/**
	 * Retrieves the current WAL (Write-Ahead Log) file reference from the engine state.
	 *
	 * @return a {@code LogFileRecordReference} object representing the current WAL file reference,
	 * or {@code null} if no WAL file reference is present in the engine state
	 */
	@Nullable
	public LogFileRecordReference walFileReference() {
		return this.engineState.walFileReference();
	}

	/**
	 * Retrieves the catalog identified by the specified catalog name from the current state.
	 *
	 * @param catalogName the name of the catalog to retrieve, must not be null
	 * @return an {@code Optional} containing the {@code CatalogContract} if a catalog with the specified name exists,
	 * or an empty {@code Optional} if no such catalog is found
	 */
	@Nonnull
	public Optional<CatalogContract> getCatalog(@Nonnull String catalogName) {
		return Optional.ofNullable(this.catalogs.get(catalogName)).map(CatalogWrapper::catalog);
	}

	/**
	 * Determines whether the catalog identified by the specified catalog name is in a read-only state.
	 *
	 * @param catalogName the name of the catalog to check, must not be null
	 * @return {@code true} if the catalog is read-only, {@code false} otherwise
	 */
	public boolean isReadOnly(@Nonnull String catalogName) {
		return this.readOnlyCatalogs.contains(catalogName);
	}

	/**
	 * Replaces the in-memory reference for the specified catalog by name if the provided
	 * {@link Catalog} instance has a higher {@link Catalog#getVersion() version} than the
	 * current reference.
	 *
	 * This is a best-effort, in-place optimization intended for scenarios where the underlying
	 * {@code catalogs} map is a concurrent and mutable implementation. If the map is unmodifiable,
	 * calling this method will fail; in such cases prefer {@link #withUpdatedCatalogInstance(CatalogContract)} to
	 * obtain a new immutable snapshot.
	 *
	 * Concurrency: the operation relies on {@link Map#computeIfPresent} which is safe with concurrent
	 * maps. Only a strictly newer reference replaces the existing one; if the reference is the same or
	 * older, the current catalog remains unchanged.
	 *
	 * @param catalog a newer {@link Catalog} instance to swap in by name
	 */
	public void replaceCatalogReference(@Nonnull Catalog catalog) {
		// catalog indexes are ConcurrentHashMap - we can do it safely here
		final CatalogWrapper currentCatalogRef = this.catalogs.get(catalog.getName());
		// replace catalog only when reference/pointer differs
		final CatalogContract currentCatalog = currentCatalogRef.catalog();
		if (currentCatalog != catalog && currentCatalog.getVersion() < catalog.getVersion()) {
			currentCatalogRef.replaceCatalogReference(catalog);
		}
	}

	/**
	 * Returns a new snapshot with the provided catalog present in the catalogs map and the engine
	 * state's active/inactive arrays updated accordingly.
	 *
	 * Rules:
	 * - If {@code catalog} is an actual {@link Catalog} instance, its name is inserted into
	 * {@link EngineState#activeCatalogs()} and removed from {@link EngineState#inactiveCatalogs()}.
	 * - Otherwise, the name is inserted into {@link EngineState#inactiveCatalogs()} and removed
	 * from {@link EngineState#activeCatalogs()}.
	 *
	 * The resulting catalogs map is a copy of the current map with the entry updated and is wrapped
	 * as unmodifiable in the returned record.
	 *
	 * @param catalog catalog to include in this snapshot
	 * @return new ExpandedEngineState reflecting the update
	 */
	@Nonnull
	public ExpandedEngineState withUpdatedCatalogInstance(@Nonnull CatalogContract catalog) {
		final HashMap<String, CatalogWrapper> updatedCatalogs = new HashMap<>(this.catalogs);
		updatedCatalogs.put(catalog.getName(), new CatalogWrapper(catalog));

		final EngineState.Builder engineStateBuilder = EngineState
			.builder(this.engineState)
			.version(this.engineState.version());

		if (catalog instanceof Catalog) {
			engineStateBuilder.activeCatalogs(
				insertRecordIntoOrderedArray(catalog.getName(), this.engineState.activeCatalogs()));
			engineStateBuilder.inactiveCatalogs(
				removeRecordFromOrderedArray(catalog.getName(), this.engineState.inactiveCatalogs()));
		} else {
			engineStateBuilder.activeCatalogs(
				removeRecordFromOrderedArray(catalog.getName(), this.engineState.activeCatalogs()));
			engineStateBuilder.inactiveCatalogs(
				insertRecordIntoOrderedArray(catalog.getName(), this.engineState.inactiveCatalogs()));
		}
		return new ExpandedEngineState(
			this.startVersion,
			engineStateBuilder.build(),
			updatedCatalogs
		);
	}

	/**
	 * Returns a new persisted {@link EngineState} derived from the underlying snapshot, but with the
	 * provided WAL file reference.
	 *
	 * This method does not mutate this record. Use it when you need to advance the WAL pointer that
	 * will be stored together with the next engine snapshot.
	 *
	 * @param walFileReference new write-ahead log reference to embed in the returned EngineState
	 * @param engineStateVersion the version to set on the new EngineState
	 * @return a new {@link EngineState} identical to the current one except for the WAL reference
	 */
	@Nonnull
	public EngineState engineState(
		@Nonnull LogFileRecordReference walFileReference,
		long engineStateVersion
	) {
		return EngineState.builder(this.engineState)
		                  .version(engineStateVersion)
		                  .walFileReference(walFileReference)
		                  .build();
	}

	/**
	 * Builder for creating modified snapshots of ExpandedEngineState without bumping the version on
	 * each intermediate operation. The version is increased exactly once upon build().
	 */
	public static class Builder {
		@Nonnull private final ExpandedEngineState base;
		private long startVersion;
		private long version;
		@Nonnull private final HashMap<String, CatalogWrapper> catalogs;
		@Nonnull private String[] activeCatalogs;
		@Nonnull private String[] inactiveCatalogs;
		@Nonnull private String[] readOnlyCatalogs;

		/**
		 * Initializes builder with values from the provided snapshot.
		 */
		Builder(@Nonnull ExpandedEngineState base) {
			this.base = base;
			this.startVersion = base.startVersion;
			this.version = this.base.engineState.version();
			this.catalogs = new HashMap<>(base.catalogs);
			this.activeCatalogs = base.engineState.activeCatalogs();
			this.inactiveCatalogs = base.engineState.inactiveCatalogs();
			this.readOnlyCatalogs = base.engineState.readOnlyCatalogs();
		}

		/**
		 * Sets a specific version for the engine state being built.
		 *
		 * @param version the version to set
		 * @return this builder instance
		 */
		@Nonnull
		public Builder withVersion(long version) {
			this.version = version;
			return this;
		}


		/**
		 * Stages the provided catalog into the snapshot.
		 * If the catalog is a live Catalog instance it will be marked active, otherwise inactive.
		 */
		@Nonnull
		public Builder withCatalog(@Nonnull CatalogContract catalog) {
			this.catalogs.put(catalog.getName(), new CatalogWrapper(catalog));
			if (catalog instanceof Catalog) {
				this.activeCatalogs = insertRecordIntoOrderedArray(catalog.getName(), this.activeCatalogs);
				this.inactiveCatalogs = removeRecordFromOrderedArray(
					catalog.getName(), this.inactiveCatalogs);
			} else {
				this.activeCatalogs = removeRecordFromOrderedArray(catalog.getName(), this.activeCatalogs);
				this.inactiveCatalogs = insertRecordIntoOrderedArray(
					catalog.getName(), this.inactiveCatalogs);
			}
			return this;
		}

		/**
		 * Stages removal of the provided catalog from the snapshot including all arrays.
		 */
		@Nonnull
		public Builder withoutCatalog(@Nonnull CatalogContract catalog) {
			final String catalogName = catalog.getName();
			return withoutCatalog(catalogName);
		}

		/**
		 * Stages removal of the provided catalog from the snapshot including all arrays.
		 */
		@Nonnull
		public Builder withoutCatalog(@Nonnull String catalogName) {
			this.catalogs.remove(catalogName);
			this.activeCatalogs = removeRecordFromOrderedArray(catalogName, this.activeCatalogs);
			this.inactiveCatalogs = removeRecordFromOrderedArray(catalogName, this.inactiveCatalogs);
			this.readOnlyCatalogs = removeRecordFromOrderedArray(catalogName, this.readOnlyCatalogs);
			return this;
		}

		/**
		 * Marks the catalog as read-only in the staged snapshot.
		 */
		@Nonnull
		public Builder withReadOnlyCatalog(@Nonnull CatalogContract catalog) {
			this.readOnlyCatalogs = insertRecordIntoOrderedArray(catalog.getName(), this.readOnlyCatalogs);
			return this;
		}

		/**
		 * Removes the read-only flag for the catalog in the staged snapshot.
		 */
		@Nonnull
		public Builder withoutReadOnlyCatalog(@Nonnull CatalogContract catalog) {
			this.readOnlyCatalogs = removeRecordFromOrderedArray(catalog.getName(), this.readOnlyCatalogs);
			return this;
		}

		/**
		 * Builds a new ExpandedEngineState snapshot, increasing the version exactly once.
		 */
		@Nonnull
		public ExpandedEngineState build() {
			final EngineState.Builder engineStateBuilder = EngineState
				.builder(this.base.engineState)
				.version(this.version)
				.activeCatalogs(this.activeCatalogs)
				.inactiveCatalogs(this.inactiveCatalogs)
				.readOnlyCatalogs(this.readOnlyCatalogs);
			return new ExpandedEngineState(
				this.startVersion,
				engineStateBuilder.build(),
				this.catalogs
			);
		}
	}

	/**
	 * A wrapper record for managing and updating an atomic reference to a {@code CatalogContract}.
	 * Designed to encapsulate safe concurrent operations on catalog references.
	 */
	private record CatalogWrapper(
		@Nonnull AtomicReference<CatalogContract> catalogReference
	) {

		private CatalogWrapper(@Nonnull CatalogContract catalogReference) {
			this(new AtomicReference<>(catalogReference));
		}

		/**
		 * Retrieves the current {@code CatalogContract} instance from the atomic reference.
		 *
		 * @return the current {@code CatalogContract} managed within the atomic reference.
		 */
		@Nonnull
		public CatalogContract catalog() {
			return this.catalogReference.get();
		}

		/**
		 * Replaces the current catalog reference with the provided catalog instance.
		 * Ensures that the existing catalog reference is an instance of the {@code Catalog} class
		 * before performing the replacement.
		 *
		 * @param catalog the new {@code CatalogContract} instance to replace the existing catalog reference.
		 *                Must not be null.
		 */
		public void replaceCatalogReference(@Nonnull CatalogContract catalog) {
			this.catalogReference.getAndAccumulate(
				catalog,
				(existing, newCatalog) -> {
					Assert.isPremiseValid(
						existing instanceof Catalog,
						"Catalog reference must be an instance of Catalog to replace its state, but was: " +
							existing.getClass().getName()
					);
					return newCatalog;
				}
			);
		}

	}

}
