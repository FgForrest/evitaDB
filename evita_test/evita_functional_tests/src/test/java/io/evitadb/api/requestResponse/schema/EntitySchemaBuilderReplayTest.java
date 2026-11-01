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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaNullableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaSortableMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaDescriptionMutation;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the entity schema builder replays its mutations to the same schema the server gets by applying them
 * one by one. The builder applies a run of mutations that change attributes alone to the attributes and builds the
 * entity schema once for the whole run, so these tests compare it with the one-by-one application in everything the
 * schema exposes: content, version, the order of the attributes and the classes they are held as.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Entity schema builder replay should")
@Tag(CONTRACT)
@Tag(SCHEMA)
class EntitySchemaBuilderReplayTest {
	private static final String ENTITY_TYPE = "replayedEntity";
	private static final String MISSING_ATTRIBUTE = "missing";
	private final CatalogSchema catalogSchema = CatalogSchema._internalBuild(
		"replayCatalog",
		NamingConvention.generate("replayCatalog"),
		null,
		EnumSet.allOf(CatalogEvolutionMode.class),
		new EntitySchemaProvider() {
			@Nonnull
			@Override
			public Collection<EntitySchemaContract> getEntitySchemas() {
				return List.of();
			}

			@Nonnull
			@Override
			public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
				return Optional.empty();
			}
		}
	);

	@DisplayName("give the schema the server gets by applying the emitted mutations one by one")
	@Test
	void shouldGiveSchemaOfMutationsAppliedOneByOne() {
		final EntitySchemaContract storedSchema = entityWithAttributes(10);
		final EntitySchemaBuilder builder = new InternalEntitySchemaBuilder(this.catalogSchema, storedSchema);

		// attribute changes mixed with changes of other parts of the schema, a change restored later
		// and changes that remove or create an attribute
		builder
			.withAttribute(attributeName(1), String.class, whichIs -> whichIs.filterable().nonSortable())
			.withDescription("Replayed entity.")
			.withAttribute(attributeName(2), String.class, whichIs -> whichIs.filterable().sortable().withDescription("Second.").nullable())
			.withAttribute("created", Integer.class, whichIs -> whichIs.sortable())
			.withAttribute(attributeName(1), String.class, whichIs -> whichIs.filterable().sortable())
			.withAttribute(attributeName(3), String.class, whichIs -> whichIs.nonFilterable().sortable())
			.withoutAttribute(attributeName(4))
			.withAttribute(attributeName(5), String.class, whichIs -> whichIs.filterable().sortable().localized())
			.withAttribute(attributeName(6), String.class, whichIs -> whichIs.filterable().sortable().withDescription("Sixth."));

		final ModifyEntitySchemaMutation mutation = builder.toMutation().orElseThrow();
		assertSameSchema(applyOneByOne(storedSchema, List.of(mutation.getSchemaMutations())), builder.toInstance());
	}

	@DisplayName("give the schema of the mutations applied one by one when it replays a list handed to it")
	@Test
	void shouldGiveSchemaOfHandedMutationsAppliedOneByOne() {
		final EntitySchemaContract storedSchema = entityWithAttributes(5);
		final List<LocalEntitySchemaMutation> mutations = List.of(
			new SetAttributeSchemaSortableMutation(attributeName(0), false),
			new ModifyAttributeSchemaDescriptionMutation(attributeName(1), "First."),
			new ModifyEntitySchemaDescriptionMutation("Replayed entity."),
			new SetAttributeSchemaNullableMutation(attributeName(2), true),
			// changes nothing: the attribute is sortable already
			new SetAttributeSchemaSortableMutation(attributeName(3), true),
			new SetAttributeSchemaSortableMutation(attributeName(4), false)
		);

		final EntitySchemaContract replayed = new InternalEntitySchemaBuilder(this.catalogSchema, storedSchema, mutations)
			.toInstance();

		assertSameSchema(applyOneByOne(storedSchema, mutations), replayed);
	}

	@DisplayName("fail at the mutation and with the message the one-by-one application fails with")
	@Test
	void shouldFailLikeMutationsAppliedOneByOne() {
		final EntitySchemaContract storedSchema = entityWithAttributes(3);
		final List<LocalEntitySchemaMutation> mutations = List.of(
			new SetAttributeSchemaSortableMutation(attributeName(0), false),
			new SetAttributeSchemaSortableMutation(MISSING_ATTRIBUTE, false),
			new SetAttributeSchemaSortableMutation(attributeName(1), false)
		);

		final InvalidSchemaMutationException expected = assertThrows(
			InvalidSchemaMutationException.class, () -> applyOneByOne(storedSchema, mutations)
		);
		final InvalidSchemaMutationException replayed = assertThrows(
			InvalidSchemaMutationException.class,
			() -> new InternalEntitySchemaBuilder(this.catalogSchema, storedSchema, mutations).toInstance()
		);
		assertEquals(expected.getMessage(), replayed.getMessage());
	}

	@DisplayName("keep the attributes in the order of the mutations applied one by one")
	@Test
	void shouldKeepAttributeOrderOfMutationsAppliedOneByOne() {
		// enough attributes for many of them to share a bucket of the hash map that holds them
		final int attributeCount = 500;
		final EntitySchemaContract storedSchema = entityWithAttributes(attributeCount);
		final List<LocalEntitySchemaMutation> mutations = new ArrayList<>(attributeCount * 2);
		for (int i = 0; i < attributeCount; i += 3) {
			mutations.add(new SetAttributeSchemaNullableMutation(attributeName(i), true));
		}
		for (int i = attributeCount - 1; i >= 0; i -= 7) {
			mutations.add(new ModifyAttributeSchemaDescriptionMutation(attributeName(i), "Changed " + i + "."));
		}
		for (int i = 0; i < attributeCount; i += 6) {
			// changes again an attribute changed before, which moves it after the others
			mutations.add(new SetAttributeSchemaSortableMutation(attributeName(i), false));
		}

		final EntitySchemaContract replayed = new InternalEntitySchemaBuilder(this.catalogSchema, storedSchema, mutations)
			.toInstance();

		assertSameSchema(applyOneByOne(storedSchema, mutations), replayed);
	}

	/**
	 * Returns the name of the attribute with the given index.
	 */
	@Nonnull
	private static String attributeName(int index) {
		return "attribute" + index;
	}

	/**
	 * Asserts that the replayed schema equals the schema of the one-by-one application in content, version, the
	 * order of its attributes and the classes the attributes are held as.
	 */
	private static void assertSameSchema(@Nonnull EntitySchemaContract expected, @Nonnull EntitySchemaContract replayed) {
		assertEquals(expected.version(), replayed.version());
		// the equality of entity schemas covers their name and version only
		assertEquals(expected.getDescription(), replayed.getDescription());
		assertEquals(expected.getAttributes(), replayed.getAttributes());
		assertEquals(expected.getSortableAttributeCompounds(), replayed.getSortableAttributeCompounds());
		assertEquals(expected.getReferences(), replayed.getReferences());
		assertEquals(expected.getAssociatedData(), replayed.getAssociatedData());
		assertEquals(new ArrayList<>(expected.getAttributes().keySet()), new ArrayList<>(replayed.getAttributes().keySet()));
		for (Entry<String, EntityAttributeSchemaContract> attribute : expected.getAttributes().entrySet()) {
			assertEquals(
				attribute.getValue().getClass(), replayed.getAttributes().get(attribute.getKey()).getClass(),
				attribute.getKey()
			);
		}
	}

	/**
	 * Returns a stored entity schema with the given number of filterable and sortable attributes.
	 */
	@Nonnull
	private EntitySchemaContract entityWithAttributes(int attributeCount) {
		final EntitySchemaBuilder builder = new InternalEntitySchemaBuilder(this.catalogSchema, EntitySchema._internalBuild(ENTITY_TYPE));
		for (int i = 0; i < attributeCount; i++) {
			builder.withAttribute(attributeName(i), String.class, whichIs -> whichIs.filterable().sortable());
		}
		return applyOneByOne(EntitySchema._internalBuild(ENTITY_TYPE), List.of(builder.toMutation().orElseThrow().getSchemaMutations()));
	}

	/**
	 * Applies the mutations to the schema one by one, the way the server applies the mutations a client sends.
	 */
	@Nonnull
	private EntitySchemaContract applyOneByOne(
		@Nonnull EntitySchemaContract schema,
		@Nonnull List<LocalEntitySchemaMutation> mutations
	) {
		EntitySchemaContract result = schema;
		for (LocalEntitySchemaMutation mutation : mutations) {
			result = mutation.mutate(this.catalogSchema, result);
		}
		return result;
	}

}
