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

package io.evitadb.spi.store.catalog.persistence.storageParts.index;

import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import lombok.Getter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Locale;
import java.util.Map;

/**
 * This container carries the "hot" entity-id bitmaps of a single {@link io.evitadb.index.EntityIndex}
 * — the {@link #entityIds} superset and the per-locale {@link #entityIdsByLanguage} partitions — that
 * used to be persisted inline inside {@link EntityIndexStoragePart}.
 *
 * They are evicted into this sibling record so that the bulky, rarely-changing manifest (the sub-index
 * reference sets in {@link EntityIndexStoragePart}) is no longer rewritten on every entity insert /
 * delete: a pure entity-membership change now re-emits only this small part, and a schema / sub-index
 * change re-emits only the manifest. The two parts share the owning index primary key (one bitmaps
 * record per index, 1:1 with the manifest) but live in distinct container keyspaces, so their primary
 * keys never collide.
 *
 * The reload path resolves the effective bitmaps from this part when present, falling back to the
 * legacy inline carrier on {@link EntityIndexStoragePart} for catalogs written before the eviction.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ToString(of = "primaryKey")
public class EntityIdsStoragePart implements StoragePart {
	@Serial private static final long serialVersionUID = 6112285351874560299L;

	/**
	 * Unique id that identifies the owning {@link io.evitadb.index.EntityIndex}. Matches the
	 * {@link EntityIndexStoragePart#getPrimaryKey()} of the sibling manifest.
	 */
	@Getter private final int primaryKey;
	/**
	 * Version of the owning entity index at the time these bitmaps were persisted. Because the bitmaps
	 * part is re-emitted on every membership change while the manifest is not, this version may run
	 * ahead of the sibling manifest's version; the loader reconciles the two by taking the maximum.
	 */
	@Getter private final int version;
	/**
	 * Superset bitmap of every entity primary key known to the owning index; equals the union of all
	 * per-locale partitions held in {@link #entityIdsByLanguage}.
	 */
	@Getter private final Bitmap entityIds;
	/**
	 * Per-locale partitions of the entity-id membership: maps each supported {@link Locale} to the
	 * bitmap of entity primary keys present in that locale.
	 */
	@Getter private final Map<Locale, TransactionalBitmap> entityIdsByLanguage;

	public EntityIdsStoragePart(
		int primaryKey,
		int version,
		@Nonnull Bitmap entityIds,
		@Nonnull Map<Locale, TransactionalBitmap> entityIdsByLanguage
	) {
		this.primaryKey = primaryKey;
		this.version = version;
		this.entityIds = entityIds;
		this.entityIdsByLanguage = entityIdsByLanguage;
	}

	@Nullable
	@Override
	public Long getStoragePartPK() {
		return (long) this.primaryKey;
	}

	@Override
	public long computeUniquePartIdAndSet(@Nonnull KeyCompressor keyCompressor) {
		return this.primaryKey;
	}

}
