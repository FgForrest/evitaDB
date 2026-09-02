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
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityClassifierWithParent;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.annotation.EntityRef;
import io.evitadb.api.requestResponse.data.annotation.ParentEntity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKeyRef;
import io.evitadb.api.requestResponse.data.structure.EntityReferenceWithParent;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

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
import static io.evitadb.test.TestTags.PROXY;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterisation tests for the parent chain returned by `hierarchyContent` - see issue #1365
 * (https://github.com/FgForrest/evitaDB/issues/1365).
 *
 * This class pins the `today` column of the behaviour matrix in
 * `documentation/adr/2026-08-03-hierarchy-content-parents-behaviour.md`, measured on 2026-09-02. It
 * asserts what the engine does right now, defects included - it does **not** assert what it ought to
 * do. Several of the pinned rows are the very defects #1365 reports, and three of the pinned
 * situations (a deleted mid-chain ancestor two levels up, a never-created ancestor two levels up, and
 * a deleted Czech-only mid-chain ancestor two levels up) pin a thrown exception as the current
 * outcome.
 *
 * When the `HierarchyParentsBehaviour` argument lands, each row moves to the `COMPLETE` or the
 * `MATCHING` column of that same matrix; every row a change does not touch must keep passing
 * unchanged, which is what makes this class the backward-compatibility guard for the whole line of
 * work. Every method that pins a matrix row carries that row's identifier in its name and in its
 * display name; the remaining methods pin a control, a variant of a row, or a defect the matrix has
 * no row for.
 *
 * Assertions run against the raw {@link SealedEntity} API rather than through typed proxy interfaces,
 * because {@link io.evitadb.api.proxy.impl.ProxyUtils#createOptionalWrapper} picks a swallowing
 * wrapper for a getter that neither returns an {@link Optional} nor declares an exception, which
 * would report "never requested" and "cannot be materialized" identically and let a broken
 * implementation pass. The single deliberate exception is the typed-proxy row in
 * {@link LocaleGateTest}: it crosses to the proxy surface through a `throws`-declaring getter,
 * because that is the only place today's {@link ContextMissingException} on dereference is
 * observable, and that exception is the one observable which would change silently for existing
 * callers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Evita hierarchyContent parent chain - characterisation of current behaviour")
@ExtendWith(EvitaParameterResolver.class)
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
	 *
	 * The language-only form is deliberate and matches the sibling suite
	 * {@link ManagedReferenceLocaleFunctionalTest}; the country-qualified
	 * {@link io.evitadb.test.generator.DataGenerator#CZECH_LOCALE} is a different locale, and mixing the
	 * two in one schema would declare one Czech locale while the upsert wrote another.
	 */
	private static final Locale LOCALE_CZECH = new Locale("cs");
	/**
	 * Rendering of a chain that ends, i.e. whose last element reports an empty
	 * {@link EntityClassifierWithParent#getParentEntity()}.
	 */
	private static final String CHAIN_END = "(end)";
	/**
	 * Upper bound on the length of a collected parent chain. The deepest fixture hierarchy is five nodes
	 * deep, so a collected chain can never legitimately hold more than four ancestors; anything beyond
	 * this bound means the walk found a cycle and must fail rather than spin.
	 */
	private static final int MAX_CHAIN_LENGTH = 16;

	/**
	 * Builds the whole behaviour-matrix fixture in a single shared catalog.
	 *
	 * The `CATEGORY` collection is hierarchical and localized in `cs` and `en`, carries a localized
	 * nullable `name` and a global nullable `code`. A node created with English data is
	 * materializable under the English query locale; a node created with Czech data only is not,
	 * while still holding a readable global `code`. The `BRAND` collection is hierarchical, carries
	 * the same global `code`, and declares no locale at all.
	 *
	 * Primary keys are grouped by the matrix row they serve: `1-3` the fully materializable control,
	 * `11-12` row P1, `21-23` row P2, `31-34` row P3, `41-44` row P4, `51-54` row P5, `61-63` row P6,
	 * `71-72` row K1, `81-83` row K2, `91-94` the deeper deleted-root probe, `101-103` the non-localized
	 * `BRAND` hierarchy, `111-112` row K6, `121-125` rows K3/K4/K5 and `131-134` the Czech-only
	 * mid-chain deletion.
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
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::nullable)
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

				// a deleted root three levels up, plus the phantom-root assertion
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
				deleteFixtureNode(session, 71);
				deleteFixtureNode(session, 81);
				deleteFixtureNode(session, 91);
				// two mid-chain nodes - their descendants become orphans
				deleteFixtureNode(session, 122);
				deleteFixtureNode(session, 132);
			}
		);

		return new DataCarrier();
	}

	/**
	 * Typed proxy over the `CATEGORY` collection whose parent getter **declares** an exception, so that
	 * {@link io.evitadb.api.proxy.impl.ProxyUtils#createOptionalWrapper} picks the rethrowing wrapper
	 * instead of the swallowing one. Without the `throws` clause the wrapper catches the
	 * {@link ContextMissingException} and hands back `null` instead, and the exception this interface
	 * exists to observe would be invisible.
	 */
	@EntityRef(Entities.CATEGORY)
	public interface ParentDereferencingCategory extends EntityClassifier {

		/**
		 * Returns the primary key of the proxied category.
		 *
		 * @return the primary key of the proxied category
		 */
		@PrimaryKeyRef
		int getId();

		/**
		 * Returns the immediate parent of the proxied category.
		 *
		 * @return the immediate parent proxy, or `null` when the category is a root
		 * @throws ContextMissingException when the parent is present in the chain but carries no body
		 */
		@ParentEntity
		@Nullable
		ParentDereferencingCategory getParentEntity() throws ContextMissingException;

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
	 * Creates a brand in the non-localized hierarchical collection. The global `code` attribute is
	 * assigned by the same rule the categories use, so the shared body assertion works on brands too.
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
			.setAttribute(ATTRIBUTE_CODE, expectedCode(primaryKey))
			.setAttribute(ATTRIBUTE_NAME, "Brand " + primaryKey);
		if (parentPrimaryKey != null) {
			builder.setParent(parentPrimaryKey);
		}
		builder.upsertVia(session);
	}

	/**
	 * Deletes a fixture category and asserts the deletion really happened. A deletion that silently
	 * did nothing would surface as a wrong parent chain several rows away from its cause.
	 *
	 * @param session    the session to delete through
	 * @param primaryKey the primary key of the fixture node to delete
	 */
	private static void deleteFixtureNode(@Nonnull EvitaSessionContract session, int primaryKey) {
		assertTrue(
			session.deleteEntity(Entities.CATEGORY, primaryKey),
			"Fixture node " + primaryKey + " was expected to exist and be deleted"
		);
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
	 * Returns the requirement every matrix row is measured under - `hierarchyContent` fetching the
	 * complete attribute content of every ancestor it reaches.
	 *
	 * @return the standard `hierarchyContent` requirement
	 */
	@Nonnull
	private static HierarchyContent standardRequirement() {
		return hierarchyContent(entityFetch(attributeContentAll()));
	}

	/**
	 * Returns the filter every characterisation query selects its leaf with - the queried primary key
	 * alone, or the same primary key gated by the English query locale the P rows are measured under.
	 *
	 * @param primaryKey      the primary key of the queried entity
	 * @param withQueryLocale whether the filter carries `entityLocaleEquals(en)`
	 * @return the filter to place in the query
	 */
	@Nonnull
	private static FilterBy leafFilter(int primaryKey, boolean withQueryLocale) {
		return withQueryLocale ?
			filterBy(entityPrimaryKeyInSet(primaryKey), entityLocaleEquals(Locale.ENGLISH)) :
			filterBy(entityPrimaryKeyInSet(primaryKey));
	}

	/**
	 * Returns the parent chain of the given entity under the standard requirement and the English
	 * query locale - the combination the behaviour matrix calls `standard`.
	 *
	 * @param evita      the embedded evitaDB instance
	 * @param primaryKey the primary key of the queried leaf
	 * @return the parent chain, ordered from the immediate parent upwards
	 */
	@Nonnull
	private static List<EntityClassifierWithParent> standardParentChain(@Nonnull Evita evita, int primaryKey) {
		return fetchParentChain(evita, primaryKey, standardRequirement(), true);
	}

	/**
	 * Runs the standard characterisation query against the `CATEGORY` collection.
	 *
	 * @param evita                the embedded evitaDB instance
	 * @param primaryKey           the primary key of the queried leaf
	 * @param hierarchyRequirement the `hierarchyContent` requirement under test
	 * @param withQueryLocale      whether the query carries `entityLocaleEquals(en)`
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
						leafFilter(primaryKey, withQueryLocale),
						require(entityFetch(attributeContentAll(), hierarchyRequirement))
					)
				);
			}
		);
	}

	/**
	 * Runs the standard characterisation query and returns the parent chain of the queried entity.
	 *
	 * @param evita                the embedded evitaDB instance
	 * @param primaryKey           the primary key of the queried leaf
	 * @param hierarchyRequirement the `hierarchyContent` requirement under test
	 * @param withQueryLocale      whether the query carries `entityLocaleEquals(en)`
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
	 * Runs the standard characterisation query against the non-localized `BRAND` collection.
	 *
	 * @param evita           the embedded evitaDB instance
	 * @param primaryKey      the primary key of the queried brand
	 * @param withQueryLocale whether the query carries `entityLocaleEquals(en)`
	 * @return every brand the query matched
	 */
	@Nonnull
	private static List<SealedEntity> fetchBrands(
		@Nonnull Evita evita,
		int primaryKey,
		boolean withQueryLocale
	) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryListOfSealedEntities(
					query(
						collection(Entities.BRAND),
						leafFilter(primaryKey, withQueryLocale),
						require(entityFetch(attributeContentAll(), standardRequirement()))
					)
				);
			}
		);
	}


	/**
	 * Fetches a category through the typed proxy interface under the standard requirement and the
	 * English query locale.
	 *
	 * @param evita      the embedded evitaDB instance
	 * @param primaryKey the primary key of the queried category
	 * @return the proxy over the queried category
	 */
	@Nonnull
	private static ParentDereferencingCategory fetchCategoryProxy(@Nonnull Evita evita, int primaryKey) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.queryOne(
					query(
						collection(Entities.CATEGORY),
						leafFilter(primaryKey, true),
						require(entityFetch(attributeContentAll(), standardRequirement()))
					),
					ParentDereferencingCategory.class
				);
			}
		).orElseThrow(() -> new AssertionError("Entity with primary key " + primaryKey + " was not returned."));
	}

	/**
	 * Walks the parent axis of the given entity from the immediate parent upwards until
	 * {@link EntityClassifierWithParent#getParentEntity()} reports an empty optional. The walk is
	 * bounded by {@link #MAX_CHAIN_LENGTH}, so a regression producing a cyclic chain fails the test
	 * instead of hanging the surefire fork.
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
			if (chain.size() > MAX_CHAIN_LENGTH) {
				throw new AssertionError(
					"The parent chain of entity " + entity.getPrimaryKey() + " exceeded " + MAX_CHAIN_LENGTH +
						" elements, which the fixture cannot produce - collected so far: " + describeChain(chain)
				);
			}
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
	 * Asserts an entire parent chain in one call: the rendered chain must equal the expected elements
	 * joined in the behaviour-matrix notation, and every element is then checked individually -
	 * `B(pk)` through {@link #assertBody(EntityClassifierWithParent, int)} and `P(pk)` through
	 * {@link #assertPointer(EntityClassifierWithParent, int)}. An expected element written in neither
	 * shape is a mistake in the test itself and fails immediately rather than being skipped.
	 *
	 * @param chain            the chain returned by the engine
	 * @param expectedElements the expected elements in leaf-to-root order, e.g. `B(22)`, `P(21)`
	 * @throws AssertionError when the rendered chain differs, an element fails its own check, or an
	 *                        expected element uses neither the `B(pk)` nor the `P(pk)` shape
	 */
	private static void assertChain(
		@Nonnull List<EntityClassifierWithParent> chain,
		@Nonnull String... expectedElements
	) {
		final StringBuilder expected = new StringBuilder(64);
		for (String expectedElement : expectedElements) {
			expected.append(expectedElement).append(" -> ");
		}
		expected.append(CHAIN_END);
		assertEquals(expected.toString(), describeChain(chain));

		for (int i = 0; i < expectedElements.length; i++) {
			final String expectedElement = expectedElements[i];
			final int primaryKey = Integer.parseInt(
				expectedElement.substring(expectedElement.indexOf('(') + 1, expectedElement.indexOf(')'))
			);
			final char shape = expectedElement.charAt(0);
			if (shape == 'B') {
				assertBody(chain.get(i), primaryKey);
			} else if (shape == 'P') {
				assertPointer(chain.get(i), primaryKey);
			} else {
				throw new AssertionError(
					"Chain element `" + expectedElement + "` must start with `B` for a body or `P` for a pointer."
				);
			}
		}
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
	 * Asserts that the given chain element is a bodyless pointer carrying the expected primary key.
	 * It is an {@link EntityReferenceWithParent}, which is explicitly not a {@link SealedEntity} - and
	 * that is precisely what makes its attributes unreadable, since only a sealed entity exposes them.
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

	/**
	 * Asserts that fetching a leaf over a broken ancestor chain fails with the exact exception the
	 * index raises today - the base {@link EvitaInvalidUsageException} rather than any of its
	 * subtypes, carrying the complete message that names the ancestor the walk could not resolve.
	 *
	 * @param fetch                   the fetch expected to fail
	 * @param missingParentPrimaryKey the primary key the message must name as unresolvable
	 */
	private static void assertBrokenChainThrows(@Nonnull Executable fetch, int missingParentPrimaryKey) {
		final EvitaInvalidUsageException exception = assertThrows(EvitaInvalidUsageException.class, fetch);
		assertSame(
			EvitaInvalidUsageException.class, exception.getClass(),
			"The broken chain must surface as the base client error, not as one of its subtypes."
		);
		assertEquals(
			"The node parent `" + missingParentPrimaryKey + "` is unexpectedly not present in the index!",
			exception.getMessage()
		);
	}

	/**
	 * Pins the rows of the behaviour matrix where a query-level locale makes an ancestor
	 * unmaterializable - the defect #1365 reports - together with the fully materializable control and
	 * the typed-proxy view of the resulting bodyless pointer.
	 */
	@Nested
	@DisplayName("Locale gate (P rows)")
	class LocaleGateTest {

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
			assertChain(standardParentChain(evita, 12), "P(11)");
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
			assertChain(standardParentChain(evita, 23), "B(22)");
		}

		/**
		 * Matrix row P3 - `34 -> 33 -> 32(cs) -> 31` under the standard requirement. The walk short-circuits
		 * at the first unmaterializable ancestor and discards the materializable root above it.
		 *
		 * The terminal `B(33)` reports `parentAvailable() == true`. Measured, that flag says only that
		 * hierarchy data was fetched for the ancestor -
		 * {@link io.evitadb.api.requestResponse.data.structure.EntityDecorator#parentAvailable()} is the
		 * stored flag combined with the hierarchy predicate, not a statement that a parent exists - so it
		 * is `true` on a genuine root as well (see the control row). It therefore does **not** separate
		 * this cut from a root either, which is what makes the ambiguity total.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P3: locale-less ancestor and the materializable root above it both disappear")
		@UseDataSet(DATA_SET)
		@Test
		void shouldDropLocaleLessAncestorAndEverythingAboveIt_P3(Evita evita) {
			final List<EntityClassifierWithParent> chain = standardParentChain(evita, 34);
			// 32 holds Czech data only and 31 is perfectly materializable, yet both are gone - the walk
			// short-circuits at the first missing body instead of stepping over it
			assertChain(chain, "B(33)");
			assertTrue(
				assertBody(chain.get(0), 33).parentAvailable(),
				"The cut chain still reports parentAvailable() on 33 today."
			);
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
			assertChain(standardParentChain(evita, 44), "P(43)");
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
			// 52 carries English data and would materialize, but it sits above the locale-less 53
			assertChain(standardParentChain(evita, 54), "P(53)");
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
			assertChain(standardParentChain(evita, 63), "P(62)");
		}

		/**
		 * Fixture control - `3 -> 2 -> 1`, every node materializable. Proves the fixture and the standard
		 * requirement return a full chain with bodies when nothing blocks the walk.
		 *
		 * The terminal `B(1)` is the genuine root, and it reports `parentAvailable() == true` all the
		 * same. That was measured rather than derived:
		 * {@link io.evitadb.api.requestResponse.data.structure.EntityDecorator#parentAvailable()} answers
		 * "was hierarchy data fetched for this entity", not "does this entity have a parent". The flag is
		 * therefore identical on a genuine root, on a `stopAt` cut and on a chain cut by an
		 * unmaterializable ancestor, so nothing on the raw API separates the three - which is the
		 * ambiguity the P and K rows keep running into.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("Control: a fully materializable chain is returned complete, with bodies")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReturnCompleteChainWithBodiesWhenEveryAncestorMaterializes_control(Evita evita) {
			final SealedEntity leaf = fetchLeaf(evita, 3, standardRequirement(), true).orElseThrow();
			assertEquals(expectedCode(3), leaf.getAttribute(ATTRIBUTE_CODE));

			final List<EntityClassifierWithParent> chain = parentChainOf(leaf);
			assertChain(chain, "B(2)", "B(1)");
			assertTrue(
				assertBody(chain.get(1), 1).parentAvailable(),
				"The genuine root 1 reports parentAvailable() today, exactly as a cut chain does."
			);
		}

		/**
		 * The typed-proxy view of matrix row P1. A `@ParentEntity` getter that declares an exception gets
		 * the rethrowing wrapper, so dereferencing the bodyless pointer at 11 surfaces the
		 * {@link ContextMissingException} the raw API hides behind an {@link EntityReferenceWithParent}. The
		 * control on the fully materializable 3 proves the interface really resolves a parent when one
		 * carries a body, so the throw cannot come from a proxy that never works at all.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P1 proxy: dereferencing a locale-less immediate parent throws ContextMissingException")
		@UseDataSet(DATA_SET)
		@Tag(PROXY)
		@Test
		void shouldThrowContextMissingWhenTypedProxyDereferencesLocaleLessImmediateParent_P1proxy(Evita evita) {
			final ParentDereferencingCategory localeLessParentHolder = fetchCategoryProxy(evita, 12);
			final ContextMissingException exception = assertThrows(
				ContextMissingException.class, localeLessParentHolder::getParentEntity
			);
			// the message blames the requirement even though `hierarchyContent(entityFetch(...))` was
			// present - the parent was reached, it simply carries no body
			assertEquals(
				"Parent entity was not fetched along with the entity. You need to use `hierarchyContent` " +
					"with `entityFetch` requirement in your `require` part of the query.",
				exception.getMessage()
			);

			final ParentDereferencingCategory control = fetchCategoryProxy(evita, 3);
			final ParentDereferencingCategory parent = control.getParentEntity();
			assertNotNull(parent, "The materializable parent 2 must be returned as a proxy.");
			assertEquals(2, parent.getId());
		}
	}

	/**
	 * Pins how the shape of the `hierarchyContent` requirement itself changes the returned chain - no
	 * ancestor bodies at all, a `stopAt` cut, no query locale, `dataInLocales` nested inside the ancestor
	 * `entityFetch`, and a request narrowed to the single global attribute the gated ancestor really
	 * holds.
	 *
	 * The last row in this group varies the schema rather than the requirement: it pins why the
	 * behaviour matrix has no row for a collection that declares no locale at all.
	 */
	@Nested
	@DisplayName("Requirement variations (N rows)")
	class RequirementVariationTest {

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
			// the ancestor axis itself is complete - the locale gate only fires once bodies are fetched
			assertChain(fetchParentChain(evita, 23, hierarchyContent(), true), "P(22)", "P(21)");
		}

		/**
		 * Matrix row N2 - `12 -> 11(cs)` with `stopAt(distance(1))`. Narrowing the walk to a single step
		 * changes nothing, because the only ancestor inside the bound is the unmaterializable 11: the
		 * locale gate still yields the very same bodyless pointer P1 returns.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("N2: stopAt(distance(1)) still yields the bodyless pointer for a locale-less parent")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReturnBodylessPointerUnderStopAtDistanceOne_N2(Evita evita) {
			assertChain(
				fetchParentChain(
					evita, 12, hierarchyContent(stopAt(distance(1)), entityFetch(attributeContentAll())), true
				),
				"P(11)"
			);
		}

		/**
		 * Matrix row N3 - `34 -> 33 -> 32(cs) -> 31` with `stopAt(distance(2))`. The bound would admit both
		 * 33 and 32, but the locale gate cuts at 32 before the bound is ever reached, so the chain is the
		 * single body P3 returns.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("N3: stopAt(distance(2)) drops the locale-less ancestor it would have reached")
		@UseDataSet(DATA_SET)
		@Test
		void shouldDropLocaleLessAncestorUnderStopAtDistanceTwo_N3(Evita evita) {
			assertChain(
				fetchParentChain(
					evita, 34, hierarchyContent(stopAt(distance(2)), entityFetch(attributeContentAll())), true
				),
				"B(33)"
			);
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
			// removing the single entityLocaleEquals line repairs the chain - the gate is the query locale
			assertChain(fetchParentChain(evita, 12, standardRequirement(), false), "B(11)");
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
			assertChain(
				fetchParentChain(
					evita, 12,
					hierarchyContent(entityFetch(attributeContentAll(), dataInLocales(Locale.ENGLISH))),
					true
				),
				"P(11)"
			);
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
			assertChain(chain, "B(11)");
			assertNull(assertBody(chain.get(0), 11).getAttribute(ATTRIBUTE_NAME, Locale.ENGLISH));
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
		void shouldDropImmediateParentEvenWhenOnlyGlobalAttributeRequested_P1variant(Evita evita) {
			// `code` is a global attribute the ancestor actually holds, and it is still refused
			assertChain(
				fetchParentChain(evita, 12, hierarchyContent(entityFetch(attributeContent(ATTRIBUTE_CODE))), true),
				"P(11)"
			);
		}

		/**
		 * Variant of matrix row P3 requesting only the global `code` attribute. Narrowing the request to
		 * an attribute the gated ancestor genuinely holds does not save it - the chain is cut at 33
		 * exactly as in P3, and the surviving 33 still delivers that attribute.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("P3 variant: requesting only a global attribute does not save a deeper ancestor")
		@UseDataSet(DATA_SET)
		@Test
		void shouldDropDeeperAncestorEvenWhenOnlyGlobalAttributeRequested_P3variant(Evita evita) {
			final List<EntityClassifierWithParent> chain = fetchParentChain(
				evita, 34, hierarchyContent(entityFetch(attributeContent(ATTRIBUTE_CODE))), true
			);
			assertChain(chain, "B(33)");
			// the narrowed requirement still delivers the global attribute on the ancestor that survives
			assertEquals(expectedCode(33), assertBody(chain.get(0), 33).getAttribute(ATTRIBUTE_CODE));
		}

		/**
		 * `stopAt(distance(1))` on the fully materializable control chain. The cut is reported exactly the
		 * way a genuine root is - an empty `getParentEntity()` - and `parentAvailable()` is `true` on
		 * both, so the raw API offers nothing that separates a requested cut from the end of a hierarchy.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("Control: stopAt(distance(1)) cuts a complete chain indistinguishably from a root")
		@UseDataSet(DATA_SET)
		@Test
		void shouldCutFullyMaterializableChainUnderStopAtDistanceOne_control(Evita evita) {
			final List<EntityClassifierWithParent> chain = fetchParentChain(
				evita, 3, hierarchyContent(stopAt(distance(1)), entityFetch(attributeContentAll())), true
			);
			assertChain(chain, "B(2)");
			assertTrue(
				assertBody(chain.get(0), 2).parentAvailable(),
				"The cut chain still reports parentAvailable() on 2 today."
			);
		}

		/**
		 * Pins why the behaviour matrix carries no non-localized row: a collection whose schema declares no
		 * locale matches nothing under a query-level `entityLocaleEquals`, so the queried entity itself is
		 * filtered out before its parents are ever considered. The control arm runs the identical query
		 * without the locale and gets the brand and its complete parent chain, so the empty result above
		 * cannot be produced by a missing fixture.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("Non-localized collection: a query-level locale filters out the queried entity itself")
		@UseDataSet(DATA_SET)
		@Test
		void shouldFilterOutNonLocalizedEntityItselfUnderQueryLocale_nonLocalizedSchema(Evita evita) {
			// the brand schema declares no locale, so nothing in it matches entityLocaleEquals - the parent
			// chain of a non-localized hierarchy is simply not reachable through a query-level locale filter
			final List<SealedEntity> localeFilteredBrands = fetchBrands(evita, 103, true);
			assertTrue(
				localeFilteredBrands.isEmpty(),
				"Expected no brand to match a query-level locale, but got: " + localeFilteredBrands
			);

			final List<SealedEntity> brands = fetchBrands(evita, 103, false);
			assertEquals(1, brands.size(), "The very same query without a locale must return the brand.");
			assertEquals(103, brands.get(0).getPrimaryKey());
			assertChain(parentChainOf(brands.get(0)), "B(102)", "B(101)");
		}
	}

	/**
	 * Pins how a chain broken by a deleted or never-created ancestor behaves. Whether the walk stays
	 * silent or throws depends on whether the vanished node had a parent of its own and on how far
	 * above the queried entity the break sits.
	 */
	@Nested
	@DisplayName("Broken chains (K rows)")
	class BrokenChainTest {

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
			// 71 no longer exists as an entity, yet the chain still points at it
			assertChain(standardParentChain(evita, 72), "P(71)");
		}

		/**
		 * Matrix row K2 - `83 -> 82 -> 81`, where 81 was a deleted root. Deleting a root never throws,
		 * because a removed root is never un-indexed and its children are never orphaned.
		 *
		 * The terminal `B(82)` reports `parentAvailable() == true`, which a genuine root does as well (see
		 * the control row), so neither observable separates this cut from the end of a hierarchy.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K2: a deleted root two levels up disappears silently")
		@UseDataSet(DATA_SET)
		@Test
		void shouldDropDeletedRootTwoLevelsUp_K2(Evita evita) {
			final List<EntityClassifierWithParent> chain = standardParentChain(evita, 83);
			assertChain(chain, "B(82)");
			assertTrue(
				assertBody(chain.get(0), 82).parentAvailable(),
				"The cut chain still reports parentAvailable() on 82 today."
			);
		}

		/**
		 * Variant of matrix row K2 - `94 -> 93 -> 92 -> 91`, where the deleted root 91 sits three levels
		 * up. The walk stays silent at depth three as well, which is what proves the throw of K4 needs the
		 * vanished node to have had a parent of its own rather than merely to sit two or more levels up.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K2 variant: a deleted root three levels up disappears silently as well")
		@UseDataSet(DATA_SET)
		@Test
		void shouldDropDeletedRootThreeLevelsUp_K2variant(Evita evita) {
			assertChain(standardParentChain(evita, 94), "B(93)", "B(92)");
		}

		/**
		 * Matrix row K3 - `123 -> 122 -> 121`, where the mid-chain 122 has been deleted and sits at the
		 * immediate-parent position. The deleted parent survives as a bodyless pointer and the chain ends
		 * there, exactly as a locale-less immediate parent does in P1.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K3: a deleted mid-chain immediate parent is returned as a bodyless pointer")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReturnBodylessPointerToDeletedMidChainParent_K3(Evita evita) {
			assertChain(standardParentChain(evita, 123), "P(122)");
		}

		/**
		 * Variant of matrix row K3 - `133 -> 132 -> 131`, where the deleted mid-chain 132 also held Czech
		 * data only. The chain is the same single bodyless pointer K3 returns.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K3 variant: a deleted Czech-only mid-chain parent is returned as a bodyless pointer")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReturnBodylessPointerToDeletedLocaleLessMidChainParent_K3variant(Evita evita) {
			assertChain(standardParentChain(evita, 133), "P(132)");
		}

		/**
		 * Variant of matrix row N1 measured on the broken chain of K3 - `123 -> 122 deleted` with a bare
		 * `hierarchyContent()`. Where N1 returns the whole primary-key axis, an orphaned subtree has no
		 * axis to report, so the same requirement yields a single pointer.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("N1 variant: on a broken chain a bare hierarchyContent yields a single pointer")
		@UseDataSet(DATA_SET)
		@Test
		void shouldReturnSinglePointerOnBrokenChainWhenNoBodiesRequested_N1variant(Evita evita) {
			assertChain(fetchParentChain(evita, 123, hierarchyContent(), true), "P(122)");
		}

		/**
		 * Matrix row K4 - `124 -> 123 -> 122 -> 121`, where the deleted mid-chain 122 sits exactly two
		 * levels above the queried entity. This is the one matrix row whose current outcome is an
		 * exception.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K4: a deleted mid-chain ancestor exactly two levels up makes the query throw")
		@UseDataSet(DATA_SET)
		@Test
		void shouldThrowWhenDeletedMidChainAncestorSitsTwoLevelsUp_K4(Evita evita) {
			// the pre-walk in HierarchyIndex#traverseHierarchyToRoot starts at the queried entity's parent,
			// so the assert only fires when the break sits exactly two levels above the queried entity
			assertBrokenChainThrows(() -> fetchLeaf(evita, 124, standardRequirement(), true), 122);
		}

		/**
		 * Variant of matrix row K4 with a bare `hierarchyContent()`. The pre-walk runs before any body is
		 * fetched, so dropping the ancestor `entityFetch` does not avoid the throw.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K4 variant: the throw survives when no ancestor bodies are requested")
		@UseDataSet(DATA_SET)
		@Test
		void shouldThrowWhenDeletedMidChainAncestorSitsTwoLevelsUpWithoutBodies_K4variant(Evita evita) {
			assertBrokenChainThrows(() -> fetchLeaf(evita, 124, hierarchyContent(), true), 122);
		}

		/**
		 * Variant of matrix row K4 with no query locale. The break is structural, so removing the locale
		 * that gates every P row leaves the throw untouched.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K4 variant: the throw survives when the query carries no locale")
		@UseDataSet(DATA_SET)
		@Test
		void shouldThrowWhenDeletedMidChainAncestorSitsTwoLevelsUpWithoutQueryLocale_K4variant(Evita evita) {
			assertBrokenChainThrows(() -> fetchLeaf(evita, 124, standardRequirement(), false), 122);
		}

		/**
		 * Variant of matrix row K4 on the Czech-only mid-chain deletion - `134 -> 133 -> 132 -> 131`. The
		 * deleted 132 held Czech data only, and the deletion path is unaffected by the ancestor's locale.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("K4 variant: a deleted Czech-only ancestor two levels up throws the same way")
		@UseDataSet(DATA_SET)
		@Test
		void shouldThrowWhenDeletedLocaleLessMidChainAncestorSitsTwoLevelsUp_K4variant(Evita evita) {
			assertBrokenChainThrows(() -> fetchLeaf(evita, 134, standardRequirement(), true), 132);
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
			// 124 carries English data and would materialize; three levels up the traversal is silent and
			// the body is lost all the same
			assertChain(standardParentChain(evita, 125), "P(124)");
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
			// the upsert that set parent 999 was accepted even though 999 never existed
			assertChain(standardParentChain(evita, 111), "P(999)");
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
			// the dangling-parent ingest path reaches the very same assert as K4, with no deletion involved
			assertBrokenChainThrows(() -> fetchLeaf(evita, 112, standardRequirement(), true), 999);
		}
	}

	/**
	 * Pins defects the behaviour matrix has no row for, because they are not shapes of a returned
	 * chain but states of the index behind it.
	 */
	@Nested
	@DisplayName("Defect pins")
	class DefectPinTest {

		/**
		 * A deleted root leaves a phantom node behind in the hierarchy index, so it still matches
		 * `hierarchyWithinRootSelf()` while resolving to no entity, and its descendants stay attached
		 * through it instead of becoming orphans. Folding the fix in is part of #1365 because it changes
		 * what a broken chain means to the parent fetcher; a mid-chain deletion, asserted here as the
		 * contrast, already orphans its subtree correctly.
		 *
		 * @param evita the embedded evitaDB instance provided by the test extension
		 */
		@DisplayName("Defect pin: a deleted root still matches hierarchyWithinRootSelf today")
		@UseDataSet(DATA_SET)
		@Test
		void shouldStillListDeletedRootInHierarchyToday_phantomRoot(Evita evita) {
			final List<EntityReferenceContract> hierarchyMemberReferences = evita.queryCatalog(
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
			final Set<Integer> hierarchyMembers = new HashSet<>(hierarchyMemberReferences.size() * 2);
			for (EntityReferenceContract reference : hierarchyMemberReferences) {
				hierarchyMembers.add(reference.getPrimaryKey());
			}

			final int[] phantomRoots = {71, 81, 91};
			// 71, 81 and 91 were deleted, yet they are still listed - EntityRemoveMutation emits
			// RemoveParentMutation only for an entity that has a parent, so a removed root is never
			// un-indexed and its descendants are never orphaned
			for (int phantomRoot : phantomRoots) {
				assertTrue(
					hierarchyMembers.contains(phantomRoot),
					"The deleted root " + phantomRoot + " is still attached to the hierarchy root today."
				);
			}
			for (int descendant : new int[]{72, 82, 83, 92, 93, 94}) {
				assertTrue(
					hierarchyMembers.contains(descendant),
					"Descendant " + descendant + " stays attached through the phantom root above it."
				);
			}

			// a mid-chain deletion behaves correctly - the deleted node leaves and its subtree is orphaned
			for (int deletedMidChainNode : new int[]{122, 132}) {
				assertFalse(
					hierarchyMembers.contains(deletedMidChainNode),
					"The deleted mid-chain node " + deletedMidChainNode + " must leave the hierarchy index."
				);
			}
			for (int orphan : new int[]{123, 124, 125, 133, 134}) {
				assertFalse(
					hierarchyMembers.contains(orphan),
					"Node " + orphan + " must be orphaned by the mid-chain deletion above it."
				);
			}

			// every one of the three phantom roots resolves to no entity at all
			for (int phantomRoot : phantomRoots) {
				final Optional<SealedEntity> deletedRoot = evita.queryCatalog(
					TEST_CATALOG,
					session -> {
						return session.queryOneSealedEntity(
							query(
								collection(Entities.CATEGORY),
								filterBy(entityPrimaryKeyInSet(phantomRoot)),
								require(entityFetch(attributeContentAll()))
							)
						);
					}
				);
				assertTrue(
					deletedRoot.isEmpty(),
					"The deleted root " + phantomRoot + " must not be fetchable, but was: " + deletedRoot
				);
			}
		}
	}

}
