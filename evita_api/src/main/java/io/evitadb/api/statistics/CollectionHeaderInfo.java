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

/**
 * The {@link CatalogStatisticsComponent#COLLECTIONS} component of one entity collection - the counters carried by its
 * storage header. The catalog-level counterpart {@link CollectionsInfo} lists only *which* collections exist; these
 * are the numbers behind one of them.
 *
 * **On `maxRecordSizeBytes`** - this is a high-water mark, not a current maximum. It is seeded from its previous value
 * and only ever widened on flush; removing the biggest record never lowers it. It therefore means *largest record ever
 * seen in this collection*, and must be labelled that way wherever it is displayed.
 *
 * **On `lastPrimaryKey`** - the gap between it and the collection's record count reveals how many entities have been
 * deleted over the collection's lifetime.
 *
 * @param entityTypePrimaryKey      internal primary key assigned to the entity type itself
 * @param version                   version of the collection header, incremented on every flush
 * @param lastPrimaryKey            highest entity primary key assigned so far
 * @param lastEntityIndexPrimaryKey highest entity index primary key assigned so far
 * @param lastInternalPriceId       highest internal price id assigned so far
 * @param lastKeyId                 highest storage key id assigned so far
 * @param maxRecordSizeBytes        largest single stored record ever observed in this collection, in bytes
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record CollectionHeaderInfo(
	int entityTypePrimaryKey,
	long version,
	int lastPrimaryKey,
	int lastEntityIndexPrimaryKey,
	int lastInternalPriceId,
	long lastKeyId,
	long maxRecordSizeBytes
) {
}
