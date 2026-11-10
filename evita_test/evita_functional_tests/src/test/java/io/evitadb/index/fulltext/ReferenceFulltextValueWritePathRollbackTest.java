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

package io.evitadb.index.fulltext;

import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.InsertReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Locale;
import java.util.Set;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.*;

/** Real late write-path failures, after dictionary changes and before a unique attribute accepts the entity. */
@Tag(ENGINE)
@Tag(FULLTEXT)
@Tag(TRANSACTION)
@DisplayName("Reference dictionary entity rollback")
class ReferenceFulltextValueWritePathRollbackTest implements EvitaTestSupport {
	private static final String CATALOG = "referenceDictionaryRollback";
	private static final FulltextFieldKey KEY = FulltextFieldKey.referenceAttribute("categories", "label");
	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReferenceFulltextValueWritePathRollbackTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null) {
			this.evita.close();
		}
		if (this.paths != null) {
			cleanupTestPaths(this.paths);
		}
	}

	/** Warm-up inverses and entity savepoints both rewind value birth, ALL demotion and fresh field registration. */
	@ParameterizedTest(name = "alive={0}, retired generation={1}")
	@CsvSource({"false, false", "true, false", "false, true", "true, true"})
	@DisplayName("should restore union and dictionary after a late unique-value failure")
	void shouldRestoreUnionAndDictionaryAfterLateFailure(boolean alive, boolean retire) {
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(CATALOG, session -> {
			session.defineEntitySchema("product").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
				.withAttribute("code", String.class, attribute -> attribute.unique())
				.withReferenceTo("categories", "category", Cardinality.ZERO_OR_MORE,
					reference -> reference.withAttribute("label", String.class,
						attribute -> attribute.localized().nullable().searchable()))
				.updateVia(session);
			for (int owner = 1; owner <= 2; owner++) {
				session.upsertEntity(session.createNewEntity("product", owner).setAttribute("code", "code" + owner)
					.setReference("categories", 10,
						reference -> reference.setAttribute("label", Locale.ENGLISH, "red")));
			}
		});
		if (alive) {
			this.evita.updateCatalog(CATALOG, session -> { session.goLiveAndClose(); });
		}
		if (retire) {
			this.evita.updateCatalog(CATALOG, session -> {
				session.getEntitySchemaOrThrow("product").openForWrite()
				.withReferenceTo("categories", "category", Cardinality.ZERO_OR_MORE,
					reference -> reference.withAttribute("label", String.class,
						attribute -> attribute.localized().nullable().searchable().lengthPivot(100)))
				.updateVia(session);
			});
		}
		this.evita.updateCatalog(CATALOG, session -> {
			final ReferenceKey reference = new ReferenceKey("categories", 10);
			// Keep a dictionary layer open before the failed entity on the ordinary generation path.
			if (!retire) {
				session.upsertEntity(new EntityUpsertMutation("product", 3, EntityExistence.MUST_NOT_EXIST,
					new InsertReferenceMutation(reference),
					new ReferenceAttributeMutation(reference,
						new UpsertAttributeMutation("label", Locale.ENGLISH, "red")),
					new UpsertAttributeMutation("code", "code3")));
			}
			assertThrows(UniqueValueViolationException.class, () -> session.upsertEntity(
				new EntityUpsertMutation("product", 4, EntityExistence.MUST_NOT_EXIST,
					new InsertReferenceMutation(reference),
					new ReferenceAttributeMutation(reference,
					new UpsertAttributeMutation("label", Locale.ENGLISH, "blue")),
					new UpsertAttributeMutation("code", "code1"))));
			// The same session/transaction must remain writable after the per-entity rollback.
			session.upsertEntity(session.createNewEntity("product", 5).setAttribute("code", "code5"));
		});
		assertRestored(retire);
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
		assertRestored(retire);
	}

	/** Checks live counts, form and enrollment, plus the union and generation registry after a real reload. */
	private void assertRestored(boolean retire) {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstanceOrThrowException(CATALOG);
		final GlobalEntityIndex global = (GlobalEntityIndex) catalog.getCollectionForEntityInternal("product")
			.orElseThrow().getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE));
		final FulltextIndex index = global.getFulltextIndex(Locale.ENGLISH);
		assertEquals(1, index.getFieldCount());
		assertEquals(0, index.getFieldId(KEY));
		assertFalse(index.isFieldRetired(0));
		final ReferenceFulltextValueIndex values = index.getReferenceValues(0);
		assertFalse(values.isEnrolled(4));
		assertEquals(2, values.allocator.getNextValueId());
		assertEquals(1, values.values.size());
		assertTrue(values.lookup(Set.of("blue")).isEmpty());
		// owners 1 and 2, plus owner 3, which the failing session writes first only when no retirement is pending
		assertEquals(retire ? 2L : 3L, values.targetCounts.getLongRecordEqualTo(10).orElseThrow());
		assertEquals(ReferenceFulltextValueIndex.MembershipForm.ALL, values.lookup(Set.of("red")).getFirst().form());
		assertFalse(index.hasIndexedValue(0, 4));
		assertEquals(retire ? 2 : 3, values.resolve(values.lookup(Set.of("red")).getFirst(),
			new BaseBitmap(1, 2, 3, 4)).size());
	}
}
