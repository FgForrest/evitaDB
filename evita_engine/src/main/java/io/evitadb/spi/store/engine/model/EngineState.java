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

package io.evitadb.spi.store.engine.model;


import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;

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
 * - list of catalogs whose on-disk folder is no longer present (`missingCatalogs`)
 *
 * This record is immutable, but provides a builder for creating modified instances.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public record EngineState<T extends LogRecordReference>(
	int storageProtocolVersion,
	long version,
	@Nonnull OffsetDateTime introducedAt,
	@Nullable T walReference,
	@Nonnull String[] activeCatalogs,
	@Nonnull String[] inactiveCatalogs,
	@Nonnull String[] readOnlyCatalogs,
	@Nonnull String[] missingCatalogs
) implements Serializable {
	@Serial private static final long serialVersionUID = 7261559824913670482L;

	/**
	 * Returns a new builder initialized with default values.
	 *
	 * @return new builder instance
	 */
	@Nonnull
	public static <T extends LogRecordReference> Builder<T> builder() {
		return new Builder<>();
	}

	/**
	 * Returns a new builder initialized with values from the current instance.
	 *
	 * @return new builder instance
	 */
	@Nonnull
	public static <T extends LogRecordReference> Builder<T> builder(@Nonnull EngineState<T> engineState) {
		return new Builder<>(engineState);
	}

	/**
	 * Verifies that the given array of items is strictly ascending (no duplicates). If the array is not strictly
	 * ascending, an internal error is thrown with a descriptive message.
	 *
	 * @param type  a descriptive name or type associated with the items being validated; used in the error message
	 *              if the assertion fails
	 * @param items the array of strings to be validated
	 * @throws GenericEvitaInternalError if the array is not strictly ascending
	 */
	private static void assertSorted(@Nonnull String type, @Nonnull String[] items) {
		for (int i = 1; i < items.length; i++) {
			Assert.isPremiseValid(
				items[i - 1].compareTo(items[i]) < 0,
				() -> type + " catalogs must be strictly ascending (no duplicates), but found: "
					+ Arrays.toString(items)
			);
		}
	}

	/**
	 * Convenience constructor preserving the legacy record shape (without `missingCatalogs`) for call sites that
	 * have not yet adopted the missing-catalog bucket. Instances created through this constructor start with an
	 * empty missing-catalog bucket.
	 */
	public EngineState(
		int storageProtocolVersion,
		long version,
		@Nonnull OffsetDateTime introducedAt,
		@Nullable T walReference,
		@Nonnull String[] activeCatalogs,
		@Nonnull String[] inactiveCatalogs,
		@Nonnull String[] readOnlyCatalogs
	) {
		this(
			storageProtocolVersion,
			version,
			introducedAt,
			walReference,
			activeCatalogs,
			inactiveCatalogs,
			readOnlyCatalogs,
			new String[0]
		);
	}

	public EngineState {
		assertSorted("Active", activeCatalogs);
		assertSorted("Inactive", inactiveCatalogs);
		assertSorted("Read-only", readOnlyCatalogs);
		assertSorted("Missing", missingCatalogs);
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (!(o instanceof final EngineState<?> that)) return false;

		return this.version == that.version && this.storageProtocolVersion == that.storageProtocolVersion && Arrays.equals(
			this.activeCatalogs, that.activeCatalogs) && Arrays.equals(
			this.inactiveCatalogs, that.inactiveCatalogs) && Arrays.equals(
			this.readOnlyCatalogs, that.readOnlyCatalogs) && Arrays.equals(
			this.missingCatalogs, that.missingCatalogs) && this.introducedAt.equals(
			that.introducedAt) && Objects.equals(
			this.walReference, that.walReference);
	}

	@Override
	public int hashCode() {
		int result = this.storageProtocolVersion;
		result = 31 * result + Long.hashCode(this.version);
		result = 31 * result + this.introducedAt.hashCode();
		result = 31 * result + Objects.hashCode(this.walReference);
		result = 31 * result + Arrays.hashCode(this.activeCatalogs);
		result = 31 * result + Arrays.hashCode(this.inactiveCatalogs);
		result = 31 * result + Arrays.hashCode(this.readOnlyCatalogs);
		result = 31 * result + Arrays.hashCode(this.missingCatalogs);
		return result;
	}

	@Nonnull
	@Override
	public String toString() {
		return "EngineState{" +
			"storageProtocolVersion=" + this.storageProtocolVersion +
			", version=" + this.version +
			", introducedAt=" + this.introducedAt +
			", walReference=" + this.walReference +
			", activeCatalogs=" + Arrays.toString(this.activeCatalogs) +
			", inactiveCatalogs=" + Arrays.toString(this.inactiveCatalogs) +
			", readOnlyCatalogs=" + Arrays.toString(this.readOnlyCatalogs) +
			", missingCatalogs=" + Arrays.toString(this.missingCatalogs) +
			'}';
	}

	/**
	 * Builder for creating modified instances of EngineState.
	 */
	public static class Builder<T extends LogRecordReference> {
		/**
		 * Returns a sorted defensive copy of the given array, or an empty array when the source is `null`.
		 * Used by the catalog-list setters to gracefully accept `null` input while still normalizing the
		 * internal representation to a sorted array (matching the record invariant enforced by
		 * `assertSorted`).
		 *
		 * @param src source array; may be `null`
		 * @return sorted copy of `src`, or `new String[0]` when `src` is `null`
		 */
		@Nonnull
		private static String[] sortedCopyOrEmpty(@Nullable String[] src) {
			if (src == null) {
				return new String[0];
			}
			final String[] sortedCopy = Arrays.copyOf(src, src.length);
			Arrays.sort(sortedCopy);
			return sortedCopy;
		}

		private int storageProtocolVersion;
		private long version;
		/**
		 * Explicit introduced-at timestamp. When `null`, `build()` substitutes the current time —
		 * that path is only appropriate for brand-new engine states; copies of existing states
		 * carry the original timestamp forward via the copy-constructor.
		 */
		@Nullable
		private OffsetDateTime introducedAt;
		@Nullable
		private T walReference;
		@Nonnull
		private String[] activeCatalogs = new String[0];
		@Nonnull
		private String[] inactiveCatalogs = new String[0];
		@Nonnull
		private String[] readOnlyCatalogs = new String[0];
		@Nonnull
		private String[] missingCatalogs = new String[0];

		Builder() {
		}

		Builder(@Nonnull EngineState<T> engineState) {
			this.storageProtocolVersion = engineState.storageProtocolVersion;
			this.version = engineState.version;
			this.introducedAt = engineState.introducedAt;
			this.walReference = engineState.walReference;
			this.activeCatalogs = Arrays.copyOf(engineState.activeCatalogs, engineState.activeCatalogs.length);
			this.inactiveCatalogs = Arrays.copyOf(engineState.inactiveCatalogs, engineState.inactiveCatalogs.length);
			this.readOnlyCatalogs = Arrays.copyOf(engineState.readOnlyCatalogs, engineState.readOnlyCatalogs.length);
			this.missingCatalogs = Arrays.copyOf(engineState.missingCatalogs, engineState.missingCatalogs.length);
		}

		/**
		 * Sets the storage protocol version.
		 *
		 * @param storageProtocolVersion storage protocol version
		 * @return this builder instance
		 */
		@Nonnull
		public Builder<T> storageProtocolVersion(int storageProtocolVersion) {
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
		public Builder<T> version(long version) {
			this.version = version;
			return this;
		}

		/**
		 * Sets the introduced-at timestamp explicitly. Normal callers should not need to set this —
		 * the copy-constructor already preserves the original timestamp, and the no-arg builder
		 * defaults to the current time. Exposed primarily for tests and round-trip serialization.
		 *
		 * @param introducedAt introduced-at timestamp; `null` resets to the build-time default (now)
		 * @return this builder instance
		 */
		@Nonnull
		public Builder<T> introducedAt(@Nullable OffsetDateTime introducedAt) {
			this.introducedAt = introducedAt;
			return this;
		}

		/**
		 * Sets the WAL file reference.
		 *
		 * @param walFileReference WAL file reference
		 * @return this builder instance
		 */
		@Nonnull
		public Builder<T> walFileReference(@Nullable T walFileReference) {
			this.walReference = walFileReference;
			return this;
		}

		/**
		 * Sets the active catalogs. The array is defensively copied and sorted; `null` is treated as empty.
		 *
		 * @param activeCatalogs active catalogs; may be `null`
		 * @return this builder instance
		 */
		@Nonnull
		public Builder<T> activeCatalogs(@Nullable String[] activeCatalogs) {
			this.activeCatalogs = sortedCopyOrEmpty(activeCatalogs);
			return this;
		}

		/**
		 * Sets the inactive catalogs. The array is defensively copied and sorted; `null` is treated as empty.
		 *
		 * @param inactiveCatalogs inactive catalogs; may be `null`
		 * @return this builder instance
		 */
		@Nonnull
		public Builder<T> inactiveCatalogs(@Nullable String[] inactiveCatalogs) {
			this.inactiveCatalogs = sortedCopyOrEmpty(inactiveCatalogs);
			return this;
		}

		/**
		 * Sets the read-only catalogs. The array is defensively copied and sorted; `null` is treated as empty.
		 *
		 * @param readOnlyCatalogs read-only catalogs; may be `null`
		 * @return this builder instance
		 */
		@Nonnull
		public Builder<T> readOnlyCatalogs(@Nullable String[] readOnlyCatalogs) {
			this.readOnlyCatalogs = sortedCopyOrEmpty(readOnlyCatalogs);
			return this;
		}

		/**
		 * Sets the missing catalogs (catalogs whose on-disk folder is no longer present). The array is defensively
		 * copied and sorted; `null` is treated as empty.
		 *
		 * @param missingCatalogs missing catalogs; may be `null`
		 * @return this builder instance
		 */
		@Nonnull
		public Builder<T> missingCatalogs(@Nullable String[] missingCatalogs) {
			this.missingCatalogs = sortedCopyOrEmpty(missingCatalogs);
			return this;
		}

		/**
		 * Builds a new EngineState instance with the current builder values.
		 *
		 * If no `introducedAt` value was carried over from a copy-constructor or set explicitly, the
		 * current time is used — that's the right default only for genuinely new engine states.
		 * Reconciliation rewrites (e.g. `DefaultEnginePersistenceService.syncEngineStateByFolderContents`)
		 * must go through `builder(EngineState)` so the original timestamp is preserved.
		 *
		 * @return new EngineState instance
		 */
		@Nonnull
		public EngineState<T> build() {
			return new EngineState<>(
				this.storageProtocolVersion,
				this.version,
				this.introducedAt == null ? OffsetDateTime.now() : this.introducedAt,
				this.walReference,
				this.activeCatalogs,
				this.inactiveCatalogs,
				this.readOnlyCatalogs,
				this.missingCatalogs
			);
		}
	}

}
