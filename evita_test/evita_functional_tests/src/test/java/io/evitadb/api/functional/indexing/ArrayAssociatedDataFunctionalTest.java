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

package io.evitadb.api.functional.indexing;

import io.evitadb.api.exception.InvalidMutationException;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.data.DataItemArray;
import io.evitadb.dataType.data.DataItemMap;
import io.evitadb.dataType.data.DataItemValue;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Locale;
import java.util.Map;

import static io.evitadb.api.query.QueryConstraints.associatedDataContentAll;
import static io.evitadb.api.query.QueryConstraints.dataInLocalesAll;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that associated data holding an array can be replaced through the builder of an entity that already
 * exists, the same way it can be set when the entity is created, and that the replaced value is stored with the
 * array type the schema declares. An array of complex objects is one {@link ComplexDataObject} whose root is
 * a {@link DataItemArray}; a raw array of complex data objects matches no type the schema can declare, so it is
 * refused on its first upsert rather than stored under a type that refuses every later upsert.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Array associated data")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(INDEXING)
class ArrayAssociatedDataFunctionalTest {
	private static final String PRODUCT = "product";
	private static final String NUMBERS = "numbers";
	private static final String LOCALIZED_NUMBERS = "localizedNumbers";
	private static final String OBJECTS = "objects";
	private static final int PK = 1;

	/**
	 * Builds an upsert of the product setting associated data {@link #OBJECTS} to the given value.
	 *
	 * @param value the value of the associated data
	 * @return the upsert mutation
	 */
	@Nonnull
	private static EntityUpsertMutation objectsUpsert(@Nonnull Serializable value) {
		return new EntityUpsertMutation(
			PRODUCT, PK, EntityExistence.MAY_EXIST,
			new UpsertAssociatedDataMutation(OBJECTS, value)
		);
	}

	/**
	 * Creates a complex data object with a single `name` property.
	 *
	 * @param name the value of the property
	 * @return the complex data object
	 */
	@Nonnull
	private static ComplexDataObject createComplexDataObject(@Nonnull String name) {
		return new ComplexDataObject(new DataItemMap(Map.of("name", new DataItemValue(name))));
	}

	@Test
	@DisplayName("should verify every associated data value when upserts share a key")
	void shouldVerifyEveryAssociatedDataValueWhenUpsertsShareKey(Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(PRODUCT).updateVia(session);
			final InvalidMutationException exception = assertThrows(
				InvalidMutationException.class,
				() -> session.upsertEntity(new EntityUpsertMutation(
					PRODUCT, PK, EntityExistence.MAY_EXIST,
					new UpsertAssociatedDataMutation(OBJECTS, createComplexDataObject("first")),
					new UpsertAssociatedDataMutation(OBJECTS, new ComplexDataObject[]{createComplexDataObject("second")})
				))
			);
			assertTrue(exception.getMessage().contains("one `ComplexDataObject`"), exception.getMessage());
			assertTrue(session.getEntity(PRODUCT, PK, associatedDataContentAll()).isEmpty());
		});
	}

	@Test
	@DisplayName("should verify every attribute value when upserts share a key")
	void shouldVerifyEveryAttributeValueWhenUpsertsShareKey(Evita evita) {
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(PRODUCT).withAttribute(NUMBERS, String.class).updateVia(session);
			final InvalidMutationException exception = assertThrows(
				InvalidMutationException.class,
				() -> session.upsertEntity(new EntityUpsertMutation(
					PRODUCT, PK, EntityExistence.MAY_EXIST,
					new UpsertAttributeMutation(NUMBERS, "first"),
					new UpsertAttributeMutation(NUMBERS, 42)
				))
			);
			assertTrue(exception.getMessage().contains("must respect this data type"), exception.getMessage());
		});
	}

	@Test
	@DisplayName("should replace array associated data through the builder of an existing entity")
	void shouldReplaceArrayAssociatedDataThroughExistingEntityBuilder(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT)
					.withLocale(Locale.ENGLISH)
					.withAssociatedData(NUMBERS, Integer[].class)
					.withAssociatedData(LOCALIZED_NUMBERS, Integer[].class, AssociatedDataSchemaEditor::localized)
					.updateVia(session);
				session.createNewEntity(PRODUCT, PK)
					.setAssociatedData(NUMBERS, new Integer[]{1, 2})
					.setAssociatedData(LOCALIZED_NUMBERS, Locale.ENGLISH, new Integer[]{4, 5})
					.upsertVia(session);
			}
		);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.getEntity(PRODUCT, PK, associatedDataContentAll(), dataInLocalesAll())
					.orElseThrow()
					.openForWrite()
					.setAssociatedData(NUMBERS, new Integer[]{3})
					.setAssociatedData(LOCALIZED_NUMBERS, Locale.ENGLISH, new Integer[]{6})
					.upsertVia(session);
			}
		);

		final Serializable[][] stored = evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final SealedEntity product = session.getEntity(
					PRODUCT, PK, associatedDataContentAll(), dataInLocalesAll()
				).orElseThrow();
				return new Serializable[][]{
					product.getAssociatedDataArray(NUMBERS),
					product.getAssociatedDataArray(LOCALIZED_NUMBERS, Locale.ENGLISH)
				};
			}
		);
		assertAll(
			() -> assertArrayEquals(new Integer[]{3}, stored[0]),
			() -> assertEquals(Integer[].class, stored[0].getClass()),
			() -> assertArrayEquals(new Integer[]{6}, stored[1]),
			() -> assertEquals(Integer[].class, stored[1].getClass())
		);
	}

	@Test
	@DisplayName("should refuse an array of complex data objects on the first upsert when the schema lacks it")
	void shouldRefuseArrayOfComplexDataObjectsOnFirstUpsertWhenSchemaIsMissing(Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(PRODUCT).updateVia(session);
			}
		);

		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final InvalidMutationException exception = assertThrows(
					InvalidMutationException.class,
					() -> session.upsertEntity(
						objectsUpsert(new ComplexDataObject[]{createComplexDataObject("first")})
					)
				);
				final String message = exception.getMessage();
				assertTrue(message.contains("`" + OBJECTS + "`"), message);
				assertTrue(message.contains("one `ComplexDataObject`"), message);
			}
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertAll(
					() -> assertTrue(session.getEntity(PRODUCT, PK, associatedDataContentAll()).isEmpty()),
					() -> assertTrue(
						session.getEntitySchemaOrThrowException(PRODUCT).getAssociatedData(OBJECTS).isEmpty()
					)
				);
			}
		);

		// the refusal left nothing behind - the array passed as one complex data object is accepted under the name
		final ComplexDataObject objects = new ComplexDataObject(
			new DataItemArray(
				new DataItem[]{createComplexDataObject("first").root(), createComplexDataObject("second").root()}
			)
		);
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(objectsUpsert(objects));
			}
		);
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				assertAll(
					() -> assertEquals(
						objects,
						session.getEntity(PRODUCT, PK, associatedDataContentAll()).orElseThrow()
							.getAssociatedData(OBJECTS)
					),
					() -> assertEquals(
						ComplexDataObject.class,
						session.getEntitySchemaOrThrowException(PRODUCT).getAssociatedData(OBJECTS).orElseThrow()
							.getType()
					)
				);
			}
		);
	}

}
