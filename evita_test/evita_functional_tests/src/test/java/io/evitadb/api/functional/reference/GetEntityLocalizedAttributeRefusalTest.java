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

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.*;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.EvitaRequest.ReferenceContentKey;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.dataType.Scope;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins schema-name and localized-content validation parity between direct fetches and planned queries.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Direct fetches validate requested content like planned queries")
@ExtendWith(EvitaParameterResolver.class)
@Execution(ExecutionMode.SAME_THREAD)
@Tag(ENGINE)
@Tag(REFERENCE)
class GetEntityLocalizedAttributeRefusalTest {
	/** Writable fixture shared by the direct/planned parity cases in this class. */
	private static final String DATA_SET = "getEntityLocalizedAttributeRefusal";
	/** Writable catalog left in warm-up, which has no transaction to roll a refused call back. */
	private static final String WARM_UP_DATA_SET = "getEntityLocalizedAttributeRefusalWarmUp";

	/**
	 * Thing 1 has an English label, thing 2 a German one; owner 1 references both.
	 *
	 * @param evita engine supplied by `EvitaParameterResolver` to create `DATA_SET`
	 */
	@DataSet(value = DATA_SET, readOnly = false, destroyAfterClass = true)
	void setUp(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("Thing").withoutGeneratedPrimaryKey().withHierarchy()
				.withAttribute("code", String.class, a -> a.filterable().nullable())
				.withAttribute("label", String.class, a -> a.localized().nullable())
				.withAssociatedData("note", String.class, ad -> ad.localized().nullable())
				.withAssociatedData("info", String.class, ad -> ad.nullable())
				.withAttribute("liveUnique", String.class, a -> a.localized().nullable().uniqueInScope(Scope.LIVE))
				.withAttribute("archivedUnique", String.class,
					a -> a.localized().nullable().uniqueInScope(Scope.ARCHIVED))
				.withReferenceToEntity("next", "Thing", Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFilteringAndPartitioning().withGroupTypeRelatedToEntity("Thing"))
				.updateVia(session);
			session.createNewEntity("Thing", 1).setAttribute("code", "a")
				.setAttribute("label", Locale.ENGLISH, "one")
				.setAttribute("label", Locale.GERMAN, "eins").setReference("next", 2, r -> r.setGroup("Thing", 3))
				.setAssociatedData("note", Locale.ENGLISH, "note-one")
				.setAssociatedData("info", "info-one").upsertVia(session);
			session.createNewEntity("Thing", 2).setAttribute("code", "x")
				.setAttribute("label", Locale.GERMAN, "zwei").setAttribute("label", Locale.ENGLISH, "two")
				.setAssociatedData("note", Locale.ENGLISH, "note-two").setParent(1)
				.setReference("next", 3).upsertVia(session);
			session.createNewEntity("Thing", 3).setAttribute("label", Locale.ENGLISH, "group")
				.setAttribute("label", Locale.GERMAN, "gruppe").setParent(1).upsertVia(session);
			session.defineEntitySchema("Owner").withoutGeneratedPrimaryKey()
				.withReferenceToEntity("things", "Thing", Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFilteringAndPartitioning().withGroupTypeRelatedToEntity("Thing")
						.withAttribute("tag", String.class, a -> a.localized().nullable()))
				.updateVia(session);
			session.createNewEntity("Owner", 2).upsertVia(session);
			session.createNewEntity("Owner", 1)
				.setReference("things", 1, r -> r.setGroup("Thing", 3).setAttribute("tag", Locale.ENGLISH, "tag-one"))
				.setReference("things", 2, r -> r.setGroup("Thing", 3))
				.upsertVia(session);
		});
	}

	/**
	 * Defines one schema that evolves on the fly, in a catalog that stays in warm-up.
	 *
	 * @param evita engine supplied by `EvitaParameterResolver` to create `WARM_UP_DATA_SET`
	 */
	@DataSet(
		value = WARM_UP_DATA_SET, readOnly = false, destroyAfterClass = true,
		expectedCatalogState = CatalogState.WARMING_UP
	)
	void setUpWarmUp(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("Evolving").withoutGeneratedPrimaryKey()
				.verifySchemaButCreateOnTheFly().updateVia(session);
		});
	}

	/**
	 * A named localized attribute of the entity is refused without a locale on both paths and read with one.
	 *
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("refuses a named localized attribute of the entity itself")
	void shouldRefuseLocalizedAttributeOfEntityWhenNoLocaleIsGiven(@Nonnull Evita evita) {
		evita.queryCatalog(TEST_CATALOG, session -> {
			assertAll(
				() -> assertThrows(EntityLocaleMissingException.class, () -> session.queryOneSealedEntity(query(
					collection("Thing"), filterBy(entityPrimaryKeyInSet(1)),
					require(entityFetch(attributeContent("label")))
				)), "planned query"),
				() -> assertThrows(EntityLocaleMissingException.class,
					() -> session.getEntity("Thing", 1, attributeContent("label")), "getEntity"),
				() -> assertEquals("one", session.getEntity("Thing", 1, attributeContent("label"),
					dataInLocales(Locale.ENGLISH)).orElseThrow().getAttribute("label"), "getEntity with dataInLocales")
			);
		});
	}

	/**
	 * Named localized associated data of the entity is refused without a locale on both paths.
	 *
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("refuses named localized associated data of the entity itself")
	void shouldRefuseLocalizedAssociatedDataOfEntityWhenNoLocaleIsGiven(@Nonnull Evita evita) {
		evita.queryCatalog(TEST_CATALOG, session -> {
			assertAll(
				() -> assertThrows(EntityLocaleMissingException.class, () -> session.queryOneSealedEntity(query(
					collection("Thing"), filterBy(entityPrimaryKeyInSet(1)),
					require(entityFetch(associatedDataContent("note")))
				)), "planned query"),
				() -> assertThrows(EntityLocaleMissingException.class,
					() -> session.getEntity("Thing", 1, associatedDataContent("note")), "getEntity")
			);
		});
	}

	/**
	 * Bulk content without a locale is accepted on both paths and returns the non-localized values only.
	 *
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("returns only non-localized data when everything is fetched without a locale")
	void shouldReturnOnlyNonLocalizedDataWhenAllIsFetchedWithoutLocale(@Nonnull Evita evita) {
		evita.queryCatalog(TEST_CATALOG, session -> {
			final SealedEntity planned = session.queryOneSealedEntity(query(
				collection("Thing"), filterBy(entityPrimaryKeyInSet(1)),
				require(entityFetch(attributeContentAll(), associatedDataContentAll()))
			)).orElseThrow();
			final SealedEntity direct = session.getEntity(
				"Thing", 1, attributeContentAll(), associatedDataContentAll()
			).orElseThrow();
			for (final SealedEntity entity : new SealedEntity[]{planned, direct}) {
				assertAll(
					() -> assertEquals(Set.of(), entity.getLocales()),
					() -> assertEquals(Set.of("code"), entity.getAttributeValues().stream()
						.map(it -> it.key().attributeName()).collect(Collectors.toSet())),
					() -> assertEquals(Set.of("info"), entity.getAssociatedDataValues().stream()
						.map(it -> it.key().associatedDataName()).collect(Collectors.toSet()))
				);
			}
		});
	}

	/**
	 * A locale named in one branch of an `or` in the reference filter reaches no fetched body on either path.
	 *
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("refuses a named localized attribute of referenced bodies whose locale is named only below or")
	void shouldRefuseLocalizedAttributeOfBodiesWhenLocaleIsOnlyBelowOr(@Nonnull Evita evita) {
		final ReferenceContent content = referenceContent("things",
			filterBy(or(entityHaving(entityLocaleEquals(Locale.ENGLISH)), entityHaving(attributeEquals("code", "x")))),
			entityFetch(attributeContent("label")));
		evita.queryCatalog(TEST_CATALOG, session -> {
			assertAll(
				() -> assertThrows(EntityLocaleMissingException.class, () -> session.queryOneSealedEntity(query(
					collection("Owner"), filterBy(entityPrimaryKeyInSet(1)), require(entityFetch(content))
				)), "planned query"),
				() -> assertThrows(EntityLocaleMissingException.class,
					() -> session.getEntity("Owner", 1, content), "getEntity")
			);
		});
	}

	/**
	 * All checked body levels refuse independently of rows and return localized values with a root locale.
	 *
	 * @param shape fetch level from `@ValueSource` at which localized content must be validated
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@ParameterizedTest
	@ValueSource(strings = {"attribute", "data", "referenceAttribute", "body", "group", "parent", "nested",
		"bodyData", "groupParentReference", "parentReferenceGroup"})
	@DisplayName("validate every fetch level before looking up rows")
	@UseDataSet(DATA_SET)
	void shouldValidateEveryFetchLevel(@Nonnull String shape, @Nonnull Evita evita) {
		final EntityContentRequire content = contentFor(shape);
		final String type = shape.equals("attribute") || shape.equals("data") || shape.equals("parent") ||
			shape.equals("parentReferenceGroup") ? "Thing" : "Owner";
		final int key = type.equals("Thing") ? 2 : 1;
		evita.queryCatalog(TEST_CATALOG, session -> {
			for (final int id : new int[]{key, 999}) {
				assertThrows(EntityLocaleMissingException.class, () -> planned(session, type, id, content));
				assertThrows(EntityLocaleMissingException.class, () -> session.getEntity(type, id, content));
			}
			assertTrue(session.getEntity(type, key).isPresent());
			final SealedEntity direct = session.getEntity(type, key, content, dataInLocales(Locale.ENGLISH))
				.orElseThrow();
			final SealedEntity query = planned(session, type, key, content, dataInLocales(Locale.ENGLISH));
			assertLocalizedContent(shape, direct);
			assertLocalizedContent(shape, query);
		});
	}

	/**
	 * Unknown names are schema errors even for absent entities and empty reference sets, with or without locales.
	 *
	 * @param shape content kind or fetch level from `@ValueSource` at which an unknown name is requested
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@ParameterizedTest
	@ValueSource(strings = {"attribute", "data", "reference", "body", "group", "bodyData", "parent",
			"nestedReference", "referenceAttribute"})
	@DisplayName("refuse unknown names with and without locale context")
	@UseDataSet(DATA_SET)
	void shouldRefuseUnknownNamesIndependentlyOfData(@Nonnull String shape, @Nonnull Evita evita) {
		final EntityContentRequire content = switch (shape) {
			case "attribute" -> attributeContent("unknown");
			case "data" -> associatedDataContent("unknown");
			case "reference" -> referenceContent("unknown");
			case "referenceAttribute" -> referenceContentWithAttributes("things", attributeContent("unknown"));
			case "body" -> referenceContent("things", entityFetch(attributeContent("unknown")));
			case "group" -> referenceContent("things", entityGroupFetch(attributeContent("unknown")));
			case "bodyData" -> referenceContent("things", entityFetch(associatedDataContent("unknown")));
			case "parent" -> hierarchyContent(entityFetch(attributeContent("unknown")));
			case "nestedReference" -> referenceContent("things", entityFetch(referenceContent("unknown")));
			default -> throw new IllegalArgumentException(shape);
		};
		final Class<? extends RuntimeException> exception = shape.equals("data") || shape.equals("bodyData") ?
			AssociatedDataNotFoundException.class : shape.equals("reference") || shape.equals("nestedReference") ?
			ReferenceNotFoundException.class : AttributeNotFoundException.class;
		evita.queryCatalog(TEST_CATALOG, session -> {
			for (final boolean locale : new boolean[]{false, true}) {
				final EntityContentRequire[] requirements = locale ?
					new EntityContentRequire[]{content, dataInLocales(Locale.ENGLISH)} :
					new EntityContentRequire[]{content};
				for (final int id : new int[]{1, 2, 999}) {
					final String type = shape.equals("attribute") || shape.equals("data") || shape.equals("parent") ?
						"Thing" : "Owner";
					assertThrows(exception, () -> session.getEntity(type, id, requirements));
					assertThrows(exception, () -> planned(session, type, id, requirements));
				}
			}
			assertEquals(Scope.LIVE, session.getEntity("Owner", 1).orElseThrow().getScope());
		});
	}

	/**
	 * Every destructive boundary validates before changing entity existence, scope or hierarchy.
	 *
	 * @param operation fetching mutation from `@ValueSource` whose refusal must leave entity data unchanged
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@ParameterizedTest
	@ValueSource(strings = {"upsert", "delete", "hierarchy", "archive", "restore"})
	@DisplayName("refuse mutation fetches before changing entity data")
	@UseDataSet(DATA_SET)
	void shouldValidateBeforeMutation(@Nonnull String operation, @Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			final int key = switch (operation) {
				case "upsert" -> 100;
				case "delete" -> 110;
				case "hierarchy" -> 120;
				case "archive" -> 130;
				case "restore" -> 140;
				default -> throw new IllegalArgumentException(operation);
			};
			if (!operation.equals("upsert")) {
				session.createNewEntity("Thing", key).setAttribute("label", Locale.ENGLISH, "mutation")
					.upsertVia(session);
				if (operation.equals("hierarchy")) {
					session.createNewEntity("Thing", key + 100).setParent(key).upsertVia(session);
				}
				if (operation.equals("restore")) {
					assertTrue(session.archiveEntity("Thing", key));
				}
			}
			assertThrows(EntityLocaleMissingException.class,
				() -> mutate(session, operation, key, attributeContent("label")));
			assertThrows(EntityLocaleMissingException.class, () -> mutate(session, operation, key,
				referenceContent("next", entityFetch(attributeContent("label")))));
			assertThrows(AttributeNotFoundException.class,
				() -> mutate(session, operation, key, attributeContent("unknown"), dataInLocales(Locale.ENGLISH)));
			final Scope expectedScope = operation.equals("restore") ? Scope.ARCHIVED : Scope.LIVE;
			assertEquals(!operation.equals("upsert"), session.getEntity("Thing", key,
				new Scope[]{expectedScope}).isPresent());
			if (!operation.equals("upsert")) {
				assertEquals(expectedScope, session.getEntity("Thing", key,
					new Scope[]{expectedScope}).orElseThrow().getScope());
			}
			if (operation.equals("hierarchy")) {
				assertTrue(session.getEntity("Thing", key + 100).isPresent());
			}
			final SealedEntity result = mutate(session, operation, key,
				attributeContent("label"), dataInLocales(Locale.ENGLISH));
			assertEquals("mutation", result.getAttribute("label", Locale.ENGLISH));
		});
	}

	/**
	 * The collection boundary also protects enrichment callers which do not use the session's locale propagation.
	 *
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@Test
	@DisplayName("validate enrichment at the collection boundary")
	@UseDataSet(DATA_SET)
	void shouldValidateCollectionEnrichment(@Nonnull Evita evita) {
		evita.queryCatalog(TEST_CATALOG, session -> {
			final SealedEntity entity = session.getEntity("Thing", 1).orElseThrow();
			final Catalog catalog = (Catalog) evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
			final EvitaRequest request = new EvitaRequest(query(collection("Thing"),
				require(entityFetch(attributeContent("label")))), OffsetDateTime.now(), SealedEntity.class, "Thing");
			assertThrows(EntityLocaleMissingException.class, () -> catalog
				.getCollectionForEntityOrThrowException("Thing")
				.enrichEntity(entity, request, session));
			assertEquals(Scope.LIVE, session.getEntity("Thing", 1).orElseThrow().getScope());
			assertThrows(AttributeNotFoundException.class,
				() -> session.enrichEntity(entity, attributeContent("unknown")));
			assertThrows(AttributeNotFoundException.class,
				() -> session.enrichOrLimitEntity(entity, attributeContent("unknown")));
		});
	}

	/**
	 * Embedded enrichment propagates one locale, or all locales when the original selection was empty.
	 *
	 * @param oneLocale `@ValueSource` flag selecting English only when `true`, or implicit all locales otherwise
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("preserve embedded enrichment locale propagation")
	@UseDataSet(DATA_SET)
	void shouldPropagateEmbeddedEnrichmentLocales(boolean oneLocale, @Nonnull Evita evita) {
		evita.queryCatalog(TEST_CATALOG, session -> {
			final SealedEntity entity = session.getEntity("Thing", 1, oneLocale ?
				new EntityContentRequire[]{dataInLocales(Locale.ENGLISH)} : new EntityContentRequire[0]).orElseThrow();
			assertEquals("one", session.enrichEntity(entity, attributeContent("label"))
				.getAttribute("label", Locale.ENGLISH));
			assertEquals("one", session.enrichOrLimitEntity(entity, attributeContent("label"))
				.getAttribute("label", Locale.ENGLISH));
		});
	}

	/**
	 * Root selections authorize descendants, while a child override still decides which localized values are read.
	 *
	 * @param selection locale source or override from `@ValueSource` used to build the root and body requirements
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@ParameterizedTest
	@ValueSource(strings = {"all", "one", "two", "override", "body", "group"})
	@DisplayName("inherit and isolate locale availability at body levels")
	@UseDataSet(DATA_SET)
	void shouldInheritAndIsolateBodyLocales(@Nonnull String selection, @Nonnull Evita evita) {
		evita.queryCatalog(TEST_CATALOG, session -> {
			final EntityContentRequire[] requirements = switch (selection) {
				case "all" -> new EntityContentRequire[]{contentFor("body"), dataInLocales()};
				case "one" -> new EntityContentRequire[]{contentFor("body"), dataInLocales(Locale.ENGLISH)};
				case "two" -> new EntityContentRequire[]{
					contentFor("body"), dataInLocales(Locale.ENGLISH, Locale.GERMAN)
				};
				case "override" -> new EntityContentRequire[]{
					referenceContent("things", entityFetch(attributeContent("label"), dataInLocales(Locale.GERMAN))),
					dataInLocales(Locale.ENGLISH)
				};
				case "body" -> new EntityContentRequire[]{referenceContent("things",
					filterBy(and(entityHaving(entityLocaleEquals(Locale.ENGLISH)))),
					entityFetch(attributeContent("label")))};
				case "group" -> new EntityContentRequire[]{referenceContent("things",
					filterBy(and(groupHaving(entityLocaleEquals(Locale.ENGLISH)))),
					entityGroupFetch(attributeContent("label")))};
				default -> throw new IllegalArgumentException(selection);
			};
			for (final SealedEntity owner : new SealedEntity[]{planned(session, "Owner", 1, requirements),
				session.getEntity("Owner", 1, requirements).orElseThrow()}) {
				final SealedEntity body = selection.equals("group") ? owner.getReference("things", 1).orElseThrow()
					.getGroupEntity().orElseThrow() : owner.getReference("things", 1).orElseThrow()
					.getReferencedEntity().orElseThrow();
				if (selection.equals("override")) {
					assertEquals("eins", body.getAttribute("label", Locale.GERMAN));
				} else {
					assertEquals(
						selection.equals("group") ? "group" : "one", body.getAttribute("label", Locale.ENGLISH)
					);
				}
				if (selection.equals("all") || selection.equals("two")) {
					assertEquals("eins", body.getAttribute("label", Locale.GERMAN));
				}
			}
			final ReferenceContent isolated = referenceContentWithAttributes("things",
				filterBy(entityHaving(entityLocaleEquals(Locale.ENGLISH))), attributeContent("tag"),
				entityFetch(attributeContent("label")));
			assertThrows(EntityLocaleMissingException.class, () -> planned(session, "Owner", 1, isolated));
			assertThrows(EntityLocaleMissingException.class, () -> session.getEntity("Owner", 1, isolated));
		});
	}

	/**
	 * A locale an intermediate body names reaches the bodies and the hierarchy parents fetched below it.
	 *
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@Test
	@DisplayName("inherit an intermediate body locale into nested bodies and hierarchy parents")
	@UseDataSet(DATA_SET)
	void shouldInheritIntermediateBodyLocaleIntoNestedLevels(@Nonnull Evita evita) {
		final EntityContentRequire content = referenceContent("things", entityFetch(dataInLocales(Locale.GERMAN),
			referenceContent("next", entityFetch(attributeContent("label"))),
			hierarchyContent(entityFetch(attributeContent("label")))));
		evita.queryCatalog(TEST_CATALOG, session -> {
			for (final SealedEntity owner : new SealedEntity[]{planned(session, "Owner", 1, content),
				session.getEntity("Owner", 1, content).orElseThrow()}) {
				final SealedEntity nested = owner.getReference("things", 1).orElseThrow().getReferencedEntity()
					.orElseThrow().getReference("next", 2).orElseThrow().getReferencedEntity().orElseThrow();
				final SealedEntity parent = owner.getReference("things", 2).orElseThrow().getReferencedEntity()
					.orElseThrow().getParentEntity().map(SealedEntity.class::cast).orElseThrow();

				assertEquals("zwei", nested.getAttribute("label", Locale.GERMAN));
				assertEquals("eins", parent.getAttribute("label", Locale.GERMAN));
			}
		});
	}

	/**
	 * Scope-sensitive uniqueness uses any root processing scope on both planned and direct paths.
	 *
	 * @param name localized attribute from `@ValueSource`, unique in either live or archived scope
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@ParameterizedTest
	@ValueSource(strings = {"liveUnique", "archivedUnique"})
	@DisplayName("apply the same unique attribute exemption in every root scope")
	@UseDataSet(DATA_SET)
	void shouldUseRootScopesForUniqueExemption(@Nonnull String name, @Nonnull Evita evita) {
		evita.queryCatalog(TEST_CATALOG, session -> {
			for (final Scope[] scopes : new Scope[][]{{Scope.LIVE}, {Scope.ARCHIVED}, {Scope.LIVE, Scope.ARCHIVED}}) {
				final boolean accepted = scopes.length == 2 || (name.equals("liveUnique") == (scopes[0] == Scope.LIVE));
				for (final String level :
					new String[]{"entity", "body", "group", "parent", "belowGroup", "belowParent"}) {
					final String type = level.equals("entity") || level.equals("parent") ||
						level.equals("belowParent") ? "Thing" : "Owner";
					final EntityContentRequire content = switch (level) {
						case "entity" -> attributeContent(name);
						case "body" -> referenceContent("things", filterBy(entityHaving(scope(Scope.ARCHIVED))),
							entityFetch(attributeContent(name)));
						case "group" -> referenceContent("things", entityGroupFetch(attributeContent(name)));
						case "parent" -> hierarchyContent(entityFetch(attributeContent(name)));
						case "belowGroup" -> referenceContent("things", entityGroupFetch(referenceContent("next",
							entityFetch(attributeContent(name)))));
						case "belowParent" -> hierarchyContent(entityFetch(referenceContent("next",
							entityFetch(attributeContent(name)))));
						default -> throw new IllegalArgumentException(level);
					};
					if (accepted) {
						assertDoesNotThrow(() -> session.getEntity(type, 1, scopes, content));
						assertDoesNotThrow(() -> session.queryOneSealedEntity(query(collection(type),
							filterBy(scope(scopes), entityPrimaryKeyInSet(1)), require(entityFetch(content)))));
					} else {
						assertThrows(
							EntityLocaleMissingException.class, () -> session.getEntity(type, 1, scopes, content)
						);
						assertThrows(
							EntityLocaleMissingException.class,
							() -> session.queryOneSealedEntity(query(collection(type),
								filterBy(scope(scopes), entityPrimaryKeyInSet(1)), require(entityFetch(content))))
						);
					}
				}
			}
		});
	}

	/**
	 * Validation sees the schema evolved by this upsert, but refusal still precedes applying entity mutations. A
	 * refusal caught inside `updateCatalog` does not fail the transaction: a new session sees the session's other
	 * writes, the evolved schema and the created collection, but not the refused entity.
	 *
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@Test
	@DisplayName("validate fetching upserts against automatically evolved schemas")
	@UseDataSet(DATA_SET)
	void shouldValidateEvolvedSchemaBeforeUpsert(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("Evolving").withoutGeneratedPrimaryKey()
				.verifySchemaButCreateOnTheFly().updateVia(session);
			assertEquals("new", session.upsertAndFetchEntity(
					session.createNewEntity("Evolving", 1).setAttribute("newLabel", Locale.ENGLISH, "new"),
					attributeContent("newLabel"), dataInLocales(Locale.ENGLISH)
				)
				.getAttribute("newLabel", Locale.ENGLISH));
			assertThrows(EntityLocaleMissingException.class, () -> session.upsertAndFetchEntity(
				session.createNewEntity("Evolving", 2).setAttribute("refusedLabel", Locale.ENGLISH, "refused"),
				attributeContent("refusedLabel")));
			assertTrue(session.getEntity("Evolving", 2).isEmpty());
			assertEquals("plain", session.upsertAndFetchEntity(session.createNewEntity("Evolving", 3)
				.setAttribute("newPlain", "plain"), attributeContent("newPlain")).getAttribute("newPlain"));
			refuseUpsertCreatingCollection(session, "CreatedByRefusal");
		});

		assertRefusedUpsertsKeptSchemaChanges(evita);
	}

	/**
	 * A refusal that escapes a call made directly on the session marks its transaction rollback-only, so closing the
	 * session discards everything it wrote - the evolved schema and the created collection included.
	 *
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@Test
	@DisplayName("roll back the transaction a refused fetching upsert escapes from")
	@UseDataSet(DATA_SET)
	void shouldRollBackTransactionWhenRefusalEscapesSessionCall(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("RolledBack").withoutGeneratedPrimaryKey()
				.verifySchemaButCreateOnTheFly().updateVia(session);
		});
		final EvitaSessionContract session = evita.createReadWriteSession(TEST_CATALOG);
		session.createNewEntity("RolledBack", 1).setAttribute("code", "control").upsertVia(session);

		assertThrows(EntityLocaleMissingException.class, () -> session.upsertAndFetchEntity(
			session.createNewEntity("RolledBack", 2).setAttribute("refusedLabel", Locale.ENGLISH, "refused"),
			attributeContent("refusedLabel")));
		refuseUpsertCreatingCollection(session, "CreatedByEscapedRefusal");

		assertTrue(session.isRollbackOnly());
		assertThrows(RollbackException.class, session::close);
		evita.queryCatalog(TEST_CATALOG, reader -> {
			assertAll(
				() -> assertTrue(
					reader.getEntitySchemaOrThrowException("RolledBack").getAttribute("refusedLabel").isEmpty()
				),
				() -> assertTrue(reader.getEntity("RolledBack", 1).isEmpty()),
				() -> assertFalse(reader.getAllEntityTypes().contains("CreatedByEscapedRefusal"))
			);
		});
	}

	/**
	 * A catalog in warm-up has no transaction, and a refused fetching upsert leaves the same state behind.
	 *
	 * @param evita engine supplied by `EvitaParameterResolver` with `WARM_UP_DATA_SET` loaded
	 */
	@Test
	@DisplayName("keep the schema changes of a refused fetching upsert in a warm-up catalog")
	@UseDataSet(WARM_UP_DATA_SET)
	void shouldKeepSchemaChangesOfRefusedUpsertInWarmUpCatalog(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.createNewEntity("Evolving", 1).setAttribute("newPlain", "plain").upsertVia(session);
			assertThrows(EntityLocaleMissingException.class, () -> session.upsertAndFetchEntity(
				session.createNewEntity("Evolving", 2).setAttribute("refusedLabel", Locale.ENGLISH, "refused"),
				attributeContent("refusedLabel")));
			refuseUpsertCreatingCollection(session, "CreatedByRefusal");
		});

		assertRefusedUpsertsKeptSchemaChanges(evita);
	}

	/**
	 * Bulk-only content remains legal throughout the fetch tree.
	 *
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@Test
	@DisplayName("accept bulk-only content at every level without a locale")
	@UseDataSet(DATA_SET)
	void shouldAcceptBulkOnlyRequirements(@Nonnull Evita evita) {
		evita.queryCatalog(TEST_CATALOG, session -> {
			final EntityContentRequire[] content = {attributeContentAll(), associatedDataContentAll(),
				referenceContentWithAttributes("next", attributeContentAll(), entityFetch(attributeContentAll(),
					associatedDataContentAll(), hierarchyContent(entityFetch(attributeContentAll())))),
				hierarchyContent(entityFetch(attributeContentAll(), associatedDataContentAll()))};
			assertEquals(Set.of(), planned(session, "Thing", 2, content).getLocales());
			assertEquals(Set.of(), session.getEntity("Thing", 2, content).orElseThrow().getLocales());
			final ReferenceContent allReferences = referenceContentAllWithAttributes(
				entityFetch(attributeContentAll()), entityGroupFetch(associatedDataContentAll()));
			assertEquals(2, planned(session, "Owner", 1, allReferences).getReferences("things").size());
			assertEquals(2, session.getEntity("Owner", 1, allReferences).orElseThrow().getReferences("things").size());
		});
	}

	/**
	 * Named leaves are validated as written even when adjacent bulk requirements absorb them for fetching.
	 *
	 * @param level content kind or fetch level from `@ValueSource` where a named leaf is placed beside bulk content
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@ParameterizedTest
	@ValueSource(strings = {"attribute", "data", "body", "group", "parent", "nested", "referenceAttribute"})
	@DisplayName("refuse named localized content next to bulk content as written")
	@UseDataSet(DATA_SET)
	void shouldValidateNamedContentBesideBulk(@Nonnull String level, @Nonnull Evita evita) {
		final EntityContentRequire[] leaves = {attributeContent("label"), attributeContentAll()};
		final EntityContentRequire content = switch (level) {
			case "attribute" -> attributeContent("label");
			case "data" -> associatedDataContent("note");
			case "body" -> referenceContent("next", entityFetch(leaves));
			case "group" -> referenceContent("next", entityGroupFetch(leaves));
			case "parent" -> hierarchyContent(entityFetch(leaves));
			case "nested" -> referenceContent("next", entityFetch(referenceContent("next", entityFetch(leaves))));
			case "referenceAttribute" -> new ReferenceContent(null, ManagedReferencesBehaviour.ANY,
				new String[]{"things"}, new RequireConstraint[]{attributeContent("tag"), attributeContentAll()},
				new Constraint<?>[0]);
			default -> throw new IllegalArgumentException(level);
		};
		final EntityContentRequire[] requirements = level.equals("attribute") ? leaves : level.equals("data") ?
			new EntityContentRequire[]{associatedDataContent("note"), associatedDataContentAll()} :
			new EntityContentRequire[]{content};
		final String type = level.equals("referenceAttribute") ? "Owner" : "Thing";
		evita.queryCatalog(TEST_CATALOG, session -> {
			assertThrows(EntityLocaleMissingException.class, () -> planned(session, type, 1, requirements));
			assertThrows(EntityLocaleMissingException.class, () -> session.getEntity(type, 1, requirements));
			assertEquals(Scope.LIVE, session.getEntity(type, 1).orElseThrow().getScope());
		});
	}

	/**
	 * Named instances and several explicit names retain the schema checks before any reference rows are sliced.
	 *
	 * @param shape reference form from `@ValueSource`: a named instance, several names, or an empty slice
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@ParameterizedTest
	@ValueSource(strings = {"named", "several", "emptySlice"})
	@DisplayName("validate named instances, explicit name arrays and zero-result slices")
	@UseDataSet(DATA_SET)
	void shouldValidateReferenceShapesBeforeSlicing(@Nonnull String shape, @Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("ReferenceShapes").withoutGeneratedPrimaryKey()
				.withReferenceToEntity("first", "Thing", Cardinality.ZERO_OR_MORE)
				.withReferenceToEntity("second", "Thing", Cardinality.ZERO_OR_MORE).updateVia(session);
			if (session.getEntity("ReferenceShapes", 1).isEmpty()) {
				session.createNewEntity("ReferenceShapes", 1).setReference("first", 1).setReference("second", 2)
					.upsertVia(session);
			}
			final String[] names = shape.equals("several") ? new String[]{"first", "second"} : new String[]{"first"};
			final ReferenceContent content = new ReferenceContent(shape.equals("named") ? "picked" : null,
				ManagedReferencesBehaviour.ANY, names,
				new RequireConstraint[]{entityFetch(attributeContent("label")), strip(0, 1)},
				shape.equals("emptySlice") ? new Constraint<?>[]{filterBy(entityPrimaryKeyInSet(999))} :
					new Constraint<?>[0]);
			assertThrows(EntityLocaleMissingException.class, () -> planned(session, "ReferenceShapes", 1, content));
			assertThrows(EntityLocaleMissingException.class, () -> session.getEntity("ReferenceShapes", 1, content));
			assertEquals(Scope.LIVE, session.getEntity("ReferenceShapes", 1).orElseThrow().getScope());
			for (final SealedEntity owner : new SealedEntity[]{planned(session, "ReferenceShapes", 1, content,
				dataInLocales(Locale.ENGLISH)), session.getEntity("ReferenceShapes", 1, content,
				dataInLocales(Locale.ENGLISH)).orElseThrow()}) {
				if (shape.equals("named")) {
					assertEquals("one", ((ServerEntityDecorator) owner).getReferencesForReferenceContentInstance(
						new ReferenceContentKey("picked", "first")).orElseThrow().getData().getFirst()
						.getReferencedEntity().orElseThrow().getAttribute("label", Locale.ENGLISH));
				} else if (shape.equals("emptySlice")) {
					assertTrue(owner.getReferenceChunk("first").isEmpty());
				} else {
					assertEquals("one", owner.getReference("first", 1).orElseThrow().getReferencedEntity()
						.orElseThrow().getAttribute("label", Locale.ENGLISH));
					assertEquals("two", owner.getReference("second", 2).orElseThrow().getReferencedEntity()
						.orElseThrow().getAttribute("label", Locale.ENGLISH));
				}
			}
			if (shape.equals("several")) {
				assertThrows(ReferenceNotFoundException.class, () -> planned(session, "ReferenceShapes", 1,
					referenceContent("first", "unknown")));
				assertThrows(ReferenceNotFoundException.class, () -> session.getEntity("ReferenceShapes", 1,
					referenceContent("first", "unknown")));
			}
		});
	}

	/**
	 * Missing attributes and associated data accumulate across siblings, including after an authorized sibling.
	 *
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@Test
	@DisplayName("report missing locale names across all levels")
	@UseDataSet(DATA_SET)
	void shouldCollectDiagnosticsAcrossLevels(@Nonnull Evita evita) {
		evita.queryCatalog(TEST_CATALOG, session -> {
			final EntityLocaleMissingException error = assertThrows(EntityLocaleMissingException.class,
				() -> session.getEntity("Owner", 1, referenceContentWithAttributes("things", attributeContent("tag"),
					entityFetch(attributeContent("label"), dataInLocales(Locale.ENGLISH)),
					entityGroupFetch(
						associatedDataContent("note"), hierarchyContent(entityFetch(attributeContent("label")))
					))));
			assertEquals(Set.of("tag", "label"), Set.of(error.getAttributeNames()));
			assertEquals(Set.of("note"), Set.of(error.getAssociatedDataNames()));
			assertEquals(Scope.LIVE, session.getEntity("Owner", 1).orElseThrow().getScope());
		});
	}

	/**
	 * A name missing at several levels is reported once, as the planned query reports it.
	 *
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@Test
	@DisplayName("report a name missing at several levels once")
	@UseDataSet(DATA_SET)
	void shouldReportEachMissingNameOnce(@Nonnull Evita evita) {
		final ReferenceContent content = referenceContent("things", entityFetch(attributeContent("label")),
			entityGroupFetch(attributeContent("label")));
		evita.queryCatalog(TEST_CATALOG, session -> {
			final EntityLocaleMissingException direct = assertThrows(EntityLocaleMissingException.class,
				() -> session.getEntity("Owner", 1, content));
			final EntityLocaleMissingException plannedError = assertThrows(EntityLocaleMissingException.class,
				() -> planned(session, "Owner", 1, content));

			assertArrayEquals(new String[]{"label"}, direct.getAttributeNames());
			assertArrayEquals(plannedError.getAttributeNames(), direct.getAttributeNames());
		});
	}

	/**
	 * Automatic evolution includes localized associated data and reference attributes before fetch validation.
	 *
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@Test
	@DisplayName("validate newly evolved associated data and reference attributes")
	@UseDataSet(DATA_SET)
	void shouldValidateEvolvedAssociatedDataAndReferenceAttributes(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("EvolvingContent").withoutGeneratedPrimaryKey()
				.verifySchemaButCreateOnTheFly()
				.withReferenceToEntity("fresh", "Thing", Cardinality.ZERO_OR_MORE).updateVia(session);
			assertEquals("new-note", session.upsertAndFetchEntity(session.createNewEntity("EvolvingContent", 1)
				.setAssociatedData("note", Locale.ENGLISH, "new-note"), associatedDataContent("note"),
				dataInLocales(Locale.ENGLISH)).getAssociatedData("note", Locale.ENGLISH));
			assertThrows(EntityLocaleMissingException.class, () -> session.upsertAndFetchEntity(
				session.createNewEntity("EvolvingContent", 2).setAssociatedData("refusedNote", Locale.ENGLISH, "note"),
				associatedDataContent("refusedNote")));
			assertTrue(session.getEntity("EvolvingContent", 2).isEmpty());
			assertEquals("new-tag", session.upsertAndFetchEntity(session.createNewEntity("EvolvingContent", 3)
				.setReference("fresh", 1, r -> r.setAttribute("tag", Locale.ENGLISH, "new-tag")),
				referenceContentWithAttributes("fresh", attributeContent("tag")), dataInLocales(Locale.ENGLISH))
				.getReference("fresh", 1).orElseThrow().getAttribute("tag", Locale.ENGLISH));
			assertThrows(EntityLocaleMissingException.class, () -> session.upsertAndFetchEntity(
				session.createNewEntity("EvolvingContent", 4)
					.setReference("fresh", 1, r -> r.setAttribute("refusedTag", Locale.ENGLISH, "tag")),
				referenceContentWithAttributes("fresh", attributeContent("refusedTag"))));
			assertTrue(session.getEntity("EvolvingContent", 4).isEmpty());
		});
	}

	/**
	 * Wildcard validation still checks managed siblings beside unmanaged targets and absent body/group rows.
	 *
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@Test
	@DisplayName("check managed wildcard siblings while preserving unmanaged fetch behavior")
	@UseDataSet(DATA_SET)
	void shouldCheckManagedSiblingsBesideUnmanagedTargets(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("Mixed").withoutGeneratedPrimaryKey()
				.withReferenceTo(
					"external", "External", Cardinality.ZERO_OR_MORE, r -> r.withGroupType("ExternalGroup")
				)
				.withReferenceToEntity("managed", "Thing", Cardinality.ZERO_OR_MORE)
				.updateVia(session);
			session.createNewEntity("Mixed", 1).setReference("external", 1, r -> r.setGroup("ExternalGroup", 1))
				.setReference("managed", 999).upsertVia(session);
			assertThrows(EntityLocaleMissingException.class, () -> session.getEntity("Mixed", 1,
				referenceContentAll(entityFetch(attributeContent("label")))));
			assertThrows(AttributeNotFoundException.class, () -> session.getEntity("Mixed", 1,
				referenceContentAll(entityFetch(attributeContent("unknown"))), dataInLocales(Locale.ENGLISH)));
			assertThrows(EntityNotManagedException.class, () -> session.getEntity("Mixed", 1,
				referenceContent("external", entityFetch(attributeContent("unknown")))));
			assertTrue(session.getEntity("Mixed", 1,
				referenceContent("external", entityGroupFetch(attributeContent("unknown")))).isPresent());
			assertEquals(Scope.LIVE, session.getEntity("Mixed", 1).orElseThrow().getScope());
		});
	}

	/**
	 * An empty collection has the same schema contract as a populated one.
	 *
	 * @param evita engine supplied by `EvitaParameterResolver` with `DATA_SET` loaded
	 */
	@Test
	@DisplayName("validate requests for empty collections")
	@UseDataSet(DATA_SET)
	void shouldValidateAnEmptyCollection(@Nonnull Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("Empty").withoutGeneratedPrimaryKey()
				.withAttribute("label", String.class, a -> a.localized().nullable()).updateVia(session);
			assertThrows(
				EntityLocaleMissingException.class, () -> session.getEntity("Empty", 1, attributeContent("label"))
			);
			assertThrows(
				EntityLocaleMissingException.class, () -> planned(session, "Empty", 1, attributeContent("label"))
			);
			assertThrows(
				ReferenceNotFoundException.class, () -> session.getEntity("Empty", 1, referenceContent("unknown"))
			);
			assertThrows(
				ReferenceNotFoundException.class, () -> planned(session, "Empty", 1, referenceContent("unknown"))
			);
			assertTrue(session.getEntity("Empty", 1).isEmpty());
		});
	}

	/**
	 * Returns one body-level shape used by both the planned and direct assertions.
	 *
	 * @param shape content kind or fetch path used by `shouldValidateEveryFetchLevel` and locale inheritance cases
	 * @return requirements requesting a named localized value at the selected fetch level
	 */
	@Nonnull
	private static EntityContentRequire contentFor(@Nonnull String shape) {
		return switch (shape) {
			case "attribute" -> attributeContent("label");
			case "data" -> associatedDataContent("note");
			case "referenceAttribute" -> referenceContentWithAttributes("things", attributeContent("tag"));
			case "body" -> referenceContent("things", entityFetch(attributeContent("label")));
			case "group" -> referenceContent("things", entityGroupFetch(attributeContent("label")));
			case "parent" -> hierarchyContent(entityFetch(attributeContent("label")));
			case "nested" -> referenceContent("things", entityFetch(referenceContent("next", entityFetch(
				referenceContent("next", entityFetch(attributeContent("label")))))));
			case "bodyData" -> referenceContent("things", entityFetch(associatedDataContent("note")));
			case "groupParentReference" -> referenceContent("things", entityGroupFetch(hierarchyContent(entityFetch(
				referenceContent("next", entityFetch(attributeContent("label")))))));
			case "parentReferenceGroup" -> hierarchyContent(entityFetch(referenceContent("next",
				entityGroupFetch(attributeContent("label")))));
			default -> throw new IllegalArgumentException(shape);
		};
	}

	/**
	 * Checks actual returned localized values, independently of the validation outcome.
	 *
	 * @param shape fetch path from the `shouldValidateEveryFetchLevel` value source, selecting the expected value
	 * @param entity result of a planned or direct fetch with English content requested
	 */
	private static void assertLocalizedContent(@Nonnull String shape, @Nonnull SealedEntity entity) {
		switch (shape) {
			case "attribute" -> assertEquals("two", entity.getAttribute("label", Locale.ENGLISH));
			case "data" -> assertEquals("note-two", entity.getAssociatedData("note", Locale.ENGLISH));
			case "referenceAttribute" -> assertEquals("tag-one", entity.getReference("things", 1).orElseThrow()
				.getAttribute("tag", Locale.ENGLISH));
			case "body" -> assertEquals("one", entity.getReference("things", 1).orElseThrow()
				.getReferencedEntity().orElseThrow().getAttribute("label", Locale.ENGLISH));
			case "group" -> assertEquals("group", entity.getReference("things", 1).orElseThrow()
				.getGroupEntity().orElseThrow().getAttribute("label", Locale.ENGLISH));
			case "parent" -> assertEquals("one", entity.getParentEntity().map(SealedEntity.class::cast)
				.orElseThrow().getAttribute("label", Locale.ENGLISH));
			case "nested" -> assertEquals("group", entity.getReference("things", 1).orElseThrow()
				.getReferencedEntity().orElseThrow()
				.getReference("next", 2).orElseThrow().getReferencedEntity().orElseThrow()
				.getReference("next", 3).orElseThrow().getReferencedEntity().orElseThrow()
				.getAttribute("label", Locale.ENGLISH));
			case "bodyData" -> assertEquals("note-one", entity.getReference("things", 1).orElseThrow()
				.getReferencedEntity().orElseThrow().getAssociatedData("note", Locale.ENGLISH));
			case "groupParentReference" -> assertEquals("two", entity.getReference("things", 2).orElseThrow()
				.getGroupEntity().orElseThrow().getParentEntity().map(SealedEntity.class::cast).orElseThrow()
				.getReference("next", 2).orElseThrow()
				.getReferencedEntity().orElseThrow().getAttribute("label", Locale.ENGLISH));
			case "parentReferenceGroup" -> assertEquals("group", entity.getParentEntity().map(SealedEntity.class::cast)
				.orElseThrow().getReference("next", 2).orElseThrow().getGroupEntity().orElseThrow()
				.getAttribute("label", Locale.ENGLISH));
			default -> throw new IllegalArgumentException(shape);
		}
	}

	/**
	 * Refuses a fetching upsert of an entity type that has no collection yet - the call creates the collection.
	 *
	 * @param session writable session in which the refused upsert may evolve the schema and create a collection
	 * @param entityType new collection name whose creation is expected to survive or roll back with the caller
	 */
	private static void refuseUpsertCreatingCollection(
		@Nonnull EvitaSessionContract session, @Nonnull String entityType
	) {
		assertThrows(EntityLocaleMissingException.class, () -> session.upsertAndFetchEntity(
			new InitialEntityBuilder(entityType, 1).setAttribute("label", Locale.ENGLISH, "refused")
				.toMutation().orElseThrow(),
			attributeContent("label")));
	}

	/**
	 * Checks in a new session that the refused upserts wrote no entity, while the schema they evolved, the collection
	 * they created and the entity the session wrote before them stay.
	 *
	 * @param evita engine holding the catalog after refused upserts, used to inspect their effects in a new session
	 */
	private static void assertRefusedUpsertsKeptSchemaChanges(@Nonnull Evita evita) {
		evita.queryCatalog(TEST_CATALOG, session -> {
			assertAll(
				() -> assertTrue(
					session.getEntitySchemaOrThrowException("Evolving").getAttribute("refusedLabel").isPresent()
				),
				() -> assertTrue(session.getEntity("Evolving", 1).isPresent()),
				() -> assertTrue(session.getEntity("Evolving", 2).isEmpty()),
				() -> assertEquals(0, session.getEntityCollectionSize("CreatedByRefusal"))
			);
		});
	}

	/**
	 * Runs the planned counterpart for a primary-key direct request.
	 *
	 * @param session reader used to execute the planned counterpart in the same catalog as the direct request
	 * @param type collection containing the entity under test
	 * @param key primary key to match; the caller must expect an existing entity
	 * @param requirements content and locale selection to apply to the query result; empty fetches only the body
	 * @return the matching entity with the requested content; absence fails the assertion path
	 */
	@Nonnull
	private static SealedEntity planned(
		@Nonnull EvitaSessionContract session,
		@Nonnull String type,
		int key,
		@Nonnull EntityContentRequire... requirements
	) {
		return session.queryOneSealedEntity(query(collection(type), filterBy(entityPrimaryKeyInSet(key)),
			require(entityFetch(requirements)))).orElseThrow();
	}

	/**
	 * Executes one fetching mutation; callers inspect existence and scopes immediately after refusals.
	 *
	 * @param session writable session in which the selected mutation and its fetch validation run
	 * @param operation mutation selector: `upsert`, `delete`, `hierarchy`, `archive`, or `restore`
	 * @param key primary key of the `Thing` entity to mutate
	 * @param requirements content and locale selection to fetch from the mutation result
	 * @return the mutated entity, or the deleted root for a hierarchy deletion, with the requested content
	 */
	@Nonnull
	private static SealedEntity mutate(
		@Nonnull EvitaSessionContract session, @Nonnull String operation, int key,
		@Nonnull EntityContentRequire... requirements
	) {
		return switch (operation) {
			case "upsert" -> session.upsertAndFetchEntity(session.createNewEntity("Thing", key)
				.setAttribute("label", Locale.ENGLISH, "mutation"), requirements);
			case "delete" -> session.deleteEntity("Thing", key, requirements).orElseThrow();
			case "hierarchy" -> session.deleteEntityAndItsHierarchy("Thing", key, requirements).deletedRootEntity();
			case "archive" -> session.archiveEntity("Thing", key, requirements).orElseThrow();
			case "restore" -> session.restoreEntity("Thing", key, requirements).orElseThrow();
			default -> throw new IllegalArgumentException(operation);
		};
	}
}
