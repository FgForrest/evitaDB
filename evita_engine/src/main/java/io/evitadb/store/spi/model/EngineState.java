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


import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.spi.model.reference.LogFileRecordReference;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
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
 * - list of read-only catalogs
 *
 * This record is immutable, but provides a builder for creating modified instances.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record EngineState(
	int storageProtocolVersion,
	long version,
	@Nonnull OffsetDateTime introducedAt,
	@Nullable LogFileRecordReference walFileReference,
	@Nonnull String[] activeCatalogs,
	@Nonnull String[] inactiveCatalogs,
	@Nonnull String[] readOnlyCatalogs
) implements Serializable {
	@Serial private static final long serialVersionUID = 3167647107268939398L;

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
	 * Verifies that the given array of items is sorted in ascending order based on their natural ordering.
	 * If the array is not sorted, an internal error is thrown with a descriptive message.
	 *
	 * @param type  a descriptive name or type associated with the items being validated; used in the error message if the assertion fails
	 * @param items the array of strings to be validated for ascending order
	 * @throws GenericEvitaInternalError if the array is not sorted in ascending order
	 */
	private static void assertSorted(@Nonnull String type, @Nonnull String[] items) {
		String previousCatalog = null;
		for (int i = 0; i < items.length; i++) {
			if (i > 0) {
				Assert.isPremiseValid(
					previousCatalog == null || previousCatalog.compareTo(items[i]) < 0,
					type + " catalogs must be sorted in ascending order, but found: " + Arrays.toString(items)
				);
			}
			previousCatalog = items[i];
		}
	}

	public EngineState {
		assertSorted("Active", activeCatalogs);
		assertSorted("Inactive", inactiveCatalogs);
		assertSorted("Read-only", readOnlyCatalogs);
	}

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof final EngineState that)) return false;

		return this.version == that.version && this.storageProtocolVersion == that.storageProtocolVersion && Arrays.equals(
			this.activeCatalogs, that.activeCatalogs) && Arrays.equals(
			this.inactiveCatalogs, that.inactiveCatalogs) && Arrays.equals(
			this.readOnlyCatalogs, that.readOnlyCatalogs) && this.introducedAt.equals(
			that.introducedAt) && Objects.equals(
			this.walFileReference, that.walFileReference);
	}

	@Override
	public int hashCode() {
		int result = this.storageProtocolVersion;
		result = 31 * result + Long.hashCode(this.version);
		result = 31 * result + this.introducedAt.hashCode();
		result = 31 * result + Objects.hashCode(this.walFileReference);
		result = 31 * result + Arrays.hashCode(this.activeCatalogs);
		result = 31 * result + Arrays.hashCode(this.inactiveCatalogs);
		result = 31 * result + Arrays.hashCode(this.readOnlyCatalogs);
		return result;
	}

	@Nonnull
	@Override
	public String toString() {
		return "EngineState{" +
			"storageProtocolVersion=" + this.storageProtocolVersion +
			", version=" + this.version +
			", introducedAt=" + this.introducedAt +
			", walFileReference=" + this.walFileReference +
			", activeCatalogs=" + Arrays.toString(this.activeCatalogs) +
			", inactiveCatalogs=" + Arrays.toString(this.inactiveCatalogs) +
			", readOnlyCatalogs=" + Arrays.toString(this.readOnlyCatalogs) +
			'}';
	}

	/**
	 * Builder for creating modified instances of EngineState.
	 */
	public static class Builder {
		private int storageProtocolVersion;
		private long version;
		@Nullable
		private LogFileRecordReference walFileReference;
		@Nonnull
		private String[] activeCatalogs = new String[0];
		@Nonnull
		private String[] inactiveCatalogs = new String[0];
		@Nonnull
		private String[] readOnlyCatalogs = new String[0];

		Builder() {
		}

		Builder(@Nonnull EngineState engineState) {
			this.storageProtocolVersion = engineState.storageProtocolVersion;
			this.version = engineState.version;
			this.walFileReference = engineState.walFileReference;
			this.activeCatalogs = Arrays.copyOf(engineState.activeCatalogs, engineState.activeCatalogs.length);
			this.inactiveCatalogs = Arrays.copyOf(engineState.inactiveCatalogs, engineState.inactiveCatalogs.length);
			this.readOnlyCatalogs = Arrays.copyOf(engineState.readOnlyCatalogs, engineState.readOnlyCatalogs.length);
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
		public Builder walFileReference(@Nullable LogFileRecordReference walFileReference) {
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
			this.activeCatalogs = Optional
				.ofNullable(activeCatalogs)
				.map(catalogs -> {
					final String[] sortedCopy = Arrays.copyOf(catalogs, catalogs.length);
					Arrays.sort(sortedCopy);
					return sortedCopy;
				})
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
			this.inactiveCatalogs = Optional
				.ofNullable(inactiveCatalogs)
				.map(catalogs -> {
					final String[] sortedCopy = Arrays.copyOf(catalogs, catalogs.length);
					Arrays.sort(sortedCopy);
					return sortedCopy;
				})
				.orElse(new String[0]);
			return this;
		}

		/**
		 * Sets the read-only catalogs.
		 *
		 * @param readOnlyCatalogs read-only catalogs
		 * @return this builder instance
		 */
		@Nonnull
		public Builder readOnlyCatalogs(@Nonnull String[] readOnlyCatalogs) {
			this.readOnlyCatalogs = Optional
				.ofNullable(readOnlyCatalogs)
				.map(catalogs -> {
					final String[] sortedCopy = Arrays.copyOf(catalogs, catalogs.length);
					Arrays.sort(sortedCopy);
					return sortedCopy;
				})
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
				OffsetDateTime.now(),
				this.walFileReference,
				this.activeCatalogs,
				this.inactiveCatalogs,
				this.readOnlyCatalogs
			);
		}
	}

}
