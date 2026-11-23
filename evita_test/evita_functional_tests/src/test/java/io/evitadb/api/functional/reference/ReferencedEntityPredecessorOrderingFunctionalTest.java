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

package io.evitadb.api.functional.reference;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.Page;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaRequest.ReferenceContentKey;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceContractSerializablePredicate;
import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceDecodeCoverage;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.virtual.CatalogReloadTestSupport;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises owner-local predecessor ordering in both reference fetch routes and during catalog lifecycle changes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Ordering an owner's references by referenced-entity predecessors")
@Tag(CONTRACT)
@Tag(ORDER)
@Tag(REFERENCE)
class ReferencedEntityPredecessorOrderingFunctionalTest implements EvitaTestSupport {
	private static final String CATALOG = "referenceListOrdering";
	private static final String OWNER = "owner";
	private static final String TARGET = "target";
	private static final String ITEMS = "items";
	private static final String MIRROR = "mirror";
	private static final String DUPLICATES = "duplicates";
	private static final String OWNERS = "owners";
	private static final String POSITION = "position";
	private static final String LABEL = "label";
	private static final Scope[] SCOPES = {Scope.LIVE, Scope.ARCHIVED};
	private static final int[] CHAIN = {2, 3, Predecessor.HEAD_PK};

	private TestPaths paths;
	private EvitaConfiguration configuration;
	private Evita evita;

