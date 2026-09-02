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

package io.evitadb.api.statistics;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * The {@link CatalogStatisticsComponent#COLLECTIONS} component of one entity collection - the counters carried by its
 * storage header. The catalog-level counterpart {@link CollectionsInfo} lists only *which* collections exist; these
 * are the numbers behind one of them.
 *
 * **On `maxRecordSizeBytes`** - this is a high-water mark, not a current maximum. It is seeded from its previous value
 * and only ever widened on flush; removing the biggest record never lowers it. It therefore means *largest record ever
 * seen in this collection*, and must be labelled that way wherever it is displayed.
 *
 * **On `lastPrimaryKey`** - this is the high-water mark of the collection's *auto-generated* key sequence, not the
 * largest primary key in use. A client that supplies its own primary keys never advances the sequence, so a
 * collection holding fifty explicitly-keyed entities reports `0` here. Where keys *are* generated, the gap between it
 * and the collection's record count is the number of entities deleted over the collection's lifetime; where they are
 * not, the number carries no such reading and must not be presented as one.
 *
 * @param entityTypePrimaryKey      internal primary key assigned to the entity type itself
 * @param version                   version of the collection header, incremented on every flush
 * @param lastPrimaryKey            highest entity primary key the collection has generated so far; `0` when every
 *                                  key was supplied by the client
 * @param lastEntityIndexPrimaryKey highest entity index primary key assigned so far
 * @param lastInternalPriceId       highest internal price id assigned so far
 * @param lastKeyId                 highest storage key id assigned so far
 * @param maxRecordSizeBytes        largest single stored record ever observed in this collection, in bytes
 * @param lastModified              wall-clock time this collection's storage header was last written, or `null` when
 *                                  the collection has not been written since the catalog was upgraded to 2026.3 - see
 *                                  the note below
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CollectionHeaderInfo(
	int entityTypePrimaryKey,
	long version,
	int lastPrimaryKey,
	int lastEntityIndexPrimaryKey,
	int lastInternalPriceId,
	long lastKeyId,
	long maxRecordSizeBytes,
	@Nullable OffsetDateTime lastModified
) {

	/**
	 * Wall-clock time of the last write to this collection, when it is known.
	 *
	 * **`null` is a real and expected answer, not an error.** The timestamp is persisted in the collection's storage
	 * header, and headers written before 2026.3 do not carry one; a catalog upgraded from an earlier release therefore
	 * reports `null` for every collection until each is next flushed. A client must render that as *unknown* rather
	 * than as a date - which is precisely why this is `null` and not an epoch-zero instant.
	 *
	 * The header is rewritten by every flush that changed the collection and by every compaction of it, so a
	 * compaction moves this forward without the data having changed. It answers *when was this collection's storage
	 * last written*, which is the question a management screen asks, but it is not a data-modification audit trail.
	 *
	 * @return the last write, or empty when the header predates the timestamp
	 */
	@Nonnull
	public Optional<OffsetDateTime> lastModifiedIfKnown() {
		return Optional.ofNullable(this.lastModified);
	}

}
