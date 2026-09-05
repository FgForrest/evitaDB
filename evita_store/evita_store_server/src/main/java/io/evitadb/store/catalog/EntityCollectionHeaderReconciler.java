/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.store.catalog;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.utils.Assert;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Decides which of the two recorded answers to "where does this entity collection live" a reader must believe.
 *
 * Which file a collection lives in is recorded twice, and both copies are written from the same object in the same
 * round, under the same lock, published by the same bootstrap record: once as a {@link CollectionFileReference} inside
 * the catalog header, and once as {@link EntityCollectionFileHeader#entityTypeFileIndex()} on the collection header
 * itself. Only the first is written unconditionally, so only the first cannot lag behind a compaction - which is why
 * it takes precedence here.
 *
 * The second copy is deliberately kept rather than removed: it is the only route to a collection's data that does not
 * pass through the catalog header, which is what post-mortem analysis of a catalog with an unreadable header depends
 * on.
 *
 * Everything that opens a collection from a stored header goes through here - the catalog load path, both storage
 * protocol migrations that reconstruct collections, and the historical branch of the backup task. A path that resolves
 * a data file from a stored header WITHOUT passing it through this class reintroduces the defect for that path alone,
 * which is exactly how the migrations were found to have escaped the first version of this fix.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
public class EntityCollectionHeaderReconciler {

	/**
	 * Returns the collection header addressing the data file the published catalog header names.
	 *
	 * Three outcomes, because only two of them are understood:
	 *
	 * - **the two agree** (or the catalog header does not know this collection at all, or recorded no location to
	 *   compare): the stored header is returned untouched, which is the ordinary case and logs nothing;
	 * - **the indexes differ and nothing contradicts the location**: the file index is taken from the catalog header
	 *   and everything else is left alone. This is the one shape a historical defect could produce - a flush deduped
	 *   the collection header write by comparing the location alone, so a compaction that changed the file index
	 *   while leaving the header record's location unmoved updated one copy and not the other. Its location is
	 *   necessarily still correct, because the write was skipped precisely ON THE GROUNDS that it had not moved;
	 * - **anything else**: refused, naming both pairs. A header assembled from two disagreeing sources would be a
	 *   guess, and no path is known that produces such a pair.
	 *
	 * The repair is in memory only. The next flush that changes the collection moves its header record and rewrites
	 * the collection header with the right index through the ordinary write path, which is what clears the warning.
	 *
	 * **What this cannot repair.** The historical defect skipped the write of the WHOLE record, so in principle every
	 * mutable field of the stored header is as old as its file index - record count, the primary-key, index-key and
	 * internal-price-id high-water marks among them. Only the index is corrected, because the catalog header records
	 * only the index and the location: there is no second copy of the rest to reconcile against, and a value invented
	 * here would be a worse answer than the stored one. In practice the skip fires only on a compacting flush whose
	 * rewritten file placed the offset index at a byte-identical position AND length, which pins the live part count
	 * and the live byte total - so a divergence in the counters requires records to have been substituted for others
	 * of exactly the same size. The warning says the index was repaired; it does not claim the rest was verified.
	 *
	 * @param catalogName         name of the catalog, for the message
	 * @param publishedReference  what the published catalog header says, or {@code null} if it does not know the
	 *                            collection
	 * @param storedHeader        collection header as read from the catalog's offset index
	 * @return the stored header, or a copy of it naming the file the catalog header addresses
	 */
	@Nonnull
	public static EntityCollectionFileHeader reconcile(
		@Nonnull String catalogName,
		@Nullable CollectionFileReference publishedReference,
		@Nonnull EntityCollectionFileHeader storedHeader
	) {
		if (publishedReference == null) {
			return storedHeader;
		}
		final boolean sameIndex = publishedReference.fileIndex() == storedHeader.entityTypeFileIndex();
		final FileLocation publishedLocation = publishedReference.fileLocation();
		// a reference the catalog header never recorded a location for carries no evidence either way, so it cannot
		// contradict the stored one. `CollectionFileReference#fileLocation` is nullable and the type is routinely
		// built without one (`incrementAndGet`, the collection-replacement path) - those instances stay in memory
		// today, but treating an absent location as a DISAGREEMENT would make the refusal below depend on a field
		// nobody promised to write
		final boolean sameLocation = publishedLocation == null ||
			Objects.equals(publishedLocation, storedHeader.fileLocation());
		if (sameIndex && sameLocation) {
			return storedHeader;
		}
		Assert.isPremiseValid(
			sameLocation,
			() -> new GenericEvitaInternalError(
				"Catalog `" + catalogName + "` addresses entity collection `" + storedHeader.entityType() +
					"` as file index " + publishedReference.fileIndex() + " at " + publishedReference.fileLocation() +
					" in its catalog header, but as file index " + storedHeader.entityTypeFileIndex() + " at " +
					storedHeader.fileLocation() + " in the collection header!"
			)
		);
		log.warn(
			"Entity collection `{}` of catalog `{}` is addressed as file index {} by the catalog header and as {} by" +
				" its own header, which a flush predating the fix for this left behind. Resolving from the catalog" +
				" header, which is the copy written unconditionally; the collection's records are reachable and" +
				" unaffected, and the next flush that changes this collection rewrites its header and clears this.",
			storedHeader.entityType(), catalogName,
			publishedReference.fileIndex(), storedHeader.entityTypeFileIndex()
		);
		return storedHeader.withEntityTypeFileIndex(publishedReference.fileIndex());
	}

	private EntityCollectionHeaderReconciler() {
		throw new UnsupportedOperationException("This class cannot be instantiated!");
	}

}
