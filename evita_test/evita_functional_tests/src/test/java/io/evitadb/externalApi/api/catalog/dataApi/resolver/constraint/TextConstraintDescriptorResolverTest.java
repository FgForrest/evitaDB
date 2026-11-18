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


package io.evitadb.externalApi.api.catalog.dataApi.resolver.constraint;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.descriptor.ConstraintDescriptor;
import io.evitadb.api.query.descriptor.ConstraintDescriptorProvider;
import io.evitadb.api.query.descriptor.ConstraintType;
import io.evitadb.api.query.filter.*;
import io.evitadb.api.query.order.*;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.catalog.dataApi.builder.constraint.ConstraintBuildContext;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.*;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/** Round-trips fulltext descriptor keys using a real catalog schema and the production key parser. */
@ExtendWith(EvitaParameterResolver.class)
@Tag(EXTERNAL_API)
@Tag(QUERY)
@DisplayName("Fulltext descriptor keys")
class TextConstraintDescriptorResolverTest {
	/** Shared catalog and fixture identifier. */
	private static final String DATA_SET = "TextConstraintDescriptorKeys";
	/** Catalog whose classifiers are parsed. */
	private static final String CATALOG = "textKeys";

	/**
	 * Defines the schema classifiers referenced by the fulltext keys.
	 *
	 * @param evita engine owning the real schema
	 */
	@DataSet(DATA_SET)
	void setUp(@Nonnull Evita evita) {
		evita.defineCatalog(CATALOG);
		evita.updateCatalog(CATALOG, session -> {
			session.defineEntitySchema("block").updateVia(session);
			session.defineEntitySchema("item").withAttribute("name", String.class)
				.withAttribute("hidden", String.class).withAssociatedData("description", String.class)
				.withReferenceToEntity("brand", "block", Cardinality.ZERO_OR_MORE)
				.withReferenceToEntity("blocks", "block", Cardinality.ZERO_OR_MORE).updateVia(session);
			session.goLiveAndClose();
		});
	}

	/**
	 * Enumerates every fulltext key using the real schema classifiers.
	 *
	 * @return descriptor, suffix, classifier, wire key and whether it needs reference context
	 */
	@Nonnull
	private static Stream<Arguments> textKeys() {
		return Stream.of(
			Arguments.of(AttributeIn.class, null, "name", "attributeNameIn", false),
			Arguments.of(AttributeIn.class, "all", null, "attributeInAll", false),
			Arguments.of(AssociatedDataIn.class, null, "description", "associatedDataDescriptionIn", false),
			Arguments.of(AssociatedDataIn.class, "all", null, "associatedDataInAll", false),
			Arguments.of(ReferenceIn.class, null, "brand", "referenceBrandIn", false),
			Arguments.of(EntityIn.class, null, null, "entityIn", true),
			Arguments.of(AttributeWeight.class, null, "name", "attributeNameWeight", false),
			Arguments.of(AssociatedDataWeight.class, null, "description", "associatedDataDescriptionWeight", false),
			Arguments.of(ReferenceWeight.class, null, "brand", "referenceBrandWeight", false),
			Arguments.of(EntityWeight.class, null, null, "entityWeight", true)
		);
	}

	/**
	 * Builds and parses the fulltext keys with the same locators used during schema building and request resolution.
	 *
	 * @param type constraint class in the registry
	 * @param suffix creator suffix for wildcard selectors
	 * @param classifier schema classifier, absent for wildcard/entity keys
	 * @param expected expected API key
	 * @param reference whether the selector/weight belongs inside a reference
	 * @param evita engine containing the real catalog schema
	 */
	@ParameterizedTest
	@MethodSource("textKeys")
	@UseDataSet(DATA_SET)
	@DisplayName("Every fulltext key builds and parses without collisions")
	void shouldRoundTripTextKeys(
		@Nonnull Class<? extends Constraint<?>> type, @Nullable String suffix,
		@Nullable String classifier, @Nonnull String expected, boolean reference, @Nonnull Evita evita
	) {
		evita.queryCatalog(CATALOG, session -> {
			final DataLocator locator = reference ?
				new ReferenceTextDataLocator(new ManagedEntityTypePointer("item"), "blocks") :
				new TextDataLocator(new ManagedEntityTypePointer("item"));
			final ConstraintDescriptor descriptor = ConstraintDescriptorProvider.getConstraint(type, suffix);
			final ConstraintBuildContext context = new ConstraintBuildContext(locator);
			assertEquals(expected, new ConstraintKeyBuilder().build(context, descriptor, () -> classifier));
			final ConstraintDescriptorResolver parser = new ConstraintDescriptorResolver(
				session.getCatalogSchema(), descriptor.type());
			final var parsed = parser.resolve(new ConstraintResolveContext(locator), expected).orElseThrow();
			assertEquals(descriptor, parsed.constraintDescriptor());
			assertEquals(classifier, parsed.classifier());
		});
	}

	/**
	 * Keeps normal attribute keys that start like a named selector (`attributeHiddenIn...`) resolving to their own
	 * constraints in an entity context, never to a fulltext selector.
	 *
	 * @param evita engine containing the real catalog schema
	 */
	@Test
	@UseDataSet(DATA_SET)
	@DisplayName("Normal attribute keys never resolve to fulltext selectors")
	void shouldNotResolveNormalAttributeKeysAsTextSelectors(@Nonnull Evita evita) {
		evita.queryCatalog(CATALOG, session -> {
			final ConstraintDescriptorResolver filters = new ConstraintDescriptorResolver(
				session.getCatalogSchema(), ConstraintType.FILTER);
			for (final String normal : List.of("attributeHiddenInSet", "attributeHiddenInRange")) {
				final ConstraintDescriptorResolver.ParsedConstraintDescriptor comparison = filters.resolve(
					new ConstraintResolveContext(new EntityDataLocator(new ManagedEntityTypePointer("item"))), normal
				).orElseThrow();
				assertNotEquals(AttributeIn.class, comparison.constraintDescriptor().constraintClass());
			}
		});
	}
}
