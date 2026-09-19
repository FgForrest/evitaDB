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
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.core.exception.ReferenceNotIndexedException;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityHaving;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.groupHaving;
import static io.evitadb.api.query.QueryConstraints.not;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the refusal of a `groupHaving` placed on a reference that does not maintain the group index it reads.
 *
 * Declaring a referenced group type does not make the engine build group indexes -
 * {@link ReferenceIndexedComponents} decides that, per scope, and it defaults to
 * {@link ReferenceIndexedComponents#REFERENCED_ENTITY} alone. A `groupHaving` on a reference whose group type is
 * declared and managed but whose indexed components omit
 * {@link ReferenceIndexedComponents#REFERENCED_GROUP_ENTITY} therefore reads a family of indexes that was never
 * built. Every index contributes nothing, the constraint matches nothing, and `not(groupHaving(...))` matches
 * *everything* - the complement of the empty set. Neither answer is distinguishable from a genuine result, which is
 * what makes the silence dangerous rather than merely unhelpful: it produced a green-but-blind fixture in this very
 * package, see {@code ReferenceHavingRowSemanticsFunctionalTest}.
 *
 * The guard is deliberately narrow, and the nested classes below pin both halves of it:
 *
 * - {@link Refused} - the reference IS indexed in a queried scope, but not for the group component.
 * - {@link Allowed} - what the guard must leave alone: the component present in *some* queried scope, and a
 *   reference indexed in no queried scope at all, which the engine already refuses with a better message.
 *
 * There is deliberately no `entityHaving` counterpart. Such a guard could never fire: when no queried scope
 * carries {@link ReferenceIndexedComponents#REFERENCED_ENTITY} there is no reduced entity index in any of them,
 * and the query resolves to an empty result before the body is translated. That short-circuit is a defect in its
 * own right - a reference indexed for the group component alone answers even `groupHaving` with nothing - but it
 * lives elsewhere and is not patched over here.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Having constraint on a reference lacking the index component it reads")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(ENGINE)
@Tag(FILTER)
@Tag(REFERENCE)
public class HavingWithoutIndexedComponentFunctionalTest {

	/**
	 * Shared dataset name. The catalog is built once for the class and torn down after the last test.
	 */
	private static final String MISSING_INDEXED_COMPONENT = "MissingIndexedComponent";

	private static final String PRODUCT = "Product";
	private static final String BRAND = "Brand";
	private static final String BRAND_GROUP = "BrandGroup";

	/**
	 * Indexed with the default components, so {@link ReferenceIndexedComponents#REFERENCED_ENTITY} only. Its group
	 * type is declared and managed - everything a user would look at suggests `groupHaving` should work here, and
	 * the missing component is the one thing that does not show up in the schema they wrote.
	 */
	private static final String REF_GROUP_BLIND = "groupBlindBrands";

	/**
	 * Carries both components in {@link Scope#LIVE} and only
	 * {@link ReferenceIndexedComponents#REFERENCED_ENTITY} in {@link Scope#ARCHIVED}. Proves the guard looks at the
	 * queried scopes as a set rather than demanding the component in every one of them.
	 */
	private static final String REF_LIVE_ONLY_GROUP = "liveOnlyGroupBrands";

	/**
	 * Not indexed in any scope. The guard must stay silent here - what a reference with no index at all should do
	 * is a different question, and answering it from this guard would widen a validation fix into a behaviour
	 * change nobody asked for.
	 */
	private static final String REF_UNINDEXED = "unindexedBrands";

	private static final int PREMIUM_GROUP_PK = 100;
	private static final int BUDGET_GROUP_PK = 200;
	private static final int PREMIUM_BRAND_PK = 10;
	private static final int BUDGET_BRAND_PK = 20;

	/**
	 * Carries the premium group like product 1, but lives in {@link Scope#ARCHIVED}. Without it the archived scope
	 * holds no reduced index for {@link #REF_LIVE_ONLY_GROUP} at all, the query short-circuits to an empty result
	 * before the body is translated, and a test querying only that scope would pass for the wrong reason.
	 */
	private static final int ARCHIVED_PRODUCT_PK = 3;

	/**
	 * Builds the shared catalog: two brand groups, two brands (one per group) and two products, the first carrying
	 * the premium brand on every reference and the second the budget one. Two products are enough - every assertion
	 * below only needs one product to be selected and one to be rejected, so that a wrong answer cannot coincide
	 * with the right one.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = MISSING_INDEXED_COMPONENT, destroyAfterClass = true)
	void setUpMissingIndexedComponent(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(BRAND_GROUP)
					.updateVia(session);
				session.defineEntitySchema(BRAND)
					.updateVia(session);
				session.defineEntitySchema(PRODUCT)
					.withReferenceToEntity(
						REF_GROUP_BLIND, BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
							.withGroupTypeRelatedToEntity(BRAND_GROUP)
					)
					.withReferenceToEntity(
						REF_LIVE_ONLY_GROUP, BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
							.withGroupTypeRelatedToEntity(BRAND_GROUP)
							.indexedWithComponentsInScope(
								Scope.LIVE,
								ReferenceIndexedComponents.REFERENCED_ENTITY,
								ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
							)
							.indexedWithComponentsInScope(
								Scope.ARCHIVED,
								ReferenceIndexedComponents.REFERENCED_ENTITY
							)
					)
					.withReferenceToEntity(
						REF_UNINDEXED, BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.withGroupTypeRelatedToEntity(BRAND_GROUP)
					)
					.updateVia(session);

				session.upsertEntity(session.createNewEntity(BRAND_GROUP, PREMIUM_GROUP_PK));
				session.upsertEntity(session.createNewEntity(BRAND_GROUP, BUDGET_GROUP_PK));
				session.upsertEntity(session.createNewEntity(BRAND, PREMIUM_BRAND_PK));
				session.upsertEntity(session.createNewEntity(BRAND, BUDGET_BRAND_PK));

				upsertProduct(session, 1, PREMIUM_BRAND_PK, PREMIUM_GROUP_PK);
				upsertProduct(session, 2, BUDGET_BRAND_PK, BUDGET_GROUP_PK);
				upsertProduct(session, ARCHIVED_PRODUCT_PK, PREMIUM_BRAND_PK, PREMIUM_GROUP_PK);
				session.archiveEntity(PRODUCT, ARCHIVED_PRODUCT_PK);
			}
		);
	}

	/**
	 * The premises every other test in this class rests on. A fixture that quietly is not the schema you believe
	 * you wrote is precisely how a `groupHaving` guard elsewhere in this package came to pass while proving
	 * nothing, so the schema is asserted rather than assumed.
	 */
	@DisplayName("Fixture premises")
	@Nested
	class FixturePremises {

		@DisplayName("Should have built the four references with the intended indexed components")
		@UseDataSet(MISSING_INDEXED_COMPONENT)
		@Test
		void shouldHaveBuiltTheIntendedSchema(@Nonnull Evita evita) {
			final Map<String, String> actual = evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntitySchema productSchema = session.getEntitySchemaOrThrow(PRODUCT);
					final Map<String, String> description = new TreeMap<>();
					for (String referenceName : List.of(
						REF_GROUP_BLIND, REF_LIVE_ONLY_GROUP, REF_UNINDEXED
					)) {
						final ReferenceSchemaContract reference = productSchema
							.getReferenceOrThrowException(referenceName);
						description.put(referenceName, describe(reference));
					}
					return description;
				}
			);
			assertEquals(
				Map.of(
					REF_GROUP_BLIND, "LIVE=[REFERENCED_ENTITY]",
					REF_LIVE_ONLY_GROUP,
						"LIVE=[REFERENCED_ENTITY, REFERENCED_GROUP_ENTITY] ARCHIVED=[REFERENCED_ENTITY]",
					REF_UNINDEXED, ""
				),
				actual,
				"The schema the fixture actually built is not the one the tests below assume"
			);
		}

		/**
		 * Renders the reference's indexed components per scope, scopes and components both in a stable order so the
		 * expectation above can be written literally.
		 *
		 * @param reference the reference schema to describe
		 * @return one `SCOPE=[COMPONENT, ...]` group per indexed scope, space separated
		 */
		@Nonnull
		private String describe(@Nonnull ReferenceSchemaContract reference) {
			final StringBuilder result = new StringBuilder(64);
			for (Scope scope : Scope.values()) {
				final Set<ReferenceIndexedComponents> components = reference.getIndexedComponents(scope);
				if (components.isEmpty()) {
					continue;
				}
				if (!result.isEmpty()) {
					result.append(' ');
				}
				result.append(scope.name()).append('=').append(
					components.stream().map(Enum::name).sorted().toList()
				);
			}
			return result.toString();
		}

	}

	@DisplayName("Refused with a message naming the missing component")
	@Nested
	class Refused {

		@DisplayName("Should refuse groupHaving on a reference indexed without REFERENCED_GROUP_ENTITY")
		@UseDataSet(MISSING_INDEXED_COMPONENT)
		@Test
		void shouldThrowWhenGroupHavingTargetsReferenceWithoutGroupComponent(@Nonnull Evita evita) {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> queryProductPks(
					evita,
					query(
						collection(PRODUCT),
						filterBy(
							referenceHaving(
								REF_GROUP_BLIND,
								groupHaving(entityPrimaryKeyInSet(PREMIUM_GROUP_PK))
							)
						)
					)
				)
			);
			assertMessageNames(exception, REF_GROUP_BLIND, "REFERENCED_GROUP_ENTITY");
		}

		/**
		 * The decisive shape. Without the guard this query answers `1, 2` - every product in the catalog - because
		 * the inner constraint matches nothing and `not` complements the empty set. That is not a near-miss: it is
		 * the exact opposite of the intended answer, and it is indistinguishable from a working query.
		 */
		@DisplayName("Should refuse a negated groupHaving rather than answer it with the whole collection")
		@UseDataSet(MISSING_INDEXED_COMPONENT)
		@Test
		void shouldThrowWhenNegatedGroupHavingTargetsReferenceWithoutGroupComponent(@Nonnull Evita evita) {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> queryProductPks(
					evita,
					query(
						collection(PRODUCT),
						filterBy(
							referenceHaving(
								REF_GROUP_BLIND,
								not(groupHaving(entityPrimaryKeyInSet(PREMIUM_GROUP_PK)))
							)
						)
					)
				)
			);
			assertMessageNames(exception, REF_GROUP_BLIND, "REFERENCED_GROUP_ENTITY");
		}

		/**
		 * The component is present in {@link Scope#LIVE} only, so a query restricted to {@link Scope#ARCHIVED}
		 * cannot be answered and must say so - the mirror of
		 * {@link Allowed#shouldAnswerGroupHavingWhenOnlyOneQueriedScopeCarriesTheComponent()}.
		 */
		@DisplayName("Should refuse groupHaving when the only queried scope lacks the component")
		@UseDataSet(MISSING_INDEXED_COMPONENT)
		@Test
		void shouldThrowWhenTheOnlyQueriedScopeLacksTheComponent(@Nonnull Evita evita) {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> queryProductPks(
					evita,
					query(
						collection(PRODUCT),
						filterBy(
							scope(Scope.ARCHIVED),
							referenceHaving(
								REF_LIVE_ONLY_GROUP,
								groupHaving(entityPrimaryKeyInSet(PREMIUM_GROUP_PK))
							)
						)
					)
				)
			);
			assertMessageNames(exception, REF_LIVE_ONLY_GROUP, "REFERENCED_GROUP_ENTITY");
		}

	}

	@DisplayName("Left alone")
	@Nested
	class Allowed {

		@DisplayName("Should answer groupHaving on a reference indexed for the group component")
		@UseDataSet(MISSING_INDEXED_COMPONENT)
		@Test
		void shouldAnswerGroupHavingWhenTheComponentIsPresent(@Nonnull Evita evita) {
			assertEquals(
				List.of(1),
				queryProductPks(
					evita,
					query(
						collection(PRODUCT),
						filterBy(
							referenceHaving(
								REF_LIVE_ONLY_GROUP,
								groupHaving(entityPrimaryKeyInSet(PREMIUM_GROUP_PK))
							)
						)
					)
				),
				"The group component is indexed on this reference, so the constraint is answerable"
			);
		}

		@DisplayName("Should answer entityHaving on a reference indexed for the entity component")
		@UseDataSet(MISSING_INDEXED_COMPONENT)
		@Test
		void shouldAnswerEntityHavingWhenTheComponentIsPresent(@Nonnull Evita evita) {
			assertEquals(
				List.of(1),
				queryProductPks(
					evita,
					query(
						collection(PRODUCT),
						filterBy(
							referenceHaving(
								REF_GROUP_BLIND,
								entityHaving(entityPrimaryKeyInSet(PREMIUM_BRAND_PK))
							)
						)
					)
				),
				"The entity component is indexed on this reference, so the constraint is answerable"
			);
		}

		/**
		 * A schema may legitimately index groups in one scope and not in another. The scopes that cannot answer
		 * contribute nothing to the union, which is a correct partial answer rather than a misconfiguration - so
		 * one carrying scope among those queried is enough.
		 */
		@DisplayName("Should answer groupHaving when only one of the queried scopes carries the component")
		@UseDataSet(MISSING_INDEXED_COMPONENT)
		@Test
		void shouldAnswerGroupHavingWhenOnlyOneQueriedScopeCarriesTheComponent(@Nonnull Evita evita) {
			assertEquals(
				List.of(1),
				queryProductPks(
					evita,
					query(
						collection(PRODUCT),
						filterBy(
							scope(Scope.LIVE, Scope.ARCHIVED),
							referenceHaving(
								REF_LIVE_ONLY_GROUP,
								groupHaving(entityPrimaryKeyInSet(PREMIUM_GROUP_PK))
							)
						)
					)
				),
				"LIVE carries REFERENCED_GROUP_ENTITY, so the query is answerable across the queried scopes. Product " +
					ARCHIVED_PRODUCT_PK + " carries the same group but lives in ARCHIVED, which indexes no group - it " +
					"is absent from the union by the same rule that makes this query legal at all"
			);
		}

		/**
		 * A reference indexed in no queried scope is already refused, loudly and with a better message than this
		 * guard could give, by the throwing stub {@code ReferencedTypeEntityIndex#createThrowingStub} installs. The
		 * guard defers to it rather than replacing it - pinned here so that deference stays deliberate.
		 */
		@DisplayName("Should leave a reference indexed in no queried scope to ReferenceNotIndexedException")
		@UseDataSet(MISSING_INDEXED_COMPONENT)
		@Test
		void shouldDeferToReferenceNotIndexedForAReferenceThatIsNotIndexedAtAll(@Nonnull Evita evita) {
			final ReferenceNotIndexedException exception = assertThrows(
				ReferenceNotIndexedException.class,
				() -> queryProductPks(
					evita,
					query(
						collection(PRODUCT),
						filterBy(
							referenceHaving(
								REF_UNINDEXED,
								groupHaving(entityPrimaryKeyInSet(PREMIUM_GROUP_PK))
							)
						)
					)
				)
			);
			assertTrue(
				exception.getMessage().contains(REF_UNINDEXED),
				"The pre-existing refusal names the reference, was: " + exception.getMessage()
			);
		}

	}

	/**
	 * Asserts the refusal message is actionable: it must name the reference the user wrote and the component they
	 * have to add. A bare exception type would leave them exactly as stuck as the silence did.
	 *
	 * @param exception     the refusal raised by the query
	 * @param referenceName the reference the constraint targeted
	 * @param componentName the indexed component the constraint needed
	 */
	private static void assertMessageNames(
		@Nonnull EvitaInvalidUsageException exception,
		@Nonnull String referenceName,
		@Nonnull String componentName
	) {
		final String message = exception.getMessage();
		assertTrue(
			message.contains(referenceName),
			"The message must name the reference `" + referenceName + "`, was: " + message
		);
		assertTrue(
			message.contains(componentName),
			"The message must name the missing component `" + componentName + "`, was: " + message
		);
		assertTrue(
			message.contains("indexedComponentsInScopes"),
			"The message must name the schema setting that fixes it, was: " + message
		);
	}

	/**
	 * Creates one product carrying the same brand and group on every reference of the fixture, so that which
	 * reference a query names is the only thing that varies between the assertions.
	 *
	 * @param session  session to write through
	 * @param pk       primary key of the created product
	 * @param brandPk  brand referenced by every reference
	 * @param groupPk  group assigned to every reference
	 */
	private static void upsertProduct(
		@Nonnull EvitaSessionContract session,
		int pk,
		int brandPk,
		int groupPk
	) {
		session.upsertEntity(
			session.createNewEntity(PRODUCT, pk)
				.setReference(REF_GROUP_BLIND, brandPk, whichIs -> whichIs.setGroup(BRAND_GROUP, groupPk))
				.setReference(REF_LIVE_ONLY_GROUP, brandPk, whichIs -> whichIs.setGroup(BRAND_GROUP, groupPk))
				.setReference(REF_UNINDEXED, brandPk, whichIs -> whichIs.setGroup(BRAND_GROUP, groupPk))
		);
	}

	/**
	 * Runs the query and returns the matched product primary keys.
	 *
	 * @param evita      the engine instance
	 * @param queryToRun the query to execute
	 * @return primary keys of the matched products, in the order the engine returned them
	 */
	@Nonnull
	private static List<Integer> queryProductPks(@Nonnull Evita evita, @Nonnull Query queryToRun) {
		return evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReferenceContract> response = session.queryEntityReference(queryToRun);
				return response.getRecordData()
					.stream()
					.map(EntityReferenceContract::getPrimaryKey)
					.toList();
			}
		);
	}

}
