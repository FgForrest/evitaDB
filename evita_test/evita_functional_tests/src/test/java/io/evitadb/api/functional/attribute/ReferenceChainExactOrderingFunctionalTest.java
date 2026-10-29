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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.core.exception.AttributeNotFilterableException;
import io.evitadb.core.query.QueryPlan;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact ordering refuses predecessor values before execution, at entity level and inside every reference ordering.
 * Both chain types receive the same explanation under either execution route preference.
 */
@DisplayName("Exact ordering rejects chain attributes consistently")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(ORDER)
@Tag(ATTRIBUTE)
@Tag(REFERENCE)
class ReferenceChainExactOrderingFunctionalTest {
	/**
	 * Dataset holding three owners of one target, so filtering-only partitions are materialized too.
	 */
	private static final String CHAIN_REFERENCES = "chainExactReferences";
	/**
	 * Owner collection with an entity chain and reference chains.
	 */
	private static final String ENTITY_OWNER = "chainOwner";
	/**
	 * Flat collection referenced by every owner.
	 */
	private static final String ENTITY_TARGET = "chainTarget";
	/**
	 * Chain name declared on the entity and on every reference. The target references declare a different chain type
	 * than the entity, so the type named in their refusal shows the reference attribute was resolved.
	 */
	private static final String ATTRIBUTE_CHAIN = "chain";
	/**
	 * Reference chain without an entity attribute of the same name.
	 */
	private static final String ATTRIBUTE_REFERENCE_CHAIN = "referenceOnlyChain";
	/**
	 * Both predecessor types over both reference indexing levels.
	 */
	private static final String[] REFERENCES = {
		"ownerChainPartitioning", "ownerChainFiltering", "targetChainPartitioning", "targetChainFiltering"
	};

