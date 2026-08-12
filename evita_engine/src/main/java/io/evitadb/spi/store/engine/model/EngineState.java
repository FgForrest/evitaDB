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
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import static io.evitadb.utils.ArrayUtils.binarySearch;

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
 * - the folder each catalog's data lives in (`catalogFolders`)
 * - folders no catalog points at any more, awaiting deletion (`retiredFolders`)
 * - the highest folder generation handed out per catalog name (`generationPeaks`)
 *
 * The last three make this record the **sole authority** for the mapping between a catalog and its storage
 * folder. Nothing on disk outside the engine bootstrap may be consulted to answer "which folder is catalog
 * `X`?" — which is what lets a rename or a replace be committed by publishing a new binding rather than by
 * physically renaming directories. See {@link CatalogFolderId}.
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
	@Nonnull String[] missingCatalogs,
	@Nonnull CatalogFolderBinding[] catalogFolders,
	@Nonnull RetiredFolder[] retiredFolders,
	@Nonnull CatalogGenerationPeak[] generationPeaks
) implements Serializable {
	@Serial private static final long serialVersionUID = 3948172605583194127L;

	/**
	 * Empty binding array, handed out instead of allocating a fresh zero-length array per call site.
	 */
	public static final CatalogFolderBinding[] NO_FOLDER_BINDINGS = new CatalogFolderBinding[0];
	/**
	 * Empty tombstone array, handed out instead of allocating a fresh zero-length array per call site.
	 */
	public static final RetiredFolder[] NO_RETIRED_FOLDERS = new RetiredFolder[0];
	/**
	 * Empty generation-peak array, handed out instead of allocating a fresh zero-length array per call site.
	 */
	public static final CatalogGenerationPeak[] NO_GENERATION_PEAKS = new CatalogGenerationPeak[0];

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
	 * Verifies that the given array of items is strictly ascending by the key the extractor yields. Used for the
	 * folder-related arrays, whose ordering key is a field rather than the element itself.
	 *
	 * @param type         a descriptive name of the collection being validated; used in the error message
	 * @param items        the array to be validated
	 * @param keyExtractor yields the ordering key of one element
	 * @throws GenericEvitaInternalError if the array is not strictly ascending
	 */
	private static <U> void assertSorted(
		@Nonnull String type,
		@Nonnull U[] items,
		@Nonnull Function<U, String> keyExtractor
	) {
		for (int i = 1; i < items.length; i++) {
			final String previousKey = keyExtractor.apply(items[i - 1]);
			final String currentKey = keyExtractor.apply(items[i]);
			Assert.isPremiseValid(
				previousKey.compareTo(currentKey) < 0,
				() -> type + " must be strictly ascending (no duplicates), but found: " + Arrays.toString(items)
			);
		}
	}

	/**
	 * Builds the folder bindings the legacy layout implied, in which a catalog's folder simply *was* its name.
	 *
	 * This is the faithful translation of a state that predates the name-to-folder map — not a fallback. It is
	 * applied wherever such a state enters the engine (the legacy convenience constructors below, and every
	 * backward-compatible serializer), so that a lookup is total from the first moment the state exists and
	 * nothing downstream ever has to guess what an absent binding meant.
	 *
	 * @param activeCatalogs   names registered as active
	 * @param inactiveCatalogs names registered as inactive
	 * @param missingCatalogs  names whose folder was absent when the state was last written
	 * @return one identity binding per distinct name, strictly ascending
	 */
	@Nonnull
	private static CatalogFolderBinding[] identityBindings(
		@Nonnull String[] activeCatalogs,
		@Nonnull String[] inactiveCatalogs,
		@Nonnull String[] missingCatalogs
	) {
		// the three buckets are disjoint in practice, but a set keeps the result well-formed regardless
		final TreeSet<String> distinctNames = new TreeSet<>();
		Collections.addAll(distinctNames, activeCatalogs);
		Collections.addAll(distinctNames, inactiveCatalogs);
		Collections.addAll(distinctNames, missingCatalogs);
		if (distinctNames.isEmpty()) {
			return NO_FOLDER_BINDINGS;
		}
		final CatalogFolderBinding[] bindings = new CatalogFolderBinding[distinctNames.size()];
		int index = 0;
		for (final String catalogName : distinctNames) {
			bindings[index++] = new CatalogFolderBinding(catalogName, new CatalogFolderId(catalogName));
		}
		return bindings;
	}

	/**
	 * Returns the binding array with `binding` installed for its catalog name, replacing any binding that name
	 * already had. Replacement — rather than the insert-if-absent the shared array helpers provide — is the whole
	 * point: rebinding a name to a different folder is exactly what a rename and a replace do.
	 *
	 * @param bindings current bindings, strictly ascending by catalog name
	 * @param binding  binding to install
	 * @return new array carrying the binding; the input is never modified
	 */
	@Nonnull
	public static CatalogFolderBinding[] withBinding(
		@Nonnull CatalogFolderBinding[] bindings,
		@Nonnull CatalogFolderBinding binding
	) {
		final int index = binarySearch(
			bindings, binding.catalogName(), (examined, key) -> examined.catalogName().compareTo(key)
		);
		if (index >= 0) {
			final CatalogFolderBinding[] result = Arrays.copyOf(bindings, bindings.length);
			result[index] = binding;
			return result;
		}
		return ArrayUtils.insertRecordIntoArrayOnIndex(binding, bindings, -1 * index - 1);
	}

	/**
	 * Returns the binding array without the entry for `catalogName`, or the input array when the name is unbound.
	 *
	 * @param bindings    current bindings, strictly ascending by catalog name
	 * @param catalogName name whose binding is to be dropped
	 * @return array without the name's binding; the input is never modified
	 */
	@Nonnull
	public static CatalogFolderBinding[] withoutBinding(
		@Nonnull CatalogFolderBinding[] bindings,
		@Nonnull String catalogName
	) {
		final int index = binarySearch(
			bindings, catalogName, (examined, key) -> examined.catalogName().compareTo(key)
		);
		return index >= 0 ? ArrayUtils.removeRecordFromArrayOnIndex(bindings, index) : bindings;
	}

	/**
	 * Returns the tombstone array with `retiredFolder` recorded, or the input array when that folder is already
	 * tombstoned.
	 *
	 * Re-retiring a folder is a no-op rather than an error: the array is a set of folders awaiting deletion, so a
	 * second entry for one folder would say nothing the first does not, and the array is asserted to hold no
	 * duplicates.
	 *
	 * @param retiredFolders current tombstones, strictly ascending by folder token
	 * @param retiredFolder  tombstone to record
	 * @return new array carrying the tombstone; the input is never modified
	 */
	@Nonnull
	public static RetiredFolder[] withRetiredFolder(
		@Nonnull RetiredFolder[] retiredFolders,
		@Nonnull RetiredFolder retiredFolder
	) {
		final int index = binarySearch(
			retiredFolders, retiredFolder.folderId().id(),
			(examined, key) -> examined.folderId().id().compareTo(key)
		);
		return index >= 0 ?
			retiredFolders :
			ArrayUtils.insertRecordIntoArrayOnIndex(retiredFolder, retiredFolders, -1 * index - 1);
	}

	/**
	 * Returns the tombstone array without the entries naming folders that are provably gone.
	 *
	 * This is how a tombstone is retired in turn: it exists solely to say "this folder still has to be deleted",
	 * so once the deletion is confirmed the entry is noise that would otherwise be carried in persisted state for
	 * the lifetime of the installation — the folder is gone, so boot classification never reports it again and
	 * nothing else would ever drop it.
	 *
	 * @param retiredFolders current tombstones, strictly ascending by folder token
	 * @param drainedFolders folders whose removal has been confirmed; may name folders that were never tombstoned
	 * @return array without the confirmed entries; the input is returned unchanged when nothing matches
	 */
	@Nonnull
	public static RetiredFolder[] withoutRetiredFolders(
		@Nonnull RetiredFolder[] retiredFolders,
		@Nonnull Set<CatalogFolderId> drainedFolders
	) {
		if (retiredFolders.length == 0 || drainedFolders.isEmpty()) {
			return retiredFolders;
		}
		RetiredFolder[] result = retiredFolders;
		// walked backwards so that a removal never shifts an index still to be examined
		for (int i = retiredFolders.length - 1; i >= 0; i--) {
			if (drainedFolders.contains(retiredFolders[i].folderId())) {
				result = ArrayUtils.removeRecordFromArrayOnIndex(result, i);
			}
		}
		return result;
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

	/**
	 * Convenience constructor preserving the legacy record shape, in which a catalog's storage folder was its
	 * name. Instances created through this constructor carry an identity binding per catalog and no retired
	 * folders or generation peaks — nothing has been allocated or retired under the old layout.
	 *
	 * This is the entry point every backward-compatible serializer uses, so the translation of "no map" into
	 * "folder == name" is written down exactly once.
	 *
	 * @param storageProtocolVersion storage protocol version the engine state adheres to
	 * @param version                current version of the engine state
	 * @param introducedAt           moment this engine state version was introduced
	 * @param walReference           reference to the engine Write-Ahead Log file, or `null` when there is none
	 * @param activeCatalogs         names of the active catalogs, alphabetically ordered
	 * @param inactiveCatalogs       names of the inactive catalogs, alphabetically ordered
	 * @param readOnlyCatalogs       names of the read-only catalogs, alphabetically ordered
	 * @param missingCatalogs        names of the catalogs whose folder is no longer present, alphabetically
	 *                               ordered
	 */
	public EngineState(
		int storageProtocolVersion,
		long version,
		@Nonnull OffsetDateTime introducedAt,
		@Nullable T walReference,
		@Nonnull String[] activeCatalogs,
		@Nonnull String[] inactiveCatalogs,
		@Nonnull String[] readOnlyCatalogs,
		@Nonnull String[] missingCatalogs
	) {
		this(
			storageProtocolVersion,
			version,
			introducedAt,
			walReference,
			activeCatalogs,
			inactiveCatalogs,
			readOnlyCatalogs,
			missingCatalogs,
			identityBindings(activeCatalogs, inactiveCatalogs, missingCatalogs),
			NO_RETIRED_FOLDERS,
			NO_GENERATION_PEAKS
		);
	}

	public EngineState {
		assertSorted("Active", activeCatalogs);
		assertSorted("Inactive", inactiveCatalogs);
		assertSorted("Read-only", readOnlyCatalogs);
		assertSorted("Missing", missingCatalogs);
		assertSorted("Catalog folder bindings", catalogFolders, CatalogFolderBinding::catalogName);
		assertSorted("Generation peaks", generationPeaks, CatalogGenerationPeak::catalogName);
		// tombstones are keyed by folder token, because one catalog may have several folders awaiting deletion
		assertSorted("Retired folders", retiredFolders, it -> it.folderId().id());
	}

	/**
	 * Returns the folder token holding the data of the passed catalog, or `null` when this state records no
	 * binding for that name.
	 *
	 * The absent case is reported rather than papered over. Answering an unbound name with the catalog's own
	 * name would send reads and writes to whatever directory happens to carry that name and report success —
	 * the failure mode this whole redesign exists to remove. Callers that know the catalog must already be
	 * registered therefore go through `CatalogFolderContext#folderIdFor`, which turns `null` into a loud error;
	 * callers registering a catalog the state does not know yet ask for a binding instead.
	 *
	 * @param catalogName name of the catalog to resolve
	 * @return token identifying the folder bound to the catalog, or `null` when the catalog is unbound
	 */
	@Nullable
	public CatalogFolderId boundFolderIdFor(@Nonnull String catalogName) {
		final int index = binarySearch(
			this.catalogFolders, catalogName, (examined, key) -> examined.catalogName().compareTo(key)
		);
		return index >= 0 ? this.catalogFolders[index].folderId() : null;
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (!(o instanceof final EngineState<?> that)) return false;

		return this.version == that.version && this.storageProtocolVersion == that.storageProtocolVersion && Arrays.equals(
			this.activeCatalogs, that.activeCatalogs) && Arrays.equals(
			this.inactiveCatalogs, that.inactiveCatalogs) && Arrays.equals(
			this.readOnlyCatalogs, that.readOnlyCatalogs) && Arrays.equals(
			this.missingCatalogs, that.missingCatalogs) && Arrays.equals(
			this.catalogFolders, that.catalogFolders) && Arrays.equals(
			this.retiredFolders, that.retiredFolders) && Arrays.equals(
			this.generationPeaks, that.generationPeaks) && this.introducedAt.equals(
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
		result = 31 * result + Arrays.hashCode(this.catalogFolders);
		result = 31 * result + Arrays.hashCode(this.retiredFolders);
		result = 31 * result + Arrays.hashCode(this.generationPeaks);
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
			", catalogFolders=" + Arrays.toString(this.catalogFolders) +
			", retiredFolders=" + Arrays.toString(this.retiredFolders) +
			", generationPeaks=" + Arrays.toString(this.generationPeaks) +
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

		/**
		 * Returns a defensive copy of the given array sorted by the given comparator, or `empty` when the source
		 * is `null`. The folder-related counterpart of {@link #sortedCopyOrEmpty(String[])}, whose elements order
		 * by a field rather than by themselves.
		 *
		 * @param src        source array; may be `null`
		 * @param empty      value to return for a `null` source
		 * @param comparator ordering to normalize the copy to
		 * @return sorted copy of `src`, or `empty` when `src` is `null`
		 */
		@Nonnull
		private static <U> U[] sortedCopyOrEmpty(
			@Nullable U[] src,
			@Nonnull U[] empty,
			@Nonnull Comparator<U> comparator
		) {
			if (src == null) {
				return empty;
			}
			final U[] sortedCopy = Arrays.copyOf(src, src.length);
			Arrays.sort(sortedCopy, comparator);
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
		@Nonnull
		private CatalogFolderBinding[] catalogFolders = NO_FOLDER_BINDINGS;
		@Nonnull
		private RetiredFolder[] retiredFolders = NO_RETIRED_FOLDERS;
		@Nonnull
		private CatalogGenerationPeak[] generationPeaks = NO_GENERATION_PEAKS;

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
			this.catalogFolders = Arrays.copyOf(engineState.catalogFolders, engineState.catalogFolders.length);
			this.retiredFolders = Arrays.copyOf(engineState.retiredFolders, engineState.retiredFolders.length);
			this.generationPeaks = Arrays.copyOf(engineState.generationPeaks, engineState.generationPeaks.length);
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
		 * Sets the catalog-to-folder bindings. The array is defensively copied and sorted by catalog name;
		 * `null` is treated as empty.
		 *
		 * @param catalogFolders bindings to record; may be `null`
		 * @return this builder instance
		 */
		@Nonnull
		public Builder<T> catalogFolders(@Nullable CatalogFolderBinding[] catalogFolders) {
			this.catalogFolders = sortedCopyOrEmpty(
				catalogFolders, NO_FOLDER_BINDINGS, Comparator.comparing(CatalogFolderBinding::catalogName)
			);
			return this;
		}

		/**
		 * Sets the folders awaiting deletion. The array is defensively copied and sorted by folder token;
		 * `null` is treated as empty.
		 *
		 * @param retiredFolders tombstones to record; may be `null`
		 * @return this builder instance
		 */
		@Nonnull
		public Builder<T> retiredFolders(@Nullable RetiredFolder[] retiredFolders) {
			this.retiredFolders = sortedCopyOrEmpty(
				retiredFolders, NO_RETIRED_FOLDERS, Comparator.comparing(it -> it.folderId().id())
			);
			return this;
		}

		/**
		 * Sets the per-catalog generation peaks. The array is defensively copied and sorted by catalog name;
		 * `null` is treated as empty.
		 *
		 * @param generationPeaks peaks to record; may be `null`
		 * @return this builder instance
		 */
		@Nonnull
		public Builder<T> generationPeaks(@Nullable CatalogGenerationPeak[] generationPeaks) {
			this.generationPeaks = sortedCopyOrEmpty(
				generationPeaks, NO_GENERATION_PEAKS, Comparator.comparing(CatalogGenerationPeak::catalogName)
			);
			return this;
		}

		/**
		 * Builds a new EngineState instance with the current builder values.
		 *
		 * If no `introducedAt` value was carried over from a copy-constructor or set explicitly, the
		 * current time is used — that's the right default only for genuinely new engine states.
		 * Any rewrite of an existing state must go through `builder(EngineState)` so the original timestamp is
		 * preserved — the boot-time reconciliation in `DefaultEnginePersistenceService` most of all.
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
				this.missingCatalogs,
				this.catalogFolders,
				this.retiredFolders,
				this.generationPeaks
			);
		}
	}

}
