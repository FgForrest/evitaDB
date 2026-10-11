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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.index.virtual.VirtualPartitionInvariants;
import io.evitadb.index.virtual.VirtualPartitionInvariants.RegisteredRow;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies `referenceHaving` whose body names the locale of the owner - a bare `entityLocaleEquals`, alone, negated or
 * inside an `or` with a reference attribute - against the answer the entity bodies give: an owner matches when one of
 * its references satisfies the body, and a bare `entityLocaleEquals` inside a reference body is satisfied by every
 * reference of an owner having data in that locale.
 *
 * The same history is written into two catalogs of one engine: one representing its single-owner partitions as
 * registry rows (the default for a new catalog) and one representing every partition by an index object. Each holds:
 *
 * | owner  | locales | `parts` row (target/type, order) | written | what it exercises                        |
 * |--------|---------|----------------------------------|---------|------------------------------------------|
 * | 1      | de, en  | 1/spare 50                       | warm-up | single-owner partition, no order below 3 |
 * | 2      | en      | 2/spare 1                        | warm-up | matched by its own order                 |
 * | 3, 4   | de / en | 3/spare 40, 60                   | warm-up | shared partition, no order below 3       |
 * | 5, 6   | en      | 4/accessory 70, 2                | warm-up | shared partition with an order below 3   |
 * | 7      | de, en  | 4/spare 90                       | warm-up | sibling of 5 and 6, archived once live   |
 * | 8      | en      | 5/spare 1                        | warm-up | single-owner partition, order below 3    |
 * | 9      | de, en  | 5/accessory 50                   | warm-up | sibling of 8, written after it           |
 * | 11     | en      | 8/spare 1                        | warm-up | matched by its own order, archived       |
 * | 13, 14 | en      | 7/accessory 2, 75                | warm-up | shared partition with an order below 3   |
 * | 10     | de, en  | 6/spare 30                       | live    | single-owner partition, no order below 3 |
 * | 12     | de, en  | 7/spare 60                       | live    | sibling of 13 and 14, archived later     |
 *
 * The relation type is the representative attribute of the reference, so the partitions of one target and different
 * relation types are siblings: they share the target and hold different owners.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("referenceHaving with the locale of the owner in its body")
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(REFERENCE)
@TestInstance(Lifecycle.PER_CLASS)
class ReferenceHavingEntityLocaleFunctionalTest implements EvitaTestSupport {
	private static final String CATALOG_VIRTUAL = "referenceHavingEntityLocaleVirtual";
	private static final String CATALOG_OBJECTS = "referenceHavingEntityLocaleObjects";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_PART = "part";
	private static final String REFERENCE_PARTS = "parts";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_RELATION_TYPE = "relationType";
	private static final String ATTRIBUTE_ORDER = "order";
	private static final String SPARE = "spare";
	private static final String ACCESSORY = "accessory";
	private static final int ORDER_LIMIT = 3;
	private static final Scope[] BOTH_SCOPES = {Scope.LIVE, Scope.ARCHIVED};
	/**
	 * The owners archived once the catalog is live - two written in the warm-up, one in a live transaction.
	 */
	private static final int[] ARCHIVED_OWNERS = {7, 11, 12};

	private TestPaths paths;
	private Evita evita;

	/**
	 * The representation of the single-owner partitions of a catalog.
	 */
	enum Representation {
		/** a single-owner partition is a registry row, a shared one an index object */
		ROWS(CATALOG_VIRTUAL),
		/** every partition is an index object */
		OBJECTS(CATALOG_OBJECTS);

		/**
		 * The catalog holding the representation.
		 */
		private final String catalog;

		Representation(@Nonnull String catalog) {
			this.catalog = catalog;
		}
	}

	/**
	 * The body of the `referenceHaving` and its meaning for one reference of an owner.
	 */
	enum Body {
		/** a bare `entityLocaleEquals` - the owner has data in German */
		GERMAN_OWNER {
			@Nonnull
			@Override
			FilterConstraint constraint() {
				return entityLocaleEquals(Locale.GERMAN);
			}

			@Override
			boolean isSatisfiedBy(@Nonnull SealedEntity owner, @Nonnull ReferenceContract reference) {
				return isGerman(owner);
			}
		},
		/** a negated `entityLocaleEquals` - the owner has no data in German */
		NOT_GERMAN_OWNER {
			@Nonnull
			@Override
			FilterConstraint constraint() {
				return not(entityLocaleEquals(Locale.GERMAN));
			}

			@Override
			boolean isSatisfiedBy(@Nonnull SealedEntity owner, @Nonnull ReferenceContract reference) {
				return !isGerman(owner);
			}
		},
		/** a reference attribute comparison - the order of the reference is below the limit */
		LOW_ORDER {
			@Nonnull
			@Override
			FilterConstraint constraint() {
				return attributeLessThan(ATTRIBUTE_ORDER, ORDER_LIMIT);
			}

			@Override
			boolean isSatisfiedBy(@Nonnull SealedEntity owner, @Nonnull ReferenceContract reference) {
				return isLowOrder(reference);
			}
		},
		/** the disjunction of both */
		GERMAN_OWNER_OR_LOW_ORDER {
			@Nonnull
			@Override
			FilterConstraint constraint() {
				return or(entityLocaleEquals(Locale.GERMAN), attributeLessThan(ATTRIBUTE_ORDER, ORDER_LIMIT));
			}

			@Override
			boolean isSatisfiedBy(@Nonnull SealedEntity owner, @Nonnull ReferenceContract reference) {
				return isGerman(owner) || isLowOrder(reference);
			}
		};

