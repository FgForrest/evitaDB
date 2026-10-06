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

package io.evitadb.test.upgrade;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement.attributeElement;
import static io.evitadb.test.upgrade.Release2026_1FixtureRecipes.NEW_DUPLICATE;
import static io.evitadb.test.upgrade.Release2026_1FixtureRecipes.defineRepresentativeReference;
import static io.evitadb.test.upgrade.Release2026_1FixtureRecipes.range;
import static io.evitadb.test.upgrade.Release2026_1FixtureRecipes.upsertProductReferencingBrandOne;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.BRAND;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.GROUP;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.PRODUCT;
import static io.evitadb.test.upgrade.ReleaseFixtureValues.*;

/**
 * Recipes of the release fixtures with reference shapes that the v2026.2.18 engine writes: reduced indexes keyed by
 * representative values, duplicate references, reference attributes in the referenced-type, group and partition
 * indexes, and reflected references. This class compiles against the 2026.2 API and the current one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class Release2026_2ReferenceFixtureRecipes {

	/**
	 * The moment two seconds after {@link ReleaseFixtureValues#BASE}, the end of the shorter shared-border range.
	 */
	private static final OffsetDateTime T2 = BASE.plusSeconds(2);
	/**
	 * The moment three seconds after {@link ReleaseFixtureValues#BASE}, the end of the longer shared-border range.
	 */
	private static final OffsetDateTime T3 = BASE.plusSeconds(3);
	/**
	 * The moment between {@link #T2} and {@link #T3}.
	 */
	private static final OffsetDateTime T2_5 = BASE.plusSeconds(2).plusNanos(500_000_000L);

	private Release2026_2ReferenceFixtureRecipes() {
		// recipes only
	}

	/**
	 * Returns the recipes of every reference fixture the v2026.2.18 engine writes.
	 *
	 * @return the recipes
	 */
	@Nonnull
	public static List<ReleaseFixtureRecipe> all() {
		return Arrays.asList(
			partitionOpenBoundTwins(),
			partitionWholeSecondEqualRepresentatives(),
			duplicateReferencesOpenBoundTwins(),
			referenceFilterLocalizedRemovedOwner(),
			referenceFilterDecimalRangeRemovedOwner(),
			unindexedDuplicateReferencesOpenBoundTwins(),
			archivedDuplicateReferencesOpenBoundTwins(),
			referenceWholeSecondEqualRangesBothScopes(),
			healthyEntityAndGroupPartitions(),
			groupPartitionTwoReferences(),
			healthyGroupUniqueWithdrawnKey(),
			reflectedReferences(),
			reflectedOfReflectedReference(),
			sharedRangeBorder()
		);
	}

	/**
	 * Two reduced indexes of `product.brand` keyed by the representative open-bound twins
	 * {@link ReleaseFixtureValues#U1} (product 1) and {@link ReleaseFixtureValues#U2} (product 2) — distinct keys in
	 * the release, one key today.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe partitionOpenBoundTwins() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"partition-open-bound-twins",
				"two partitions of a duplicate reference keyed by open-bound DateTimeRange twins",
				Release2026_1FixtureRecipes::writeRepresentativeOpenBoundTwins
			)
			.withProbe(
				"brand 1",
				query(collection(PRODUCT), filterBy(referenceHaving(BRAND, entityPrimaryKeyInSet(1))))
			)
			.withProbe(
				"rank 2",
				query(collection(PRODUCT), filterBy(referenceHaving(BRAND, attributeEquals("rank", 2))))
			);
	}

	/**
	 * One reduced index of `product.brand` holding product 1 (representative A) and product 2 (representative C),
	 * release-equal values that are distinct today.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe partitionWholeSecondEqualRepresentatives() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"partition-whole-second-equal-representatives",
				"one partition of a duplicate reference keyed by release-equal, today-distinct representatives A and C",
				session -> {
					defineRepresentativeReference(session);
					upsertProductReferencingBrandOne(session, 1, A);
					upsertProductReferencingBrandOne(session, 2, C);
				}
			)
			.withProbe(
				"brand 1",
				query(collection(PRODUCT), filterBy(referenceHaving(BRAND, entityPrimaryKeyInSet(1))))
			);
	}

	/**
	 * Product 1 holds two indexed references to brand 1 with the representative open-bound twins — two
	 * duplicates of one owner that are indistinguishable today.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe duplicateReferencesOpenBoundTwins() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"duplicate-references-open-bound-twins",
				"one owner holding two indexed duplicate references whose representatives are open-bound twins",
				session -> {
					defineRepresentativeReference(session);
					upsertProductReferencingBrandOne(session, 1, U1, U2);
				}
			)
			.withProbe(
				"rank 2",
				query(collection(PRODUCT), filterBy(referenceHaving(BRAND, attributeEquals("rank", 2))))
			);
	}

	/**
	 * A filterable localized `String` reference attribute `label`; product 1 references brand 1 with `"ab"`,
	 * product 2 with `"a<ZWSP>b"` (collator-equal, raw-distinct). Product 1's attribute is removed later, which made
	 * the release erase brand 1's partition from the type index's FILTER while product 2 still contributes.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe referenceFilterLocalizedRemovedOwner() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"reference-filter-localized-removed-owner",
				"referenced-type FILTER of a localized String reference attribute erased while an owner contributes",
				session -> {
					defineReferenceWithAttribute(
						session, "label", String.class, true, 0
					);
					session.createNewEntity(PRODUCT, 1)
						.setReference(BRAND, 1, ref -> ref.setAttribute("label", Locale.ENGLISH, "ab"))
						.upsertVia(session);
					session.createNewEntity(PRODUCT, 2)
						.setReference(BRAND, 1, ref -> ref.setAttribute("label", Locale.ENGLISH, ZWSP))
						.upsertVia(session);
				}
			)
			.thenAlive(
				session -> session.getEntity(PRODUCT, 1, entityFetchAllContent()).orElseThrow()
					.openForWrite()
					.updateReference(BRAND, 1, ref -> ref.removeAttribute("label", Locale.ENGLISH))
					.upsertVia(session)
			)
			.withProbe(
				"label equals ZWSP",
				query(
					collection(PRODUCT),
					filterBy(entityLocaleEquals(Locale.ENGLISH), referenceHaving(BRAND, attributeEquals("label", ZWSP)))
				)
			);
	}

	/**
	 * A filterable `BigDecimalNumberRange` reference attribute `size` with one indexed decimal place;
	 * products 1 and 2 reference brand 1 with `[1.2, 9]` and `[1.24, 9]` — one FILTER key after the rescale, two raw
	 * counter keys. Product 1's attribute is removed later.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe referenceFilterDecimalRangeRemovedOwner() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"reference-filter-decimal-range-removed-owner",
				"referenced-type FILTER of a BigDecimalNumberRange reference attribute " +
					"erased while an owner contributes",
				session -> {
					defineReferenceWithAttribute(session, "size", BigDecimalNumberRange.class, false, 1);
					session.createNewEntity(PRODUCT, 1)
						.setReference(BRAND, 1, ref -> ref.setAttribute("size", range("1.2", "9")))
						.upsertVia(session);
					session.createNewEntity(PRODUCT, 2)
						.setReference(BRAND, 1, ref -> ref.setAttribute("size", range("1.24", "9")))
						.upsertVia(session);
				}
			)
			.thenAlive(
				session -> session.getEntity(PRODUCT, 1, entityFetchAllContent()).orElseThrow()
					.openForWrite()
					.updateReference(BRAND, 1, ref -> ref.removeAttribute("size"))
					.upsertVia(session)
			)
			.withProbe(
				"size inRange 5",
				query(
					collection(PRODUCT),
					filterBy(referenceHaving(BRAND, attributeInRange("size", new BigDecimal("5"))))
				)
			);
	}

	/**
	 * `product.brand` allows duplicates, is indexed in no scope and has a representative `DateTimeRange`;
	 * product 1 holds two references to brand 1 with the open-bound twins.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe unindexedDuplicateReferencesOpenBoundTwins() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"unindexed-duplicate-references-open-bound-twins",
				"one owner holding two unindexed duplicate references whose representatives are open-bound twins",
				session -> {
					session.defineEntitySchema(BRAND).updateVia(session);
					session.defineEntitySchema(PRODUCT)
						.withReferenceToEntity(
							BRAND, BRAND, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
							whichIs -> whichIs
								.nonIndexed()
								.withAttribute("validity", DateTimeRange.class, thatIs -> thatIs.representative())
								.withAttribute("rank", Integer.class)
						)
						.updateVia(session);
					session.createNewEntity(BRAND, 1).upsertVia(session);
					upsertProductReferencingBrandOne(session, 1, U1, U2);
				}
			)
			.withProbe("product 1", query(collection(PRODUCT), filterBy(entityPrimaryKeyInSet(1))));
	}

	/**
	 * The shape of {@link #duplicateReferencesOpenBoundTwins()} indexed in the live scope only, with product 1 archived
	 * afterwards — its duplicates are then indexed nowhere.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe archivedDuplicateReferencesOpenBoundTwins() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"archived-duplicate-references-open-bound-twins",
				"archived owner holding two duplicate references (indexed in the live scope only) " +
					"with open-bound twins",
				session -> {
					defineRepresentativeReference(session);
					upsertProductReferencingBrandOne(session, 1, U1, U2);
					upsertProductReferencingBrandOne(session, 2, FAR);
				}
			)
			.thenAlive(session -> session.archiveEntity(PRODUCT, 1))
			.withProbe(
				"archived",
				query(collection(PRODUCT), filterBy(scope(Scope.ARCHIVED), entityPrimaryKeyInSet(1, 2)))
			);
	}

	/**
	 * A filterable `DateTimeRange` reference attribute holding A and C (folded by the
	 * release) in a reference indexed with entity and group partitions in both scopes; two owners per referenced
	 * entity, a group shared by every reference, two references per owner and an archived owner.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe referenceWholeSecondEqualRangesBothScopes() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"reference-whole-second-equal-ranges-both-scopes",
				"DateTimeRange reference attribute folding A and C in entity, group and type indexes of both scopes",
				session -> {
					session.defineEntitySchema(GROUP).updateVia(session);
					session.defineEntitySchema(BRAND).updateVia(session);
					session.defineEntitySchema(PRODUCT)
						.withReferenceToEntity(
							BRAND, BRAND, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.withGroupTypeRelatedToEntity(GROUP)
								.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED)
								.indexedWithComponentsInScope(
									Scope.LIVE,
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.indexedWithComponentsInScope(
									Scope.ARCHIVED,
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.withAttribute(
									"validity", DateTimeRange.class,
									thatIs -> thatIs.filterableInScope(Scope.LIVE, Scope.ARCHIVED)
								)
						)
						.updateVia(session);
					session.createNewEntity(GROUP, 10).upsertVia(session);
					session.createNewEntity(BRAND, 1).upsertVia(session);
					session.createNewEntity(BRAND, 2).upsertVia(session);
					session.createNewEntity(PRODUCT, 1)
						.setReference(BRAND, 1, ref -> ref.setGroup(10).setAttribute("validity", A))
						.setReference(BRAND, 2, ref -> ref.setGroup(10).setAttribute("validity", C))
						.upsertVia(session);
					session.createNewEntity(PRODUCT, 2)
						.setReference(BRAND, 1, ref -> ref.setGroup(10).setAttribute("validity", C))
						.upsertVia(session);
					session.createNewEntity(PRODUCT, 3)
						.setReference(BRAND, 2, ref -> ref.setGroup(10).setAttribute("validity", A))
						.upsertVia(session);
				}
			)
			.thenAlive(session -> session.archiveEntity(PRODUCT, 3))
			.withProbe("live inRange +2.500", referenceValidityInRange(Scope.LIVE, T2_5))
			.withProbe(
				"live inRange +1.500",
				referenceValidityInRange(Scope.LIVE, BASE.plusSeconds(1).plusNanos(500_000_000L))
			)
			.withProbe("archived inRange +2.500", referenceValidityInRange(Scope.ARCHIVED, T2_5))
			.withProbe(
				"live group 10 inRange +2.500",
				query(
					collection(PRODUCT),
					filterBy(
						referenceHaving(
							BRAND, groupHaving(entityPrimaryKeyInSet(10)), attributeInRange("validity", T2_5)
						)
					)
				)
			);
	}

	/**
	 * Healthy: a duplicate-allowing reference with entity and group partitions where referenced entity 7 and group 7
	 * carry the same ordinary representative value — an entity partition and a group partition with equal
	 * discriminators, which are different index keys.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe healthyEntityAndGroupPartitions() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"healthy-entity-and-group-partitions",
				"healthy entity and group partitions with equal primary keys and equal ordinary representative values",
				session -> {
					session.defineEntitySchema(GROUP).updateVia(session);
					session.defineEntitySchema(BRAND).updateVia(session);
					session.defineEntitySchema(PRODUCT)
						.withReferenceToEntity(
							BRAND, BRAND, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
							whichIs -> whichIs
								.withGroupTypeRelatedToEntity(GROUP)
								.indexedForFilteringAndPartitioning()
								.indexedWithComponents(
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
								.withAttribute("code", String.class, thatIs -> thatIs.filterable().representative())
						)
						.updateVia(session);
					session.createNewEntity(GROUP, 7).upsertVia(session);
					session.createNewEntity(BRAND, 7).upsertVia(session);
					session.createNewEntity(BRAND, 8).upsertVia(session);
					session.createNewEntity(PRODUCT, 1)
						.setOrUpdateReference(BRAND, 7, NEW_DUPLICATE, ref -> ref.setGroup(7).setAttribute("code", "x"))
						.upsertVia(session);
					session.createNewEntity(PRODUCT, 2)
						.setOrUpdateReference(BRAND, 8, NEW_DUPLICATE, ref -> ref.setGroup(7).setAttribute("code", "x"))
						.upsertVia(session);
				}
			)
			.withProbe(
				"brand 7",
				query(collection(PRODUCT), filterBy(referenceHaving(BRAND, entityPrimaryKeyInSet(7))))
			)
			.withProbe(
				"group 7",
				query(collection(PRODUCT), filterBy(referenceHaving(BRAND, groupHaving(entityPrimaryKeyInSet(7)))))
			)
			.withProbe(
				"code x",
				query(collection(PRODUCT), filterBy(referenceHaving(BRAND, attributeEquals("code", "x"))))
			);
	}

	/**
	 * Group partitions: product 1 has a filterable `DateTimeRange` entity attribute and two references to different
	 * brands in the same group 10, so the reduced group index counts its entity attribute once per owner; product 2 is
	 * in group 20. The test updates the attribute and removes the references after the upgrade.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe groupPartitionTwoReferences() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"group-partition-two-references",
				"one owner reaching one group partition through two references, with a DateTimeRange entity attribute",
				session -> {
					session.defineEntitySchema(GROUP).updateVia(session);
					session.defineEntitySchema(BRAND).updateVia(session);
					session.defineEntitySchema(PRODUCT)
						.withAttribute("validity", DateTimeRange.class, whichIs -> whichIs.filterable())
						.withReferenceToEntity(
							BRAND, BRAND, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.withGroupTypeRelatedToEntity(GROUP)
								.indexedForFilteringAndPartitioning()
								.indexedWithComponents(
									ReferenceIndexedComponents.REFERENCED_ENTITY,
									ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
								)
						)
						.updateVia(session);
					session.createNewEntity(GROUP, 10).upsertVia(session);
					session.createNewEntity(GROUP, 20).upsertVia(session);
					for (int brand = 1; brand <= 3; brand++) {
						session.createNewEntity(BRAND, brand).upsertVia(session);
					}
					session.createNewEntity(PRODUCT, 1)
						.setAttribute("validity", A)
						.setReference(BRAND, 1, ref -> ref.setGroup(10))
						.setReference(BRAND, 2, ref -> ref.setGroup(10))
						.upsertVia(session);
					session.createNewEntity(PRODUCT, 2)
						.setAttribute("validity", A)
						.setReference(BRAND, 3, ref -> ref.setGroup(20))
						.upsertVia(session);
				}
			)
			.withProbe("group 10 inRange +2.500", groupValidityInRange(10, T2_5))
			.withProbe("group 10 inRange +1 year", groupValidityInRange(10, BASE.plusYears(1).plusDays(1)))
			.withProbe("group 20 inRange +2.500", groupValidityInRange(20, T2_5));
	}

	/**
	 * Healthy unique reference attribute with a withdrawn release representative: the reference is indexed with group
	 * partitions only and has a non-localized unique `DateTimeRange` attribute. Group 10 receives
	 * {@link ReleaseFixtureValues#UNTIL_100_PLUS_ONE} (product 1) and {@link ReleaseFixtureValues#UNTIL_900_PLUS_ONE}
	 * (product 2), release-equal; product 1's contribution is then removed, and only afterwards group 20 receives
	 * {@link ReleaseFixtureValues#UNTIL_100_PLUS_TWO} (product 3), equal today to the withdrawn value only. No two
	 * owners ever shared a value under today's rules, so the catalog is healthy and must not be refused.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe healthyGroupUniqueWithdrawnKey() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"healthy-group-unique-withdrawn-key",
				"healthy group-only unique DateTimeRange reference attribute whose folded release key was withdrawn",
				session -> {
					session.defineEntitySchema(GROUP).updateVia(session);
					session.defineEntitySchema(BRAND).updateVia(session);
					session.defineEntitySchema(PRODUCT)
						.withReferenceToEntity(
							BRAND, BRAND, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.withGroupTypeRelatedToEntity(GROUP)
								.indexedForFilteringAndPartitioning()
								.indexedWithComponents(ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY)
								.withAttribute("validity", DateTimeRange.class, thatIs -> thatIs.unique())
						)
						.updateVia(session);
					session.createNewEntity(GROUP, 10).upsertVia(session);
					session.createNewEntity(GROUP, 20).upsertVia(session);
					for (int brand = 1; brand <= 3; brand++) {
						session.createNewEntity(BRAND, brand).upsertVia(session);
					}
					session.createNewEntity(PRODUCT, 1)
						.setReference(BRAND, 1, ref -> ref.setGroup(10).setAttribute("validity", UNTIL_100_PLUS_ONE))
						.upsertVia(session);
					session.createNewEntity(PRODUCT, 2)
						.setReference(BRAND, 2, ref -> ref.setGroup(10).setAttribute("validity", UNTIL_900_PLUS_ONE))
						.upsertVia(session);
				}
			)
			.thenAlive(
				session -> session.getEntity(PRODUCT, 1, entityFetchAllContent()).orElseThrow()
					.openForWrite().removeReference(BRAND, 1).upsertVia(session),
				session -> session.createNewEntity(PRODUCT, 3)
					.setReference(BRAND, 3, ref -> ref.setGroup(20).setAttribute("validity", UNTIL_100_PLUS_TWO))
					.upsertVia(session)
			)
			.withProbe("equals until 10:00:00.100+01:00", referenceValidityEquals(UNTIL_100_PLUS_ONE))
			.withProbe("equals until 10:00:00.900+01:00", referenceValidityEquals(UNTIL_900_PLUS_ONE))
			.withProbe("equals until 11:00:00.100+02:00", referenceValidityEquals(UNTIL_100_PLUS_TWO))
			.withProbe(
				"group 10 equals until 10:00:00.900+01:00",
				query(
					collection(PRODUCT),
					filterBy(
						referenceHaving(
							BRAND,
							groupHaving(entityPrimaryKeyInSet(10)),
							attributeEquals("validity", UNTIL_900_PLUS_ONE)
						)
					)
				)
			)
			.withProbe(
				"group 20 equals until 10:00:00.100+01:00",
				query(
					collection(PRODUCT),
					filterBy(
						referenceHaving(
							BRAND,
							groupHaving(entityPrimaryKeyInSet(20)),
							attributeEquals("validity", UNTIL_100_PLUS_ONE)
						)
					)
				)
			);
	}

	/**
	 * Reflected references: `brand` reflects three references of `product` to it, each once — `productsAll` inherits
	 * every attribute of `product.brand`, `productsExcept` all but `validity` of `product.brandExcept`, and
	 * `productsOnly` only `validity` of `product.brandOnly`. Each original carries a filterable `DateTimeRange`
	 * `validity` and a sortable compound `(localizedName, priority)`. `product.relatedBy` reflects the self-reference
	 * `product.related`, which carries a filterable `DateTimeRange` `since`. A reference can be reflected only once, so
	 * the three inheritance variants need three originals.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe reflectedReferences() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"reflected-references",
				"reflected references inheriting DateTimeRange attributes and a localized compound, " +
					"incl. self-reference",
				session -> {
					session.defineEntitySchema(BRAND)
						.withLocale(Locale.ENGLISH)
						.withReflectedReferenceToEntity(
							"productsAll", PRODUCT, BRAND, whichIs -> whichIs.withAttributesInherited()
						)
						.withReflectedReferenceToEntity(
							"productsExcept", PRODUCT, "brandExcept",
							whichIs -> whichIs.withAttributesInheritedExcept("validity")
						)
						.withReflectedReferenceToEntity(
							"productsOnly", PRODUCT, "brandOnly", whichIs -> whichIs.withAttributesInherited("validity")
						)
						.updateVia(session);
					session.defineEntitySchema(PRODUCT)
						.withLocale(Locale.ENGLISH)
						.withReferenceToEntity(
							BRAND, BRAND, Cardinality.ZERO_OR_MORE,
							Release2026_2ReferenceFixtureRecipes::defineBrandAttributes
						)
						.withReferenceToEntity(
							"brandExcept", BRAND, Cardinality.ZERO_OR_MORE,
							Release2026_2ReferenceFixtureRecipes::defineBrandAttributes
						)
						.withReferenceToEntity(
							"brandOnly", BRAND, Cardinality.ZERO_OR_MORE,
							Release2026_2ReferenceFixtureRecipes::defineBrandAttributes
						)
						.withReferenceToEntity(
							"related", PRODUCT, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedForFilteringAndPartitioning()
								.withAttribute("since", DateTimeRange.class, thatIs -> thatIs.filterable())
						)
						.withReflectedReferenceToEntity(
							"relatedBy", PRODUCT, "related", whichIs -> whichIs.withAttributesInherited()
						)
						.updateVia(session);
					session.createNewEntity(BRAND, 1).upsertVia(session);
					session.createNewEntity(BRAND, 2).upsertVia(session);
					productWithBrandReferences(session, 2, 1, C, "y", 2).upsertVia(session);
					productWithBrandReferences(session, 1, 1, A, "x", 1)
						.setReference("related", 2, ref -> ref.setAttribute("since", A))
						.upsertVia(session);
					productWithBrandReferences(session, 3, 2, FAR, "z", 3)
						.setReference("related", 2, ref -> ref.setAttribute("since", C))
						.upsertVia(session);
				}
			)
			.withProbe("brands by productsAll inRange +2.500", brandsByReflectedValidity("productsAll"))
			.withProbe("brands by productsOnly inRange +2.500", brandsByReflectedValidity("productsOnly"))
			.withProbe(
				"products by relatedBy inRange +2.500",
				query(collection(PRODUCT), filterBy(referenceHaving("relatedBy", attributeInRange("since", T2_5))))
			)
			.withProbe(
				"products ordered by brand namePriority",
				query(
					collection(PRODUCT),
					filterBy(entityLocaleEquals(Locale.ENGLISH), referenceHaving(BRAND)),
					orderBy(referenceProperty(BRAND, attributeNatural("namePriority")))
				)
			)
			.withProbe(
				"brands ordered by productsExcept namePriority",
				query(
					collection(BRAND),
					filterBy(entityLocaleEquals(Locale.ENGLISH), referenceHaving("productsExcept")),
					orderBy(referenceProperty("productsExcept", attributeNatural("namePriority")))
				)
			);
	}

	/**
	 * Configures a reference of {@link #reflectedReferences()} to `brand`: partitioned, with a filterable
	 * `DateTimeRange` `validity`, a localized sortable `localizedName`, a sortable `priority` and the compound
	 * `namePriority` of the last two.
	 *
	 * @param reference the reference schema builder
	 */
	private static void defineBrandAttributes(@Nonnull ReferenceSchemaEditor.ReferenceSchemaBuilder reference) {
		reference
			.indexedForFilteringAndPartitioning()
			.withAttribute("validity", DateTimeRange.class, thatIs -> thatIs.filterable())
			.withAttribute("localizedName", String.class, thatIs -> thatIs.localized().sortable())
			.withAttribute("priority", Integer.class, thatIs -> thatIs.sortable())
			.withSortableAttributeCompound(
				"namePriority", attributeElement("localizedName"), attributeElement("priority")
			);
	}

	/**
	 * Creates a product of {@link #reflectedReferences()} referencing one brand through all three of its brand
	 * references with the same attributes.
	 *
	 * @param session       the session
	 * @param productPk     the product primary key
	 * @param brandPk       the brand primary key
	 * @param validity      the `validity` of each reference
	 * @param localizedName the English `localizedName` of each reference
	 * @param priority      the `priority` of each reference
	 * @return the entity builder, not upserted yet
	 */
	@Nonnull
	private static EntityBuilder productWithBrandReferences(
		@Nonnull EvitaSessionContract session,
		int productPk,
		int brandPk,
		@Nonnull DateTimeRange validity,
		@Nonnull String localizedName,
		int priority
	) {
		final EntityBuilder product = session.createNewEntity(PRODUCT, productPk);
		for (String reference : new String[]{BRAND, "brandExcept", "brandOnly"}) {
			product.setReference(
				reference, brandPk,
				ref -> ref.setAttribute("validity", validity)
					.setAttribute("localizedName", Locale.ENGLISH, localizedName)
					.setAttribute("priority", priority)
			);
		}
		return product;
	}

	/**
	 * A reflected reference of a reflected reference: `product.brand` is a plain indexed reference, `brand.products`
	 * reflects it, and `product.brandProducts` reflects `brand.products`; attributes and the compound
	 * `(localizedName, priority)` are inherited along both reflections.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe reflectedOfReflectedReference() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"reflected-of-reflected-reference",
				"reflected reference reflecting another reflected reference, inheriting attributes and a compound",
				session -> {
					session.defineEntitySchema(BRAND).withLocale(Locale.ENGLISH).updateVia(session);
					session.defineEntitySchema(PRODUCT)
						.withLocale(Locale.ENGLISH)
						.withReferenceToEntity(
							BRAND, BRAND, Cardinality.ZERO_OR_MORE,
							Release2026_2ReferenceFixtureRecipes::defineBrandAttributes
						)
						.updateVia(session);
					session.defineEntitySchema(BRAND)
						.withReflectedReferenceToEntity(
							"products", PRODUCT, BRAND,
							whichIs -> whichIs.withAttributesInherited().indexedForFilteringAndPartitioning()
						)
						.updateVia(session);
					session.defineEntitySchema(PRODUCT)
						.withReflectedReferenceToEntity(
							"brandProducts", BRAND, "products", whichIs -> whichIs.withAttributesInherited()
						)
						.updateVia(session);
					session.createNewEntity(BRAND, 1).upsertVia(session);
					session.createNewEntity(PRODUCT, 1)
						.setReference(BRAND, 1, ref -> ref.setAttribute("validity", A)
							.setAttribute("localizedName", Locale.ENGLISH, "x").setAttribute("priority", 1))
						.upsertVia(session);
					session.createNewEntity(PRODUCT, 2)
						.setReference(BRAND, 1, ref -> ref.setAttribute("validity", C)
							.setAttribute("localizedName", Locale.ENGLISH, "y").setAttribute("priority", 2))
						.upsertVia(session);
				}
			)
			.withProbe("brands by products inRange +2.500", brandsByReflectedValidity("products"))
			.withProbe(
				"products by brandProducts inRange +2.500",
				query(
					collection(PRODUCT),
					filterBy(referenceHaving("brandProducts", attributeInRange("validity", T2_5)))
				)
			);
	}

	/**
	 * Shared range borders: products 1 and 2 reference brand 1 with filterable range reference
	 * attributes sharing a start — `span` `[0, 20]` and `[0, 30]`, `validity` `until(T2)` and `until(T3)`, which share
	 * the open start — so the type index's `RangeIndex` record (the partition) receives one start twice.
	 *
	 * @return the recipe
	 */
	@Nonnull
	public static ReleaseFixtureRecipe sharedRangeBorder() {
		return ReleaseFixtureRecipe.writtenBy2026_2(
				"shared-range-border",
				"two owners' range reference attributes sharing a start in one referenced-type RangeIndex record",
				session -> {
					session.defineEntitySchema(BRAND).updateVia(session);
					session.defineEntitySchema(PRODUCT)
						.withReferenceToEntity(
							BRAND, BRAND, Cardinality.ZERO_OR_MORE,
							whichIs -> whichIs
								.indexedForFiltering()
								.faceted()
								.withAttribute("span", IntegerNumberRange.class, thatIs -> thatIs.filterable())
								.withAttribute("validity", DateTimeRange.class, thatIs -> thatIs.filterable())
						)
						.updateVia(session);
					session.createNewEntity(BRAND, 1).upsertVia(session);
					session.createNewEntity(PRODUCT, 1)
						.setReference(BRAND, 1, ref -> ref
							.setAttribute("span", IntegerNumberRange.between(0, 20))
							.setAttribute("validity", DateTimeRange.until(T2)))
						.upsertVia(session);
					session.createNewEntity(PRODUCT, 2)
						.setReference(BRAND, 1, ref -> ref
							.setAttribute("span", IntegerNumberRange.between(0, 30))
							.setAttribute("validity", DateTimeRange.until(T3)))
						.upsertVia(session);
				}
			)
			.withProbe(
				"span inRange 25",
				query(collection(PRODUCT), filterBy(referenceHaving(BRAND, attributeInRange("span", 25))))
			)
			.withProbe(
				"validity inRange +2.500",
				query(collection(PRODUCT), filterBy(referenceHaving(BRAND, attributeInRange("validity", T2_5))))
			)
			.withProbe(
				"facet validity inRange +2.500",
				query(
					collection(PRODUCT),
					filterBy(userFilter(facetHaving(BRAND, attributeInRange("validity", T2_5))))
				)
			);
	}

	/**
	 * Defines `brand`, its entity 1, and `product.brand` indexed for filtering with one filterable reference attribute.
	 *
	 * @param session              the session
	 * @param attributeName        the reference attribute
	 * @param type                 its type
	 * @param localized            whether it is localized
	 * @param indexedDecimalPlaces its indexed decimal places
	 */
	private static void defineReferenceWithAttribute(
		@Nonnull EvitaSessionContract session,
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> type,
		boolean localized,
		int indexedDecimalPlaces
	) {
		session.defineEntitySchema(BRAND).updateVia(session);
		session.defineEntitySchema(PRODUCT)
			.withReferenceToEntity(
				BRAND, BRAND, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFiltering()
					.withAttribute(
						attributeName, type,
						thatIs -> {
							thatIs.filterable().nullable().indexDecimalPlaces(indexedDecimalPlaces);
							if (localized) {
								thatIs.localized();
							}
						}
					)
			)
			.updateVia(session);
		session.createNewEntity(BRAND, 1).upsertVia(session);
	}

	/**
	 * Returns a query for products of the scope with a `brand` reference whose `validity` contains the moment.
	 *
	 * @param scope  the scope
	 * @param moment the moment
	 * @return the query
	 */
	@Nonnull
	private static Query referenceValidityInRange(@Nonnull Scope scope, @Nonnull OffsetDateTime moment) {
		return query(
			collection(PRODUCT),
			filterBy(scope(scope), referenceHaving(BRAND, attributeInRange("validity", moment)))
		);
	}

	/**
	 * Returns a query for products with a `brand` reference whose `validity` equals the value.
	 *
	 * @param value the value
	 * @return the query
	 */
	@Nonnull
	private static Query referenceValidityEquals(@Nonnull DateTimeRange value) {
		return query(collection(PRODUCT), filterBy(referenceHaving(BRAND, attributeEquals("validity", value))));
	}

	/**
	 * Returns a query for products in the brand group whose own `validity` contains the moment.
	 *
	 * @param groupPk the group primary key
	 * @param moment  the moment
	 * @return the query
	 */
	@Nonnull
	private static Query groupValidityInRange(int groupPk, @Nonnull OffsetDateTime moment) {
		return query(
			collection(PRODUCT),
			filterBy(
				referenceHaving(BRAND, groupHaving(entityPrimaryKeyInSet(groupPk))),
				attributeInRange("validity", moment)
			)
		);
	}

	/**
	 * Returns a query for brands whose reflected reference holds a `validity` containing +2.500 s.
	 *
	 * @param reflectedReference the reflected reference
	 * @return the query
	 */
	@Nonnull
	private static Query brandsByReflectedValidity(@Nonnull String reflectedReference) {
		return query(
			collection(BRAND),
			filterBy(referenceHaving(reflectedReference, attributeInRange("validity", T2_5)))
		);
	}

}
