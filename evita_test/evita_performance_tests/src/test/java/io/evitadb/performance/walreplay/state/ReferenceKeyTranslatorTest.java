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

package io.evitadb.performance.walreplay.state;

import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityRemoveMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceGroupMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.SetReferenceGroupMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.evitadb.test.TestTags.TEST_HARNESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Guards the two rules that let a source WAL replay over a rebuilt catalog: a reference present at T0 takes the id
 * it was paired with, and a reference created after T0 shifts by the difference of the two per-entity counters.
 * Everything else - temporary ids, reflected references, entities the map does not name - must pass through
 * untouched, because rewriting it would break transactions that replay fine as recorded.
 *
 * The fixture map describes one product whose source counter stood at 30 and the rebuild's at 50 at T0, with its
 * `relatedProducts: 7/12` reference rebuilt as `7/3`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference key translator rewrites WAL reference ids for a rebuilt catalog")
@Tag(TEST_HARNESS)
class ReferenceKeyTranslatorTest {

	private static final String PRODUCT = "Product";
	private static final String RELATED = "relatedProducts";
	private static final String VARIANTS = "variants";
	private static final int PRODUCT_PK = 100;

	private ReferenceKeyTranslator translator;

	/**
	 * Writes the map file and loads the translator under test.
	 */
	@BeforeEach
	void setUp(@TempDir Path tempDir) throws IOException {
		this.translator = ReferenceKeyTranslator.load(
			writeMap(
				tempDir,
				"X\tProduct\tvariants",
				"E\tProduct\t100\t30\t50",
				"R\tProduct\t100\trelatedProducts\t7\t12\t3"
			)
		);
	}

	@Test
	@DisplayName("should count the entities and reference pairs of the map")
	void shouldCountEntitiesAndPairsOfTheMap() {
		assertEquals(1, this.translator.getEntityCount());
		assertEquals(1, this.translator.getReferencePairCount());
	}

	@Test
	@DisplayName("should reject a map row it does not know")
	void shouldThrowExceptionWhenMapHasUnknownRow(@TempDir Path tempDir) throws IOException {
		final Path mapFile = writeMap(tempDir, "Q\tProduct\t1");
		assertThrows(GenericEvitaInternalError.class, () -> ReferenceKeyTranslator.load(mapFile));
	}

	@Nested
	@DisplayName("Rewriting")
	class Rewriting {

		@Test
		@DisplayName("should take the paired id of a reference present at T0")
		void shouldRewritePairedId() {
			final ReferenceMutation<?> rewritten = translateSingle(new RemoveReferenceMutation(key(RELATED, 7, 12)));

			assertInstanceOf(RemoveReferenceMutation.class, rewritten);
			assertEquals(key(RELATED, 7, 3), rewritten.getReferenceKey());
			assertEquals(1, ReferenceKeyTranslatorTest.this.translator.getTranslatedByPairing());
		}

		@Test
		@DisplayName("should shift the id of a reference created after T0 by the counter difference")
		void shouldShiftIdCreatedAfterT0() {
			final ReferenceMutation<?> rewritten = translateSingle(
				new SetReferenceGroupMutation(key(RELATED, 8, 31), "group", 5)
			);

			final SetReferenceGroupMutation setGroup = assertInstanceOf(SetReferenceGroupMutation.class, rewritten);
			assertEquals(key(RELATED, 8, 51), setGroup.getReferenceKey());
			assertEquals("group", setGroup.getGroupType());
			assertEquals(5, setGroup.getGroupPrimaryKey());
			assertEquals(1, ReferenceKeyTranslatorTest.this.translator.getTranslatedByOffset());
		}

		@Test
		@DisplayName("should keep the insert's cardinality, referenced type and decisive timestamp")
		void shouldKeepEverythingButTheKeyOfAnInsert() {
			final InsertReferenceMutation original = (InsertReferenceMutation) new InsertReferenceMutation(
				key(RELATED, 7, 12), Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, PRODUCT
			).withDecisiveTimestamp(42L);

			final InsertReferenceMutation rewritten = assertInstanceOf(
				InsertReferenceMutation.class, translateSingle(original)
			);
			assertEquals(key(RELATED, 7, 3), rewritten.getReferenceKey());
			assertEquals(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, rewritten.getReferenceCardinality());
			assertEquals(PRODUCT, rewritten.getReferencedEntityType());
			assertEquals(42L, rewritten.getDecisiveTimestamp());
		}

