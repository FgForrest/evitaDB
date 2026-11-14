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

package io.evitadb.core.cache;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionIndexEntries;
import io.evitadb.index.virtual.MaterializedPartitionTwin;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Verifies result and formula identity over virtual reference partitions across unrelated and matched writes,
 * discriminated query bodies and a materialized partition twin.
 *
 * The catalog is built anew for every case - each case writes - and holds:
 *
 * - products `1` - {@link #PRODUCT_COUNT}, product `n` ranked `10 * n`, named in English, every odd one in German too;
 * - `relatedProducts`: owners `1` - `6` share target {@link #SHARED_ACCESSORY_TARGET} as accessories and owners `7` -
 *   `12` target {@link #SHARED_SPARE_TARGET} as spares (two index objects), and owner `k` of `1` -
 *   {@link #OWNER_COUNT} holds target `20 + k` alone - an accessory for an odd `k`, a spare for an even one;
 * - `accessories`: owner `k` holds target `40 + k` alone as an accessory - every partition single-owner;
 * - `categories` (live only, filterable `priority`): every product sits in category `1` with priority `n % 5`, products
 *   `1` - `3` in category `2` with priority `4`, and products `4` - `7` alone in categories `3` - `6` with priorities
 *   `1` - `4` - reflected by the categories as `products`, so a `referenceHaving` over the products of a category is
 *   answered from the product side by the bidirectional rewrite;
 * - products {@link #FIRST_ARCHIVED_OWNER} - {@link #OWNER_COUNT} are archived once the catalog is live.
 *
 * The cases comparing rows with index objects read the index objects from the {@link MaterializedPartitionTwin} of the
 * catalog, which clones every product - so every partition of the products' references is an index object there - and
 * mirror every write of a product to its clone.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Formula identity over virtual reference partitions")
@Tag(ENGINE)
@Tag(REFERENCE)
@TestInstance(Lifecycle.PER_CLASS)
class VirtualPartitionFormulaIdentityTest implements EvitaTestSupport {
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final String REFERENCE_RELATED = "relatedProducts";
	private static final String REFERENCE_ACCESSORIES = "accessories";
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final String REFERENCE_PRODUCTS = "products";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_RANK = "rank";
	private static final String ATTRIBUTE_NAME = "name";
	private static final String ATTRIBUTE_RELATION_TYPE = "relationType";
	private static final String ATTRIBUTE_PRIORITY = "priority";
	private static final String TYPE_ACCESSORY = "accessory";
	private static final String TYPE_SPARE = "spare";
	private static final Locale LOCALE_EN = Locale.ENGLISH;
	private static final Locale LOCALE_DE = Locale.GERMAN;
	private static final Scope[] BOTH_SCOPES = {Scope.LIVE, Scope.ARCHIVED};
	/**
	 * How many products the catalog holds.
	 */
	private static final int PRODUCT_COUNT = 60;
	/**
	 * How many products own `relatedProducts` and `accessories` rows.
	 */
	private static final int OWNER_COUNT = 20;
	/**
	 * The first of the owners archived once the catalog is live.
	 */
	private static final int FIRST_ARCHIVED_OWNER = 18;
	/**
	 * The `relatedProducts` target owners `1` - `6` share as accessories.
	 */
	private static final int SHARED_ACCESSORY_TARGET = 51;
	/**
	 * The `relatedProducts` target owners `7` - `12` share as spares.
	 */
	private static final int SHARED_SPARE_TARGET = 52;
	/**
	 * How many categories the catalog holds - the last one held by no product.
	 */
	private static final int CATEGORY_COUNT = 7;
	/**
	 * The rank bound of the owner attribute every tested ancestor carries - owners `1` - `15`.
	 */
	private static final int RANK_BOUND = 150;
	/**
	 * Every live product.
	 */
	private static final IntPredicate LIVE_PRODUCT = pk -> pk < FIRST_ARCHIVED_OWNER || pk > OWNER_COUNT;

	private TestPaths paths;
	private Evita evita;
	/**
	 * The catalog of the case.
	 */
	private String catalog;
	/**
	 * The twin of the catalog of the case, holding every partition of the products' references as an index object -
	 * built only by the cases comparing the two, `null` otherwise.
	 */
	@Nullable private MaterializedPartitionTwin objectTwin;
	/**
	 * Distinguishes the catalogs of the cases.
	 */
	private int catalogSequence;

	/**
	 * A planned formula tree and its computed primary keys.
	 */
	private record Run(@Nonnull Formula raw, @Nonnull List<Integer> answer) {
	}

	@BeforeAll
	void setUpEvita() {
		this.paths = createTestPaths("VirtualPartitionFormulaIdentityTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	@AfterAll
	void tearDownEvita() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@BeforeEach
	void setUpCase() {
		this.catalog = "virtualPartitionIdentity" + (++this.catalogSequence);
		this.objectTwin = null;
		MaterializedPartitionTwin.createRowCatalog(
			this.evita, this.catalog, VirtualPartitionFormulaIdentityTest::fillCatalog,
			VirtualPartitionFormulaIdentityTest::archiveLastOwners
		);
	}

	@AfterEach
	void tearDownCase() {
		this.evita.deleteCatalogIfExists(this.catalog);
		if (this.objectTwin != null) {
			this.evita.deleteCatalogIfExists(this.objectTwin.objectCatalog());
		}
	}

	@Test
	@DisplayName("the catalog registers the single-owner partitions the cases rely on")
	void shouldRegisterTheSingleOwnerPartitions() {
		// owner `k` holds target `20 + k` alone, owners 1 - 12 also a shared partition held by its index object
		assertEquals(
			ownersOf(IntStream.rangeClosed(1, OWNER_COUNT).filter(LIVE_PRODUCT)),
			registeredOwners(ENTITY_PRODUCT, Scope.LIVE, REFERENCE_RELATED)
		);
		assertEquals(
			ownersOf(IntStream.rangeClosed(FIRST_ARCHIVED_OWNER, OWNER_COUNT)),
			registeredOwners(ENTITY_PRODUCT, Scope.ARCHIVED, REFERENCE_RELATED)
		);
		assertEquals(
			ownersOf(IntStream.rangeClosed(1, OWNER_COUNT).filter(LIVE_PRODUCT)),
			registeredOwners(ENTITY_PRODUCT, Scope.LIVE, REFERENCE_ACCESSORIES)
		);
		// products 4 - 7 alone in categories 3 - 6
		assertEquals(ownersOf(IntStream.rangeClosed(4, 7)), registeredOwners(ENTITY_PRODUCT, Scope.LIVE, REFERENCE_CATEGORIES));
	}

	/** Replanning identical inputs yields equal keys for the complete tree, including its side computations. */
	@Test
	@DisplayName("repeated planning preserves the complete formula tree key")
	void shouldKeepCompleteTreeKeyAcrossRepeatedPlanning() {
		final Query query = relatedAccessoriesUpTo(RANK_BOUND, Scope.LIVE);
		final Run first = run(this.catalog, query);
		final Run second = run(this.catalog, query);

		assertNotSame(first.raw(), second.raw());
		assertEquals(first.raw().getKey(), second.raw().getKey());
		assertEquals(expectedRelatedAccessories(RANK_BOUND), first.answer());
		assertEquals(first.answer(), second.answer());
	}

	@Test
	@DisplayName("(i) a write in the matched bucket of the type index that changes no answer preserves the ancestor identity")
	void shouldKeepTheAncestorUnderAWriteInTheSameTypeIndexBucket() {
		final Query query = relatedAccessoriesUpTo(RANK_BOUND, Scope.LIVE);
		final AndFormula previous = computeAncestor(query, accessoryOwnersReadVirtually());

		// owner 1 already matches through two rows; a third accessory row lands in the same bucket of the type index
		final int[] bucketBefore = typeIndexBucketRecords(REFERENCE_RELATED, ATTRIBUTE_RELATION_TYPE, TYPE_ACCESSORY);
		write(session -> session.upsertEntity(
			session.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent()).orElseThrow().openForWrite()
				.setReference(REFERENCE_RELATED, 45, ref -> ref.setAttribute(ATTRIBUTE_RELATION_TYPE, TYPE_ACCESSORY))
		));
		// premise of the injected-dependency counterfactual: the write changes the bucket the query reads
		assertChanged(
			bucketBefore,
			typeIndexBucketRecords(REFERENCE_RELATED, ATTRIBUTE_RELATION_TYPE, TYPE_ACCESSORY),
			"The write must change the bucket of the type index, or keeping the ancestor proves nothing"
		);

		assertUnchanged(query, previous, accessoryOwnersReadVirtually(), expectedRelatedAccessories(RANK_BOUND));
	}

	@Test
	@DisplayName("(ii) a locale added to an owner the query does not match preserves the ancestor identity")
	void shouldKeepTheAncestorUnderALocaleChangeOfAnUnmatchedOwner() {
		final Query query = relatedAccessoriesUpTo(RANK_BOUND, Scope.LIVE);
		final AndFormula previous = computeAncestor(query, accessoryOwnersReadVirtually());

		// owner 8 holds only spares and has no German name yet
		final int[] localeBefore = globalLocaleRecords(LOCALE_DE);
		write(session -> session.upsertEntity(
			session.getEntity(ENTITY_PRODUCT, 8, entityFetchAllContent()).orElseThrow().openForWrite()
				.setAttribute(ATTRIBUTE_NAME, LOCALE_DE, "Produkt 8")
		));
		assertChanged(
			localeBefore,
			globalLocaleRecords(LOCALE_DE),
			"The write must change the global locale bitmap of the owners, or keeping the ancestor proves nothing"
		);

		assertUnchanged(query, previous, accessoryOwnersReadVirtually(), expectedRelatedAccessories(RANK_BOUND));
	}

	@Test
	@DisplayName("(iii) a partition registered next to the matched ones preserves the ancestor identity")
	void shouldKeepTheAncestorUnderAWriteToTheRegistry() {
		final Query query = relatedAccessoriesUpTo(RANK_BOUND, Scope.LIVE);
		final AndFormula previous = computeAncestor(query, accessoryOwnersReadVirtually());

		// owner 8 takes target 46 alone as a spare - a new single-owner partition, which the body does not match
		final Object registryBefore = typeIndexOf(ENTITY_PRODUCT, Scope.LIVE, REFERENCE_RELATED)
			.getVirtualReferencePartitionIndex();
		final Set<Integer> registeredBefore = registeredPartitions(ENTITY_PRODUCT, Scope.LIVE, REFERENCE_RELATED);
		write(session -> session.upsertEntity(
			session.getEntity(ENTITY_PRODUCT, 8, entityFetchAllContent()).orElseThrow().openForWrite()
				.setReference(REFERENCE_RELATED, 46, ref -> ref.setAttribute(ATTRIBUTE_RELATION_TYPE, TYPE_SPARE))
		));
		final Set<Integer> registeredAfter = registeredPartitions(ENTITY_PRODUCT, Scope.LIVE, REFERENCE_RELATED);
		assertEquals(registeredBefore.size() + 1, registeredAfter.size(), "The write must register one partition");
		assertNotSame(
			registryBefore,
			typeIndexOf(ENTITY_PRODUCT, Scope.LIVE, REFERENCE_RELATED).getVirtualReferencePartitionIndex(),
			"The write must replace the registry instance, or keeping the ancestor proves nothing"
		);

		assertUnchanged(query, previous, accessoryOwnersReadVirtually(), expectedRelatedAccessories(RANK_BOUND));
	}

	@Test
	@DisplayName("(iv) the same body under another locale, scope or reference has its own identity and answer")
	void shouldKeepDiscriminatedBodiesApart() {
		// locale: one type index, the locale leaf narrows the rows differently
		final Query german = accessoriesInLocale(LOCALE_DE);
		final Query english = accessoriesInLocale(LOCALE_EN);
		final int[] liveOwners = IntStream.rangeClosed(1, OWNER_COUNT).filter(LIVE_PRODUCT).toArray();
		final int[] germanOwners = IntStream.of(liveOwners).filter(pk -> pk % 2 == 1).toArray();
		assertDiscriminated(german, germanOwners, english, liveOwners);

		// scope: the same body over the live and the archived owners
		final int allRanks = PRODUCT_COUNT * 10;
		assertDiscriminated(
			accessoriesUpTo(allRanks, Scope.LIVE),
			liveOwners,
			accessoriesUpTo(allRanks, Scope.ARCHIVED),
			IntStream.rangeClosed(FIRST_ARCHIVED_OWNER, OWNER_COUNT).toArray()
		);

		// reference: the same body over another reference of the same owners
		assertDiscriminated(
			accessoriesUpTo(RANK_BOUND, Scope.LIVE),
			liveOwners,
			relatedAccessoriesUpTo(RANK_BOUND, Scope.LIVE),
			accessoryOwnersReadVirtually()
		);
	}

	@Test
	@DisplayName("(v) a write to a matched row changes the ancestor's key, so the fresh answer is computed")
	void shouldChangeIdentityAfterAWriteToAMatchedRow() {
		final Query query = relatedAccessoriesUpTo(RANK_BOUND, Scope.LIVE);
		final AndFormula previous = computeAncestor(query, accessoryOwnersReadVirtually());
		final FormulaKey previousKey = previous.getKey();

		// owner 9 matches only through its single-owner accessory row - which becomes a spare
		write(session -> session.upsertEntity(
			session.getEntity(ENTITY_PRODUCT, 9, entityFetchAllContent()).orElseThrow().openForWrite()
				.setReference(REFERENCE_RELATED, 29, ref -> ref.setAttribute(ATTRIBUTE_RELATION_TYPE, TYPE_SPARE))
		));

		// the answer first: a constant remembered from before the write would still hold owner 9
		final Run fresh = run(this.catalog, query);
		final List<Integer> expected = expectedRelatedAccessories(RANK_BOUND).stream().filter(pk -> pk != 9).toList();
		assertEquals(expected, fresh.answer());
		final int[] remainingVirtualOwners = IntStream.of(accessoryOwnersReadVirtually())
			.filter(pk -> pk != 9).toArray();
		final AndFormula ancestor = findAncestor(fresh.raw(), remainingVirtualOwners);
		assertNotEquals(previousKey, ancestor.getKey(), "The changed constant must change the ancestor's key");
	}

	@Test
	@DisplayName("a referenceHaving over registry rows and its materialized twin return the same owners")
	void shouldComputeABareAllVirtualReferenceHaving() {
		final Query query = query(
			collection(ENTITY_PRODUCT),
			filterBy(scope(Scope.LIVE), referenceHaving(
				REFERENCE_ACCESSORIES, attributeEquals(ATTRIBUTE_RELATION_TYPE, TYPE_ACCESSORY)
			))
		);
		final int[] liveOwners = IntStream.rangeClosed(1, OWNER_COUNT).filter(LIVE_PRODUCT).toArray();
		final MaterializedPartitionTwin twin = objectTwin();
		final Run objectReading = run(twin.objectCatalog(), query);
		assertEquals(toList(liveOwners), twin.expectedAnswer(ENTITY_PRODUCT, objectReading.answer()));
		final Run virtualReading = run(this.catalog, query);
		assertEquals(toList(liveOwners), virtualReading.answer());
		assertFalse(
			findPath(virtualReading.raw(), isConstantOver(liveOwners)).isEmpty(),
			"The owners of the rows must be one constant: " + virtualReading.raw()
		);
	}

	@Test
	@DisplayName("(vi) the bidirectional rewrite preserves answers across unrelated writes and reacts to a matching write")
	void shouldPreserveAnswersAcrossUnrelatedWritesAndReactToAMatchingWrite() {
		final MaterializedPartitionTwin twin = objectTwin();
		final Query query = categoriesWithPriorityAbove(1);
		// categories 1, 2 and 4 - 6: category 3 holds only product 4 with priority 1, category 7 holds nothing
		final List<Integer> expected = List.of(1, 2, 4, 5, 6);
		// Compare the row and index-object representations before and after the same writes.
		final Run objectBefore = run(twin.objectCatalog(), query);
		final Run virtualBefore = run(this.catalog, query);
		final FormulaKey objectTreeKey = objectBefore.raw().getKey();
		final FormulaKey virtualTreeKey = virtualBefore.raw().getKey();
		assertEquals(expected, objectBefore.answer());
		assertEquals(expected, virtualBefore.answer());

		// a write outside the product collection - the code of a category the query excludes: both readings keep
		// their answers
		writeCategory(
			CATEGORY_COUNT, category -> category.setAttribute(ATTRIBUTE_CODE, "C" + CATEGORY_COUNT + "x")
		);
		final Run objectAfterCategory = run(twin.objectCatalog(), query);
		final Run virtualAfterCategory = run(this.catalog, query);
		assertEquals(expected, objectAfterCategory.answer());
		assertEquals(expected, virtualAfterCategory.answer());
		assertEquals(objectTreeKey, objectAfterCategory.raw().getKey());
		assertEquals(virtualTreeKey, virtualAfterCategory.raw().getKey());

		// a counterpart write that changes no answer: product 30 takes category 7, which no product held, with
		// priority 1 - a new single-owner partition the body does not match, in a category the code excludes
		final int[] bucketBefore = counterpartBucketRecords();
		writeProduct(
			30,
			product -> product.setReference(
				REFERENCE_CATEGORIES, CATEGORY_COUNT, ref -> ref.setAttribute(ATTRIBUTE_PRIORITY, 1)
			)
		);
		assertChanged(
			bucketBefore,
			counterpartBucketRecords(),
			"The write must change the bucket of the counterpart type index, or keeping the answers proves nothing"
		);
		final Run objectAfter = run(twin.objectCatalog(), query);
		final Run virtualAfter = run(this.catalog, query);
		assertEquals(expected, objectAfter.answer());
		assertEquals(expected, virtualAfter.answer());
		// the key of the whole tree is free to change here: the write changed the counterpart bucket the tree reads

		// a counterpart write that changes the answer: product 5, alone in category 4, drops to priority 0
		writeProduct(
			5,
			product -> product.setReference(REFERENCE_CATEGORIES, 4, ref -> ref.setAttribute(ATTRIBUTE_PRIORITY, 0))
		);
		final Run objectChanged = run(twin.objectCatalog(), query);
		final Run virtualChanged = run(this.catalog, query);
		final List<Integer> changed = List.of(1, 2, 5, 6);
		assertEquals(changed, objectChanged.answer());
		assertEquals(changed, virtualChanged.answer());
		assertNotEquals(objectTreeKey, objectChanged.raw().getKey());
		assertNotEquals(virtualTreeKey, virtualChanged.raw().getKey());
	}

	/**
	 * Computes the query and locates the ancestor of the owners read from registry rows.
	 */
	@Nonnull
	private AndFormula computeAncestor(@Nonnull Query query, @Nonnull int[] virtualOwners) {
		return findAncestor(run(this.catalog, query).raw(), virtualOwners);
	}

	/**
	 * Verifies that an unrelated write preserves the ancestor's identity and expected answer.
	 */
	private void assertUnchanged(
		@Nonnull Query query,
		@Nonnull AndFormula previous,
		@Nonnull int[] virtualOwners,
		@Nonnull List<Integer> expected
	) {
		final Run next = run(this.catalog, query);
		final AndFormula ancestor = findAncestor(next.raw(), virtualOwners);
		assertEquals(previous.getKey(), ancestor.getKey(), "The write must not change the ancestor's key");
		assertEquals(expected, next.answer());
	}

	/**
	 * Verifies that queries with different discriminators have different identities and answers.
	 */
	private void assertDiscriminated(
		@Nonnull Query first,
		@Nonnull int[] firstOwners,
		@Nonnull Query second,
		@Nonnull int[] secondOwners
	) {
		final Run firstRun = run(this.catalog, first);
		final Run secondRun = run(this.catalog, second);
		assertNotEquals(
			findAncestor(firstRun.raw(), firstOwners).getKey(),
			findAncestor(secondRun.raw(), secondOwners).getKey(),
			"Discriminated bodies must not share a key"
		);
		assertNotEquals(firstRun.raw().getKey(), secondRun.raw().getKey(), "Discriminated trees must not share a key");
		assertNotEquals(firstRun.answer(), secondRun.answer(), "The discriminator must matter");
	}

	/**
	 * Returns the nearest {@link AndFormula} above the constant over the given owners of rows - the ancestor
	 * every case names.
	 *
	 * @param tree          the tree
	 * @param virtualOwners the owners the constant holds
	 * @return the ancestor
	 */
	@Nonnull
	private static AndFormula findAncestor(@Nonnull Formula tree, @Nonnull int[] virtualOwners) {
		return nearestAnd(tree, isConstantOver(virtualOwners));
	}

	/**
	 * Returns the nearest {@link AndFormula} above the target that conjoins it with something else - one of its inner
	 * formulas does not hold the target, as the owner attribute next to the `referenceHaving` does not.
	 *
	 * @param tree   the tree
	 * @param target identifies the target formula
	 * @return the ancestor
	 */
	@Nonnull
	private static AndFormula nearestAnd(@Nonnull Formula tree, @Nonnull Predicate<Formula> target) {
		final List<Formula> path = findPath(tree, target);
		assertFalse(path.isEmpty(), "The target formula must be in the tree: " + tree);
		for (int i = path.size() - 2; i >= 0; i--) {
			if (
				path.get(i) instanceof AndFormula andFormula &&
					Arrays.stream(andFormula.getInnerFormulas()).anyMatch(it -> findPath(it, target).isEmpty())
			) {
				return andFormula;
			}
		}
		throw new AssertionError("No AndFormula conjoins the target formula with another one: " + tree);
	}

	/**
	 * Returns a predicate matching a constant over exactly the given primary keys.
	 *
	 * @param primaryKeys the primary keys
	 * @return the predicate
	 */
	@Nonnull
	private static Predicate<Formula> isConstantOver(@Nonnull int[] primaryKeys) {
		return formula -> formula instanceof ConstantFormula constant &&
			Arrays.equals(primaryKeys, constant.getDelegate().getArray());
	}

	/**
	 * Returns the path from the root to the first formula matching the predicate, depth first.
	 *
	 * @param tree      the tree
	 * @param predicate the predicate
	 * @return the path, root first, empty when no formula matches
	 */
	@Nonnull
	private static List<Formula> findPath(@Nonnull Formula tree, @Nonnull Predicate<Formula> predicate) {
		final List<Formula> path = new ArrayList<>();
		return findPath(tree, predicate, path) ? path : List.of();
	}

	/**
	 * Extends the path by the formula and its subtree while looking for a match.
	 *
	 * @param formula   the formula visited
	 * @param predicate the predicate
	 * @param path      the path to the formula, extended in place
	 * @return TRUE when the match was found in the subtree
	 */
	private static boolean findPath(
		@Nonnull Formula formula,
		@Nonnull Predicate<Formula> predicate,
		@Nonnull List<Formula> path
	) {
		path.add(formula);
		if (predicate.test(formula)) {
			return true;
		}
		for (final Formula inner : formula.getInnerFormulas()) {
			if (findPath(inner, predicate, path)) {
				return true;
			}
		}
		path.removeLast();
		return false;
	}

	/**
	 * Plans and computes the query on the current committed state of a catalog.
	 */
	@Nonnull
	private Run run(@Nonnull String catalogName, @Nonnull Query query) {
		final String entityType = query.getCollection().getEntityType();
		return inEngineSession(
			catalogName,
			session -> {
				final QueryPlanningContext context = collectionOf(catalogName, entityType).createQueryContext(
					new EvitaRequest(query.normalizeQuery(), OffsetDateTime.now(), EntityReference.class, null),
					session
				);
				final Formula raw = QueryPlanner.planQuery(context).getFilter();
				try (final QueryExecutionContext executionContext = context.createExecutionContext()) {
					raw.initialize(executionContext);
					return new Run(raw, toList(raw.compute().getArray()));
				}
			}
		);
	}

	/**
	 * Runs read-only planning logic in an engine session over the committed catalog state.
	 */
	private <T> T inEngineSession(@Nonnull String catalogName, @Nonnull Function<EvitaSessionContract, T> logic) {
		return this.evita.updateCatalog(catalogName, logic);
	}

	/**
	 * Returns the query of products ranked up to the bound that hold an accessory row of `relatedProducts`.
	 *
	 * @param rankBound the rank bound
	 * @param scope     the scope
	 * @return the query
	 */
	@Nonnull
	private static Query relatedAccessoriesUpTo(int rankBound, @Nonnull Scope scope) {
		return productQuery(scope, REFERENCE_RELATED, rankBound, attributeEquals(ATTRIBUTE_RELATION_TYPE, TYPE_ACCESSORY));
	}

	/**
	 * Returns the query of products ranked up to the bound that hold an accessory row of `accessories`.
	 *
	 * @param rankBound the rank bound
	 * @param scope     the scope
	 * @return the query
	 */
	@Nonnull
	private static Query accessoriesUpTo(int rankBound, @Nonnull Scope scope) {
		return productQuery(scope, REFERENCE_ACCESSORIES, rankBound, attributeEquals(ATTRIBUTE_RELATION_TYPE, TYPE_ACCESSORY));
	}

	/**
	 * Returns the query of live products ranked up to {@link #RANK_BOUND} holding an `accessories` row and named in
	 * the locale.
	 *
	 * @param locale the locale
	 * @return the query
	 */
	@Nonnull
	private static Query accessoriesInLocale(@Nonnull Locale locale) {
		return productQuery(Scope.LIVE, REFERENCE_ACCESSORIES, RANK_BOUND, entityLocaleEquals(locale));
	}

	/**
	 * Returns the query of products of the scope ranked up to the bound holding a row of the reference satisfying the
	 * body - `and(attributeLessThanEquals(rank), referenceHaving(...))`, the ancestor every case names.
	 *
	 * @param scope     the scope
	 * @param reference the reference
	 * @param rankBound the rank bound
	 * @param body      the body of the `referenceHaving`
	 * @return the query
	 */
	@Nonnull
	private static Query productQuery(
		@Nonnull Scope scope,
		@Nonnull String reference,
		int rankBound,
		@Nonnull FilterConstraint body
	) {
		return query(
			collection(ENTITY_PRODUCT),
			filterBy(
				scope(scope),
				and(attributeLessThanEquals(ATTRIBUTE_RANK, rankBound), referenceHaving(reference, body))
			)
		);
	}

	/**
	 * Returns the query of categories with a code holding a product with a priority above the bound - answered by the
	 * bidirectional rewrite from the product side.
	 *
	 * @param priority the priority bound
	 * @return the query
	 */
	@Nonnull
	private static Query categoriesWithPriorityAbove(int priority) {
		return query(
			collection(ENTITY_CATEGORY),
			filterBy(
				scope(Scope.LIVE),
				and(
					attributeInSet(
						ATTRIBUTE_CODE,
						IntStream.rangeClosed(1, CATEGORY_COUNT - 1).mapToObj(it -> "C" + it).toArray(String[]::new)
					),
					referenceHaving(REFERENCE_PRODUCTS, attributeGreaterThan(ATTRIBUTE_PRIORITY, priority))
				)
			)
		);
	}

	/**
	 * Returns the live owners holding a single-owner accessory row of `relatedProducts` - the odd owners. The constant
	 * holds all of them whatever the owner attribute next to the `referenceHaving` admits: the conjunction above it
	 * narrows them.
	 *
	 * @return the owners, ascending
	 */
	@Nonnull
	private static int[] accessoryOwnersReadVirtually() {
		return IntStream.rangeClosed(1, OWNER_COUNT)
			.filter(LIVE_PRODUCT)
			.filter(pk -> pk % 2 == 1)
			.toArray();
	}

	/**
	 * Returns the live products ranked up to the bound holding an accessory row of `relatedProducts` - the owners of the
	 * shared accessory target and the odd owners.
	 *
	 * @param rankBound the rank bound
	 * @return the products, ascending
	 */
	@Nonnull
	private static List<Integer> expectedRelatedAccessories(int rankBound) {
		return IntStream.rangeClosed(1, Math.min(OWNER_COUNT, rankBound / 10))
			.filter(LIVE_PRODUCT)
			.filter(pk -> pk <= 6 || pk % 2 == 1)
			.boxed()
			.toList();
	}

	/**
	 * Asserts that a write changed the records a query reads, naming both snapshots on failure.
	 *
	 * @param before  the records read before the write
	 * @param after   the records read after the write
	 * @param message what the case loses when the records did not change
	 */
	private static void assertChanged(@Nonnull int[] before, @Nonnull int[] after, @Nonnull String message) {
		assertFalse(
			Arrays.equals(before, after),
			message + ": " + Arrays.toString(before) + " -> " + Arrays.toString(after)
		);
	}

	/**
	 * Returns a copy of the records of the bucket of a value in a filter index of the live product type index of a
	 * reference. The copy is taken now, so a later write cannot reach it.
	 *
	 * @param reference the reference
	 * @param attribute the attribute of the reference
	 * @param value     the value
	 * @return the records
	 */
	@Nonnull
	private int[] typeIndexBucketRecords(
		@Nonnull String reference, @Nonnull String attribute, @Nonnull Serializable value
	) {
		final ReferencedTypeEntityIndex typeIndex = typeIndexOf(ENTITY_PRODUCT, Scope.LIVE, reference);
		final AttributeIndexKey key = typeIndex.getFilterIndexes()
			.stream()
			.filter(it -> attribute.equals(it.attributeName()))
			.findFirst()
			.orElseThrow();
		return typeIndex.getFilterIndex(key).getRecordsEqualTo(value).getArray();
	}

	/**
	 * Returns a copy of the records of the bucket of priority `1` in the live type index of `categories` - the
	 * counterpart of `products`.
	 *
	 * @return the records
	 */
	@Nonnull
	private int[] counterpartBucketRecords() {
		return typeIndexBucketRecords(REFERENCE_CATEGORIES, ATTRIBUTE_PRIORITY, 1);
	}

	/**
	 * Returns a copy of the records of the global locale bitmap of the live products.
	 *
	 * @param locale the locale
	 * @return the records
	 */
	@Nonnull
	private int[] globalLocaleRecords(@Nonnull Locale locale) {
		final EntityIndex globalIndex = collectionOf(this.catalog, ENTITY_PRODUCT).getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
		);
		assertNotNull(globalIndex);
		return globalIndex.getRecordsWithLanguageFormula(locale).compute().getArray();
	}

	/**
	 * Returns the type index of a reference of a collection of the catalog of the case in a scope.
	 *
	 * @param entityType the collection
	 * @param scope      the scope
	 * @param reference  the reference
	 * @return the type index
	 */
	@Nonnull
	private ReferencedTypeEntityIndex typeIndexOf(@Nonnull String entityType, @Nonnull Scope scope, @Nonnull String reference) {
		final EntityIndex index = collectionOf(this.catalog, entityType).getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, reference)
		);
		return assertInstanceOf(ReferencedTypeEntityIndex.class, index);
	}

	/**
	 * Returns the partitions registered as single-owner in the type index of a reference.
	 *
	 * @param entityType the collection
	 * @param scope      the scope
	 * @param reference  the reference
	 * @return the partition primary keys
	 */
	@Nonnull
	private Set<Integer> registeredPartitions(@Nonnull String entityType, @Nonnull Scope scope, @Nonnull String reference) {
		final Set<Integer> partitions = new TreeSet<>();
		VirtualReferencePartitionIndexEntries.forEachOwnerRow(
			typeIndexOf(entityType, scope, reference).getVirtualReferencePartitionIndex(),
			(partition, row) -> partitions.add(partition)
		);
		return partitions;
	}

	/**
	 * Returns the owners of the partitions registered as single-owner in the type index of a reference.
	 *
	 * @param entityType the collection
	 * @param scope      the scope
	 * @param reference  the reference
	 * @return the owners
	 */
	@Nonnull
	private Set<Integer> registeredOwners(@Nonnull String entityType, @Nonnull Scope scope, @Nonnull String reference) {
		final Set<Integer> owners = new TreeSet<>();
		VirtualReferencePartitionIndexEntries.forEachOwnerRow(
			typeIndexOf(entityType, scope, reference).getVirtualReferencePartitionIndex(),
			(partition, row) -> owners.add((int) (row >>> 32))
		);
		return owners;
	}

	/**
	 * Collects primary keys into a sorted set.
	 *
	 * @param primaryKeys the primary keys
	 * @return the set
	 */
	@Nonnull
	private static Set<Integer> ownersOf(@Nonnull IntStream primaryKeys) {
		return primaryKeys.boxed().collect(TreeSet::new, Set::add, Set::addAll);
	}

	/**
	 * Converts primary keys to a list.
	 *
	 * @param primaryKeys the primary keys
	 * @return the list
	 */
	@Nonnull
	private static List<Integer> toList(@Nonnull int[] primaryKeys) {
		return IntStream.of(primaryKeys).boxed().toList();
	}

	/**
	 * Returns the current collection of a catalog - a write replaces it.
	 *
	 * @param catalogName the catalog
	 * @param entityType  the collection
	 * @return the collection
	 */
	@Nonnull
	private EntityCollection collectionOf(@Nonnull String catalogName, @Nonnull String entityType) {
		return (EntityCollection) this.evita.getCatalogInstance(catalogName)
			.orElseThrow()
			.getCollectionForEntity(entityType)
			.orElseThrow();
	}

	/**
	 * Returns the twin of the catalog of the case, building it on first use - every product cloned, so every partition
	 * of the products' references is an index object.
	 *
	 * @return the twin
	 */
	@Nonnull
	private MaterializedPartitionTwin objectTwin() {
		if (this.objectTwin == null) {
			this.objectTwin = MaterializedPartitionTwin.create(
				this.evita, this.catalog, VirtualPartitionFormulaIdentityTest::fillCatalog,
				VirtualPartitionFormulaIdentityTest::archiveLastOwners, ENTITY_PRODUCT,
				REFERENCE_ACCESSORIES, REFERENCE_CATEGORIES
			);
		}
		return this.objectTwin;
	}

	/**
	 * Applies a change to the live catalog of the case in its own transaction.
	 *
	 * @param change the change
	 */
	private void write(@Nonnull Consumer<EvitaSessionContract> change) {
		this.evita.updateCatalog(this.catalog, change);
	}

	/**
	 * Applies a change of a product to the catalog of the case and - once the twin exists - to the product and its
	 * clone in the object catalog, each catalog in its own transaction.
	 *
	 * @param primaryKey the product
	 * @param change     the change
	 */
	private void writeProduct(int primaryKey, @Nonnull Consumer<EntityBuilder> change) {
		write(session -> upsertChanged(session, ENTITY_PRODUCT, primaryKey, change));
		if (this.objectTwin != null) {
			this.evita.updateCatalog(
				this.objectTwin.objectCatalog(),
				session -> {
					upsertChanged(session, ENTITY_PRODUCT, primaryKey, change);
					upsertChanged(session, ENTITY_PRODUCT, MaterializedPartitionTwin.cloneOf(primaryKey), change);
				}
			);
		}
	}

	/**
	 * Applies a change of a category - never cloned - to the catalog of the case and, once the twin exists, to the
	 * object catalog, each in its own transaction.
	 *
	 * @param primaryKey the category
	 * @param change     the change
	 */
	private void writeCategory(int primaryKey, @Nonnull Consumer<EntityBuilder> change) {
		write(session -> upsertChanged(session, ENTITY_CATEGORY, primaryKey, change));
		if (this.objectTwin != null) {
			this.evita.updateCatalog(
				this.objectTwin.objectCatalog(),
				session -> {
					upsertChanged(session, ENTITY_CATEGORY, primaryKey, change);
				}
			);
		}
	}

	/**
	 * Opens an entity for write, applies a change and upserts it.
	 *
	 * @param session    the session
	 * @param entityType the collection
	 * @param primaryKey the entity
	 * @param change     the change
	 */
	private static void upsertChanged(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		int primaryKey,
		@Nonnull Consumer<EntityBuilder> change
	) {
		final EntityBuilder builder = session.getEntity(entityType, primaryKey, entityFetchAllContent())
			.orElseThrow()
			.openForWrite();
		change.accept(builder);
		session.upsertEntity(builder);
	}

	/**
	 * Archives the last owners - the change the live catalog goes through.
	 *
	 * @param session the session of the live catalog
	 */
	private static void archiveLastOwners(@Nonnull EvitaSessionContract session) {
		for (int pk = FIRST_ARCHIVED_OWNER; pk <= OWNER_COUNT; pk++) {
			session.archiveEntity(ENTITY_PRODUCT, pk);
		}
	}

	/**
	 * Defines the schemas and writes the entities described in the class documentation.
	 *
	 * @param session the warm-up session
	 */
	private static void fillCatalog(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_CATEGORY)
			.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.filterableInScope(Scope.LIVE))
			.updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withLocale(LOCALE_EN, LOCALE_DE)
			.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.filterableInScope(BOTH_SCOPES))
			.withAttribute(ATTRIBUTE_RANK, Integer.class, whichIs -> whichIs.filterableInScope(BOTH_SCOPES))
			.withAttribute(
				ATTRIBUTE_NAME, String.class, whichIs -> whichIs.localized().filterableInScope(BOTH_SCOPES).nullable()
			)
			.withReferenceToEntity(
				REFERENCE_RELATED, ENTITY_PRODUCT, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringInScope(BOTH_SCOPES)
					.withAttribute(ATTRIBUTE_RELATION_TYPE, String.class, thatIs -> thatIs.filterableInScope(BOTH_SCOPES))
			)
			.withReferenceToEntity(
				REFERENCE_ACCESSORIES, ENTITY_PRODUCT, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringInScope(BOTH_SCOPES)
					.withAttribute(ATTRIBUTE_RELATION_TYPE, String.class, thatIs -> thatIs.filterableInScope(BOTH_SCOPES))
			)
			.withReferenceToEntity(
				REFERENCE_CATEGORIES, ENTITY_CATEGORY, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.indexedForFilteringInScope(Scope.LIVE)
					.withAttribute(ATTRIBUTE_PRIORITY, Integer.class, thatIs -> thatIs.filterableInScope(Scope.LIVE))
			)
			.updateVia(session);
		session.defineEntitySchema(ENTITY_CATEGORY)
			.withReflectedReferenceToEntity(
				REFERENCE_PRODUCTS, ENTITY_PRODUCT, REFERENCE_CATEGORIES,
				whichIs -> whichIs
					.withCardinality(Cardinality.ZERO_OR_MORE)
					.withAttributesInherited()
					.indexedForFilteringInScope(Scope.LIVE)
			)
			.updateVia(session);
		for (int pk = 1; pk <= CATEGORY_COUNT; pk++) {
			session.upsertEntity(session.createNewEntity(ENTITY_CATEGORY, pk).setAttribute(ATTRIBUTE_CODE, "C" + pk));
		}
		for (int pk = 1; pk <= PRODUCT_COUNT; pk++) {
			session.upsertEntity(newProduct(session, pk));
		}
	}

	/**
	 * Creates one product with its references.
	 *
	 * @param session    the session
	 * @param primaryKey the product primary key
	 * @return the entity builder
	 */
	@Nonnull
	private static EntityBuilder newProduct(@Nonnull EvitaSessionContract session, int primaryKey) {
		final EntityBuilder product = session.createNewEntity(ENTITY_PRODUCT, primaryKey)
			.setAttribute(ATTRIBUTE_CODE, "P" + primaryKey)
			.setAttribute(ATTRIBUTE_RANK, primaryKey * 10)
			.setAttribute(ATTRIBUTE_NAME, LOCALE_EN, "product " + primaryKey)
			.setReference(REFERENCE_CATEGORIES, 1, ref -> ref.setAttribute(ATTRIBUTE_PRIORITY, primaryKey % 5));
		if (primaryKey % 2 == 1) {
			product.setAttribute(ATTRIBUTE_NAME, LOCALE_DE, "Produkt " + primaryKey);
		}
		if (primaryKey <= 3) {
			product.setReference(REFERENCE_CATEGORIES, 2, ref -> ref.setAttribute(ATTRIBUTE_PRIORITY, 4));
		} else if (primaryKey <= 7) {
			// products 4 - 7 alone in categories 3 - 6, with priorities 1 - 4
			product.setReference(
				REFERENCE_CATEGORIES, primaryKey - 1, ref -> ref.setAttribute(ATTRIBUTE_PRIORITY, primaryKey - 3)
			);
		}
		if (primaryKey <= OWNER_COUNT) {
			final String singleType = primaryKey % 2 == 1 ? TYPE_ACCESSORY : TYPE_SPARE;
			product.setReference(
				REFERENCE_RELATED, 20 + primaryKey, ref -> ref.setAttribute(ATTRIBUTE_RELATION_TYPE, singleType)
			);
			if (primaryKey <= 6) {
				product.setReference(
					REFERENCE_RELATED, SHARED_ACCESSORY_TARGET,
					ref -> ref.setAttribute(ATTRIBUTE_RELATION_TYPE, TYPE_ACCESSORY)
				);
			} else if (primaryKey <= 12) {
				product.setReference(
					REFERENCE_RELATED, SHARED_SPARE_TARGET, ref -> ref.setAttribute(ATTRIBUTE_RELATION_TYPE, TYPE_SPARE)
				);
			}
			product.setReference(
				REFERENCE_ACCESSORIES, 40 + primaryKey, ref -> ref.setAttribute(ATTRIBUTE_RELATION_TYPE, TYPE_ACCESSORY)
			);
		}
		return product;
	}

}
