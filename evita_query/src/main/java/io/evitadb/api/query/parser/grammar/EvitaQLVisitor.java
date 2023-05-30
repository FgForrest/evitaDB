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
	 * Visit a parse tree produced by {@link EvitaQLParser#classifierTokenUnit}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassifierTokenUnit(EvitaQLParser.ClassifierTokenUnitContext ctx);
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
	 * Visit a parse tree produced by the {@code collectionConstraint}
	 * labeled alternative in {@link EvitaQLParser#headConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCollectionConstraint(EvitaQLParser.CollectionConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code filterByConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFilterByConstraint(EvitaQLParser.FilterByConstraintContext ctx);
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
	 * Visit a parse tree produced by the {@code orderByConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrderByConstraint(EvitaQLParser.OrderByConstraintContext ctx);
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
	 * Visit a parse tree produced by the {@code randomConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRandomConstraint(EvitaQLParser.RandomConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code referencePropertyConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitReferencePropertyConstraint(EvitaQLParser.ReferencePropertyConstraintContext ctx);
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
	 * Visit a parse tree produced by the {@code singleRefReferenceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentConstraint(EvitaQLParser.SingleRefReferenceContentConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefWithFilterReferenceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefWithFilterReferenceContentConstraint(EvitaQLParser.SingleRefWithFilterReferenceContentConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefWithOrderReferenceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefWithOrderReferenceContentConstraint(EvitaQLParser.SingleRefWithOrderReferenceContentConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code singleRefWithFilterAndOrderReferenceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefWithFilterAndOrderReferenceContentConstraint(EvitaQLParser.SingleRefWithFilterAndOrderReferenceContentConstraintContext ctx);
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
	 * Visit a parse tree produced by the {@code priceTypeConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPriceTypeConstraint(EvitaQLParser.PriceTypeConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code dataInLocalesConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDataInLocalesConstraint(EvitaQLParser.DataInLocalesConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetSummaryConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummaryConstraint(EvitaQLParser.FacetSummaryConstraintContext ctx);
	/**
	 * Visit a parse tree produced by the {@code facetSummaryOfReferenceConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummaryOfReferenceConstraint(EvitaQLParser.FacetSummaryOfReferenceConstraintContext ctx);
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
	 * Visit a parse tree produced by {@link EvitaQLParser#valueWithClassifierListArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitValueWithClassifierListArgs(EvitaQLParser.ValueWithClassifierListArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#classifierWithFilterConstraintArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassifierWithFilterConstraintArgs(EvitaQLParser.ClassifierWithFilterConstraintArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#classifierWithOrderConstraintListArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClassifierWithOrderConstraintListArgs(EvitaQLParser.ClassifierWithOrderConstraintListArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#valueWithRequireConstraintListArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitValueWithRequireConstraintListArgs(EvitaQLParser.ValueWithRequireConstraintListArgsContext ctx);
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
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefReferenceContentArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefReferenceContentArgs(EvitaQLParser.SingleRefReferenceContentArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefWithFilterReferenceContentArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefWithFilterReferenceContentArgs(EvitaQLParser.SingleRefWithFilterReferenceContentArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefWithOrderReferenceContentArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefWithOrderReferenceContentArgs(EvitaQLParser.SingleRefWithOrderReferenceContentArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#singleRefWithFilterAndOrderReferenceContentArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSingleRefWithFilterAndOrderReferenceContentArgs(EvitaQLParser.SingleRefWithFilterAndOrderReferenceContentArgsContext ctx);
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
	 * Visit a parse tree produced by {@link EvitaQLParser#facetSummaryArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummaryArgs(EvitaQLParser.FacetSummaryArgsContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaQLParser#facetSummaryOfReferenceArgs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFacetSummaryOfReferenceArgs(EvitaQLParser.FacetSummaryOfReferenceArgsContext ctx);
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
	 * Visit a parse tree produced by the {@code positionalParameterVariadicClassifierTokens}
	 * labeled alternative in {@link EvitaQLParser#variadicClassifierTokens}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPositionalParameterVariadicClassifierTokens(EvitaQLParser.PositionalParameterVariadicClassifierTokensContext ctx);
	/**
	 * Visit a parse tree produced by the {@code namedParameterVariadicClassifierTokens}
	 * labeled alternative in {@link EvitaQLParser#variadicClassifierTokens}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNamedParameterVariadicClassifierTokens(EvitaQLParser.NamedParameterVariadicClassifierTokensContext ctx);
	/**
	 * Visit a parse tree produced by the {@code explicitVariadicClassifierTokens}
	 * labeled alternative in {@link EvitaQLParser#variadicClassifierTokens}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExplicitVariadicClassifierTokens(EvitaQLParser.ExplicitVariadicClassifierTokensContext ctx);
	/**
	 * Visit a parse tree produced by the {@code positionalParameterClassifierToken}
	 * labeled alternative in {@link EvitaQLParser#classifierToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPositionalParameterClassifierToken(EvitaQLParser.PositionalParameterClassifierTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code namedParameterClassifierToken}
	 * labeled alternative in {@link EvitaQLParser#classifierToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNamedParameterClassifierToken(EvitaQLParser.NamedParameterClassifierTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code stringClassifierToken}
	 * labeled alternative in {@link EvitaQLParser#classifierToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStringClassifierToken(EvitaQLParser.StringClassifierTokenContext ctx);
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
	 * Visit a parse tree produced by the {@code enumValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEnumValueToken(EvitaQLParser.EnumValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code multipleValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMultipleValueToken(EvitaQLParser.MultipleValueTokenContext ctx);
}