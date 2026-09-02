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

package io.evitadb.api.functional.fetch;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReferenceWithParent;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContent;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.dataInLocales;
import static io.evitadb.api.query.QueryConstraints.distance;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.hierarchyContent;
import static io.evitadb.api.query.QueryConstraints.hierarchyWithinRootSelf;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.stopAt;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterisation tests for the parent chain returned by `hierarchyContent` - see issue #1365
 * (https://github.com/FgForrest/evitaDB/issues/1365).
 *
 * This class pins the behaviour **measured on 2026-09-02**, i.e. the `today` column of the behaviour
 * matrix in `documentation/adr/2026-08-03-hierarchy-content-parents-behaviour.md`. It asserts what
 * the engine does right now, defects included - it does **not** assert what it ought to do. Several
 * of the pinned rows are the very defects #1365 reports, and two of them (a deleted mid-chain
 * ancestor two levels up, and a never-created ancestor two levels up) pin a thrown exception as the
 * current outcome.
 *
 * Each later phase of #1365 flips exactly the rows it changes over to the target columns (`COMPLETE`
 * / `MATCHING`) of that same matrix. A row that is not flipped by a phase must keep passing
 * unchanged, which is what makes this class the backward-compatibility guard for the whole line of
 * work. Every test method carries the matrix row identifier it pins in its name and in its display
 * name, so the diff of a later phase reads row by row.
 *
 * Assertions deliberately run against the raw {@link SealedEntity} API rather than through typed
 * proxy interfaces: `ProxyUtils#createOptionalWrapper` picks a swallowing wrapper for a plain
 * {@link Optional} return type, which would report "never requested" and "cannot be materialized"
 * identically and let a broken implementation pass.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Evita hierarchyContent parent chain - behaviour measured on 2026-09-02")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(HIERARCHY)
class HierarchyContentParentsBehaviourFunctionalTest {
	/**
	 * Name of the shared data set holding the whole behaviour matrix fixture.
	 */
	private static final String DATA_SET = "hierarchyContentParentsBehaviour";
	/**
	 * Czech locale - the locale a node holds data in when it must be unmaterializable under the
	 * English query locale.
	 */
	private static final Locale LOCALE_CZECH = new Locale("cs");
	/**
	 * Rendering of a chain that ends, i.e. whose last element reports an empty
	 * {@link EntityClassifierWithParent#getParentEntity()}.
	 */
	private static final String CHAIN_END = "(end)";