		@Test
		@DisplayName("should rewrite reference attribute and group removal mutations too")
		void shouldRewriteAttributeAndGroupRemoval() {
			final EntityUpsertMutation translated = (EntityUpsertMutation) translate(
				upsert(
					new ReferenceAttributeMutation(key(RELATED, 7, 12), new UpsertAttributeMutation("order", 1)),
					new RemoveReferenceGroupMutation(key(RELATED, 7, 12))
				)
			);

			final List<? extends LocalMutation<?, ?>> mutations = translated.getLocalMutations();
			assertInstanceOf(ReferenceAttributeMutation.class, mutations.get(0));
			assertEquals(key(RELATED, 7, 3), ((ReferenceMutation<?>) mutations.get(0)).getReferenceKey());
			assertInstanceOf(RemoveReferenceGroupMutation.class, mutations.get(1));
			assertEquals(key(RELATED, 7, 3), ((ReferenceMutation<?>) mutations.get(1)).getReferenceKey());
		}

	}

	@Nested
	@DisplayName("Passing through")
	class PassingThrough {

		@Test
		@DisplayName("should leave a temporary id untouched")
		void shouldKeepTemporaryId() {
			assertUntouched(upsert(new InsertReferenceMutation(key(RELATED, 9, -1))));
		}

		@Test
		@DisplayName("should leave a reflected reference untouched")
		void shouldKeepReflectedReference() {
			assertUntouched(upsert(new RemoveReferenceMutation(key(VARIANTS, 7, 12))));
		}

		@Test
		@DisplayName("should leave an id removed before T0 untouched")
		void shouldKeepIdRemovedBeforeT0() {
			assertUntouched(upsert(new RemoveReferenceMutation(key(RELATED, 7, 20))));
		}

		@Test
		@DisplayName("should leave an entity the map does not name untouched")
		void shouldKeepEntityTheMapDoesNotName() {
			assertUntouched(
				new EntityUpsertMutation(
					PRODUCT, PRODUCT_PK + 1, EntityExistence.MUST_EXIST,
					new RemoveReferenceMutation(key(RELATED, 7, 12))
				)
			);
		}

		@Test
		@DisplayName("should stop translating an entity once the slice removes it")
		void shouldStopTranslatingRemovedEntity() {
			final EntityRemoveMutation removal = new EntityRemoveMutation(PRODUCT, PRODUCT_PK);
			assertSame(removal, translate(removal));

			assertUntouched(upsert(new RemoveReferenceMutation(key(RELATED, 7, 12))));
		}

		/**
		 * Asserts the translator returns the very same mutation instance.
		 *
		 * @param mutation the mutation to translate
		 */
		private void assertUntouched(@Nonnull EntityMutation mutation) {
			assertSame(mutation, translate(mutation));
			assertEquals(0, ReferenceKeyTranslatorTest.this.translator.getTranslatedByPairing());
			assertEquals(0, ReferenceKeyTranslatorTest.this.translator.getTranslatedByOffset());
		}

	}

	/**
	 * Writes the given rows as a map file.
	 *
	 * @param directory the directory to write into
	 * @param rows      tab-separated rows
	 * @return the map file
	 */
	@Nonnull
	private static Path writeMap(@Nonnull Path directory, @Nonnull String... rows) throws IOException {
		final Path mapFile = directory.resolve("reference-key-map.tsv");
		Files.write(mapFile, List.of(rows), StandardCharsets.UTF_8);
		return mapFile;
	}

	/**
	 * Creates a reference key with a known internal id.
	 */
	@Nonnull
	private static ReferenceKey key(@Nonnull String referenceName, int referencedPrimaryKey, int internalPrimaryKey) {
		return new ReferenceKey(referenceName, referencedPrimaryKey, internalPrimaryKey);
	}

	/**
	 * Wraps local mutations into an upsert of the product the map describes.
	 */
	@Nonnull
	private static EntityUpsertMutation upsert(@Nonnull LocalMutation<?, ?>... mutations) {
		return new EntityUpsertMutation(PRODUCT, PRODUCT_PK, EntityExistence.MUST_EXIST, mutations);
	}

	/**
	 * Translates an entity mutation.
	 */
	@Nonnull
	private EntityMutation translate(@Nonnull EntityMutation mutation) {
		return this.translator.translate(mutation);
	}

	/**
	 * Translates a single reference mutation of the mapped product and returns its translated form.
	 */
	@Nonnull
	private ReferenceMutation<?> translateSingle(@Nonnull LocalMutation<?, ?> mutation) {
		final EntityUpsertMutation translated = (EntityUpsertMutation) translate(upsert(mutation));
		return (ReferenceMutation<?>) translated.getLocalMutations().get(0);
	}

}