		/**
		 * Returns the body.
		 *
		 * @return the constraint placed inside `referenceHaving`
		 */
		@Nonnull
		abstract FilterConstraint constraint();

		/**
		 * Returns TRUE when the reference of the owner satisfies the body.
		 *
		 * @param owner     the body of the owner
		 * @param reference one of its references
		 * @return TRUE when the reference satisfies the body
		 */
		abstract boolean isSatisfiedBy(@Nonnull SealedEntity owner, @Nonnull ReferenceContract reference);
	}

	/**
	 * Returns every combination of the representation, the queried scopes and the body.
	 *
	 * @return the arguments of {@link #shouldMatchOwnersHavingAReferenceSatisfyingTheBody}
	 */
	@Nonnull
	static Stream<Arguments> cases() {
		final Scope[][] scopeSets = {{Scope.LIVE}, {Scope.ARCHIVED}, BOTH_SCOPES};
		final List<Arguments> result = new ArrayList<>(
			Representation.values().length * scopeSets.length * Body.values().length
		);
		for (final Representation representation : Representation.values()) {
			for (final Scope[] scopes : scopeSets) {
				for (final Body body : Body.values()) {
					result.add(Arguments.of(representation, scopes, body));
				}
			}
		}
		return result.stream();
	}

	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("ReferenceHavingEntityLocaleFunctionalTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		// TODO JNO - remove at the end of #1615
		this.evita.createCatalogWithVirtualReferencePartitions(CATALOG_OBJECTS, false);
		for (final Representation representation : Representation.values()) {
			final String catalog = representation.catalog;
			this.evita.defineCatalog(catalog).updateViaNewSession(this.evita);
			this.evita.updateCatalog(catalog, ReferenceHavingEntityLocaleFunctionalTest::warmUp);
			this.evita.updateCatalog(catalog, EvitaSessionContract::goLiveAndClose);
			this.evita.updateCatalog(
				catalog,
				session -> {
					session.upsertEntity(newProduct(session, 10, true, 6, SPARE, 30));
					session.upsertEntity(newProduct(session, 12, true, 7, SPARE, 60));
				}
			);
			this.evita.updateCatalog(
				catalog,
				session -> {
					for (final int owner : ARCHIVED_OWNERS) {
						session.archiveEntity(ENTITY_PRODUCT, owner);
					}
				}
			);
		}
	}

	@AfterAll
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("represents the single-owner partitions of the default catalog as rows and shared ones as objects")
	void shouldHoldRowsAndObjectsInTheDefaultCatalog() {
		final Collection<RegisteredRow> rows = VirtualPartitionInvariants.registeredRowsOf(
			VirtualPartitionInvariants.collectionOf(this.evita, CATALOG_VIRTUAL, ENTITY_PRODUCT)
		).values();
		// the single-owner partitions of owners 1 and 8 are rows, the partition shared by 5 and 6 is not
		assertTrue(rows.stream().anyMatch(it -> it.owner() == 1 && it.scope() == Scope.LIVE), "rows: " + rows);
		assertTrue(rows.stream().anyMatch(it -> it.owner() == 8 && it.scope() == Scope.LIVE), "rows: " + rows);
		assertFalse(rows.stream().anyMatch(it -> it.owner() == 5 || it.owner() == 6), "rows: " + rows);
	}

	@ParameterizedTest(name = "{0} {1} {2}")
	@MethodSource("cases")
	@DisplayName("matches exactly the owners having a reference that satisfies the body")
	void shouldMatchOwnersHavingAReferenceSatisfyingTheBody(
		@Nonnull Representation representation,
		@Nonnull Scope[] scopes,
		@Nonnull Body body
	) {
		final Set<Integer> expected = new TreeSet<>();
		final Set<Scope> queriedScopes = Set.of(scopes);
		for (final SealedEntity owner : readOwners(representation.catalog)) {
			if (queriedScopes.contains(owner.getScope()) &&
				owner.getReferences(REFERENCE_PARTS).stream().anyMatch(it -> body.isSatisfiedBy(owner, it))
			) {
				expected.add(owner.getPrimaryKeyOrThrowException());
			}
		}
		assertFalse(expected.isEmpty(), "the fixture must hold a matching owner for every case");
		final FilterConstraint filter = referenceHaving(REFERENCE_PARTS, body.constraint());
		final Set<Integer> actual = new TreeSet<>(
			this.evita.queryCatalog(
				representation.catalog,
				(Function<EvitaSessionContract, List<Integer>>) session -> session.query(
					query(collection(ENTITY_PRODUCT), filterBy(scope(scopes), filter), require(page(1, 100))),
					EntityReference.class
				).getRecordData().stream().map(EntityReference::getPrimaryKey).toList()
			)
		);
		final Set<Integer> missing = new TreeSet<>(expected);
		missing.removeAll(actual);
		final Set<Integer> extra = new TreeSet<>(actual);
		extra.removeAll(expected);
		assertEquals(
			expected, actual,
			filter + " in " + List.of(scopes) + " of the " + representation + " catalog: missing " + missing +
				", extra " + extra
		);
	}

