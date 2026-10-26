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

package io.evitadb.api.functional.attribute;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaTypeMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaNormalizedMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeNormalizationStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalAttributeNormalizationStoragePart;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/** One catalog per page mode covers policy changes, all physical owners, rollback and production reload. */
@Tag(ENGINE)
@Tag(ATTRIBUTE)
@Tag(SCHEMA)
@Tag(SERIALIZATION)
@DisplayName("Built-with normalization policy across schema adoption and restart")
// Keep these engine lifecycles from competing with the fast loop's concurrent catalog bootstraps.
@Isolated("Pay the engine fixture cost once without concurrent catalog bootstrap contention")
class BuiltWithNormalizationFunctionalTest implements EvitaTestSupport {
	private static final Locale TR = Locale.forLanguageTag("tr");
	private TestPaths paths;
	private Evita evita;

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@ParameterizedTest(name = "{0} values")
	@ValueSource(ints = {1, 300})
	@DisplayName("Schema changes preserve populated key policies, while empty attributes adopt immediately")
	void shouldKeepBuiltWithKeysUntilAnEmptySchemaAdoption(int count) throws Exception {
		this.paths = createTestPaths("BuiltWithNormalizationFunctionalTest");
		this.evita = new Evita(configuration(this.paths));
		createLegacyFixture(count);
		createPolicyFixture(count);
		assertWarmUpAndLiveAdoptions(count);
		assertRetainedPoliciesAfterRestart(count);
		assertEmptyReadoptionAndReplay(count);
	}