	/**
	 * Creates real chains with head owner 102, and one referenced-entity head per owner.
	 */
	@DataSet(value = CHAIN_REFERENCES, destroyAfterClass = true)
	void setUp(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_TARGET).withoutGeneratedPrimaryKey().updateVia(session);
		session.createNewEntity(ENTITY_TARGET, 1).upsertVia(session);
		final EntitySchemaBuilder schema = session.defineEntitySchema(ENTITY_OWNER).withoutGeneratedPrimaryKey()
			.withAttribute(ATTRIBUTE_CHAIN, Predecessor.class, thatIs -> thatIs.sortable().nullable());
		for (final String reference : REFERENCES) {
			final boolean partitioning = reference.endsWith("Partitioning");
			final Class<? extends Serializable> type = reference.startsWith("target") ?
				ReferencedEntityPredecessor.class : Predecessor.class;
			schema.withReferenceToEntity(
				reference, ENTITY_TARGET, Cardinality.ZERO_OR_MORE,
				whichIs -> (partitioning ? whichIs.indexedForFilteringAndPartitioning() : whichIs.indexedForFiltering())
					.withAttribute(ATTRIBUTE_CHAIN, type, thatIs -> thatIs.sortable().nullable())
					.withAttribute(ATTRIBUTE_REFERENCE_CHAIN, type, thatIs -> thatIs.sortable().nullable())
			);
		}
		schema.updateVia(session);
		for (final int primaryKey : new int[]{102, 100, 104}) {
			final Predecessor value = primaryKey == 102 ? Predecessor.HEAD :
				new Predecessor(primaryKey == 100 ? 102 : 100);
			final EntityBuilder owner = session.createNewEntity(ENTITY_OWNER, primaryKey)
				.setAttribute(ATTRIBUTE_CHAIN, value);
			for (final String reference : REFERENCES) {
				final Serializable referenceValue = reference.startsWith("target") ?
					ReferencedEntityPredecessor.HEAD : value;
				owner.setReference(reference, 1, whichIs -> whichIs.setAttribute(ATTRIBUTE_CHAIN, referenceValue)
					.setAttribute(ATTRIBUTE_REFERENCE_CHAIN, referenceValue));
			}
			owner.upsertVia(session);
		}
	}

	/**
	 * Enumerates both routes for both types, indexing levels, name collisions and reference ordering behaviours.
	 */
	@Nonnull
	static Stream<Arguments> referenceOrders() {
		return Stream.of(REFERENCES).flatMap(reference -> Stream.of(
			ATTRIBUTE_CHAIN, ATTRIBUTE_REFERENCE_CHAIN
		).flatMap(attribute -> Stream.of("implicit", "pickFirst", "traverse").flatMap(ordering -> Stream.of(
			DebugMode.PREFER_INDEX_SCAN, DebugMode.PREFER_PREFETCHING
		).map(route -> Arguments.of(reference, attribute, ordering, route)))));
	}

	/**
	 * Enumerates both routes for both types, indexing levels and name collisions.
	 */
	@Nonnull
	static Stream<Arguments> referenceAttributes() {
		return Stream.of(REFERENCES).flatMap(reference -> Stream.of(
			ATTRIBUTE_CHAIN, ATTRIBUTE_REFERENCE_CHAIN
		).flatMap(attribute -> Stream.of(
			DebugMode.PREFER_INDEX_SCAN, DebugMode.PREFER_PREFETCHING
		).map(route -> Arguments.of(reference, attribute, route))));
	}

	/**
	 * Refuses exact entity chain values before either execution route starts.
	 */
	@DisplayName("An exact entity chain order is rejected before execution")
	@UseDataSet(CHAIN_REFERENCES)
	@ParameterizedTest
	@EnumSource(
		value = DebugMode.class, names = {"PREFER_INDEX_SCAN", "PREFER_PREFETCHING"}
	)
	void shouldRejectExactEntityChainOrdering(@Nonnull DebugMode route, @Nonnull EvitaSessionContract session) {
		final EvitaInvalidUsageException exception = assertThrows(
			EvitaInvalidUsageException.class,
			() -> session.query(
				query(
					collection(ENTITY_OWNER), filterBy(entityPrimaryKeyInSet(100, 102, 104)),
					orderBy(attributeSetExact(ATTRIBUTE_CHAIN, Predecessor.HEAD)),
					require(page(1, 10), debug(route))
				), EntityReference.class
			)
		);
		assertChainRefusal(exception, ATTRIBUTE_CHAIN, Predecessor.class);
	}

	/**
	 * Requires the same explanation for both chain types in every reference ordering, with or without a name collision.
	 */
	@DisplayName("An exact reference chain order is rejected before execution")
	@UseDataSet(CHAIN_REFERENCES)
	@ParameterizedTest(name = "{0}, {1}, {2}, {3}")
	@MethodSource("referenceOrders")
	void shouldRejectExactReferenceChainOrdering(
		@Nonnull String reference, @Nonnull String attribute, @Nonnull String ordering, @Nonnull DebugMode route,
		@Nonnull EvitaSessionContract session
	) {
		final Serializable head = reference.startsWith("target") ? ReferencedEntityPredecessor.HEAD : Predecessor.HEAD;
		final EvitaInvalidUsageException exception = assertThrows(
			EvitaInvalidUsageException.class,
			() -> session.query(
				query(
					collection(ENTITY_OWNER), filterBy(entityPrimaryKeyInSet(100, 102, 104)),
					orderBy(
						referenceOrder(reference, ordering, attributeSetExact(attribute, head)),
						entityPrimaryKeyNatural(OrderDirection.DESC)
					),
					require(page(1, 10), debug(route))
				), EntityReference.class
			)
		);
		assertChainRefusal(exception, attribute, head.getClass());
	}

	/**
	 * Characterizes the prerequisite filter's refusal without adding an ordering prohibition.
	 */
	@DisplayName("An entity chain cannot supply the filter values for exact ordering")
	@UseDataSet(CHAIN_REFERENCES)
	@ParameterizedTest
	@EnumSource(
		value = DebugMode.class, names = {"PREFER_INDEX_SCAN", "PREFER_PREFETCHING"}
	)
	void shouldRejectEntityChainFilterValues(@Nonnull DebugMode route, @Nonnull EvitaSessionContract session) {
		final AttributeNotFilterableException exception = assertThrows(
			AttributeNotFilterableException.class,
			() -> session.query(
				query(
					collection(ENTITY_OWNER), filterBy(attributeInSet(ATTRIBUTE_CHAIN, Predecessor.HEAD)),
					orderBy(attributeSetInFilter(ATTRIBUTE_CHAIN)), require(page(1, 10), debug(route))
				), EntityReference.class
			)
		);
		assertTrue(exception.getMessage().contains("`" + ATTRIBUTE_CHAIN + "`"), exception::getMessage);
	}

	/**
	 * Checks the prerequisite chain filter for both types on both route preferences. The filter is planned before
	 * any ordering, so one reference ordering form stands for all of them.
	 */
	@DisplayName("A reference chain cannot supply the filter values for exact ordering")
	@UseDataSet(CHAIN_REFERENCES)
	@ParameterizedTest(name = "{0}, {1}, {2}")
	@MethodSource("referenceAttributes")
	void shouldRejectReferenceChainFilterValues(
		@Nonnull String reference, @Nonnull String attribute, @Nonnull DebugMode route,
		@Nonnull EvitaSessionContract session
	) {
		final Serializable head = reference.startsWith("target") ? ReferencedEntityPredecessor.HEAD : Predecessor.HEAD;
		final AttributeNotFilterableException exception = assertThrows(
			AttributeNotFilterableException.class,
			() -> session.query(
				query(
					collection(ENTITY_OWNER), filterBy(referenceHaving(reference, attributeInSet(attribute, head))),
					orderBy(referenceProperty(reference, attributeSetInFilter(attribute))),
					require(page(1, 10), debug(route))
				), EntityReference.class
			)
		);
		assertTrue(exception.getMessage().contains("`" + attribute + "`"), exception::getMessage);
		assertTrue(exception.getMessage().contains("`" + reference + "`"), exception::getMessage);
	}

	/**
	 * Wraps the requested ordering in each supported reference ordering behaviour.
	 */
	@Nonnull
	private static OrderConstraint referenceOrder(
		@Nonnull String reference, @Nonnull String ordering, @Nonnull OrderConstraint exact
	) {
		return switch (ordering) {
			case "implicit" -> referenceProperty(reference, exact);
			case "pickFirst" -> referenceProperty(
				reference, pickFirstByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)), exact
			);
			case "traverse" -> referenceProperty(
				reference, traverseByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)), exact
			);
			default -> throw new IllegalArgumentException("Unknown reference ordering: " + ordering);
		};
	}

	/**
	 * Requires an actionable diagnostic from translation, before query execution is entered - a `QueryPlan#execute`
	 * frame on the stack would mean the refusal came from execution instead.
	 */
	private static void assertChainRefusal(
		@Nonnull EvitaInvalidUsageException exception, @Nonnull String attribute, @Nonnull Class<?> type
	) {
		assertEquals(
			"Constraint `attributeSetExact` cannot order chain attribute `" + attribute + "` of type `" +
				type.getName() + "`: a chain orders by predecessor links, so its values cannot be listed. " +
				"Use `attributeNatural` to order by the chain instead; inside `referenceProperty` it needs " +
				"`traverseByEntityProperty` or no explicit traversal, not `pickFirstByEntityProperty`.",
			exception.getMessage()
		);
		assertFalse(
			Arrays.stream(exception.getStackTrace()).anyMatch(
				frame -> frame.getClassName().equals(QueryPlan.class.getName()) &&
					frame.getMethodName().equals("execute")
			), exception::getMessage
		);
	}
}