	/**
	 * Builds the whole behaviour-matrix fixture in a single shared catalog.
	 *
	 * The `CATEGORY` collection is hierarchical and localized in `cs` and `en`, carries a localized
	 * nullable `name` and a global nullable `code`. A node created with English data is
	 * materializable under the English query locale; a node created with Czech data only is not,
	 * while still holding a readable global `code`. The `BRAND` collection is hierarchical and
	 * declares no locale at all.
	 *
	 * Primary keys are grouped by the matrix row they serve: `1-3` the fully materializable control,
	 * `11-12` row P1, `21-23` row P2, `31-34` row P3, `41-44` row P4, `51-54` row P5, `61-63` row P6,
	 * `71-72` row K1, `81-83` row K2, `91-94` the deeper deleted-root probe, `111-112` row K6,
	 * `121-125` rows K3/K4/K5 and `131-134` the Czech-only mid-chain deletion.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 * @return an empty data carrier - the fixture is addressed by primary key, not by shared objects
	 */
	@DataSet(DATA_SET)
	DataCarrier setUp(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withHierarchy()
					.withLocale(LOCALE_CZECH, Locale.ENGLISH)
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().nullable())
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::nullable)
					.updateAndFetchVia(session);

				// the brand schema declares no locale whatsoever - a query-level locale filter therefore
				// matches nothing in this collection at all
				session.defineEntitySchema(Entities.BRAND)
					.withoutGeneratedPrimaryKey()
					.withHierarchy()
					.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> {})
					.updateAndFetchVia(session);

				// control - every node materializable under the English query locale
				createEnglishCategory(session, 1, null);
				createEnglishCategory(session, 2, 1);
				createEnglishCategory(session, 3, 2);

				// P1 - the immediate parent holds Czech data only
				createCzechCategory(session, 11, null);
				createEnglishCategory(session, 12, 11);

				// P2 - the root holds Czech data only
				createCzechCategory(session, 21, null);
				createEnglishCategory(session, 22, 21);
				createEnglishCategory(session, 23, 22);

				// P3 - a Czech-only node in the middle with a materializable root above it
				createEnglishCategory(session, 31, null);
				createCzechCategory(session, 32, 31);
				createEnglishCategory(session, 33, 32);
				createEnglishCategory(session, 34, 33);

				// P4 - two adjacent Czech-only ancestors with a materializable root above them
				createEnglishCategory(session, 41, null);
				createCzechCategory(session, 42, 41);
				createCzechCategory(session, 43, 42);
				createEnglishCategory(session, 44, 43);

				// P5 - Czech-only, materializable, Czech-only, interleaved up to the root
				createCzechCategory(session, 51, null);
				createEnglishCategory(session, 52, 51);
				createCzechCategory(session, 53, 52);
				createEnglishCategory(session, 54, 53);

				// P6 - two Czech-only ancestors all the way up to the root
				createCzechCategory(session, 61, null);
				createCzechCategory(session, 62, 61);
				createEnglishCategory(session, 63, 62);

				// K1 - the immediate parent is a root that gets deleted below
				createEnglishCategory(session, 71, null);
				createEnglishCategory(session, 72, 71);

				// K2 - a deleted root two levels up
				createEnglishCategory(session, 81, null);
				createEnglishCategory(session, 82, 81);
				createEnglishCategory(session, 83, 82);

				// deeper deleted-root probe, feeding the phantom-root assertion
				createEnglishCategory(session, 91, null);
				createEnglishCategory(session, 92, 91);
				createEnglishCategory(session, 93, 92);
				createEnglishCategory(session, 94, 93);

				// K6 - a parent primary key that was never created; the upsert is accepted all the same
				createEnglishCategory(session, 111, 999);
				createEnglishCategory(session, 112, 111);

				// K3 / K4 / K5 - a mid-chain node that gets deleted below, with three descendants
				createEnglishCategory(session, 121, null);
				createEnglishCategory(session, 122, 121);
				createEnglishCategory(session, 123, 122);
				createEnglishCategory(session, 124, 123);
				createEnglishCategory(session, 125, 124);

				// a Czech-only mid-chain node that gets deleted below - deletion and locale combined
				createEnglishCategory(session, 131, null);
				createCzechCategory(session, 132, 131);
				createEnglishCategory(session, 133, 132);
				createEnglishCategory(session, 134, 133);

				// the non-localized hierarchy
				createBrand(session, 101, null);
				createBrand(session, 102, 101);
				createBrand(session, 103, 102);
			}
		);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// three roots - their descendants keep pointing at a primary key that no longer resolves
				session.deleteEntity(Entities.CATEGORY, 71);
				session.deleteEntity(Entities.CATEGORY, 81);
				session.deleteEntity(Entities.CATEGORY, 91);
				// two mid-chain nodes - their descendants become orphans
				session.deleteEntity(Entities.CATEGORY, 122);
				session.deleteEntity(Entities.CATEGORY, 132);
			}
		);

		return new DataCarrier();
	}

	/* ------------------------------------------------------------------------------------------ */
	/* P rows - the locale gate                                                                    */
	/* ------------------------------------------------------------------------------------------ */

	/**
	 * Matrix row P1 - `12 -> 11(cs)` under the standard requirement. The unmaterializable immediate
	 * parent survives as a bodyless pointer and the chain ends there.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("P1: locale-less immediate parent is returned as a bodyless pointer")
	@UseDataSet(DATA_SET)
	@Test
	void shouldReturnBodylessPointerForLocaleLessImmediateParent_P1(Evita evita) {
		final List<EntityClassifierWithParent> chain = fetchParentChain(
			evita, 12, hierarchyContent(entityFetch(attributeContentAll())), true
		);
		assertEquals("P(11) -> " + CHAIN_END, describeChain(chain));
		assertPointer(chain.get(0), 11);
	}

	/**
	 * Matrix row P2 - `23 -> 22 -> 21(cs)` under the standard requirement. The unmaterializable root is
	 * gone entirely and its materializable child now looks like a root.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("P2: locale-less root is dropped, the materializable parent below it keeps its body")
	@UseDataSet(DATA_SET)
	@Test
	void shouldDropLocaleLessRootAndKeepMaterializableParent_P2(Evita evita) {
		final List<EntityClassifierWithParent> chain = fetchParentChain(
			evita, 23, hierarchyContent(entityFetch(attributeContentAll())), true
		);
		assertEquals("B(22) -> " + CHAIN_END, describeChain(chain));
		assertBody(chain.get(0), 22);
	}

	/**
	 * Matrix row P3 - `34 -> 33 -> 32(cs) -> 31` under the standard requirement. The walk short-circuits
	 * at the first unmaterializable ancestor and discards the materializable root above it.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("P3: locale-less ancestor and the materializable root above it both disappear")
	@UseDataSet(DATA_SET)
	@Test
	void shouldDropLocaleLessAncestorAndEverythingAboveIt_P3(Evita evita) {
		final List<EntityClassifierWithParent> chain = fetchParentChain(
			evita, 34, hierarchyContent(entityFetch(attributeContentAll())), true
		);
		// 32 holds Czech data only and 31 is perfectly materializable, yet both are gone - the walk
		// short-circuits at the first missing body instead of stepping over it
		assertEquals("B(33) -> " + CHAIN_END, describeChain(chain));
		assertBody(chain.get(0), 33);
	}

	/**
	 * Matrix row P4 - `44 -> 43(cs) -> 42(cs) -> 41` under the standard requirement. Two adjacent
	 * unmaterializable ancestors yield one pointer and nothing above it.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("P4: two adjacent locale-less ancestors collapse to a single pointer")
	@UseDataSet(DATA_SET)
	@Test
	void shouldReturnSinglePointerForTwoAdjacentLocaleLessAncestors_P4(Evita evita) {
		final List<EntityClassifierWithParent> chain = fetchParentChain(
			evita, 44, hierarchyContent(entityFetch(attributeContentAll())), true
		);
		assertEquals("P(43) -> " + CHAIN_END, describeChain(chain));
		assertPointer(chain.get(0), 43);
	}

	/**
	 * Matrix row P5 - `54 -> 53(cs) -> 52 -> 51(cs)` under the standard requirement. The materializable
	 * ancestor sandwiched between two unmaterializable ones is lost.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("P5: a materializable ancestor above a locale-less parent is discarded")
	@UseDataSet(DATA_SET)
	@Test
	void shouldDiscardMaterializableAncestorAboveLocaleLessParent_P5(Evita evita) {
		final List<EntityClassifierWithParent> chain = fetchParentChain(
			evita, 54, hierarchyContent(entityFetch(attributeContentAll())), true
		);
		// 52 carries English data and would materialize, but it sits above the locale-less 53
		assertEquals("P(53) -> " + CHAIN_END, describeChain(chain));
		assertPointer(chain.get(0), 53);
	}

	/**
	 * Matrix row P6 - `63 -> 62(cs) -> 61(cs)` under the standard requirement. This row was inferred in
	 * the original measurement rather than observed, and is measured here for the first time.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("P6: locale-less ancestors all the way to the root collapse to a single pointer")
	@UseDataSet(DATA_SET)
	@Test
	void shouldReturnSinglePointerForLocaleLessAncestorsUpToRoot_P6(Evita evita) {
		final List<EntityClassifierWithParent> chain = fetchParentChain(
			evita, 63, hierarchyContent(entityFetch(attributeContentAll())), true
		);
		assertEquals("P(62) -> " + CHAIN_END, describeChain(chain));
		assertPointer(chain.get(0), 62);
	}

	/**
	 * Fixture control - `3 -> 2 -> 1`, every node materializable. Proves the fixture and the standard
	 * requirement return a full chain with bodies when nothing blocks the walk.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("Control: a fully materializable chain is returned complete, with bodies")
	@UseDataSet(DATA_SET)
	@Test
	void shouldReturnCompleteChainWithBodiesWhenEveryAncestorMaterializes_control(Evita evita) {
		final SealedEntity leaf = fetchLeaf(
			evita, 3, hierarchyContent(entityFetch(attributeContentAll())), true
		).orElseThrow();
		assertEquals("c3", leaf.getAttribute(ATTRIBUTE_CODE));

		final List<EntityClassifierWithParent> chain = parentChainOf(leaf);
		assertEquals("B(2) -> B(1) -> " + CHAIN_END, describeChain(chain));
		assertBody(chain.get(0), 2);
		assertBody(chain.get(1), 1);
	}

	/* ------------------------------------------------------------------------------------------ */
	/* N rows - requirement variations                                                             */
	/* ------------------------------------------------------------------------------------------ */

	/**
	 * Matrix row N1 - `23 -> 22 -> 21(cs)` with a bare `hierarchyContent()`. No body is requested, so
	 * the locale gate never fires and the whole primary-key axis is returned.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("N1: the full primary-key chain survives when no ancestor bodies are requested")
	@UseDataSet(DATA_SET)
	@Test
	void shouldReturnFullPrimaryKeyChainWhenNoBodiesRequested_N1(Evita evita) {
		final List<EntityClassifierWithParent> chain = fetchParentChain(
			evita, 23, hierarchyContent(), true
		);
		// the ancestor axis itself is complete - the locale gate only fires once bodies are fetched
		assertEquals("P(22) -> P(21) -> " + CHAIN_END, describeChain(chain));
		assertPointer(chain.get(0), 22);
		assertPointer(chain.get(1), 21);
	}

	/**
	 * Matrix row N2 - `12 -> 11(cs)` with `stopAt(distance(1))`.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("N2: stopAt(distance(1)) still yields the bodyless pointer for a locale-less parent")
	@UseDataSet(DATA_SET)
	@Test
	void shouldReturnBodylessPointerUnderStopAtDistanceOne_N2(Evita evita) {
		final List<EntityClassifierWithParent> chain = fetchParentChain(
			evita, 12, hierarchyContent(stopAt(distance(1)), entityFetch(attributeContentAll())), true
		);
		assertEquals("P(11) -> " + CHAIN_END, describeChain(chain));
		assertPointer(chain.get(0), 11);
	}

	/**
	 * Matrix row N3 - `34 -> 33 -> 32(cs) -> 31` with `stopAt(distance(2))`.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("N3: stopAt(distance(2)) drops the locale-less ancestor it would have reached")
	@UseDataSet(DATA_SET)
	@Test
	void shouldDropLocaleLessAncestorUnderStopAtDistanceTwo_N3(Evita evita) {
		final List<EntityClassifierWithParent> chain = fetchParentChain(
			evita, 34, hierarchyContent(stopAt(distance(2)), entityFetch(attributeContentAll())), true
		);
		assertEquals("B(33) -> " + CHAIN_END, describeChain(chain));
		assertBody(chain.get(0), 33);
	}

	/**
	 * Matrix row N4 - `12 -> 11(cs)` with no query locale at all. The control that isolates the
	 * query-level `entityLocaleEquals` as the cause.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("N4: without a query locale the chain is complete and carries bodies")
	@UseDataSet(DATA_SET)
	@Test
	void shouldReturnCompleteChainWithBodiesWithoutQueryLocale_N4(Evita evita) {
		final List<EntityClassifierWithParent> chain = fetchParentChain(
			evita, 12, hierarchyContent(entityFetch(attributeContentAll())), false
		);
		// removing the single entityLocaleEquals line repairs the chain - the gate is the query locale
		assertEquals("B(11) -> " + CHAIN_END, describeChain(chain));
		assertBody(chain.get(0), 11);
	}

	/**
	 * Matrix row N5 - `12 -> 11(cs)` with a query locale plus `dataInLocales(en)` inside the parent
	 * `entityFetch`. The inner requirement does not override the query-level gate.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("N5: dataInLocales inside the parent entityFetch does not rescue the ancestor")
	@UseDataSet(DATA_SET)
	@Test
	void shouldNotRescueAncestorWithDataInLocalesInsideEntityFetch_N5(Evita evita) {
		final List<EntityClassifierWithParent> chain = fetchParentChain(
			evita, 12,
			hierarchyContent(entityFetch(attributeContentAll(), dataInLocales(Locale.ENGLISH))),
			true
		);
		assertEquals("P(11) -> " + CHAIN_END, describeChain(chain));
		assertPointer(chain.get(0), 11);
	}

	/**
	 * Companion of matrix row N5 - the same `dataInLocales(en)` inside the parent `entityFetch`, but
	 * with no query-level locale. The ancestor body comes back with its localized attribute absent.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("N5 variant: with the locale requested only inside entityFetch the body is returned")
	@UseDataSet(DATA_SET)
	@Test
	void shouldReturnAncestorBodyWhenLocaleIsRequestedOnlyInsideEntityFetch_N5variant(Evita evita) {
		final List<EntityClassifierWithParent> chain = fetchParentChain(
			evita, 12,
			hierarchyContent(entityFetch(attributeContentAll(), dataInLocales(Locale.ENGLISH))),
			false
		);
		// the very same inner requirement that cannot rescue the ancestor in N5 returns its body here,
		// with the localized attribute simply absent - only the query-level locale acts as a gate
		assertEquals("B(11) -> " + CHAIN_END, describeChain(chain));
		final SealedEntity parent = assertBody(chain.get(0), 11);
		assertNull(parent.getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
	}

	/**
	 * Variant of matrix row P1 requesting only the global `code` attribute. The gate is an existence
	 * predicate on the entity, not a check of which attributes were asked for.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("P1 variant: requesting only a global attribute does not save the immediate parent")
	@UseDataSet(DATA_SET)
	@Test
	void shouldDropImmediateParentEvenWhenOnlyGlobalAttributeRequested_P1global(Evita evita) {
		final List<EntityClassifierWithParent> chain = fetchParentChain(
			evita, 12, hierarchyContent(entityFetch(attributeContent(ATTRIBUTE_CODE))), true
		);
		// `code` is a global attribute the ancestor actually holds, and it is still refused
		assertEquals("P(11) -> " + CHAIN_END, describeChain(chain));
		assertPointer(chain.get(0), 11);
	}

	/**
	 * Variant of matrix row P3 requesting only the global `code` attribute.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("P3 variant: requesting only a global attribute does not save a deeper ancestor")
	@UseDataSet(DATA_SET)
	@Test
	void shouldDropDeeperAncestorEvenWhenOnlyGlobalAttributeRequested_P3global(Evita evita) {
		final SealedEntity leaf = fetchLeaf(
			evita, 34, hierarchyContent(entityFetch(attributeContent(ATTRIBUTE_CODE))), true
		).orElseThrow();
		final List<EntityClassifierWithParent> chain = parentChainOf(leaf);
		assertEquals("B(33) -> " + CHAIN_END, describeChain(chain));
		assertEquals("c33", assertBody(chain.get(0), 33).getAttribute(ATTRIBUTE_CODE));
	}

	/**
	 * Pins why the behaviour matrix carries no non-localized row: a collection whose schema declares no
	 * locale matches nothing under a query-level `entityLocaleEquals`, so the queried entity itself is
	 * filtered out before its parents are ever considered.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("Non-localized collection: a query-level locale filters out the queried entity itself")
	@UseDataSet(DATA_SET)
	@Test
	void shouldReturnNothingFromNonLocalizedCollectionUnderQueryLocale_nonLocalizedSchema(Evita evita) {
		final List<SealedEntity> brands = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryListOfSealedEntities(
					query(
						collection(Entities.BRAND),
						filterBy(
							entityPrimaryKeyInSet(103),
							entityLocaleEquals(Locale.ENGLISH)
						),
						require(
							entityFetch(
								attributeContentAll(),
								hierarchyContent(entityFetch(attributeContentAll()))
							)
						)
					)
				);
			}
		);
		// the brand schema declares no locale, so nothing in it matches entityLocaleEquals - the parent
		// chain of a non-localized hierarchy is simply not reachable through a query-level locale filter
		assertTrue(brands.isEmpty(), "Expected no brand to match a query-level locale, but got: " + brands);
	}

	/* ------------------------------------------------------------------------------------------ */
	/* K rows - broken chains                                                                      */
	/* ------------------------------------------------------------------------------------------ */

	/**
	 * Matrix row K1 - `72 -> 71`, where 71 was a root that has been deleted. The chain points at a
	 * primary key that resolves to no entity.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("K1: a deleted root as the immediate parent is returned as a bodyless pointer")
	@UseDataSet(DATA_SET)
	@Test
	void shouldReturnBodylessPointerToDeletedRootParent_K1(Evita evita) {
		final List<EntityClassifierWithParent> chain = fetchParentChain(
			evita, 72, hierarchyContent(entityFetch(attributeContentAll())), true
		);
		// 71 no longer exists as an entity, yet the chain still points at it
		assertEquals("P(71) -> " + CHAIN_END, describeChain(chain));
		assertPointer(chain.get(0), 71);
	}

	/**
	 * Matrix row K2 - `83 -> 82 -> 81`, where 81 was a deleted root. Deleting a root never throws,
	 * because a removed root is never un-indexed and its children are never orphaned.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("K2: a deleted root two levels up disappears silently")
	@UseDataSet(DATA_SET)
	@Test
	void shouldDropDeletedRootTwoLevelsUp_K2(Evita evita) {
		final List<EntityClassifierWithParent> chain = fetchParentChain(
			evita, 83, hierarchyContent(entityFetch(attributeContentAll())), true
		);
		assertEquals("B(82) -> " + CHAIN_END, describeChain(chain));
		assertBody(chain.get(0), 82);
	}

	/**
	 * Matrix row K3 - `123 -> 122 -> 121`, where the mid-chain 122 has been deleted and sits at the
	 * immediate-parent position.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("K3: a deleted mid-chain immediate parent is returned as a bodyless pointer")
	@UseDataSet(DATA_SET)
	@Test
	void shouldReturnBodylessPointerToDeletedMidChainParent_K3(Evita evita) {
		final List<EntityClassifierWithParent> chain = fetchParentChain(
			evita, 123, hierarchyContent(entityFetch(attributeContentAll())), true
		);
		assertEquals("P(122) -> " + CHAIN_END, describeChain(chain));
		assertPointer(chain.get(0), 122);
	}

	/**
	 * Matrix row K4 - `124 -> 123 -> 122 -> 121`, where the deleted mid-chain 122 sits exactly two
	 * levels above the queried entity. This is the one row whose current outcome is an exception.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("K4: a deleted mid-chain ancestor exactly two levels up makes the query throw")
	@UseDataSet(DATA_SET)
	@Test
	void shouldThrowWhenDeletedMidChainAncestorSitsTwoLevelsUp_K4(Evita evita) {
		final EvitaInvalidUsageException exception = assertThrows(
			EvitaInvalidUsageException.class,
			() -> fetchLeaf(evita, 124, hierarchyContent(entityFetch(attributeContentAll())), true)
		);
		// the pre-walk in HierarchyIndex#traverseHierarchyToRoot starts at the queried entity's parent,
		// so the assert only fires when the break sits exactly two levels above the queried entity
		assertTrue(
			exception.getMessage().contains("unexpectedly not present in the index"),
			"Unexpected message: " + exception.getMessage()
		);
	}

	/**
	 * Matrix row K5 - `125 -> 124 -> 123 -> 122`, where the break sits three levels up. No exception,
	 * but the materializable immediate parent is demoted to a pointer.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("K5: a break three levels up demotes the materializable parent to a pointer")
	@UseDataSet(DATA_SET)
	@Test
	void shouldDemoteMaterializableParentToPointerWhenBreakSitsThreeLevelsUp_K5(Evita evita) {
		final List<EntityClassifierWithParent> chain = fetchParentChain(
			evita, 125, hierarchyContent(entityFetch(attributeContentAll())), true
		);
		// 124 carries English data and would materialize; three levels up the traversal is silent and
		// the body is lost all the same
		assertEquals("P(124) -> " + CHAIN_END, describeChain(chain));
		assertPointer(chain.get(0), 124);
	}

	/**
	 * Matrix row K6 - `111 -> 999`, a parent primary key that was never created. The upsert setting it
	 * was accepted, and the chain reports the dangling key.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("K6: a never-created parent primary key is returned as a bodyless pointer")
	@UseDataSet(DATA_SET)
	@Test
	void shouldReturnBodylessPointerToNeverCreatedParent_K6(Evita evita) {
		final List<EntityClassifierWithParent> chain = fetchParentChain(
			evita, 111, hierarchyContent(entityFetch(attributeContentAll())), true
		);
		// the upsert that set parent 999 was accepted even though 999 never existed
		assertEquals("P(999) -> " + CHAIN_END, describeChain(chain));
		assertPointer(chain.get(0), 999);
	}

	/**
	 * Companion of matrix row K6 - `112 -> 111 -> 999`. The dangling parent primary key reaches the same
	 * assert as K4 from the ingest side, with no deletion involved.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("K6 variant: a never-created ancestor two levels up makes the query throw")
	@UseDataSet(DATA_SET)
	@Test
	void shouldThrowWhenNeverCreatedAncestorSitsTwoLevelsUp_K6variant(Evita evita) {
		final EvitaInvalidUsageException exception = assertThrows(
			EvitaInvalidUsageException.class,
			() -> fetchLeaf(evita, 112, hierarchyContent(entityFetch(attributeContentAll())), true)
		);
		// the dangling-parent ingest path reaches the very same assert as K4, with no deletion involved
		assertTrue(
			exception.getMessage().contains("unexpectedly not present in the index"),
			"Unexpected message: " + exception.getMessage()
		);
	}

	/**
	 * Variant of matrix row K3 - `133 -> 132 -> 131`, where the deleted mid-chain 132 also held Czech
	 * data only. Deletion and the locale gate produce the same observable.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("K3 variant: a deleted Czech-only mid-chain parent is returned as a bodyless pointer")
	@UseDataSet(DATA_SET)
	@Test
	void shouldReturnBodylessPointerToDeletedLocaleLessMidChainParent_K3variant(Evita evita) {
		final List<EntityClassifierWithParent> chain = fetchParentChain(
			evita, 133, hierarchyContent(entityFetch(attributeContentAll())), true
		);
		// deletion and the locale gate produce the same observable, so the caller cannot tell them apart
		assertEquals("P(132) -> " + CHAIN_END, describeChain(chain));
		assertPointer(chain.get(0), 132);
	}

	/* ------------------------------------------------------------------------------------------ */
	/* Phantom root - a defect pin, not a matrix row                                               */
	/* ------------------------------------------------------------------------------------------ */

	/**
	 * Defect pin, not a matrix row: a deleted root leaves a phantom node behind in the hierarchy index,
	 * so it still matches `hierarchyWithinRootSelf()` while resolving to no entity. Folding the fix in
	 * is part of #1365 because it changes what a broken chain means to the parent fetcher.
	 *
	 * @param evita the embedded evitaDB instance provided by the test extension
	 */
	@DisplayName("Defect pin: a deleted root still matches hierarchyWithinRootSelf today")
	@UseDataSet(DATA_SET)
	@Test
	void shouldStillListDeletedRootInHierarchyToday_phantomRoot(Evita evita) {
		final List<EntityReferenceContract> hierarchyMembers = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryListOfEntityReferences(
					query(
						collection(Entities.CATEGORY),
						filterBy(hierarchyWithinRootSelf()),
						require(page(1, 500))
					)
				);
			}
		);
		final int[] attachedToRoot = new int[hierarchyMembers.size()];
		for (int i = 0; i < attachedToRoot.length; i++) {
			attachedToRoot[i] = hierarchyMembers.get(i).getPrimaryKey();
		}
		Arrays.sort(attachedToRoot);

		// 71, 81 and 91 were deleted, yet they are still listed - EntityRemoveMutation emits
		// RemoveParentMutation only for an entity that has a parent, so a removed root is never
		// un-indexed and its descendants are never orphaned
		assertArrayEquals(
			new int[]{
				1, 2, 3,
				11, 12,
				21, 22, 23,
				31, 32, 33, 34,
				41, 42, 43, 44,
				51, 52, 53, 54,
				61, 62, 63,
				71, 72,
				81, 82, 83,
				91, 92, 93, 94,
				121,
				131
			},
			attachedToRoot
		);

		// the very same primary key resolves to no entity at all
		final Optional<SealedEntity> deletedRoot = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneSealedEntity(
					query(
						collection(Entities.CATEGORY),
						filterBy(entityPrimaryKeyInSet(81)),
						require(entityFetch(attributeContentAll()))
					)
				);
			}
		);
		assertTrue(deletedRoot.isEmpty(), "The deleted root must not be fetchable, but was: " + deletedRoot);
	}

	/* ------------------------------------------------------------------------------------------ */
	/* Fixture helpers                                                                             */
	/* ------------------------------------------------------------------------------------------ */

	/**
	 * Creates a category holding English localized data plus the global `code` attribute, so
	 * that it is materializable under an English query locale.
	 *
	 * @param session          the session to upsert through
	 * @param primaryKey       the primary key to assign
	 * @param parentPrimaryKey the parent primary key, or `null` for a root node
	 */
	private static void createEnglishCategory(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nullable Integer parentPrimaryKey
	) {
		createCategory(session, primaryKey, parentPrimaryKey, Locale.ENGLISH);
	}

	/**
	 * Creates a category holding Czech localized data plus the global `code` attribute. Such a
	 * node is not materializable under an English query locale even though its global attribute is
	 * perfectly readable.
	 *
	 * @param session          the session to upsert through
	 * @param primaryKey       the primary key to assign
	 * @param parentPrimaryKey the parent primary key, or `null` for a root node
	 */
	private static void createCzechCategory(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nullable Integer parentPrimaryKey
	) {
		createCategory(session, primaryKey, parentPrimaryKey, LOCALE_CZECH);
	}

	/**
	 * Creates a category carrying the global `code` attribute and a localized `name` in
	 * the single requested locale.
	 *
	 * @param session          the session to upsert through
	 * @param primaryKey       the primary key to assign
	 * @param parentPrimaryKey the parent primary key, or `null` for a root node
	 * @param locale           the only locale the created node holds data in
	 */
	private static void createCategory(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nullable Integer parentPrimaryKey,
		@Nonnull Locale locale
	) {
		final EntityBuilder builder = session.createNewEntity(Entities.CATEGORY, primaryKey)
			.setAttribute(ATTRIBUTE_CODE, expectedCode(primaryKey))
			.setAttribute(ATTRIBUTE_NAME, locale, "Category " + primaryKey);
		if (parentPrimaryKey != null) {
			builder.setParent(parentPrimaryKey);
		}
		builder.upsertVia(session);
	}

	/**
	 * Creates a brand in the non-localized hierarchical collection.
	 *
	 * @param session          the session to upsert through
	 * @param primaryKey       the primary key to assign
	 * @param parentPrimaryKey the parent primary key, or `null` for a root node
	 */
	private static void createBrand(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nullable Integer parentPrimaryKey
	) {
		final EntityBuilder builder = session.createNewEntity(Entities.BRAND, primaryKey)
			.setAttribute(ATTRIBUTE_NAME, "Brand " + primaryKey);
		if (parentPrimaryKey != null) {
			builder.setParent(parentPrimaryKey);
		}
		builder.upsertVia(session);
	}

	/**
	 * Returns the value of the global `code` attribute a fixture node of the given primary key
	 * carries. Reading it back is what proves an element of the chain really has a body.
	 *
	 * @param primaryKey the primary key of the fixture node
	 * @return the expected global attribute value
	 */
	@Nonnull
	private static String expectedCode(int primaryKey) {
		return "c" + primaryKey;
	}

	/* ------------------------------------------------------------------------------------------ */
	/* Query and assertion helpers                                                                 */
	/* ------------------------------------------------------------------------------------------ */

	/**
	 * Runs the standard characterisation query against the `CATEGORY` collection.
	 *
	 * @param evita            the embedded evitaDB instance
	 * @param primaryKey       the primary key of the queried leaf
	 * @param hierarchyRequirement the `hierarchyContent` requirement under test
	 * @param withQueryLocale  whether the query carries `entityLocaleEquals(en)`
	 * @return the queried entity, or empty when the filter matched nothing
	 */
	@Nonnull
	private static Optional<SealedEntity> fetchLeaf(
		@Nonnull Evita evita,
		int primaryKey,
		@Nonnull HierarchyContent hierarchyRequirement,
		boolean withQueryLocale
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOneSealedEntity(
					query(
						collection(Entities.CATEGORY),
						withQueryLocale ?
							filterBy(entityPrimaryKeyInSet(primaryKey), entityLocaleEquals(Locale.ENGLISH)) :
							filterBy(entityPrimaryKeyInSet(primaryKey)),
						require(entityFetch(attributeContentAll(), hierarchyRequirement))
					)
				);
			}
		);
	}

	/**
	 * Runs the standard characterisation query and returns the parent chain of the queried entity.
	 *
	 * @param evita            the embedded evitaDB instance
	 * @param primaryKey       the primary key of the queried leaf
	 * @param hierarchyRequirement the `hierarchyContent` requirement under test
	 * @param withQueryLocale  whether the query carries `entityLocaleEquals(en)`
	 * @return the parent chain, ordered from the immediate parent upwards
	 */
	@Nonnull
	private static List<EntityClassifierWithParent> fetchParentChain(
		@Nonnull Evita evita,
		int primaryKey,
		@Nonnull HierarchyContent hierarchyRequirement,
		boolean withQueryLocale
	) {
		return parentChainOf(
			fetchLeaf(evita, primaryKey, hierarchyRequirement, withQueryLocale)
				.orElseThrow(() -> new AssertionError("Entity with primary key " + primaryKey + " was not returned."))
		);
	}

	/**
	 * Walks the parent axis of the given entity from the immediate parent upwards until
	 * {@link EntityClassifierWithParent#getParentEntity()} reports an empty optional.
	 *
	 * @param entity the queried entity whose ancestors to collect
	 * @return the ancestors in leaf-to-root order, the queried entity itself excluded
	 */
	@Nonnull
	private static List<EntityClassifierWithParent> parentChainOf(@Nonnull SealedEntity entity) {
		final List<EntityClassifierWithParent> chain = new ArrayList<>(8);
		Optional<EntityClassifierWithParent> current = entity.getParentEntity();
		while (current.isPresent()) {
			final EntityClassifierWithParent node = current.get();
			chain.add(node);
			current = node.getParentEntity();
		}
		return chain;
	}

	/**
	 * Renders a parent chain in the notation the behaviour matrix uses: `B(pk)` for an ancestor
	 * present with a body, `P(pk)` for a bodyless pointer, and a trailing `(end)` marking
	 * the position where the chain stops.
	 *
	 * @param chain the chain to render
	 * @return the rendered chain, always terminated by `(end)`
	 */
	@Nonnull
	private static String describeChain(@Nonnull List<EntityClassifierWithParent> chain) {
		final StringBuilder result = new StringBuilder(64);
		for (EntityClassifierWithParent node : chain) {
			result.append(node instanceof SealedEntity ? "B(" : "P(")
				.append(node.getPrimaryKey())
				.append(") -> ");
		}
		return result.append(CHAIN_END).toString();
	}

	/**
	 * Asserts that the given chain element is a materialized entity carrying the expected primary key
	 * and the global `code` attribute the fixture assigned to it.
	 *
	 * @param node       the chain element to check
	 * @param primaryKey the primary key the element must carry
	 * @return the element narrowed to {@link SealedEntity}
	 */
	@Nonnull
	private static SealedEntity assertBody(@Nonnull EntityClassifierWithParent node, int primaryKey) {
		final SealedEntity sealedEntity = assertInstanceOf(
			SealedEntity.class, node,
			"Ancestor " + primaryKey + " was expected to carry a body."
		);
		assertEquals(primaryKey, sealedEntity.getPrimaryKey());
		assertEquals(
			expectedCode(primaryKey), sealedEntity.getAttribute(ATTRIBUTE_CODE),
			"The body of ancestor " + primaryKey + " must expose its global attribute."
		);
		return sealedEntity;
	}

	/**
	 * Asserts that the given chain element is a bodyless pointer carrying the expected primary key -
	 * an {@link EntityReferenceWithParent} that is explicitly not a {@link SealedEntity}, so no
	 * attribute of it can be read at all.
	 *
	 * @param node       the chain element to check
	 * @param primaryKey the primary key the pointer must carry
	 */
	private static void assertPointer(@Nonnull EntityClassifierWithParent node, int primaryKey) {
		assertFalse(
			node instanceof SealedEntity,
			"Ancestor " + primaryKey + " was expected to be bodyless, but carries a body."
		);
		final EntityReferenceWithParent pointer = assertInstanceOf(EntityReferenceWithParent.class, node);
		assertEquals(primaryKey, pointer.getPrimaryKey());
		assertEquals(Entities.CATEGORY, pointer.getType());
	}

}