	/**
	 * Seeds every physical role, then checks warm-up adoption and failed-batch rollback on that same engine.
	 *
	 * @param count number of `Product` entities seeded by the fixture, with primary keys `1..count`
	 */
	private void createPolicyFixture(int count) {
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute("unusedGlobal", String.class, a -> a.nullable())
			.withAttribute("globalRetyped", String.class, a -> a.uniqueGlobally().nullable().normalized(lowercase()))
			.withAttribute("global", String.class, a -> a.uniqueGlobally().sortable().nullable()
				.normalized(lowercase()))
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("Category").withoutGeneratedPrimaryKey().updateVia(session);
			session.defineEntitySchema("Other").withoutGeneratedPrimaryKey()
				.withGlobalAttribute("global").updateVia(session);
			session.defineEntitySchema("Product").withoutGeneratedPrimaryKey().withLocale(TR)
				.withGlobalAttribute("global").withGlobalAttribute("globalRetyped")
				.withAttribute("code", String.class, a -> a.unique().sortable().nullable().normalized(lowercase()))
				.withAttribute("sortOnly", String.class, a -> a.sortable().nullable().normalized(lowercase()))
				.withAttribute("owner", String.class, a -> a.localized().unique().nullable().normalized(lowercase()))
				.withAttribute("compoundOnly", String.class, a -> a.nullable().normalized(lowercase()))
				.withAttribute("nullElement", String.class, a -> a.nullable())
				.withAttribute("empty", String.class, a -> a.filterable().nullable())
				.withAttribute("vacant", String.class, a -> a.filterable().nullable())
				.withAttribute("unstable", String.class, a -> a.filterable().sortable().nullable()
					.normalized(lowercase(), asciiFolding()))
				.withAttribute("retyped", String.class, a -> a.filterable().nullable().normalized(lowercase()))
				.withAttribute("orphan", String.class,
					a -> a.filterable().sortable().nullable().normalized(lowercase()))
				.withAttribute("archivedOnly", String.class, a -> a.filterableInScope(Scope.values()).nullable())
				.withAttribute("number", Integer.class, a -> a.nullable())
				.withSortableAttributeCompound("compound", element("compoundOnly"), element("number"))
				.withSortableAttributeCompound("partial", element("nullElement"), element("number"))
				.withReferenceToEntity("virtual", "Category", Cardinality.ZERO_OR_MORE,
					r -> reference(r.indexedForFiltering()))
				.withReferenceToEntity("real", "Category", Cardinality.ZERO_OR_MORE,
					r -> reference(r.indexedForFilteringAndPartitioning()).withGroupTypeRelatedToEntity("Category"))
				.updateVia(session);
			session.defineEntitySchema("Category")
				.withReflectedReferenceToEntity("products", "Product", "virtual",
					r -> r.withCardinality(Cardinality.ZERO_OR_MORE).indexedForFiltering().withAttributesInherited())
				.updateVia(session);
			for (int i = 1; i <= count; i++) {
				session.createNewEntity("Category", i).upsertVia(session);
				product(session, i).setAttribute("retyped", "RETYPE" + i)
					.setAttribute("globalRetyped", "GLOBALTYPE" + i).upsertVia(session);
			}
			session.createNewEntity("Product", 9000).setAttribute("archivedOnly", "ARCHIVED").upsertVia(session);
			session.archiveEntity("Product", 9000);
			setNormalization(session, "Product", "archivedOnly", lowercase());
			// A later failing statement restores the row an earlier statement in the same warm-up batch changed.
			assertThrows(InvalidSchemaMutationException.class, () -> session.updateCatalogSchema(
				new SetAttributeSchemaNormalizedMutation("unusedGlobal", lowercase()),
				new SetAttributeSchemaNormalizedMutation("unusedGlobal", step("uninstalled"))));
			assertEquals(List.of(),
				catalog().getGlobalAttributeNormalizationRows().getOrDefault("unusedGlobal", List.of()));
			// Warm-up uses exactly the same populated scan as live schema mutations.
			setNormalization(session, "Product", "code", asciiFolding());
			setNormalization(session, "Product", "empty", lowercase());
			setNormalization(session, "Product", "nullElement", lowercase());
			session.goLiveAndClose();
		});
	}

	/**
	 * Mutates populated declarations and verifies unchanged keys, uniqueness and old-reader policy isolation.
	 *
	 * @param count number of `Product` entities seeded by the fixture, with primary keys `1..count`
	 */
	private void assertWarmUpAndLiveAdoptions(int count) {
		assertEquals(List.of(), row("archivedOnly"), "An archived String key pins the policy");
		assertEquals(List.of(lowercase()), row("code"));
		assertEquals(List.of(lowercase()), row("empty"));
		assertEquals(List.of(lowercase()), row("nullElement"), "NULL compound element is empty for its policy");
		assertThrows(IllegalStateException.class, () -> this.evita.updateCatalog(TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.updateCatalogSchema(new SetAttributeSchemaNormalizedMutation("unusedGlobal", lowercase()));
				throw new IllegalStateException("Rollback policy test");
			}));
		assertEquals(List.of(),
			catalog().getGlobalAttributeNormalizationRows().getOrDefault("unusedGlobal", List.of()));
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			setNormalization(session, "Product", "code");
			setNormalization(session, "Product", "sortOnly", asciiFolding());
			setNormalization(session, "Product", "owner");
			setNormalization(session, "Product", "compoundOnly");
			setNormalization(session, "Product", "unstable");
			session.updateEntitySchema(new ModifyEntitySchemaMutation("Product",
				new SetAttributeSchemaNormalizedMutation("retyped"),
				new ModifyAttributeSchemaTypeMutation("retyped", Integer.class, 0)));
			session.updateCatalogSchema(new SetAttributeSchemaNormalizedMutation("globalRetyped"),
				new ModifyAttributeSchemaTypeMutation("globalRetyped", Integer.class, 0));
			for (final String reference : List.of("virtual", "real")) {
				session.updateEntitySchema(new ModifyEntitySchemaMutation("Product",
					new ModifyReferenceAttributeSchemaMutation(reference,
						new SetAttributeSchemaNormalizedMutation("code"))));
			}
			session.updateCatalogSchema(new SetAttributeSchemaNormalizedMutation("global"));
			session.getEntitySchemaOrThrow("Product").openForWrite().withoutAttribute("orphan").updateVia(session);
			session.createNewEntity("Category", count + 1).upsertVia(session);
			product(session, count + 1)
				.setReference("virtual", count + 1, r -> r.setAttribute("code", "NEW"))
				.setReference("virtual", 1, r -> r.setAttribute("code", "REF" + (count + 1)))
				.removeAttribute("orphan").setAttribute("empty", "EMPTY")
				.setAttribute("nullElement", "UPPER").upsertVia(session);
			session.createNewEntity("Other", 1).setAttribute("global", "OTHER").upsertVia(session);
		});
		assertPopulatedKeys(count + 1);
		assertEquals(List.of(lowercase()), row("orphan"));
		assertEquals(List.of(lowercase()), catalog().getGlobalAttributeNormalizationRows().get("global"));
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertTrue(session.getEntitySchemaOrThrow("Product").getAttribute("code").orElseThrow()
				.getNormalization().isEmpty(), "Schema reports the accepted declaration");
			assertEquals(List.of(1), session.queryListOfEntityReferences(query(collection("Product"),
				filterBy(attributeEquals("code", "ITEM1")))).stream().map(it -> it.getPrimaryKey()).toList());
		});
		assertThrows(UniqueValueViolationException.class, () -> this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.createNewEntity("Product", 10000).setAttribute("code", "item1").upsertVia(session);
		}));
		assertThrows(UniqueValueViolationException.class, () -> this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.createNewEntity("Other", 2).setAttribute("global", "GLOBAL1").upsertVia(session);
		}));
		// Old snapshot readers retain their policy when an empty row switches in a committed writer.
		try (final EvitaSessionContract reader = this.evita.createReadOnlySession(TEST_CATALOG)) {
			final EntityCollection oldCollection = productCollection();
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				setNormalization(session, "Product", "vacant", lowercase());
			});
			assertTrue(reader.getEntitySchemaOrThrow("Product").getAttribute("vacant").orElseThrow()
				.getNormalization().isEmpty());
			assertEquals("TITLE", oldCollection.getAttributeNormalizationResolver().resolve(null,
				oldCollection.getSchema().getAttribute("vacant").orElseThrow(), null).apply("TITLE"));
			assertEquals("title", productCollection().getAttributeNormalizationResolver().resolve(null,
				productCollection().getSchema().getAttribute("vacant").orElseThrow(), null).apply("TITLE"));
		}

	}

	/**
	 * Uses the existing single normal restart to check orphan, retyped, compound and legacy bindings.
	 *
	 * @param count number of `Product` entities seeded by the fixture, with primary keys `1..count`
	 */
	private void assertRetainedPoliciesAfterRestart(int count) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrow("Product").openForWrite()
				.withoutSortableAttributeCompound("compound")
				.withSortableAttributeCompound("compound", element("unstable"), element("number")).updateVia(session);
		});
		this.evita.close();
		this.evita = new Evita(configuration(this.paths));
		this.evita.waitUntilFullyInitialized();
		assertLegacyFixture();
		assertPopulatedKeys(count + 1);
		assertEquals(Integer.class, productCollection().getSchema().getAttribute("retyped").orElseThrow().getType());
		assertEquals("retype1", index().getFilterIndex(key("retyped")).getInvertedIndex()
			.getNormalizer().apply("RETYPE1"));
		assertEquals(1, catalog().getCatalogIndex(Scope.LIVE).getGlobalUniqueIndexes().entrySet().stream()
			.filter(entry -> entry.getKey().attributeName().equals("globalRetyped"))
			.findFirst().orElseThrow().getValue()
			.getEntityReferenceByUniqueValue("GLOBALTYPE1", null, catalog()).orElseThrow().getPrimaryKey());
		final Serializable[] oldCompoundTuple = {"ǧᴀʀ", 1};
		index().getSortIndex(key("compound")).getKeyNormalizer().apply(oldCompoundTuple);
		assertEquals("g\u030cᴀʀ", oldCompoundTuple[0],
			"Retained compound binding names compoundOnly, not today's unstable element");
		assertEquals("orphan1", index().getSortIndex(key("orphan")).getValueOfRecord(1).orElseThrow());
		assertEquals("orphanx", index().getFilterIndex(key("orphan")).getInvertedIndex()
			.getNormalizer().apply("ORPHANX"));
	}

	/**
	 * Deletes the last keys, re-adopts declarations and checks the existing crash-copy replay checkpoint.
	 *
	 * @param count number of `Product` entities seeded by the fixture, with primary keys `1..count`
	 */
	private void assertEmptyReadoptionAndReplay(int count) throws Exception {
		// Restore declarations before deletion; base entity removal requires every stored attribute's schema.
		// Deleting values never switches a row, even after its last key disappears.
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.updateCatalogSchema(new ModifyAttributeSchemaTypeMutation("globalRetyped", String.class, 0),
				new SetAttributeSchemaNormalizedMutation("globalRetyped", lowercase()));
			session.updateEntitySchema(new ModifyEntitySchemaMutation("Product",
				new ModifyAttributeSchemaTypeMutation("retyped", String.class, 0),
				new SetAttributeSchemaNormalizedMutation("retyped", lowercase())));
			session.getEntitySchemaOrThrow("Product").openForWrite()
				.withoutSortableAttributeCompound("compound")
				.withSortableAttributeCompound("compound", element("compoundOnly"), element("number"))
				.withAttribute("orphan", String.class,
					a -> a.filterable().sortable().nullable().normalized(lowercase()))
				.updateVia(session);
			for (int i = 1; i <= count + 1; i++) {
				session.deleteEntity("Product", i);
			}
		});
		assertEquals(List.of(lowercase()), row("code"));
		// Other still has a global key, so an otherwise empty adopting collection cannot switch it.
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.updateCatalogSchema(new SetAttributeSchemaNormalizedMutation("global", asciiFolding()));
		});
		assertEquals(List.of(lowercase()), catalog().getGlobalAttributeNormalizationRows().get("global"));
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.deleteEntity("Other", 1);
			session.updateCatalogSchema(new SetAttributeSchemaNormalizedMutation("global", asciiFolding()));
			setNormalization(session, "Product", "code", asciiFolding());
			setNormalization(session, "Product", "compoundOnly", asciiFolding());
			for (final String reference : List.of("virtual", "real")) {
				session.updateEntitySchema(new ModifyEntitySchemaMutation("Product",
					new ModifyReferenceAttributeSchemaMutation(reference,
						new SetAttributeSchemaNormalizedMutation("code", asciiFolding()))));
			}
			session.createNewEntity("Product", 2000).setAttribute("code", "UPPER")
				.setAttribute("compoundOnly", "UPPER").setAttribute("number", 1)
				.setAttribute("global", "UPPER")
				.setReference("virtual", 1, r -> r.setAttribute("code", "UPPER"))
				.setReference("real", 1, r -> r.setGroup("Category", 1).setAttribute("code", "UPPER"))
				.upsertVia(session);
			session.createNewEntity("Other", 2).setAttribute("global", "UPPER-OTHER").upsertVia(session);
		});
		assertEquals("UPPER-OTHER", catalog().getCollectionForEntityOrThrowException("Other")
			.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL)).getSortIndex(key("global"))
			.getValueOfRecord(2).orElseThrow());
		assertEquals(List.of(asciiFolding()), row("code"));
		assertEquals("UPPER", index().getSortIndex(key("code")).getValueOfRecord(2000).orElseThrow());
		assertEquals(List.of(asciiFolding()), catalog().getGlobalAttributeNormalizationRows().get("global"));
		for (final String reference : List.of("virtual", "real")) {
			final ReferencedTypeEntityIndex type =
				(ReferencedTypeEntityIndex) productCollection().getIndexByKeyIfExists(
					new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, reference));
			assertEquals("UPPER", type.getFilterIndex(new AttributeIndexKey(reference, "code", null))
				.getInvertedIndex().getValueIterator().next().getValue());
		}
		assertReplayRowsAndKeys();
	}

	/**
	 * Creates the pre-545 absence shape in the same engine, without paying for another restart.
	 *
	 * @param count number of `Product` entities seeded by the fixture, with primary keys `1..count`
	 */
	private void createLegacyFixture(int count) throws Exception {
		this.evita.defineCatalog("legacyPolicy")
			.withAttribute("global", String.class, a -> a.uniqueGlobally().nullable()).updateViaNewSession(this.evita);
		this.evita.updateCatalog("legacyPolicy", session -> {
			session.defineEntitySchema("Legacy").withoutGeneratedPrimaryKey().withGlobalAttribute("global")
				.withAttribute("code", String.class, a -> a.unique().sortable()).updateVia(session);
			for (int id = 1; id <= count; id++) {
				session.createNewEntity("Legacy", id).setAttribute("code", "ÍTITLE" + id)
					.setAttribute("global", "ÍGLOBAL" + id).upsertVia(session);
			}
		});
		final Catalog legacy = (Catalog) this.evita.getCatalogInstanceOrThrowException("legacyPolicy");
		// The keys and schemas have release semantics; removing just the new singletons reproduces legacy absence.
		legacy.getPersistenceService().getStoragePartPersistenceService(legacy.getVersion())
			.removeStoragePart(legacy.getVersion(), 1L, GlobalAttributeNormalizationStoragePart.class);
		legacy.getCollectionForEntityOrThrowException("Legacy").getStoragePartPersistenceService()
			.removeStoragePart(legacy.getVersion(), 1L, AttributeNormalizationStoragePart.class);
		final ProgressingFuture<Void> flush = legacy.flush();
		flush.execute(Runnable::run);
		flush.get(30, TimeUnit.SECONDS);
	}

	/** Missing parts restore NFD-only before scalar and catalog-unique trees are loaded. */
	private void assertLegacyFixture() {
		final Catalog legacy = (Catalog) this.evita.getCatalogInstanceOrThrowException("legacyPolicy");
		final EntityCollection collection = legacy.getCollectionForEntityOrThrowException("Legacy");
		assertTrue(legacy.getGlobalAttributeNormalizationRows().isEmpty());
		assertTrue(collection.getNormalizationPolicy().rows().isEmpty());
		assertEquals("I\u0301TITLE1", collection.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL))
			.getSortIndex(key("code")).getValueOfRecord(1).orElseThrow());
		assertEquals(1, legacy.getCatalogIndex(Scope.LIVE).getGlobalUniqueIndexes().values().iterator().next()
			.getEntityReferenceByUniqueValue("ÍGLOBAL1", null, legacy).orElseThrow().getPrimaryKey());
	}

	/** Proves an actual WAL gap and boots a crash copy through production replay, with no graceful checkpoint. */
	private void assertReplayRowsAndKeys() throws IOException {
		final Catalog before = catalog();
		assertTrue(before.getVersion() > before.getPersistenceService().getLastCatalogVersion(),
			"A bootstrap behind the visible catalog is required to exercise WAL replay");
		final TestPaths replayPaths = createTestPaths("BuiltWithNormalizationReplay");
		try {
			try (final Stream<Path> files = Files.walk(this.paths.storage())) {
				for (final Path path : files.toList()) {
					final Path destination = replayPaths.storage().resolve(this.paths.storage().relativize(path));
					if (Files.isDirectory(path)) {
						Files.createDirectories(destination);
					} else {
						Files.createDirectories(destination.getParent());
						Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
					}
				}
			}
			try (final Evita recovered = new Evita(configuration(replayPaths))) {
				recovered.waitUntilFullyInitialized();
				final Catalog after = (Catalog) recovered.getCatalogInstanceOrThrowException(TEST_CATALOG);
				assertEquals(before.getVersion(), after.getVersion());
				assertEquals(before.getGlobalAttributeNormalizationRows(), after.getGlobalAttributeNormalizationRows());
				for (final String type : List.of("Product", "Other", "Category")) {
					assertEquals(before.getCollectionForEntityOrThrowException(type).getNormalizationPolicy(),
						after.getCollectionForEntityOrThrowException(type).getNormalizationPolicy());
				}
				assertEquals("UPPER", after.getCollectionForEntityOrThrowException("Product")
					.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL)).getSortIndex(key("code"))
					.getValueOfRecord(2000).orElseThrow());
			}
		} finally {
			cleanupTestPaths(replayPaths);
		}
	}

	/**
	 * Keeps checkpoints under test control so a copied bootstrap has a demonstrable WAL replay gap.
	 *
	 * @param testPaths storage and export locations of the test engine
	 * @return configuration of the test engine with the periodic checkpoint effectively disabled
	 */
	@Nonnull
	private EvitaConfiguration configuration(@Nonnull TestPaths testPaths) {
		final EvitaConfiguration base = newTestEvitaConfigurationBuilder(testPaths).build();
		return EvitaConfiguration.builder(base).transaction(TransactionOptions.builder(base.transaction())
			.checkpointIntervalInMillis(3_600_000L).build()).build();
	}

	/**
	 * Supplies a raw value to each physical key producer, including virtual and grouped materializations.
	 *
	 * @param session read-write session of the catalog under test
	 * @param id primary key of the new `Product`; it also seeds each attribute value
	 * @return builder of the not yet upserted `Product` with all of its attributes and both references set
	 */
	@Nonnull
	private static EntityBuilder product(@Nonnull EvitaSessionContract session, int id) {
		return session.createNewEntity("Product", id).setAttribute("code", "ITEM" + id)
			.setAttribute("sortOnly", "SORT" + id).setAttribute("owner", TR, "TITLE" + id)
			.setAttribute("compoundOnly", "PART" + id).setAttribute("number", id)
			.setAttribute("unstable", "ǧᴀʀ" + id).setAttribute("global", "GLOBAL" + id)
			.setAttribute("orphan", "ORPHAN" + id)
			.setReference("virtual", id, r -> r.setAttribute("code", "REF" + id))
			.setReference("real", id, r -> r.setGroup("Category", 1).setAttribute("code", "REF" + id));
	}

	/**
	 * Defines reference scalar and compound keys.
	 *
	 * @param reference editor of the reference schema to extend
	 * @return the same editor, with the `code` attribute and the `compound` sortable compound defined
	 */
	@Nonnull
	private static <T extends ReferenceSchemaEditor<T>> T reference(@Nonnull T reference) {
		return reference.withAttribute("code", String.class,
				a -> a.filterable().sortable().nullable().normalized(lowercase()))
			.withAttribute("number", Integer.class, a -> a.nullable())
			.withSortableAttributeCompound("compound", element("code"), element("number"));
	}

	/**
	 * Inspects future and existing functions after schema adoption and after reload.
	 *
	 * @param count primary key of the highest seeded `Product`, whose key values are asserted
	 */
	private void assertPopulatedKeys(int count) {
		final EntityIndex index = index();
		assertEquals("upper", ((ComparableArray) index.getSortIndex(key("partial"))
			.getValueOfRecord(count).orElseThrow()).array()[0]);
		assertEquals("item" + count, index.getSortIndex(key("code")).getValueOfRecord(count).orElseThrow());
		assertEquals("sort" + count, index.getSortIndex(key("sortOnly")).getValueOfRecord(count).orElseThrow());
		assertEquals(count, index.getUniqueIndex(key("owner")).getRecordIdByUniqueValue("TITLE" + count));
		assertEquals("GAR" + count, index.getSortIndex(key("unstable")).getValueOfRecord(count).orElseThrow());
		assertEquals("part" + count, ((ComparableArray) index.getSortIndex(key("compound"))
			.getValueOfRecord(count).orElseThrow()).array()[0]);
		for (final String reference : List.of("virtual", "real")) {
			final ReferencedTypeEntityIndex type =
				(ReferencedTypeEntityIndex) productCollection().getIndexByKeyIfExists(
					new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, reference));
			assertFalse(type.getFilterIndex(new AttributeIndexKey(reference, "code", null))
				.getInvertedIndex().getRecordsEqualTo("ref" + count).isEmpty());
		}
		final EntityCollection category = catalog().getCollectionForEntityOrThrowException("Category");
		assertEquals(List.of(lowercase()), category.getNormalizationPolicy().rows().get(
			new AttributeIndexKey("products", "code", null)));
	}

	/**
	 * Applies an explicit statement, including an idempotent declaration after the attribute was emptied.
	 *
	 * @param session read-write session of the catalog under test
	 * @param type entity type owning the attribute
	 * @param name name of the attribute whose declaration is set
	 * @param steps declared normalization chain; an empty chain declares none
	 */
	private static void setNormalization(
		@Nonnull EvitaSessionContract session, @Nonnull String type,
		@Nonnull String name, @Nonnull AnalysisStep... steps
	) {
		session.updateEntitySchema(new ModifyEntitySchemaMutation(type,
			new SetAttributeSchemaNormalizedMutation(name, steps)));
	}

	/**
	 * Returns the current catalog snapshot.
	 *
	 * @return the current instance of the test catalog
	 */
	@Nonnull
	private Catalog catalog() {
		return (Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
	}

	/**
	 * Returns the current Product owner.
	 *
	 * @return the `Product` collection of the current catalog instance
	 */
	@Nonnull
	private EntityCollection productCollection() {
		return catalog().getCollectionForEntityOrThrowException("Product");
	}

	/**
	 * Returns its live global index.
	 *
	 * @return the global entity index of the `Product` collection
	 */
	@Nonnull
	private EntityIndex index() {
		return productCollection().getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL));
	}

	/**
	 * Returns an entity attribute's built-with row.
	 *
	 * @param name name of the entity attribute
	 * @return steps the attribute keys were built with, or an empty list when no row is recorded
	 */
	@Nonnull
	private List<AnalysisStep> row(@Nonnull String name) {
		return productCollection().getNormalizationPolicy().rows().getOrDefault(key(name), List.of());
	}

	/**
	 * Scope- and locale-independent entity attribute identity.
	 *
	 * @param name name of the entity attribute
	 * @return key of the attribute without reference or locale
	 */
	@Nonnull
	private static AttributeIndexKey key(@Nonnull String name) {
		return new AttributeIndexKey(null, name, null);
	}

	/**
	 * Ascending compound element.
	 *
	 * @param name name of the attribute forming the compound element
	 * @return ascending element placing nulls last
	 */
	@Nonnull
	private static AttributeElement element(@Nonnull String name) {
		return new AttributeElement(name, ASC, OrderBehaviour.NULLS_LAST);
	}
}
