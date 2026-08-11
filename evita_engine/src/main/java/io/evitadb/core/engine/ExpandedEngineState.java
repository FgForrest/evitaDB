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

package io.evitadb.core.engine;


import io.evitadb.api.CatalogContract;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.spi.store.engine.model.CatalogFolderBinding;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.spi.store.engine.model.RetiredFolder;
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
	@Nonnull EngineState<LogRecordReference> engineState,
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
	 * Returns the binding array guaranteed to carry an entry for the passed catalog name, binding it to the
	 * passed folder when it has none yet.
	 *
	 * An existing binding is left exactly as it is — a catalog being re-staged (activated, made alive, swapped
	 * for a newer instance) must keep pointing at the folder it already occupies, and overwriting it would undo
	 * a rename. Only a name the state has never seen is bound, and it is bound to the token the caller supplies.
	 *
	 * **The token is never derived here.** This method used to invent `new CatalogFolderId(catalogName)` for an
	 * unbound name, which silently discarded the folder an allocation had just created: the folder was written,
	 * the catalog was bound to a *different*, identity-named directory, and nothing reported a failure. Deciding
	 * a folder is `CatalogFolderContext`'s job — see `folderIdForBinding`, whose identity branch is the one
	 * legitimate source of an identity token (a folder discovered on disk under the catalog's own name).
	 *
	 * @param bindings    bindings currently recorded, strictly ascending by catalog name
	 * @param catalogName name that must be bound in the result
	 * @param folderId    folder to bind the name to when it carries no binding yet
	 * @return binding array containing the name; the input array is never modified
	 */
	@Nonnull
	private static CatalogFolderBinding[] bindingsIncluding(
		@Nonnull CatalogFolderBinding[] bindings,
		@Nonnull String catalogName,
		@Nonnull CatalogFolderId folderId
	) {
		for (final CatalogFolderBinding binding : bindings) {
			if (binding.catalogName().equals(catalogName)) {
				return bindings;
			}
		}
		return EngineState.withBinding(bindings, new CatalogFolderBinding(catalogName, folderId));
	}

	/**
	 * Returns the binding array unchanged, having verified it already binds the passed catalog name.
	 *
	 * Used by every staging path that re-stages a catalog the engine state already knows — a transition
	 * placeholder, a catalog going live, a freshly loaded instance. Such a path has no business choosing a
	 * folder, and an unbound name reaching it means the catalog was never registered, which is a programming
	 * error rather than something to paper over with a default.
	 *
	 * @param bindings    bindings currently recorded, strictly ascending by catalog name
	 * @param catalogName name expected to be bound already
	 * @return the input array, unmodified
	 * @throws GenericEvitaInternalError when the name carries no binding
	 */
	@Nonnull
	private static CatalogFolderBinding[] bindingsRequiring(
		@Nonnull CatalogFolderBinding[] bindings,
		@Nonnull String catalogName
	) {
		for (final CatalogFolderBinding binding : bindings) {
			if (binding.catalogName().equals(catalogName)) {
				return bindings;
			}
		}
		throw new GenericEvitaInternalError(
			"Catalog `" + catalogName + "` is being staged without a folder binding! Only a path that " +
				"registers a catalog for the first time may establish one, and it must pass the folder token " +
				"explicitly."
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
	public static ExpandedEngineState create(
		@Nonnull EngineState<LogRecordReference> engineState,
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
		@Nonnull EngineState<LogRecordReference> engineState,
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
	public LogRecordReference walFileReference() {
		return this.engineState.walReference();
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
	 * Returns the folder token holding the data of the passed catalog as recorded by the persisted snapshot, or
	 * `null` when the catalog has no binding.
	 *
	 * This is the runtime entry point to the engine state's name-to-folder authority — see
	 * {@link EngineState#boundFolderIdFor(String)} for why an unbound name is reported rather than guessed at.
	 *
	 * @param catalogName name of the catalog to resolve
	 * @return token identifying the folder bound to the catalog, or `null` when the catalog is unbound
	 */
	@Nullable
	public CatalogFolderId boundFolderIdFor(@Nonnull String catalogName) {
		return this.engineState.boundFolderIdFor(catalogName);
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
	 * current reference, and reports whether the catalog schema version advanced as part of
	 * the swap.
	 *
	 * This is a best-effort, in-place optimization intended for scenarios where the underlying
	 * {@code catalogs} map is a concurrent and mutable implementation. If the map is unmodifiable,
	 * calling this method will fail; in such cases prefer {@link #withUpdatedCatalogInstance(CatalogContract)} to
	 * obtain a new immutable snapshot.
	 *
	 * Concurrency: the prior schema-version snapshot is read **before** the atomic swap inside
	 * {@link CatalogWrapper#replaceCatalogReference(Catalog)}, so under concurrent calls for the
	 * same catalog the read may be stale relative to whoever ultimately wins the swap. The commit
	 * pipeline (`TransactionManager#propagateCatalogSnapshot`) serializes calls per catalog, so in
	 * practice this race cannot occur — the read remains consistent with the replaced reference.
	 * Only a strictly newer reference replaces the existing one; if the reference is the same or
	 * older, the current catalog remains unchanged.
	 *
	 * @param catalog a newer {@link Catalog} instance to swap in by name
	 * @return {@code true} when the swap actually happened AND the new catalog has a strictly
	 * higher schema version than the prior reference; {@code false} when the swap was skipped
	 * (older / identical reference) or when the schema version did not advance (data-only commit)
	 */
	public boolean replaceCatalogReference(@Nonnull Catalog catalog) {
		// catalog indexes are ConcurrentHashMap - we can do it safely here
		final CatalogWrapper currentCatalogRef = this.catalogs.get(catalog.getName());
		// replace catalog only when reference/pointer differs and is strictly newer
		final CatalogContract currentCatalog = currentCatalogRef.catalog();
		if (currentCatalog == catalog || currentCatalog.getVersion() >= catalog.getVersion()) {
			return false;
		}
		// UnusableCatalog placeholder cannot read its schema (would throw); treat as -1 so a
		// real-Catalog replacement always counts as a schema advance. Defensive against a
		// programming-error path — production swaps here are always Catalog→Catalog from the
		// commit pipeline.
		final int priorSchemaVersion = currentCatalog instanceof Catalog priorAlive
			? priorAlive.getSchema().version()
			: -1;
		currentCatalogRef.replaceCatalogReference(catalog);
		return catalog.getSchema().version() > priorSchemaVersion;
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

		final EngineState.Builder<LogRecordReference> engineStateBuilder = EngineState
			.builder(this.engineState)
			.version(this.engineState.version())
			.catalogFolders(bindingsRequiring(this.engineState.catalogFolders(), catalog.getName()));

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
	public EngineState<LogRecordReference> engineState(
		@Nonnull LogRecordReference walFileReference,
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
		private final long startVersion;
		private long version;
		@Nonnull private final HashMap<String, CatalogWrapper> catalogs;
		@Nonnull private String[] activeCatalogs;
		@Nonnull private String[] inactiveCatalogs;
		@Nonnull private String[] readOnlyCatalogs;
		@Nonnull private String[] missingCatalogs;
		@Nonnull private CatalogFolderBinding[] catalogFolders;
		@Nonnull private RetiredFolder[] retiredFolders;

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
			this.missingCatalogs = base.engineState.missingCatalogs();
			this.catalogFolders = base.engineState.catalogFolders();
			this.retiredFolders = base.engineState.retiredFolders();
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
		 * Stages a catalog the engine state already knows.
		 * If the catalog is a live Catalog instance it will be marked active, otherwise inactive.
		 *
		 * The catalog keeps the folder binding it already has. A name arriving here unbound is a programming
		 * error — use {@link #withCatalog(CatalogContract, CatalogFolderId)} to register a name for the first
		 * time, which is the only way a binding is ever established.
		 *
		 * @param catalog catalog to stage; must already be bound to a folder
		 * @return this builder instance
		 */
		@Nonnull
		public Builder withCatalog(@Nonnull CatalogContract catalog) {
			this.catalogFolders = bindingsRequiring(this.catalogFolders, catalog.getName());
			return stageCatalog(catalog);
		}

		/**
		 * Stages a catalog the engine state does not know yet, binding it to the passed folder.
		 *
		 * This is the only entry point that establishes a binding, and it is deliberately separate from
		 * {@link #withCatalog(CatalogContract)}: the folder a new catalog occupies is decided by whoever
		 * materialised it — a create and a restore allocate one, boot discovery adopts the one it found — and
		 * that decision must travel to the state rather than being re-derived from the catalog's name here.
		 *
		 * Re-staging a name that *is* already bound leaves its binding untouched, so passing a token for a
		 * catalog that turns out to be known is harmless rather than a silent relocation.
		 *
		 * @param catalog  catalog to stage
		 * @param folderId folder the catalog occupies, used only when the name carries no binding yet
		 * @return this builder instance
		 */
		@Nonnull
		public Builder withCatalog(@Nonnull CatalogContract catalog, @Nonnull CatalogFolderId folderId) {
			this.catalogFolders = bindingsIncluding(this.catalogFolders, catalog.getName(), folderId);
			return stageCatalog(catalog);
		}

		/**
		 * Points a catalog name at a folder, overwriting whatever it was bound to before.
		 *
		 * This is the one entry point that overwrites an existing binding, and it is the whole of a rename and a
		 * replace: no folder is created, none is deleted, nothing is copied, and not a single byte moves on disk.
		 * `renameCatalog(A → B)` makes `B` name the folder `A` was in; `replaceCatalog(A → B)` does the same and
		 * leaves `B`'s former folder to be tombstoned by the caller.
		 *
		 * The distinction from {@link #withCatalog(CatalogContract, CatalogFolderId)} is deliberate and worth
		 * keeping: that one establishes a binding for a name the state has never seen and leaves an existing one
		 * untouched, so a create or a restore cannot silently relocate a live catalog by passing a stale token.
		 * Only the pointer swap is allowed to repoint a name, and only because repointing *is* the operation.
		 *
		 * @param catalog  catalog whose name is being pointed at the folder
		 * @param folderId folder the catalog now occupies
		 * @return this builder instance
		 */
		@Nonnull
		public Builder withCatalogBoundTo(@Nonnull CatalogContract catalog, @Nonnull CatalogFolderId folderId) {
			this.catalogFolders = EngineState.withBinding(
				this.catalogFolders, new CatalogFolderBinding(catalog.getName(), folderId)
			);
			return stageCatalog(catalog);
		}

		/**
		 * Moves the catalog into the bucket its type implies, leaving folder bindings alone.
		 *
		 * @param catalog catalog to stage
		 * @return this builder instance
		 */
		@Nonnull
		private Builder stageCatalog(@Nonnull CatalogContract catalog) {
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
		 * Stages a transient placeholder (typically an {@link io.evitadb.core.catalog.UnusableCatalog})
		 * for a catalog that is mid-flight in a state transition — e.g. `BEING_UPGRADED`,
		 * `BEING_ACTIVATED` — without touching the persisted bucket arrays.
		 *
		 * This is the escape hatch the upgrade operator uses so a crash mid-transition leaves the
		 * catalog name in whatever bucket (`activeCatalogs` / `inactiveCatalogs`) it was in before,
		 * allowing the next boot to auto-retry the operation. Unlike {@link #withCatalog}, this method
		 * never relocates the name between the arrays; only the in-memory catalogs map is updated.
		 *
		 * @param placeholder the transient in-flight placeholder to install under its own name
		 * @return this builder instance
		 */
		@Nonnull
		public Builder withInFlightPlaceholder(@Nonnull CatalogContract placeholder) {
			this.catalogs.put(placeholder.getName(), new CatalogWrapper(placeholder));
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
		 *
		 * The catalog's folder binding goes with it — nothing points at that folder any more. Recording the
		 * folder as a tombstone so the engine may later delete it is a separate concern and belongs to whoever
		 * knows whether the data is meant to survive the removal: a drop retires the folder, whereas a rename
		 * unbinds the old name while the very same folder stays bound to the new one.
		 */
		@Nonnull
		public Builder withoutCatalog(@Nonnull String catalogName) {
			this.catalogs.remove(catalogName);
			this.activeCatalogs = removeRecordFromOrderedArray(catalogName, this.activeCatalogs);
			this.inactiveCatalogs = removeRecordFromOrderedArray(catalogName, this.inactiveCatalogs);
			this.readOnlyCatalogs = removeRecordFromOrderedArray(catalogName, this.readOnlyCatalogs);
			this.missingCatalogs = removeRecordFromOrderedArray(catalogName, this.missingCatalogs);
			this.catalogFolders = EngineState.withoutBinding(this.catalogFolders, catalogName);
			return this;
		}

		/**
		 * Stages the transition of the specified catalog to the MISSING bucket. The catalog is removed from the
		 * active / inactive / read-only arrays and its in-memory `CatalogWrapper` is dropped — MISSING catalogs
		 * cannot serve any requests. The catalog name is added to the `missingCatalogs` array so it remains visible
		 * to the engine and so a later boot can recover it: reconciliation sorts a name whose folder is back into
		 * the `reappeared` bucket of `CatalogInventoryDivergence`, and `Evita` drains that bucket by dispatching
		 * a `RestoreCatalogSchemaMutation` per name. Its operator calls
		 * {@link #withCatalogNoLongerMissing(String)}, which clears the bucket entry and the binding it kept
		 * alive, landing the catalog back in the inactive array.
		 *
		 * The folder binding is deliberately kept. It names the folder that went missing, which is precisely what
		 * a later reappearance has to be matched against; dropping it would leave the recovered folder
		 * indistinguishable from one an operator hand-placed.
		 *
		 * @param catalogName name of the catalog to mark as missing; must not be null
		 * @return this builder instance
		 */
		@Nonnull
		public Builder withMissingCatalog(@Nonnull String catalogName) {
			this.catalogs.remove(catalogName);
			this.activeCatalogs = removeRecordFromOrderedArray(catalogName, this.activeCatalogs);
			this.inactiveCatalogs = removeRecordFromOrderedArray(catalogName, this.inactiveCatalogs);
			this.readOnlyCatalogs = removeRecordFromOrderedArray(catalogName, this.readOnlyCatalogs);
			this.missingCatalogs = insertRecordIntoOrderedArray(catalogName, this.missingCatalogs);
			return this;
		}

		/**
		 * Stages removal of the catalog from the `missingCatalogs` bucket, **and drops the binding that bucket
		 * entry was keeping alive**.
		 *
		 * Both halves are needed, and the second one is the whole point. {@link #withMissingCatalog(String)}
		 * deliberately keeps the binding, because it names the folder that vanished and that is what a later
		 * reappearance is matched against. But a name that is still bound is a name
		 * {@link #withCatalog(CatalogContract, CatalogFolderId)} will not rebind — it establishes a binding only
		 * for a name the state has never seen. So clearing the bucket alone leaves the catalog pointing at the
		 * folder that went missing while its real data sits in the folder this operation just filled, and the
		 * next boot stages the name MISSING all over again.
		 *
		 * Dropping the binding here rather than widening `withCatalog` is deliberate: the three-way split between
		 * `withCatalog(catalog)`, `withCatalog(catalog, folderId)` and `withCatalogBoundTo(...)` is what stops a
		 * create carrying a stale token from silently relocating a live catalog, and it is worth keeping. Making
		 * the binding *absent* leaves that guard intact and simply tells the truth: a catalog whose folder is
		 * gone is bound to nothing.
		 *
		 * The call is a no-op — on both arrays — when the catalog is not in the missing bucket, so it is safe to
		 * chain unconditionally. Every path that re-registers a name which may be missing must call it first:
		 * recovery, restore-from-backup, auto-discovery, a fresh create under the name, and a replace onto it.
		 *
		 * @param catalogName name of the catalog that is no longer missing
		 * @return this builder instance
		 */
		@Nonnull
		public Builder withCatalogNoLongerMissing(@Nonnull String catalogName) {
			final String[] remainingMissing = removeRecordFromOrderedArray(catalogName, this.missingCatalogs);
			//noinspection ArrayEquality - the helper returns the very same instance when nothing was removed
			if (remainingMissing != this.missingCatalogs) {
				this.missingCatalogs = remainingMissing;
				this.catalogFolders = EngineState.withoutBinding(this.catalogFolders, catalogName);
			}
			return this;
		}

		/**
		 * Records that nothing points at the passed folder any more and that the engine owes its deletion.
		 *
		 * This is what makes a folder removal non-blocking: the tombstone is durable *before* anything touches the
		 * filesystem, so a delete that the operating system refuses merely postpones the work to the next boot
		 * instead of failing the operation the user asked for. It is also the only positive evidence of ownership
		 * that authorises deleting a folder the engine no longer references — without it such a folder classifies
		 * as unclaimed, which is deliberately never destroyed.
		 *
		 * Stage it in the **same commit** that unbinds the folder. A tombstone written afterwards is not durable
		 * across the crash it exists to survive, and one written before would authorise deleting a folder that is
		 * still live if the commit never happens.
		 *
		 * @param catalogName name of the catalog whose data the folder holds, carried for diagnostics only
		 * @param folderId    folder awaiting deletion
		 * @return this builder instance
		 */
		@Nonnull
		public Builder withRetiredFolder(@Nonnull String catalogName, @Nonnull CatalogFolderId folderId) {
			this.retiredFolders = EngineState.withRetiredFolder(
				this.retiredFolders, new RetiredFolder(catalogName, folderId)
			);
			return this;
		}

		/**
		 * Drops the tombstones of folders whose deletion has since been confirmed.
		 *
		 * Applied centrally by the engine-state commit path rather than by the operators, so that a tombstone is
		 * discharged by *any* subsequent engine mutation instead of only by whoever happened to delete the folder.
		 * Nothing else would ever drop it: a folder that is gone is never classified again, so the entry would
		 * otherwise be carried in persisted state forever.
		 *
		 * @param drainedFolders folders whose removal is confirmed; entries that were never tombstoned are ignored
		 * @return this builder instance
		 */
		@Nonnull
		public Builder withoutRetiredFolders(@Nonnull Set<CatalogFolderId> drainedFolders) {
			this.retiredFolders = EngineState.withoutRetiredFolders(this.retiredFolders, drainedFolders);
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
			final EngineState.Builder<LogRecordReference> engineStateBuilder = EngineState
				.builder(this.base.engineState)
				.version(this.version)
				.activeCatalogs(this.activeCatalogs)
				.inactiveCatalogs(this.inactiveCatalogs)
				.readOnlyCatalogs(this.readOnlyCatalogs)
				.missingCatalogs(this.missingCatalogs)
				.catalogFolders(this.catalogFolders)
				.retiredFolders(this.retiredFolders);
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