	/**
	 * Defines the schema and writes the owners of the warm-up - see the class documentation for their order and
	 * purpose.
	 *
	 * @param session the warm-up session
	 */
	private static void warmUp(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withLocale(Locale.ENGLISH, Locale.GERMAN)
			.withAttribute(ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().nullable())
			.withReferenceTo(
				REFERENCE_PARTS, ENTITY_PART, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				whichIs -> whichIs
					.indexedForFilteringInScope(BOTH_SCOPES)
					.withAttribute(
						ATTRIBUTE_RELATION_TYPE, String.class,
						thatIs -> thatIs.filterableInScope(BOTH_SCOPES).representative()
					)
					.withAttribute(
						ATTRIBUTE_ORDER, Integer.class, thatIs -> thatIs.filterableInScope(BOTH_SCOPES).nullable()
					)
			)
			.updateVia(session);
		session.upsertEntity(newProduct(session, 1, true, 1, SPARE, 50));
		session.upsertEntity(newProduct(session, 2, false, 2, SPARE, 1));
		session.upsertEntity(newProduct(session, 3, true, 3, SPARE, 40));
		session.upsertEntity(newProduct(session, 4, false, 3, SPARE, 60));
		session.upsertEntity(newProduct(session, 5, false, 4, ACCESSORY, 70));
		session.upsertEntity(newProduct(session, 6, false, 4, ACCESSORY, 2));
		session.upsertEntity(newProduct(session, 7, true, 4, SPARE, 90));
		session.upsertEntity(newProduct(session, 8, false, 5, SPARE, 1));
		session.upsertEntity(newProduct(session, 9, true, 5, ACCESSORY, 50));
		session.upsertEntity(newProduct(session, 11, false, 8, SPARE, 1));
		session.upsertEntity(newProduct(session, 13, false, 7, ACCESSORY, 2));
		session.upsertEntity(newProduct(session, 14, false, 7, ACCESSORY, 75));
	}

	/**
	 * Creates an owner with an English name, a German one when asked, and one reference.
	 *
	 * @param session      the session to create the owner in
	 * @param primaryKey   the primary key of the owner
	 * @param german       TRUE for an owner having data in German
	 * @param target       the referenced part
	 * @param relationType the representative relation type of the reference
	 * @param order        the order of the reference
	 * @return the builder of the owner
	 */
	@Nonnull
	private static EntityBuilder newProduct(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		boolean german,
		int target,
		@Nonnull String relationType,
		int order
	) {
		final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, primaryKey)
			.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "product " + primaryKey);
		if (german) {
			product.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Produkt " + primaryKey);
		}
		product.setOrUpdateReference(
			REFERENCE_PARTS, target, Functions.alwaysFalse(),
			ref -> {
				ref.setAttribute(ATTRIBUTE_RELATION_TYPE, relationType);
				ref.setAttribute(ATTRIBUTE_ORDER, order);
			}
		);
		return product;
	}

	/**
	 * Reads the bodies of every owner of both scopes with their locales and references.
	 *
	 * @param catalog the catalog to read
	 * @return the owners
	 */
	@Nonnull
	private List<SealedEntity> readOwners(@Nonnull String catalog) {
		return this.evita.queryCatalog(
			catalog,
			(Function<EvitaSessionContract, List<SealedEntity>>) session -> session.querySealedEntity(
				query(
					collection(ENTITY_PRODUCT),
					filterBy(scope(BOTH_SCOPES)),
					require(
						page(1, 100),
						entityFetch(
							attributeContentAll(), referenceContentWithAttributes(REFERENCE_PARTS), dataInLocalesAll()
						)
					)
				)
			).getRecordData()
		);
	}

	/**
	 * Returns TRUE when the owner has data in German.
	 *
	 * @param owner the body of the owner
	 * @return TRUE for a German owner
	 */
	private static boolean isGerman(@Nonnull SealedEntity owner) {
		return owner.getAllLocales().contains(Locale.GERMAN);
	}

	/**
	 * Returns TRUE when the order of the reference is below {@link #ORDER_LIMIT}.
	 *
	 * @param reference the reference
	 * @return TRUE for an order below the limit
	 */
	private static boolean isLowOrder(@Nonnull ReferenceContract reference) {
		final Integer order = reference.getAttribute(ATTRIBUTE_ORDER);
		return order != null && order < ORDER_LIMIT;
	}

}
