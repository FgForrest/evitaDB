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
 * Session traits contains all input information that allows creating new session and defining its behaviour.
 *
 * @param catalogName   unique name of the catalog, refers to {@link CatalogContract#getName()}
 * @param flags         flags that alter session behaviour
 * @param onTermination callback function that will be executed once the session is closed
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

	public SessionTraits(@Nonnull String catalogName, @Nullable CommitBehavior commitBehaviour, @Nullable SessionFlags... flags) {
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
				(flags.length == 1 ? EnumSet.of(flags[0]) : EnumSet.of(flags[0], Arrays.copyOfRange(flags, 1, flags.length))),
			commitBehaviour == null ? CommitBehavior.defaultBehaviour() : commitBehaviour,
			onTermination
		);
	}

	public SessionTraits(@Nonnull String catalogName, @Nullable SessionFlags... flags) {
		this(catalogName, null, CommitBehavior.defaultBehaviour(), flags);
	}

	/**
	 * When TRUE all entity bodies in this session are returned in RAW binary format, used in PRIVATE API.
	 */
	public boolean isBinary() {
		return this.flags.contains(SessionFlags.BINARY);
	}

	/**
	 * When TRUE all opened transaction will be rolled back on close, even though the session is
	 * read-write no change would really occur.
	 */
	public boolean isDryRun() {
		return this.flags.contains(SessionFlags.DRY_RUN);
	}

	/**
	 * When TRUE the session will accept write requests, otherwise its considered to be read-only.
	 */
	public boolean isReadWrite() {
		return this.flags.contains(SessionFlags.READ_WRITE);
	}

	/**
	 * Contains set of all flags, that be used when opening session.
	 */
	public enum SessionFlags {

		/**
		 * When flag is used all opened transaction will be rolled back on close, even though the session is
		 * read-write no change would really occur.
		 */
		DRY_RUN,

		/**
		 * When flag is used the session will accept write requests, otherwise its considered to be read-only.
		 */
		READ_WRITE,

		/**
		 * When flag is used all entity bodies in this session are returned in RAW binary format, used in PRIVATE API.
		 */
		BINARY

	}

}
