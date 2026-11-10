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

package io.evitadb.api.functional.fulltext;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.core.cache.CacheAnteroom;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.cache.HeapMemoryCacheSupervisor;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.exception.AttributeNotSearchableException;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.query.QueryPlan;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.fulltext.FulltextFormula;
import io.evitadb.core.query.algebra.fulltext.FulltextScoreAccessor;
import io.evitadb.core.query.algebra.fulltext.FulltextScoreCombiner;
import io.evitadb.core.query.algebra.fulltext.ReferenceFulltextScoreAccessor;
import io.evitadb.core.query.algebra.fulltext.ReferenceOwnerFulltextScoreAccessor;
import io.evitadb.core.query.sort.fulltext.RelevanceTextSorter;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencePartitionSplit;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.fulltext.FulltextScoringPolicy;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.PlanPreference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.*;

/** Real planner regressions for direct reference text, sharing one catalog across all partition representations. */
@ExtendWith(EvitaParameterResolver.class)
@DisplayName("Fulltext within one reference row")
@Tag(ENGINE)
@Tag(FULLTEXT)
@Tag(QUERY)
@Tag(REFERENCE)
class FulltextReferenceHavingFunctionalTest {
	private static final String DATA = "fulltextReferenceRows";
	private static final String TRANSITIONS_DATA = "fulltextReferenceTransitions";
	private static final String CATALOG = "testCatalog";
	private static final Locale CZECH = Locale.forLanguageTag("cs");
	private static final int[] OWNERS = IntStream.rangeClosed(1, 11).toArray();

	private static Scheduler scheduler;
	private static HeapMemoryCacheSupervisor cache;

	/** Enables the real formula cache with explicit synchronous promotion rather than time-based assertions. */
	@BeforeAll
	static void startCache() {
		scheduler = new Scheduler(ThreadPoolOptions.requestThreadPoolBuilder()
			.minThreadCount(1).maxThreadCount(2).build());
		cache = new HeapMemoryCacheSupervisor(CacheOptions.builder().enabled(true)
			.reevaluateEachSeconds(3600).anteroomRecordCount(10_000)
			.minimalComplexityThreshold(1L).minimalUsageThreshold(1).cacheSizeInBytes(1_000_000L).build(), scheduler);
	}

	/** Releases both cache resources and its scheduler at the end of the shared fixture. */
	@AfterAll
	static void stopCache() {
		cache.close();
		scheduler.shutdown();
	}

	/** The same logical rows written as shared objects, single-owner registry rows or both. */
	enum Representation {
		MATERIALIZED, VIRTUAL, MIXED
	}

	/** Builds all representations and weight variants once; clones create genuinely shared target partitions. */
	@DataSet(DATA)
	void createDataSet(@Nonnull Evita evita) {
		evita.updateCatalog(CATALOG, session -> {
			session.getCatalogSchema().openForWrite()
				.withFulltextLanguage("cs", language -> language.wordNumberSplit()).updateVia(session);
			for (final Representation representation : Representation.values()) {
				final String type = representation.name();
				final EntitySchemaBuilder schema = session.defineEntitySchema(type).withoutGeneratedPrimaryKey()
					.withLocale(CZECH, Locale.ENGLISH)
					.withAttribute("label", String.class, a -> a.localized().nullable()
						.searchableInScope(Scope.values()))
					.withAttribute("enabled", Boolean.class, a -> a.filterableInScope(Scope.values()));
				for (final int weight : new int[]{1, 3, 0}) {
					schema.withReferenceTo("labels" + weight, "Target", Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
						r -> r.indexedForFilteringInScope(Scope.values())
							.indexedWithComponents(ReferenceIndexedComponents.REFERENCED_ENTITY)
							.withAttribute("label", String[].class, a -> a.localized().nullable()
								.searchableInScope(Scope.values()).searchWeight(weight))
							.withAttribute("other", String[].class, a -> a.localized().nullable()
								.searchableInScope(Scope.values()).searchWeight(weight))
							.withAttribute("primary", Boolean.class, a -> a.filterableInScope(Scope.values()))
							.withAttribute("variant", Integer.class,
								a -> a.filterableInScope(Scope.values()).representative())
							.withAttribute("liveOnly", String.class, a -> a.localized().nullable().searchable()));
				}
				schema.withReferenceTo("transitions", "Target", Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFiltering().withAttribute("label", String.class,
						a -> a.localized().nullable().searchable()));
				schema.updateVia(session);
				for (final int pk : OWNERS) {
					writeOwner(session, representation, pk, pk);
					if (representation == Representation.MATERIALIZED ||
						representation == Representation.MIXED && pk % 2 == 1) {
						writeOwner(session, representation, pk + 100, pk);
					}
				}
				session.defineEntitySchema("Root" + type).withoutGeneratedPrimaryKey().withLocale(CZECH)
					.withAttribute("name", String.class, a -> a.localized().searchable())
					.withReferenceToEntity("owners", type, Cardinality.ZERO_OR_MORE, r -> r.indexedForFiltering()
						.indexedWithComponents(ReferenceIndexedComponents.REFERENCED_ENTITY,
							ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY)
						.withGroupTypeRelatedToEntity(type)
						.withAttribute("note", String.class,
							a -> a.localized().nullable().searchable().searchWeight(3)))
					.updateVia(session);
				for (int root = 1; root <= 2; root++) {
					final int referencedOwner = root == 1 ? 3 : 1;
					session.upsertEntity(session.createNewEntity("Root" + type, root)
						.setAttribute("name", CZECH, "root").setReference("owners", referencedOwner, r -> {
							r.setGroup(type, referencedOwner);
							if (referencedOwner == 3) {
								r.setAttribute("note", CZECH, "reference");
							}
						}));
				}

				session.upsertEntity(session.createNewEntity(type, 201).setScope(Scope.ARCHIVED)
					.setAttribute("label", CZECH, "archiv")
					.setAttribute("enabled", true)
					.setOrUpdateReference("labels1", -201, r -> false, r -> r.setAttribute("variant", 1)
						.setAttribute("primary", true)
						.setAttribute("label", CZECH, new String[]{"zimní"})));
			}
			session.defineEntitySchema("Empty").withoutGeneratedPrimaryKey().withLocale(CZECH)
				.withReferenceTo("labels1", "Target", Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFilteringInScope(Scope.values())
						.withAttribute("label", String[].class, a -> a.localized().nullable().searchable())
						.withAttribute("primary", Boolean.class, a -> a.filterable()))
				.updateVia(session);
			createHierarchyCollections(session);
		});
	}

