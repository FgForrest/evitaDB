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

package io.evitadb.core.query.filter.translator.reference;

import io.evitadb.api.exception.CollectionNotFoundException;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.filter.FilterByVisitor.ProcessingScope;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * Precondition matrix of {@link BidirectionalReferenceRewriter}, exercised against Mockito-mocked schemas and a mocked
 * {@link QueryPlanningContext}. No catalog, no dataset - every row here describes a schema/index shape that is either
 * impossible or prohibitively expensive to build from a legal schema plus a legal query.
 *
 * The class is built around **one baseline** ({@link RewriteFixture#baseline(Set)}) that is applicable, asserted as
 * such by `shouldBeApplicableForTheBaselineShape`. Every negative row then flips exactly one knob on that baseline and
 * asserts the rewrite declines. Without the positive control every negative row would be able to pass vacuously - a
 * typo in the wiring would look like a correct decline.
 *
 * ## Two groups that were written red
 *
 * The {@link CrossScopeAccounting} and {@link PlanMemoisation} groups were written against defects that existed when
 * this class was built, and each failed before its fix landed. Both are **green now** and are ordinary regression
 * rows: cross-scope accounting is folded onto the true union cardinality, and the rewrite plan is memoized by
 * `QueryPlanningContext#computeOncePerConstraint`. They are kept because a plan derived twice, or an owner counted
 * twice, costs performance without changing a single result — nothing else in the suite would notice a regression.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("BidirectionalReferenceRewriter — precondition matrix")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(REFERENCE)
class BidirectionalReferenceRewriterTest {
	/**
	 * Entity type the query targets - the *owner* end of the reference pair.
	 */
	private static final String OWNER_ENTITY_TYPE = "Tag";
	/**
	 * Reference the `referenceHaving` filters on. In the baseline it is the **reflected** end, so `findCounterpart`
	 * takes the branch that names its original outright.
	 */
	private static final String OWNER_REFERENCE_NAME = "taggedProducts";
	/**
	 * Entity type the owner reference points at.
	 */
	private static final String TARGET_ENTITY_TYPE = "Product";
	/**
	 * Reference declared on the target schema that the owner reference reflects - the counterpart.
	 */
	private static final String COUNTERPART_REFERENCE_NAME = "tags";
	/**
	 * Reference attribute the baseline constraint filters on.
	 */
	private static final String ATTRIBUTE_NAME = "relevance";
	/**
	 * A reference attribute the baseline never names - used by the inheritance-filter rows.
	 */
	private static final String UNRELATED_ATTRIBUTE_NAME = "note";
	/**
	 * The constraint every applicability row is evaluated against: a single reference-attribute leaf, which is the
	 * narrowest shape `splitChildren` accepts.
	 */
	private static final ReferenceHaving BASELINE_CONSTRAINT = referenceHaving(
		OWNER_REFERENCE_NAME, attributeEquals(ATTRIBUTE_NAME, 7L)
	);
	/**
	 * Number of owner keys the counterpart type index announces per scope in the baseline.
	 */
	private static final int BASELINE_CANDIDATE_OWNERS = 10;
	/**
	 * Number of reduced indexes the owner side would visit in the baseline. `10 * MINIMAL_GAIN = 40 <= 100`, so the
	 * baseline clears the cost gate with room to spare.
	 */
	private static final int BASELINE_OWNER_SIDE_BUCKETS = 100;

	/**
	 * Builds an ascending bitmap of `count` consecutive primary keys starting at `firstId`.
	 */
	@Nonnull
	private static Bitmap ascendingBitmap(int firstId, int count) {
		final int[] ids = new int[count];
		for (int i = 0; i < count; i++) {
			ids[i] = firstId + i;
		}
		return new BaseBitmap(ids);
	}

	/**
	 * Counts how many times the mocked context was asked for the **owner** collection's global index. This is the one
	 * lookup `preparePlan` performs exactly once per invocation, which makes it the cheapest observable proxy for "how
	 * many plans were prepared" - see the {@link PlanMemoisation} group.
	 *
	 * The entity type is matched explicitly because `createReferencedEntityFormula` calls the very same method with
	 * the *target* entity type.
	 */
	private static int countGlobalIndexLookups(@Nonnull QueryPlanningContext queryContext, @Nonnull String entityType) {
		final Collection<Invocation> invocations = mockingDetails(queryContext).getInvocations();
		int count = 0;
		for (final Invocation invocation : invocations) {
			if (!"getGlobalEntityIndexIfExists".equals(invocation.getMethod().getName())) {
				continue;
			}
			final Object[] arguments = invocation.getArguments();
			if (arguments.length == 2 && entityType.equals(arguments[0])) {
				count++;
			}
		}
		return count;
	}

	/**
	 * The baseline wiring - a fully applicable bidirectional pair, with every knob the precondition matrix cares about
	 * reachable as a field so a row can flip exactly one of them.
	 *
	 * Fields are deliberately mocks rather than values: a row re-stubs them with `when(...)` and then re-runs
	 * {@link #isApplicable()}.
	 */
	private static final class RewriteFixture {
		/**
		 * Scopes the rewrite is asked about. Every per-scope stub below covers exactly these.
		 */
		private final Set<Scope> scopes;
		private final QueryPlanningContext queryContext;
		private final EntitySchemaContract ownerEntitySchema;
		private final ReflectedReferenceSchemaContract ownerReference;
		private final EntitySchemaContract targetEntitySchema;
		private final ReferenceSchemaContract counterpart;
		private final AttributeSchemaContract counterpartAttribute;

		private RewriteFixture(@Nonnull Set<Scope> scopes) {
			this.scopes = scopes;
			this.queryContext = mock(QueryPlanningContext.class);
			this.ownerEntitySchema = mock(EntitySchemaContract.class);
			this.ownerReference = mock(ReflectedReferenceSchemaContract.class);
			this.targetEntitySchema = mock(EntitySchemaContract.class);
			this.counterpart = mock(ReferenceSchemaContract.class);
			this.counterpartAttribute = mock(AttributeSchemaContract.class);

			when(this.ownerEntitySchema.getName()).thenReturn(OWNER_ENTITY_TYPE);
			when(this.targetEntitySchema.getName()).thenReturn(TARGET_ENTITY_TYPE);

			when(this.ownerReference.getName()).thenReturn(OWNER_REFERENCE_NAME);
			when(this.ownerReference.getCardinality()).thenReturn(Cardinality.ZERO_OR_MORE);
			when(this.ownerReference.isReferencedEntityTypeManaged()).thenReturn(true);
			when(this.ownerReference.getReferencedEntityType()).thenReturn(TARGET_ENTITY_TYPE);
			when(this.ownerReference.isReflectedReferenceAvailable()).thenReturn(true);
			when(this.ownerReference.getReflectedReferenceName()).thenReturn(COUNTERPART_REFERENCE_NAME);
			when(this.ownerReference.getAttributeInheritanceFilter()).thenReturn(new String[0]);
			when(this.ownerReference.getAttributesInheritanceBehavior())
				.thenReturn(AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT);

			when(this.counterpart.getName()).thenReturn(COUNTERPART_REFERENCE_NAME);
			when(this.counterpart.getCardinality()).thenReturn(Cardinality.ZERO_OR_MORE);
			when(this.counterpart.getAttribute(ATTRIBUTE_NAME)).thenReturn(Optional.of(this.counterpartAttribute));

			// the rewrite derives its plan through `QueryPlanningContext#computeOncePerConstraint`, so the mock has to
			// run that method for real. Left to Mockito's default it would return NULL, every row here would exercise
			// a path that never produces a plan, and the memoization pin could not fail even if the cache were gone.
			when(this.queryContext.computeOncePerConstraint(any(), any(), any())).thenCallRealMethod();

			when(this.queryContext.getSchema(TARGET_ENTITY_TYPE)).thenReturn(this.targetEntitySchema);
			when(this.targetEntitySchema.getReference(COUNTERPART_REFERENCE_NAME))
				.thenReturn(Optional.of(this.counterpart));

			for (final Scope scope : scopes) {
				when(this.ownerReference.isIndexedInScope(scope)).thenReturn(true);
				when(this.ownerReference.getIndexedComponents(scope))
					.thenReturn(Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY));
				when(this.counterpart.isIndexedInScope(scope)).thenReturn(true);
				when(this.counterpart.getIndexedComponents(scope))
					.thenReturn(Set.of(ReferenceIndexedComponents.REFERENCED_ENTITY));
				when(this.counterpartAttribute.isFilterableInScope(scope)).thenReturn(true);
				when(this.counterpartAttribute.isUniqueInScope(scope)).thenReturn(false);

				stubCounterpartTypeIndex(scope, ascendingBitmap(1, BASELINE_CANDIDATE_OWNERS));
				stubOwnerTypeIndex(scope, ascendingBitmap(1, BASELINE_OWNER_SIDE_BUCKETS));
				stubOwnerGlobalIndex(scope, ascendingBitmap(1, BASELINE_OWNER_SIDE_BUCKETS));
			}
		}

		/**
		 * Creates the applicable baseline for the passed scopes.
		 */
		@Nonnull
		static RewriteFixture baseline(@Nonnull Set<Scope> scopes) {
			return new RewriteFixture(scopes);
		}

		/**
		 * Replaces the counterpart's type-level index in the given scope. A NULL bitmap stubs the index away entirely.
		 */
		void stubCounterpartTypeIndex(@Nonnull Scope scope, @Nullable Bitmap referencedPrimaryKeys) {
			final EntityIndexKey indexKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, scope, COUNTERPART_REFERENCE_NAME
			);
			if (referencedPrimaryKeys == null) {
				when(this.queryContext.getEntityIndex(TARGET_ENTITY_TYPE, indexKey, ReferencedTypeEntityIndex.class))
					.thenReturn(Optional.empty());
			} else {
				final ReferencedTypeEntityIndex typeIndex = mock(ReferencedTypeEntityIndex.class);
				when(typeIndex.getAllReferencedPrimaryKeys()).thenReturn(referencedPrimaryKeys);
				when(this.queryContext.getEntityIndex(TARGET_ENTITY_TYPE, indexKey, ReferencedTypeEntityIndex.class))
					.thenReturn(Optional.of(typeIndex));
			}
		}

		/**
		 * Replaces the owner reference's type-level index in the given scope - these primary keys are the reduced
		 * indexes the owner-side evaluation would have to visit. A NULL bitmap stubs the index away entirely.
		 */
		void stubOwnerTypeIndex(@Nonnull Scope scope, @Nullable Bitmap bucketPrimaryKeys) {
			final EntityIndexKey indexKey = new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY_TYPE, scope, OWNER_REFERENCE_NAME
			);
			if (bucketPrimaryKeys == null) {
				when(this.queryContext.getEntityIndex(OWNER_ENTITY_TYPE, indexKey, ReferencedTypeEntityIndex.class))
					.thenReturn(Optional.empty());
			} else {
				final ReferencedTypeEntityIndex typeIndex = mock(ReferencedTypeEntityIndex.class);
				when(typeIndex.getAllPrimaryKeys()).thenReturn(bucketPrimaryKeys);
				when(this.queryContext.getEntityIndex(OWNER_ENTITY_TYPE, indexKey, ReferencedTypeEntityIndex.class))
					.thenReturn(Optional.of(typeIndex));
			}
		}

		/**
		 * Replaces the owner collection's global index in the given scope. A NULL bitmap stubs the index away entirely.
		 */
		void stubOwnerGlobalIndex(@Nonnull Scope scope, @Nullable Bitmap ownerPrimaryKeys) {
			if (ownerPrimaryKeys == null) {
				when(this.queryContext.getGlobalEntityIndexIfExists(OWNER_ENTITY_TYPE, scope))
					.thenReturn(Optional.empty());
			} else {
				final GlobalEntityIndex globalIndex = mock(GlobalEntityIndex.class);
				when(globalIndex.getAllPrimaryKeys()).thenReturn(ownerPrimaryKeys);
				when(this.queryContext.getGlobalEntityIndexIfExists(OWNER_ENTITY_TYPE, scope))
					.thenReturn(Optional.of(globalIndex));
			}
		}

		/**
		 * Runs the applicability check against the baseline constraint and the fixture's own scopes.
		 */
		boolean isApplicable() {
			return isApplicableInScopes(this.scopes);
		}

		/**
		 * Runs the applicability check against the baseline constraint and an explicitly passed scope set.
		 */
		boolean isApplicableInScopes(@Nonnull Set<Scope> requestedScopes) {
			return BidirectionalReferenceRewriter.isApplicable(
				this.queryContext, this.ownerEntitySchema, this.ownerReference, BASELINE_CONSTRAINT, requestedScopes
			);
		}
	}

	@Nested
	@DisplayName("Baseline — the positive control every negative row is measured against")
	class Baseline {

		@Test
		@DisplayName("should be applicable for the baseline single-scope shape")
		void shouldBeApplicableForTheBaselineShape() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			assertTrue(
				fixture.isApplicable(),
				"The baseline wiring must be applicable - every negative row in this class flips exactly one knob on " +
					"it and would pass vacuously if the baseline itself declined."
			);
		}

		@Test
		@DisplayName("should be applicable for the baseline two-scope shape")
		void shouldBeApplicableForTheMultiScopeBaselineShape() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE, Scope.ARCHIVED));
			assertTrue(
				fixture.isApplicable(),
				"The two-scope baseline must be applicable - it is the control for the per-scope negative rows " +
					"(scope indexing, attribute filterability, cross-scope accounting)."
			);
		}
	}

	@Nested
	@DisplayName("Scope and indexing preconditions")
	class ScopeAndIndexingPreconditions {

		@Test
		@DisplayName("should decline when the requested scope set is empty")
		void shouldDeclineWhenScopeSetIsEmpty() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			assertFalse(
				fixture.isApplicableInScopes(EnumSet.noneOf(Scope.class)),
				"An empty scope set must decline before anything else is inspected - with no scope there is no index " +
					"family to read the candidate owners from."
			);
		}

		@Test
		@DisplayName("should decline when the owner reference is not indexed in one of the requested scopes")
		void shouldDeclineWhenOwnerEndIsNotIndexedInSomeRequestedScope() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE, Scope.ARCHIVED));
			when(fixture.ownerReference.isIndexedInScope(Scope.ARCHIVED)).thenReturn(false);
			assertFalse(
				fixture.isApplicable(),
				"The owner end has to be usable in every requested scope - otherwise the rewrite would swallow the " +
					"ReferenceNotIndexedException that names the owner reference."
			);
		}

		@Test
		@DisplayName("should decline when the counterpart reference is not indexed in one of the requested scopes")
		void shouldDeclineWhenCounterpartEndIsNotIndexedInSomeRequestedScope() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE, Scope.ARCHIVED));
			when(fixture.counterpart.isIndexedInScope(Scope.ARCHIVED)).thenReturn(false);
			assertFalse(
				fixture.isApplicable(),
				"The counterpart end has to be usable in every requested scope - the reduced index family " +
					"the rewrite walks does not exist there otherwise."
			);
		}

		@Test
		@DisplayName("should decline when the owner reference does not index the REFERENCED_ENTITY component")
		void shouldDeclineWhenIndexedComponentsLackReferencedEntity() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			when(fixture.ownerReference.getIndexedComponents(Scope.LIVE))
				.thenReturn(Set.of(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY));
			assertFalse(
				fixture.isApplicable(),
				"Without the REFERENCED_ENTITY component there is no entity-level partitioning, hence no reduced " +
					"index per owner for the rewrite to visit."
			);
		}

		@Test
		@DisplayName("should stay applicable when no owner exists in any requested scope")
		void shouldDeclineWhenNoOwnerExistsInAnyRequestedScope() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			fixture.stubOwnerGlobalIndex(Scope.LIVE, null);
			// deliberately asserting TRUE despite the row name taken from the design document:
			// `collectCandidateOwners` returns an *empty* array here, not NULL, so a plan is produced and
			// `tryRewrite` answers EmptyFormula. Reading this as a decline is what the row exists to rule out.
			assertTrue(
				fixture.isApplicable(),
				"A missing owner global index yields an empty candidate array, not a missing plan - the rewrite " +
					"stays applicable and answers with EmptyFormula instead of falling back to the owner side."
			);
		}
	}

	@Nested
	@DisplayName("Cardinality preconditions")
	class CardinalityPreconditions {

		@Test
		@DisplayName("should decline when the owner reference allows duplicate rows")
		void shouldDeclineWhenOwnerCardinalityAllowsDuplicates() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			when(fixture.ownerReference.getCardinality()).thenReturn(Cardinality.ZERO_OR_MORE_WITH_DUPLICATES);
			assertFalse(
				fixture.isApplicable(),
				"A reference allowing duplicate (owner, referenced) rows shapes its reduced index families " +
					"differently on the two ends, so the rewrite is not equivalent there."
			);
		}

		@Test
		@DisplayName("should decline when the counterpart reference allows duplicate rows")
		void shouldDeclineWhenCounterpartCardinalityAllowsDuplicates() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			when(fixture.counterpart.getCardinality()).thenReturn(Cardinality.ONE_OR_MORE_WITH_DUPLICATES);
			assertFalse(
				fixture.isApplicable(),
				"The duplicates check has to be applied to the counterpart as well - this is the direction no legal " +
					"schema can produce, because the reflected end inherits the original's cardinality."
			);
		}
	}

	@Nested
	@DisplayName("Counterpart resolution")
	class CounterpartResolution {

		/**
		 * Wires the *scanning* direction of `findCounterpart`: the owner reference is the original end, and the
		 * reflection that points back at it lives on the target schema.
		 *
		 * @param reflectionTargetEntityType entity type the reflection declares it points at - the knob the
		 *                                   "another entity type" row flips
		 */
		@Nonnull
		private Optional<ReferenceSchemaContract> findCounterpartByScanning(
			@Nonnull String reflectionTargetEntityType,
			@Nonnull ReflectedReferenceSchemaContract reflection
		) {
			final QueryPlanningContext queryContext = mock(QueryPlanningContext.class);
			final EntitySchemaContract ownerEntitySchema = mock(EntitySchemaContract.class);
			final ReferenceSchemaContract ownerReference = mock(ReferenceSchemaContract.class);
			final EntitySchemaContract targetEntitySchema = mock(EntitySchemaContract.class);

			// the owner is now Product, filtering on its plain `tags` reference towards Tag
			when(ownerEntitySchema.getName()).thenReturn(TARGET_ENTITY_TYPE);
			when(ownerReference.getName()).thenReturn(COUNTERPART_REFERENCE_NAME);
			when(ownerReference.isReferencedEntityTypeManaged()).thenReturn(true);
			when(ownerReference.getReferencedEntityType()).thenReturn(OWNER_ENTITY_TYPE);

			when(reflection.isReflectedReferenceAvailable()).thenReturn(true);
			when(reflection.getReflectedReferenceName()).thenReturn(COUNTERPART_REFERENCE_NAME);
			when(reflection.getReferencedEntityType()).thenReturn(reflectionTargetEntityType);

			when(targetEntitySchema.getReferences())
				.thenReturn(Map.<String, ReferenceSchemaContract>of(OWNER_REFERENCE_NAME, reflection));
			when(queryContext.getSchema(OWNER_ENTITY_TYPE)).thenReturn(targetEntitySchema);

			return BidirectionalReferenceRewriter.findCounterpart(queryContext, ownerEntitySchema, ownerReference);
		}

		@Test
		@DisplayName("should return the named original when the owner reference is the reflected end")
		void shouldFindOriginalFromReflectedEnd() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			final Optional<ReferenceSchemaContract> counterpart = BidirectionalReferenceRewriter.findCounterpart(
				fixture.queryContext, fixture.ownerEntitySchema, fixture.ownerReference
			);
			assertSame(
				fixture.counterpart, counterpart.orElse(null),
				"A reflected owner reference names its original outright - no scan of the target schema is needed."
			);
		}

		@Test
		@DisplayName("should find the reflection by scanning when the owner reference is the original end")
		void shouldFindReflectionByScanningTargetSchema() {
			final ReflectedReferenceSchemaContract reflection = mock(ReflectedReferenceSchemaContract.class);
			final Optional<ReferenceSchemaContract> counterpart = findCounterpartByScanning(
				TARGET_ENTITY_TYPE, reflection
			);
			assertSame(
				reflection, counterpart.orElse(null),
				"An original owner reference has to be paired by scanning the target schema for a reflection that " +
					"names it and points back at the owner collection."
			);
		}

		@Test
		@DisplayName("should not match a same-named reflection that points at another entity type")
		void shouldNotMatchAReflectionPointingAtAnotherEntityType() {
			final ReflectedReferenceSchemaContract reflection = mock(ReflectedReferenceSchemaContract.class);
			final Optional<ReferenceSchemaContract> counterpart = findCounterpartByScanning("Brand", reflection);
			assertTrue(
				counterpart.isEmpty(),
				"The reflection has to point back at the *owner* collection - accepting a same-named reflection " +
					"on an unrelated collection would answer the query from a different index family."
			);
		}

		@Test
		@DisplayName("should decline when the reflected reference is not available")
		void shouldDeclineWhenReflectionIsNotAvailable() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			when(fixture.ownerReference.isReflectedReferenceAvailable()).thenReturn(false);
			assertFalse(
				fixture.isApplicable(),
				"An unresolved reflection carries no usable original, so there is no counterpart to rewrite to."
			);
		}

		@Test
		@DisplayName("should decline when the referenced entity type is not managed by evitaDB")
		void shouldDeclineWhenReferencedEntityTypeIsNotManaged() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			when(fixture.ownerReference.isReferencedEntityTypeManaged()).thenReturn(false);
			assertFalse(
				fixture.isApplicable(),
				"An unmanaged referenced entity type has no collection, hence no counterpart reference at all."
			);
		}

		@Test
		@DisplayName("should decline when the referenced collection does not exist")
		void shouldDeclineWhenReferencedCollectionDoesNotExist() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			when(fixture.queryContext.getSchema(TARGET_ENTITY_TYPE))
				.thenThrow(new CollectionNotFoundException(TARGET_ENTITY_TYPE));
			assertFalse(
				fixture.isApplicable(),
				"The `catch (RuntimeException)` in findCounterpart has to swallow a missing collection and decline - " +
					"letting it escape would turn a planning optimisation into a query failure."
			);
		}
	}

	@Nested
	@DisplayName("Attribute mirroring")
	class AttributeMirroring {

		@Test
		@DisplayName("should decline when the attribute is excluded by an INHERIT_ALL_EXCEPT filter")
		void shouldDeclineWhenAttributeIsExcludedByInheritAllExcept() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			when(fixture.ownerReference.getAttributeInheritanceFilter())
				.thenReturn(new String[]{ATTRIBUTE_NAME});
			assertFalse(
				fixture.isApplicable(),
				"An excluded attribute may still exist on the counterpart under the same name while holding entirely " +
					"unrelated values - only inheritance guarantees the two ends agree."
			);
		}

		@Test
		@DisplayName("should stay applicable when the attribute is not listed in an INHERIT_ALL_EXCEPT filter")
		void shouldBeApplicableWhenAttributeIsNotExcludedByInheritAllExcept() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			when(fixture.ownerReference.getAttributeInheritanceFilter())
				.thenReturn(new String[]{UNRELATED_ATTRIBUTE_NAME});
			assertTrue(
				fixture.isApplicable(),
				"Under INHERIT_ALL_EXCEPT an attribute missing from the filter *is* inherited - this is the positive " +
					"half of the pair that catches a flipped switch branch."
			);
		}

		@Test
		@DisplayName("should decline when the attribute is not listed in an INHERIT_ONLY_SPECIFIED filter")
		void shouldDeclineWhenAttributeIsExcludedByInheritOnlySpecified() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			when(fixture.ownerReference.getAttributesInheritanceBehavior())
				.thenReturn(AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED);
			when(fixture.ownerReference.getAttributeInheritanceFilter())
				.thenReturn(new String[]{UNRELATED_ATTRIBUTE_NAME});
			assertFalse(
				fixture.isApplicable(),
				"Under INHERIT_ONLY_SPECIFIED an attribute missing from the filter is *not* inherited."
			);
		}

		@Test
		@DisplayName("should stay applicable when the attribute is listed in an INHERIT_ONLY_SPECIFIED filter")
		void shouldBeApplicableWhenAttributeIsListedByInheritOnlySpecified() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			when(fixture.ownerReference.getAttributesInheritanceBehavior())
				.thenReturn(AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED);
			when(fixture.ownerReference.getAttributeInheritanceFilter())
				.thenReturn(new String[]{ATTRIBUTE_NAME});
			assertTrue(
				fixture.isApplicable(),
				"Under INHERIT_ONLY_SPECIFIED a listed attribute is inherited - the positive half of the pair."
			);
		}

		@Test
		@DisplayName("should decline when the attribute is absent on the counterpart")
		void shouldDeclineWhenAttributeIsAbsentOnTheCounterpart() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			when(fixture.counterpart.getAttribute(ATTRIBUTE_NAME)).thenReturn(Optional.empty());
			assertFalse(
				fixture.isApplicable(),
				"Declared inheritance is not enough - the attribute has to be present on the counterpart schema for " +
					"the counterpart-side translators to find it."
			);
		}

		@Test
		@DisplayName("should decline when the attribute is neither filterable nor unique in some scope")
		void shouldDeclineWhenAttributeIsNeitherFilterableNorUniqueInSomeScope() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE, Scope.ARCHIVED));
			when(fixture.counterpartAttribute.isFilterableInScope(Scope.ARCHIVED)).thenReturn(false);
			when(fixture.counterpartAttribute.isUniqueInScope(Scope.ARCHIVED)).thenReturn(false);
			assertFalse(
				fixture.isApplicable(),
				"Without a filter or unique index on the counterpart attribute in every requested scope the " +
					"per-owner formulas cannot be built at all."
			);
		}

		@Test
		@DisplayName("should stay applicable when the attribute is only unique, never filterable")
		void shouldBeApplicableWhenAttributeIsOnlyUniqueInScope() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			when(fixture.counterpartAttribute.isFilterableInScope(Scope.LIVE)).thenReturn(false);
			when(fixture.counterpartAttribute.isUniqueInScope(Scope.LIVE)).thenReturn(true);
			assertTrue(
				fixture.isApplicable(),
				"A unique index answers an equality constraint just as well as a filter index - the precondition " +
					"is a disjunction, and asserting only the decline would not notice it becoming a conjunction."
			);
		}
	}

	@Nested
	@DisplayName("Cost gate")
	class CostGate {

		@Test
		@DisplayName("should decline when the candidate owner count exceeds the absolute ceiling")
		void shouldDeclineWhenCandidateOwnersExceedTheCeiling() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			// 10 001 candidates with 40 004 owner-side buckets would clear the ratio comfortably - only the absolute
			// ceiling can decline this shape, which is precisely what the row pins
			fixture.stubCounterpartTypeIndex(Scope.LIVE, ascendingBitmap(1, 10_001));
			fixture.stubOwnerTypeIndex(Scope.LIVE, ascendingBitmap(1, 40_004));
			assertFalse(
				fixture.isApplicable(),
				"MAX_CANDIDATE_OWNERS is an absolute ceiling on the number of per-owner formulas built at planning " +
					"time - it must decline even when the gain ratio is satisfied."
			);
		}

		@Test
		@DisplayName("should decline when the gain margin is not met by a single bucket")
		void shouldDeclineWhenGainMarginIsNotMet() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			fixture.stubCounterpartTypeIndex(Scope.LIVE, ascendingBitmap(1, 10));
			fixture.stubOwnerTypeIndex(Scope.LIVE, ascendingBitmap(1, 39));
			assertFalse(
				fixture.isApplicable(),
				"10 candidates * MINIMAL_GAIN = 40 owner-side buckets required, 39 available - the rewrite must " +
					"decline one bucket short of the boundary."
			);
		}

		@Test
		@DisplayName("should accept at exactly the gain boundary")
		void shouldAcceptAtExactlyTheGainBoundary() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			fixture.stubCounterpartTypeIndex(Scope.LIVE, ascendingBitmap(1, 10));
			fixture.stubOwnerTypeIndex(Scope.LIVE, ascendingBitmap(1, 40));
			assertTrue(
				fixture.isApplicable(),
				"10 candidates * MINIMAL_GAIN = 40 == 40 owner-side buckets - the comparison is inclusive, and this " +
					"row plus the previous one pin both MINIMAL_GAIN and the direction of the inequality."
			);
		}

		@Test
		@DisplayName("should decline when the counterpart type index is missing in a requested scope")
		void shouldDeclineWhenCounterpartTypeIndexIsMissing() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			fixture.stubCounterpartTypeIndex(Scope.LIVE, null);
			assertFalse(
				fixture.isApplicable(),
				"The counterpart claims to be indexed in this scope but has no index - the rewrite must fall through " +
					"rather than guess at an empty candidate set."
			);
		}

		@Test
		@DisplayName("should decline when the owner-side type index is missing in every requested scope")
		void shouldDeclineWhenOwnerTypeIndexIsMissingEverywhere() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			fixture.stubOwnerTypeIndex(Scope.LIVE, null);
			assertFalse(
				fixture.isApplicable(),
				"With no owner-side bucket count there is nothing to compare the candidate count against, so the " +
					"trade cannot be shown to pay off."
			);
		}
	}

	@Nested
	@DisplayName("Cross-scope accounting")
	class CrossScopeAccounting {

		@Test
		@DisplayName("should not count the same owner twice across scopes when applying the absolute ceiling")
		void shouldNotCountTheSameOwnerTwiceAcrossScopes() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE, Scope.ARCHIVED));
			// both scopes announce the *same* 6 000 owner keys, so the real candidate union is 6 000 - comfortably
			// under MAX_CANDIDATE_OWNERS - while the per-scope sum is 12 000 and trips the ceiling
			final Bitmap sharedCandidateOwners = ascendingBitmap(1, 6_000);
			fixture.stubCounterpartTypeIndex(Scope.LIVE, sharedCandidateOwners);
			fixture.stubCounterpartTypeIndex(Scope.ARCHIVED, sharedCandidateOwners);
			// 6 000 * MINIMAL_GAIN = 24 000, so the gain ratio still holds once the union is counted correctly
			final Bitmap ownerSideBuckets = ascendingBitmap(1, 24_000);
			fixture.stubOwnerTypeIndex(Scope.LIVE, ownerSideBuckets);
			fixture.stubOwnerTypeIndex(Scope.ARCHIVED, ownerSideBuckets);
			assertTrue(
				fixture.isApplicable(),
				"The candidate owner union across LIVE and ARCHIVED is 6 000, which is below MAX_CANDIDATE_OWNERS " +
					"(10 000), so the rewrite must be taken. worthRewriting sums the per-scope cardinalities " +
					"(12 000) instead of unioning them, which is a correct upper bound for the gain ratio but " +
					"wrong for the absolute ceiling."
			);
		}

		@Test
		@DisplayName("should not double-count the same owner across scopes when applying the gain ratio")
		void shouldNotDoubleCountAcrossScopesForTheGainRatio() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE, Scope.ARCHIVED));
			// union 1 000, sum 2 000 - both below the ceiling, so only the ratio can decline this shape
			final Bitmap sharedCandidateOwners = ascendingBitmap(1, 1_000);
			fixture.stubCounterpartTypeIndex(Scope.LIVE, sharedCandidateOwners);
			fixture.stubCounterpartTypeIndex(Scope.ARCHIVED, sharedCandidateOwners);
			// exactly at the boundary for the union: 1 000 * MINIMAL_GAIN = 4 000
			final Bitmap ownerSideBuckets = ascendingBitmap(1, 4_000);
			fixture.stubOwnerTypeIndex(Scope.LIVE, ownerSideBuckets);
			fixture.stubOwnerTypeIndex(Scope.ARCHIVED, ownerSideBuckets);
			assertTrue(
				fixture.isApplicable(),
				"The candidate owner union is 1 000 and the owner side would visit 4 000 reduced indexes, so the " +
					"rewrite sits exactly on the gain boundary and must be taken. Summing the per-scope " +
					"cardinalities reports 2 000 candidates and declines a provably four times cheaper plan."
			);
		}
	}

	@Nested
	@DisplayName("Widened scopes without a counterpart index")
	class WidenedScopeWithoutCounterpartIndex {

		/**
		 * Pins that adding a {@link ReferenceIndexedComponents#REFERENCED_ENTITY} check to `counterpartScopes` would
		 * change nothing, so nobody adds one believing it closes a gap.
		 *
		 * `counterpartScopes` widens the scan beyond the requested scopes to every scope where **both** ends are
		 * `isIndexedInScope` — deliberately mirroring `ContainerizedLocalMutationExecutor#isRelationMaintained`, which
		 * tests exactly that and nothing more. It does *not* also require `REFERENCED_ENTITY` among the scope's indexed
		 * components, while `referenceUsableInScopes` does. That asymmetry is real, and this row is what makes it safe
		 * to leave: a scope is only ever *iterated* by `collectCandidateOwners`, and the first thing that loop does with
		 * a scope whose `REFERENCED_ENTITY_TYPE` index is absent — which is precisely the scope a components check would
		 * have excluded — is `continue`. Removing a scope from the set and skipping it inside the loop produce the
		 * identical candidate union, the identical `crossScope` flag and the identical gate arithmetic.
		 *
		 * The requested scopes are not at risk either way: they are added to the set unconditionally, and
		 * `referenceUsableInScopes` already rejects the whole rewrite at `BidirectionalReferenceRewriter:306` when
		 * `REFERENCED_ENTITY` is missing from any of them.
		 *
		 * **What this row does not claim.** The `continue` rests on "no index there means no rows there", and that
		 * premise is false — issue #1583 is a reflected reference on an archived owner whose rows exist with no type
		 * index at all. A reference indexing only `REFERENCED_GROUP_ENTITY` in a scope is a second route to the same
		 * false premise, since `isRelationMaintained` keeps the relation on `isIndexedInScope` alone. Both under-report
		 * through the missing index itself, not through the scope set, so neither is addressed by changing this method.
		 */
		@Test
		@DisplayName("should ignore a widened scope that indexes only the group component")
		void shouldIgnoreAWidenedScopeThatIndexesOnlyTheGroupComponent() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			// both ends are indexed in ARCHIVED, so `counterpartScopes` widens into it ...
			when(fixture.ownerReference.isIndexedInScope(Scope.ARCHIVED)).thenReturn(true);
			when(fixture.counterpart.isIndexedInScope(Scope.ARCHIVED)).thenReturn(true);
			// ... but the counterpart indexes only the group component there, so no REFERENCED_ENTITY_TYPE index exists
			when(fixture.counterpart.getIndexedComponents(Scope.ARCHIVED))
				.thenReturn(Set.of(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY));
			fixture.stubCounterpartTypeIndex(Scope.ARCHIVED, null);

			assertTrue(
				fixture.isApplicable(),
				"A widened scope whose counterpart type index is absent must be skipped, not treated as a reason to " +
					"abandon the rewrite - only a *requested* scope missing its index does that. Declining here would " +
					"disable the rewrite for every reflected counterpart, which is the case it exists for."
			);
		}
	}

	@Nested
	@DisplayName("Plan memoisation")
	class PlanMemoisation {

		@Test
		@DisplayName("should prepare the rewrite plan only once across the applicability check and the rewrite")
		void shouldPrepareThePlanOnlyOnceAcrossApplicabilityCheckAndRewrite() {
			final RewriteFixture fixture = RewriteFixture.baseline(EnumSet.of(Scope.LIVE));
			final FilterByVisitor filterByVisitor = mock(FilterByVisitor.class);
			when(filterByVisitor.getQueryContext()).thenReturn(fixture.queryContext);
			final ProcessingScope<?> processingScope = mock(ProcessingScope.class);
			when(processingScope.getScopes()).thenReturn(fixture.scopes);
			// a bare `referenceHaving` keeps `tryRewrite` inside preparePlan plus the shared narrowing formula:
			// with no global index for the target collection that formula is empty and the method returns before
			// any per-owner formula is built - so the only owner-type global lookups left are preparePlan's own
			final ReferenceHaving bareConstraint = referenceHaving(OWNER_REFERENCE_NAME);

			clearInvocations(fixture.queryContext);
			final boolean applicable = BidirectionalReferenceRewriter.isApplicable(
				fixture.queryContext, fixture.ownerEntitySchema, fixture.ownerReference, bareConstraint, fixture.scopes
			);
			assertTrue(
				applicable,
				"The memoisation row is only meaningful when the rewrite is actually taken - the bare " +
					"constraint must be applicable against the baseline."
			);
			final Optional<Formula> rewritten = BidirectionalReferenceRewriter.tryRewrite(
				bareConstraint, filterByVisitor, fixture.ownerEntitySchema, fixture.ownerReference, processingScope
			);
			assertTrue(
				rewritten.isPresent(),
				"tryRewrite must take over the constraint it just declared itself applicable for."
			);

			assertEquals(
				1, countGlobalIndexLookups(fixture.queryContext, OWNER_ENTITY_TYPE),
				"The rewrite plan must be prepared exactly once for a constraint, not once for the applicability " +
					"check and again for the rewrite itself. Index selection asks isApplicable and the reference " +
					"translator then calls tryRewrite, so today the counterpart type index is fetched, unioned, " +
					"intersected and materialised into an int[] twice per constraint."
			);
		}
	}

}
