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

package io.evitadb.api.functional;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.RemoveAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.data.mutation.reference.RemoveReferenceMutation;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies one entity upsert that changes the scope of an existing entity more than once - back to where it started,
 * there and back and there again, or to the same scope twice - with or without other local mutations between the
 * scope changes. Every scope change must find the entity where the previous one left it, together with everything
 * the mutations before it changed, so the entity ends up indexed in exactly the scope its body names, under the
 * attribute values, locales and references it has after the upsert, and in no index of the other scope. The same
 * must hold after the engine restarts on the stored data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Changing the scope of an entity several times in one mutation")
@Tag(CONTRACT)
@Tag(INDEXING)
class RepeatedScopeChangeFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String REFERENCE_BRAND = "brand";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ORIGINAL_CODE = "A";
	private static final String CHANGED_CODE = "B";
	private static final Locale CZECH = Locale.forLanguageTag("cs");
	private static final Locale[] ALL_LOCALES = {CZECH, Locale.ENGLISH, Locale.FRENCH};
	private static final Scope[] ALL_SCOPES = {Scope.LIVE, Scope.ARCHIVED};
	private static final int PRODUCT_PK = 1;
	private static final int SHARING_PRODUCT_PK = 2;
	private static final int BRAND_PK = 10;

	/**
	 * The local mutations of the probed upsert, given relative to the scope the product starts in (`origin`) and the
	 * other one (`other`).
	 */
	enum Shape {
		/** `other`, then `origin` */
		THERE_AND_BACK,
		/** `other`, the code changed, then `origin` */
		THERE_AND_BACK_CHANGING_ATTRIBUTE,
		/** `other`, the first value of a new locale, then `origin` */
		THERE_AND_BACK_ADDING_LOCALE,
		/** `other`, the last value of a locale removed, then `origin` */
		THERE_AND_BACK_REMOVING_LOCALE,
		/** `other`, the reference removed, then `origin` */
		THERE_AND_BACK_REMOVING_REFERENCE,
		/** `other`, `origin`, then `other` again */
		THERE_BACK_AND_THERE,
		/** `other` twice in a row */
		THERE_TWICE
	}

	/**
	 * The state of the product the probed upsert must leave behind.
	 *
	 * @param scope      the scope the product ends in
	 * @param code       the value of the code attribute the product ends with
	 * @param locales    the locales the product ends with
	 * @param referenced TRUE when the product ends with its reference to the brand
	 */
	private record Expectation(
		@Nonnull Scope scope,
		@Nonnull String code,
		@Nonnull Set<Locale> locales,
		boolean referenced
	) {
	}

	/**
	 * Every combination of the catalog state, the scope the product starts in and the shape of the upsert.
	 *
	 * @return arguments of {@link #shouldIndexEntityInTheScopeItEndsInWhenScopeIsChangedRepeatedly}
	 */
	@Nonnull
	static Stream<Arguments> variants() {
		return combinationsOf(Shape.values());
	}

	/**
	 * Every combination of the catalog state and the scope the products start in with the shapes that change the scope
	 * only and bring the product back to the partition it shares at least once.
	 *
	 * @return arguments of {@link #shouldKeepSharedReferencePartitionWhenScopeIsChangedRepeatedly}
	 */
	@Nonnull
	static Stream<Arguments> sharedPartitionVariants() {
		return combinationsOf(Shape.THERE_AND_BACK, Shape.THERE_BACK_AND_THERE);
	}

	/**
	 * Combines both catalog states and both scopes the product may start in with each of `shapes`.
	 *
	 * @param shapes the shapes of the upsert to combine
	 * @return the arguments: the catalog state, the scope the product starts in and the shape
	 */
	@Nonnull
	private static Stream<Arguments> combinationsOf(@Nonnull Shape... shapes) {
		final List<Arguments> result = new ArrayList<>(2 * ALL_SCOPES.length * shapes.length);
		for (CatalogState state : new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE}) {
			for (Scope origin : ALL_SCOPES) {
				for (Shape shape : shapes) {
					result.add(Arguments.of(state, origin, shape));
				}
			}
		}
		return result.stream();
	}

	/**
	 * Applies the upsert of `shape` to the product stored in `origin` as one entity mutation and checks the stored
	 * body and the indexes of both scopes against the state the upsert must leave behind - on the engine that applied
	 * it, and again on an engine restarted on the stored data, so the indexes loaded from disk are held to the same
	 * expectation as the ones kept in memory.
	 *
	 * @param state  the state the catalog is in when the upsert is applied
	 * @param origin the scope the product starts in
	 * @param shape  the shape of the upsert
	 */
	@ParameterizedTest(name = "{0} from {1} {2}")
	@MethodSource("variants")
	@DisplayName("the entity is indexed only in the scope it ends in, with the state it ends with")
	void shouldIndexEntityInTheScopeItEndsInWhenScopeIsChangedRepeatedly(
		@Nonnull CatalogState state,
		@Nonnull Scope origin,
		@Nonnull Shape shape
	) {
		final TestPaths paths = createTestPaths("RepeatedScopeChange_" + state + "_" + origin + "_" + shape);
		final Scope other = origin == Scope.LIVE ? Scope.ARCHIVED : Scope.LIVE;
		final Expectation expectation = expectationOf(shape, origin, other);
		try {
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					setUpProduct(session, origin);
					if (state == CatalogState.ALIVE) {
						session.goLiveAndClose();
					}
				});

				assertDoesNotThrow(
					() -> evita.updateCatalog(
						TEST_CATALOG,
						(Consumer<EvitaSessionContract>) session -> session.upsertEntity(
							new EntityUpsertMutation(
								ENTITY_PRODUCT, PRODUCT_PK, EntityExistence.MUST_EXIST,
								mutationsOf(shape, origin, other)
							)
						)
					)
				);

				evita.queryCatalog(TEST_CATALOG, session -> {
					assertProductState(session, expectation);
				});
			}

			// the indexes the engine loads on restart must agree with the ones it kept in memory
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				evita.waitUntilFullyInitialized();
				evita.queryCatalog(TEST_CATALOG, session -> {
					assertProductState(session, expectation);
				});
			}
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Moves the product away from the reference partition it shares with a second product, which stays in `origin`,
	 * and back again within one upsert. The partition of `origin` loses one of its two owners with every move away and
	 * regains it with every move back, so how many owners it is left with is known only once the whole upsert is
	 * applied. Each product must end up answering the reference and the facet in exactly the scope it ends in, also
	 * after the engine restarts on the stored data.
	 *
	 * @param state  the state the catalog is in when the upsert is applied
	 * @param origin the scope both products start in
	 * @param shape  the shape of the upsert, which changes the scope of the product only
	 */
	@ParameterizedTest(name = "{0} from {1} {2}")
	@MethodSource("sharedPartitionVariants")
	@DisplayName("a reference partition shared with another entity keeps exactly the owners that end in its scope")
	void shouldKeepSharedReferencePartitionWhenScopeIsChangedRepeatedly(
		@Nonnull CatalogState state,
		@Nonnull Scope origin,
		@Nonnull Shape shape
	) {
		final TestPaths paths = createTestPaths("RepeatedScopeChangeShared_" + state + "_" + origin + "_" + shape);
		final Scope other = origin == Scope.LIVE ? Scope.ARCHIVED : Scope.LIVE;
		final Scope productScope = expectationOf(shape, origin, other).scope();
		try {
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					setUpProductsSharingBrand(session, origin);
					if (state == CatalogState.ALIVE) {
						session.goLiveAndClose();
					}
				});

				assertDoesNotThrow(
					() -> evita.updateCatalog(
						TEST_CATALOG,
						(Consumer<EvitaSessionContract>) session -> session.upsertEntity(
							new EntityUpsertMutation(
								ENTITY_PRODUCT, PRODUCT_PK, EntityExistence.MUST_EXIST,
								mutationsOf(shape, origin, other)
							)
						)
					)
				);

				evita.queryCatalog(TEST_CATALOG, session -> {
					assertSharedPartitionState(session, productScope, origin);
				});
			}

			// the indexes the engine loads on restart must agree with the ones it kept in memory
			try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				evita.waitUntilFullyInitialized();
				evita.queryCatalog(TEST_CATALOG, session -> {
					assertSharedPartitionState(session, productScope, origin);
				});
			}
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Builds the local mutations of the probed upsert.
	 *
	 * @param shape  the shape of the upsert
	 * @param origin the scope the product starts in
	 * @param other  the other scope
	 * @return the local mutations, in the order they are applied
	 */
	@Nonnull
	private static List<LocalMutation<?, ?>> mutationsOf(
		@Nonnull Shape shape,
		@Nonnull Scope origin,
		@Nonnull Scope other
	) {
		final LocalMutation<?, ?> toOther = new SetEntityScopeMutation(other);
		final LocalMutation<?, ?> toOrigin = new SetEntityScopeMutation(origin);
		return switch (shape) {
			case THERE_AND_BACK -> List.of(toOther, toOrigin);
			case THERE_AND_BACK_CHANGING_ATTRIBUTE -> List.of(
				toOther, new UpsertAttributeMutation(ATTRIBUTE_CODE, CHANGED_CODE), toOrigin
			);
			case THERE_AND_BACK_ADDING_LOCALE -> List.of(
				toOther, new UpsertAttributeMutation(ATTRIBUTE_NAME, Locale.FRENCH, "Produit"), toOrigin
			);
			case THERE_AND_BACK_REMOVING_LOCALE -> List.of(
				toOther, new RemoveAttributeMutation(ATTRIBUTE_NAME, Locale.ENGLISH), toOrigin
			);
			case THERE_AND_BACK_REMOVING_REFERENCE -> List.of(
				toOther, new RemoveReferenceMutation(REFERENCE_BRAND, BRAND_PK), toOrigin
			);
			case THERE_BACK_AND_THERE -> List.of(toOther, toOrigin, new SetEntityScopeMutation(other));
			case THERE_TWICE -> List.of(toOther, new SetEntityScopeMutation(other));
		};
	}

	/**
	 * Returns the state of the product the upsert of `shape` must leave behind.
	 *
	 * @param shape  the shape of the upsert
	 * @param origin the scope the product starts in
	 * @param other  the other scope
	 * @return the expected state
	 */
	@Nonnull
	private static Expectation expectationOf(@Nonnull Shape shape, @Nonnull Scope origin, @Nonnull Scope other) {
		final Set<Locale> locales = new LinkedHashSet<>(List.of(CZECH, Locale.ENGLISH));
		if (shape == Shape.THERE_AND_BACK_ADDING_LOCALE) {
			locales.add(Locale.FRENCH);
		} else if (shape == Shape.THERE_AND_BACK_REMOVING_LOCALE) {
			locales.remove(Locale.ENGLISH);
		}
		return new Expectation(
			shape == Shape.THERE_BACK_AND_THERE || shape == Shape.THERE_TWICE ? other : origin,
			shape == Shape.THERE_AND_BACK_CHANGING_ATTRIBUTE ? CHANGED_CODE : ORIGINAL_CODE,
			locales,
			shape != Shape.THERE_AND_BACK_REMOVING_REFERENCE
		);
	}

	/**
	 * Asserts that the stored product matches `expectation` and that the indexes of every scope agree with it: the
	 * scope the product ends in answers for its primary key, its code, each of its locales and its reference, the
	 * other scope answers for none of them, and no scope answers for a value the product no longer has.
	 *
	 * @param session     the session to query
	 * @param expectation the expected state of the product
	 */
	private static void assertProductState(@Nonnull EvitaSessionContract session, @Nonnull Expectation expectation) {
		final SealedEntity product = session.getEntity(
			ENTITY_PRODUCT, PRODUCT_PK, ALL_SCOPES, entityFetchAllContent()
		).orElseThrow();
		assertEquals(expectation.scope(), product.getScope(), "scope of the stored body");
		assertEquals(expectation.code(), product.getAttribute(ATTRIBUTE_CODE), "code of the stored body");
		assertEquals(expectation.locales(), product.getLocales(), "locales of the stored body");
		assertEquals(
			expectation.referenced(), product.getReference(REFERENCE_BRAND, BRAND_PK).isPresent(),
			"reference of the stored body"
		);

		final String staleCode = ORIGINAL_CODE.equals(expectation.code()) ? CHANGED_CODE : ORIGINAL_CODE;
		for (Scope scope : ALL_SCOPES) {
			final int present = scope == expectation.scope() ? 1 : 0;
			assertEquals(present, count(session, entityPrimaryKeyInSet(PRODUCT_PK), scope), scope + ": primary key");
			assertEquals(
				present, count(session, attributeEquals(ATTRIBUTE_CODE, expectation.code()), scope),
				scope + ": code " + expectation.code()
			);
			assertEquals(
				0, count(session, attributeEquals(ATTRIBUTE_CODE, staleCode), scope),
				scope + ": code " + staleCode
			);
			for (Locale locale : ALL_LOCALES) {
				assertEquals(
					expectation.locales().contains(locale) ? present : 0,
					count(session, entityLocaleEquals(locale), scope),
					scope + ": locale " + locale
				);
			}
			assertEquals(
				expectation.referenced() ? present : 0,
				count(session, referenceHaving(REFERENCE_BRAND, entityPrimaryKeyInSet(BRAND_PK)), scope),
				scope + ": reference"
			);
		}
	}

	/**
	 * Counts the products the indexes of `scope` answer for `constraint`.
	 *
	 * @param session    the session to query
	 * @param constraint the constraint to filter by
	 * @param scope      the scope to query
	 * @return the number of products found
	 */
	private static int count(
		@Nonnull EvitaSessionContract session,
		@Nonnull FilterConstraint constraint,
		@Nonnull Scope scope
	) {
		return session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(constraint, scope(scope))
			),
			EntityReference.class
		).getTotalRecordCount();
	}

	/**
	 * Defines the schemas, every component of which is indexed in both scopes, a brand, and the product with its
	 * original code, Czech and English names and a reference to the brand, stored in `origin`.
	 *
	 * @param session the session of the catalog in warm-up
	 * @param origin  the scope the product starts in
	 */
	private static void setUpProduct(@Nonnull EvitaSessionContract session, @Nonnull Scope origin) {
		session.defineEntitySchema(ENTITY_BRAND).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withLocale(CZECH, Locale.ENGLISH, Locale.FRENCH)
			.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.filterableInScope(ALL_SCOPES))
			.withAttribute(
				ATTRIBUTE_NAME, String.class,
				whichIs -> whichIs.localized().nullable().filterableInScope(ALL_SCOPES)
			)
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringInScope(ALL_SCOPES)
			)
			.updateVia(session);

		session.upsertEntity(session.createNewEntity(ENTITY_BRAND, BRAND_PK));
		session.createNewEntity(ENTITY_PRODUCT, PRODUCT_PK)
			.setAttribute(ATTRIBUTE_CODE, ORIGINAL_CODE)
			.setAttribute(ATTRIBUTE_NAME, CZECH, "Produkt")
			.setAttribute(ATTRIBUTE_NAME, Locale.ENGLISH, "Product")
			.setReference(REFERENCE_BRAND, BRAND_PK)
			.setScope(origin)
			.upsertVia(session);
	}

	/**
	 * Asserts that the stored product ends in `productScope` and that the indexes of every scope answer for each of
	 * the two products sharing the brand - for its primary key, its reference and its facet - in exactly the scope
	 * the product ends in. The reference and the facet of the brand are also counted without narrowing to one owner,
	 * because a scope that no longer holds an owner answers for none of its primary keys, and an owner left behind
	 * in the partition or the facet index of that scope would go unnoticed otherwise.
	 *
	 * @param session             the session to query
	 * @param productScope        the scope the probed product ends in
	 * @param sharingProductScope the scope the product sharing the partition stays in
	 */
	private static void assertSharedPartitionState(
		@Nonnull EvitaSessionContract session,
		@Nonnull Scope productScope,
		@Nonnull Scope sharingProductScope
	) {
		final SealedEntity product = session.getEntity(
			ENTITY_PRODUCT, PRODUCT_PK, ALL_SCOPES, entityFetchAllContent()
		).orElseThrow();
		assertEquals(productScope, product.getScope(), "scope of the stored body");
		assertOwnerIndexedOnlyIn(session, PRODUCT_PK, productScope);
		assertOwnerIndexedOnlyIn(session, SHARING_PRODUCT_PK, sharingProductScope);

		final FilterConstraint isBrand = entityPrimaryKeyInSet(BRAND_PK);
		for (Scope scope : ALL_SCOPES) {
			final int owners = (scope == productScope ? 1 : 0) + (scope == sharingProductScope ? 1 : 0);
			assertEquals(
				owners, count(session, referenceHaving(REFERENCE_BRAND, isBrand), scope),
				scope + ": owners of the reference"
			);
			assertEquals(
				owners, count(session, facetHaving(REFERENCE_BRAND, isBrand), scope),
				scope + ": owners of the facet"
			);
		}
	}

	/**
	 * Asserts that the indexes of `ownerScope` answer for the product `ownerPk` - its primary key, its reference to
	 * the brand and its brand facet - and the indexes of the other scope for none of them.
	 *
	 * @param session    the session to query
	 * @param ownerPk    the primary key of the product
	 * @param ownerScope the scope the product ends in
	 */
	private static void assertOwnerIndexedOnlyIn(
		@Nonnull EvitaSessionContract session,
		int ownerPk,
		@Nonnull Scope ownerScope
	) {
		final FilterConstraint isOwner = entityPrimaryKeyInSet(ownerPk);
		final FilterConstraint isBrand = entityPrimaryKeyInSet(BRAND_PK);
		for (Scope scope : ALL_SCOPES) {
			final int present = scope == ownerScope ? 1 : 0;
			final String owner = scope + ": product " + ownerPk;
			assertEquals(present, count(session, isOwner, scope), owner + ", primary key");
			assertEquals(
				present, count(session, and(isOwner, referenceHaving(REFERENCE_BRAND, isBrand)), scope),
				owner + ", reference"
			);
			assertEquals(
				present, count(session, and(isOwner, facetHaving(REFERENCE_BRAND, isBrand)), scope),
				owner + ", facet"
			);
		}
	}

	/**
	 * Defines the schemas, with the reference to the brand indexed for filtering and faceted in both scopes, a brand,
	 * and two products referencing it, both stored in `origin`, so the partition of the brand in `origin` has two
	 * owners.
	 *
	 * @param session the session of the catalog in warm-up
	 * @param origin  the scope both products start in
	 */
	private static void setUpProductsSharingBrand(@Nonnull EvitaSessionContract session, @Nonnull Scope origin) {
		session.defineEntitySchema(ENTITY_BRAND).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringInScope(ALL_SCOPES).facetedInScope(ALL_SCOPES)
			)
			.updateVia(session);

		session.upsertEntity(session.createNewEntity(ENTITY_BRAND, BRAND_PK));
		for (int productPk : new int[]{PRODUCT_PK, SHARING_PRODUCT_PK}) {
			session.createNewEntity(ENTITY_PRODUCT, productPk)
				.setReference(REFERENCE_BRAND, BRAND_PK)
				.setScope(origin)
				.upsertVia(session);
		}
	}

}
