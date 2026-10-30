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

package io.evitadb.api;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference
	.ModifyReflectedReferenceAttributeInheritanceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound
	.CreateSortableAttributeCompoundSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.grpc.generated.GrpcReferenceSchema;
import io.evitadb.externalApi.grpc.requestResponse.schema.EntitySchemaConverter;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.referenceContentWithAttributes;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement
	.attributeElement;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that excluded inherited names remain available to independent local declarations, both in schemas
 * and in reference values, across session boundaries and storage reloads.
 */
@DisplayName("Local declarations sharing excluded inherited names")
@Tag(ENGINE)
@Tag(REFERENCE)
@Tag(SCHEMA)
class ReflectedReferenceLocalDeclarationsFunctionalTest implements EvitaTestSupport {
	/**
	 * Storage retained when the engine restarts.
	 */
	@Nonnull
	private TestPaths paths;
	/**
	 * Real engine serving the test catalog.
	 */
	@Nonnull
	private Evita evita;

	/**
	 * Creates an empty catalog in isolated storage.
	 */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReflectedReferenceLocalDeclarations");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	/**
	 * Releases the engine and its storage after each invocation.
	 */
	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	/**
	 * A change to the original reference must retain the excluded local attribute when rebinding its reflection.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Local attribute survives rebinding after an original reference description change")
	void shouldRetainLocalAttributeAfterRebinding(boolean transactional) {
		defineSchema(transactional, false);
		changeOriginalDescription();
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertLocalAttribute(session);
		});
	}

	/**
	 * Storage must include local declarations even if an excluded original has the same name.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Local attribute survives an engine restart")
	void shouldStoreLocalAttribute(boolean transactional) {
		defineSchema(transactional, false);
		restart();
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertLocalAttribute(session);
		});
	}

	/**
	 * Attribute lookup for mutations must find the local declaration in a later session.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Local attribute can be modified in a later session")
	void shouldModifyLocalAttribute(boolean transactional) {
		defineSchema(transactional, false);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrowException("brand").openForWrite()
				.withReflectedReferenceToEntity("products", "product", "brand", reference -> reference
					.withAttribute("label", String.class, attribute -> attribute.withDescription("changed local")))
				.updateVia(session);
		});
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertEquals("changed local", reflected(session).getAttribute("label").orElseThrow().getDescription());
		});
		restart();
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertEquals("changed local", reflected(session).getAttribute("label").orElseThrow().getDescription());
		});
	}

	/**
	 * Compounds use the same inheritance membership rule as attributes during rebinding.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Local sortable compound survives rebinding")
	void shouldRetainLocalCompoundAfterRebinding(boolean transactional) {
		defineSchema(transactional, true);
		changeOriginalDescription();
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertLocalCompound(session);
		});
	}

	/**
	 * Compound storage must retain the local schema rather than discard it by original name presence.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Local sortable compound survives an engine restart")
	void shouldStoreLocalCompound(boolean transactional) {
		defineSchema(transactional, true);
		restart();
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertLocalCompound(session);
		});
	}

	/**
	 * Inserted original values propagate only for selected attributes, and local values are indexed independently.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Original insertion keeps excluded values separate and local values remain queryable after restart")
	void shouldKeepInsertedOriginalValuesSeparate(boolean transactional) {
		defineSchema(transactional, false);
		insertOriginal();
		assertValues("original", null, "shared");
		setValue("brand", "products", "label", "local");
		assertValues("original", "local", "shared");
		assertLocalFilter();
		restart();
		assertValues("original", "local", "shared");
		assertLocalFilter();
	}

	/**
	 * Insertion from the reflected end must not copy its local value to the original reference.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Reflected insertion propagates inherited values and keeps its local value separate")
	void shouldKeepInsertedReflectedValuesSeparate(boolean transactional) {
		defineSchema(transactional, false);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.upsertEntity(session.createNewEntity("product", 1));
			session.upsertEntity(session.createNewEntity("brand", 1).setReference("products", 1,
				reference -> reference.setAttribute("label", "local").setAttribute("validity", "shared")));
		});
		assertValues(null, "local", "shared");
		restart();
		assertValues(null, "local", "shared");
	}

	/**
	 * Updates of the original must not overwrite the reflected local value even when the names match.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Original updates honour the inheritance filter")
	void shouldKeepOriginalUpdatesSeparate(boolean transactional) {
		defineSchema(transactional, false);
		insertOriginal();
		setValue("product", "brand", "label", "changed original");
		assertValues("changed original", null, "shared");
		setValue("product", "brand", "validity", "changed shared");
		assertValues("changed original", null, "changed shared");
	}

	/**
	 * Neither an update nor a removal of the reflected local attribute may reach the original attribute with the
	 * same name.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Reflected updates honour the inheritance filter")
	void shouldKeepReflectedUpdatesSeparate(boolean transactional) {
		defineSchema(transactional, false);
		insertOriginal();
		setValue("brand", "products", "label", "local");
		assertValues("original", "local", "shared");
		setValue("brand", "products", "validity", "changed shared");
		assertValues("original", "local", "changed shared");
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.upsertEntity(session.getEntity("brand", 1, referenceContentWithAttributes("products"))
				.orElseThrow().openForWrite()
				.updateReference("products", 1, reference -> reference.removeAttribute("label")));
		});
		assertValues("original", null, "changed shared");
	}

	/**
	 * Wire conversion must classify compound inheritance independently of attribute names, whether the inheritance
	 * filter excludes the local name or selects only the others.
	 */
	@ParameterizedTest
	@CsvSource({"false, false", "false, true", "true, false", "true, true"})
	@DisplayName("Schema conversion distinguishes local and inherited sortable compounds")
	void shouldConvertCompoundInheritance(boolean transactional, boolean only) {
		defineSchema(transactional, true);
		if (only) {
			this.inheritOnly("label", "validity", "sharedRanking");
		}
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final GrpcReferenceSchema converted = EntitySchemaConverter.convert(
				session.getCatalogSchema(), session.getEntitySchemaOrThrowException("brand"), false
			).getReferencesOrThrow("products");
			assertAll(
				() -> assertFalse(converted.getSortableAttributeCompoundsOrThrow("ranking").getInherited()),
				() -> assertTrue(converted.getSortableAttributeCompoundsOrThrow("sharedRanking").getInherited())
			);
		});
	}

	/**
	 * Metadata copies must preserve both kinds of local declaration through their shared extraction helpers.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Reflected reference metadata copies retain local declarations")
	void shouldRetainLocalDeclarationsWhenCopyingReference(boolean transactional) {
		defineSchema(transactional, false);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrowException("brand").openForWrite()
				.withReflectedReferenceToEntity("products", "product", "brand",
					reference -> reference.withDescription("local reference"))
				.updateVia(session);
		});
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertLocalAttribute(session);
		});
	}

	/**
	 * Selecting only the shared attribute still leaves the other original name available to the local declaration.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Local attribute survives switching to explicitly selected inheritance")
	void shouldRetainLocalAttributeWithExplicitInheritance(boolean transactional) {
		defineSchema(transactional, false);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrowException("brand").openForWrite()
				.withReflectedReferenceToEntity("products", "product", "brand",
					reference -> reference.withAttributesInherited("validity"))
				.updateVia(session);
		});
		changeOriginalDescription();
		restart();
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertLocalAttribute(session);
		});
	}

	/**
	 * Selecting only the other original names still leaves the same-named original compound to the local one.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Local sortable compound survives switching to explicitly selected inheritance")
	void shouldRetainLocalCompoundWithExplicitInheritance(boolean transactional) {
		this.defineSchema(transactional, true);
		this.inheritOnly("label", "validity", "sharedRanking");
		this.changeOriginalDescription();
		this.restart();
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertLocalCompound(session);
		});
	}

	/**
	 * Compound mutation lookup and copy extraction must retain the local compound in a later session.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Local sortable compound can be modified in a later session")
	void shouldModifyLocalCompound(boolean transactional) {
		defineSchema(transactional, true);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrowException("brand").openForWrite()
				.withReflectedReferenceToEntity("products", "product", "brand", reference -> reference
					.withSortableAttributeCompound("ranking",
						new AttributeElement[]{attributeElement("validity"), attributeElement("label")},
						definition -> definition.withDescription("changed compound")))
				.updateVia(session);
		});
		restart();
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertEquals("changed compound", reflected(session).getDeclaredSortableAttributeCompound("ranking")
				.orElseThrow().getDescription());
		});
	}

	/**
	 * Every supported DTO copy route must preserve the local attribute extracted from a real bound schema.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("All reflected schema copies retain the local attribute")
	void shouldRetainLocalAttributeThroughEveryCopy(boolean transactional) {
		defineSchema(transactional, false);
		assertCopiesRetainLocalDeclaration(false);
	}

	/**
	 * Every supported DTO copy route must preserve the local compound extracted from a real bound schema.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("All reflected schema copies retain the local sortable compound")
	void shouldRetainLocalCompoundThroughEveryCopy(boolean transactional) {
		defineSchema(transactional, true);
		assertCopiesRetainLocalDeclaration(true);
	}

	/**
	 * Attribute inheritance flags sent to the driver must preserve the independent local declaration, whether the
	 * inheritance filter excludes the local name or selects only the others.
	 */
	@ParameterizedTest
	@CsvSource({"false, false", "false, true", "true, false", "true, true"})
	@DisplayName("Schema conversion distinguishes local and inherited attributes")
	void shouldConvertAttributeInheritance(boolean transactional, boolean only) {
		defineSchema(transactional, false);
		if (only) {
			this.inheritOnly("validity");
		}
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final GrpcReferenceSchema converted = EntitySchemaConverter.convert(
				session.getCatalogSchema(), session.getEntitySchemaOrThrowException("brand"), false
			).getReferencesOrThrow("products");
			assertAll(
				() -> assertFalse(converted.getAttributesOrThrow("label").getInherited()),
				() -> assertTrue(converted.getAttributesOrThrow("validity").getInherited())
			);
		});
	}

	/**
	 * Changing either the behavior or the filter must refuse a selected local name before replacing its schema.
	 * Four products make rewriting profitable, so a divergent original value would also change query answers.
	 */
	@ParameterizedTest
	@CsvSource({
		"false, false, false", "false, false, true", "false, true, false", "false, true, true",
		"true, false, false", "true, false, true", "true, true, false", "true, true, true"
	})
	@DisplayName("Inheritance changes refuse local attributes and compounds and preserve populated pairs")
	void shouldRefuseInheritanceOverLocalDeclaration(boolean transactional, boolean compound, boolean only) {
		this.defineSchema(transactional, compound);
		this.populatePair(compound);
		this.assertPopulatedPair(compound);
		this.assertInheritanceRefused(compound, only);
		this.assertPopulatedPair(compound);
		this.restart();
		this.assertPopulatedPair(compound);
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertEquals("session continued", session.getEntitySchemaOrThrowException("brand").getDescription());
			if (compound) {
				assertLocalCompound(session);
			} else {
				assertLocalAttribute(session);
			}
		});
	}

	/**
	 * The refusal protects independent definitions even before any values have been inserted.
	 */
	@ParameterizedTest
	@CsvSource({"false, false", "false, true", "true, false", "true, true"})
	@DisplayName("Inheritance changes refuse local declarations even in an empty catalog")
	void shouldRefuseInheritanceOverEmptyLocalDeclaration(boolean transactional, boolean compound) {
		this.defineSchema(transactional, compound);
		this.assertInheritanceRefused(compound, false);
	}

	/**
	 * Selection uses actual original maps: absent names remain local, while unrelated actual names can be inherited.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Inheritance can widen to absent original names and to names without local declarations")
	void shouldAllowInheritanceWithoutLocalClashes(boolean transactional) {
		this.defineSchema(transactional, false);
		this.populatePair(false);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrowException("brand").openForWrite()
				.withReflectedReferenceToEntity("products", "product", "brand", reference -> reference
					.withAttributesInherited("validity")
					.withAttribute("localOnly", String.class, attribute -> attribute.nullable().filterable())
					.withSortableAttributeCompound("localRanking", attributeElement("label"),
						attributeElement("validity")))
				.updateVia(session);
			session.getEntitySchemaOrThrowException("product").openForWrite()
				.withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_MORE, reference -> reference
					.withAttribute("color", String.class, attribute -> attribute.nullable().filterable())
					.withSortableAttributeCompound("sharedRanking", attributeElement("label"),
						attributeElement("validity")))
				.updateVia(session);
			session.updateEntitySchema(new ModifyEntitySchemaMutation("brand",
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation("products",
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED,
					"validity", "localOnly", "localRanking")));
			assertTrue(reflected(session).getDeclaredAttribute("localOnly").isPresent());
			assertTrue(reflected(session).getDeclaredSortableAttributeCompound("localRanking").isPresent());
			session.updateEntitySchema(new ModifyEntitySchemaMutation("brand",
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation("products",
					AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT, "label")));
			assertTrue(reflected(session).getInheritedAttributes().contains("color"));
			assertTrue(reflected(session).getSortableAttributeCompound("sharedRanking").isPresent());
			assertFalse(reflected(session).getDeclaredSortableAttributeCompounds().containsKey("sharedRanking"));
			assertTrue(reflected(session).getDeclaredAttribute("localOnly").isPresent());
			assertTrue(reflected(session).getDeclaredSortableAttributeCompound("localRanking").isPresent());
		});
		this.restart();
		this.assertPopulatedPair(false);
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertLocalAttribute(session);
			assertTrue(reflected(session).getDeclaredAttribute("localOnly").isPresent());
			assertTrue(reflected(session).getDeclaredSortableAttributeCompound("localRanking").isPresent());
		});
	}

	/**
	 * The preflight sees the original as it was before the batch, while the real pass applies the removal of the
	 * original name first - after which widening the inheritance replaces no local declaration.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Inheritance can widen in the batch that removes the clashing original name")
	void shouldAcceptInheritanceWideningAfterOriginalDropsLocalName(boolean transactional) {
		this.defineSchema(transactional, false);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.updateCatalogSchema(
				new ModifyEntitySchemaMutation("product",
					new ModifyReferenceAttributeSchemaMutation("brand", new RemoveAttributeSchemaMutation("label"))),
				new ModifyEntitySchemaMutation("brand",
					new ModifyReflectedReferenceAttributeInheritanceSchemaMutation("products",
						AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT))
			);
		});
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertLocalAttributeWithoutOriginal(session);
		});
		this.restart();
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertLocalAttributeWithoutOriginal(session);
		});
	}

	/**
	 * The preflight sees the collection as it was before the batch, so a reflected reference created by an earlier
	 * mutation of the same batch is unknown to it until the real pass applies the batch in order.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Inheritance can change on a reflected reference created by an earlier mutation of the batch")
	void shouldAcceptInheritanceChangeOfReflectedReferenceCreatedEarlierInBatch(boolean transactional) {
		if (transactional) {
			this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		}
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("brand").withoutGeneratedPrimaryKey().updateVia(session);
			session.defineEntitySchema("product").withoutGeneratedPrimaryKey()
				.withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_MORE, reference -> reference
					.indexedForFiltering()
					.withAttribute("label", String.class, attribute -> attribute.nullable().filterable())
					.withAttribute("validity", String.class, attribute -> attribute.nullable().filterable()))
				.updateVia(session);
		});
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			final ModifyEntitySchemaMutation createReflection = session.getEntitySchemaOrThrowException("brand")
				.openForWrite()
				.withReflectedReferenceToEntity("products", "product", "brand")
				.toMutation()
				.orElseThrow();
			session.updateCatalogSchema(
				createReflection,
				new ModifyEntitySchemaMutation("brand",
					new ModifyReflectedReferenceAttributeInheritanceSchemaMutation("products",
						AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED, "validity"))
			);
		});
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertOnlyValidityInherited(session);
		});
		this.restart();
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertOnlyValidityInherited(session);
		});
	}

	/**
	 * The preflight carries the acceptable change into the refused one, so the refusal discards both before either
	 * reaches the catalog, and the session goes on.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Inheritance refusal discards an acceptable change preceding it in the same entity mutation")
	void shouldRefuseInheritanceAfterAcceptableChangeOfSameEntityMutation(boolean transactional) {
		this.defineSchema(transactional, false);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			final EntitySchemaContract reflectedSchema = session.getEntitySchemaOrThrowException("brand");
			assertThrows(InvalidSchemaMutationException.class,
				() -> session.updateEntitySchema(new ModifyEntitySchemaMutation("brand",
					new ModifyReferenceSchemaDescriptionMutation("products", "changed reflection"),
					new ModifyReflectedReferenceAttributeInheritanceSchemaMutation("products",
						AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT))));
			assertEquals(reflectedSchema, session.getEntitySchemaOrThrowException("brand"));
			assertFalse(session.isRollbackOnly());
			session.getEntitySchemaOrThrowException("brand").openForWrite()
				.withDescription("session continued").updateVia(session);
		});
		this.restart();
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertEquals("session continued", session.getEntitySchemaOrThrowException("brand").getDescription());
			assertTrue(reflected(session).isDescriptionInherited());
			assertLocalAttribute(session);
		});
	}

	/**
	 * One refusal names every clashing local declaration once, attributes and compounds alike, in name order.
	 */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Inheritance refusal lists every clashing local attribute and sortable compound")
	void shouldListEveryClashingLocalNameInRefusal(boolean transactional) {
		if (transactional) {
			this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		}
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("brand").withoutGeneratedPrimaryKey()
				.withReflectedReferenceToEntity("products", "product", "brand", reference -> reference
					.withAttributesInheritedExcept("ranking", "label")
					.withAttribute("label", String.class, attribute -> attribute.nullable().filterable()))
				.updateVia(session);
			session.updateEntitySchema(new ModifyEntitySchemaMutation("brand",
				new ModifyReferenceSortableAttributeCompoundSchemaMutation("products",
					new CreateSortableAttributeCompoundSchemaMutation("ranking", null, null,
						Scope.DEFAULT_SCOPES, attributeElement("validity"), attributeElement("label")))));
			session.defineEntitySchema("product").withoutGeneratedPrimaryKey()
				.withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_MORE, reference -> reference
					.indexedForFiltering()
					.withAttribute("label", String.class, attribute -> attribute.nullable().filterable())
					.withAttribute("validity", String.class, attribute -> attribute.nullable().filterable())
					.withSortableAttributeCompound("ranking", attributeElement("label"), attributeElement("validity")))
				.updateVia(session);
		});
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			final InvalidSchemaMutationException exception = assertThrows(InvalidSchemaMutationException.class,
				() -> session.updateEntitySchema(new ModifyEntitySchemaMutation("brand",
					new ModifyReflectedReferenceAttributeInheritanceSchemaMutation("products",
						AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT))));
			assertTrue(exception.getMessage().contains("names `label`, `ranking` would be inherited"),
				exception.getMessage());
		});
	}

	/**
	 * Catches the mutation exception inside the writing session, checks both schemas, and commits a later edit.
	 */
	private void assertInheritanceRefused(boolean compound, boolean only) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			final EntitySchemaContract originalSchema = session.getEntitySchemaOrThrowException("product");
			final EntitySchemaContract reflectedSchema = session.getEntitySchemaOrThrowException("brand");
			final String name = compound ? "ranking" : "label";
			final InvalidSchemaMutationException exception = assertThrows(InvalidSchemaMutationException.class,
				() -> session.updateEntitySchema(new ModifyEntitySchemaMutation("brand",
					new ModifyReflectedReferenceAttributeInheritanceSchemaMutation("products",
						only ? AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED :
							AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT,
						only ? new String[]{name, "validity"} : new String[0]))));
			assertAll(
				() -> assertTrue(exception.getMessage().contains("`products`")),
				() -> assertTrue(exception.getMessage().contains("`brand`")),
				() -> assertTrue(exception.getMessage().contains("`" + name + "`")),
				() -> assertTrue(exception.getMessage().contains("Remove or rename")),
				() -> assertTrue(exception.getMessage().contains("excluded from inheritance")),
				() -> assertEquals(originalSchema, session.getEntitySchemaOrThrowException("product")),
				() -> assertEquals(reflectedSchema, session.getEntitySchemaOrThrowException("brand"))
			);
			assertFalse(session.isRollbackOnly());
			session.getEntitySchemaOrThrowException("brand").openForWrite()
				.withDescription("session continued").updateVia(session);
		});
	}

	/**
	 * Populates both ends with four references so query rewriting cannot hide a local value behind its cost gate.
	 */
	private void populatePair(boolean compound) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.upsertEntity(session.createNewEntity("brand", 1));
			for (int i = 1; i <= 4; i++) {
				session.upsertEntity(session.createNewEntity("product", i).setReference("brand", 1,
					reference -> reference.setAttribute("label", "original").setAttribute("validity", "shared")));
			}
			if (!compound) {
				final EntityBuilder brand = session.getEntity("brand", 1,
					referenceContentWithAttributes("products")).orElseThrow().openForWrite();
				for (int i = 1; i <= 4; i++) {
					brand.updateReference("products", i, reference -> reference.setAttribute("label", "local"));
				}
				session.upsertEntity(brand);
			}
		});
	}

	/**
	 * Checks every stored value on both ends and the answers observed through the reflected query rewriter.
	 */
	private void assertPopulatedPair(boolean compound) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			for (int i = 1; i <= 4; i++) {
				final ReferenceContract original = session.getEntity("product", i,
					referenceContentWithAttributes("brand")).orElseThrow().getReference("brand", 1).orElseThrow();
				final ReferenceContract reflection = session.getEntity("brand", 1,
					referenceContentWithAttributes("products")).orElseThrow().getReference("products", i).orElseThrow();
				assertEquals("original", original.getAttribute("label"));
				assertEquals(compound ? "original" : "local", reflection.getAttribute("label"));
				assertEquals("shared", original.getAttribute("validity"));
				assertEquals("shared", reflection.getAttribute("validity"));
			}
		});
		if (!compound) {
			this.assertLocalFilter();
		}
	}

	/**
	 * Exercises the supported copy methods, including compound-only and attribute-only mutation copies.
	 */
	private void assertCopiesRetainLocalDeclaration(boolean compound) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final ReflectedReferenceSchema schema = reflected(session);
			final ReferenceSchemaContract original = session.getEntitySchemaOrThrowException("product")
				.getReferenceOrThrowException("brand");
			final Map<String, ReferenceSchemaContract> copies = Map.ofEntries(
				Map.entry("withName", schema.withName("renamedProducts")),
				Map.entry("withDescription", schema.withDescription("copy")),
				Map.entry("withDeprecationNotice", schema.withDeprecationNotice("copy")),
				Map.entry("withCardinality", schema.withCardinality(Cardinality.ZERO_OR_MORE)),
				Map.entry("withUpdatedReferencedEntityType", schema.withUpdatedReferencedEntityType("product")),
				Map.entry("withIndexed", schema.withIndexed(null)),
				Map.entry("withIndexedComponents", schema.withIndexedComponents(null)),
				Map.entry("withFaceted", schema.withFaceted(null)),
				Map.entry("withFacetedPartially", schema.withFacetedPartially(Map.of())),
				Map.entry("withBucketed", schema.withBucketed(Map.of())),
				Map.entry("withBucketedPartially", schema.withBucketedPartially(Map.of())),
				Map.entry("withAttributeInheritance", schema.withAttributeInheritance(
					schema.getAttributesInheritanceBehavior(), schema.getAttributeInheritanceFilter())),
				Map.entry("withDeclaredAttributes", schema.withDeclaredAttributes(schema.getDeclaredAttributes())),
				Map.entry("withDeclaredSortableAttributeCompounds",
					schema.withDeclaredSortableAttributeCompounds(schema.getDeclaredSortableAttributeCompounds())),
				Map.entry("withReferencedSchema", schema.withReferencedSchema(original)),
				Map.entry("withReferencedSchemaAfterSchemaChange",
					schema.withReferencedSchemaAfterSchemaChange(original))
			);
			assertAll(copies.entrySet().stream().map(copy -> (Executable) () -> assertTrue(compound ?
				copy.getValue().getSortableAttributeCompound("ranking").isPresent() :
				copy.getValue().getAttribute("label").isPresent(),
				copy.getKey() + " must retain the local declaration")));
			assertDefaultComponentsCompletionRetainsLocalDeclaration(session, schema, compound);
		});
	}

	/**
	 * Reproduces the bound DTO state immediately before the indexing mutation completes a newly indexed scope.
	 * The public schema update returns the completed schema, so the intermediate input uses the same DTO copies
	 * as {@link SetReferenceSchemaIndexedMutation#mutate} and is checked against the real mutation result.
	 */
	private static void assertDefaultComponentsCompletionRetainsLocalDeclaration(
		@Nonnull EvitaSessionContract session, @Nonnull ReflectedReferenceSchema previousSchema, boolean compound
	) {
		final SetReferenceSchemaIndexedMutation mutation = new SetReferenceSchemaIndexedMutation(
			previousSchema.getName(),
			new ScopedReferenceIndexType[]{
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
				new ScopedReferenceIndexType(Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING)
			},
			new ScopedReferenceIndexedComponents[]{
				new ScopedReferenceIndexedComponents(
					Scope.LIVE, new ReferenceIndexedComponents[]{ReferenceIndexedComponents.REFERENCED_ENTITY}
				)
			}
		);
		final ReflectedReferenceSchema indexedSchema = (ReflectedReferenceSchema) previousSchema
			.withIndexed(mutation.getIndexedInScopes());
		final ReflectedReferenceSchema pendingSchema = (ReflectedReferenceSchema) indexedSchema
			.withIndexedComponents(mutation.getIndexedComponentsInScopes());
		assertAll(
			() -> assertTrue(pendingSchema.isReflectedReferenceAvailable()),
			() -> assertFalse(previousSchema.isIndexedInScope(Scope.ARCHIVED)),
			() -> assertTrue(pendingSchema.isIndexedInScope(Scope.ARCHIVED)),
			() -> assertFalse(pendingSchema.isIndexedComponentsInherited()),
			() -> assertTrue(pendingSchema.getIndexedComponents(Scope.ARCHIVED).isEmpty())
		);
		final ReflectedReferenceSchema completedSchema = pendingSchema
			.withDefaultComponentsInUncoveredScopes(previousSchema);
		assertAll(
			() -> assertNotSame(pendingSchema, completedSchema, "completion must create a copy"),
			() -> assertEquals(Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY),
				completedSchema.getIndexedComponents(Scope.ARCHIVED)),
			() -> assertEquals(pendingSchema.getIndexedComponents(Scope.LIVE),
				completedSchema.getIndexedComponents(Scope.LIVE)),
			() -> assertEquals(compound ? "local compound" : "local attribute", compound ?
				completedSchema.getDeclaredSortableAttributeCompound("ranking")
					.map(it -> it.getDescription()).orElse(null) :
				completedSchema.getDeclaredAttribute("label").map(it -> it.getDescription()).orElse(null),
				"completion must retain the local declaration with its description"),
			() -> assertEquals(completedSchema,
				mutation.mutate(session.getEntitySchemaOrThrowException("brand"), previousSchema))
		);
	}

	/**
	 * Declares the local schema before the original exists, then binds the pair in the same session. The compound
	 * uses a create mutation because the compound builder needs a resolved reference to look up its elements.
	 */
	private void defineSchema(boolean transactional, boolean compound) {
		if (transactional) {
			this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		}
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("brand").withoutGeneratedPrimaryKey()
				.withReflectedReferenceToEntity("products", "product", "brand", reference -> {
					reference.withAttributesInheritedExcept(compound ? "ranking" : "label");
					if (!compound) {
						reference.withAttribute("label", String.class, attribute -> attribute.nullable().filterable()
							.withDescription("local attribute"));
					}
				}).updateVia(session);
			if (compound) {
				session.updateEntitySchema(new ModifyEntitySchemaMutation("brand",
					new ModifyReferenceSortableAttributeCompoundSchemaMutation("products",
						new CreateSortableAttributeCompoundSchemaMutation("ranking", "local compound", null,
							Scope.DEFAULT_SCOPES, attributeElement("validity"), attributeElement("label")))));
			}
			session.defineEntitySchema("product").withoutGeneratedPrimaryKey()
				.withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_MORE, reference -> {
					reference.indexedForFiltering()
						.withAttribute("label", String.class, attribute -> attribute.nullable().filterable())
						.withAttribute("validity", String.class, attribute -> attribute.nullable().filterable());
					if (compound) {
						reference.withSortableAttributeCompound("ranking", attributeElement("label"),
							attributeElement("validity"));
						reference.withSortableAttributeCompound("sharedRanking", attributeElement("validity"),
							attributeElement("label"));
					}
				}).updateVia(session);
		});
	}

	/**
	 * Switches the reflected reference to inherit only the given original names, in a later session.
	 */
	private void inheritOnly(@Nonnull String... names) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.updateEntitySchema(new ModifyEntitySchemaMutation("brand",
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation("products",
					AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED, names)));
		});
	}

	/**
	 * Changes only original reference metadata in a later session.
	 */
	private void changeOriginalDescription() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrowException("product").openForWrite()
				.withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_MORE,
					reference -> reference.withDescription("changed original reference"))
				.updateVia(session);
		});
	}

	/**
	 * Inserts the original reference after both collections and their schemas exist.
	 */
	private void insertOriginal() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.upsertEntity(session.createNewEntity("brand", 1));
			session.upsertEntity(session.createNewEntity("product", 1).setReference("brand", 1,
				reference -> reference.setAttribute("label", "original").setAttribute("validity", "shared")));
		});
	}

	/**
	 * Updates one reference attribute through a fresh write session.
	 */
	private void setValue(
		@Nonnull String entityType, @Nonnull String referenceName, @Nonnull String attributeName,
		@Nonnull String value
	) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.upsertEntity(session.getEntity(entityType, 1,
				referenceContentWithAttributes(referenceName)).orElseThrow().openForWrite()
				.updateReference(referenceName, 1, reference -> reference.setAttribute(attributeName, value)));
		});
	}

	/**
	 * Checks both ends in a fresh read session, including the shared inherited value.
	 */
	private void assertValues(@Nullable String originalLabel, @Nullable String localLabel, @Nonnull String shared) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final ReferenceContract original = session.getEntity("product", 1,
				referenceContentWithAttributes("brand")).orElseThrow().getReference("brand", 1).orElseThrow();
			final ReferenceContract reflected = session.getEntity("brand", 1,
				referenceContentWithAttributes("products")).orElseThrow().getReference("products", 1).orElseThrow();
			assertAll(
				() -> assertEquals(originalLabel, original.getAttribute("label"), "original label"),
				() -> assertEquals(localLabel, reflected.getAttribute("label"), "local label"),
				() -> assertEquals(shared, original.getAttribute("validity"), "original inherited value"),
				() -> assertEquals(shared, reflected.getAttribute("validity"), "reflected inherited value")
			);
		});
	}

	/**
	 * Queries the local value and checks that the original value does not leak into its index.
	 */
	private void assertLocalFilter() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertEquals(List.of(1), session.queryEntityReference(query(collection("brand"),
				filterBy(referenceHaving("products", attributeEquals("label", "local")))))
				.getRecordData().stream().map(it -> it.getPrimaryKey()).toList());
			assertTrue(session.queryEntityReference(query(collection("brand"),
				filterBy(referenceHaving("products", attributeEquals("label", "original")))))
				.getRecordData().isEmpty());
		});
	}

	/**
	 * Checks the merged and declared attribute views separately.
	 */
	private static void assertLocalAttribute(@Nonnull EvitaSessionContract session) {
		final ReflectedReferenceSchema schema = reflected(session);
		assertTrue(schema.getAttribute("label").isPresent(), "local label must remain in the merged schema");
		assertEquals("local attribute", schema.getAttribute("label").orElseThrow().getDescription());
		assertTrue(schema.getDeclaredAttribute("label").isPresent(), "local label must remain declared");
		assertTrue(schema.getDeclaredAttributes().containsKey("label"));
		assertFalse(schema.getDeclaredAttributes().containsKey("validity"));
	}

	/**
	 * Checks the merged and declared compound views separately.
	 */
	private static void assertLocalCompound(@Nonnull EvitaSessionContract session) {
		final ReflectedReferenceSchema schema = reflected(session);
		assertTrue(schema.getSortableAttributeCompound("ranking").isPresent(), "local compound must remain");
		assertEquals("local compound", schema.getSortableAttributeCompound("ranking").orElseThrow().getDescription());
		assertTrue(schema.getDeclaredSortableAttributeCompound("ranking").isPresent());
		assertTrue(schema.getDeclaredSortableAttributeCompounds().containsKey("ranking"));
		assertFalse(schema.getDeclaredSortableAttributeCompounds().containsKey("sharedRanking"));
	}

	/**
	 * Checks that the local attribute stays declared after the original dropped its name and nothing excludes it.
	 */
	private static void assertLocalAttributeWithoutOriginal(@Nonnull EvitaSessionContract session) {
		final ReflectedReferenceSchema schema = reflected(session);
		assertAll(
			() -> assertTrue(session.getEntitySchemaOrThrowException("product").getReferenceOrThrowException("brand")
				.getAttribute("label").isEmpty()),
			() -> assertEquals("local attribute",
				schema.getDeclaredAttribute("label").map(it -> it.getDescription()).orElse(null)),
			() -> assertEquals(Set.of("validity"), schema.getInheritedAttributes()),
			() -> assertEquals(
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT, schema.getAttributesInheritanceBehavior()
			),
			() -> assertEquals(0, schema.getAttributeInheritanceFilter().length)
		);
	}

	/**
	 * Checks that only the explicitly selected original attribute is inherited.
	 */
	private static void assertOnlyValidityInherited(@Nonnull EvitaSessionContract session) {
		final ReflectedReferenceSchema schema = reflected(session);
		assertAll(
			() -> assertEquals(Set.of("validity"), schema.getInheritedAttributes()),
			() -> assertTrue(schema.getAttribute("validity").isPresent()),
			() -> assertTrue(schema.getAttribute("label").isEmpty())
		);
	}

	/**
	 * Obtains the bound DTO used by schema mutation and storage callers.
	 */
	@Nonnull
	private static ReflectedReferenceSchema reflected(@Nonnull EvitaSessionContract session) {
		return (ReflectedReferenceSchema) session.getEntitySchemaOrThrowException("brand")
			.getReferenceOrThrowException("products");
	}

	/**
	 * Reopens the same storage directory using the established engine restart pattern.
	 */
	private void restart() {
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
	}
}