	/**
	 * Adds a hierarchy whose parent filter searches the tags of another collection: tag 7 is shared by categories 1
	 * and 2 (a materialized partition), tag 8 belongs to category 3 alone (a virtual one). Tag keys stay disjoint
	 * from category keys, so a partition resolved in the wrong collection can never coincide with the right one.
	 */
	private static void createHierarchyCollections(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema("Category").withoutGeneratedPrimaryKey().withHierarchy().withLocale(CZECH)
			.withAttribute("name", String.class, a -> a.localized())
			.withReferenceTo("tags", "Tag", Cardinality.ZERO_OR_MORE, r -> r.indexedForFiltering()
				.indexedWithComponents(ReferenceIndexedComponents.REFERENCED_ENTITY)
				.withAttribute("label", String.class, a -> a.localized().nullable().searchable()))
			.updateVia(session);
		session.defineEntitySchema("Product").withoutGeneratedPrimaryKey().withLocale(CZECH)
			.withAttribute("name", String.class, a -> a.localized())
			.withReferenceToEntity("categories", "Category", Cardinality.ZERO_OR_MORE, r -> r.indexedForFiltering()
				.indexedWithComponents(ReferenceIndexedComponents.REFERENCED_ENTITY))
			.updateVia(session);
		final String[] labels = {"Zimní bundy", "Letní šály", "Zimní bundy"};
		for (int category = 1; category <= 3; category++) {
			final String label = labels[category - 1];
			session.upsertEntity(session.createNewEntity("Category", category)
				.setAttribute("name", CZECH, "kategorie")
				.setReference("tags", category == 3 ? 8 : 7, r -> r.setAttribute("label", CZECH, label)));
			session.upsertEntity(session.createNewEntity("Product", category)
				.setAttribute("name", CZECH, "produkt")
				.setReference("categories", category));
		}
	}

	/** Reuses the fixture for partition transitions with writes enabled and explicit class-end disposal. */
	@DataSet(value = TRANSITIONS_DATA, readOnly = false, destroyAfterClass = true)
	void createTransitionDataSet(@Nonnull Evita evita) {
		createDataSet(evita);
	}

	/** Writes one logical owner, optionally cloned to force a second owner into each partition. */
	private static void writeOwner(EvitaSessionContract session, Representation representation, int pk, int logical) {
		final EntityBuilder owner = session.createNewEntity(representation.name(), pk)
			.setAttribute("label", CZECH, "owneronly").setAttribute("label", Locale.ENGLISH, "owneronly")
			.setAttribute("enabled", true);
		for (final int weight : new int[]{1, 3, 0}) {
			final String reference = "labels" + weight;
			switch (logical) {
				case 1 -> {
					row(owner, representation, pk, logical, reference, 1, true, new String[]{"Zimní čepice"});
					row(owner, representation, pk, logical, reference, 2, false, new String[]{"Letní bundy"});
				}
				case 2 -> {
					row(owner, representation, pk, logical, reference, 1, false, new String[]{"Zimní bundy"});
					row(owner, representation, pk, logical, reference, 2, true, new String[]{"Červené šály"});
				}
				case 3 -> row(owner, representation, pk, logical, reference, 1, true, new String[]{"Zimní bundy"});
				case 4 -> row(owner, representation, pk, logical, reference, 1, true,
					new String[]{"Zimní"}, "bundy");
				case 5 -> row(owner, representation, pk, logical, reference, 1, true,
					new String[]{"Zimní", "bundy", "bundy"}, "abcdefgh", "123456789");
				case 6 -> { /* This owner deliberately has no reference rows. */ }
				case 7 -> row(owner, representation, pk, logical, reference, 1, true, new String[0]);
				case 8 -> row(owner, representation, pk, logical, reference, 1, true,
					new String[]{weight == 3 ? "Zimní bundy" : "Letní bundy"});
				case 9 -> {
					row(owner, representation, pk, logical, reference, 1, true, new String[]{"Zimní bundy"});
					row(owner, representation, pk, logical, reference, 2, false, new String[]{"Červené šály"});
				}
				case 10 -> {
					row(owner, representation, pk, logical, reference, 1, true, new String[]{"Zimní"});
					row(owner, representation, pk, logical, reference, 2, false, new String[]{"bundy"});
				}
				case 11 -> {
					row(owner, representation, pk, logical, reference, 1, true,
						new String[]{"Zimní bundy"}, "abc123", "abcdefgh");
					row(owner, representation, pk, logical, reference, 2, false, new String[]{"Zimní bundy"});
				}
				default -> throw new IllegalArgumentException("Unknown fixture owner");
			}
		}
		session.upsertEntity(owner);
	}

