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


package io.evitadb.index.virtual;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.ChainableType;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies that the chains of `Predecessor` and `ReferencedEntityPredecessor` reference attributes order the owners
 * and references of single-owner partitions kept as registry rows exactly as they order them when every partition is
 * an index object: a row is a singleton chain read out of the registry - on the block route of an entity ordering,
 * and on the by-key route of `referenceContent(orderBy)`, for the original reference and for its reflection, whose
 * chain is read in the other collection.
 *
 * Every catalog is built in one engine together with the object catalogs of its {@link MaterializedPartitionTwin}s -
 * one cloning each collection, because the original reference and its reflection are owned by different collections
 * and a route may read either. The answer of an object catalog, mapped back to the originals, is the expected answer.
 * A chain orders by its structure and by the history of its index, not by primary key, so cloning owners adds a second
 * chain of equal length next to the original one; the class therefore first proves, on every strict chain of the
 * fixture, that the mapped answer of the object catalogs is exactly the order the entity bodies give - see
 * `shouldOrderTheStrictChainsOfTheObjectCatalogsAsTheBodiesDo`. The fixtures:
 *
 * - {@link ParameterValuesShapedFixture} - every case mixes a single-owner partition with a real chain: the parameter
 *   values shared by every product chain all products, a value of a product's own (or the lonely value) is a
 *   single-owner partition; owner and target primary keys never coincide;
 * - a duplicate-heavy catalog: {@link #OWNER_COUNT} owners each holding two duplicate references to one target, told
 *   apart by their representative attribute - {@link #OWNER_COUNT} times two single-owner partitions of one target;
 *   the target reflects the reference, so its references are ordered through the owners' partitions - the reflected
 *   route. Every reference is a head of its own chain, so the bodies give no order there; the object catalogs alone
 *   are the expectation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Read parity of chains over virtual reference partitions")
@Tag(ENGINE)
@Tag(ORDER)
@Tag(REFERENCE)
@TestInstance(Lifecycle.PER_CLASS)
class VirtualChainParityTest implements EvitaTestSupport {
	private static final String CATALOG_PARAMETERS = "virtualChainParityParameters";
	private static final String CATALOG_DUPLICATES = "virtualChainParityDuplicates";
	private static final String ENTITY_TARGET = "target";
	private static final String ENTITY_OWNER = "owner";
	private static final String REFERENCE_ITEMS = "items";
	private static final String REFERENCE_OWNERS = "owners";
	private static final String ATTRIBUTE_KIND = "kind";
	private static final String ATTRIBUTE_ORDER = "order";
	/**
	 * The only target of the duplicate-heavy catalog.
	 */
	private static final int TARGET = 7;
	/**
	 * How many owners hold two duplicate references to {@link #TARGET}.
	 */
	private static final int OWNER_COUNT = 400;
	/**
	 * The page large enough to hold every entity of every catalog, clones included.
	 */
	private static final int PAGE_SIZE = 100_000;
	/**
	 * The value product `3` holds alone.
	 */
	private static final int OWN_VALUE_OF_PRODUCT_3 = ParameterValuesShapedFixture.FIRST_OWN_VALUE +
		2 * ParameterValuesShapedFixture.ROWS_PER_PRODUCT;
	/**
	 * The value product `1` holds alone.
	 */
	private static final int OWN_VALUE_OF_PRODUCT_1 = ParameterValuesShapedFixture.FIRST_OWN_VALUE;

	private TestPaths paths;
	private Evita evita;
	/**
	 * The twin of {@link #CATALOG_PARAMETERS} cloning its products - every partition of `parameterValues` an object.
	 */
	private MaterializedPartitionTwin parameterProducts;
	/**
	 * The twin of {@link #CATALOG_PARAMETERS} cloning its parameter values - every partition of `products` an object.
	 */
	private MaterializedPartitionTwin parameterValues;
	/**
	 * The twin of {@link #CATALOG_DUPLICATES} cloning its owners - every partition of `items` an object.
	 */
	private MaterializedPartitionTwin duplicateOwners;
	/**
	 * The twin of {@link #CATALOG_DUPLICATES} cloning its target - every partition of `owners` an object.
	 */
	private MaterializedPartitionTwin duplicateTargets;

	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("VirtualChainParityTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		MaterializedPartitionTwin.createRowCatalog(
			this.evita, CATALOG_PARAMETERS, ParameterValuesShapedFixture::setUp, session -> {
			}
		);
		this.parameterProducts = MaterializedPartitionTwin.create(
			this.evita, CATALOG_PARAMETERS, ParameterValuesShapedFixture::setUp, session -> {
			},
			ParameterValuesShapedFixture.ENTITY_PRODUCT, ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES
		);
		this.parameterValues = MaterializedPartitionTwin.create(
			this.evita, CATALOG_PARAMETERS, ParameterValuesShapedFixture::setUp, session -> {
			},
			ParameterValuesShapedFixture.ENTITY_PARAMETER_VALUE, ParameterValuesShapedFixture.REFERENCE_PRODUCTS
		);
		MaterializedPartitionTwin.createRowCatalog(
			this.evita, CATALOG_DUPLICATES, VirtualChainParityTest::setUpDuplicates, session -> {
			}
		);
		this.duplicateOwners = MaterializedPartitionTwin.create(
			this.evita, CATALOG_DUPLICATES, VirtualChainParityTest::setUpDuplicates, session -> {
			},
			ENTITY_OWNER, REFERENCE_ITEMS
		);
		this.duplicateTargets = MaterializedPartitionTwin.create(
			this.evita, CATALOG_DUPLICATES, VirtualChainParityTest::setUpDuplicates, session -> {
			},
			ENTITY_TARGET, REFERENCE_OWNERS
		);
	}

	@AfterAll
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("the object catalogs order every strict chain of the fixture as the entity bodies do")
	void shouldOrderTheStrictChainsOfTheObjectCatalogsAsTheBodiesDo() {
		// the references of a value ordered by its `ReferencedEntityPredecessor` are left out - the engine does not
		// order them by the chain in any representation (#1691, defect 1)
		final String product = ParameterValuesShapedFixture.ENTITY_PRODUCT;
		final String value = ParameterValuesShapedFixture.ENTITY_PARAMETER_VALUE;
		final String parameterValuesReference = ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES;
		final String productsReference = ParameterValuesShapedFixture.REFERENCE_PRODUCTS;
		final String order = ParameterValuesShapedFixture.ATTRIBUTE_ORDER;
		final String position = ParameterValuesShapedFixture.ATTRIBUTE_POSITION;
		int proven = 0;
		final List<String> mismatches = new ArrayList<>(8);
		for (OrderDirection direction : OrderDirection.values()) {
			// the block route: the products of one value in the chain of `order`, a `Predecessor` among the products
			for (int block : new int[]{
				ParameterValuesShapedFixture.FIRST_SHARED_VALUE, OWN_VALUE_OF_PRODUCT_3,
				ParameterValuesShapedFixture.LONELY_PARAMETER_VALUE
			}) {
				final List<Integer> bodies = directed(
					chainOfOwners(product, parameterValuesReference, block, order), direction
				);
				final List<Integer> objects = this.parameterProducts.expectedAnswer(
					product,
					queryPrimaryKeys(
						this.parameterProducts.objectCatalog(), product, Scope.LIVE,
						referenceHaving(parameterValuesReference, entityPrimaryKeyInSet(block)),
						referenceProperty(
							parameterValuesReference, traverseByEntityProperty(entityPrimaryKeyNatural(direction)),
							attributeNatural(order, direction)
						)
					)
				);
				proveEqual(mismatches, bodies, objects, "The products of value " + block + " " + direction);
				proven++;
			}
			// the block route of the original side: the values of one product in the chain of `position`
			for (int block : new int[]{3, ParameterValuesShapedFixture.LONELY_OWNER}) {
				final List<Integer> bodies = directed(
					chainOfOwners(value, productsReference, block, position), direction
				);
				final List<Integer> objects = this.parameterValues.expectedAnswer(
					value,
					queryPrimaryKeys(
						this.parameterValues.objectCatalog(), value, Scope.LIVE,
						referenceHaving(productsReference, entityPrimaryKeyInSet(block)),
						referenceProperty(
							productsReference, traverseByEntityProperty(entityPrimaryKeyNatural(direction)),
							attributeNatural(position, direction)
						)
					)
				);
				proveEqual(mismatches, bodies, objects, "The values of product " + block + " " + direction);
				proven++;
			}
			// the by-key route: the references of one entity in their chain, read through both object catalogs
			for (int owner : new int[]{3, 5, ParameterValuesShapedFixture.LONELY_OWNER}) {
				final List<Integer> bodies = directed(
					chainOfReferences(product, parameterValuesReference, owner, position), direction
				);
				proveEqual(
					mismatches, bodies,
					referencesOf(this.parameterProducts, product, owner, parameterValuesReference, position, direction),
					"The values of product " + owner + " " + direction + " through the cloned products"
				);
				proveEqual(
					mismatches, bodies,
					referencesOf(this.parameterValues, product, owner, parameterValuesReference, position, direction),
					"The values of product " + owner + " " + direction + " through the cloned values"
				);
				proven++;
			}
		}
		assertTrue(mismatches.isEmpty(), () -> mismatches.size() + " mismatches: " + mismatches);
		assertEquals(16, proven, "Every strict chain must have been proven");
	}

	@Test
	@DisplayName("should order the owners of a single-owner partition and a real chain identically on the block route")
	void shouldOrderBlocksOfVirtualSingletonAndRealChainIdentically() {
		final List<String> differences = new ArrayList<>(16);
		for (String attribute : new String[]{
			ParameterValuesShapedFixture.ATTRIBUTE_ORDER, ParameterValuesShapedFixture.ATTRIBUTE_POSITION
		}) {
			for (OrderDirection targetDirection : OrderDirection.values()) {
				for (OrderDirection direction : OrderDirection.values()) {
					// the reflected side: products in the blocks of parameter values
					final OrderConstraint productOrdering = referenceProperty(
						ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES,
						traverseByEntityProperty(entityPrimaryKeyNatural(targetDirection)),
						attributeNatural(attribute, direction)
					);
					final FilterConstraint productSelection = referenceHaving(
						ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES,
						entityPrimaryKeyInSet(
							ParameterValuesShapedFixture.FIRST_SHARED_VALUE, OWN_VALUE_OF_PRODUCT_3,
							ParameterValuesShapedFixture.LONELY_PARAMETER_VALUE
						)
					);
					// the original side: parameter values in the blocks of products
					final OrderConstraint valueOrdering = referenceProperty(
						ParameterValuesShapedFixture.REFERENCE_PRODUCTS,
						traverseByEntityProperty(entityPrimaryKeyNatural(targetDirection)),
						attributeNatural(attribute, direction)
					);
					final FilterConstraint valueSelection = referenceHaving(
						ParameterValuesShapedFixture.REFERENCE_PRODUCTS,
						entityPrimaryKeyInSet(ParameterValuesShapedFixture.LONELY_OWNER, 1)
					);
					for (Scope scope : Scope.values()) {
						for (MaterializedPartitionTwin twin : new MaterializedPartitionTwin[]{
							this.parameterProducts, this.parameterValues
						}) {
							final boolean products = twin == this.parameterProducts;
							final String entityType = twin.ownerType();
							final FilterConstraint selection = products ? productSelection : valueSelection;
							final OrderConstraint ordering = products ? productOrdering : valueOrdering;
							final List<Integer> expected = twin.expectedAnswer(
								entityType, queryPrimaryKeys(twin.objectCatalog(), entityType, scope, selection, ordering)
							);
							final List<Integer> actual = queryPrimaryKeys(
								twin.rowCatalog(), entityType, scope, selection, ordering
							);
							if (!expected.equals(actual)) {
								differences.add(
									attribute + " " + entityType + " " + scope + " targets " + targetDirection + " " +
										direction + ": " + expected + " vs " + actual
								);
							}
						}
					}
				}
			}
		}
		assertTrue(differences.isEmpty(), () -> differences.size() + " differences: " + differences);
		assertTrue(
			this.parameterProducts.singleOwnerTargets(ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES, Scope.LIVE)
				.containsAll(List.of(OWN_VALUE_OF_PRODUCT_3, ParameterValuesShapedFixture.LONELY_PARAMETER_VALUE)),
			"The own value of product 3 and the lonely value must be single-owner partitions of the products"
		);
		assertTrue(
			this.parameterValues.singleOwnerTargets(ParameterValuesShapedFixture.REFERENCE_PRODUCTS, Scope.LIVE)
				.contains(ParameterValuesShapedFixture.LONELY_OWNER),
			"The lonely product must be a single-owner partition of the values"
		);
	}

	@Test
	@DisplayName("should order the references of single-owner partitions and real chains identically, both sides")
	void shouldOrderReferenceContentChainsIdentically() {
		final List<String> differences = new ArrayList<>(16);
		final Integer[] values = {
			ParameterValuesShapedFixture.FIRST_SHARED_VALUE, ParameterValuesShapedFixture.FIRST_SHARED_VALUE + 1,
			OWN_VALUE_OF_PRODUCT_1, OWN_VALUE_OF_PRODUCT_1 + 1,
			ParameterValuesShapedFixture.LONELY_PARAMETER_VALUE, ParameterValuesShapedFixture.MIXED_PARAMETER_VALUE
		};
		final Integer[] products = {1, 3, 5, ParameterValuesShapedFixture.LONELY_OWNER};
		for (String attribute : new String[]{
			ParameterValuesShapedFixture.ATTRIBUTE_ORDER, ParameterValuesShapedFixture.ATTRIBUTE_POSITION
		}) {
			for (OrderDirection direction : OrderDirection.values()) {
				for (Scope scope : Scope.values()) {
					for (MaterializedPartitionTwin twin : new MaterializedPartitionTwin[]{
						this.parameterProducts, this.parameterValues
					}) {
						// the original side reads the partitions of the values keyed by the products
						compareReferenceOrder(
							twin, ParameterValuesShapedFixture.ENTITY_PARAMETER_VALUE, scope, values,
							ParameterValuesShapedFixture.REFERENCE_PRODUCTS, attributeNatural(attribute, direction),
							attribute + " " + scope + " " + direction, differences
						);
						// the reflected side reads the partitions of the products keyed by the values
						compareReferenceOrder(
							twin, ParameterValuesShapedFixture.ENTITY_PRODUCT, scope, products,
							ParameterValuesShapedFixture.REFERENCE_PARAMETER_VALUES,
							attributeNatural(attribute, direction), attribute + " " + scope + " " + direction,
							differences
						);
					}
				}
			}
		}
		assertTrue(differences.isEmpty(), () -> differences.size() + " differences: " + differences);
	}

	@Test
	@DisplayName("should order the reflected references of duplicate single-owner partitions identically")
	void shouldOrderReflectedReferenceContentOfDuplicatesIdentically() {
		assertEquals(
			OWNER_COUNT, this.duplicateTargets.singleOwnerTargets(REFERENCE_OWNERS, Scope.LIVE).size(),
			"Every partition of the reflected reference must be single-owner"
		);
		for (OrderDirection direction : OrderDirection.values()) {
			final OrderConstraint ordering = attributeNatural(ATTRIBUTE_ORDER, direction);
			final Map<Integer, List<Integer>> actual = queryReferenceOrder(
				CATALOG_DUPLICATES, ENTITY_TARGET, Scope.LIVE, null, REFERENCE_OWNERS, ordering
			);
			assertEquals(2 * OWNER_COUNT, actual.get(TARGET).size());
			// the reflected partitions keyed by the owners are objects when the target is cloned
			assertEquals(
				this.duplicateTargets.expectedAnswerPerOwner(
					queryReferenceOrder(
						this.duplicateTargets.objectCatalog(), ENTITY_TARGET, Scope.LIVE,
						entityPrimaryKeyInSet(MaterializedPartitionTwin.withClones(TARGET)), REFERENCE_OWNERS, ordering
					),
					VirtualChainParityTest::sortedCopy
				),
				actual,
				"The target cloned " + direction
			);
			// the original partitions of the owners are objects when the owners are cloned - their references join
			// the reflection, and the originals keep their order among themselves
			final Map<Integer, List<Integer>> ownersCloned = queryReferenceOrder(
				this.duplicateOwners.objectCatalog(), ENTITY_TARGET, Scope.LIVE, null, REFERENCE_OWNERS, ordering
			);
			assertEquals(
				Map.of(TARGET, originalsOnly(ownersCloned.get(TARGET))), actual, "The owners cloned " + direction
			);
		}
	}

	@Test
	@DisplayName("should order the duplicate references of every owner identically")
	void shouldOrderDuplicateReferencesOfEveryOwnerIdentically() {
		final Map<Integer, List<Integer>> actual = queryReferenceOrder(
			CATALOG_DUPLICATES, ENTITY_OWNER, Scope.LIVE, null, REFERENCE_ITEMS, attributeNatural(ATTRIBUTE_ORDER)
		);
		assertEquals(OWNER_COUNT, actual.size());
		assertEquals(
			this.duplicateOwners.expectedAnswerPerOwner(
				queryReferenceOrder(
					this.duplicateOwners.objectCatalog(), ENTITY_OWNER, Scope.LIVE, null, REFERENCE_ITEMS,
					attributeNatural(ATTRIBUTE_ORDER)
				),
				VirtualChainParityTest::sortedCopy
			),
			actual
		);
		assertTrue(
			this.duplicateOwners.singleOwnerTargets(REFERENCE_ITEMS, Scope.LIVE).contains(TARGET),
			"The duplicates of the target must be single-owner partitions"
		);
	}

	/**
	 * Records a mismatch of the order the bodies give and the order a catalog answers.
	 *
	 * @param mismatches receives the mismatch
	 * @param bodies     the order the bodies give
	 * @param answer     the order the catalog answers - an object catalog's mapped back to the originals
	 * @param label      names the chain
	 */
	private static void proveEqual(
		@Nonnull List<String> mismatches,
		@Nonnull List<Integer> bodies,
		@Nonnull List<Integer> answer,
		@Nonnull String label
	) {
		if (!bodies.equals(answer)) {
			mismatches.add(label + ": bodies " + bodies + " vs answer " + answer);
		}
	}

	/**
	 * Compares the reference order of selected entities of the parameter values catalog with the order an object
	 * catalog gives, mapped back to the originals: per selected entity, the referenced entities in the order the
	 * `referenceContent(orderBy)` sorted them.
	 *
	 * @param twin          the twin whose object catalog gives the expected order
	 * @param entityType    the queried collection
	 * @param scope         the scope
	 * @param selection     the selected entities
	 * @param referenceName the reference whose references are ordered
	 * @param ordering      the ordering of the references
	 * @param label         names the comparison in a difference
	 * @param differences   receives the differences
	 */
	private void compareReferenceOrder(
		@Nonnull MaterializedPartitionTwin twin,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull Integer[] selection,
		@Nonnull String referenceName,
		@Nonnull OrderConstraint ordering,
		@Nonnull String label,
		@Nonnull List<String> differences
	) {
		final boolean ownersCloned = twin.ownerType().equals(entityType);
		final Map<Integer, List<Integer>> objects = queryReferenceOrder(
			twin.objectCatalog(), entityType, scope,
			entityPrimaryKeyInSet(ownersCloned ? MaterializedPartitionTwin.withClones(selection) : selection),
			referenceName, ordering
		);
		final Map<Integer, List<Integer>> expected;
		if (ownersCloned) {
			expected = twin.expectedAnswerPerOwner(objects, VirtualChainParityTest::sortedCopy);
		} else {
			// the references name the cloned collection - every clone follows its original
			expected = new LinkedHashMap<>(objects.size() * 2);
			objects.forEach((owner, references) -> expected.put(owner, twin.expectedAnswer(twin.ownerType(), references)));
		}
		final Map<Integer, List<Integer>> actual = queryReferenceOrder(
			twin.rowCatalog(), entityType, scope, entityPrimaryKeyInSet(selection), referenceName, ordering
		);
		if (!new HashMap<>(expected).equals(new HashMap<>(actual))) {
			differences.add(
				entityType + "." + referenceName + " through the cloned " + twin.ownerType() + " " + label + ": " +
					expected + " vs " + actual
			);
		}
	}

	/**
	 * Returns the references of one entity in the order an object catalog gives, mapped back to the originals.
	 *
	 * @param twin          the twin whose object catalog is read
	 * @param entityType    the queried collection
	 * @param owner         the entity
	 * @param referenceName the reference whose references are ordered
	 * @param attribute     the chain attribute ordering them
	 * @param direction     the direction of the ordering
	 * @return the referenced entities in order
	 */
	@Nonnull
	private List<Integer> referencesOf(
		@Nonnull MaterializedPartitionTwin twin,
		@Nonnull String entityType,
		int owner,
		@Nonnull String referenceName,
		@Nonnull String attribute,
		@Nonnull OrderDirection direction
	) {
		final boolean ownersCloned = twin.ownerType().equals(entityType);
		final Map<Integer, List<Integer>> objects = queryReferenceOrder(
			twin.objectCatalog(), entityType, Scope.LIVE,
			entityPrimaryKeyInSet(ownersCloned ? MaterializedPartitionTwin.withClones(owner) : new Integer[]{owner}),
			referenceName, attributeNatural(attribute, direction)
		);
		return ownersCloned ?
			twin.expectedAnswerPerOwner(objects, VirtualChainParityTest::sortedCopy).get(owner) :
			twin.expectedAnswer(twin.ownerType(), objects.get(owner));
	}

	/**
	 * Returns the owners of a partition in the order the chain attribute of their rows gives, read from the bodies of
	 * the row catalog: the owners chained by the predecessors their rows name, from the head on.
	 *
	 * @param entityType    the owner collection
	 * @param referenceName the reference
	 * @param target        the referenced entity of the partition
	 * @param attribute     the chain attribute naming another owner
	 * @return the owners in chain order
	 */
	@Nonnull
	private List<Integer> chainOfOwners(
		@Nonnull String entityType,
		@Nonnull String referenceName,
		int target,
		@Nonnull String attribute
	) {
		final Map<Integer, Integer> predecessors = new HashMap<>(32);
		final FilterConstraint holders = referenceHaving(referenceName, entityPrimaryKeyInSet(target));
		for (final SealedEntity owner : readBodies(entityType, holders, referenceName)) {
			for (final ReferenceContract reference : owner.getReferences(referenceName)) {
				if (reference.getReferencedPrimaryKey() == target) {
					predecessors.put(owner.getPrimaryKeyOrThrowException(), predecessorOf(reference, attribute));
				}
			}
		}
		return strictChain(predecessors, entityType + " of " + referenceName + " " + target);
	}

	/**
	 * Returns the references of one entity in the order the chain attribute of its references gives, read from its
	 * body in the row catalog: the referenced entities chained by the predecessors the references name.
	 *
	 * @param entityType    the owner collection
	 * @param referenceName the reference
	 * @param owner         the entity
	 * @param attribute     the chain attribute naming another referenced entity
	 * @return the referenced entities in chain order
	 */
	@Nonnull
	private List<Integer> chainOfReferences(
		@Nonnull String entityType,
		@Nonnull String referenceName,
		int owner,
		@Nonnull String attribute
	) {
		final Map<Integer, Integer> predecessors = new HashMap<>(256);
		for (final SealedEntity body : readBodies(entityType, entityPrimaryKeyInSet(owner), referenceName)) {
			for (final ReferenceContract reference : body.getReferences(referenceName)) {
				predecessors.put(reference.getReferencedPrimaryKey(), predecessorOf(reference, attribute));
			}
		}
		return strictChain(predecessors, entityType + " " + owner + " ." + referenceName);
	}

	/**
	 * Returns the predecessor a chain attribute of a row names.
	 *
	 * @param reference the row
	 * @param attribute the chain attribute
	 * @return the primary key of the predecessor, {@link ChainableType#HEAD_PK} for a head
	 */
	private static int predecessorOf(@Nonnull ReferenceContract reference, @Nonnull String attribute) {
		final ChainableType chainable = reference.getAttribute(attribute);
		if (chainable == null) {
			return fail("The row " + reference + " carries no `" + attribute + "`");
		}
		return chainable.predecessorPk();
	}

	/**
	 * Orders elements by the predecessors they name, from the only head on. Fails unless the elements form one strict
	 * chain - one head, every element named as a predecessor at most once, every element reached - on which the order
	 * is defined by the data alone, whatever history the index went through.
	 *
	 * @param predecessors the predecessor of every element, {@link ChainableType#HEAD_PK} for the head
	 * @param label        names the chain in a failure message
	 * @return the elements in chain order
	 */
	@Nonnull
	private static List<Integer> strictChain(@Nonnull Map<Integer, Integer> predecessors, @Nonnull String label) {
		Integer head = null;
		final Map<Integer, Integer> successors = CollectionUtils.createHashMap(predecessors.size());
		for (final Map.Entry<Integer, Integer> entry : predecessors.entrySet()) {
			if (entry.getValue() == ChainableType.HEAD_PK) {
				if (head != null) {
					fail("The chain of " + label + " has two heads: " + head + " and " + entry.getKey());
				}
				head = entry.getKey();
			} else if (successors.put(entry.getValue(), entry.getKey()) != null) {
				fail("The chain of " + label + " forks after " + entry.getValue());
			}
		}
		if (head == null) {
			return fail("The chain of " + label + " has no head: " + predecessors);
		}
		final List<Integer> result = new ArrayList<>(predecessors.size());
		for (Integer element = head; element != null; element = successors.get(element)) {
			result.add(element);
		}
		assertEquals(predecessors.size(), result.size(), "The chain of " + label + " must reach every element");
		return result;
	}

	/**
	 * Returns a chain order in a direction.
	 *
	 * @param chain     the chain in ascending order
	 * @param direction the direction
	 * @return the chain in the direction
	 */
	@Nonnull
	private static List<Integer> directed(@Nonnull List<Integer> chain, @Nonnull OrderDirection direction) {
		if (direction == OrderDirection.ASC) {
			return chain;
		}
		final List<Integer> reversed = new ArrayList<>(chain);
		Collections.reverse(reversed);
		return reversed;
	}

	/**
	 * Returns the elements of a list sorted - the form the references of an original and of its clone are compared
	 * in, since two heads of equal chains may follow in another order in each.
	 *
	 * @param elements the primary keys
	 * @return the primary keys, ascending
	 */
	@Nonnull
	private static List<Integer> sortedCopy(@Nonnull List<Integer> elements) {
		return elements.stream().sorted().toList();
	}

	/**
	 * Returns the elements of a list that are no clones, in their order.
	 *
	 * @param elements the primary keys
	 * @return the primary keys of the originals
	 */
	@Nonnull
	private static List<Integer> originalsOnly(@Nonnull List<Integer> elements) {
		return elements.stream().filter(it -> it < MaterializedPartitionTwin.OWNER_OFFSET).toList();
	}

	/**
	 * Reads the bodies of the selected entities of the row catalog of the parameter values with their references.
	 *
	 * @param entityType    the collection
	 * @param selection     the selected entities
	 * @param referenceName the reference whose rows are fetched
	 * @return the bodies
	 */
	@Nonnull
	private List<SealedEntity> readBodies(
		@Nonnull String entityType,
		@Nonnull FilterConstraint selection,
		@Nonnull String referenceName
	) {
		return this.evita.queryCatalog(
			CATALOG_PARAMETERS,
			(Function<EvitaSessionContract, List<SealedEntity>>) session -> session.queryList(
				query(
					collection(entityType),
					filterBy(selection),
					require(page(1, PAGE_SIZE), entityFetch(referenceContentWithAttributes(referenceName)))
				),
				SealedEntity.class
			)
		);
	}

	/**
	 * Fills the duplicate-heavy catalog.
	 *
	 * @param session the session of the catalog in warm-up
	 */
	private static void setUpDuplicates(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_TARGET).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(ENTITY_OWNER)
			.withoutGeneratedPrimaryKey()
			.withReferenceToEntity(
				REFERENCE_ITEMS, ENTITY_TARGET, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE)
					.withAttribute(
						ATTRIBUTE_KIND, String.class, thatIs -> thatIs.filterableInScope(Scope.LIVE).representative()
					)
					.withAttribute(ATTRIBUTE_ORDER, Predecessor.class, thatIs -> thatIs.sortableInScope(Scope.LIVE))
			)
			.updateVia(session);
		session.defineEntitySchema(ENTITY_TARGET)
			.withReflectedReferenceToEntity(
				REFERENCE_OWNERS, ENTITY_OWNER, REFERENCE_ITEMS,
				whichIs -> whichIs
					.withCardinality(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES)
					.withAttributesInherited()
					.indexedForFilteringInScope(Scope.LIVE)
			)
			.updateVia(session);
		session.createNewEntity(ENTITY_TARGET, TARGET).upsertVia(session);
		for (int owner = 1; owner <= OWNER_COUNT; owner++) {
			final int theOwner = owner;
			session.createNewEntity(ENTITY_OWNER, 1_000 + owner)
				.setOrUpdateReference(
					REFERENCE_ITEMS, TARGET, Functions.alwaysFalse(),
					whichIs -> whichIs.setAttribute(ATTRIBUTE_KIND, "a" + theOwner)
						.setAttribute(ATTRIBUTE_ORDER, Predecessor.HEAD)
				)
				.setOrUpdateReference(
					REFERENCE_ITEMS, TARGET, Functions.alwaysFalse(),
					whichIs -> whichIs.setAttribute(ATTRIBUTE_KIND, "b" + theOwner)
						.setAttribute(ATTRIBUTE_ORDER, Predecessor.HEAD)
				)
				.upsertVia(session);
		}
	}

	/**
	 * Queries a collection of a catalog in the scope on the index route and returns the primary keys in order.
	 *
	 * @param catalog    the catalog
	 * @param entityType the collection
	 * @param scope      the scope
	 * @param selection  the selection
	 * @param ordering   the ordering
	 * @return the primary keys in the order the query returns them
	 */
	@Nonnull
	private List<Integer> queryPrimaryKeys(
		@Nonnull String catalog,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nonnull FilterConstraint selection,
		@Nonnull OrderConstraint ordering
	) {
		return this.evita.queryCatalog(
			catalog,
			(Function<EvitaSessionContract, List<Integer>>) session -> session.query(
					query(
						collection(entityType),
						filterBy(scope(scope), selection),
						orderBy(ordering),
						require(page(1, PAGE_SIZE), debug(DebugMode.PREFER_INDEX_SCAN))
					),
					EntityReference.class
				)
				.getRecordData()
				.stream()
				.map(EntityReference::getPrimaryKey)
				.toList()
		);
	}

	/**
	 * Queries entities of a collection in the scope and returns the referenced primary keys of each in the order the
	 * `referenceContent(orderBy)` sorted them.
	 *
	 * @param catalog       the catalog
	 * @param entityType    the collection
	 * @param scope         the scope
	 * @param selection     the selection, `null` for every entity of the scope
	 * @param referenceName the reference whose references are ordered
	 * @param ordering      the ordering of the references
	 * @return the referenced primary keys of every entity, by its primary key
	 */
	@Nonnull
	private Map<Integer, List<Integer>> queryReferenceOrder(
		@Nonnull String catalog,
		@Nonnull String entityType,
		@Nonnull Scope scope,
		@Nullable FilterConstraint selection,
		@Nonnull String referenceName,
		@Nonnull OrderConstraint ordering
	) {
		return this.evita.queryCatalog(
			catalog,
			session -> {
				final Map<Integer, List<Integer>> result = CollectionUtils.createLinkedHashMap(64);
				for (SealedEntity entity : session.queryList(
					query(
						collection(entityType),
						filterBy(scope(scope), selection),
						require(
							page(1, PAGE_SIZE),
							entityFetch(referenceContentWithAttributes(referenceName, orderBy(ordering)))
						)
					),
					SealedEntity.class
				)) {
					result.put(
						entity.getPrimaryKeyOrThrowException(),
						entity.getReferences(referenceName)
							.stream()
							.map(ReferenceContract::getReferencedPrimaryKey)
							.toList()
					);
				}
				return result;
			}
		);
	}

}
