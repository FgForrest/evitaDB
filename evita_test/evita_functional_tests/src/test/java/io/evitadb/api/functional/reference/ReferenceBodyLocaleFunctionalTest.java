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
import io.evitadb.api.exception.EntityLocaleMissingException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.filter.FilterGroupBy;
import io.evitadb.api.query.require.DataInLocales;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityFetchRequire;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.FacetSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaEditor;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Discriminating reproductions for localized nested body requirements and reference-specific nested query caching.
 * Four owners cover all combinations of two references to the same target collection. Localized bodies carry
 * different English and German names, so accepting a requirement without fetching its intended locale cannot pass.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Nested reference locales and having cache isolation")
@ExtendWith(EvitaParameterResolver.class)
@Tag(ENGINE)
@Tag(REFERENCE)
@Tag(FILTER)
@Tag(REQUIRE)
@Tag(CACHE)
class ReferenceBodyLocaleFunctionalTest implements EvitaTestSupport {
	private static final String DATA_SET = "referenceBodyLocale";

	/**
	 * Creates two targets and groups, and owners holding X/Y, Y/X, X/X and Y/Y respectively.
	 * Every owner has English data so a bare locale predicate on a reference row keeps that row.
	 *
	 * @param evita embedded engine supplied by the extension
	 */
	@DataSet(DATA_SET)
	void setUp(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("Brand")
				.withHierarchy()
				.withoutGeneratedPrimaryKey()
				.withLocale(Locale.ENGLISH, Locale.GERMAN)
				.withAttribute("code", String.class, AttributeSchemaEditor::filterable)
				.withAttribute("name", String.class, thatIs -> thatIs.localized().filterable())
				.withAssociatedData("description", String.class, AssociatedDataSchemaEditor::localized)
				.updateVia(session);
			session.defineEntitySchema("BrandGroup")
				.withoutGeneratedPrimaryKey()
				.withLocale(Locale.ENGLISH, Locale.GERMAN)
				.withAttribute("name", String.class, AttributeSchemaEditor::localized)
				.withAssociatedData("description", String.class, AssociatedDataSchemaEditor::localized)
				.updateVia(session);
			session.defineEntitySchema("Product")
				.withoutGeneratedPrimaryKey()
				.withLocale(Locale.ENGLISH, Locale.GERMAN)
				.withAttribute("name", String.class, AttributeSchemaEditor::localized)
				.withReferenceToEntity("brand", "Brand", Cardinality.EXACTLY_ONE,
					thatIs -> thatIs.indexedForFilteringAndPartitioning().faceted().withGroupTypeRelatedToEntity("BrandGroup"))
				.withReferenceToEntity("manufacturer", "Brand", Cardinality.EXACTLY_ONE,
				                       ReferenceSchemaEditor::indexedForFilteringAndPartitioning
				)
				.updateVia(session);
			for (int pk = 1; pk <= 2; pk++) {
				session.upsertEntity(session.createNewEntity("Brand", pk)
					.setAttribute("code", pk == 1 ? "X" : "Y")
					.setAttribute("name", Locale.ENGLISH, "English brand " + pk)
					.setAttribute("name", Locale.GERMAN, "Deutsche Marke " + pk)
					.setAssociatedData("description", Locale.ENGLISH, "English description " + pk)
					.setAssociatedData("description", Locale.GERMAN, "Deutsche Beschreibung " + pk));
				session.upsertEntity(session.createNewEntity("BrandGroup", pk)
					.setAttribute("name", Locale.ENGLISH, "English group " + pk)
					.setAttribute("name", Locale.GERMAN, "Deutsche Gruppe " + pk)
					.setAssociatedData("description", Locale.ENGLISH, "English description " + pk)
					.setAssociatedData("description", Locale.GERMAN, "Deutsche Beschreibung " + pk));
			}
			session.defineEntitySchema("Brand")
				.withReferenceToEntity("leaf", "BrandGroup", Cardinality.EXACTLY_ONE, ReferenceSchemaEditor::indexed)
				.updateVia(session);
			for (int pk = 1; pk <= 2; pk++) {
				session.getEntity("Brand", pk, referenceContentAll()).orElseThrow().openForWrite()
					.setReference("leaf", pk).upsertVia(session);
				if (pk == 2) {
					session.getEntity("Brand", pk).orElseThrow().openForWrite().setParent(1).upsertVia(session);
				}
			}
			for (int pk = 1; pk <= 4; pk++) {
				final int brand = pk == 1 || pk == 3 ? 1 : 2;
				final int manufacturer = pk == 2 || pk == 3 ? 1 : 2;
				session.upsertEntity(session.createNewEntity("Product", pk)
					.setAttribute("name", Locale.ENGLISH, "English product " + pk)
					.setAttribute("name", Locale.GERMAN, "Deutsches Produkt " + pk)
					.setReference("brand", brand, ref -> ref.setGroup("BrandGroup", brand))
					.setReference("manufacturer", manufacturer));
			}
		});
	}

	/**
	 * Supplies each boolean combination on the default, index scan and prefetch planning routes.
	 *
	 * @return boolean operation and optional explicit route
	 */
	@Nonnull
	static Stream<Arguments> cacheQueries() {
		return Stream.of("and", "or", "not").flatMap(operation -> Stream.of(
			Arguments.of(operation, null),
			Arguments.of(operation, DebugMode.PREFER_INDEX_SCAN),
			Arguments.of(operation, DebugMode.PREFER_PREFETCHING)
		));
	}

	/** Verifies the locale inherited by named requirements of referenced entity and group bodies. */
	@Nested
	@DisplayName("Localized nested bodies")
	class LocalizedBodies {
		/**
		 * The all-attributes control proves the body locale before the equivalent named request is planned.
		 *
		 * @param nestedLocale wrap the reference filter locale in entityHaving
		 * @param group fetch the group body instead of the target body
		 * @param evita embedded engine
		 */
		@ParameterizedTest(name = "entityHaving={0}, group={1}")
		@CsvSource({"false,false", "true,false", "false,true", "true,true"})
		@UseDataSet(DATA_SET)
		@DisplayName("accepts a named localized attribute using the reference filter locale")
		void shouldFetchNamedLocalizedAttributeWhenOnlyReferenceFilterHasLocale(
			boolean nestedLocale, boolean group, @Nonnull Evita evita
		) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final FilterBy referenceFilter = filterBy(nestedLocale ?
					(group ? groupHaving(entityLocaleEquals(Locale.ENGLISH)) :
						entityHaving(entityLocaleEquals(Locale.ENGLISH))) : entityLocaleEquals(Locale.ENGLISH));
				final ReferenceContent controlContent = group ?
					referenceContent("brand", referenceFilter, entityGroupFetch(attributeContentAll())) :
					referenceContent("brand", referenceFilter, entityFetch(attributeContentAll()));
				final SealedEntity control = fetchProduct(session, controlContent);
				assertEquals(Set.of(Locale.ENGLISH), bodyOf(control, group).getLocales(), "Fixture body locale");
				assertEquals(group ? "English group 1" : "English brand 1",
					bodyOf(control, group).getAttribute("name"), "Fixture English name");
				final SealedEntity storedBody = session.getEntity(group ? "BrandGroup" : "Brand", 1,
					attributeContentAll(), dataInLocalesAll()).orElseThrow();
				assertNotEquals(storedBody.<String>getAttribute("name", Locale.ENGLISH),
					storedBody.<String>getAttribute("name", Locale.GERMAN), "Fixture translations must differ");

				final ReferenceContent namedContent = group ?
					referenceContent("brand", referenceFilter, entityGroupFetch(attributeContent("name"))) :
					referenceContent("brand", referenceFilter, entityFetch(attributeContent("name")));
				final SealedEntity result = assertDoesNotThrow(() -> fetchProduct(session, namedContent),
					"A named localized body attribute must use the reference filter's English locale");
				assertEquals(bodyOf(control, group).<String>getAttribute("name"),
					bodyOf(result, group).<String>getAttribute("name"));
			});
		}

		/** A group cannot adopt a sibling entity level's locale, and an unresolved body remains refused. */
		@ParameterizedTest
		@CsvSource({"false,false", "false,true", "true,true"})
		@UseDataSet(DATA_SET)
		@DisplayName("refuses named localized attributes when the body level has no locale")
		void shouldRefuseNamedLocalizedAttributeWhenBodyLevelHasNoLocale(
			boolean entityOpinion, boolean group, @Nonnull Evita evita
		) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final FilterBy filter = entityOpinion ? filterBy(entityHaving(entityLocaleEquals(Locale.ENGLISH))) : null;
				final ReferenceContent content = group ?
					referenceContent("brand", filter, entityGroupFetch(attributeContent("name"))) :
					referenceContent("brand", filter, entityFetch(attributeContent("name")));
				assertThrows(EntityLocaleMissingException.class, () -> fetchProduct(session, content),
					"A body without a resolved locale must refuse a named localized attribute");
			});
		}

		/** Named associated data uses the same body-level locale as named attributes. */
		@ParameterizedTest
		@CsvSource({"false,false", "true,false", "false,true", "true,true"})
		@UseDataSet(DATA_SET)
		@DisplayName("accepts named associated data in the resolved body locale")
		void shouldFetchNamedAssociatedDataWhenOnlyReferenceFilterHasLocale(
			boolean nestedLocale, boolean group, @Nonnull Evita evita
		) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final FilterBy filter = filterBy(nestedLocale ?
					(group ? groupHaving(entityLocaleEquals(Locale.ENGLISH)) :
						entityHaving(entityLocaleEquals(Locale.ENGLISH))) : entityLocaleEquals(Locale.ENGLISH));
				final ReferenceContent content = group ?
					referenceContent("brand", filter, entityGroupFetch(associatedDataContent("description"))) :
					referenceContent("brand", filter, entityFetch(associatedDataContent("description")));
				final SealedEntity body = bodyOf(assertDoesNotThrow(() -> fetchProduct(session, content),
					"Named associated data must use the nested English locale"), group);
				assertEquals("English description 1", body.getAssociatedData("description"));
				final SealedEntity stored = session.getEntity(group ? "BrandGroup" : "Brand", 1,
					associatedDataContentAll(), dataInLocalesAll()).orElseThrow();
				assertNotEquals(stored.<String>getAssociatedData("description", Locale.ENGLISH),
					stored.<String>getAssociatedData("description", Locale.GERMAN));
			});
		}

		/** An explicit data requirement belongs to the current fetch even when the main query has no locale. */
		@ParameterizedTest
		@CsvSource({"false,false", "false,true", "true,false", "true,true"})
		@UseDataSet(DATA_SET)
		@DisplayName("validates each body's explicit dataInLocales")
		void shouldFetchNamedDataWhenNestedFetchRequestsLocales(
			boolean group, boolean associatedData, @Nonnull Evita evita
		) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final EntityContentRequire content = associatedData ?
					associatedDataContent("description") : attributeContent("name");
				final ReferenceContent reference = group ?
					referenceContent("brand", entityGroupFetch(content, dataInLocales(Locale.GERMAN))) :
					referenceContent("brand", entityFetch(content, dataInLocales(Locale.GERMAN)));
				final SealedEntity body = bodyOf(assertDoesNotThrow(() -> fetchProduct(session, reference),
					"A local dataInLocales requirement must permit named localized data"), group);
				assertEquals(Set.of(Locale.GERMAN), body.getLocales());
				assertEquals(associatedData ? "Deutsche Beschreibung 1" :
					(group ? "Deutsche Gruppe 1" : "Deutsche Marke 1"),
					associatedData ? body.getAssociatedData("description", Locale.GERMAN) :
						body.getAttribute("name", Locale.GERMAN));
			});
		}

		/**
		 * A body fetch naming no `dataInLocales` inherits the requirement of the owner fetch, so the locales the owner
		 * requests license named localized data of the referenced and the summarized bodies alike.
		 *
		 * @param associatedData request the named associated data instead of the named attribute
		 * @param level          where the body is fetched: `reference`, `summary` or `summaryOfReference`
		 * @param evita          embedded engine
		 */
		@ParameterizedTest(name = "associatedData={0}, level={1}")
		@CsvSource({"false,reference", "true,reference", "false,summary", "true,summary",
			"false,summaryOfReference", "true,summaryOfReference"})
		@UseDataSet(DATA_SET)
		@DisplayName("accepts named nested data in the locales the owner fetch requests")
		void shouldFetchNamedNestedDataWhenOwnerFetchRequestsLocales(
			boolean associatedData, @Nonnull String level, @Nonnull Evita evita
		) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final EntityContentRequire content = associatedData ?
					associatedDataContent("description") : attributeContent("name");
				final DataInLocales locales = dataInLocales(Locale.ENGLISH, Locale.GERMAN);
				final RequireConstraint[] requirements = switch (level) {
					case "reference" -> new RequireConstraint[]{
						entityFetch(locales, referenceContent("brand", entityFetch(content)))
					};
					case "summary" -> new RequireConstraint[]{
						entityFetch(locales), facetSummary(FacetStatisticsDepth.COUNTS, entityFetch(content))
					};
					case "summaryOfReference" -> new RequireConstraint[]{
						entityFetch(locales),
						facetSummaryOfReference("brand", FacetStatisticsDepth.COUNTS, entityFetch(content))
					};
					default -> throw new IllegalArgumentException("Unexpected level: " + level);
				};

				final EvitaResponse<SealedEntity> response = assertDoesNotThrow(
					() -> session.query(query(collection("Product"), filterBy(entityPrimaryKeyInSet(1)),
						require(requirements)), SealedEntity.class),
					"The owner's dataInLocales must license named localized data of a nested body"
				);

				final SealedEntity body = "reference".equals(level) ?
					bodyOf(response.getRecordData().get(0), false) : summarizedBrand(response, 1);
				assertEquals(associatedData ? "English description 1" : "English brand 1", associatedData ?
					body.getAssociatedData("description", Locale.ENGLISH) : body.getAttribute("name", Locale.ENGLISH));
				assertEquals(associatedData ? "Deutsche Beschreibung 1" : "Deutsche Marke 1", associatedData ?
					body.getAssociatedData("description", Locale.GERMAN) : body.getAttribute("name", Locale.GERMAN));
			});
		}

		/** The second body inherits the first body's opinion and can override it independently. */
		@ParameterizedTest
		@CsvSource({"false", "true"})
		@UseDataSet(DATA_SET)
		@DisplayName("validates named attributes at the second nested body level")
		void shouldFetchNamedAttributeWhenEnclosingBodyProvidesLocale(boolean override, @Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final ReferenceContent leaf = referenceContent("leaf", override ?
					filterBy(entityHaving(entityLocaleEquals(Locale.GERMAN))) : null, entityFetch(attributeContent("name")));
				final SealedEntity first = bodyOf(fetchProduct(session, referenceContent("brand",
					filterBy(entityHaving(entityLocaleEquals(Locale.ENGLISH))), entityFetch(leaf))), false);
				final SealedEntity second = first.getReference("leaf", 1).orElseThrow()
					.getReferencedEntity().orElseThrow();
				assertEquals(override ? "Deutsche Gruppe 1" : "English group 1", second.getAttribute("name"),
					"Named attributes must follow the immediately enclosing body, then an explicit override");
			});
		}

		/** Generic, named and deprecated summaries validate their entity and group levels independently. */
		@ParameterizedTest
		@CsvSource({"false,false,false", "false,false,true", "false,true,false", "false,true,true",
			"true,false,false", "true,false,true", "true,true,false", "true,true,true"})
		@UseDataSet(DATA_SET)
		@DisplayName("validates summary body data in the summary filter locale")
		void shouldFetchNamedDataWhenSummaryLevelProvidesLocale(
			boolean legacy, boolean generic, boolean group, @Nonnull Evita evita
		) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final FilterBy filter = group ? null : filterBy(entityLocaleEquals(Locale.ENGLISH));
				final FilterGroupBy groupFilter = group ? filterGroupBy(entityLocaleEquals(Locale.ENGLISH)) : null;
				final EntityFetch entity = group ? null :
					entityFetch(attributeContent("name"), associatedDataContent("description"));
				final EntityGroupFetch groupFetch = group ?
					entityGroupFetch(attributeContent("name"), associatedDataContent("description")) : null;
				final RequireConstraint summary = summaryRequire(
					legacy, generic, filter, groupFilter, entity, groupFetch
				);

				final EvitaResponse<EntityReference> response = assertDoesNotThrow(
					() -> session.query(query(collection("Product"), require(summary)), EntityReference.class),
					"Summary named data must use its own level's locale"
				);

				final ReferenceSummary result = legacy ? response.getExtraResult(FacetSummary.class) :
					response.getExtraResult(ReferenceSummary.class);
				assertNotNull(result);
				assertEquals(2, result.getReferenceStatistics().size());
				for (final ReferenceSummary.ReferenceGroupStatistics statistics : result.getReferenceStatistics()) {
					final SealedEntity body = (SealedEntity) (group ? statistics.getGroupEntity() :
						statistics.getFacetStatistics().iterator().next().getFacetEntity());
					assertNotNull(body);
					assertEquals(Set.of(Locale.ENGLISH), body.getLocales());
					assertEquals("English " + (group ? "group " : "brand ") + body.getPrimaryKey(),
						body.getAttribute("name"));
					assertEquals("English description " + body.getPrimaryKey(), body.getAssociatedData("description"));
				}
			});
		}

		/** Stop-node validation inherits the referenced body's locale even without a locale on the owner. */
		@ParameterizedTest
		@CsvSource({"false", "true"})
		@UseDataSet(DATA_SET)
		@DisplayName("checks localized hierarchy bounds in the nested body's locale")
		void shouldPlanHierarchyContentWhenBodyLevelProvidesLocale(boolean german, @Nonnull Evita evita) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				final Locale locale = german ? Locale.GERMAN : Locale.ENGLISH;
				final SealedEntity product = assertDoesNotThrow(() -> session.queryOneSealedEntity(query(
					collection("Product"), filterBy(entityPrimaryKeyInSet(2)), require(entityFetch(referenceContent(
						"brand", filterBy(entityHaving(entityLocaleEquals(locale))), entityFetch(hierarchyContent(
							stopAt(node(filterBy(attributeEquals("name", german ? "Deutsche Marke 1" : "English brand 1")))),
							entityFetch(attributeContentAll())
						))
					))))).orElseThrow(), "Hierarchy bound checks must use the nested body's locale");
				final SealedEntity brand = product.getReference("brand", 2).orElseThrow().getReferencedEntity().orElseThrow();
				final SealedEntity parent = (SealedEntity) brand.getParentEntity().orElseThrow();
				assertEquals(1, parent.getPrimaryKey());
				assertEquals(german ? "Deutsche Marke 1" : "English brand 1", parent.getAttribute("name"));
			});
		}
	}

	/** Guards cross-reference boolean semantics against accidental reuse of nested target results as owners. */
	@Nested
	@DisplayName("Nested having cache isolation")
	class HavingCache {
		/**
		 * Each separate query first establishes its own exact owner set. Their set operations are the oracle for
		 * the combined query, repeated with the two reference constraints in both orders.
		 *
		 * @param operation boolean operation to compare
		 * @param route optional planner preference
		 * @param evita embedded engine
		 */
		@ParameterizedTest(name = "{0}, route={1}")
		@MethodSource("io.evitadb.api.functional.reference.ReferenceBodyLocaleFunctionalTest#cacheQueries")
		@UseDataSet(DATA_SET)
		@DisplayName("combines identical nested target filters independently for each reference")
		void shouldCombineReferenceFiltersWhenNestedTargetFilterIsIdentical(
			@Nonnull String operation, @Nullable DebugMode route, @Nonnull Evita evita
		) {
			evita.queryCatalog(TEST_CATALOG, session -> {
				for (final boolean reverse : new boolean[]{false, true}) {
					final FilterConstraint first = referenceHaving(reverse ? "manufacturer" : "brand",
						entityHaving(attributeEquals("code", "X")));
					final FilterConstraint second = referenceHaving(reverse ? "brand" : "manufacturer",
						entityHaving(attributeEquals("code", "X")));
					final List<Integer> firstOwners = owners(session, first, route);
					final List<Integer> secondOwners = owners(session, second, route);
					assertEquals(reverse ? List.of(2, 3) : List.of(1, 3), firstOwners, "First split-query oracle");
					assertEquals(reverse ? List.of(1, 3) : List.of(2, 3), secondOwners, "Second split-query oracle");
					assertNotEquals(firstOwners, secondOwners, "Fixture must distinguish the two reference mappings");
					final Set<Integer> expected = new TreeSet<>(firstOwners);
					final FilterConstraint combined;
					switch (operation) {
						case "and" -> {
							expected.retainAll(secondOwners);
							combined = and(first, second);
						}
						case "or" -> {
							expected.addAll(secondOwners);
							combined = or(first, second);
						}
						case "not" -> {
							expected.removeAll(secondOwners);
							combined = and(first, not(second));
						}
						default -> throw new IllegalArgumentException("Unexpected operation: " + operation);
					}
					assertEquals(List.copyOf(expected), owners(session, combined, route),
						"Combined query must equal the split-query oracle; reverse=" + reverse);
				}
			});
		}
	}

	/**
	 * Fetches one owner without assigning a locale to the main query.
	 *
	 * @param session catalog session
	 * @param content nested reference requirement
	 * @return fetched owner
	 */
	@Nonnull
	private static SealedEntity fetchProduct(@Nonnull EvitaSessionContract session, @Nonnull ReferenceContent content) {
		return session.queryOne(query(collection("Product"), filterBy(entityPrimaryKeyInSet(1)),
			require(entityFetch(content))), SealedEntity.class).orElseThrow();
	}

	/**
	 * Builds one of the four summary requirements - the facet summary or the reference summary, of all references or
	 * of `brand` alone - with the passed filters and exactly one of the bodies.
	 *
	 * @param legacy      build the facet summary instead of the reference summary
	 * @param generic     build the form summarizing all references instead of the one naming `brand`
	 * @param filter      the filter of the referenced entities, NULL for none
	 * @param groupFilter the filter of the groups, NULL for none
	 * @param entity      the fetch of the referenced entity bodies, NULL when the group bodies are fetched
	 * @param groupFetch  the fetch of the group bodies, NULL when the referenced entity bodies are fetched
	 * @return the summary requirement
	 */
	@Nonnull
	private static RequireConstraint summaryRequire(
		boolean legacy, boolean generic, @Nullable FilterBy filter, @Nullable FilterGroupBy groupFilter,
		@Nullable EntityFetch entity, @Nullable EntityGroupFetch groupFetch
	) {
		if (!legacy) {
			return generic ?
				referenceSummary(FacetStatisticsDepth.COUNTS, filter, groupFilter, entity, groupFetch) :
				referenceSummaryOfReference("brand", filter, groupFilter, entity, groupFetch);
		}
		final EntityFetchRequire[] body = new EntityFetchRequire[]{entity == null ? groupFetch : entity};
		return generic ?
			facetSummary(filter, groupFilter, body) : facetSummaryOfReference("brand", filter, groupFilter, body);
	}

	/**
	 * Extracts the fetched target or group body.
	 *
	 * @param product fetched owner
	 * @param group whether to extract the group
	 * @return requested nested body
	 */
	@Nonnull
	private static SealedEntity bodyOf(@Nonnull SealedEntity product, boolean group) {
		final ReferenceContract reference = product.getReference("brand", 1).orElseThrow();
		return (group ? reference.getGroupEntity() : reference.getReferencedEntity()).orElseThrow();
	}

	/**
	 * Extracts the fetched body of one brand from the facet summary of the response.
	 *
	 * @param response    response carrying a facet summary with fetched brand bodies
	 * @param primaryKey  primary key of the brand
	 * @return the fetched brand body
	 */
	@Nonnull
	private static SealedEntity summarizedBrand(@Nonnull EvitaResponse<SealedEntity> response, int primaryKey) {
		final FacetSummary summary = response.getExtraResult(FacetSummary.class);
		assertNotNull(summary);
		for (final ReferenceSummary.ReferenceGroupStatistics group : summary.getReferenceStatistics()) {
			for (final ReferenceSummary.FacetStatistics facet : group.getFacetStatistics()) {
				if (facet.getFacetEntity().getPrimaryKey() == primaryKey) {
					return (SealedEntity) facet.getFacetEntity();
				}
			}
		}
		return fail("The summary must hold brand " + primaryKey);
	}

	/**
	 * Queries all four candidate owners, supplying primary keys so the prefetch route is available.
	 *
	 * @param session catalog session
	 * @param filter owner predicate
	 * @param route optional planner preference
	 * @return matching primary keys in natural order
	 */
	@Nonnull
	private static List<Integer> owners(
		@Nonnull EvitaSessionContract session, @Nonnull FilterConstraint filter, @Nullable DebugMode route
	) {
		return session.query(query(collection("Product"),
			filterBy(entityPrimaryKeyInSet(1, 2, 3, 4), filter),
			require(page(1, 10), route == null ? null : debug(route))), EntityReference.class)
			.getRecordData().stream().map(EntityReference::getPrimaryKey).toList();
	}
}
