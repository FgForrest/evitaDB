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

package io.evitadb.index;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.core.buffer.DataStoreChanges.RemovedStoragePart;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.dataType.Scope;
import io.evitadb.index.attribute.EntityAttributeIndex;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.facet.FacetIndex;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.price.PriceSuperIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIdsStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the entity-id bitmap eviction behaviour of `EntityIndex.getModifiedStorageParts`: the hot
 * membership bitmaps are emitted as a sibling {@link EntityIdsStoragePart}, decoupled from the bulky
 * {@link EntityIndexStoragePart} manifest. A membership change on an already-persisted index re-emits
 * only the bitmaps part (not the manifest); the first write of a never-persisted index co-emits the
 * manifest so it stays reloadable; an emptied index removes the bitmaps part.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("EntityIndex entity-id bitmap eviction")
@Tag(INDEXING)
@Tag(SERIALIZATION)
class EntityIndexBitmapEvictionTest {

	private static final String ENTITY_TYPE = "Product";
	private static final int INDEX_PK = 1;

	/**
	 * Builds a fresh (never-persisted) global index — `previouslyPersisted` is false.
	 *
	 * @return a fresh empty global entity index
	 */
	@Nonnull
	private static GlobalEntityIndex freshIndex() {
		return new GlobalEntityIndex(
			INDEX_PK, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
		);
	}

