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

package io.evitadb.core.transaction.conflict;

import io.evitadb.api.requestResponse.mutation.conflict.EntityConflictKey;
import io.evitadb.core.buffer.RingBuffer.OutsideScopeException;
import io.evitadb.core.transaction.conflict.ConflictRingBuffer.CatalogVersionIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests pinning the catalog-version boundary contract of {@link ConflictRingBuffer}.
 *
 * `ConflictRingBuffer` specialises the generic {@link io.evitadb.core.buffer.RingBuffer RingBuffer}
 * for {@link VersionedConflictKey} elements keyed by a {@link CatalogVersionIndex}. Its distinguishing
 * behaviour is how a bare catalog version is translated into the underlying `(version, index)` boundary:
 * every conversion — `forEachSince`, `clearAllUntil`, `clearAllAfter` — anchors the version at index `0`,
 * which makes the scan/clear boundary **inclusive** of the version itself. This is deliberately different
 * from the change-data-capture sibling, whose watermark conversion is exclusive; these tests guard that
 * inclusive `(version, 0)` semantics against regression.
 *
 * The tests build a real buffer over real {@link VersionedConflictKey} instances wrapping real
 * {@link EntityConflictKey} keys — no mocks — and collect scan results through a list-appending consumer.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Conflict ring buffer catalog-version boundary contract")
@Tag(ENGINE)
@Tag(TRANSACTION)
class ConflictRingBufferTest {
	private static final String CATALOG_NAME = "conflictCatalog";
	private static final String ENTITY_TYPE = "Product";
	/**
	 * Catalog version of the oldest key the buffer starts covering; also the lowest watermark the tests
	 * may scan from without falling outside the buffer scope.
	 */
	private static final long START_VERSION = 10L;
	/**
	 * A visibility ceiling high enough that none of the offered keys are withheld by the effective-end
	 * watermark in the scan / clear tests (visibility is exercised separately).
	 */
	private static final long UNBOUNDED_LAST_VERSION = 1_000L;
	/**
	 * A ring buffer capacity that comfortably holds every key offered by the scan / clear tests, so no
	 * eviction perturbs the effective-start boundary in those scenarios.
	 */
	private static final int SPACIOUS_BUFFER_SIZE = 16;

	@Nested
	@DisplayName("Scanning since a watermark version")
	class ScanningSince {

		@Test
		@DisplayName("includes the watermark version itself and every later key in order")
		void shouldReturnKeysAtAndAfterWatermarkVersion() throws OutsideScopeException {
			// given a buffer holding one key per version 10, 11 and 12
			final ConflictRingBuffer buffer = newSpaciousBuffer();
			final VersionedConflictKey key10 = key(10L, 0, 100);
			final VersionedConflictKey key11 = key(11L, 0, 101);
			final VersionedConflictKey key12 = key(12L, 0, 102);
			buffer.offer(key10);
			buffer.offer(key11);
			buffer.offer(key12);

			// when scanning since version 11
			final List<VersionedConflictKey> scanned = collectSince(buffer, 11L);

			// then the watermark version 11 is inclusive and version 10 is excluded, keeping order
			assertEquals(List.of(key11, key12), scanned);
		}
	}

	@Nested
	@DisplayName("Clearing keys relative to a version")
	class Clearing {

		@Test
		@DisplayName("clearAllAfter removes the version and every later key while keeping earlier ones")
		void shouldRemoveKeysAtAndAfterVersionOnClearAllAfter() throws OutsideScopeException {
			// given version 10, two keys under version 11, and version 12
			final ConflictRingBuffer buffer = newSpaciousBuffer();
			final VersionedConflictKey key10 = key(10L, 0, 100);
			buffer.offer(key10);
			buffer.offer(key(11L, 0, 110));
			buffer.offer(key(11L, 1, 111));
			buffer.offer(key(12L, 0, 120));

			// when clearing everything at or after version 11
			buffer.clearAllAfter(11L);

			// then both index-0 and index-1 keys of version 11 and the version-12 key are gone,
			// while the version-10 key survives (inclusive at-or-after boundary, not exclusive +1)
			assertEquals(List.of(key10), collectSince(buffer, 10L));
		}

		@Test
		@DisplayName("clearAllAfter with a boundary in a gap between versions removes only the tail")
		void shouldRemoveOnlyEntriesAboveGapBoundaryOnClearAllAfter() throws OutsideScopeException {
			// given versions 10, 11 and 13 retained with version 12 absent — exactly the state left when a
			// rejected/rolled-back transaction's reserved version 12 falls between two committed versions
			// (identifyConflicts registers a reserved version's keys only after the conflict scan succeeds)
			final ConflictRingBuffer buffer = newSpaciousBuffer();
			final VersionedConflictKey key10 = key(10L, 0, 100);
			final VersionedConflictKey key11 = key(11L, 0, 110);
			buffer.offer(key10);
			buffer.offer(key11);
			buffer.offer(key(13L, 0, 130));

			// when clearing everything at or after the absent boundary version 12
			buffer.clearAllAfter(12L);

			// then only version 13 is removed — the boundary resolves to its insertion point rather than a
			// binary-search miss, so versions 10 and 11 below it must survive intact (the old code treated
			// the miss as a raw negative index and wiped the entire retained buffer)
			assertEquals(List.of(key10, key11), collectSince(buffer, 10L));
		}