	/**
	 * Opens a new catalog with a plain reference and a reflection of a separately defined forward reference.
	 */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths(CATALOG);
		this.configuration = newTestEvitaConfigurationBuilder(this.paths).build();
		this.evita = new Evita(this.configuration);
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(CATALOG, session -> {
			session.defineEntitySchema(TARGET).updateVia(session);
			session.defineEntitySchema(OWNER)
				.withReferenceToEntity(ITEMS, TARGET, Cardinality.ZERO_OR_MORE, ref -> ref
					.indexedForFilteringInScope(SCOPES)
					.withAttribute(POSITION, ReferencedEntityPredecessor.class, attr -> attr.sortableInScope(SCOPES))
					.withAttribute(LABEL, Integer.class, attr -> attr.sortableInScope(SCOPES)))
				.withReferenceToEntity(DUPLICATES, TARGET, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES, ref -> ref
					.indexedForFiltering()
					.withAttribute(LABEL, Integer.class, attr -> attr.filterable().representative())
					.withAttribute(POSITION, ReferencedEntityPredecessor.class, attr -> attr.nullable().sortable()))
				.updateVia(session);
			session.defineEntitySchema(TARGET)
				.withReferenceToEntity(OWNERS, OWNER, Cardinality.ZERO_OR_MORE, ref -> ref
					.indexedForFilteringInScope(SCOPES)
					.withAttribute(POSITION, Predecessor.class, attr -> attr.sortableInScope(SCOPES)))
				.updateVia(session);
			session.defineEntitySchema(OWNER)
				.withReflectedReferenceToEntity(MIRROR, TARGET, OWNERS, ref -> ref
					.withCardinality(Cardinality.ZERO_OR_MORE)
					.withAttributesInherited()
					.indexedForFilteringInScope(SCOPES))
				.updateVia(session);
		});
	}

	/**
	 * Closes the engine and removes the temporary storage.
	 */
	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Checks that a hidden middle link remains available to both reference sorting routes.
	 *
	 * @param direction requested order direction
	 */
	@ParameterizedTest
	@EnumSource(OrderDirection.class)
	@DisplayName("Should follow the chain across a hidden middle link before taking a page")
	void shouldOrderAcrossFilteredLink(@Nonnull OrderDirection direction) {
		this.evita.updateCatalog(CATALOG, session -> {
			writeTargets(session);
			writeOwner(session, 1, CHAIN);
			session.goLiveAndClose();
		});
		final List<Integer> expected = direction == OrderDirection.ASC ? List.of(3, 1) : List.of(1, 3);
		final ReferenceContent orderedChain = referenceContent(
			ITEMS, filterBy(entityPrimaryKeyInSet(1, 3)), orderBy(attributeNatural(POSITION, direction))
		);
		final ReferenceContent orderedLabels = referenceContent(
			ITEMS, filterBy(entityPrimaryKeyInSet(1, 3)), orderBy(attributeNatural(LABEL))
		);
		assertEquals(expected, referenceKeys(fetchOwner(Scope.LIVE, 1, orderedChain), ITEMS));
		final List<ReferenceContract> pageRows = List.copyOf(fetchOwner(Scope.LIVE, 1, referenceContent(
			ITEMS, filterBy(entityPrimaryKeyInSet(1, 3)), orderBy(attributeNatural(POSITION, direction)),
			entityFetch(), null, page(2, 1)
		)).getReferences(ITEMS));
		assertEquals(expected.getLast().intValue(), pageRows.getFirst().getReferencedPrimaryKey());
		assertEquals(1, pageRows.size());
		assertTrue(pageRows.getFirst().getReferencedEntity().isPresent());

		assertEquals(List.of(1, 3), referenceKeys(fetchOwner(Scope.LIVE, 1, orderedLabels), ITEMS));
	}

	/**
	 * Checks that the decode of a key-filtered reference keeps every row only when a referenced-entity predecessor
	 * orders it, because the hidden rows carry links of the chain.
	 */
	@Test
	@DisplayName("Should decode every row of a reference ordered by a referenced-entity predecessor")
	void shouldDecodeEveryRowOfAReferenceOrderedByPredecessor() {
		final ReferenceContent orderedChain = referenceContent(
			ITEMS, filterBy(entityPrimaryKeyInSet(1, 3)), orderBy(attributeNatural(POSITION))
		);
		final ReferenceContent orderedLabels = referenceContent(
			ITEMS, filterBy(entityPrimaryKeyInSet(1, 3)), orderBy(attributeNatural(LABEL))
		);
		final ReferenceContent otherReference = referenceContent(
			DUPLICATES, filterBy(entityPrimaryKeyInSet(1)), orderBy(attributeNatural(LABEL))
		);

		final ReferenceDecodeCoverage chainCoverage = decodeCoverage(orderedChain, otherReference);
		final ReferenceDecodeCoverage labelCoverage = decodeCoverage(orderedLabels);

		assertEquals(Set.of(ITEMS), chainCoverage.getNamesDecodedWhole());
		assertEquals(Set.of(DUPLICATES), chainCoverage.getNamesDecodedByKey().keySet());
		assertArrayEquals(new int[]{1}, chainCoverage.getNamesDecodedByKey().get(DUPLICATES).toArray());
		assertEquals(Set.of(ITEMS), labelCoverage.getNamesDecodedByKey().keySet());
		assertArrayEquals(new int[]{1, 3}, labelCoverage.getNamesDecodedByKey().get(ITEMS).toArray());
	}

	/**
	 * Checks that a live catalog's write transaction ranks the rows of a committed owner and of an owner it wrote
	 * itself, and that both keep their order once it commits.
	 */
	@Test
	@DisplayName("Should order the rows an open write transaction sees, including the ones it wrote itself")
	void shouldOrderRowsInsideWriteTransaction() {
		this.evita.updateCatalog(CATALOG, session -> {
			writeTargets(session);
			writeOwner(session, 1, CHAIN);
			session.goLiveAndClose();
		});
		final ReferenceContent contents = referenceContent(ITEMS, orderBy(attributeNatural(POSITION)));

		this.evita.updateCatalog(CATALOG, session -> {
			writeOwner(session, 2, new int[]{3, Predecessor.HEAD_PK, 2});
			final List<SealedEntity> owners = session.queryListOfSealedEntities(
				query(
					collection(OWNER), filterBy(entityPrimaryKeyInSet(1, 2)),
					orderBy(entityPrimaryKeyNatural(OrderDirection.ASC)), require(entityFetch(contents))
				)
			);
			assertEquals(List.of(3, 2, 1), referenceKeys(owners.getFirst(), ITEMS));
			assertEquals(List.of(2, 3, 1), referenceKeys(owners.getLast(), ITEMS));
		});

		assertEquals(List.of(3, 2, 1), referenceKeys(fetchOwner(Scope.LIVE, 1, contents), ITEMS));
		assertEquals(List.of(2, 3, 1), referenceKeys(fetchOwner(Scope.LIVE, 2, contents), ITEMS));
	}

	/**
	 * Checks that all unranked rows reach the fallback and that paging fetches the bodies of those same rows.
	 *
	 * @param named whether to fetch a named reference view
	 * @param paged whether to take a one-row page from the fallback ordering
	 */
	@ParameterizedTest(name = "Named = {0}, paged = {1}")
	@CsvSource({"false,false", "true,false", "false,true", "true,true"})
	@DisplayName("Should order unranked rows by the fallback and fetch bodies for the same page")
	void shouldOrderUnrankedRowsAndFetchTheirBodies(boolean named, boolean paged) {
		this.evita.updateCatalog(CATALOG, session -> {
			writeTargets(session);
			session.createNewEntity(TARGET, 4).upsertVia(session);
			final EntityBuilder owner = session.createNewEntity(OWNER, 1);
			owner.setOrUpdateReference(DUPLICATES, 1, Functions.alwaysFalse(), ref -> ref.setAttribute(LABEL, 10));
			owner.setOrUpdateReference(DUPLICATES, 1, Functions.alwaysFalse(), ref -> ref.setAttribute(LABEL, 11));
			owner.setOrUpdateReference(DUPLICATES, 2, Functions.alwaysFalse(), ref -> ref.setAttribute(LABEL, 20));
			owner.setOrUpdateReference(DUPLICATES, 3, Functions.alwaysFalse(), ref -> ref
				.setAttribute(LABEL, 30)
				.setAttribute(POSITION, new ReferencedEntityPredecessor(Predecessor.HEAD_PK)));
			owner.setOrUpdateReference(DUPLICATES, 4, Functions.alwaysFalse(), ref -> ref
				.setAttribute(LABEL, 40)
				.setAttribute(POSITION, new ReferencedEntityPredecessor(3)));
			owner.upsertVia(session);
			session.goLiveAndClose();
		});
		final String alias = "ordered";
		final OrderBy ordering = orderBy(attributeNatural(POSITION), entityPrimaryKeyNatural(OrderDirection.DESC));
		final Page page = paged ? page(4, 1) : null;
		final ReferenceContent content = named ?
			new ReferenceContent(
				alias, ManagedReferencesBehaviour.ANY, new String[]{DUPLICATES},
				paged ? new RequireConstraint[]{entityFetch(), page} : new RequireConstraint[]{entityFetch()},
				new Constraint<?>[]{ordering}
			) :
			referenceContent(DUPLICATES, ordering, entityFetch(), page);
		final SealedEntity owner = this.fetchOwner(Scope.LIVE, 1, content);
		final List<ReferenceContract> rows = named ?
			((ServerEntityDecorator) owner).getReferencesForReferenceContentInstance(
				new ReferenceContentKey(alias, DUPLICATES)
			).orElseThrow().getData() :
			List.copyOf(owner.getReferences(DUPLICATES));
		assertEquals(
			paged ? List.of(1) : List.of(3, 4, 2, 1, 1),
			rows.stream().map(ReferenceContract::getReferencedPrimaryKey).toList()
		);
		if (paged) {
			for (final ReferenceContract row : rows) {
				final SealedEntity body = row.getReferencedEntity().orElseThrow(
					() -> new AssertionError("Missing body for referenced entity " + row.getReferencedPrimaryKey())
				);
				assertEquals(row.getReferencedPrimaryKey(), body.getPrimaryKeyOrThrowException());
			}
		}
	}

	/**
	 * Checks that a live-only predecessor ordering sends archived owners' rows to primary-key fallback.
	 */
	@Test
	@DisplayName("Should use fallback ordering for archived owners outside the live ordering scope")
	void shouldUseFallbackForOwnersOutsideOrderingScope() {
		this.evita.updateCatalog(CATALOG, session -> {
			writeTargets(session);
			writeOwner(session, 1, CHAIN);
			writeOwner(session, 2, CHAIN);
			session.goLiveAndClose();
		});
		this.evita.updateCatalog(CATALOG, session -> {
			assertTrue(session.archiveEntity(OWNER, 2));
		});
		final ReferenceContent content = referenceContent(
			ITEMS, orderBy(inScope(Scope.LIVE, attributeNatural(POSITION)))
		);
		final List<SealedEntity> owners = this.evita.queryCatalog(
			CATALOG, (EvitaSessionContract session) -> session.queryListOfSealedEntities(
				query(
					collection(OWNER), filterBy(scope(SCOPES)), orderBy(entityPrimaryKeyNatural(OrderDirection.ASC)),
					require(entityFetch(content))
				)
			)
		);
		assertEquals(2, owners.size());
		assertEquals(Scope.LIVE, owners.getFirst().getScope());
		assertEquals(List.of(3, 2, 1), referenceKeys(owners.getFirst(), ITEMS));
		assertEquals(Scope.ARCHIVED, owners.getLast().getScope());
		assertEquals(List.of(1, 2, 3), referenceKeys(owners.getLast(), ITEMS));
	}

	/**
	 * Checks that enriching an older entity decodes all links after a sortable predecessor is added to its schema.
	 */
	@Test
	@DisplayName("Should follow a hidden link when enriching an entity fetched before the chain attribute was added")
	void shouldOrderAcrossFilteredLinkWhenEnrichingAgainstNewSchema() {
		final String chainPosition = "chainPosition";
		this.evita.updateCatalog(CATALOG, session -> {
			final EntityBuilder owner = session.createNewEntity(OWNER, 1);
			for (final int key : List.of(2, 3, 4, 5, 6)) {
				session.createNewEntity(TARGET, key).upsertVia(session);
				owner.setReference(ITEMS, key, ref -> ref
					.setAttribute(POSITION, new ReferencedEntityPredecessor(Predecessor.HEAD_PK))
					.setAttribute(LABEL, key));
			}
			owner.upsertVia(session);
			session.goLiveAndClose();
		});
		final ReferenceContent ordinaryOrder = referenceContent(
			ITEMS, filterBy(entityPrimaryKeyInSet(2, 4, 5, 6)), orderBy(entityPrimaryKeyNatural(OrderDirection.ASC))
		);
		final EntityDecorator retained = (EntityDecorator) fetchOwner(Scope.LIVE, 1, ordinaryOrder);
		assertEquals(List.of(2, 4, 5, 6), referenceKeys(retained, ITEMS));
		final ReferenceDecodeCoverage retainedCoverage = retained.getReferencePredicate().getDecodeCoverage();
		assertNotNull(retainedCoverage);
		assertArrayEquals(
			new int[]{2, 4, 5, 6}, retainedCoverage.getNamesDecodedByKey().get(ITEMS).toArray()
		);

		this.evita.updateCatalog(CATALOG, session -> {
			session.defineEntitySchema(OWNER)
				.withReferenceToEntity(ITEMS, TARGET, Cardinality.ZERO_OR_MORE, ref -> ref
					.withAttribute(
						chainPosition, ReferencedEntityPredecessor.class, attr -> attr.nullable().sortable()
					))
				.updateVia(session);
			final int[] predecessors = {3, 4, Predecessor.HEAD_PK, 6, Predecessor.HEAD_PK};
			final EntityBuilder owner = session.getEntity(OWNER, 1, referenceContentWithAttributes(ITEMS))
				.orElseThrow().openForWrite();
			for (final int key : List.of(2, 3, 4, 5, 6)) {
				owner.updateReference(ITEMS, key, ref -> ref.setAttribute(
					chainPosition, new ReferencedEntityPredecessor(predecessors[key - 2])
				));
			}
			owner.upsertVia(session);
		});
		final ReferenceContent chainOrder = referenceContent(
			ITEMS, filterBy(entityPrimaryKeyInSet(2, 4, 5, 6)), orderBy(attributeNatural(chainPosition))
		);
		final SealedEntity enriched = this.evita.queryCatalog(
			CATALOG, (EvitaSessionContract session) -> session.enrichEntity(retained, chainOrder)
		);
		assertEquals(List.of(4, 2, 6, 5), referenceKeys(enriched, ITEMS));
	}

	/**
	 * Checks that representative values keep their stored order within each target's chain position.
	 *
	 * @param direction requested order direction
	 */
	@ParameterizedTest
	@EnumSource(OrderDirection.class)
	@DisplayName("Should keep duplicate rows together and stable in both directions")
	void shouldKeepDuplicateRowsInTheirStoredOrder(@Nonnull OrderDirection direction) {
		this.evita.updateCatalog(CATALOG, session -> {
			writeTargets(session);
			final EntityBuilder owner = session.createNewEntity(OWNER, 1);
			for (final int key : List.of(1, 2, 3)) {
				for (final int label : List.of(key * 10 + 2, key * 10 + 1)) {
					owner.setOrUpdateReference(DUPLICATES, key, Functions.alwaysFalse(), ref -> ref
						.setAttribute(LABEL, label)
						.setAttribute(POSITION, new ReferencedEntityPredecessor(CHAIN[key - 1])));
				}
			}
			owner.upsertVia(session);
			session.goLiveAndClose();
		});
		final List<ReferenceContract> stored = List.copyOf(fetchOwner(
			Scope.LIVE, 1, referenceContentWithAttributes(DUPLICATES)
		).getReferences(DUPLICATES));
		final List<Integer> targetOrder = direction == OrderDirection.ASC ? List.of(3, 2, 1) : List.of(1, 2, 3);
		final List<Integer> expected = targetOrder.stream()
			.flatMap(key -> stored.stream().filter(row -> row.getReferencedPrimaryKey() == key))
			.map(row -> row.<Integer>getAttribute(LABEL))
			.toList();
		final SealedEntity ordered = fetchOwner(
			Scope.LIVE, 1, referenceContentWithAttributes(DUPLICATES, orderBy(attributeNatural(POSITION, direction)))
		);
		assertEquals(6, expected.size());
		assertEquals(
			expected, ordered.getReferences(DUPLICATES).stream().map(row -> row.<Integer>getAttribute(LABEL)).toList()
		);
	}

	/**
	 * Compares an inconsistent plain list to one built by the indexed forward predecessor.
	 *
	 * @param first predecessor of target 1
	 * @param second predecessor of target 2
	 * @param third predecessor of target 3
	 */
	@ParameterizedTest
	@CsvSource({"2,-1,-1", "2,3,1", "2,99,-1"})
	@DisplayName("Should match reflected ordering for multiple heads, a cycle and a missing predecessor")
	void shouldMatchReflectedOrderingForInconsistentChain(int first, int second, int third) {
		final int[] chain = {first, second, third};
		this.evita.updateCatalog(CATALOG, session -> {
			writeOwner(session, 1, chain);
			for (final int key : List.of(1, 2, 3)) {
				session.createNewEntity(TARGET, key)
					.setReference(OWNERS, 1, ref -> ref.setAttribute(POSITION, new Predecessor(chain[key - 1])))
					.upsertVia(session);
			}
			session.createNewEntity(TARGET, 99).upsertVia(session);
			session.goLiveAndClose();
		});
		for (final OrderDirection direction : OrderDirection.values()) {
			final SealedEntity owner = fetchOwner(
				Scope.LIVE, 1,
				referenceContent(ITEMS, orderBy(attributeNatural(POSITION, direction))),
				referenceContent(MIRROR, orderBy(attributeNatural(POSITION, direction)))
			);
			assertEquals(3, referenceKeys(owner, MIRROR).size());
			assertEquals(referenceKeys(owner, MIRROR), referenceKeys(owner, ITEMS), direction.name());
		}
	}

	/**
	 * Checks local predecessor ordering and paging when reflected rows share an internal primary key.
	 */
	@Test
	@DisplayName("Should rank reflected rows independently when they share an internal primary key")
	void shouldRankReflectedRowsWithSharedInternalPrimaryKey() {
		final String brand = "brand";
		final String product = "product";
		final String products = "products";
		this.evita.updateCatalog(CATALOG, session -> {
			session.defineEntitySchema(brand).updateVia(session);
			session.defineEntitySchema(product)
				.withReferenceToEntity(brand, brand, Cardinality.ZERO_OR_MORE, ref -> ref.indexedForFiltering())
				.updateVia(session);
			session.defineEntitySchema(brand)
				.withReflectedReferenceToEntity(products, product, brand, ref -> ref
					.withCardinality(Cardinality.ZERO_OR_MORE)
					.indexedForFiltering()
					.withAttribute(POSITION, ReferencedEntityPredecessor.class, attr -> attr.nullable().sortable()))
				.updateVia(session);
			session.createNewEntity(brand, 1).upsertVia(session);
			for (final int key : List.of(9, 10, 11, 12, 13)) {
				session.createNewEntity(product, key).setReference(brand, 1).upsertVia(session);
			}
			final EntityBuilder owner = session.getEntity(brand, 1, referenceContentWithAttributes(products))
				.orElseThrow().openForWrite();
			owner.updateReference(products, 11, ref -> ref.setAttribute(POSITION, new ReferencedEntityPredecessor(12)));
			owner.updateReference(products, 12, ref -> ref.setAttribute(POSITION, new ReferencedEntityPredecessor(13)));
			owner.updateReference(products, 13, ref -> ref.setAttribute(
				POSITION, new ReferencedEntityPredecessor(Predecessor.HEAD_PK)
			));
			owner.upsertVia(session);
			session.goLiveAndClose();
		});
		this.evita.queryCatalog(CATALOG, session -> {
			final SealedEntity owner = session.getEntity(brand, 1, referenceContentWithAttributes(products))
				.orElseThrow();
			assertEquals(List.of(9, 10, 11, 12, 13), referenceKeys(owner, products));
			assertEquals(
				List.of(1, 1, 1, 1, 1),
				owner.getReferences(products).stream().map(row -> row.getReferenceKey().internalPrimaryKey()).toList(),
				"Reflected rows must share an internal primary key"
			);
			assertNull(owner.getReference(products, 9).orElseThrow().getAttribute(POSITION));
			assertNull(owner.getReference(products, 10).orElseThrow().getAttribute(POSITION));

			final OrderBy ascending = orderBy(attributeNatural(POSITION, OrderDirection.ASC));
			final OrderBy descending = orderBy(attributeNatural(POSITION, OrderDirection.DESC));
			final OrderBy withFallback = orderBy(
				attributeNatural(POSITION, OrderDirection.ASC), entityPrimaryKeyNatural(OrderDirection.DESC)
			);
			assertAll(
				() -> assertEquals(
					List.of(13, 12, 11, 9, 10),
					referenceKeys(
						session.getEntity(brand, 1, referenceContent(products, ascending)).orElseThrow(), products
					),
					"Ascending chain with default fallback"
				),
				() -> assertEquals(
					List.of(11, 12, 13, 9, 10),
					referenceKeys(
						session.getEntity(brand, 1, referenceContent(products, descending)).orElseThrow(), products
					),
					"Descending chain with default fallback"
				),
				() -> assertEquals(
					List.of(13, 12, 11, 10, 9),
					referenceKeys(
						session.getEntity(brand, 1, referenceContent(products, withFallback)).orElseThrow(), products
					),
					"Ascending chain with explicit fallback"
				),
				() -> {
					final SealedEntity pagedOwner = session.getEntity(
						brand, 1, referenceContent(products, ascending, entityFetch(), page(1, 1))
					).orElseThrow();
					assertEquals(List.of(13), referenceKeys(pagedOwner, products));
					assertEquals(13, pagedOwner.getReference(products, 13).orElseThrow().getReferencedEntity()
						.orElseThrow().getPrimaryKeyOrThrowException());
				}
			);
		});
	}

	/**
	 * Covers empty collections, owners without rows, and populated lists in either scope.
	 *
	 * @param scope queried scope
	 * @param population zero for no owners, one for one owner without rows, or two for a populated reference
	 */
	@ParameterizedTest
	@CsvSource({"LIVE,0", "LIVE,1", "LIVE,2", "ARCHIVED,0", "ARCHIVED,1", "ARCHIVED,2"})
	@DisplayName("Should refuse owner ordering and allow reference fetch ordering independently of data")
	void shouldValidateOrderingFromSchema(@Nonnull Scope scope, int population) {
		this.evita.updateCatalog(CATALOG, session -> {
			writeTargets(session);
			if (population == 1) {
				session.createNewEntity(OWNER, 1).upsertVia(session);
			} else if (population == 2) {
				writeOwner(session, 1, CHAIN);
			}
			if (population > 0 && scope == Scope.ARCHIVED) {
				assertTrue(session.archiveEntity(OWNER, 1));
			}
			session.goLiveAndClose();
		});
		for (final DebugMode route : List.of(DebugMode.PREFER_INDEX_SCAN, DebugMode.PREFER_PREFETCHING)) {
			final List<SealedEntity> owners = this.evita.queryCatalog(
				CATALOG, (EvitaSessionContract session) -> session.queryListOfSealedEntities(
					query(collection(OWNER), filterBy(scope(scope)), require(
						entityFetch(referenceContent(ITEMS, orderBy(attributeNatural(POSITION)))), debug(route)
					))
				)
			);
			assertEquals(population == 0 ? 0 : 1, owners.size());
			if (!owners.isEmpty()) {
				assertEquals(population == 2 ? List.of(3, 2, 1) : List.of(), referenceKeys(owners.getFirst(), ITEMS));
			}
			for (final OrderConstraint ordering : List.of(
				referenceProperty(
					ITEMS, traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
					attributeNatural(POSITION)
				),
				referenceProperty(ITEMS, attributeNatural(POSITION)),
				referenceProperty(
					ITEMS, pickFirstByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
					attributeNatural(POSITION)
				)
			)) {
				final EvitaInvalidUsageException error = assertThrows(
					EvitaInvalidUsageException.class,
					() -> this.evita.queryCatalog(CATALOG, session -> {
						session.queryListOfSealedEntities(query(
							collection(OWNER), filterBy(scope(scope)), orderBy(ordering),
							require(entityFetch(), debug(route))
						));
					}),
					route + " " + ordering
				);
				assertTrue(error.getMessage().contains(POSITION), error::getMessage);
				assertTrue(error.getMessage().contains("own list of referenced entities"), error::getMessage);
				assertTrue(error.getMessage().contains("cannot order the owners"), error::getMessage);
				assertTrue(error.getMessage().contains("referenceProperty"), error::getMessage);
			}
		}
	}

	/**
	 * Moves and removes owners sharing every target and repeats an archive-scope ordering after restart. A third
	 * owner, when present, is never touched after its restore and must keep its whole list.
	 *
	 * @param goLive whether the writes use a transactional catalog
	 * @param ownerCount number of owners sharing each target
	 */
	@ParameterizedTest
	@CsvSource({"false,2", "false,3", "true,2", "true,3"})
	@DisplayName("Should archive, restore, delete and unlink shared rows, then reload the chain")
	void shouldKeepSharedOwnerListsAcrossMutationsAndReload(boolean goLive, int ownerCount) {
		this.evita.updateCatalog(CATALOG, session -> {
			writeTargets(session);
			for (final int owner : IntStream.rangeClosed(1, ownerCount).toArray()) {
				writeOwner(session, owner, CHAIN);
			}
			if (goLive) {
				session.goLiveAndClose();
			}
		});
		this.evita.updateCatalog(CATALOG, session -> {
			for (final int owner : IntStream.rangeClosed(1, ownerCount).toArray()) {
				assertTrue(session.archiveEntity(OWNER, owner));
			}
		});
		this.evita.updateCatalog(CATALOG, session -> {
			for (final int owner : IntStream.rangeClosed(1, ownerCount).toArray()) {
				assertTrue(session.restoreEntity(OWNER, owner));
			}
		});
		this.evita.updateCatalog(CATALOG, session -> {
			session.getEntity(OWNER, 2, referenceContentWithAttributes(ITEMS)).orElseThrow().openForWrite()
				.removeReference(ITEMS, 1)
				.upsertVia(session);
			assertTrue(session.deleteEntity(OWNER, 1));
			assertTrue(session.archiveEntity(OWNER, 2));
		});
		final ReferenceContent contents = referenceContent(ITEMS, orderBy(attributeNatural(POSITION)));
		assertEquals(List.of(3, 2), referenceKeys(fetchOwner(Scope.ARCHIVED, 2, contents), ITEMS));
		if (ownerCount == 3) {
			assertEquals(List.of(3, 2, 1), referenceKeys(fetchOwner(Scope.LIVE, 3, contents), ITEMS));
		}
		this.evita.close();
		this.evita = new Evita(this.configuration);
		CatalogReloadTestSupport.awaitCatalogLoaded(this.evita, CATALOG);
		assertEquals(List.of(3, 2), referenceKeys(fetchOwner(Scope.ARCHIVED, 2, contents), ITEMS));
		if (ownerCount == 3) {
			assertEquals(List.of(3, 2, 1), referenceKeys(fetchOwner(Scope.LIVE, 3, contents), ITEMS));
		}
	}

	/**
	 * Writes the targets of the three-element list.
	 *
	 * @param session catalog writer
	 */
	private static void writeTargets(@Nonnull EvitaSessionContract session) {
		for (final int key : List.of(1, 2, 3)) {
			session.createNewEntity(TARGET, key).upsertVia(session);
		}
	}

	/**
	 * Writes one list keyed by the three target primary keys.
	 *
	 * @param session catalog writer
	 * @param primaryKey owner primary key
	 * @param predecessors predecessor of each target in primary key order
	 */
	private static void writeOwner(@Nonnull EvitaSessionContract session, int primaryKey, @Nonnull int[] predecessors) {
		final EntityBuilder owner = session.createNewEntity(OWNER, primaryKey);
		for (final int key : List.of(1, 2, 3)) {
			owner.setReference(ITEMS, key, ref -> ref
				.setAttribute(POSITION, new ReferencedEntityPredecessor(predecessors[key - 1]))
				.setAttribute(LABEL, key));
		}
		owner.upsertVia(session);
	}

	/**
	 * Fetches one owner with the requested reference contents.
	 *
	 * @param scope owner scope
	 * @param primaryKey owner primary key
	 * @param contents reference fetch requirements
	 * @return fetched owner
	 */
	@Nonnull
	private SealedEntity fetchOwner(@Nonnull Scope scope, int primaryKey, @Nonnull ReferenceContent... contents) {
		return this.evita.queryCatalog(CATALOG, (EvitaSessionContract session) -> session.queryListOfSealedEntities(
			query(collection(OWNER), filterBy(scope(scope), entityPrimaryKeyInSet(primaryKey)),
				require(entityFetch(contents)))
		)).getFirst();
	}

	/**
	 * Returns the target keys in their fetched order.
	 *
	 * @param owner fetched entity
	 * @param referenceName requested reference name
	 * @return ordered target keys
	 */
	@Nonnull
	private static List<Integer> referenceKeys(@Nonnull SealedEntity owner, @Nonnull String referenceName) {
		return owner.getReferences(referenceName).stream().map(ReferenceContract::getReferencedPrimaryKey).toList();
	}

	/**
	 * Builds the storage-facing predicate with the same request shape and schema used by the fetch.
	 *
	 * @param contents reference fetch requirements
	 * @return decode coverage
	 */
	@Nonnull
	private ReferenceDecodeCoverage decodeCoverage(@Nonnull ReferenceContent... contents) {
		final Query query = query(collection(OWNER), require(entityFetch(contents)));
		final EvitaRequest request = new EvitaRequest(query, OffsetDateTime.now(), SealedEntity.class, null);
		final ReferenceDecodeCoverage coverage = this.evita.queryCatalog(
			CATALOG, (EvitaSessionContract session) ->
				new ReferenceContractSerializablePredicate(request, session.getEntitySchema(OWNER).orElseThrow())
					.getDecodeCoverage()
		);
		assertNotNull(coverage);
		return coverage;
	}
}