	/**
	 * Builds a global index through the load constructor with the given non-empty entity ids, so it
	 * reports `previouslyPersisted == true` — mirroring an index reloaded from / committed to disk.
	 *
	 * @param entityIds the persisted entity-id superset
	 * @return a "loaded" global entity index carrying the supplied bitmap
	 */
	@Nonnull
	private static GlobalEntityIndex loadedIndex(@Nonnull int... entityIds) {
		return new GlobalEntityIndex(
			INDEX_PK,
			new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE),
			5,
			new TransactionalBitmap(entityIds),
			Map.of(),
			new EntityAttributeIndex(ENTITY_TYPE),
			new PriceSuperIndex(),
			new HierarchyIndex(),
			new FacetIndex()
		);
	}

	/**
	 * Returns the single storage part of the given type emitted by `getModifiedStorageParts`, or null
	 * when none was emitted; fails when more than one is present.
	 *
	 * @param index the index to flush
	 * @param type  the storage-part type to look for
	 * @param <T>   the storage-part type
	 * @return the single matching part, or null
	 */
	@Nullable
	private static <T extends StoragePart> T flushAndFind(
		@Nonnull EntityIndex index, @Nonnull Class<T> type
	) {
		final TrappedChanges trapped = new TrappedChanges();
		index.getModifiedStorageParts(trapped);
		final Iterator<StoragePart> it = trapped.getTrappedChangesIterator();
		T found = null;
		while (it.hasNext()) {
			final StoragePart part = it.next();
			if (type.isInstance(part)) {
				assertNull(found, "More than one " + type.getSimpleName() + " emitted");
				found = type.cast(part);
			}
		}
		return found;
	}

	@Nonnull
	private static AttributeSchemaContract filterableStringSchema(@Nonnull String name) {
		return AttributeSchema._internalBuild(
			name, null,
			new Scope[]{Scope.LIVE}, Scope.NO_SCOPE,
			false, false, false,
			String.class, null,
			ConflictResolutionOverride.INHERITED
		);
	}

	@Test
	@DisplayName("membership change on a persisted index emits only the bitmaps part")
	void shouldEmitOnlyBitmapsPartOnPersistedMembershipChange() {
		final GlobalEntityIndex index = loadedIndex(1, 2, 3);
		index.insertPrimaryKeyIfMissing(4);

		final TrappedChanges trapped = new TrappedChanges();
		index.getModifiedStorageParts(trapped);

		EntityIdsStoragePart bitmaps = null;
		EntityIndexStoragePart manifest = null;
		final Iterator<StoragePart> it = trapped.getTrappedChangesIterator();
		while (it.hasNext()) {
			final StoragePart part = it.next();
			if (part instanceof EntityIdsStoragePart b) {
				bitmaps = b;
			} else if (part instanceof EntityIndexStoragePart m) {
				manifest = m;
			}
		}

		assertNull(manifest, "Manifest must NOT be re-emitted on a pure membership change");
		assertNotNull(bitmaps, "Bitmaps part must be emitted on a membership change");
		assertArrayEquals(new int[]{1, 2, 3, 4}, bitmaps.getEntityIds().getArray());
		assertEquals(INDEX_PK, bitmaps.getPrimaryKey());
	}

	@Test
	@DisplayName("first write of a fresh index co-emits the manifest and the bitmaps part")
	void shouldEmitManifestAndBitmapsOnFirstWrite() {
		final GlobalEntityIndex index = freshIndex();
		index.insertPrimaryKeyIfMissing(1);

		assertNotNull(
			flushAndFind(index, EntityIndexStoragePart.class),
			"A never-persisted index must emit its manifest on first write so it stays reloadable"
		);
		final EntityIdsStoragePart bitmaps = flushAndFind(index, EntityIdsStoragePart.class);
		assertNotNull(bitmaps, "First write must also emit the bitmaps part");
		assertArrayEquals(new int[]{1}, bitmaps.getEntityIds().getArray());
	}

	@Test
	@DisplayName("emptying a persisted index removes the bitmaps part and writes no manifest")
	void shouldRemoveBitmapsPartWhenPersistedIndexEmptied() {
		final GlobalEntityIndex index = loadedIndex(7);
		index.removePrimaryKey(7);

		assertNull(
			flushAndFind(index, EntityIndexStoragePart.class),
			"Emptying must not rewrite the manifest"
		);
		assertNull(
			flushAndFind(index, EntityIdsStoragePart.class),
			"An emptied index must not write an (empty) bitmaps part"
		);
		final RemovedStoragePart removal = flushAndFind(index, RemovedStoragePart.class);
		assertNotNull(removal, "An emptied persisted index must remove its bitmaps part");
		assertSame(EntityIdsStoragePart.class, removal.containerType());
		assertEquals(INDEX_PK, removal.getStoragePartPK());
	}

	@Test
	@DisplayName("a fresh index emptied before its first flush still removes the (never-written) bitmaps part")
	void shouldRemoveBitmapsPartWhenFreshIndexEmptiedBeforeFirstFlush() {
		final GlobalEntityIndex index = freshIndex();
		index.insertPrimaryKeyIfMissing(1);
		index.removePrimaryKey(1);

		// the removal targets a sibling that was never persisted — a harmless no-op store-side, so the
		// emission is unconditional (the `previouslyPersisted` gate was dropped in the eager-migration
		// simplification)
		final RemovedStoragePart removal = flushAndFind(index, RemovedStoragePart.class);
		assertNotNull(removal, "An emptied index must emit the sibling removal even when never persisted");
		assertSame(EntityIdsStoragePart.class, removal.containerType());
		assertNull(
			flushAndFind(index, EntityIdsStoragePart.class),
			"An emptied index must not write an (empty) bitmaps part"
		);
		// a never-persisted index must still co-emit its manifest so it stays reloadable
		assertNotNull(
			flushAndFind(index, EntityIndexStoragePart.class),
			"A never-persisted index must emit its manifest on first write"
		);
	}

	@Test
	@DisplayName("a clean persisted index emits nothing")
	void shouldEmitNothingForCleanPersistedIndex() {
		final GlobalEntityIndex index = loadedIndex(1, 2, 3);

		assertNull(flushAndFind(index, EntityIndexStoragePart.class));
		assertNull(flushAndFind(index, EntityIdsStoragePart.class));
		assertNull(flushAndFind(index, RemovedStoragePart.class));
	}

	@Test
	@DisplayName("a sub-index appearing on a persisted index emits the manifest but no bitmaps part")
	void shouldEmitManifestWhenSubIndexAppears() {
		final GlobalEntityIndex index = loadedIndex(1);
		// adding a filter attribute introduces a sub-index — a structural manifest change — without
		// touching the membership bitmaps
		index.insertFilterAttribute(
			null, filterableStringSchema("code"), Collections.emptySet(), null, "VAL", 1, false
		);

		assertNotNull(
			flushAndFind(index, EntityIndexStoragePart.class),
			"A new sub-index is a structural change and must rewrite the manifest"
		);
		assertNull(
			flushAndFind(index, EntityIdsStoragePart.class),
			"No membership change occurred — the bitmaps part must not be re-emitted"
		);
	}
}
