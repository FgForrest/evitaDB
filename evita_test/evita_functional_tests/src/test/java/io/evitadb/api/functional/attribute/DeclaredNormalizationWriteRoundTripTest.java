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
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.attribute.GlobalUniqueIndex;
import io.evitadb.index.attribute.SortIndex.ComparableArray;
import io.evitadb.index.trigram.TrigramCodec;
import io.evitadb.index.trigram.TrigramIndex;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator.SUBSTRING_SEARCH;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SERIALIZATION;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Catalog round trips for declared String key functions, inspecting index keys directly so query-side routing
 * can be implemented independently. Counts exercise SINGLE and PAGED filter, unique, sort and virtual roots.
 */
@Tag(INDEXING)
@Tag(SERIALIZATION)
@DisplayName("Declared normalization write and loader round trips")
// Keep these engine lifecycles from competing with the fast loop's concurrent catalog bootstraps.
@Isolated("Pay the engine fixture cost once without concurrent catalog bootstrap contention")
class DeclaredNormalizationWriteRoundTripTest implements EvitaTestSupport {
	private static final List<String> ORPHAN_ROLES =
		List.of("search", "code", "owner", "sortOnly", "compound", "reference", "global");
	private static final Locale TURKISH = Locale.forLanguageTag("tr");
	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("DeclaredNormalizationWriteRoundTrip");
		this.evita = new Evita(configuration());
		this.evita.waitUntilFullyInitialized();
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 300})
	@DisplayName("Canonical keys survive reload, promotion, demotion, update and removal")
	void shouldKeepDeclaredKeysThroughWritesReloadUpdatesAndRemovals(int count) {
		createFixture(count);
		assertKeys(count);
		restart();
		assertKeys(count);
		this.evita.updateCatalog(TEST_CATALOG, session -> { product(session, 2000, 1).upsertVia(session); });
		restart();
		final EntityCollection promoted = catalog().getCollectionForEntityOrThrowException("Product");
		final EntityIndex partition = promoted.getIndexByKeyIfExists(new EntityIndexKey(
			EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
			new RepresentativeReferenceKey(
				new ReferenceKey("virtual", 1))));
		assertNotNull(partition);
		assertEquals("GAR1", partition.getSortIndex(new AttributeIndexKey("virtual", "code", null))
			.getValueOfRecord(1).orElseThrow());
		this.evita.updateCatalog(TEST_CATALOG, session -> { session.deleteEntity("Product", 2000); });
		restart();
		assertKeys(count);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntity("Product", 1, QueryConstraints.entityFetchAllContent())
				.orElseThrow().openForWrite()
				.setAttribute("code", raw(1001))
				.setAttribute("sortOnly", raw(1001))
				.setAttribute("owner", TURKISH, "ÍTITLE1001")
				.setAttribute("global", TURKISH, "ÍTITLE1001")
				.setAttribute("globalLocale", TURKISH, "ÍTITLE1001")
				.setAttribute("tags", new String[]{" NEW ", "new"})
				.setReference("virtual", 1, r -> r.setAttribute("code", raw(1001))
					.setAttribute("tags", new String[]{" NEW ", "new"})
					.setAttribute("rootElement", "ÍTITLE-1001")
					.setAttribute("localElement", TURKISH, "ÍTITLE-1001")
					.setAttribute("uniqueTitle", TURKISH, "ÍTITLE1001"))
				.upsertVia(session);
		});
		restart();
		final EntityIndex index = productIndex();
		assertEquals("GAR1001", index.getSortIndex(new AttributeIndexKey(null, "sortOnly", null))
			.getValueOfRecord(1).orElseThrow());
		assertEquals(0, index.getFilterIndex(new AttributeIndexKey(null, "code", null))
			.getInvertedIndex().getRecordsEqualTo("GAR1").size());
		assertEquals(1, index.getFilterIndex(new AttributeIndexKey(null, "tags", null))
			.getInvertedIndex().getRecordsEqualTo("new").size());
		this.evita.updateCatalog(TEST_CATALOG, session -> { session.deleteEntity("Product", 1); });
		restart();
		if (count > 1) {
			assertEquals(0, productIndex().getFilterIndex(new AttributeIndexKey(null, "code", null))
				.getInvertedIndex().getRecordsEqualTo("GAR1001").size());
		}
	}

	/** Populated value indexes can survive removal or renaming of their current schema declaration. */
	@ParameterizedTest(name = "{0} values / rename={1}")
	@MethodSource("schemaChanges")
	@DisplayName("should reopen populated indexes after removing or renaming their declaration")
	void shouldReloadAfterSchemaChange(int count, boolean rename) {
		createFixture(count);
		for (final String role : ORPHAN_ROLES) {
			changeDeclaration(role, rename);
		}
		assertEquals(count, productIndex().getAllPrimaryKeys().size());
		restart();
		assertEquals(count, productIndex().getAllPrimaryKeys().size());
		for (final String role : ORPHAN_ROLES) {
			assertOrphanedKeys(role, rename);
		}
	}

	/** Changes independent declarations in one fixture without paying another engine setup for each role. */
	private void changeDeclaration(@Nonnull String role, boolean rename) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			if (role.equals("global")) {
				if (rename) {
					session.updateCatalogSchema(new ModifyAttributeSchemaNameMutation("global", "renamedGlobal"));
				} else {
					session.getCatalogSchema().openForWrite().withoutAttribute("global").updateVia(session);
				}
			} else if (role.equals("reference")) {
				if (rename) {
					session.updateEntitySchema(new ModifyEntitySchemaMutation("Product",
						new ModifyReferenceAttributeSchemaMutation("external",
							new ModifyAttributeSchemaNameMutation("code", "renamedCode"))));
				} else {
					session.getEntitySchemaOrThrow("Product").openForWrite()
						.withReferenceTo("external", "ExternalCategory", Cardinality.ZERO_OR_MORE,
							r -> r.withoutAttribute("code")).updateVia(session);
				}
			} else if (role.equals("compound")) {
				if (rename) {
					session.updateEntitySchema(new ModifyEntitySchemaMutation("Product",
						new ModifyAttributeSchemaNameMutation("rootElement", "renamedElement")));
				} else {
					session.getEntitySchemaOrThrow("Product").openForWrite()
						.withoutSortableAttributeCompound("compound").withoutAttribute("rootElement").updateVia(session);
				}
			} else if (rename) {
				session.updateEntitySchema(new ModifyEntitySchemaMutation("Product",
					new ModifyAttributeSchemaNameMutation(role, "renamed" + role)));
			} else {
				session.getEntitySchemaOrThrow("Product").openForWrite().withoutAttribute(role).updateVia(session);
			}
		});
	}

	/** Each removed or renamed role retains its original footprint and captured raw-input function after reload. */
	private void assertOrphanedKeys(@Nonnull String role, boolean rename) {
		if (!rename) {
			if (role.equals("global")) {
				assertTrue(catalog().getSchema().getAttribute("global").isEmpty());
			} else if (role.equals("reference")) {
				assertTrue(catalog().getCollectionForEntityOrThrowException("Product").getSchema()
					.getReference("external").orElseThrow().getAttribute("code").isEmpty());
			} else {
				assertTrue(catalog().getCollectionForEntityOrThrowException("Product").getSchema()
					.getAttribute(role.equals("compound") ? "rootElement" : role).isEmpty());
			}
		}
		// The retained sort footprint proves the compound lookup must tolerate its missing declaration.
		if (role.equals("compound")) {
			assertNotNull(productIndex().getSortIndex(new AttributeIndexKey(null, "compound", TURKISH)));
			final Serializable[] tuple = {"ÍTITLE1", "ÍTITLE1"};
			productIndex().getSortIndex(new AttributeIndexKey(null, "compound", TURKISH)).getKeyNormalizer().apply(tuple);
			assertArrayEquals(new String[]{"ititle1", "ıtıtle1"}, tuple);
		} else if (role.equals("sortOnly")) {
			assertNotNull(productIndex().getSortIndex(new AttributeIndexKey(null, "sortOnly", null)));
			assertEquals("GAR1", productIndex().getSortIndex(new AttributeIndexKey(null, "sortOnly", null))
				.getKeyNormalizer().apply(raw(1)));
		} else if (role.equals("reference")) {
			final EntityIndex referenceIndex = catalog().getCollectionForEntityOrThrowException("Product")
				.getIndexByKeyIfExists(new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "external"));
			assertNotNull(referenceIndex);
			final FilterIndex filter = referenceIndex.getFilterIndex(new AttributeIndexKey("external", "code", null));
			assertNotNull(filter);
			assertFalse(filter.getInvertedIndex().getRecordsEqualTo("GAR1").isEmpty());
			assertEquals("GAR1", filter.getInvertedIndex().getNormalizer().apply(raw(1)));
		} else if (role.equals("global")) {
			final Catalog catalog = catalog();
			final GlobalUniqueIndex global = catalog.getCatalogIndex(Scope.LIVE)
				.getGlobalUniqueIndexes().entrySet().stream()
				.filter(entry -> entry.getKey().attributeName().equals("global")).findFirst().orElseThrow().getValue();
			assertEquals(1, global.getEntityReferenceByUniqueValue("ÍTITLE1", TURKISH, catalog)
				.orElseThrow().getPrimaryKey());
		} else if (!role.equals("global")) {
			assertNotNull(productIndex().getFilterIndex(new AttributeIndexKey(null, role,
				role.equals("owner") ? TURKISH : null)));
		}
	}

	/** Exercises inline and paged roots for each indexed String role and both declaration changes. */
	@Nonnull
	static Stream<Arguments> schemaChanges() {
		return Stream.of(1, 300).flatMap(count ->
			Stream.of(false, true).map(rename -> Arguments.of(count, rename)));
	}

	/** Builds entity, reference, global and compound-only String footprints. */
	private void createFixture(int count) {
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute("global", String.class,
				a -> a.localized().uniqueGlobally().normalized(asciiFolding(), lowercase()))
			.withAttribute("globalArray", String[].class,
				a -> a.uniqueGlobally().normalized(lowercase(), asciiFolding(), trim(), removeCharacters("-")))
			.withAttribute("globalLocale", String.class,
				a -> a.localized().uniqueGloballyWithinLocale().normalized(asciiFolding(), lowercase()))
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("Category").withoutGeneratedPrimaryKey().withLocale(TURKISH).updateVia(session);
			session.defineEntitySchema("Product").withoutGeneratedPrimaryKey().withLocale(TURKISH)
				.withGlobalAttribute("global").withGlobalAttribute("globalLocale").withGlobalAttribute("globalArray")
				.withAttribute("code", String.class, a -> a.sortable().unique().normalized(
					lowercase(), asciiFolding(), trim(), removeCharacters("-")))
				.withAttribute("search", String.class, a -> a.filterable()
					.acceleratedFor(SUBSTRING_SEARCH)
					.normalized(lowercase(), asciiFolding(), trim(), removeCharacters("-")))
				.withAttribute("sortOnly", String.class, a -> a.sortable().normalized(
					lowercase(), asciiFolding(), trim(), removeCharacters("-")))
				.withAttribute("tags", String[].class, a -> a.filterable().normalized(lowercase(), trim()))
				.withAttribute("owner", String.class, a -> a.localized().unique().normalized(asciiFolding(), lowercase()))
				.withAttribute("ownerArray", String[].class,
					a -> a.localized().unique().normalized(lowercase(), asciiFolding(), trim(), removeCharacters("-")))
				.withAttribute("within", String.class,
					a -> a.localized().sortable().uniqueWithinLocale().normalized(asciiFolding(), lowercase()))
				.withAttribute("rootElement", String.class, a -> a.normalized(asciiFolding(), lowercase()))
				.withAttribute("localElement", String.class, a -> a.localized().normalized(asciiFolding(), lowercase()))
				.withSortableAttributeCompound("compound", element("rootElement"), element("localElement"))
				.withReferenceTo("external", "ExternalCategory", Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFiltering().withAttribute("code", String.class,
						a -> a.filterable().sortable().normalized(lowercase(), asciiFolding(), trim(), removeCharacters("-"))))
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
				final int id = i;
				session.createNewEntity("Category", i).upsertVia(session);
				product(session, id, id).upsertVia(session);
			}
			session.goLiveAndClose();
		});
	}

	/** Creates all attribute footprints, with independently selectable owner and referenced target. */
	@Nonnull
	private static EntityBuilder product(
		@Nonnull EvitaSessionContract session, int id, int referenceId
	) {
		return session.createNewEntity("Product", id)
			.setAttribute("code", raw(id)).setAttribute("sortOnly", raw(id))
			.setAttribute("search", " ǧ-ᴀʀALPHA-" + id + " ")
			.setAttribute("tags", new String[]{" ONE ", "one", " TWO "})
			.setAttribute("owner", TURKISH, "ÍTITLE" + id)
			.setAttribute("within", TURKISH, "ÍTITLE" + id)
			.setAttribute("global", TURKISH, "ÍTITLE" + id)
			.setAttribute("globalArray", new String[]{raw(id), "GAR" + id})
			.setAttribute("ownerArray", TURKISH, new String[]{" ÍTITLE-" + id + " ", "ititle" + id})
			.setAttribute("globalLocale", TURKISH, "ÍTITLE" + id)
			.setAttribute("rootElement", "ÍTITLE" + id)
			.setAttribute("localElement", TURKISH, "ÍTITLE" + id)
			.setReference("external", referenceId, r -> r.setAttribute("code", raw(id)))
			.setReference("virtual", referenceId, r -> r.setAttribute("code", raw(referenceId))
				.setAttribute("tags", new String[]{" ONE ", "one"})
				.setAttribute("rootElement", "ÍTITLE-" + id)
				.setAttribute("localElement", TURKISH, "ÍTITLE-" + id)
				.setAttribute("uniqueTitle", TURKISH, "ÍTITLE" + id))
			.setReference("real", referenceId, r -> r.setGroup("Category", 1).setAttribute("code", raw(id))
				.setAttribute("tags", new String[]{" ONE ", "one"})
				.setAttribute("rootElement", "ÍTITLE-" + id)
				.setAttribute("localElement", TURKISH, "ÍTITLE-" + id)
				.setAttribute("uniqueTitle", TURKISH, "ÍTITLE" + id));
	}

	/** Defines reference key producers shared by real and virtual partitions. */
	@Nonnull
	private static <T extends ReferenceSchemaEditor<T>> T reference(@Nonnull T reference) {
		return reference.withAttribute("code", String.class, a -> a.filterable().sortable().normalized(
			lowercase(), asciiFolding(), trim(), removeCharacters("-")))
			.withAttribute("tags", String[].class, a -> a.filterable().normalized(lowercase(), trim()))
			.withAttribute("uniqueTitle", String.class, a -> a.localized().sortable().unique().nullable().normalized(asciiFolding(), lowercase()))
			.withAttribute("rootElement", String.class, a -> a.normalized(asciiFolding(), lowercase(), removeCharacters("-")))
			.withAttribute("localElement", String.class,
				a -> a.localized().normalized(asciiFolding(), lowercase(), removeCharacters("-")))
			.withSortableAttributeCompound("compound", element("rootElement"), element("localElement"));
	}

	/** Inspects canonical keys rather than query results. */
	private void assertKeys(int count) {
		final Catalog catalog = catalog();
		final EntityIndex index = productIndex();
		assertEquals(count > 1, index.getFilterIndex(new AttributeIndexKey(null, "code", null))
			.getInvertedIndex().currentLeafPageSequences().length > 0);
		assertEquals(count > 1, index.getSortIndex(new AttributeIndexKey(null, "sortOnly", null))
			.currentLeafPageSequences().length > 0);
		for (int i = 1; i <= count; i++) {
			final String expected = "GAR" + i;
			assertEquals(1, index.getFilterIndex(new AttributeIndexKey(null, "code", null))
				.getInvertedIndex().getRecordsEqualTo(expected).size());
			assertEquals(expected, index.getSortIndex(new AttributeIndexKey(null, "sortOnly", null))
				.getValueOfRecord(i).orElseThrow());
			assertEquals(i, index.getUniqueIndex(new AttributeIndexKey(null, "owner", null))
				.getRecordIdByUniqueValue("ÍTITLE" + i));
			assertEquals(i, index.getUniqueIndex(new AttributeIndexKey(null, "ownerArray", null))
				.getRecordIdByUniqueValue("ÍTITLE" + i));
			assertEquals("ıtıtle" + i, index.getSortIndex(new AttributeIndexKey(null, "within", TURKISH))
				.getValueOfRecord(i).orElseThrow());
			assertEquals(i, index.getUniqueIndex(new AttributeIndexKey(null, "within", TURKISH))
				.getRecordIdByUniqueValue("ÍTITLE" + i));
			assertEquals(1, index.getFilterIndex(new AttributeIndexKey(null, "owner", TURKISH))
				.getInvertedIndex().getRecordsEqualTo("ıtıtle" + i).size());
			assertEquals(i, catalog.getCatalogIndex(Scope.LIVE).getGlobalUniqueIndex(
				catalog.getSchema().getAttribute("global").orElseThrow(), null)
				.getEntityReferenceByUniqueValue("ÍTITLE" + i, null, catalog).orElseThrow().getPrimaryKey());
			assertEquals(i, catalog.getCatalogIndex(Scope.LIVE).getGlobalUniqueIndex(
				catalog.getSchema().getAttribute("globalArray").orElseThrow(), null)
				.getEntityReferenceByUniqueValue(raw(i), null, catalog).orElseThrow().getPrimaryKey());
			assertEquals(i, catalog.getCatalogIndex(Scope.LIVE).getGlobalUniqueIndex(
				catalog.getSchema().getAttribute("globalLocale").orElseThrow(), TURKISH)
				.getEntityReferenceByUniqueValue("ÍTITLE" + i, TURKISH, catalog).orElseThrow().getPrimaryKey());
		}
		assertEquals(count - 1, index.getFilterIndex(new AttributeIndexKey(null, "code", null))
			.getRecordsGreaterThanFormula(raw(1)).compute().size());
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			for (final String attribute : List.of("code", "globalArray")) {
				assertEquals(List.of(1), session.queryListOfEntityReferences(query(collection("Product"),
					filterBy(attributeEquals(attribute, raw(1)))))
					.stream().map(it -> it.getPrimaryKey()).toList());
			}
			for (final String attribute : List.of("owner", "within", "global", "globalLocale")) {
				assertEquals(List.of(1), session.queryListOfEntityReferences(query(collection("Product"),
					filterBy(entityLocaleEquals(TURKISH), attributeEquals(attribute, "ÍTITLE1"))))
					.stream().map(it -> it.getPrimaryKey()).toList());
				assertEquals(List.of(1), session.queryListOfEntityReferences(query(collection("Product"),
					filterBy(entityLocaleEquals(TURKISH), attributeInSet(attribute, "ÍTITLE1"))))
					.stream().map(it -> it.getPrimaryKey()).toList());
			}
		});

		assertFalse(index.getFilterIndex(new AttributeIndexKey(null, "search", null))
			.getInvertedIndex().getRecordsEqualTo("GARalpha1").isEmpty());
		final TrigramIndex trigram = ((GlobalEntityIndex) index)
			.getTrigramIndex(new AttributeIndexKey(null, "search", null));
		assertNotNull(trigram);
		assertEquals(count, trigram.cardinalityOf(
			TrigramCodec.extractUniqueTrigrams("lph")[0]));
		final ComparableArray compound = (ComparableArray) index
			.getSortIndex(new AttributeIndexKey(null, "compound", TURKISH))
			.getValueOfRecord(1).orElseThrow();
		assertArrayEquals(new String[]{"ititle1", "ıtıtle1"}, compound.array());
		final EntityIndex reflectedType = catalog.getCollectionForEntityOrThrowException("Category")
			.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "products"));
		assertFalse(reflectedType.getFilterIndex(new AttributeIndexKey("products", "code", null))
			.getInvertedIndex().getRecordsEqualTo("GAR1").isEmpty());
		final EntityCollection collection = catalog.getCollectionForEntityOrThrowException("Product");
		for (final String reference : new String[]{"virtual", "real"}) {
			final ReferencedTypeEntityIndex type = (ReferencedTypeEntityIndex) collection.getIndexByKeyIfExists(
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, reference));
			final FilterIndex filter = type.getFilterIndex(new AttributeIndexKey(reference, "code", null));
			assertFalse(filter.getInvertedIndex().getRecordsEqualTo("GAR1").isEmpty());
			assertNotNull(type.getUniqueIndex(new AttributeIndexKey(reference, "uniqueTitle", null))
				.getRecordIdByUniqueValue("ititle1"));
			if (reference.equals("virtual")) {
				final AttributeIndexKey key = new AttributeIndexKey(reference, "code", null);
				assertEquals(count, type.getVirtualReferencePartitionIndex().getDistinctSortValueCount(key));
				final AttributeIndexKey compoundKey = new AttributeIndexKey(reference, "compound", TURKISH);
				type.getVirtualReferencePartitionIndex().forEachSortValueId(compoundKey, false, valueId -> {
					final ComparableArray value = (ComparableArray) type.getVirtualReferencePartitionIndex()
						.getNormalizedSortValue(compoundKey, valueId);
					assertTrue(((String) value.array()[0]).startsWith("ititle"));
					assertTrue(((String) value.array()[1]).startsWith("ıtıtle"));
					return true;
				});
				type.getVirtualReferencePartitionIndex().forEachSortValueId(key, false, valueId -> {
					assertTrue(((String) type.getVirtualReferencePartitionIndex().getNormalizedSortValue(key, valueId))
						.startsWith("GAR"));
					return true;
				});
			}
			type.forEachReferenceIndexPrimaryKey(partitionId -> {
				final ReferencePartition partition = collection.resolvePartitionIfExists(partitionId);
				if (partition instanceof EntityIndex materialized) {
					final FilterIndex partitionFilter = materialized.getFilterIndex(
						new AttributeIndexKey(reference, "code", null));
					assertNotNull(partitionFilter);
					assertFalse(partitionFilter.getAllRecordsFormula().compute().isEmpty());
				}
			});
		}
	}

	/** Returns the current snapshot's global product index. */
	@Nonnull
	private EntityIndex productIndex() {
		return catalog().getCollectionForEntityOrThrowException("Product")
			.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL));
	}

	/** Returns the current catalog snapshot. */
	@Nonnull
	private Catalog catalog() {
		return (Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
	}

	/** Restarts through the production catalog and component loaders. */
	private void restart() {
		this.evita.close();
		this.evita = new Evita(configuration());
		this.evita.waitUntilFullyInitialized();
	}

	/** Uses the standard isolated persistence fixture. */
	@Nonnull
	private EvitaConfiguration configuration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}

	/** Lucene folds these lowercase letters to uppercase ASCII, so a second pass would change the key. */
	@Nonnull
	private static String raw(int id) {
		return " ǧ-ᴀʀ" + id + " ";
	}

	/** Creates an ascending compound element. */
	@Nonnull
	private static AttributeElement element(@Nonnull String name) {
		return new AttributeElement(name, OrderDirection.ASC, OrderBehaviour.NULLS_LAST);
	}
}
