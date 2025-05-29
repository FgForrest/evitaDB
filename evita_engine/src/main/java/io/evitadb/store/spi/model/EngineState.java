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

package io.evitadb.store.spi.model;


import io.evitadb.store.spi.model.reference.WalFileReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Optional;

/**
 * EngineState represents the current state of the evitaDB engine.
 *
 * It contains information about:
 * - storage protocol version
 * - current version of the engine state
 * - reference to the WAL (Write-Ahead Log) file
 * - list of active catalogs
 * - list of inactive catalogs
 *
 * This record is immutable, but provides a builder for creating modified instances.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record EngineState(
	int storageProtocolVersion,
	long version,
	@Nullable WalFileReference walFileReference,
	@Nonnull String[] activeCatalogs,
	@Nonnull String[] inactiveCatalogs
) {

	/**
	 * Returns a new builder initialized with default values.
	 *
	 * @return new builder instance
	 */
	@Nonnull
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Returns a new builder initialized with values from the current instance.
	 *
	 * @return new builder instance
	 */
	@Nonnull
	public static Builder builder(@Nonnull EngineState engineState) {
		return new Builder(engineState);
	}

	/**
	 * Returns a new instance with updated storage protocol version.
	 *
	 * @param storageProtocolVersion new storage protocol version
	 * @return new instance with updated storage protocol version
	 */
	@Nonnull
	public EngineState withStorageProtocolVersion(int storageProtocolVersion) {
		return new EngineState(
			storageProtocolVersion,
			this.version,
			this.walFileReference,
			this.activeCatalogs,
			this.inactiveCatalogs
		);
	}

	/**
	 * Returns a new instance with updated version.
	 *
	 * @param version new version
	 * @return new instance with updated version
	 */
	@Nonnull
	public EngineState withVersion(long version) {
		return new EngineState(
			this.storageProtocolVersion,
			version,
			this.walFileReference,
			this.activeCatalogs,
			this.inactiveCatalogs
		);
	}

	/**
	 * Returns a new instance with updated WAL file reference.
	 *
	 * @param walFileReference new WAL file reference
	 * @return new instance with updated WAL file reference
	 */
	@Nonnull
	public EngineState withWalFileReference(@Nullable WalFileReference walFileReference) {
		return new EngineState(
			this.storageProtocolVersion,
			this.version,
			walFileReference,
			this.activeCatalogs,
			this.inactiveCatalogs
		);
	}

	/**
	 * Returns a new instance with updated active catalogs.
	 *
	 * @param activeCatalogs new active catalogs
	 * @return new instance with updated active catalogs
	 */
	@Nonnull
	public EngineState withActiveCatalogs(@Nonnull String[] activeCatalogs) {
		return new EngineState(
			this.storageProtocolVersion,
			this.version,
			this.walFileReference,
			activeCatalogs,
			this.inactiveCatalogs
		);
	}

	/**
	 * Returns a new instance with updated inactive catalogs.
	 *
	 * @param inactiveCatalogs new inactive catalogs
	 * @return new instance with updated inactive catalogs
	 */
	@Nonnull
	public EngineState withInactiveCatalogs(@Nonnull String[] inactiveCatalogs) {
		return new EngineState(
			this.storageProtocolVersion,
			this.version,
			this.walFileReference,
			this.activeCatalogs,
			inactiveCatalogs
		);
	}

	/**
	 * Builder for creating modified instances of EngineState.
	 */
	public static class Builder {
		private int storageProtocolVersion;
		private long version;
		@Nullable
		private WalFileReference walFileReference;
		@Nonnull
		private String[] activeCatalogs = new String[0];
		@Nonnull
		private String[] inactiveCatalogs = new String[0];

		Builder() {
		}

		Builder(@Nonnull EngineState engineState) {
			this.storageProtocolVersion = engineState.storageProtocolVersion;
			this.version = engineState.version;
			this.walFileReference = engineState.walFileReference;
			this.activeCatalogs = Arrays.copyOf(engineState.activeCatalogs, engineState.activeCatalogs.length);
			this.inactiveCatalogs = Arrays.copyOf(engineState.inactiveCatalogs, engineState.inactiveCatalogs.length);
		}

		/**
		 * Sets the storage protocol version.
		 *
		 * @param storageProtocolVersion storage protocol version
		 * @return this builder instance
		 */
		@Nonnull
		public Builder storageProtocolVersion(int storageProtocolVersion) {
			this.storageProtocolVersion = storageProtocolVersion;
			return this;
		}

		/**
		 * Sets the version.
		 *
		 * @param version version
		 * @return this builder instance
		 */
		@Nonnull
		public Builder version(long version) {
			this.version = version;
			return this;
		}

		/**
		 * Sets the WAL file reference.
		 *
		 * @param walFileReference WAL file reference
		 * @return this builder instance
		 */
		@Nonnull
		public Builder walFileReference(@Nullable WalFileReference walFileReference) {
			this.walFileReference = walFileReference;
			return this;
		}

		/**
		 * Sets the active catalogs.
		 *
		 * @param activeCatalogs active catalogs
		 * @return this builder instance
		 */
		@Nonnull
		public Builder activeCatalogs(@Nonnull String[] activeCatalogs) {
			this.activeCatalogs = Optional.ofNullable(activeCatalogs)
			                              .map(catalogs -> Arrays.copyOf(catalogs, catalogs.length))
			                              .orElse(new String[0]);
			return this;
		}

		/**
		 * Sets the inactive catalogs.
		 *
		 * @param inactiveCatalogs inactive catalogs
		 * @return this builder instance
		 */
		@Nonnull
		public Builder inactiveCatalogs(@Nonnull String[] inactiveCatalogs) {
			this.inactiveCatalogs = Optional.ofNullable(inactiveCatalogs)
			                                .map(catalogs -> Arrays.copyOf(catalogs, catalogs.length))
			                                .orElse(new String[0]);
			return this;
		}

		/**
		 * Builds a new EngineState instance with the current builder values.
		 *
		 * @return new EngineState instance
		 */
		@Nonnull
		public EngineState build() {
			return new EngineState(
				this.storageProtocolVersion,
				this.version,
				this.walFileReference,
				this.activeCatalogs,
				this.inactiveCatalogs
			);
		}
	}
}
