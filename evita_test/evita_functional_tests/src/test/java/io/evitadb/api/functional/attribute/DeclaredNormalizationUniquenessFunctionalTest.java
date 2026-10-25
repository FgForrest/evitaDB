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

import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaUniqueMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.PlanPreference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Locale;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.lowercase;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.asciiFolding;
import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/** Canonical unique ownership and raw returned values for every collection/catalog locale role and page mode. */
@Tag(ENGINE)
@Tag(ATTRIBUTE)
@Tag(SCHEMA)
@DisplayName("Declared String normalization uniqueness and role locales")
// Keep these engine lifecycles from competing with the fast loop's concurrent catalog bootstraps.
@Isolated("Pay the engine fixture cost once without concurrent catalog bootstrap contention")
class DeclaredNormalizationUniquenessFunctionalTest implements EvitaTestSupport {
	private static final Locale TR = Locale.forLanguageTag("tr");
	private static final Locale EN = Locale.ENGLISH;
	private static final List<String> ATTRIBUTES = List.of("owner", "within", "across", "global", "globalWithin",
		"globalAcross");
	private TestPaths paths;
	private Evita evita;

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@ParameterizedTest(name = "{0} unique values")
	@ValueSource(ints = {1, 300})
	@DisplayName("Collisions are refused in every unique role and lookups preserve the stored spelling across restart")
	void shouldEnforceCanonicalOwnershipAndKeepRawValues(int count) {
		this.paths = createTestPaths("DeclaredNormalizationUniquenessFunctionalTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG)
			.withAttribute("global", String.class, a -> a.uniqueGlobally().nullable().normalized(lowercase()))
			.withAttribute("globalControl", String.class, a -> a.uniqueGlobally().nullable())
			.withAttribute("globalWithin", String.class, a -> a.localized().uniqueGloballyWithinLocale()
				.nullable().normalized(lowercase()))
			.withAttribute("globalWithinControl", String.class,
				a -> a.localized().uniqueGloballyWithinLocale().nullable())
			.withAttribute("globalAcross", String.class,
				a -> a.localized().uniqueGlobally().nullable().normalized(lowercase()))
			.withAttribute("globalAcrossControl", String.class, a -> a.localized().uniqueGlobally().nullable())
			.withAttribute("unstableGlobal", String.class, a -> a.uniqueGlobally().nullable()
				.normalized(lowercase(), asciiFolding()))
			.withAttribute("unstableGlobalControl", String.class, a -> a.uniqueGlobally().nullable())
			.withAttribute("globalScoped", String.class, a -> a.uniqueGloballyInScope(Scope.values()).nullable()
				.normalized(lowercase(), asciiFolding()))
			.withAttribute("globalScopedControl", String.class, a -> a.uniqueGloballyInScope(Scope.values()).nullable())
			.updateViaNewSession(this.evita);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			for (final String type : List.of("Alpha", "Beta")) {
				EntitySchemaBuilder schema = session.defineEntitySchema(type).withoutGeneratedPrimaryKey()
					.withLocale(TR, EN)
					.withGlobalAttribute("global").withGlobalAttribute("globalControl")
					.withGlobalAttribute("globalWithin").withGlobalAttribute("globalWithinControl")
					.withGlobalAttribute("globalAcross").withGlobalAttribute("globalAcrossControl")
					.withGlobalAttribute("unstableGlobal").withGlobalAttribute("unstableGlobalControl")
					.withAttribute("unstableOwner", String.class, a -> a.unique().nullable()
						.normalized(lowercase(), asciiFolding()))
					.withAttribute("unstableOwnerControl", String.class, a -> a.unique().nullable())
					.withAttribute("unstableAcross", String.class, a -> a.localized().unique().nullable()
						.normalized(lowercase(), asciiFolding()))
					.withAttribute("unstableAcrossControl", String.class, a -> a.localized().unique().nullable());
				for (final boolean control : List.of(false, true)) {
					final String suffix = control ? "Control" : "";
					schema = schema.withAttribute("owner" + suffix, String.class,
						a -> a.unique().nullable().normalized(control ? new AnalysisStep[0] :
							new AnalysisStep[]{lowercase()}))
						.withAttribute("within" + suffix, String.class,
							a -> a.localized().uniqueWithinLocale().nullable()
							.normalized(control ? new AnalysisStep[0] :
								new AnalysisStep[]{lowercase()}))
						.withAttribute("across" + suffix, String.class, a -> a.localized().unique().nullable()
							.normalized(control ? new AnalysisStep[0] :
								new AnalysisStep[]{lowercase()}));
				}
				schema.updateVia(session);
			}
			for (int pk = 1; pk <= count; pk++) {
				final String raw = pk == 1 ? "ABC" : "padding" + pk;
				session.createNewEntity("Alpha", pk)
					.setAttribute("owner", raw).setAttribute("ownerControl", raw)
					.setAttribute("within", TR, raw).setAttribute("withinControl", TR, raw)
					.setAttribute("across", TR, raw).setAttribute("acrossControl", TR, raw)
					.setAttribute("global", raw).setAttribute("globalControl", raw)
					.setAttribute("globalWithin", TR, raw).setAttribute("globalWithinControl", TR, raw)
					.setAttribute("globalAcross", TR, raw).setAttribute("globalAcrossControl", TR, raw)
					.setAttribute("unstableGlobal", "ǧ" + pk).setAttribute("unstableGlobalControl", "ǧ" + pk)
					.setAttribute("unstableOwner", "ǧ" + pk).setAttribute("unstableOwnerControl", "ǧ" + pk)
					.setAttribute("unstableAcross", TR, "ǧ" + pk).setAttribute("unstableAcrossControl", TR, "ǧ" + pk)
					.upsertVia(session);
			}
			session.defineEntitySchema("RoleOwner").withoutGeneratedPrimaryKey().withLocale(TR, EN)
				.withGlobalAttribute("globalScoped")
				.withAttribute("role", String.class, a -> a.localized().nullable().normalized(lowercase())
					.uniqueWithinLocaleInScope(Scope.ARCHIVED))
				.withAttribute("control", String.class, a -> a.localized().nullable()
					.uniqueWithinLocaleInScope(Scope.ARCHIVED))
				.withAttribute("otherLocale", String.class, a -> a.localized().uniqueInScope(Scope.values()).nullable()
					.normalized(lowercase()))
				.withAttribute("otherLocaleControl", String.class,
					a -> a.localized().uniqueInScope(Scope.values()).nullable())
				.withReferenceTo("rows", "Target", Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFilteringAndPartitioningInScope(Scope.values())
					.withAttribute("role", String.class, a -> a.localized().nullable().normalized(lowercase())
						.uniqueWithinLocaleInScope(Scope.ARCHIVED))
					.withAttribute("control", String.class, a -> a.localized().nullable()
						.uniqueWithinLocaleInScope(Scope.ARCHIVED)))
				.updateVia(session);
			for (final String name : List.of("role", "control")) {
				session.updateEntitySchema(new ModifyEntitySchemaMutation("RoleOwner",
					new SetAttributeSchemaUniqueMutation(name, new ScopedAttributeUniquenessType[]{
						new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION),
						new ScopedAttributeUniquenessType(Scope.ARCHIVED,
							AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE)
					})));
				session.updateEntitySchema(new ModifyEntitySchemaMutation("RoleOwner",
					new ModifyReferenceAttributeSchemaMutation("rows", new SetAttributeSchemaUniqueMutation(name,
						new ScopedAttributeUniquenessType[]{
							new ScopedAttributeUniquenessType(
								Scope.LIVE, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION),
							new ScopedAttributeUniquenessType(Scope.ARCHIVED,
								AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE)
						}))));
			}
			for (int pk = 1; pk <= 2; pk++) {
				session.createNewEntity("RoleOwner", pk)
					.setAttribute("globalScoped", "role-owner" + pk)
					.setAttribute("role", TR, "TITLE").setAttribute("control", TR, "TITLE")
					.setAttribute("otherLocale", EN, "OTHER" + pk)
					.setAttribute("otherLocaleControl", EN, "OTHER" + pk)
					.setReference("rows", 1, r -> r.setAttribute("role", TR, "TITLE").setAttribute("control", TR,
						"TITLE"))
					.upsertVia(session);
				if (pk == 1) {
					session.archiveEntity("RoleOwner", pk);
				}
			}
			session.defineEntitySchema("ScopedOwner").withoutGeneratedPrimaryKey()
				.withGlobalAttribute("globalScoped").withGlobalAttribute("globalScopedControl")
				.withAttribute("code", String.class, a -> a.uniqueInScope(Scope.values()).nullable()
					.normalized(lowercase(), asciiFolding()))
				.withAttribute("control", String.class, a -> a.uniqueInScope(Scope.values()).nullable())
				.updateVia(session);
			for (int pk = 1; pk <= 2; pk++) {
				session.createNewEntity("ScopedOwner", pk).setAttribute("code", "ǧ").setAttribute("control", "ǧ")
					.setAttribute("globalScoped", "ǧ").setAttribute("globalScopedControl", "ǧ").upsertVia(session);
				if (pk == 1) {
					session.archiveEntity("ScopedOwner", pk);
				}
			}
			session.goLiveAndClose();
		});
		assertUniqueRoles();
		assertTurkishRoleLocales();
		assertReferenceRoleLocales();
		assertCollectionLessRoleLocales();
		assertNonStableUniqueRoles();
		assertScopePrecedence();
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
		assertUniqueRoles();
		assertTurkishRoleLocales();
		assertReferenceRoleLocales();
		assertCollectionLessRoleLocales();
		assertNonStableUniqueRoles();
		assertScopePrecedence();
	}

	/** Each normalized declaration refuses a second owner, while the corresponding control accepts raw differences. */
	private void assertUniqueRoles() {
		for (final String name : ATTRIBUTES) {
			final boolean localized = localized(name);
			final String targetType = name.startsWith("global") ? "Beta" : "Alpha";
			assertThrows(UniqueValueViolationException.class, () -> this.evita.updateCatalog(TEST_CATALOG, session -> {
				final EntityBuilder entity =
					session.createNewEntity(targetType, 2000);
				if (localized) {
					entity.setAttribute(name, (name.equals("across") || name.equals("globalAcross")) ? EN : TR, "abc");
				} else {
					entity.setAttribute(name, "abc");
				}
				entity.upsertVia(session);
			}), name);
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				if (session.getEntity(targetType, 2001 + ATTRIBUTES.indexOf(name)).isPresent()) {
					return;
				}
				final EntityBuilder entity =
					session.createNewEntity(targetType, 2001 + ATTRIBUTES.indexOf(name));
				if (localized) {
					entity.setAttribute(name + "Control", TR, "abc");
				} else {
					entity.setAttribute(name + "Control", "abc");
				}
				entity.upsertVia(session);
			});
			for (final PlanPreference route : PlanPreference.values()) {
				for (final String probe : List.of("ABC", "abc")) {
					this.evita.queryCatalog(TEST_CATALOG, session -> {
						final EvitaResponse<SealedEntity> response = session.query(query(collection("Alpha"),
							filterBy(route.filter(new int[]{1}, attributeEquals(name, probe),
								localized ? entityLocaleEquals(TR) : null)),
							require(entityFetch(attributeContentAll()), queryTelemetry(), route.debug())),
								SealedEntity.class);
						if (route == PlanPreference.INDEX_SCAN && !name.startsWith("global")) {
							route.assertTaken(response);
						}
						assertEquals(1, response.getRecordData().size());
						final SealedEntity owner = response.getRecordData().getFirst();
						assertEquals(1, owner.getPrimaryKeyOrThrowException());
						assertEquals("ABC", localized ? owner.getAttribute(name, TR) : owner.getAttribute(name));
						final EvitaResponse<EntityReference> set = session.query(query(collection("Alpha"),
							filterBy(route.filter(new int[]{1}, attributeInSet(name, "ABC", "abc", "ABC"),
								localized ? entityLocaleEquals(TR) : null)),
							require(queryTelemetry(), route.debug())), EntityReference.class);
						if (route == PlanPreference.INDEX_SCAN && !name.startsWith("global")) {
							route.assertTaken(set);
						}
						assertEquals(List.of(new EntityReference("Alpha", 1)), set.getRecordData());
						return null;
					});
				}
			}
		}
	}

	/** A raw probe is folded once on folded unique and catalog roles, on both planning preferences. */
	private void assertNonStableUniqueRoles() {
		for (final String name : List.of("unstableOwner", "unstableGlobal", "unstableAcross")) {
			for (final PlanPreference route : PlanPreference.values()) {
				this.evita.queryCatalog(TEST_CATALOG, session -> {
					final EvitaResponse<EntityReference> response = session.query(query(collection("Alpha"),
						filterBy(route.filter(new int[]{1}, attributeInSet(name, "ǧ1", "ǧ1"))),
						require(queryTelemetry(), route.debug())), EntityReference.class);
					if (route == PlanPreference.INDEX_SCAN && !name.equals("unstableGlobal")) {
						route.assertTaken(response);
					}
					assertEquals(List.of(new EntityReference("Alpha", 1)), response.getRecordData());
					assertTrue(session.query(query(collection("Alpha"), filterBy(attributeEquals(name, "G1"))),
						EntityReference.class).getRecordData().isEmpty());
					assertEquals(List.of(new EntityReference("Alpha", 1)), session.query(query(collection("Alpha"),
						filterBy(attributeEquals(name + "Control", "ǧ1"))), EntityReference.class).getRecordData());
					return null;
				});
			}
		}
	}

	/** The unique index retains the per-probe winning scope, including collisions of non-stable keys. */
	private void assertScopePrecedence() {
		for (final Scope[] scopes : List.of(new Scope[]{Scope.LIVE, Scope.ARCHIVED},
			new Scope[]{Scope.ARCHIVED, Scope.LIVE})) {
			final int winner = scopes[0] == Scope.LIVE ? 2 : 1;
			for (final String name : List.of("code", "control", "globalScoped", "globalScopedControl")) {
				for (final PlanPreference route : PlanPreference.values()) {
					this.evita.queryCatalog(TEST_CATALOG, session -> {
						final EvitaResponse<EntityReference> response = session.query(query(collection("ScopedOwner"),
							filterBy(route.filter(new int[]{1, 2}, scope(scopes), attributeInSet(name, "ǧ", "Ǧ"))),
							require(queryTelemetry(), route.debug())), EntityReference.class);
						if (route == PlanPreference.INDEX_SCAN && !name.startsWith("global")) {
							route.assertTaken(response);
						}
						assertEquals(List.of(new EntityReference("ScopedOwner", winner)), response.getRecordData());
						return null;
					});
				}
			}
		}
	}

	/** A cross-locale owner folds TITLE by root; its Turkish filter/sort siblings and within-locale view fold by tr. */
	private void assertTurkishRoleLocales() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			if (session.getEntity("Alpha", 4000).isEmpty()) {
				session.createNewEntity("Alpha", 4000).setAttribute("across", TR, "TITLE")
					.setAttribute("within", TR, "TITLE").upsertVia(session);
			}
		});
		for (final PlanPreference route : PlanPreference.values()) {
			this.evita.queryCatalog(TEST_CATALOG, session -> {
				for (final String probe : List.of("TITLE", "title")) {
					final EvitaResponse<EntityReference> response = session.query(query(collection("Alpha"),
						filterBy(route.filter(new int[]{4000}, entityLocaleEquals(TR), attributeEquals("across",
							probe))),
						require(queryTelemetry(), route.debug())), EntityReference.class);
					if (route == PlanPreference.INDEX_SCAN) {
						route.assertTaken(response);
					}
					assertEquals(List.of(new EntityReference("Alpha", 4000)), response.getRecordData());
				}
				for (final String probe : List.of("TITLE", "tıtle")) {
					final EvitaResponse<EntityReference> response = session.query(query(collection("Alpha"),
						filterBy(route.filter(new int[]{4000}, entityLocaleEquals(TR), attributeEquals("within",
							probe))),
						require(queryTelemetry(), route.debug())), EntityReference.class);
					if (route == PlanPreference.INDEX_SCAN) {
						route.assertTaken(response);
					}
					assertEquals(List.of(new EntityReference("Alpha", 4000)), response.getRecordData());
				}
				return null;
			});
		}
	}

	/** Equality binds root in LIVE and Turkish in ARCHIVED, while search always uses the row's Turkish locale. */
	private void assertReferenceRoleLocales() {
		for (final String name : List.of("role", "control")) {
			for (final String probe : List.of("TITLE", "title", "tıtle")) {
				for (final FilterConstraint leaf : List.of(attributeEquals(name, probe),
					attributeInSet(name, probe, probe))) {
					this.evita.queryCatalog(TEST_CATALOG, session -> {
						final EvitaResponse<SealedEntity> response = session.query(query(collection("RoleOwner"),
							filterBy(scope(Scope.LIVE, Scope.ARCHIVED), entityLocaleEquals(TR)),
							require(entityFetch(referenceContentWithAttributes("rows", filterBy(leaf))))),
							SealedEntity.class);
						assertEquals(2, response.getRecordData().size());
						for (final SealedEntity owner : response.getRecordData()) {
							final boolean matches = name.equals("control") ? probe.equals("TITLE") :
								owner.getScope() == Scope.LIVE ? !probe.equals("tıtle") : !probe.equals("title");
							assertArrayEquals(matches ? new int[]{1} : new int[0], owner.getReferences("rows").stream()
								.mapToInt(ReferenceContract::getReferencedPrimaryKey).toArray(),
								leaf + "/" + owner.getScope());
						}
						return null;
					});
				}
			}
		}
	}

	/**
	 * Collection-less equality and set matchers bind each owner's lookup role while reading only the query locale.
	 * Values present only in English stay invisible to a Turkish query, including the unnormalized control.
	 */
	private void assertCollectionLessRoleLocales() {
		for (final Scope[] scopes : List.of(new Scope[]{Scope.LIVE, Scope.ARCHIVED},
			new Scope[]{Scope.ARCHIVED, Scope.LIVE})) {
			for (final String name : List.of("role", "control", "otherLocale", "otherLocaleControl")) {
				for (final String probe : List.of("TITLE", "title", "tıtle", "OTHER1", "other1")) {
					final List<EntityReference> expected = !name.equals("role") ?
						(name.equals("control") && probe.equals("TITLE") ?
							List.of(new EntityReference("RoleOwner", 1), new EntityReference("RoleOwner", 2)) : List.of()) :
						switch (probe) {
							case "TITLE" -> List.of(new EntityReference("RoleOwner", 1), new EntityReference("RoleOwner", 2));
							case "title" -> List.of(new EntityReference("RoleOwner", 2));
							case "tıtle" -> List.of(new EntityReference("RoleOwner", 1));
							default -> List.of();
						};
					for (final FilterConstraint leaf : List.of(attributeEquals(name, probe),
						attributeInSet(name, probe, probe))) {
						this.evita.queryCatalog(TEST_CATALOG, session -> {
							final EvitaResponse<EntityReference> response = session.query(query(
								filterBy(scope(scopes), attributeInSet("globalScoped", "role-owner1", "role-owner2"),
									entityLocaleEquals(TR), leaf), orderBy(entityPrimaryKeyNatural(ASC))), EntityReference.class);
							assertEquals(expected, response.getRecordData(), leaf.toString());
							return null;
						});
					}
				}
			}
		}
	}

	/** Selects the value locale independently of the unique tree's folding locale. */
	private static boolean localized(@Nonnull String name) {
		return !name.equals("owner") && !name.equals("global");
	}
}