		@Test
		@DisplayName("clearAllUntil releases keys strictly below the version and advances the start boundary")
		void shouldReleaseKeysBelowVersionOnClearAllUntil() throws OutsideScopeException {
			// given one key per version 10, 11 and 12
			final ConflictRingBuffer buffer = newSpaciousBuffer();
			buffer.offer(key(10L, 0, 100));
			final VersionedConflictKey key11 = key(11L, 0, 101);
			final VersionedConflictKey key12 = key(12L, 0, 102);
			buffer.offer(key11);
			buffer.offer(key12);

			// when clearing everything up to (but excluding) version 11
			buffer.clearAllUntil(11L);

			// then the effective start advances to version 11 (version 10 released) ...
			assertEquals(new CatalogVersionIndex(11L, 0), buffer.getEffectiveStart());
			// ... and versions 11 and 12 remain scannable
			assertEquals(List.of(key11, key12), collectSince(buffer, 11L));
		}
	}

	@Nested
	@DisplayName("Watermark falling outside the retained scope")
	class OutsideScope {

		@Test
		@DisplayName("throws OutsideScopeException carrying the retained oldest version after eviction")
		void shouldThrowOutsideScopeWhenWatermarkPredatesEvictedVersions() {
			// given a small buffer overflowed by versions 10 through 15, evicting the oldest
			final ConflictRingBuffer buffer = new ConflictRingBuffer(
				CATALOG_NAME, START_VERSION, UNBOUNDED_LAST_VERSION, 3
			);
			for (long version = 10L; version <= 15L; version++) {
				buffer.offer(key(version, 0, (int) (200 + version)));
			}

			// when scanning from a version that has already been evicted
			final OutsideScopeException exception = assertThrows(
				OutsideScopeException.class,
				() -> buffer.forEachSince(10L, ignored -> { })
			);

			// then the exception exposes the retained oldest version (13) as the effective start
			final CatalogVersionIndex effectiveStart = exception.getEffectiveStart();
			assertEquals(new CatalogVersionIndex(13L, 0), effectiveStart);
		}
	}

	@Nested
	@DisplayName("Visibility governed by the effective-last version")
	class Visibility {

		@Test
		@DisplayName("withholds keys above the effective-last version until it is advanced")
		void shouldAdvanceVisibilityWithEffectiveLastCatalogVersion() throws OutsideScopeException {
			// given a buffer whose visibility ceiling is version 11, holding versions 10 through 13
			final ConflictRingBuffer buffer = new ConflictRingBuffer(
				CATALOG_NAME, START_VERSION, 11L, 4
			);
			final VersionedConflictKey key10 = key(10L, 0, 100);
			final VersionedConflictKey key11 = key(11L, 0, 101);
			final VersionedConflictKey key12 = key(12L, 0, 102);
			final VersionedConflictKey key13 = key(13L, 0, 103);
			buffer.offer(key10);
			buffer.offer(key11);
			buffer.offer(key12);
			buffer.offer(key13);

			// when scanning while the ceiling still sits at version 11
			// then only keys up to and including version 11 are visible
			assertEquals(List.of(key10, key11), collectSince(buffer, 10L));

			// when the effective-last version advances to 13
			buffer.setEffectiveLastCatalogVersion(13L);

			// then the previously withheld versions 12 and 13 become visible
			assertEquals(List.of(key10, key11, key12, key13), collectSince(buffer, 10L));
		}
	}

	/**
	 * Builds a ring buffer large enough that none of the scan / clear scenarios trigger eviction.
	 *
	 * @return a fresh empty conflict ring buffer
	 */
	@Nonnull
	private static ConflictRingBuffer newSpaciousBuffer() {
		return new ConflictRingBuffer(
			CATALOG_NAME, START_VERSION, UNBOUNDED_LAST_VERSION, SPACIOUS_BUFFER_SIZE
		);
	}

	/**
	 * Creates a versioned conflict key targeting a single entity.
	 *
	 * @param version the commit catalog version the key is registered under
	 * @param index the zero-based ordinal within the transaction's conflict key set
	 * @param primaryKey the primary key of the affected entity
	 * @return a versioned conflict key wrapping an {@link EntityConflictKey}
	 */
	@Nonnull
	private static VersionedConflictKey key(long version, int index, int primaryKey) {
		return new VersionedConflictKey(version, index, new EntityConflictKey(ENTITY_TYPE, primaryKey));
	}

	/**
	 * Scans the buffer from the given catalog version and collects the visited keys in encounter order.
	 *
	 * @param buffer the buffer to scan
	 * @param catalogVersion the inclusive watermark version to scan since
	 * @return the visited keys in the order the buffer reported them
	 * @throws OutsideScopeException if the watermark predates the buffer's retained scope
	 */
	@Nonnull
	private static List<VersionedConflictKey> collectSince(
		@Nonnull ConflictRingBuffer buffer,
		long catalogVersion
	) throws OutsideScopeException {
		final List<VersionedConflictKey> collected = new ArrayList<>();
		buffer.forEachSince(catalogVersion, collected::add);
		return collected;
	}
}
