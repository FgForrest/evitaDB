/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.EvitaRequest.ReferenceContentKey;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.dataType.Scope;
import io.evitadb.driver.EvitaClient;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.test.tester.GraphQLTester;
import io.evitadb.test.tester.RestTester;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.DESC;
import static io.evitadb.api.query.require.ManagedReferencesBehaviour.ANY;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises independent and inherited locales with values selecting opposite targets in Czech and English.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("One locale per entity level")
@ExtendWith(EvitaParameterResolver.class)
@Tag(ENGINE)
@Tag(QUERY)
@Tag(REFERENCE)
public class EntityLevelLocaleFunctionalTest {
	private static final String DATA_SET = "entityLevelLocales";
	private static final Locale CS = Locale.forLanguageTag("cs");

	/**
	 * Target 1 matches only in English, target 2 only in Czech. Owner 3 references both, owner 4 has only English.
	 * Both target collections share those values, and their ordering reverses between the locales.
	 */
	@DataSet(value = DATA_SET, openWebApi = {
		GraphQLProvider.CODE, RestProvider.CODE, GrpcProvider.CODE, SystemProvider.CODE
	})
	@Nonnull
	DataCarrier setUp(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			for (final String type : new String[]{"Brand", "Store", "Category"}) {
				session.defineEntitySchema(type).withoutGeneratedPrimaryKey().withHierarchy()
					.withLocale(CS, Locale.ENGLISH)
					.withAttribute("match", String.class, thatIs -> thatIs.localized().filterable().nullable())
					.withAttribute("name", String.class, thatIs -> thatIs.localized().sortable())
					.updateAndFetchVia(session);
				for (int pk = 1; pk <= 4; pk++) {
					final EntityBuilder builder = session.createNewEntity(type, pk)
						.setAttribute("match", Locale.ENGLISH, pk % 2 == 1 ? "yes" : "no")
						.setAttribute("match", CS, pk % 2 == 1 ? "no" : "yes")
						.setAttribute("name", Locale.ENGLISH, pk % 2 == 1 ? "Alpha" : "Zulu")
						.setAttribute("name", CS, pk % 2 == 1 ? "Zulu" : "Alpha");
					if (pk > 2) {
						builder.setParent(pk - 2);
					}
					session.upsertEntity(builder);
				}
			}
			for (final String type : new String[]{"Brand", "Store"}) {
				session.defineEntitySchema(type)
					.withReferenceToEntity("leaf", "Category", Cardinality.ONE_OR_MORE, thatIs -> thatIs.indexed())
					.updateAndFetchVia(session);
				for (int pk = 1; pk <= 2; pk++) {
					session.getEntity(type, pk, entityFetchAllContent()).orElseThrow().openForWrite()
						.setReference("leaf", 1).setReference("leaf", 2).upsertVia(session);
				}
			}
			for (final String type : new String[]{"Brand", "Store"}) {
				session.upsertEntity(session.createNewEntity(type, 5)
					.setAttribute("match", Locale.ENGLISH, "yes")
					.setAttribute("name", Locale.ENGLISH, "English-only").setReference("leaf", 1));
			}
			session.defineEntitySchema("Product").withoutGeneratedPrimaryKey().withLocale(CS, Locale.ENGLISH)
				.withAttribute("name", String.class, thatIs -> thatIs.localized())
				.withReferenceToEntity("brand", "Brand", Cardinality.ONE_OR_MORE,
					thatIs -> thatIs.indexed().faceted().withGroupTypeRelatedToEntity("Store")
						.indexedWithComponents(ReferenceIndexedComponents.values())
						.withAttribute("label", String.class,
							thatIsAttribute -> thatIsAttribute.localized().filterable()))
				.withReferenceToEntity("nullableBrand", "Brand", Cardinality.ZERO_OR_MORE,
					thatIs -> thatIs.indexed().withGroupTypeRelatedToEntity("Store")
						.indexedWithComponents(ReferenceIndexedComponents.values()))
				.withReferenceToEntity("category", "Category", Cardinality.ONE_OR_MORE, thatIs -> thatIs.indexed())
				.updateAndFetchVia(session);
			for (int pk = 1; pk <= 4; pk++) {
				final int target = pk == 2 ? 2 : 1;
				final boolean czechOwner = pk != 4;
				final EntityBuilder builder = session.createNewEntity("Product", pk)
					.setAttribute("name", Locale.ENGLISH, "product-en")
					.setReference("brand", target, ref -> {
						ref.setGroup("Store", target).setAttribute("label", Locale.ENGLISH, "no");
						if (czechOwner) {
							ref.setAttribute("label", CS, "yes");
						}
					})
					.setReference("category", target + 2);
				if (pk != 4) {
					builder.setAttribute("name", CS, "product-cs");
				}
				if (pk == 3) {
					builder.setReference("nullableBrand", 5, ref -> ref.setGroup("Store", 1))
						.setReference("nullableBrand", 1, ref -> ref.setGroup("Store", 5));
					builder.setReference("brand", 2, ref -> ref.setGroup("Store", 2)
						.setAttribute("label", CS, "yes").setAttribute("label", Locale.ENGLISH, "no"));
				}
				session.upsertEntity(builder);
			}
		});
		return new DataCarrier();
	}

	/**
	 * Supplies facet filters naming the owner locale, with the owners each selects. Brand 1 is referenced by owners 1,
	 * 3 and 4, brand 2 by owners 2 and 3, and owners 1-3 hold Czech data.
	 *
	 * @return the shape name, the facet filter and the selected owners
	 */
	@Nonnull
	static Stream<Arguments> ownerLocaleFacetFilters() {
		return Stream.of(
			Arguments.of("selection", and(entityLocaleEquals(CS), entityPrimaryKeyInSet(1)), List.of(1, 3)),
			Arguments.of("locale", entityLocaleEquals(CS), List.of(1, 2, 3)),
			Arguments.of("or", or(entityLocaleEquals(CS), entityPrimaryKeyInSet(1)), List.of(1, 2, 3, 4)),
			Arguments.of("not", not(entityLocaleEquals(CS)), List.of(4)),
			Arguments.of("notOr", not(or(entityLocaleEquals(CS), entityPrimaryKeyInSet(2))), List.of(4)),
			Arguments.of("children", and(entityLocaleEquals(CS), includingChildren()), List.of(1, 2, 3))
		);
	}

	/** Pins owner predicates inside facet filters and their summary projections. */
	@Nested
	@Tag(FACET)
	@DisplayName("Owner locales in facet filters")
	class FacetLocales {
		/** Compares disjunctive owner-locale impact predictions with selecting the facet in the full query. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@UseDataSet(DATA_SET)
		@DisplayName("predicts selected-facet impacts when a locale branch accepts any facet")
		void shouldPredictSelectedQueryWhenLocaleBranchAcceptsAnyFacet(boolean negated, @Nonnull Evita evita) {
			try (final EvitaSessionContract session = evita.createReadOnlySession(TEST_CATALOG)) {
				final FilterConstraint selected = negated ?
					not(and(entityLocaleEquals(CS), not(entityPrimaryKeyInSet(1)))) :
					or(entityLocaleEquals(CS), entityPrimaryKeyInSet(1));
				final FilterConstraint enriched = negated ?
					not(and(entityLocaleEquals(CS), not(entityPrimaryKeyInSet(1, 2)))) :
					or(entityLocaleEquals(CS), entityPrimaryKeyInSet(1, 2));
				final EvitaResponse<EntityReference> response = session.query(query(collection("Product"),
					filterBy(userFilter(facetHaving("brand", selected))),
					require(referenceSummaryOfReference("brand", FacetStatisticsDepth.IMPACT))), EntityReference.class);
				assertEquals(negated ? 3 : 4, response.getTotalRecordCount(), "The base must exercise both branches");
				final List<Integer> actual = owners(session, userFilter(facetHaving("brand", enriched)));
				assertEquals(negated ? List.of(3, 4) : List.of(1, 2, 3), actual,
					"Selecting brand 2 must remove exactly one owner");
				final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
				assertNotNull(summary);
				final ReferenceSummary.FacetStatistics facet = summary.getReferenceStatistics().stream()
					.flatMap(group -> group.getFacetStatistics().stream())
					.filter(it -> it.getFacetEntity().getPrimaryKey() == 2).findFirst().orElseThrow();
				assertNotNull(facet.getImpact());
				assertEquals(actual.size(), facet.getImpact().matchCount(),
					"Impact must match the selected-facet query across locale branches");
				assertEquals(-1, facet.getImpact().difference(), "Selecting brand 2 must remove one owner");
			}
		}

		/** A locale names no facet, so only the facets named by the other branch are marked as requested. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("marks only the named facet requested when a locale branch accepts any facet")
		void shouldMarkOnlyNamedFacetRequestedWhenLocaleBranchAcceptsAnyFacet(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final FilterConstraint selected = or(entityLocaleEquals(CS), entityPrimaryKeyInSet(1));

				final EvitaResponse<EntityReference> response = session.query(query(collection("Product"),
					filterBy(userFilter(facetHaving("brand", selected))),
					require(referenceSummaryOfReference("brand", FacetStatisticsDepth.COUNTS))), EntityReference.class);

				final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
				assertNotNull(summary);
				final List<ReferenceSummary.FacetStatistics> facets = summary.getReferenceStatistics().stream()
					.flatMap(group -> group.getFacetStatistics().stream()).toList();
				assertEquals(List.of(1, 2), facets.stream().map(it -> it.getFacetEntity().getPrimaryKey()).sorted()
					.toList(), "Both brands must be summarized");
				assertEquals(List.of(1), facets.stream().filter(ReferenceSummary.FacetStatistics::isRequested)
					.map(it -> it.getFacetEntity().getPrimaryKey()).toList(), "Only the named brand is requested");
			});
		}

		/**
		 * Owners of the same facets differ in their locales, so skipping the leaf cannot pass.
		 *
		 * @param shape    name of the facet filter shape
		 * @param filter   the facet filter naming the owner locale
		 * @param expected the owners the facet filter selects
		 * @param evita    embedded engine
		 */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.api.functional.reference.EntityLevelLocaleFunctionalTest#ownerLocaleFacetFilters")
		@UseDataSet(DATA_SET)
		@DisplayName("keeps the boolean meaning of owner locale leaves")
		void shouldFilterOwnersWhenFacetFilterNamesLocale(
			@Nonnull String shape, @Nonnull FilterConstraint filter, @Nonnull List<Integer> expected,
			@Nonnull Evita evita
		) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertEquals(List.of(1, 3, 4), owners(session, facetHaving("brand", entityPrimaryKeyInSet(1))),
					"The same facet must have Czech and non-Czech owners");
				assertEquals(List.of(1, 2, 3), owners(session, entityLocaleEquals(CS)));

				final List<Integer> selected = owners(session, facetHaving("brand", filter));

				assertEquals(expected, selected,
					"A facet locale leaf must test each owner and keep boolean meaning: " + shape);
			});
		}

		/**
		 * Counts and impacts are compared with full selected-facet queries, including unselected facets. The generic
		 * forms summarize every faceted reference, which is `brand` alone in this fixture.
		 *
		 * @param legacy  use the facet summary instead of the reference summary
		 * @param generic use the form summarizing all references instead of the one naming `brand`
		 * @param scoped  place the user filter in a scope container
		 * @param evita   embedded engine
		 */
		@ParameterizedTest(name = "legacy={0}, generic={1}, scoped={2}")
		@CsvSource({"false,false,false", "false,false,true", "false,true,false", "false,true,true",
			"true,false,false", "true,false,true", "true,true,false", "true,true,true"})
		@UseDataSet(DATA_SET)
		@DisplayName("counts and predicts selections with an owner locale")
		void shouldMatchSelectedQueriesWhenFacetSummaryKeepsLocale(
			boolean legacy, boolean generic, boolean scoped, @Nonnull Evita evita
		) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final RequireConstraint summary;
				if (generic) {
					summary = legacy ? facetSummary(FacetStatisticsDepth.IMPACT) :
						referenceSummary(FacetStatisticsDepth.IMPACT);
				} else {
					summary = legacy ? facetSummaryOfReference("brand", FacetStatisticsDepth.IMPACT) :
						referenceSummaryOfReference("brand", FacetStatisticsDepth.IMPACT);
				}
				final FilterConstraint selected = userFilter(
					facetHaving("brand", entityLocaleEquals(CS), entityPrimaryKeyInSet(1))
				);
				final EvitaResponse<EntityReference> response = session.query(query(collection("Product"),
					filterBy(scoped ? inScope(Scope.LIVE, selected) : selected),
					require(summary)), EntityReference.class);
				final ReferenceSummary result = legacy ? response.getExtraResult(FacetSummary.class) :
					response.getExtraResult(ReferenceSummary.class);
				assertNotNull(result);
				assertEquals(2, result.getReferenceStatistics().size(), "Both groups must be summarized");
				int checked = 0;
				int impactsChecked = 0;
				for (final ReferenceSummary.ReferenceGroupStatistics group : result.getReferenceStatistics()) {
					assertFalse(group.getFacetStatistics().isEmpty());
					for (final ReferenceSummary.FacetStatistics facet : group.getFacetStatistics()) {
						final int pk = facet.getFacetEntity().getPrimaryKey();
						final int alone = owners(session, facetHaving("brand", entityLocaleEquals(CS),
							entityPrimaryKeyInSet(pk))).size();
						assertEquals(2, alone, "Fixture must count only Czech owners of either facet");
						assertEquals(alone, facet.getCount(), "Facet count must retain the owner locale: " + pk);
						final int combined = owners(session, facetHaving("brand", entityLocaleEquals(CS),
							entityPrimaryKeyInSet(1, pk))).size();
						if (!facet.isRequested()) {
							assertNotNull(facet.getImpact());
							impactsChecked++;
							assertEquals(combined, facet.getImpact().matchCount(),
								"Impact must match selected query: " + pk);
							assertEquals(combined - response.getTotalRecordCount(), facet.getImpact().difference());
						}
						checked++;
					}
				}
				assertEquals(2, checked, "Both facets must be summarized");
				assertEquals(1, impactsChecked, "An unselected facet must exercise impact prediction");
			});
		}

		/**
		 * A localized facet filter outside the user filter belongs to the mandatory filter, which the summary already
		 * applies whole, so it must not add an owner guard to the statistics as well.
		 */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@UseDataSet(DATA_SET)
		@DisplayName("summarizes every group when a mandatory facet filter negates an owner locale")
		void shouldSummarizeEveryGroupWhenMandatoryFacetFilterNegatesLocale(boolean legacy, @Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final FilterConstraint mandatory = not(
					facetHaving("brand", entityLocaleEquals(CS), entityPrimaryKeyInSet(1))
				);
				assertEquals(List.of(2, 4), owners(session, mandatory), "Fixture must keep an owner of each brand");
				final RequireConstraint summary = legacy ?
					facetSummaryOfReference("brand", FacetStatisticsDepth.COUNTS) :
					referenceSummaryOfReference("brand", FacetStatisticsDepth.COUNTS);

				final EvitaResponse<EntityReference> response = session.query(
					query(collection("Product"), filterBy(mandatory), require(summary)), EntityReference.class
				);

				final ReferenceSummary result = legacy ? response.getExtraResult(FacetSummary.class) :
					response.getExtraResult(ReferenceSummary.class);
				assertNotNull(result);
				assertEquals(2, result.getReferenceStatistics().size(), "Both groups hold an owner of the result");
				for (final ReferenceSummary.ReferenceGroupStatistics group : result.getReferenceStatistics()) {
					for (final ReferenceSummary.FacetStatistics facet : group.getFacetStatistics()) {
						assertEquals(1, facet.getCount(),
							"Each brand has one owner in the result: " + facet.getFacetEntity().getPrimaryKey());
					}
				}
			});
		}

		/**
		 * Summary counts discard the rest of the user filter, so the connector of a disjunct must not decide whether
		 * the owner guard of the localized selection survives. The count equals the facet selected alone.
		 */
		@ParameterizedTest
		@ValueSource(strings = {"sameLocale", "unrelated"})
		@UseDataSet(DATA_SET)
		@DisplayName("counts facets with the owner locale when the facet filter is a disjunction")
		void shouldCountFacetsWithOwnerLocaleWhenFacetFilterIsDisjunctive(@Nonnull String shape, @Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final FilterConstraint disjunct = switch (shape) {
					case "sameLocale" -> facetHaving("brand", entityLocaleEquals(CS), entityPrimaryKeyInSet(2));
					case "unrelated" -> referenceHaving("category", entityPrimaryKeyInSet(4));
					default -> throw new IllegalArgumentException(shape);
				};
				final FilterConstraint selected = userFilter(
					or(facetHaving("brand", entityLocaleEquals(CS), entityPrimaryKeyInSet(1)), disjunct)
				);

				final FacetSummary result = session.query(query(collection("Product"), filterBy(selected),
						require(facetSummaryOfReference("brand", FacetStatisticsDepth.COUNTS))), EntityReference.class)
					.getExtraResult(FacetSummary.class);

				assertNotNull(result);
				int checked = 0;
				for (final ReferenceSummary.ReferenceGroupStatistics group : result.getReferenceStatistics()) {
					for (final ReferenceSummary.FacetStatistics facet : group.getFacetStatistics()) {
						final int pk = facet.getFacetEntity().getPrimaryKey();
						final int alone = owners(session, facetHaving("brand", entityLocaleEquals(CS),
							entityPrimaryKeyInSet(pk))).size();
						assertEquals(2, alone, "Fixture must count only Czech owners of either facet");
						assertEquals(alone, facet.getCount(), "Facet count must retain the owner locale: " + pk);
						checked++;
					}
				}
				assertEquals(2, checked, "Both brands must be summarized");
			});
		}

		/** The GraphQL surface must enforce the same owner predicate. */
		@Test
		@Tag(GRAPHQL)
		@UseDataSet(DATA_SET)
		@DisplayName("GraphQL filters owners by a facet locale leaf")
		void shouldFilterOwnersWhenGraphQLFacetNamesLocale(@Nonnull GraphQLTester tester) {
			final List<Integer> keys = tester.test(TEST_CATALOG).document("""
				{listProduct(filterBy: {facetBrandHaving: {entityLocaleEquals: cs, entityPrimaryKeyInSet: 1}}) {
				  primaryKey
				}}
				""").executeAndExpectOkAndThen().extract().jsonPath().getList("data.listProduct.primaryKey", Integer.class);
			assertEquals(List.of(1, 3), keys, "GraphQL facet locale must exclude the English-only owner");
		}

		/** The REST surface must enforce the same owner predicate. */
		@Test
		@Tag(REST)
		@UseDataSet(DATA_SET)
		@DisplayName("REST filters owners by a facet locale leaf")
		void shouldFilterOwnersWhenRestFacetNamesLocale(@Nonnull RestTester tester) {
			final List<Integer> keys = tester.test(TEST_CATALOG).urlPathSuffix("/Product/list")
				.httpMethod(RestTester.Request.METHOD_POST).requestBody("""
				{"filterBy": {"facetBrandHaving": {"entityLocaleEquals": "cs", "entityPrimaryKeyInSet": [1]}}}
				""").executeAndExpectOkAndThen().extract().jsonPath().getList("primaryKey", Integer.class);
			assertEquals(List.of(1, 3), keys, "REST facet locale must exclude the English-only owner");
		}
	}

	/**
	 * Pins main-query coalescing, conflicts, and owner-level reference opinions.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Main and nested filtering")
	class FilteringTest {
		/** Equal locale opinions still select only localized owners. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Repeated owner locales coalesce")
		void shouldCoalesceWhenMainLocaleRepeated(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertEquals(List.of(1, 2, 3), owners(session,
					entityLocaleEquals(CS), entityLocaleEquals(CS)));
				assertEquals(List.of(1, 2, 3), owners(session, referenceHaving("brand", entityLocaleEquals(CS))));
				return null;
			});
		}

		/** A disjunction cannot introduce two locales for the same owner. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Conflicting owner locales name both locales and their container")
		void shouldRejectWhenMainLocalesConflict(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
					() -> owners(session, or(entityLocaleEquals(CS), entityLocaleEquals(Locale.ENGLISH))));
				assertTrue(error.getMessage().contains("Conflicting locales `cs` and `en`"), error.getMessage());
				assertTrue(error.getMessage().contains("filterBy(or("), error.getMessage());
				return null;
			});
		}

		/** Locales conflict per level, so a disjunction inside a nested entity or group level is refused too. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@UseDataSet(DATA_SET)
		@DisplayName("Conflicting locales inside a nested level name both locales")
		void shouldRejectWhenNestedLevelLocalesConflict(boolean group, @Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
					() -> owners(session, entityLocaleEquals(CS), referenceHaving("brand",
						scope(group, or(entityLocaleEquals(CS), entityLocaleEquals(Locale.ENGLISH))))));
				assertTrue(error.getMessage().contains("Conflicting locales `cs` and `en`"), error.getMessage());
				return null;
			});
		}

		/** The direct children of `referenceHaving` belong to the owner's level, so a different locale conflicts. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("A reference container's locale conflicting with its owner's is refused")
		void shouldRejectWhenOwnerAndReferenceLevelLocalesConflict(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
					() -> owners(session, entityLocaleEquals(CS),
						referenceHaving("brand", entityLocaleEquals(Locale.ENGLISH))));
				assertTrue(error.getMessage().contains("Conflicting locales `cs` and `en`"), error.getMessage());
				return null;
			});
		}

		/** Own opinions override, while an absent nested opinion inherits the owner's Czech locale. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@UseDataSet(DATA_SET)
		@DisplayName("Nested entity and group filters use their own locale before inheriting")
		void shouldResolveWhenNestedLevelFiltersLocalizedValues(boolean group, @Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertDiscriminatingValues(session, group ? "Store" : "Brand");
				assertEquals(List.of(1, 3), owners(session, entityLocaleEquals(CS), referenceHaving("brand",
					scope(group, entityLocaleEquals(Locale.ENGLISH), attributeEquals("match", "yes")))));
				assertEquals(List.of(2, 3), owners(session, entityLocaleEquals(CS), referenceHaving("brand",
					scope(group, attributeEquals("match", "yes")))));
				final List<SealedEntity> fetched = session.queryListOfSealedEntities(query(collection("Product"),
					filterBy(entityLocaleEquals(CS), referenceHaving("brand", scope(group,
						entityLocaleEquals(Locale.ENGLISH), attributeEquals("match", "yes")))),
					require(entityFetch(attributeContentAll()))));
				assertEquals(2, fetched.size());
				fetched.forEach(it -> assertEquals("product-cs", it.getAttribute("name")));
				return null;
			});
		}

		/** A nested opinion cannot choose the locale or narrow the localized data of its owner. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Nested-only locale leaves the main query without a locale")
		void shouldKeepMainLocaleAbsentWhenOnlyNestedLevelNamesLocale(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertDiscriminatingValues(session, "Brand");
				final List<SealedEntity> fetched = session.queryListOfSealedEntities(query(collection("Product"),
					filterBy(referenceHaving("brand", entityHaving(
						entityLocaleEquals(Locale.ENGLISH), attributeEquals("match", "yes")))),
					require(entityFetch(attributeContentAll()))));
				assertEquals(List.of(1, 3, 4), fetched.stream().map(SealedEntity::getPrimaryKey).toList());
				assertThrows(ContextMissingException.class,
					() -> fetched.getFirst().getAttribute("name", Locale.ENGLISH),
					"Nested English must not become the main query locale and fetch English owner attributes");
				return null;
			});
		}

		/** The hierarchy parent and specifications share an independent locale. */
		@Test
		@Tag(HIERARCHY)
		@UseDataSet(DATA_SET)
		@DisplayName("Hierarchy parent filtering uses English inside a Czech query")
		void shouldResolveWhenHierarchyLevelNamesDifferentLocale(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertDiscriminatingValues(session, "Category");
				assertEquals(List.of(1, 3), owners(session, entityLocaleEquals(CS), hierarchyWithin("category",
					and(entityPrimaryKeyInSet(1, 2), entityLocaleEquals(Locale.ENGLISH),
						attributeEquals("match", "yes")))));
				assertEquals(List.of(2), owners(session, entityLocaleEquals(CS), hierarchyWithin("category",
					and(entityPrimaryKeyInSet(1, 2), attributeEquals("match", "yes")))));
				assertEquals(List.of(1, 3), owners(session, entityLocaleEquals(CS), hierarchyWithinRoot("category",
					having(entityLocaleEquals(Locale.ENGLISH), attributeEquals("match", "yes")))));
				return null;
			});
		}
	}

	/**
	 * Covers reference-level inheritance and body-fetch normalization.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Fetched reference levels")
	class FetchTest {
		/** A locale-less nested entity or group must inherit English from its reference filter, not Czech. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@UseDataSet(DATA_SET)
		@DisplayName("Nested row predicates inherit the reference filter's locale")
		void shouldInheritReferenceLocaleWhenNestedRowFilterHasNoOpinion(boolean group, @Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertDiscriminatingValues(session, group ? "Store" : "Brand");
				final SealedEntity fetched = fetch(session, filterBy(entityLocaleEquals(Locale.ENGLISH),
					scope(group, attributeEquals("match", "yes"))));
				assertEquals(List.of(1), keys(fetched), "Nested row filter must inherit reference-level en");
				assertEquals("product-cs", fetched.getAttribute("name"));
				return null;
			});
		}

		/** Distinct own opinions must survive flattening, and the comparator must use the fetched body's locale. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Reference rows use Czech while fetched bodies and ordering use English")
		void shouldFetchAndOrderWhenReferenceAndEntityLocalesDiffer(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final SealedEntity fetched = fetch(session, filterBy(
					entityLocaleEquals(CS), attributeEquals("label", "yes"),
					entityHaving(entityLocaleEquals(Locale.ENGLISH))
				));
				assertEquals(List.of(2, 1), keys(fetched),
					"English DESC must reverse Czech DESC and primary-key order");
				for (final ReferenceContract ref : fetched.getReferences("brand")) {
					final SealedEntity body = ref.getReferencedEntity().orElseThrow();
					assertEquals(ref.getReferencedPrimaryKey() == 1 ? "Alpha" : "Zulu", body.getAttribute("name"));
				}
				return null;
			});
		}

		/** Without an entity-level opinion the bodies and their ordering inherit the owner's Czech locale. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Bodies order in the owner's locale when the filter names no entity locale")
		void shouldOrderInOwnerLocaleWhenFilterNamesNoEntityLocale(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertEquals(List.of(1, 2), keys(fetch(session, filterBy(entityLocaleEquals(CS)))),
					"A reference-level Czech opinion orders the bodies in Czech");
				assertEquals(List.of(1, 2), keys(fetch(session, null)),
					"Without any filter the bodies order in the owner's Czech");
				return null;
			});
		}

		/** The reference level's own locale constrains the owner, so an owner without it has no fetched rows. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Reference rows require the owner to hold the reference level's locale")
		void shouldFetchNoRowsWhenOwnerLacksReferenceLevelLocale(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final List<SealedEntity> noRows = session.queryListOfSealedEntities(query(collection("Product"),
					filterBy(entityPrimaryKeyInSet(4), entityLocaleEquals(Locale.ENGLISH)),
					require(entityFetch(referenceContent("brand", filterBy(entityLocaleEquals(CS),
						entityHaving(entityLocaleEquals(Locale.ENGLISH))), entityFetch(attributeContentAll()))))));
				assertEquals(1, noRows.size());
				assertTrue(noRows.getFirst().getReferences("brand").isEmpty(), "Rows require owner holding Czech");
				return null;
			});
		}

		/** Inheriting a locale changes context, and does not add a requirement that the target has that locale. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@UseDataSet(DATA_SET)
		@DisplayName("Inherited locale does not add a locale-existence predicate")
		void shouldPreserveNullPredicateWhenReferenceLocaleInherited(boolean group, @Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final String type = group ? "Store" : "Brand";
				final SealedEntity englishOnly = session.getEntity(type, 5, dataInLocalesAll(),
					attributeContentAll()).orElseThrow();
				assertEquals(Set.of(Locale.ENGLISH), englishOnly.getLocales());
				assertEquals(List.of(3), owners(session, entityLocaleEquals(CS), referenceHaving("nullableBrand",
					scope(group, attributeIsNull("match")))), "Inherited context must permit a missing Czech value");
				assertEquals(List.of(), owners(session, entityLocaleEquals(CS), referenceHaving("nullableBrand",
					scope(group, entityLocaleEquals(CS), attributeIsNull("match")))),
					"An explicit locale predicate must exclude the English-only target");
				final SealedEntity fetched = session.queryOneSealedEntity(query(collection("Product"),
					filterBy(entityPrimaryKeyInSet(3), entityLocaleEquals(Locale.ENGLISH)),
					require(entityFetch(referenceContent("nullableBrand", filterBy(entityLocaleEquals(CS),
						scope(group, attributeIsNull("match")))))))).orElseThrow();
				assertEquals(List.of(group ? 1 : 5), fetched.getReferences("nullableBrand").stream()
					.map(ReferenceContract::getReferencedPrimaryKey).toList(),
					"Inherited locale context must not add an existence predicate");
				return null;
			});
		}

		/** Shared formula memoization must distinguish equal predicates evaluated in different inherited locales. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@UseDataSet(DATA_SET)
		@DisplayName("Equal predicates keep different inherited locale contexts")
		void shouldKeepLocalesSeparateWhenReferenceFiltersSharePredicate(boolean group, @Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertDiscriminatingValues(session, group ? "Store" : "Brand");
				final ReferenceContent english = namedContent("english", Locale.ENGLISH, group);
				final ReferenceContent czech = namedContent("czech", CS, group);
				final SealedEntity fetched = session.queryOneSealedEntity(query(collection("Product"),
					filterBy(entityPrimaryKeyInSet(3), entityLocaleEquals(CS)),
					require(entityFetch(english, czech)))).orElseThrow();
				final ServerEntityDecorator serverEntity = assertInstanceOf(ServerEntityDecorator.class, fetched);
				assertEquals(List.of(1), serverEntity.getReferencesForReferenceContentInstance(
					new ReferenceContentKey("english", "brand")).orElseThrow().getData().stream()
					.map(ReferenceContract::getReferencedPrimaryKey).toList());
				assertEquals(List.of(2), serverEntity.getReferencesForReferenceContentInstance(
					new ReferenceContentKey("czech", "brand")).orElseThrow().getData().stream()
					.map(ReferenceContract::getReferencedPrimaryKey).toList(),
					"Memoized predicates must retain their inherited locale");
				return null;
			});
		}

		/** Group bodies and sorters use the group level, independently of the entity level. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Group bodies and ordering use their own English locale")
		void shouldFetchGroupsWhenGroupLocaleDiffers(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final SealedEntity fetched = session.queryOneSealedEntity(query(collection("Product"),
					filterBy(entityPrimaryKeyInSet(3), entityLocaleEquals(CS)), require(entityFetch(
						referenceContent("brand", filterBy(entityLocaleEquals(CS),
							entityHaving(entityLocaleEquals(CS)), groupHaving(entityLocaleEquals(Locale.ENGLISH))),
							orderBy(entityGroupProperty(attributeNatural("name", DESC))),
							entityGroupFetch(attributeContentAll())))))).orElseThrow();
				assertEquals(List.of(2, 1), keys(fetched),
					"Groups must order in English independently of entity Czech");
				for (final ReferenceContract ref : fetched.getReferences("brand")) {
					assertEquals(ref.getReferencedPrimaryKey() == 1 ? "Alpha" : "Zulu",
						ref.getGroupEntity().orElseThrow().getAttribute("name"));
				}
				return null;
			});
		}

		/** Grandchild levels without an opinion inherit English from their immediate entity or group owner. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@UseDataSet(DATA_SET)
		@DisplayName("Nested reference content inherits its immediate body locale")
		void shouldInheritWhenReferenceContentNestedTwice(boolean group, @Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertDiscriminatingValues(session, "Category");
				final ReferenceContent content = referenceContent("leaf",
					filterBy(entityHaving(attributeEquals("match", "yes"))), entityFetch(attributeContentAll()));
				final SealedEntity fetched = session.queryOneSealedEntity(query(collection("Product"),
					filterBy(entityPrimaryKeyInSet(3), entityLocaleEquals(CS)), require(entityFetch(referenceContent(
						"brand", filterBy(entityLocaleEquals(CS), scope(group, entityLocaleEquals(Locale.ENGLISH))),
						group ? entityFetch() : entityFetch(attributeContentAll(), content),
						group ? entityGroupFetch(attributeContentAll(), content) : entityGroupFetch()
					))))).orElseThrow();
				assertEquals(2, fetched.getReferences("brand").size());
				for (final ReferenceContract ref : fetched.getReferences("brand")) {
					final SealedEntity body = (group ? ref.getGroupEntity() : ref.getReferencedEntity()).orElseThrow();
					assertEquals(List.of(1), body.getReferences("leaf").stream()
						.map(ReferenceContract::getReferencedPrimaryKey).toList(),
						"Grandchild must inherit immediate English body locale");
					assertEquals("Alpha", body.getReferences("leaf").iterator().next()
						.getReferencedEntity().orElseThrow().getAttribute("name"));
				}
				return null;
			});
		}

		/** Summary filter and body requests address the same target level and must agree on its locale. */
		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("Summary bodies use their own filter locale")
		void shouldFetchSummaryBodiesWhenSummaryLocaleDiffers(@Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertSummaryLocale(session);
				return null;
			});
		}

	}

	/**
	 * Verifies the shared lookup through the GraphQL and REST boundaries.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@Tag(EXTERNAL_API)
	@DisplayName("External APIs")
	class ApiTest {
		/** GraphQL coalesces duplicates and reports clear conflicts before returning data. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@Tag(GRAPHQL)
		@UseDataSet(DATA_SET)
		@DisplayName("GraphQL resolves repeated and conflicting locales")
		void shouldResolveWhenGraphQLRepeatsLocale(boolean conflict, @Nonnull GraphQLTester tester) {
			final GraphQLTester.Request request = tester.test(TEST_CATALOG).document("""
				{listProduct(filterBy: {and: [{entityLocaleEquals: cs}, {entityLocaleEquals: %s}]}) {primaryKey}}
				""".formatted(conflict ? "en" : "cs"));
			if (conflict) {
				final String error = request.executeAndExpectErrorsAndThen().extract().asString();
				assertTrue(error.contains("Conflicting locales `cs` and `en`"), error);
			} else {
				final JsonPath result = request.executeAndExpectOkAndThen().extract().jsonPath();
				assertEquals(List.of(1, 2, 3), result.getList("data.listProduct.primaryKey", Integer.class));
			}
		}

		/** REST coalesces duplicates and reports clear conflicts before returning data. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@Tag(REST)
		@UseDataSet(DATA_SET)
		@DisplayName("REST resolves repeated and conflicting locales")
		void shouldResolveWhenRestRepeatsLocale(boolean conflict, @Nonnull RestTester tester) {
			final RestTester.Request request = tester.test(TEST_CATALOG).urlPathSuffix("/Product/list")
				.httpMethod(RestTester.Request.METHOD_POST).requestBody("""
				{"filterBy": {"and": [{"entityLocaleEquals": "cs"}, {"entityLocaleEquals": "%s"}]}}
				""".formatted(conflict ? "en" : "cs"));
			if (conflict) {
				final String error = request.executeAndThen(400).extract().asString();
				assertTrue(error.contains("Conflicting locales `cs` and `en`"), error);
			} else {
				assertEquals(List.of(1, 2, 3), request.executeAndExpectOkAndThen().extract().jsonPath()
					.getList("primaryKey", Integer.class));
			}
		}

		/** Nested selections derive their fetch locale from their entity or group filter, not from the owner. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@Tag(GRAPHQL)
		@UseDataSet(DATA_SET)
		@DisplayName("GraphQL fetches nested localized fields in their own level's locale")
		void shouldFetchWhenGraphQLNestedLocaleDiffers(boolean group, @Nonnull GraphQLTester tester) {
			final JsonPath result = tester.test(TEST_CATALOG).document("""
				{listProduct(filterBy: {entityPrimaryKeyInSet: 3, entityLocaleEquals: cs}) {
				  brand(filterBy: {%s: {entityLocaleEquals: en}}) {
				    %s {primaryKey attributes {name}}
				  }
				}}
				""".formatted(group ? "groupHaving" : "entityHaving", group ? "groupEntity" : "referencedEntity"))
				.executeAndExpectOkAndThen().extract().jsonPath();
			final String path = "data.listProduct[0].brand." + (group ? "groupEntity" : "referencedEntity");
			assertEquals(List.of(1, 2), result.getList(path + ".primaryKey", Integer.class));
			assertEquals(List.of("Alpha", "Zulu"), result.getList(path + ".attributes.name", String.class),
				"GraphQL nested bodies must fetch English values despite the Czech owner");
		}

		/** A field with an explicit locale adds a second fetched locale, which must not move the default field. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@Tag(GRAPHQL)
		@UseDataSet(DATA_SET)
		@DisplayName("GraphQL default localized fields keep the body level's locale beside an explicit locale")
		void shouldUseBodyLevelLocaleWhenGraphQLFetchesSeveralLocales(boolean group, @Nonnull GraphQLTester tester) {
			final JsonPath result = tester.test(TEST_CATALOG).document("""
				{listProduct(filterBy: {entityPrimaryKeyInSet: 3, entityLocaleEquals: cs}) {
				  brand(filterBy: {%s: {entityLocaleEquals: en}}) {
				    %s {primaryKey attributes {name} czech: attributes(locale: cs) {name}}
				  }
				}}
				""".formatted(group ? "groupHaving" : "entityHaving", group ? "groupEntity" : "referencedEntity"))
				.executeAndExpectOkAndThen().extract().jsonPath();
			final String path = "data.listProduct[0].brand." + (group ? "groupEntity" : "referencedEntity");
			assertEquals(List.of(1, 2), result.getList(path + ".primaryKey", Integer.class));
			assertEquals(List.of("Zulu", "Alpha"), result.getList(path + ".czech.name", String.class),
				"The field with an explicit locale reads Czech");
			assertEquals(List.of("Alpha", "Zulu"), result.getList(path + ".attributes.name", String.class),
				"The default field must read the body level's English");
		}

		/** An owner filter chooses Czech while its nested entity filter independently chooses English. */
		@Test
		@Tag(REST)
		@UseDataSet(DATA_SET)
		@DisplayName("REST allows independent locales in different entity levels")
		void shouldResolveWhenRestLevelsNameDifferentLocales(@Nonnull RestTester tester) {
			final List<Integer> owners = tester.test(TEST_CATALOG).urlPathSuffix("/Product/list")
				.httpMethod(RestTester.Request.METHOD_POST).requestBody("""
				{"filterBy": {"entityLocaleEquals": "cs", "referenceBrandHaving": [{"entityHaving": {
				  "entityLocaleEquals": "en", "attributeMatchEquals": "yes"
				}}]}}
				""").executeAndExpectOkAndThen().extract().jsonPath().getList("primaryKey", Integer.class);
			assertEquals(List.of(1, 3), owners, "REST must constrain owners in Czech and targets in English");
		}

		/** Client predicates must expose the locale the server fetched, including nested group bodies. */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@Tag(GRPC)
		@UseDataSet(DATA_SET)
		@DisplayName("gRPC reconstructs referenced body locales")
		void shouldPreserveWhenGrpcReturnsNestedBodyLocale(boolean group, @Nonnull EvitaClient client) {
			client.queryCatalog(TEST_CATALOG, session -> {
				final SealedEntity fetched = session.queryOneSealedEntity(query(collection("Product"),
					filterBy(entityPrimaryKeyInSet(3), entityLocaleEquals(CS)), require(entityFetch(referenceContent(
						"brand", filterBy(entityLocaleEquals(CS), scope(group, entityLocaleEquals(Locale.ENGLISH))),
						entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll())
					))))).orElseThrow();
				assertEquals(2, fetched.getReferences("brand").size());
				for (final ReferenceContract ref : fetched.getReferences("brand")) {
					final SealedEntity body = (group ? ref.getGroupEntity() : ref.getReferencedEntity()).orElseThrow();
					assertEquals(ref.getReferencedPrimaryKey() == 1 ? "Alpha" : "Zulu", body.getAttribute("name"),
						"gRPC predicates must use the nested body's English locale");
				}
				return null;
			});
		}

		/** Client predicates of summary bodies must expose the locale of the summary's own filters. */
		@Test
		@Tag(GRPC)
		@UseDataSet(DATA_SET)
		@DisplayName("gRPC reconstructs summary body locales")
		void shouldPreserveWhenGrpcReturnsSummaryBodyLocale(@Nonnull EvitaClient client) {
			client.queryCatalog(TEST_CATALOG, session -> {
				assertSummaryLocale(session);
				return null;
			});
		}

		/**
		 * A named summary governs its reference entirely, so a generic summary's filter must not relabel its bodies.
		 */
		@ParameterizedTest
		@ValueSource(booleans = {false, true})
		@Tag(GRPC)
		@UseDataSet(DATA_SET)
		@DisplayName("gRPC keeps the owner locale of a named summary beside a localized generic one")
		void shouldKeepOwnerLocaleWhenNamedSummaryHasNoFilter(
			boolean legacy, @Nonnull Evita evita, @Nonnull EvitaClient client
		) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				assertNamedSummaryInOwnerLocale(session, legacy, "The server fetches named summary bodies in Czech");
				return null;
			});
			client.queryCatalog(TEST_CATALOG, session -> {
				assertNamedSummaryInOwnerLocale(session, legacy, "gRPC must read named summary bodies in Czech");
				return null;
			});
		}

	}

	/**
	 * Checks that the bodies of a named summary without a filter keep the owner's Czech locale while a generic summary
	 * beside it filters in English: the named summary takes nothing from the generic one.
	 */
	private static void assertNamedSummaryInOwnerLocale(
		@Nonnull EvitaSessionContract session, boolean legacy, @Nonnull String message
	) {
		final FilterBy englishFilter = filterBy(entityLocaleEquals(Locale.ENGLISH));
		final FilterGroupBy englishGroupFilter = filterGroupBy(entityLocaleEquals(Locale.ENGLISH));
		final RequireConstraint generic = legacy ?
			facetSummary(englishFilter, englishGroupFilter,
				entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll())) :
			referenceSummary(FacetStatisticsDepth.COUNTS, englishFilter, englishGroupFilter,
				entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll()));
		final RequireConstraint named = legacy ?
			facetSummaryOfReference(
				"brand", entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll())
			) :
			referenceSummaryOfReference(
				"brand", entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll())
			);
		final EvitaResponse<EntityReference> response = session.query(query(collection("Product"),
			filterBy(entityLocaleEquals(CS)), require(generic, named)), EntityReference.class);
		final ReferenceSummary summary = legacy ?
			response.getExtraResult(FacetSummary.class) : response.getExtraResult(ReferenceSummary.class);
		assertNotNull(summary, message);
		assertEquals(2, summary.getReferenceStatistics().size(), message);
		for (final ReferenceSummary.ReferenceGroupStatistics group : summary.getReferenceStatistics()) {
			final SealedEntity groupBody = (SealedEntity) group.getGroupEntity();
			assertEquals(groupBody.getPrimaryKey() == 1 ? "Zulu" : "Alpha", groupBody.getAttribute("name"), message);
			for (final ReferenceSummary.FacetStatistics facet : group.getFacetStatistics()) {
				final SealedEntity facetBody = (SealedEntity) facet.getFacetEntity();
				assertEquals(
					facetBody.getPrimaryKey() == 1 ? "Zulu" : "Alpha", facetBody.getAttribute("name"), message
				);
			}
		}
	}

	/** Builds a named reference filter with the same nested predicate but its own enclosing locale. */
	@Nonnull
	private static ReferenceContent namedContent(@Nonnull String name, @Nonnull Locale locale, boolean group) {
		return new ReferenceContent(name, ANY, new String[]{"brand"}, new RequireConstraint[0],
			new Constraint<?>[]{filterBy(entityLocaleEquals(locale), scope(group, attributeEquals("match", "yes")))});
	}

	/**
	 * Checks English selection, ordering and fetched attributes of summary entities and their groups. The entity
	 * filter repeats its English opinion on purpose: it is the only place where the summary path sees equal opinions
	 * coalesce.
	 */
	private static void assertSummaryLocale(@Nonnull EvitaSessionContract session) {
		final EvitaResponse<EntityReference> response = session.query(query(collection("Product"),
			filterBy(entityLocaleEquals(CS)), require(referenceSummaryOfReference("brand",
				filterBy(entityLocaleEquals(Locale.ENGLISH), entityLocaleEquals(Locale.ENGLISH)),
				filterGroupBy(entityLocaleEquals(Locale.ENGLISH)),
				orderBy(attributeNatural("name", DESC)), orderGroupBy(attributeNatural("name", DESC)),
				entityFetch(attributeContentAll()), entityGroupFetch(attributeContentAll())))), EntityReference.class);
		final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
		assertNotNull(summary);
		assertEquals(2, summary.getReferenceStatistics().size());
		final List<Integer> groups = summary.getReferenceStatistics().stream()
			.map(it -> it.getGroupEntity().getPrimaryKey()).toList();
		assertEquals(List.of(2, 1), groups, "Summary groups must order by English names");
		for (final ReferenceSummary.ReferenceGroupStatistics group : summary.getReferenceStatistics()) {
			final SealedEntity body = (SealedEntity) group.getGroupEntity();
			assertEquals(body.getPrimaryKey() == 1 ? "Alpha" : "Zulu", body.getAttribute("name"),
				"Summary group bodies must use their own English locale");
			assertEquals(1, group.getFacetStatistics().size());
			final SealedEntity facet = (SealedEntity) group.getFacetStatistics().iterator().next().getFacetEntity();
			assertEquals(facet.getPrimaryKey() == 1 ? "Alpha" : "Zulu", facet.getAttribute("name"),
				"Summary entity bodies must use their own English locale");
		}
	}

	/** Checks the precondition that the localized predicate selects opposite existing targets. */
	private static void assertDiscriminatingValues(@Nonnull EvitaSessionContract session, @Nonnull String type) {
		assertEquals(List.of(1), session.queryListOfEntityReferences(query(collection(type),
			filterBy(entityPrimaryKeyInSet(1, 2), entityLocaleEquals(Locale.ENGLISH), attributeEquals("match", "yes"))))
			.stream().map(EntityReferenceContract::getPrimaryKey).toList());
		assertEquals(List.of(2), session.queryListOfEntityReferences(query(collection(type),
			filterBy(entityPrimaryKeyInSet(1, 2), entityLocaleEquals(CS), attributeEquals("match", "yes"))))
			.stream().map(EntityReferenceContract::getPrimaryKey).toList());
	}

	/** Reads the keys of owners matching the supplied filter. */
	@Nonnull
	private static List<Integer> owners(@Nonnull EvitaSessionContract session, @Nonnull FilterConstraint... filters) {
		return session.queryListOfEntityReferences(query(collection("Product"), filterBy(filters)))
			.stream().map(EntityReferenceContract::getPrimaryKey).toList();
	}

	/** Builds a nested entity or group level. */
	@Nonnull
	private static FilterConstraint scope(boolean group, @Nonnull FilterConstraint... filters) {
		return group ? groupHaving(filters) : entityHaving(filters);
	}

	/** Fetches owner 3 and both bodies, ordering the references by the target's localized name. */
	@Nonnull
	private static SealedEntity fetch(@Nonnull EvitaSessionContract session, @Nullable FilterBy filter) {
		return session.queryOneSealedEntity(query(collection("Product"),
			filterBy(entityPrimaryKeyInSet(3), entityLocaleEquals(CS)),
			require(entityFetch(attributeContentAll(), referenceContent("brand", filter,
				orderBy(entityProperty(attributeNatural("name", DESC))), entityFetch(attributeContentAll()),
				entityGroupFetch(attributeContentAll())))))).orElseThrow();
	}

	/** Extracts the fetched reference keys in their requested order. */
	@Nonnull
	private static List<Integer> keys(@Nonnull SealedEntity entity) {
		return entity.getReferences("brand").stream().map(ReferenceContract::getReferencedPrimaryKey).toList();
	}
}
