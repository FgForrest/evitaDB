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

package io.evitadb.api;

import io.evitadb.api.TransactionContract.CommitBehavior;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.EnumSet;

/**
 * Configuration record for creating evitaDB sessions.
 *
 * **Purpose and Usage**
 *
 * `SessionTraits` encapsulates all configuration options needed to create an {@link EvitaSessionContract}. It defines
 * the session's behavior, access mode, commit semantics, and optional termination callback. Sessions are created via
 * {@link EvitaContract#createSession(SessionTraits)} or {@link EvitaContract#createReadOnlySession(String)}.
 *
 * **Key Configuration Dimensions**
 *
 * - **Access mode**: Read-only vs. read-write ({@link SessionFlags#READ_WRITE})
 * - **Commit behavior**: When to consider changes committed ({@link CommitBehavior})
 * - **Dry-run mode**: Test changes without persisting ({@link SessionFlags#DRY_RUN})
 * - **Binary mode**: Internal API mode for raw binary entities ({@link SessionFlags#BINARY})
 * - **Termination callback**: Optional cleanup logic on session close
 *
 * **Common Usage Patterns**
 *
 * Read-only session (default):
 * ```
 * new SessionTraits("catalogName")
 * ```
 *
 * Read-write session with default commit behavior:
 * ```
 * new SessionTraits("catalogName", SessionFlags.READ_WRITE)
 * ```
 *
 * Read-write with custom commit behavior:
 * ```
 * new SessionTraits("catalogName", CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, SessionFlags.READ_WRITE)
 * ```
 *
 * Session with termination callback:
 * ```
 * new SessionTraits("catalogName", session -> logger.info("Session closed"), SessionFlags.READ_WRITE)
 * ```
 *
 * **Thread-Safety**
 *
 * This record is immutable and thread-safe. Multiple threads can safely share the same `SessionTraits` instance to
 * create sessions with identical configuration.
 *
 * @param catalogName     unique name of the catalog, refers to {@link CatalogContract#getName()}
 * @param flags           flags that alter session behaviour (read-only, read-write, dry-run, binary mode)
 * @param commitBehaviour when to consider a transaction committed (conflict resolution, WAL persistence, changes visible)
 * @param onTermination   callback function that will be executed once the session is closed (optional)
 */
