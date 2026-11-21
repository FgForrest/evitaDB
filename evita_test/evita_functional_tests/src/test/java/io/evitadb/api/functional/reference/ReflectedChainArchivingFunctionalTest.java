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
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks scope changes for owners whose reflected rows inherit an inverted predecessor.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Archiving owners of reflected predecessor references")
@Tag(CONTRACT)
@Tag(REFERENCE)
class ReflectedChainArchivingFunctionalTest implements EvitaTestSupport {
	private static final String CATALOG = "reflectedChainArchiving";
	private static final String PRODUCT = "product";
	private static final String VALUE = "parameterValue";
	private static final String PRODUCTS = "products";
	private static final String VALUES = "parameterValues";
	private static final String POSITION = "position";
	private static final Scope[] SCOPES = {Scope.LIVE, Scope.ARCHIVED};

	/**
	 * Archives two of three owners of the same reflected partition in separate transactions, then checks that the
	 * remaining owner still orders its reflected row.
	 */
	@Test
	@DisplayName("Should archive two products sharing a target with inherited predecessor values")
	void shouldArchiveOwnersSharingAnInheritedPredecessor() {
		final TestPaths paths = createTestPaths(CATALOG);
		try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
			evita.defineCatalog(CATALOG).updateViaNewSession(evita);
			evita.updateCatalog(CATALOG, ReflectedChainArchivingFunctionalTest::populate);
			evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
			for (final int product : List.of(1, 2)) {
				evita.updateCatalog(CATALOG, session -> {
					assertTrue(session.archiveEntity(PRODUCT, product));
				});
			}
			final List<SealedEntity> archived = evita.queryCatalog(
				CATALOG, (EvitaSessionContract session) -> session.queryListOfSealedEntities(
					query(collection(PRODUCT), filterBy(scope(Scope.ARCHIVED)), require(entityFetch()))
				)
			);
			assertEquals(List.of(1, 2), archived.stream().map(SealedEntity::getPrimaryKeyOrThrowException).toList());

			final List<SealedEntity> remaining = evita.queryCatalog(
				CATALOG, (EvitaSessionContract session) -> session.queryListOfSealedEntities(
					query(
						collection(PRODUCT), filterBy(scope(Scope.LIVE)),
						require(entityFetch(referenceContent(VALUES, orderBy(attributeNatural(POSITION)))))
					)
				)
			);
			assertEquals(List.of(3), remaining.stream().map(SealedEntity::getPrimaryKeyOrThrowException).toList());
			assertEquals(
				List.of(1),
				remaining.getFirst().getReferences(VALUES).stream()
					.map(ReferenceContract::getReferencedPrimaryKey)
					.toList()
			);
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Writes a value referencing three products with a forward predecessor which is inherited by the reflection.
	 *
	 * @param session catalog writer
	 */
	private static void populate(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(PRODUCT).updateVia(session);
		session.defineEntitySchema(VALUE)
			.withReferenceToEntity(PRODUCTS, PRODUCT, Cardinality.ZERO_OR_MORE, ref -> ref
				.indexedForFilteringInScope(SCOPES)
				.withAttribute(POSITION, Predecessor.class, attr -> attr.sortableInScope(SCOPES)))
			.updateVia(session);
		session.defineEntitySchema(PRODUCT)
			.withReflectedReferenceToEntity(VALUES, VALUE, PRODUCTS, ref -> ref
				.withCardinality(Cardinality.ZERO_OR_MORE)
				.withAttributesInherited()
				.indexedForFilteringInScope(SCOPES))
			.updateVia(session);
		session.createNewEntity(PRODUCT, 1).upsertVia(session);
		session.createNewEntity(PRODUCT, 2).upsertVia(session);
		session.createNewEntity(PRODUCT, 3).upsertVia(session);
		session.createNewEntity(VALUE, 1)
			.setReference(PRODUCTS, 1, ref -> ref.setAttribute(POSITION, Predecessor.HEAD))
			.setReference(PRODUCTS, 2, ref -> ref.setAttribute(POSITION, Predecessor.HEAD))
			.setReference(PRODUCTS, 3, ref -> ref.setAttribute(POSITION, Predecessor.HEAD))
			.upsertVia(session);
	}
}
