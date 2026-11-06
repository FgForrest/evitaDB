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

package io.evitadb.externalApi.graphql.api.catalog.dataApi;

import graphql.schema.GraphQLSchema;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.graphql.utils.GraphQLSchemaPrinter;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.utils.CollectionUtils.createHashMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the hashed names of the shared GraphQL reference types (`With<Reference><hash>Reference` and friends)
 * do not depend on the capacity of the {@link HashMap} that happens to hold the entity schemas. That capacity differs
 * between a catalog loaded from disk (restart) and a catalog instance created by a commit or a catalog replace, and
 * it used to pick a different collection as the template of the hash.
 *
 * @see <a href="https://github.com/FgForrest/evitaDB/issues/1892">#1892</a>
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(GRAPHQL)
@Tag(EXTERNAL_API)
@Tag(SCHEMA)
class HashedReferenceTypeNamesStabilityTest implements EvitaTestSupport {
	private static final String BRAND = "Brand";
	private static final String CATEGORY = "Category";
	private static final String PRODUCT = "Product";
	private static final String IMAGE = "Image";
	private static final String REFERENCE = "image";
	private static final String REPLACEMENT_CATALOG = TEST_CATALOG + "Replacement";
	private static final Pattern HASHED_NAME = Pattern.compile("\\b[A-Za-z]*[0-9a-f]{13,16}[A-Za-z]*\\b");

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("HashedReferenceTypeNamesStabilityTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("Fixture precondition: the two map capacities pick different templates")
	void shouldPickDifferentTemplatesForLoadedAndCommittedMapCapacity() {
		// 128 is the capacity used when a catalog is loaded from disk, the collection count when it is committed
		final String loaded = firstCollectionWithReference(128);
		final String committed = firstCollectionWithReference(4);
		assertNotEquals(
			loaded, committed,
			"Fixture is vacuous: both capacities iterate to the same template, so the test cannot detect the flip."
		);
	}

	@Test
	@DisplayName("Hashed names are identical after commit, restart, second restart and catalog replace")
	void shouldKeepHashedNamesAcrossCommitRestartAndReplace() {
		final Set<String> afterCreate = hashedNames(TEST_CATALOG);
		assertFalse(afterCreate.isEmpty(), "Fixture produced no hashed names.");

		touchSchema(TEST_CATALOG);
		assertEquals(afterCreate, hashedNames(TEST_CATALOG), "Names changed after a schema commit.");

		restart();
		assertEquals(afterCreate, hashedNames(TEST_CATALOG), "Names changed after a restart.");

		restart();
		assertEquals(afterCreate, hashedNames(TEST_CATALOG), "Names changed after a second restart.");

		defineCatalog(REPLACEMENT_CATALOG);
		this.evita.replaceCatalog(REPLACEMENT_CATALOG, TEST_CATALOG);
		assertEquals(afterCreate, hashedNames(TEST_CATALOG), "Names changed after a catalog replace.");
	}

	@Test
	@DisplayName("Adding unrelated collections does not rename anything, even across a HashMap capacity threshold")
	void shouldNotRenameWhenUnrelatedCollectionIsAdded() {
		final Set<String> before = hashedNames(TEST_CATALOG);
		// "Aaa..." sorts before every existing collection by name, so sorting by name would hand it the template
		for (final String added : List.of("Aaa", "Aab", "Aac", "Aad", "Aae")) {
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.defineEntitySchema(added).updateVia(session);
			});
			final Set<String> after = hashedNames(TEST_CATALOG);
			assertTrue(after.containsAll(before), "Adding `" + added + "` renamed existing types.");
		}
	}

	@Test
	@DisplayName("Removing the template reference renames types, removing another reference does not")
	void shouldRenameOnlyWhenTemplateReferenceIsRemoved() {
		final Set<String> before = hashedNames(TEST_CATALOG);

		removeReference(PRODUCT);
		assertEquals(
			sharedNames(before), sharedNames(hashedNames(TEST_CATALOG)),
			"Removing a non-template reference renamed shared types."
		);

		removeReference(BRAND);
		assertNotEquals(
			sharedNames(before), sharedNames(hashedNames(TEST_CATALOG)),
			"Removing the template reference left the shared types intact."
		);
	}

	/**
	 * Returns the first collection with a reference when the collections are iterated as a map of given capacity does.
	 */
	@Nonnull
	private static String firstCollectionWithReference(int capacity) {
		final Map<String, Boolean> map = createHashMap(capacity);
		for (final String name : List.of(BRAND, CATEGORY, PRODUCT, IMAGE)) {
			map.put(name, !IMAGE.equals(name));
		}
		return map.entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).findFirst().orElseThrow();
	}

	/**
	 * Keeps only the types shared by all references of the same definition - those are named after the template.
	 */
	@Nonnull
	private static Set<String> sharedNames(@Nonnull Set<String> names) {
		final Set<String> shared = new TreeSet<>();
		for (final String name : names) {
			if (name.startsWith("With") || name.startsWith(IMAGE)) {
				shared.add(name);
			}
		}
		return shared;
	}

	private void defineCatalog(@Nonnull String catalogName) {
		this.evita.defineCatalog(catalogName);
		this.evita.updateCatalog(catalogName, session -> {
			// creation order = primary key order of the collections
			for (final String name : List.of(BRAND, CATEGORY, PRODUCT, IMAGE)) {
				session.defineEntitySchema(name).updateVia(session);
			}
			for (final String owner : List.of(BRAND, CATEGORY, PRODUCT)) {
				session.defineEntitySchema(owner)
					.withReferenceToEntity(
						REFERENCE, IMAGE, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
			}
		});
		this.evita.updateCatalog(catalogName, session -> {
			session.goLiveAndClose();
		});
	}

	private void touchSchema(@Nonnull String catalogName) {
		this.evita.updateCatalog(catalogName, session -> {
			session.defineEntitySchema(IMAGE).withDescription("touched").updateVia(session);
		});
	}

	private void removeReference(@Nonnull String owner) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(owner).withoutReferenceTo(REFERENCE).updateVia(session);
		});
	}

	private void restart() {
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
	}

	@Nonnull
	private Set<String> hashedNames(@Nonnull String catalogName) {
		final GraphQLSchema schema = new CatalogDataApiGraphQLSchemaBuilder(
			new GraphQLOptions("localhost:5555"),
			this.evita,
			this.evita.getCatalogInstance(catalogName).orElseThrow()
		).build();
		final Matcher matcher = HASHED_NAME.matcher(GraphQLSchemaPrinter.print(schema));
		final List<String> names = new ArrayList<>();
		while (matcher.find()) {
			names.add(matcher.group());
		}
		return new TreeSet<>(names);
	}
}