public record SessionTraits(
	@Nonnull String catalogName,
	@Nonnull EnumSet<SessionFlags> flags,
	@Nullable CommitBehavior commitBehaviour,
	@Nullable EvitaSessionTerminationCallback onTermination
) {

	public SessionTraits(@Nonnull String catalogName) {
		this(catalogName, null, CommitBehavior.defaultBehaviour(), (SessionFlags[]) null);
	}

	public SessionTraits(@Nonnull String catalogName, @Nullable CommitBehavior commitBehaviour) {
		this(catalogName, null, commitBehaviour, (SessionFlags[]) null);
	}

	public SessionTraits(
		@Nonnull String catalogName, @Nullable CommitBehavior commitBehaviour, @Nullable SessionFlags... flags) {
		this(catalogName, null, commitBehaviour, flags);
	}

	public SessionTraits(
		@Nonnull String catalogName,
		@Nullable EvitaSessionTerminationCallback onTermination,
		@Nullable SessionFlags... flags
	) {
		this(catalogName, onTermination, CommitBehavior.defaultBehaviour(), flags);
	}

	public SessionTraits(
		@Nonnull String catalogName,
		@Nullable EvitaSessionTerminationCallback onTermination,
		@Nullable CommitBehavior commitBehaviour,
		@Nullable SessionFlags... flags
	) {
		this(
			catalogName,
			flags == null || flags.length == 0 ?
				EnumSet.noneOf(SessionFlags.class) :
				(flags.length == 1
					? EnumSet.of(flags[0])
					: EnumSet.of(flags[0], Arrays.copyOfRange(flags, 1, flags.length))),
			commitBehaviour == null ? CommitBehavior.defaultBehaviour() : commitBehaviour,
			onTermination
		);
	}

	public SessionTraits(@Nonnull String catalogName, @Nullable SessionFlags... flags) {
		this(catalogName, null, CommitBehavior.defaultBehaviour(), flags);
	}

	/**
	 * Returns `true` if the session operates in binary mode, returning entity bodies in raw binary format.
	 *
	 * Binary mode is an internal API feature used for low-level evitaDB operations and inter-node communication.
	 * Client applications typically do not use this mode.
	 *
	 * @return `true` if {@link SessionFlags#BINARY} is set, `false` otherwise
	 */
	public boolean isBinary() {
		return this.flags.contains(SessionFlags.BINARY);
	}

	/**
	 * Returns `true` if the session operates in dry-run mode, where changes are validated but never persisted.
	 *
	 * In dry-run mode, all write operations are processed normally (including validation, conflict detection, and
	 * index updates), but all changes are rolled back on session close. This mode is useful for testing mutations
	 * without affecting the catalog state.
	 *
	 * @return `true` if {@link SessionFlags#DRY_RUN} is set, `false` otherwise
	 */
	public boolean isDryRun() {
		return this.flags.contains(SessionFlags.DRY_RUN);
	}

	/**
	 * Returns `true` if the session accepts write requests (mutations), `false` if read-only.
	 *
	 * Read-write sessions can execute entity mutations and schema changes. Read-only sessions reject write operations
	 * with {@link io.evitadb.api.exception.ReadOnlyException}.
	 *
	 * @return `true` if {@link SessionFlags#READ_WRITE} is set, `false` otherwise
	 */
	public boolean isReadWrite() {
		return this.flags.contains(SessionFlags.READ_WRITE);
	}

	/**
	 * Enumeration of session behavior flags that can be combined when creating an evitaDB session.
	 *
	 * Flags are combined via varargs constructors in {@link SessionTraits} to control session access mode and behavior.
	 */
	public enum SessionFlags {

		/**
		 * Enables dry-run mode where all changes are validated but rolled back on session close.
		 *
		 * **Behavior**
		 *
		 * When this flag is set, the session processes write operations normally (validation, conflict detection,
		 * transactional logic), but all changes are discarded when the session closes. No data is persisted to disk
		 * or made visible to other sessions.
		 *
		 * **Use Cases**
		 *
		 * - Testing mutations for validation errors without affecting the catalog
		 * - Simulating "what-if" scenarios in data import scripts
		 * - Debugging complex transaction logic
		 *
		 * **Note**: Dry-run mode requires {@link #READ_WRITE} to be set; otherwise, the session cannot accept mutations.
		 */
		DRY_RUN,

		/**
		 * Enables read-write mode, allowing the session to accept entity and schema mutations.
		 *
		 * **Behavior**
		 *
		 * When this flag is set, the session can execute:
		 * - Entity upserts and deletes ({@link EvitaSessionContract#upsertEntity(java.io.Serializable)})
		 * - Schema mutations ({@link EvitaSessionContract#updateEntitySchema}, etc.)
		 * - Collection operations (delete, rename, replace)
		 *
		 * Without this flag, the session is read-only and rejects write operations with
		 * {@link io.evitadb.api.exception.ReadOnlyException}.
		 *
		 * **Concurrency**
		 *
		 * Multiple read-write sessions can operate concurrently. evitaDB uses optimistic locking with conflict
		 * detection at commit time.
		 */
		READ_WRITE,

		/**
		 * Enables binary mode where entity bodies are returned in raw binary format (internal API).
		 *
		 * **Behavior**
		 *
		 * When this flag is set, entities are not deserialized into {@link io.evitadb.api.requestResponse.data.SealedEntity}
		 * objects but returned as binary blobs. This mode is used internally by evitaDB for:
		 * - Inter-node replication in distributed setups
		 * - Low-level storage operations
		 * - Performance-critical internal APIs
		 *
		 * **Warning**
		 *
		 * This flag is for evitaDB internal use only. Client applications should not use binary mode.
		 */
		BINARY

	}

}
