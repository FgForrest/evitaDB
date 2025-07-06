/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

// Generated from EvitaQL.g4 by ANTLR 4.9.2

package io.evitadb.api.query.parser.grammar;

import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link EvitaQLParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface EvitaQLVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#queryUnit}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitQueryUnit(EvitaQLParser.QueryUnitContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#headConstraintListUnit}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHeadConstraintListUnit(EvitaQLParser.HeadConstraintListUnitContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#filterConstraintListUnit}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFilterConstraintListUnit(EvitaQLParser.FilterConstraintListUnitContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#orderConstraintListUnit}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrderConstraintListUnit(EvitaQLParser.OrderConstraintListUnitContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#requireConstraintListUnit}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRequireConstraintListUnit(EvitaQLParser.RequireConstraintListUnitContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#valueTokenUnit}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitValueTokenUnit(EvitaQLParser.ValueTokenUnitContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#query}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitQuery(EvitaQLParser.QueryContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#constraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConstraint(EvitaQLParser.ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code headContainerConstraint}
	 * labeled alternative in {@link EvitaQLParser#headConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHeadContainerConstraint(EvitaQLParser.HeadContainerConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code collectionConstraint}
	 * labeled alternative in {@link EvitaQLParser#headConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCollectionConstraint(EvitaQLParser.CollectionConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code labelConstraint}
	 * labeled alternative in {@link EvitaQLParser#headConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLabelConstraint(EvitaQLParser.LabelConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code filterByConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFilterByConstraint(EvitaQLParser.FilterByConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code filterGroupByConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFilterGroupByConstraint(EvitaQLParser.FilterGroupByConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code andConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAndConstraint(EvitaQLParser.AndConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code orConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrConstraint(EvitaQLParser.OrConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code notConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNotConstraint(EvitaQLParser.NotConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code userFilterConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUserFilterConstraint(EvitaQLParser.UserFilterConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeEqualsConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeEqualsConstraint(EvitaQLParser.AttributeEqualsConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeGreaterThanConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeGreaterThanConstraint(EvitaQLParser.AttributeGreaterThanConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeGreaterThanEqualsConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeGreaterThanEqualsConstraint(EvitaQLParser.AttributeGreaterThanEqualsConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeLessThanConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeLessThanConstraint(EvitaQLParser.AttributeLessThanConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeLessThanEqualsConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeLessThanEqualsConstraint(EvitaQLParser.AttributeLessThanEqualsConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeBetweenConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeBetweenConstraint(EvitaQLParser.AttributeBetweenConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeInSetConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeInSetConstraint(EvitaQLParser.AttributeInSetConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeContainsConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeContainsConstraint(EvitaQLParser.AttributeContainsConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeStartsWithConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeStartsWithConstraint(EvitaQLParser.AttributeStartsWithConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeEndsWithConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeEndsWithConstraint(EvitaQLParser.AttributeEndsWithConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeEqualsTrueConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeEqualsTrueConstraint(EvitaQLParser.AttributeEqualsTrueConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeEqualsFalseConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeEqualsFalseConstraint(EvitaQLParser.AttributeEqualsFalseConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeIsConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeIsConstraint(EvitaQLParser.AttributeIsConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeIsNullConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeIsNullConstraint(EvitaQLParser.AttributeIsNullConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeIsNotNullConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeIsNotNullConstraint(EvitaQLParser.AttributeIsNotNullConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeInRangeConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeInRangeConstraint(EvitaQLParser.AttributeInRangeConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeInRangeNowConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeInRangeNowConstraint(EvitaQLParser.AttributeInRangeNowConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code entityPrimaryKeyInSetConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEntityPrimaryKeyInSetConstraint(EvitaQLParser.EntityPrimaryKeyInSetConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code entityLocaleEqualsConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEntityLocaleEqualsConstraint(EvitaQLParser.EntityLocaleEqualsConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code priceInCurrencyConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPriceInCurrencyConstraint(EvitaQLParser.PriceInCurrencyConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code priceInPriceListsConstraints}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPriceInPriceListsConstraints(EvitaQLParser.PriceInPriceListsConstraintsContext ctx);
	/**
	 * Visit a parse tree produced by the {@code priceValidInNowConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPriceValidInNowConstraint(EvitaQLParser.PriceValidInNowConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code priceValidInConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPriceValidInConstraint(EvitaQLParser.PriceValidInConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code priceBetweenConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPriceBetweenConstraint(EvitaQLParser.PriceBetweenConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetHavingConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetHavingConstraint(EvitaQLParser.FacetHavingConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetIncludingChildrenConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetIncludingChildrenConstraint(EvitaQLParser.FacetIncludingChildrenConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetIncludingChildrenHavingConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetIncludingChildrenHavingConstraint(EvitaQLParser.FacetIncludingChildrenHavingConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetIncludingChildrenExceptConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetIncludingChildrenExceptConstraint(EvitaQLParser.FacetIncludingChildrenExceptConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code referenceHavingConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitReferenceHavingConstraint(EvitaQLParser.ReferenceHavingConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code hierarchyWithinConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyWithinConstraint(EvitaQLParser.HierarchyWithinConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code hierarchyWithinSelfConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyWithinSelfConstraint(EvitaQLParser.HierarchyWithinSelfConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code hierarchyWithinRootConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyWithinRootConstraint(EvitaQLParser.HierarchyWithinRootConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code hierarchyWithinRootSelfConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyWithinRootSelfConstraint(EvitaQLParser.HierarchyWithinRootSelfConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code hierarchyDirectRelationConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyDirectRelationConstraint(EvitaQLParser.HierarchyDirectRelationConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code hierarchyHavingConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyHavingConstraint(EvitaQLParser.HierarchyHavingConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code hierarchyAnyHavingConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyAnyHavingConstraint(EvitaQLParser.HierarchyAnyHavingConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code hierarchyExcludingRootConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyExcludingRootConstraint(EvitaQLParser.HierarchyExcludingRootConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code hierarchyExcludingConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyExcludingConstraint(EvitaQLParser.HierarchyExcludingConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code entityHavingConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEntityHavingConstraint(EvitaQLParser.EntityHavingConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code filterInScopeConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFilterInScopeConstraint(EvitaQLParser.FilterInScopeConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code entityScopeConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEntityScopeConstraint(EvitaQLParser.EntityScopeConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code orderByConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrderByConstraint(EvitaQLParser.OrderByConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code orderGroupByConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrderGroupByConstraint(EvitaQLParser.OrderGroupByConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeNaturalConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeNaturalConstraint(EvitaQLParser.AttributeNaturalConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeSetExactConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeSetExactConstraint(EvitaQLParser.AttributeSetExactConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeSetInFilterConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeSetInFilterConstraint(EvitaQLParser.AttributeSetInFilterConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code priceNaturalConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPriceNaturalConstraint(EvitaQLParser.PriceNaturalConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code priceDiscountConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPriceDiscountConstraint(EvitaQLParser.PriceDiscountConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code randomConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRandomConstraint(EvitaQLParser.RandomConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code randomWithSeedConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRandomWithSeedConstraint(EvitaQLParser.RandomWithSeedConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code referencePropertyConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitReferencePropertyConstraint(EvitaQLParser.ReferencePropertyConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code traverseByEntityPropertyConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTraverseByEntityPropertyConstraint(EvitaQLParser.TraverseByEntityPropertyConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code pickFirstByByEntityPropertyConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPickFirstByByEntityPropertyConstraint(EvitaQLParser.PickFirstByByEntityPropertyConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code entityPrimaryKeyExactNatural}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEntityPrimaryKeyExactNatural(EvitaQLParser.EntityPrimaryKeyExactNaturalContext ctx);
	/**
	 * Visit a parse tree produced by the {@code entityPrimaryKeyExactConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEntityPrimaryKeyExactConstraint(EvitaQLParser.EntityPrimaryKeyExactConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code entityPrimaryKeyInFilterConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEntityPrimaryKeyInFilterConstraint(EvitaQLParser.EntityPrimaryKeyInFilterConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code entityPropertyConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEntityPropertyConstraint(EvitaQLParser.EntityPropertyConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code entityGroupPropertyConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEntityGroupPropertyConstraint(EvitaQLParser.EntityGroupPropertyConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code segmentsConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSegmentsConstraint(EvitaQLParser.SegmentsConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code segmentConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSegmentConstraint(EvitaQLParser.SegmentConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code segmentLimitConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSegmentLimitConstraint(EvitaQLParser.SegmentLimitConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code orderInScopeConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrderInScopeConstraint(EvitaQLParser.OrderInScopeConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code requireContainerConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRequireContainerConstraint(EvitaQLParser.RequireContainerConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code pageConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPageConstraint(EvitaQLParser.PageConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code stripConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStripConstraint(EvitaQLParser.StripConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code entityFetchConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEntityFetchConstraint(EvitaQLParser.EntityFetchConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code entityGroupFetchConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEntityGroupFetchConstraint(EvitaQLParser.EntityGroupFetchConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeContentConstraint(EvitaQLParser.AttributeContentConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code priceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPriceContentConstraint(EvitaQLParser.PriceContentConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code priceContentAllConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPriceContentAllConstraint(EvitaQLParser.PriceContentAllConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code priceContentRespectingFilterConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPriceContentRespectingFilterConstraint(EvitaQLParser.PriceContentRespectingFilterConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code associatedDataContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAssociatedDataContentConstraint(EvitaQLParser.AssociatedDataContentConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code allRefsReferenceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAllRefsReferenceContentConstraint(EvitaQLParser.AllRefsReferenceContentConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code multipleRefsReferenceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMultipleRefsReferenceContentConstraint(EvitaQLParser.MultipleRefsReferenceContentConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContent1Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContent1Constraint(EvitaQLParser.SingleRefReferenceContent1ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContent2Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContent2Constraint(EvitaQLParser.SingleRefReferenceContent2ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContent3Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContent3Constraint(EvitaQLParser.SingleRefReferenceContent3ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContent4Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContent4Constraint(EvitaQLParser.SingleRefReferenceContent4ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContent5Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContent5Constraint(EvitaQLParser.SingleRefReferenceContent5ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContent6Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContent6Constraint(EvitaQLParser.SingleRefReferenceContent6ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContent7Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContent7Constraint(EvitaQLParser.SingleRefReferenceContent7ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContent8Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContent8Constraint(EvitaQLParser.SingleRefReferenceContent8ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code allRefsWithAttributesReferenceContent1Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAllRefsWithAttributesReferenceContent1Constraint(EvitaQLParser.AllRefsWithAttributesReferenceContent1ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code allRefsWithAttributesReferenceContent2Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAllRefsWithAttributesReferenceContent2Constraint(EvitaQLParser.AllRefsWithAttributesReferenceContent2ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code allRefsWithAttributesReferenceContent3Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAllRefsWithAttributesReferenceContent3Constraint(EvitaQLParser.AllRefsWithAttributesReferenceContent3ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContentWithAttributes1Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes1Constraint(EvitaQLParser.SingleRefReferenceContentWithAttributes1ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContentWithAttributes0Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes0Constraint(EvitaQLParser.SingleRefReferenceContentWithAttributes0ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContentWithAttributes2Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes2Constraint(EvitaQLParser.SingleRefReferenceContentWithAttributes2ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContentWithAttributes3Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes3Constraint(EvitaQLParser.SingleRefReferenceContentWithAttributes3ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContentWithAttributes4Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes4Constraint(EvitaQLParser.SingleRefReferenceContentWithAttributes4ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContentWithAttributes5Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes5Constraint(EvitaQLParser.SingleRefReferenceContentWithAttributes5ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContentWithAttributes6Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes6Constraint(EvitaQLParser.SingleRefReferenceContentWithAttributes6ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContentWithAttributes7Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes7Constraint(EvitaQLParser.SingleRefReferenceContentWithAttributes7ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContentWithAttributes8Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes8Constraint(EvitaQLParser.SingleRefReferenceContentWithAttributes8ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContentWithAttributes9Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes9Constraint(EvitaQLParser.SingleRefReferenceContentWithAttributes9ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContentWithAttributes10Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes10Constraint(EvitaQLParser.SingleRefReferenceContentWithAttributes10ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContentWithAttributes11Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes11Constraint(EvitaQLParser.SingleRefReferenceContentWithAttributes11ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefReferenceContentWithAttributes12Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes12Constraint(EvitaQLParser.SingleRefReferenceContentWithAttributes12ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code emptyHierarchyContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEmptyHierarchyContentConstraint(EvitaQLParser.EmptyHierarchyContentConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRequireHierarchyContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRequireHierarchyContentConstraint(EvitaQLParser.SingleRequireHierarchyContentConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code allRequiresHierarchyContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAllRequiresHierarchyContentConstraint(EvitaQLParser.AllRequiresHierarchyContentConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code defaultAccompanyingPriceListsConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDefaultAccompanyingPriceListsConstraint(EvitaQLParser.DefaultAccompanyingPriceListsConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code accompanyingPriceContentDefaultConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAccompanyingPriceContentDefaultConstraint(EvitaQLParser.AccompanyingPriceContentDefaultConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code accompanyingPriceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAccompanyingPriceContentConstraint(EvitaQLParser.AccompanyingPriceContentConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code priceTypeConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPriceTypeConstraint(EvitaQLParser.PriceTypeConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code dataInLocalesAllConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDataInLocalesAllConstraint(EvitaQLParser.DataInLocalesAllConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code dataInLocalesConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDataInLocalesConstraint(EvitaQLParser.DataInLocalesConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetSummary1Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummary1Constraint(EvitaQLParser.FacetSummary1ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetSummary2Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummary2Constraint(EvitaQLParser.FacetSummary2ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetSummary3Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummary3Constraint(EvitaQLParser.FacetSummary3ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetSummary4Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummary4Constraint(EvitaQLParser.FacetSummary4ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetSummary5Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummary5Constraint(EvitaQLParser.FacetSummary5ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetSummary6Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummary6Constraint(EvitaQLParser.FacetSummary6ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetSummary7Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummary7Constraint(EvitaQLParser.FacetSummary7ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetSummaryOfReference1Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummaryOfReference1Constraint(EvitaQLParser.FacetSummaryOfReference1ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetSummaryOfReference2Constraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummaryOfReference2Constraint(EvitaQLParser.FacetSummaryOfReference2ConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetGroupsConjunctionConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetGroupsConjunctionConstraint(EvitaQLParser.FacetGroupsConjunctionConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetGroupsDisjunctionConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetGroupsDisjunctionConstraint(EvitaQLParser.FacetGroupsDisjunctionConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetGroupsNegationConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetGroupsNegationConstraint(EvitaQLParser.FacetGroupsNegationConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetGroupsExclusivityConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetGroupsExclusivityConstraint(EvitaQLParser.FacetGroupsExclusivityConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetCalculationRulesConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetCalculationRulesConstraint(EvitaQLParser.FacetCalculationRulesConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code attributeHistogramConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeHistogramConstraint(EvitaQLParser.AttributeHistogramConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code priceHistogramConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPriceHistogramConstraint(EvitaQLParser.PriceHistogramConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code hierarchyDistanceConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyDistanceConstraint(EvitaQLParser.HierarchyDistanceConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code hierarchyLevelConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyLevelConstraint(EvitaQLParser.HierarchyLevelConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code hierarchyNodeConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyNodeConstraint(EvitaQLParser.HierarchyNodeConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code hierarchyStopAtConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyStopAtConstraint(EvitaQLParser.HierarchyStopAtConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code hierarchyStatisticsConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyStatisticsConstraint(EvitaQLParser.HierarchyStatisticsConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code hierarchyFromRootConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyFromRootConstraint(EvitaQLParser.HierarchyFromRootConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code hierarchyFromNodeConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyFromNodeConstraint(EvitaQLParser.HierarchyFromNodeConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code hierarchyChildrenConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyChildrenConstraint(EvitaQLParser.HierarchyChildrenConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code emptyHierarchySiblingsConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEmptyHierarchySiblingsConstraint(EvitaQLParser.EmptyHierarchySiblingsConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code basicHierarchySiblingsConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBasicHierarchySiblingsConstraint(EvitaQLParser.BasicHierarchySiblingsConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code fullHierarchySiblingsConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFullHierarchySiblingsConstraint(EvitaQLParser.FullHierarchySiblingsConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code spacingConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSpacingConstraint(EvitaQLParser.SpacingConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code gapConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGapConstraint(EvitaQLParser.GapConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code hierarchyParentsConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyParentsConstraint(EvitaQLParser.HierarchyParentsConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code basicHierarchyOfSelfConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBasicHierarchyOfSelfConstraint(EvitaQLParser.BasicHierarchyOfSelfConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code fullHierarchyOfSelfConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFullHierarchyOfSelfConstraint(EvitaQLParser.FullHierarchyOfSelfConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code basicHierarchyOfReferenceConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBasicHierarchyOfReferenceConstraint(EvitaQLParser.BasicHierarchyOfReferenceConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code basicHierarchyOfReferenceWithBehaviourConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBasicHierarchyOfReferenceWithBehaviourConstraint(EvitaQLParser.BasicHierarchyOfReferenceWithBehaviourConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code fullHierarchyOfReferenceConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFullHierarchyOfReferenceConstraint(EvitaQLParser.FullHierarchyOfReferenceConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code fullHierarchyOfReferenceWithBehaviourConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFullHierarchyOfReferenceWithBehaviourConstraint(EvitaQLParser.FullHierarchyOfReferenceWithBehaviourConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code queryTelemetryConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitQueryTelemetryConstraint(EvitaQLParser.QueryTelemetryConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code requireInScopeConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRequireInScopeConstraint(EvitaQLParser.RequireInScopeConstraintContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#headConstraintList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHeadConstraintList(EvitaQLParser.HeadConstraintListContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#filterConstraintList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFilterConstraintList(EvitaQLParser.FilterConstraintListContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#orderConstraintList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrderConstraintList(EvitaQLParser.OrderConstraintListContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#requireConstraintList}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRequireConstraintList(EvitaQLParser.RequireConstraintListContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#argsOpening}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitArgsOpening(EvitaQLParser.ArgsOpeningContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#argsClosing}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitArgsClosing(EvitaQLParser.ArgsClosingContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#constraintListArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConstraintListArgs(EvitaQLParser.ConstraintListArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#emptyArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEmptyArgs(EvitaQLParser.EmptyArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#headConstraintListArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHeadConstraintListArgs(EvitaQLParser.HeadConstraintListArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#filterConstraintListArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFilterConstraintListArgs(EvitaQLParser.FilterConstraintListArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#filterConstraintArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFilterConstraintArgs(EvitaQLParser.FilterConstraintArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#traverseOrderConstraintListArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTraverseOrderConstraintListArgs(EvitaQLParser.TraverseOrderConstraintListArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#orderConstraintListArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrderConstraintListArgs(EvitaQLParser.OrderConstraintListArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#requireConstraintArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRequireConstraintArgs(EvitaQLParser.RequireConstraintArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#requireConstraintListArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRequireConstraintListArgs(EvitaQLParser.RequireConstraintListArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#classifierArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassifierArgs(EvitaQLParser.ClassifierArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#classifierWithValueArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassifierWithValueArgs(EvitaQLParser.ClassifierWithValueArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#classifierWithOptionalValueArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassifierWithOptionalValueArgs(EvitaQLParser.ClassifierWithOptionalValueArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#classifierWithValueListArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassifierWithValueListArgs(EvitaQLParser.ClassifierWithValueListArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#classifierWithOptionalValueListArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassifierWithOptionalValueListArgs(EvitaQLParser.ClassifierWithOptionalValueListArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#classifierWithBetweenValuesArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassifierWithBetweenValuesArgs(EvitaQLParser.ClassifierWithBetweenValuesArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#valueArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitValueArgs(EvitaQLParser.ValueArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#valueListArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitValueListArgs(EvitaQLParser.ValueListArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#betweenValuesArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBetweenValuesArgs(EvitaQLParser.BetweenValuesArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#classifierListArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassifierListArgs(EvitaQLParser.ClassifierListArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#classifierWithFilterConstraintArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassifierWithFilterConstraintArgs(EvitaQLParser.ClassifierWithFilterConstraintArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#classifierWithTwoFilterConstraintArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassifierWithTwoFilterConstraintArgs(EvitaQLParser.ClassifierWithTwoFilterConstraintArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#facetGroupRelationArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetGroupRelationArgs(EvitaQLParser.FacetGroupRelationArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#facetCalculationRulesArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetCalculationRulesArgs(EvitaQLParser.FacetCalculationRulesArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#classifierWithOrderConstraintListArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassifierWithOrderConstraintListArgs(EvitaQLParser.ClassifierWithOrderConstraintListArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#hierarchyWithinConstraintArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyWithinConstraintArgs(EvitaQLParser.HierarchyWithinConstraintArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#hierarchyWithinSelfConstraintArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyWithinSelfConstraintArgs(EvitaQLParser.HierarchyWithinSelfConstraintArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#hierarchyWithinRootConstraintArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyWithinRootConstraintArgs(EvitaQLParser.HierarchyWithinRootConstraintArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#hierarchyWithinRootSelfConstraintArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyWithinRootSelfConstraintArgs(EvitaQLParser.HierarchyWithinRootSelfConstraintArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#attributeSetExactArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeSetExactArgs(EvitaQLParser.AttributeSetExactArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#pageConstraintArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPageConstraintArgs(EvitaQLParser.PageConstraintArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#stripConstraintArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStripConstraintArgs(EvitaQLParser.StripConstraintArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#priceContentArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPriceContentArgs(EvitaQLParser.PriceContentArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefReferenceContent1Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContent1Args(EvitaQLParser.SingleRefReferenceContent1ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefReferenceContent2Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContent2Args(EvitaQLParser.SingleRefReferenceContent2ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefReferenceContent3Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContent3Args(EvitaQLParser.SingleRefReferenceContent3ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefReferenceContent4Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContent4Args(EvitaQLParser.SingleRefReferenceContent4ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefReferenceContent5Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContent5Args(EvitaQLParser.SingleRefReferenceContent5ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefReferenceContent6Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContent6Args(EvitaQLParser.SingleRefReferenceContent6ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefReferenceContent7Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContent7Args(EvitaQLParser.SingleRefReferenceContent7ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefReferenceContent8Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContent8Args(EvitaQLParser.SingleRefReferenceContent8ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefReferenceContentWithAttributes0Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes0Args(EvitaQLParser.SingleRefReferenceContentWithAttributes0ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefReferenceContentWithAttributes1Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes1Args(EvitaQLParser.SingleRefReferenceContentWithAttributes1ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefReferenceContentWithAttributes2Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes2Args(EvitaQLParser.SingleRefReferenceContentWithAttributes2ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefReferenceContentWithAttributes3Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes3Args(EvitaQLParser.SingleRefReferenceContentWithAttributes3ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefReferenceContentWithAttributes4Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes4Args(EvitaQLParser.SingleRefReferenceContentWithAttributes4ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefReferenceContentWithAttributes5Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes5Args(EvitaQLParser.SingleRefReferenceContentWithAttributes5ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefReferenceContentWithAttributes6Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes6Args(EvitaQLParser.SingleRefReferenceContentWithAttributes6ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefReferenceContentWithAttributes7Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes7Args(EvitaQLParser.SingleRefReferenceContentWithAttributes7ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefReferenceContentWithAttributes8Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentWithAttributes8Args(EvitaQLParser.SingleRefReferenceContentWithAttributes8ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#multipleRefsReferenceContentArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMultipleRefsReferenceContentArgs(EvitaQLParser.MultipleRefsReferenceContentArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#allRefsReferenceContentArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAllRefsReferenceContentArgs(EvitaQLParser.AllRefsReferenceContentArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#allRefsWithAttributesReferenceContent1Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAllRefsWithAttributesReferenceContent1Args(EvitaQLParser.AllRefsWithAttributesReferenceContent1ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#allRefsWithAttributesReferenceContent2Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAllRefsWithAttributesReferenceContent2Args(EvitaQLParser.AllRefsWithAttributesReferenceContent2ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#allRefsWithAttributesReferenceContent3Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAllRefsWithAttributesReferenceContent3Args(EvitaQLParser.AllRefsWithAttributesReferenceContent3ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRequireHierarchyContentArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRequireHierarchyContentArgs(EvitaQLParser.SingleRequireHierarchyContentArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#allRequiresHierarchyContentArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAllRequiresHierarchyContentArgs(EvitaQLParser.AllRequiresHierarchyContentArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#facetSummary1Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummary1Args(EvitaQLParser.FacetSummary1ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#facetSummary2Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummary2Args(EvitaQLParser.FacetSummary2ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#facetSummary3Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummary3Args(EvitaQLParser.FacetSummary3ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#facetSummary4Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummary4Args(EvitaQLParser.FacetSummary4ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#facetSummary5Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummary5Args(EvitaQLParser.FacetSummary5ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#facetSummary6Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummary6Args(EvitaQLParser.FacetSummary6ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#facetSummary7Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummary7Args(EvitaQLParser.FacetSummary7ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#facetSummaryOfReference2Args}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummaryOfReference2Args(EvitaQLParser.FacetSummaryOfReference2ArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#facetSummaryRequirementsArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummaryRequirementsArgs(EvitaQLParser.FacetSummaryRequirementsArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#facetSummaryFilterArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummaryFilterArgs(EvitaQLParser.FacetSummaryFilterArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#facetSummaryOrderArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummaryOrderArgs(EvitaQLParser.FacetSummaryOrderArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#attributeHistogramArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttributeHistogramArgs(EvitaQLParser.AttributeHistogramArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#priceHistogramArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPriceHistogramArgs(EvitaQLParser.PriceHistogramArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#hierarchyStatisticsArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyStatisticsArgs(EvitaQLParser.HierarchyStatisticsArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#hierarchyRequireConstraintArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyRequireConstraintArgs(EvitaQLParser.HierarchyRequireConstraintArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#hierarchyFromNodeArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchyFromNodeArgs(EvitaQLParser.HierarchyFromNodeArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#fullHierarchyOfSelfArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFullHierarchyOfSelfArgs(EvitaQLParser.FullHierarchyOfSelfArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#basicHierarchyOfReferenceArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBasicHierarchyOfReferenceArgs(EvitaQLParser.BasicHierarchyOfReferenceArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#basicHierarchyOfReferenceWithBehaviourArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBasicHierarchyOfReferenceWithBehaviourArgs(EvitaQLParser.BasicHierarchyOfReferenceWithBehaviourArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#fullHierarchyOfReferenceArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFullHierarchyOfReferenceArgs(EvitaQLParser.FullHierarchyOfReferenceArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#fullHierarchyOfReferenceWithBehaviourArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFullHierarchyOfReferenceWithBehaviourArgs(EvitaQLParser.FullHierarchyOfReferenceWithBehaviourArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#spacingRequireConstraintArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSpacingRequireConstraintArgs(EvitaQLParser.SpacingRequireConstraintArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#gapRequireConstraintArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGapRequireConstraintArgs(EvitaQLParser.GapRequireConstraintArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#segmentArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSegmentArgs(EvitaQLParser.SegmentArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#inScopeFilterArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInScopeFilterArgs(EvitaQLParser.InScopeFilterArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#inScopeOrderArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInScopeOrderArgs(EvitaQLParser.InScopeOrderArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#inScopeRequireArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInScopeRequireArgs(EvitaQLParser.InScopeRequireArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#positionalParameter}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPositionalParameter(EvitaQLParser.PositionalParameterContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#namedParameter}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNamedParameter(EvitaQLParser.NamedParameterContext ctx);
	/**
	 * Visit a parse tree produced by the {@code positionalParameterVariadicValueTokens}
	 * labeled alternative in {@link EvitaQLParser#variadicValueTokens}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPositionalParameterVariadicValueTokens(EvitaQLParser.PositionalParameterVariadicValueTokensContext ctx);
	/**
	 * Visit a parse tree produced by the {@code namedParameterVariadicValueTokens}
	 * labeled alternative in {@link EvitaQLParser#variadicValueTokens}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNamedParameterVariadicValueTokens(EvitaQLParser.NamedParameterVariadicValueTokensContext ctx);
	/**
	 * Visit a parse tree produced by the {@code explicitVariadicValueTokens}
	 * labeled alternative in {@link EvitaQLParser#variadicValueTokens}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExplicitVariadicValueTokens(EvitaQLParser.ExplicitVariadicValueTokensContext ctx);
	/**
	 * Visit a parse tree produced by the {@code positionalParameterValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPositionalParameterValueToken(EvitaQLParser.PositionalParameterValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code namedParameterValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNamedParameterValueToken(EvitaQLParser.NamedParameterValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code stringValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStringValueToken(EvitaQLParser.StringValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code intValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIntValueToken(EvitaQLParser.IntValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code floatValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFloatValueToken(EvitaQLParser.FloatValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code booleanValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBooleanValueToken(EvitaQLParser.BooleanValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code dateValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDateValueToken(EvitaQLParser.DateValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code timeValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTimeValueToken(EvitaQLParser.TimeValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code dateTimeValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDateTimeValueToken(EvitaQLParser.DateTimeValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code offsetDateTimeValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOffsetDateTimeValueToken(EvitaQLParser.OffsetDateTimeValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code floatNumberRangeValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFloatNumberRangeValueToken(EvitaQLParser.FloatNumberRangeValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code intNumberRangeValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIntNumberRangeValueToken(EvitaQLParser.IntNumberRangeValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code dateTimeRangeValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDateTimeRangeValueToken(EvitaQLParser.DateTimeRangeValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code uuidValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUuidValueToken(EvitaQLParser.UuidValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code enumValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEnumValueToken(EvitaQLParser.EnumValueTokenContext ctx);
}