	/** Creates an indexed row with signed targets and a representative value distinguishing duplicate rows. */
	private static void row(
		EntityBuilder owner, Representation representation, int pk, int logical, String reference,
		int slot, boolean primary, String[] label, String... other
	) {
		final boolean shared = representation == Representation.MATERIALIZED ||
			representation == Representation.MIXED && logical % 2 == 1;
		final int rowSlot = logical == 10 ? 1 : slot;
		final int target = shared ? (rowSlot == 1 ? 0 : -10) : -pk * 100 - rowSlot;
		owner.setOrUpdateReference(reference, target,
			r -> Integer.valueOf(slot).equals(r.getAttribute("variant")), r -> {
				r.setAttribute("variant", slot).setAttribute("primary", primary);
				if (label.length > 0) {
					r.setAttribute("label", CZECH, label);
				}
				if (other.length > 0) {
					r.setAttribute("other", CZECH, other);
				}
				r.setAttribute("label", Locale.ENGLISH, new String[]{slot == 1 ? "winter" : "jackets"});
		});
	}

	@Nested
	@DisplayName("Row membership")
	class Membership {

		/** Cross-target words stay pooled only at the top level; a body may combine fields and array elements. */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Requires every word within one target row across all partition paths")
		void shouldMatchWordsWithinOneReferenceRow(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				assertPath(evita, session, representation);
				for (final PlanPreference plan : PlanPreference.values()) {
					assertIds(session, representation, plan, new int[]{2, 3, 4, 5, 9, 10, 11},
						referenceHaving("labels1", textMatches("zimní bundy ")));
					assertIds(session, representation, plan, new int[]{1, 2, 3, 4, 5, 8, 9, 10, 11},
						textMatches("zimní bundy "));
					assertIds(session, representation, plan, new int[]{3, 4, 5, 9, 10, 11},
						referenceHaving("labels1", and(attributeEquals("primary", true),
							and(textMatches("zimní bundy "), entityPrimaryKeyInSet(0, -10,
								-201, -301, -401, -501, -901, -1001, -1101)))));
				}
			});
		}

		/** Complements are per existing row, including missing text, never the collection's owner union. */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Resolves NOT, double NOT, and disjunctions inside each row")
		void shouldComplementTextPerRow(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				final FilterConstraint phi = textMatches("zimní bundy ");
				for (final PlanPreference plan : PlanPreference.values()) {
					assertIds(session, representation, plan, new int[]{1, 2, 7, 8, 9},
						referenceHaving("labels1", not(phi)));
					assertIds(session, representation, plan, new int[]{2, 3, 4, 5, 9, 10, 11},
						referenceHaving("labels1", not(not(phi))));
					assertIds(session, representation, plan, new int[]{1, 2, 3, 4, 5, 9, 10, 11},
						referenceHaving("labels1", or(phi, not(attributeEquals("primary", true)))));
					final int[] existence = ids(session, representation, plan, referenceHaving("labels1"));
					assertArrayEquals(new int[]{1, 2, 3, 4, 5, 7, 8, 9, 10, 11}, existence);
					assertArrayEquals(existence, ids(session, representation, plan,
						or(referenceHaving("labels1", phi), referenceHaving("labels1", not(phi)))));
				}
			});
		}

		/** Duplicate pairs deliberately share both words and sibling predicates, and can lose negative matches. */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Pins duplicate-pair word and sibling over-matches and missed negation")
		void shouldPinDuplicatePairLimitation(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				for (final PlanPreference plan : PlanPreference.values()) {
					assertIds(session, representation, plan, new int[]{10}, and(entityPrimaryKeyInSet(10),
						referenceHaving("labels1", textMatches("zimní bundy "))));
					assertIds(session, representation, plan, new int[]{10}, and(entityPrimaryKeyInSet(10),
						referenceHaving("labels1", attributeEquals("primary", true), textMatches("bundy "))));
					assertIds(session, representation, plan, new int[0], and(entityPrimaryKeyInSet(10),
						referenceHaving("labels1", not(textMatches("zimní bundy ")))));
				}
			});
		}

		/** Shared preparation preserves prefix, typo, split-word and analyzer legs for the reference consumer. */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Uses the shared prefix, typo and split-word preparation")
		void shouldReuseExpandedAndSplitWords(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				for (final PlanPreference plan : PlanPreference.values()) {
					assertIds(session, representation, plan, new int[]{5, 11},
						referenceHaving("labels1", textMatches("abcdefgh123456789 ")));
				}
				final long[] split = scores(evita, session, representation,
					referenceHaving("labels1", textMatches("abcdefgh123456789 ")));
				assertNotEquals(0L, split[10], "An alphabetic part alone satisfies split-word membership");
				assertTrue((split[4] & FulltextScoringPolicy.QUALITY_MASK) >
					(split[10] & FulltextScoringPolicy.QUALITY_MASK), "Both parts rank above partial split coverage");
				for (final String text :
					new String[]{"zimní bund", "zimni bundy ", "zimní bunxy ", "abc123", "abc 123"}) {
					for (final PlanPreference plan : PlanPreference.values()) {
						final int[] top = ids(session, representation, plan,
							and(entityPrimaryKeyInSet(11), textMatches(text)));
						assertArrayEquals(new int[]{11}, top, "Fixture must exercise a retained expansion: " + text);
						assertArrayEquals(top, ids(session, representation, plan, and(entityPrimaryKeyInSet(11),
							referenceHaving("labels1", textMatches(text)))));
					}
				}
			});
		}

		/** Equal text in different reference homes must retain distinct membership memos and score places. */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Keeps reference names and direct text membership separate from top-level union")
		void shouldDistinguishReferenceHomes(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				for (final PlanPreference plan : PlanPreference.values()) {
					assertIds(session, representation, plan, new int[]{8}, and(entityPrimaryKeyInSet(8),
						referenceHaving("labels3", textMatches("zimní bundy ")),
						not(referenceHaving("labels1", textMatches("zimní bundy ")))));
				}
			});
		}
	}

	@Nested
	@DisplayName("Selector validation and refusals")
	class Validation {

		/** Reference selectors cannot bind owner fields, cross locale boundaries or search an unavailable scope. */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Validates reference selectors and isolates locale and owner scope")
		void shouldValidateReferenceSelectorsAndScopes(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				for (final PlanPreference plan : PlanPreference.values()) {
					assertIds(session, representation, plan, new int[0], and(entityPrimaryKeyInSet(4),
						referenceHaving("labels1", textMatches("bundy ", inField("label")))));
					assertIds(session, representation, plan, new int[]{4}, and(entityPrimaryKeyInSet(4),
						referenceHaving("labels1", textMatches("bundy ", inField("other")))));
					assertIds(session, representation, plan, new int[]{4}, and(entityPrimaryKeyInSet(4),
						referenceHaving("labels1", textMatches("zimní bundy "))));
				}
				for (final String field : new String[]{"label", "other"}) {
					assertIds(session, representation, PlanPreference.INDEX_SCAN, new int[0],
						and(entityPrimaryKeyInSet(4),
							referenceHaving("labels1", textMatches("zimní bundy ", inField(field)))));
				}
				assertIds(session, representation, PlanPreference.INDEX_SCAN, new int[0],
					referenceHaving("labels1", textMatches("owneronly ", inField("label"))));
				assertIds(session, representation, PlanPreference.INDEX_SCAN, new int[0],
					referenceHaving("labels1", textMatches("winter jackets ")));
				final AttributeNotFoundException absent = assertThrows(AttributeNotFoundException.class,
					() -> ids(session, representation, PlanPreference.INDEX_SCAN,
						referenceHaving("labels1", textMatches("x", inField("absent")))));
				assertTrue(absent.getMessage().contains("`absent`") && absent.getMessage().contains("`labels1`"),
					absent.getMessage());
				final AttributeNotSearchableException notSearchable = assertThrows(
					AttributeNotSearchableException.class,
					() -> ids(session, representation, PlanPreference.INDEX_SCAN,
						referenceHaving("labels1", textMatches("x", inField("primary")))));
				assertTrue(notSearchable.getMessage().contains("labels1"));
				final AttributeNotSearchableException liveOnly = assertThrows(AttributeNotSearchableException.class,
					() -> session.query(query(collection(representation.name()),
						filterBy(scope(Scope.LIVE, Scope.ARCHIVED), entityLocaleEquals(CZECH),
							referenceHaving("labels1", textMatches("x", inField("liveOnly"))))),
					EntityReference.class));
				assertTrue(liveOnly.getMessage().contains("`liveOnly`") && liveOnly.getMessage().contains("`ARCHIVED`"),
					liveOnly.getMessage());
				final EvitaInvalidUsageException noLocale = assertThrows(EvitaInvalidUsageException.class,
					() -> session.query(query(collection(representation.name()),
						filterBy(referenceHaving("labels1", textMatches("zimní")))), EntityReference.class));
				assertTrue(noLocale.getMessage().contains("requires the query locale"), noLocale.getMessage());
				final EvitaResponse<EntityReference> crossedScope = session.query(query(
					collection(representation.name()),
					filterBy(scope(Scope.LIVE, Scope.ARCHIVED), entityLocaleEquals(CZECH), entityPrimaryKeyInSet(201),
						referenceHaving("labels1", textMatches("zimní bundy ")))), EntityReference.class);
				assertEquals(0, crossedScope.getTotalRecordCount());
			});
		}

		/** Planning a collection that holds no entity still validates the selectors against the reference. */
		@Test
		@UseDataSet(DATA)
		@DisplayName("Validates selectors of a reference whose collection holds no entity")
		void shouldValidateSelectorsOfEmptyCollection(Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				for (final String selector : new String[]{"absent", "primary"}) {
					final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
						() -> session.query(query(collection("Empty"), filterBy(entityLocaleEquals(CZECH),
							referenceHaving("labels1", textMatches("x", inField(selector))))), EntityReference.class));
					assertTrue(error.getMessage().contains(selector), error.getMessage());
					assertTrue(error.getMessage().contains("labels1"), error.getMessage());
				}
			});
		}

		/** A reference body is translated on its own constraint stack, which must not hide an outer `userFilter`. */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Refuses direct reference text nested under userFilter")
		void shouldRefuseDirectTextUnderUserFilter(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
					() -> session.query(query(collection(representation.name()),
						filterBy(entityLocaleEquals(CZECH), userFilter(or(
							referenceHaving("labels1", textMatches("zimní bundy ")),
							attributeEquals("enabled", false))))), EntityReference.class));
				assertTrue(error.getMessage().contains("userFilter"), error.getMessage());
			});
		}

		/** Fetch-time row compilation keeps refusing direct text rather than evaluating it against fetched rows. */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Refuses direct text in the filter of fetched references")
		void shouldRefuseDirectTextInFetchedReferences(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				final EvitaInvalidUsageException fetch = assertThrows(EvitaInvalidUsageException.class,
					() -> session.query(query(collection(representation.name()),
						filterBy(entityLocaleEquals(CZECH), entityPrimaryKeyInSet(3)),
						require(entityFetch(referenceContent("labels1", filterBy(textMatches("zimní")))))),
						SealedEntity.class));
				assertTrue(fetch.getMessage().contains("fetched references"), fetch.getMessage());
				assertTrue(fetch.getMessage().contains("textMatches"), fetch.getMessage());
			});
		}
	}

	@Nested
	@DisplayName("Ranking")
	class Ranking {

		/** A cross-row decoy holds the words only across two rows, so it scores nothing while a row match does. */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Scores only owners with a row holding every word")
		void shouldScoreOnlyOwnersWithAMatchingRow(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				final long[] scores = scores(evita, session, representation,
					referenceHaving("labels1", textMatches("zimní bundy ")));

				assertEquals(0L, scores[0], "Cross-row union decoy has no positive body score");
				assertTrue(FulltextScoringPolicy.strength(scores[2]) > 0);
			});
		}

		/** Owner 11 matches in two rows and owner 3 in one; equivalent union postings score once. */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Does not amplify an owner matching in several rows")
		void shouldNotAmplifyOwnerMatchingInSeveralRows(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				final long[] scores = scores(evita, session, representation,
					referenceHaving("labels1", textMatches("zimní bundy ")));

				assertEquals(scores[2], scores[10], "Two identical matched rows must not amplify union impacts");
			});
		}

		/** Two equal constraints in one filter are one place: they score as one occurrence would. */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Scores equal constraints as one place")
		void shouldScoreEqualConstraintsAsOnePlace(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				final FilterConstraint phi = referenceHaving("labels1", textMatches("zimní bundy "));

				assertArrayEquals(scores(evita, session, representation, phi), scores(evita, session, representation,
					or(phi, referenceHaving("labels1", textMatches("zimní bundy ")))));
			});
		}

		/** Direct text keeps the reference attribute's union impact; the 0.5 factor belongs to nested target text. */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Keeps a matched owner's union impact without the referenced-entity factor")
		void shouldKeepUnionImpactWithoutReferencedEntityFactor(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				final Query query = query(collection(representation.name()), filterBy(entityLocaleEquals(CZECH),
					entityPrimaryKeyInSet(OWNERS), referenceHaving("labels1", textMatches("zimní bundy "))),
					orderBy(relevanceText()));
				final QueryPlanningContext context = context(evita, session, representation, query);
				final QueryPlan plan = QueryPlanner.planQuery(context);
				final FulltextScoreAccessor[] sources = field(plan.getSorters().iterator().next(), "accessors");
				plan.execute();
				final long owner = FulltextScoreCombiner.combine(OWNERS, sources, context.getFulltextScoringPolicy())
					.composites()[2];
				final FulltextFormula union = field(sources[0], "union");

				assertNotEquals(0L, owner, "Owner 3 matches within one row and must score");
				assertEquals(union.getFulltextScores(OWNERS).composites()[2], owner,
					"Direct text must keep the union impact with no referenced-entity 0.5 multiplier");
			});
		}

		/** The same rows under a reference whose attributes weigh 3 rank above the weight-1 reference. */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Applies the reference attribute's search weight")
		void shouldApplyReferenceAttributeSearchWeight(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				final long[] one = scores(evita, session, representation,
					referenceHaving("labels1", textMatches("zimní bundy ")));
				final long[] weighted = scores(evita, session, representation,
					referenceHaving("labels3", textMatches("zimní bundy ")));

				assertTrue(FulltextScoringPolicy.strength(weighted[2]) > FulltextScoringPolicy.strength(one[2]));
			});
		}

		/** Weight zero removes strength only: quality lanes still rank and the rows still match. */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Keeps quality lanes and membership under a zero search weight")
		void shouldKeepQualityLanesAndMembershipUnderZeroWeight(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				final long[] zero = scores(evita, session, representation,
					referenceHaving("labels0", textMatches("zimní bundy ")));

				assertEquals(0, FulltextScoringPolicy.strength(zero[2]));
				assertNotEquals(0L, zero[2], "Weight zero preserves quality lanes and membership");
				assertIds(session, representation, PlanPreference.INDEX_SCAN, new int[]{2, 3, 4, 5, 9, 10, 11},
					referenceHaving("labels0", textMatches("zimní bundy ")));
			});
		}

		/** Owners admitted only through a negated row borrow no positive score from their other rows. */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Scores no owner through a negated text occurrence")
		void shouldNotScoreOwnersThroughNegatedText(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				final long[] negated = scores(evita, session, representation,
					referenceHaving("labels1", not(textMatches("zimní bundy "))));

				assertEquals(0L, negated[0], "An owner admitted only through negative rows has zero positive score");
				assertEquals(0L, negated[1], "Owner 2 must not borrow its other row's positive score");
				assertEquals(0L, negated[8], "Owner 9 must not borrow its other row's positive score");
			});
		}

		/** An even number of negations is a positive occurrence and scores like the plain text. */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Scores double negation like the positive text")
		void shouldScoreDoubleNegationLikePositiveText(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				assertArrayEquals(
					scores(evita, session, representation, referenceHaving("labels1", textMatches("zimní bundy "))),
					scores(evita, session, representation,
						referenceHaving("labels1", not(not(textMatches("zimní bundy "))))));
			});
		}

		/** A negated occurrence of the same text beside a positive one adds nothing to the positive mask. */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Ignores a negated occurrence beside a positive one")
		void shouldIgnoreNegatedOccurrenceBesidePositiveOne(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				final FilterConstraint phi = referenceHaving("labels1", textMatches("zimní bundy "));

				assertArrayEquals(scores(evita, session, representation, and(entityPrimaryKeyInSet(3), phi)),
					scores(evita, session, representation, or(and(entityPrimaryKeyInSet(3), phi),
						and(entityPrimaryKeyInSet(2, 9),
							referenceHaving("labels1", not(textMatches("zimní bundy ")))))));
			});
		}

		/**
		 * Distinct reference homes, owner text, unequal constraints and scope copies retain the intended place
		 * algebra.
		 */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Combines distinct places and collapses equal scope copies without losing masks")
		void shouldCombineDistinctPlacesAndCollapseScopes(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				final Query mixed = query(collection(representation.name()), filterBy(entityLocaleEquals(CZECH),
					or(textMatches("zimní bundy "), referenceHaving("labels1", textMatches("zimní bundy ")),
						referenceHaving("labels3", textMatches("zimní bundy ")))), orderBy(relevanceText()));
				final QueryPlan mixedPlan = QueryPlanner.planQuery(context(evita, session, representation, mixed));
				final FulltextScoreAccessor[] mixedSources =
					field(mixedPlan.getSorters().iterator().next(), "accessors");
				assertEquals(3, mixedSources.length, "Owner and two direct reference homes must be three places");
				int direct = 0;
				int positiveDecoy = 0;
				for (final FulltextScoreAccessor source : mixedSources) {
					if (source instanceof ReferenceFulltextScoreAccessor) {
						direct++;
						assertEquals(0L, source.getFulltextScores(new int[]{1}).composites()[0]);
					}
					if (source.getFulltextScores(new int[]{1}).composites()[0] != 0L) {
						positiveDecoy++;
					}
				}
				assertEquals(2, direct);
				assertEquals(1, positiveDecoy, "Only top-level union may score the cross-row decoy");
				final Query unequal = query(collection(representation.name()), filterBy(entityLocaleEquals(CZECH),
					or(referenceHaving("labels1", textMatches("zimní ")),
						referenceHaving("labels1", textMatches("bundy ")))), orderBy(relevanceText()));
				final QueryPlan unequalPlan = QueryPlanner.planQuery(context(evita, session, representation, unequal));
				final FulltextScoreAccessor[] unequalSources = field(
					unequalPlan.getSorters().iterator().next(), "accessors");
				assertEquals(2, unequalSources.length, "Unequal direct constraints remain distinct places");
				final Query scoped = query(collection(representation.name()),
					filterBy(scope(Scope.LIVE, Scope.ARCHIVED), entityLocaleEquals(CZECH),
						entityPrimaryKeyInSet(3, 201), referenceHaving("labels1", textMatches("zimní "))),
					orderBy(relevanceText()));
				final QueryPlanningContext context = context(evita, session, representation, scoped);
				final QueryPlan scopedPlan = QueryPlanner.planQuery(context);
				final FulltextScoreAccessor[] scopedSources =
					field(scopedPlan.getSorters().iterator().next(), "accessors");
				assertEquals(1, scopedSources.length, "Scope copies of one direct constraint collapse");
				scopedPlan.getFilter().initialize(context.getInternalExecutionContext());
				assertArrayEquals(new int[]{3, 201}, scopedPlan.getFilter().compute().getArray());
				for (final long score : scopedSources[0].getFulltextScores(new int[]{3, 201}).composites()) {
					assertNotEquals(0L, score);
				}
			});
		}

		/** Direct reference places combine beside own, nested entity and nested group text, preserving their masks. */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Combines own, direct, nested entity and nested group score sources")
		void shouldCombineOwnDirectEntityAndGroupScores(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				final String type = "Root" + representation.name();
				final Query query = query(collection(type), filterBy(entityLocaleEquals(CZECH), or(
					textMatches("root ", inField("name")), referenceHaving("owners", textMatches("reference ")),
					referenceHaving("owners", entityHaving(textMatches("owneronly ", inField("label")))),
					referenceHaving("owners", groupHaving(textMatches("owneronly ", inField("label"))))
				)), orderBy(relevanceText()));
				final QueryPlanningContext context = context(evita, session, type, query, NoCacheSupervisor.INSTANCE);
				final QueryPlan plan = QueryPlanner.planQuery(context);
				final FulltextScoreAccessor[] sources = field(plan.getSorters().iterator().next(), "accessors");
				assertEquals(4, sources.length);
				int mapped = 0;
				int direct = 0;
				for (final FulltextScoreAccessor source : sources) {
					if (source instanceof ReferenceOwnerFulltextScoreAccessor) {
						mapped++;
						assertNotEquals(0L, source.getFulltextScores(new int[]{2}).composites()[0]);
					} else if (source instanceof ReferenceFulltextScoreAccessor) {
						direct++;
						assertEquals(0L, source.getFulltextScores(new int[]{2}).composites()[0]);
					}
				}
				assertEquals(2, mapped, "Entity and group mappings keep their distinct weighted places");
				assertEquals(1, direct);
				final long[] combined = FulltextScoreCombiner.combine(new int[]{1, 2}, sources,
					context.getFulltextScoringPolicy()).composites();
				assertTrue(FulltextScoringPolicy.strength(combined[0]) > FulltextScoringPolicy.strength(combined[1]));
				final EvitaResponse<EntityReference> response = session.query(query, EntityReference.class);
				assertArrayEquals(new int[]{1, 2}, response.getRecordData().stream()
					.mapToInt(EntityReference::getPrimaryKey).toArray());
			});
		}
	}

	@Nested
	@DisplayName("Partition representations and collections")
	class PartitionPaths {

		/** Query membership follows actual partition promotion and demotion while owners hold different values. */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(TRANSITIONS_DATA)
		@DisplayName("Keeps reference text exact across virtual-to-shared-to-virtual transitions")
		void shouldFollowPartitionTransitions(Representation representation, Evita evita) {
			final String type = representation.name();
			try {
				evita.updateCatalog(CATALOG, session -> {
					session.upsertEntity(session.createNewEntity(type, 1000)
						.setAttribute("label", CZECH, "owner").setAttribute("enabled", true)
						.setReference("transitions", -9999, r -> r.setAttribute("label", CZECH, "transition")));
				});
				assertTransition(evita, representation, false, new int[]{1000});
				evita.updateCatalog(CATALOG, session -> {
					session.upsertEntity(session.createNewEntity(type, 1001)
						.setAttribute("label", CZECH, "owner").setAttribute("enabled", true)
						.setReference("transitions", -9999, r -> r.setAttribute("label", CZECH, "unrelated")));
				});
				assertTransition(evita, representation, true, new int[]{1000});
				evita.queryCatalog(CATALOG, session -> {
					final EvitaResponse<EntityReference> negative = session.query(query(collection(type),
						filterBy(entityLocaleEquals(CZECH), referenceHaving("transitions",
							not(textMatches("transition "))))), EntityReference.class);
					assertArrayEquals(new int[]{1001}, negative.getRecordData().stream()
						.mapToInt(EntityReference::getPrimaryKey).toArray());
				});
				evita.updateCatalog(CATALOG, session -> {
					session.deleteEntity(type, 1001);
				});
				assertTransition(evita, representation, false, new int[]{1000});
			} finally {
				evita.updateCatalog(CATALOG, session -> {
					session.deleteEntity(type, 1000);
					session.deleteEntity(type, 1001);
				});
			}
		}

		/**
		 * Nested collection contexts must resolve local partition identities in the collection owning the text
		 * fields.
		 */
		@ParameterizedTest
		@EnumSource(Representation.class)
		@UseDataSet(DATA)
		@DisplayName("Resolves reference text partitions in the nested owner collection")
		void shouldResolvePartitionsInTheirOwnCollection(Representation representation, Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				final EvitaResponse<EntityReference> response = session.query(query(
					collection("Root" + representation.name()),
					filterBy(entityLocaleEquals(CZECH), referenceHaving("owners", entityHaving(
						referenceHaving("labels1", textMatches("zimní bundy ")))))), EntityReference.class);
				assertArrayEquals(new int[]{1}, response.getRecordData().stream()
					.mapToInt(EntityReference::getPrimaryKey).toArray());
			});
		}

		/** A hierarchy parent filter is planned over the category collection with the product query's own context. */
		@Test
		@UseDataSet(DATA)
		@DisplayName("Discovers direct reference text of a collection other than the queried one")
		void shouldDiscoverReferenceTextOfAnotherCollectionInHierarchyParentFilter(Evita evita) {
			evita.queryCatalog(CATALOG, session -> {
				for (final PlanPreference plan : PlanPreference.values()) {
					final EvitaResponse<EntityReference> response = session.query(query(collection("Product"),
						filterBy(plan.filter(new int[]{1, 2, 3}, entityLocaleEquals(CZECH),
							hierarchyWithin("categories", referenceHaving("tags", textMatches("zimní bundy "))))),
						orderBy(entityPrimaryKeyNatural(OrderDirection.ASC)),
						require(page(1, 100), plan.debug(), queryTelemetry())), EntityReference.class);
					plan.assertTaken(response);
					assertArrayEquals(new int[]{1, 3}, response.getRecordData().stream()
						.mapToInt(EntityReference::getPrimaryKey).toArray(), plan.name());
				}
			});
		}
	}

	/** Checks both production membership and the real split after a partition's owner count changes. */
	private static void assertTransition(
		Evita evita, Representation representation, boolean shared, int[] expected
	) {
		evita.queryCatalog(CATALOG, session -> {
			final Query query = query(collection(representation.name()), filterBy(entityLocaleEquals(CZECH),
				referenceHaving("transitions", textMatches("transition "))));
			final EvitaResponse<EntityReference> response = session.query(query, EntityReference.class);
			assertArrayEquals(expected, response.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey)
				.toArray());
			final QueryPlanningContext context = context(evita, session, representation, query);
			final ReferencedTypeEntityIndex type = context.getEntityIndex(representation.name(),
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "transitions"),
				ReferencedTypeEntityIndex.class).orElseThrow();
			final ReferencePartitionSplit split = context.splitPartitionsForRead(representation.name(), "transitions",
				type.getAllPrimaryKeys(), Scope.LIVE);
			assertEquals(shared, !split.getMaterializedPrimaryKeys().isEmpty());
			assertEquals(shared ? 0 : 1, split.getVirtualCount());
		});
	}

	/** Asserts owner keys while proving that the requested prefetch policy actually ran. */
	private static void assertIds(
		EvitaSessionContract session, Representation representation, PlanPreference plan,
		int[] expected, FilterConstraint constraint
	) {
		assertArrayEquals(expected, ids(session, representation, plan, constraint), representation + ": " + constraint);
	}

	/** Runs a real query using neutral owner predicates to make forced prefetch available. */
	private static int[] ids(
		EvitaSessionContract session, Representation representation, PlanPreference plan, FilterConstraint constraint
	) {
		final EvitaResponse<EntityReference> response = session.query(query(collection(representation.name()),
			filterBy(plan.filter(OWNERS, entityLocaleEquals(CZECH), entityPrimaryKeyInSet(OWNERS),
				attributeEquals("enabled", true), constraint)), orderBy(entityPrimaryKeyNatural(OrderDirection.ASC)),
			require(page(1, 100), plan.debug(), queryTelemetry())), EntityReference.class);
		plan.assertTaken(response);
		return response.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).toArray();
	}

	/** Verifies which partition representation the production read split actually returns. */
	private static void assertPath(Evita evita, EvitaSessionContract session, Representation representation) {
		final QueryPlanningContext context = context(evita, session, representation,
			query(collection(representation.name()), filterBy(entityLocaleEquals(CZECH))));
		final ReferencedTypeEntityIndex type = context.getEntityIndex(representation.name(),
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, "labels1"),
			ReferencedTypeEntityIndex.class).orElseThrow();
		final ReferencePartitionSplit split = context.splitPartitionsForRead(representation.name(), "labels1",
			type.getAllPrimaryKeys(), Scope.LIVE);
		assertEquals(representation != Representation.MATERIALIZED, split.getVirtualCount() > 0);
		assertEquals(representation != Representation.VIRTUAL, !split.getMaterializedPrimaryKeys().isEmpty());
	}

	/** Reads registered scores from the sorter's real source collection, including candidates outside membership. */
	private static long[] scores(
		Evita evita, EvitaSessionContract session, Representation representation, FilterConstraint constraint
	) {
		long[] expected = null;
		int[] expectedOrder = null;
		for (final boolean cached : new boolean[]{false, true}) {
			for (final PlanPreference preference : PlanPreference.values()) {
				final Query query = query(collection(representation.name()),
					filterBy(preference.filter(OWNERS, entityLocaleEquals(CZECH), entityPrimaryKeyInSet(OWNERS),
						attributeEquals("enabled", true), constraint)), orderBy(relevanceText()),
					require(page(1, 100), preference.debug(), queryTelemetry()));
				final QueryPlanningContext context = context(evita, session, representation, query,
					cached ? cache : NoCacheSupervisor.INSTANCE);
				final QueryPlan plan = QueryPlanner.planQuery(context);
				final RelevanceTextSorter sorter = (RelevanceTextSorter) plan.getSorters().iterator().next();
				final FulltextScoreAccessor[] sources = field(sorter, "accessors");
				assertEquals(1, sources.length, "One structural direct constraint is one place");
				final EvitaResponse<EntityReference> response = plan.execute();
				preference.assertTaken(response);
				final int[] order = response.getRecordData().stream()
					.mapToInt(EntityReference::getPrimaryKey).toArray();
				final long[] actual = FulltextScoreCombiner.combine(OWNERS, sources,
					context.getFulltextScoringPolicy()).composites();
				if (expected == null) {
					expected = actual;
					expectedOrder = order;
				} else {
					assertArrayEquals(expected, actual, "Scores must agree across prefetch and cache modes");
					assertArrayEquals(expectedOrder, order, "Ranking must agree across prefetch and cache modes");
				}
				if (cached) {
					final CacheAnteroom anteroom = field(cache, "cacheAnteroom");
					anteroom.evaluateAssociatesSynchronously();
				}
			}
		}
		return expected;
	}

	/** Builds the same planner context production uses, retaining access to the resulting sorter for assertions. */
	private static QueryPlanningContext context(
		Evita evita, EvitaSessionContract session, Representation representation, Query query
	) {
		return context(evita, session, representation, query, NoCacheSupervisor.INSTANCE);
	}

	/** Selects the production cache supervisor explicitly for cache-on and cache-off planner executions. */
	private static QueryPlanningContext context(
		Evita evita, EvitaSessionContract session, Representation representation,
		Query query, CacheSupervisor supervisor
	) {
		return context(evita, session, representation.name(), query, supervisor);
	}

	/** Creates a planner context for either the fixture owners or their separately indexed root collection. */
	private static QueryPlanningContext context(
		Evita evita, EvitaSessionContract session, String entityType, Query query, CacheSupervisor supervisor
	) {
		final Catalog catalog = (Catalog) evita.getCatalogInstanceOrThrowException(CATALOG);
		final EntityCollection collection = (EntityCollection) catalog
			.getCollectionForEntityOrThrowException(entityType);
		final Map<EntityIndexKey, EntityIndex> indexes = field(collection, "indexes");
		final Map<Integer, EntityIndex> byPk = field(collection, "indexesByPrimaryKey");
		final EvitaSessionContract actualSession = Proxy.isProxyClass(session.getClass()) ?
			field(Proxy.getInvocationHandler(session), "evitaSession") : session;
		return new QueryPlanningContext(catalog, collection, actualSession,
			new EvitaRequest(query, OffsetDateTime.now(), EntityReference.class, null),
			QueryTelemetry.root(QueryPhase.OVERALL), indexes, byPk, supervisor);
	}

	/**
	 * Inspects existing state for routing and score assertions without adding production-only test accessors. The
	 * number of score places and the hidden union preparation do not show in query results - a merged place scores
	 * exactly as collapsed copies of it would - so the sorter's accessors, the cache anteroom and the collection's
	 * index maps are read here directly.
	 */
	@SuppressWarnings("unchecked")
	private static <T> T field(Object instance, String name) {
		try {
			final Field field = instance.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return (T) field.get(instance);
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError(ex);
		}
	}
}
