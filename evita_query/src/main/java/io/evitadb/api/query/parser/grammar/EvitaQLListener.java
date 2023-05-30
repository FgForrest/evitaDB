// Generated from EvitaQL.g4 by ANTLR 4.9.2

package io.evitadb.api.query.parser.grammar;

import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link EvitaQLParser}.
 */
public interface EvitaQLListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#queryUnit}.
	 * @param ctx the parse tree
	 */
	void enterQueryUnit(EvitaQLParser.QueryUnitContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#queryUnit}.
	 * @param ctx the parse tree
	 */
	void exitQueryUnit(EvitaQLParser.QueryUnitContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#headConstraintListUnit}.
	 * @param ctx the parse tree
	 */
	void enterHeadConstraintListUnit(EvitaQLParser.HeadConstraintListUnitContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#headConstraintListUnit}.
	 * @param ctx the parse tree
	 */
	void exitHeadConstraintListUnit(EvitaQLParser.HeadConstraintListUnitContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#filterConstraintListUnit}.
	 * @param ctx the parse tree
	 */
	void enterFilterConstraintListUnit(EvitaQLParser.FilterConstraintListUnitContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#filterConstraintListUnit}.
	 * @param ctx the parse tree
	 */
	void exitFilterConstraintListUnit(EvitaQLParser.FilterConstraintListUnitContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#orderConstraintListUnit}.
	 * @param ctx the parse tree
	 */
	void enterOrderConstraintListUnit(EvitaQLParser.OrderConstraintListUnitContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#orderConstraintListUnit}.
	 * @param ctx the parse tree
	 */
	void exitOrderConstraintListUnit(EvitaQLParser.OrderConstraintListUnitContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#requireConstraintListUnit}.
	 * @param ctx the parse tree
	 */
	void enterRequireConstraintListUnit(EvitaQLParser.RequireConstraintListUnitContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#requireConstraintListUnit}.
	 * @param ctx the parse tree
	 */
	void exitRequireConstraintListUnit(EvitaQLParser.RequireConstraintListUnitContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#classifierTokenUnit}.
	 * @param ctx the parse tree
	 */
	void enterClassifierTokenUnit(EvitaQLParser.ClassifierTokenUnitContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#classifierTokenUnit}.
	 * @param ctx the parse tree
	 */
	void exitClassifierTokenUnit(EvitaQLParser.ClassifierTokenUnitContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#valueTokenUnit}.
	 * @param ctx the parse tree
	 */
	void enterValueTokenUnit(EvitaQLParser.ValueTokenUnitContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#valueTokenUnit}.
	 * @param ctx the parse tree
	 */
	void exitValueTokenUnit(EvitaQLParser.ValueTokenUnitContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#query}.
	 * @param ctx the parse tree
	 */
	void enterQuery(EvitaQLParser.QueryContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#query}.
	 * @param ctx the parse tree
	 */
	void exitQuery(EvitaQLParser.QueryContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#constraint}.
	 * @param ctx the parse tree
	 */
	void enterConstraint(EvitaQLParser.ConstraintContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#constraint}.
	 * @param ctx the parse tree
	 */
	void exitConstraint(EvitaQLParser.ConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code collectionConstraint}
	 * labeled alternative in {@link EvitaQLParser#headConstraint}.
	 * @param ctx the parse tree
	 */
	void enterCollectionConstraint(EvitaQLParser.CollectionConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code collectionConstraint}
	 * labeled alternative in {@link EvitaQLParser#headConstraint}.
	 * @param ctx the parse tree
	 */
	void exitCollectionConstraint(EvitaQLParser.CollectionConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code filterByConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterFilterByConstraint(EvitaQLParser.FilterByConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code filterByConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitFilterByConstraint(EvitaQLParser.FilterByConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code andConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAndConstraint(EvitaQLParser.AndConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code andConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAndConstraint(EvitaQLParser.AndConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code orConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterOrConstraint(EvitaQLParser.OrConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code orConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitOrConstraint(EvitaQLParser.OrConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code notConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterNotConstraint(EvitaQLParser.NotConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code notConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitNotConstraint(EvitaQLParser.NotConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code userFilterConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterUserFilterConstraint(EvitaQLParser.UserFilterConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code userFilterConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitUserFilterConstraint(EvitaQLParser.UserFilterConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeEqualsConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeEqualsConstraint(EvitaQLParser.AttributeEqualsConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeEqualsConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeEqualsConstraint(EvitaQLParser.AttributeEqualsConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeGreaterThanConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeGreaterThanConstraint(EvitaQLParser.AttributeGreaterThanConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeGreaterThanConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeGreaterThanConstraint(EvitaQLParser.AttributeGreaterThanConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeGreaterThanEqualsConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeGreaterThanEqualsConstraint(EvitaQLParser.AttributeGreaterThanEqualsConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeGreaterThanEqualsConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeGreaterThanEqualsConstraint(EvitaQLParser.AttributeGreaterThanEqualsConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeLessThanConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeLessThanConstraint(EvitaQLParser.AttributeLessThanConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeLessThanConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeLessThanConstraint(EvitaQLParser.AttributeLessThanConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeLessThanEqualsConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeLessThanEqualsConstraint(EvitaQLParser.AttributeLessThanEqualsConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeLessThanEqualsConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeLessThanEqualsConstraint(EvitaQLParser.AttributeLessThanEqualsConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeBetweenConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeBetweenConstraint(EvitaQLParser.AttributeBetweenConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeBetweenConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeBetweenConstraint(EvitaQLParser.AttributeBetweenConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeInSetConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeInSetConstraint(EvitaQLParser.AttributeInSetConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeInSetConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeInSetConstraint(EvitaQLParser.AttributeInSetConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeContainsConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeContainsConstraint(EvitaQLParser.AttributeContainsConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeContainsConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeContainsConstraint(EvitaQLParser.AttributeContainsConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeStartsWithConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeStartsWithConstraint(EvitaQLParser.AttributeStartsWithConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeStartsWithConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeStartsWithConstraint(EvitaQLParser.AttributeStartsWithConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeEndsWithConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeEndsWithConstraint(EvitaQLParser.AttributeEndsWithConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeEndsWithConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeEndsWithConstraint(EvitaQLParser.AttributeEndsWithConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeEqualsTrueConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeEqualsTrueConstraint(EvitaQLParser.AttributeEqualsTrueConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeEqualsTrueConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeEqualsTrueConstraint(EvitaQLParser.AttributeEqualsTrueConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeEqualsFalseConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeEqualsFalseConstraint(EvitaQLParser.AttributeEqualsFalseConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeEqualsFalseConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeEqualsFalseConstraint(EvitaQLParser.AttributeEqualsFalseConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeIsConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeIsConstraint(EvitaQLParser.AttributeIsConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeIsConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeIsConstraint(EvitaQLParser.AttributeIsConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeIsNullConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeIsNullConstraint(EvitaQLParser.AttributeIsNullConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeIsNullConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeIsNullConstraint(EvitaQLParser.AttributeIsNullConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeIsNotNullConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeIsNotNullConstraint(EvitaQLParser.AttributeIsNotNullConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeIsNotNullConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeIsNotNullConstraint(EvitaQLParser.AttributeIsNotNullConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeInRangeConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeInRangeConstraint(EvitaQLParser.AttributeInRangeConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeInRangeConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeInRangeConstraint(EvitaQLParser.AttributeInRangeConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code entityPrimaryKeyInSetConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterEntityPrimaryKeyInSetConstraint(EvitaQLParser.EntityPrimaryKeyInSetConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code entityPrimaryKeyInSetConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitEntityPrimaryKeyInSetConstraint(EvitaQLParser.EntityPrimaryKeyInSetConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code entityLocaleEqualsConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterEntityLocaleEqualsConstraint(EvitaQLParser.EntityLocaleEqualsConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code entityLocaleEqualsConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitEntityLocaleEqualsConstraint(EvitaQLParser.EntityLocaleEqualsConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code priceInCurrencyConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterPriceInCurrencyConstraint(EvitaQLParser.PriceInCurrencyConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code priceInCurrencyConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitPriceInCurrencyConstraint(EvitaQLParser.PriceInCurrencyConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code priceInPriceListsConstraints}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterPriceInPriceListsConstraints(EvitaQLParser.PriceInPriceListsConstraintsContext ctx);
	/**
	 * Exit a parse tree produced by the {@code priceInPriceListsConstraints}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitPriceInPriceListsConstraints(EvitaQLParser.PriceInPriceListsConstraintsContext ctx);
	/**
	 * Enter a parse tree produced by the {@code priceValidInNowConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterPriceValidInNowConstraint(EvitaQLParser.PriceValidInNowConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code priceValidInNowConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitPriceValidInNowConstraint(EvitaQLParser.PriceValidInNowConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code priceValidInConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterPriceValidInConstraint(EvitaQLParser.PriceValidInConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code priceValidInConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitPriceValidInConstraint(EvitaQLParser.PriceValidInConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code priceBetweenConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterPriceBetweenConstraint(EvitaQLParser.PriceBetweenConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code priceBetweenConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitPriceBetweenConstraint(EvitaQLParser.PriceBetweenConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code facetHavingConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterFacetHavingConstraint(EvitaQLParser.FacetHavingConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code facetHavingConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitFacetHavingConstraint(EvitaQLParser.FacetHavingConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code referenceHavingConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterReferenceHavingConstraint(EvitaQLParser.ReferenceHavingConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code referenceHavingConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitReferenceHavingConstraint(EvitaQLParser.ReferenceHavingConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code hierarchyWithinConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyWithinConstraint(EvitaQLParser.HierarchyWithinConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code hierarchyWithinConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyWithinConstraint(EvitaQLParser.HierarchyWithinConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code hierarchyWithinSelfConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyWithinSelfConstraint(EvitaQLParser.HierarchyWithinSelfConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code hierarchyWithinSelfConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyWithinSelfConstraint(EvitaQLParser.HierarchyWithinSelfConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code hierarchyWithinRootConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyWithinRootConstraint(EvitaQLParser.HierarchyWithinRootConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code hierarchyWithinRootConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyWithinRootConstraint(EvitaQLParser.HierarchyWithinRootConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code hierarchyWithinRootSelfConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyWithinRootSelfConstraint(EvitaQLParser.HierarchyWithinRootSelfConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code hierarchyWithinRootSelfConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyWithinRootSelfConstraint(EvitaQLParser.HierarchyWithinRootSelfConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code hierarchyDirectRelationConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyDirectRelationConstraint(EvitaQLParser.HierarchyDirectRelationConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code hierarchyDirectRelationConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyDirectRelationConstraint(EvitaQLParser.HierarchyDirectRelationConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code hierarchyHavingConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyHavingConstraint(EvitaQLParser.HierarchyHavingConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code hierarchyHavingConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyHavingConstraint(EvitaQLParser.HierarchyHavingConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code hierarchyExcludingRootConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyExcludingRootConstraint(EvitaQLParser.HierarchyExcludingRootConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code hierarchyExcludingRootConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyExcludingRootConstraint(EvitaQLParser.HierarchyExcludingRootConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code hierarchyExcludingConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyExcludingConstraint(EvitaQLParser.HierarchyExcludingConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code hierarchyExcludingConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyExcludingConstraint(EvitaQLParser.HierarchyExcludingConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code entityHavingConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void enterEntityHavingConstraint(EvitaQLParser.EntityHavingConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code entityHavingConstraint}
	 * labeled alternative in {@link EvitaQLParser#filterConstraint}.
	 * @param ctx the parse tree
	 */
	void exitEntityHavingConstraint(EvitaQLParser.EntityHavingConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code orderByConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void enterOrderByConstraint(EvitaQLParser.OrderByConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code orderByConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void exitOrderByConstraint(EvitaQLParser.OrderByConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeNaturalConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeNaturalConstraint(EvitaQLParser.AttributeNaturalConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeNaturalConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeNaturalConstraint(EvitaQLParser.AttributeNaturalConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeSetExactConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeSetExactConstraint(EvitaQLParser.AttributeSetExactConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeSetExactConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeSetExactConstraint(EvitaQLParser.AttributeSetExactConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeSetInFilterConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeSetInFilterConstraint(EvitaQLParser.AttributeSetInFilterConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeSetInFilterConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeSetInFilterConstraint(EvitaQLParser.AttributeSetInFilterConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code priceNaturalConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void enterPriceNaturalConstraint(EvitaQLParser.PriceNaturalConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code priceNaturalConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void exitPriceNaturalConstraint(EvitaQLParser.PriceNaturalConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code randomConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void enterRandomConstraint(EvitaQLParser.RandomConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code randomConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void exitRandomConstraint(EvitaQLParser.RandomConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code referencePropertyConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void enterReferencePropertyConstraint(EvitaQLParser.ReferencePropertyConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code referencePropertyConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void exitReferencePropertyConstraint(EvitaQLParser.ReferencePropertyConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code entityPrimaryKeyExactConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void enterEntityPrimaryKeyExactConstraint(EvitaQLParser.EntityPrimaryKeyExactConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code entityPrimaryKeyExactConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void exitEntityPrimaryKeyExactConstraint(EvitaQLParser.EntityPrimaryKeyExactConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code entityPrimaryKeyInFilterConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void enterEntityPrimaryKeyInFilterConstraint(EvitaQLParser.EntityPrimaryKeyInFilterConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code entityPrimaryKeyInFilterConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void exitEntityPrimaryKeyInFilterConstraint(EvitaQLParser.EntityPrimaryKeyInFilterConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code entityPropertyConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void enterEntityPropertyConstraint(EvitaQLParser.EntityPropertyConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code entityPropertyConstraint}
	 * labeled alternative in {@link EvitaQLParser#orderConstraint}.
	 * @param ctx the parse tree
	 */
	void exitEntityPropertyConstraint(EvitaQLParser.EntityPropertyConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code requireContainerConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterRequireContainerConstraint(EvitaQLParser.RequireContainerConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code requireContainerConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitRequireContainerConstraint(EvitaQLParser.RequireContainerConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code pageConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterPageConstraint(EvitaQLParser.PageConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code pageConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitPageConstraint(EvitaQLParser.PageConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code stripConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterStripConstraint(EvitaQLParser.StripConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code stripConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitStripConstraint(EvitaQLParser.StripConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code entityFetchConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterEntityFetchConstraint(EvitaQLParser.EntityFetchConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code entityFetchConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitEntityFetchConstraint(EvitaQLParser.EntityFetchConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code entityGroupFetchConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterEntityGroupFetchConstraint(EvitaQLParser.EntityGroupFetchConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code entityGroupFetchConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitEntityGroupFetchConstraint(EvitaQLParser.EntityGroupFetchConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeContentConstraint(EvitaQLParser.AttributeContentConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeContentConstraint(EvitaQLParser.AttributeContentConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code priceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterPriceContentConstraint(EvitaQLParser.PriceContentConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code priceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitPriceContentConstraint(EvitaQLParser.PriceContentConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code priceContentAllConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterPriceContentAllConstraint(EvitaQLParser.PriceContentAllConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code priceContentAllConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitPriceContentAllConstraint(EvitaQLParser.PriceContentAllConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code priceContentRespectingFilterConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterPriceContentRespectingFilterConstraint(EvitaQLParser.PriceContentRespectingFilterConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code priceContentRespectingFilterConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitPriceContentRespectingFilterConstraint(EvitaQLParser.PriceContentRespectingFilterConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code associatedDataContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAssociatedDataContentConstraint(EvitaQLParser.AssociatedDataContentConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code associatedDataContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAssociatedDataContentConstraint(EvitaQLParser.AssociatedDataContentConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code allRefsReferenceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAllRefsReferenceContentConstraint(EvitaQLParser.AllRefsReferenceContentConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code allRefsReferenceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAllRefsReferenceContentConstraint(EvitaQLParser.AllRefsReferenceContentConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code multipleRefsReferenceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterMultipleRefsReferenceContentConstraint(EvitaQLParser.MultipleRefsReferenceContentConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code multipleRefsReferenceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitMultipleRefsReferenceContentConstraint(EvitaQLParser.MultipleRefsReferenceContentConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code singleRefReferenceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterSingleRefReferenceContentConstraint(EvitaQLParser.SingleRefReferenceContentConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code singleRefReferenceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitSingleRefReferenceContentConstraint(EvitaQLParser.SingleRefReferenceContentConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code singleRefWithFilterReferenceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterSingleRefWithFilterReferenceContentConstraint(EvitaQLParser.SingleRefWithFilterReferenceContentConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code singleRefWithFilterReferenceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitSingleRefWithFilterReferenceContentConstraint(EvitaQLParser.SingleRefWithFilterReferenceContentConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code singleRefWithOrderReferenceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterSingleRefWithOrderReferenceContentConstraint(EvitaQLParser.SingleRefWithOrderReferenceContentConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code singleRefWithOrderReferenceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitSingleRefWithOrderReferenceContentConstraint(EvitaQLParser.SingleRefWithOrderReferenceContentConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code singleRefWithFilterAndOrderReferenceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterSingleRefWithFilterAndOrderReferenceContentConstraint(EvitaQLParser.SingleRefWithFilterAndOrderReferenceContentConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code singleRefWithFilterAndOrderReferenceContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitSingleRefWithFilterAndOrderReferenceContentConstraint(EvitaQLParser.SingleRefWithFilterAndOrderReferenceContentConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code emptyHierarchyContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterEmptyHierarchyContentConstraint(EvitaQLParser.EmptyHierarchyContentConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code emptyHierarchyContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitEmptyHierarchyContentConstraint(EvitaQLParser.EmptyHierarchyContentConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code singleRequireHierarchyContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterSingleRequireHierarchyContentConstraint(EvitaQLParser.SingleRequireHierarchyContentConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code singleRequireHierarchyContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitSingleRequireHierarchyContentConstraint(EvitaQLParser.SingleRequireHierarchyContentConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code allRequiresHierarchyContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAllRequiresHierarchyContentConstraint(EvitaQLParser.AllRequiresHierarchyContentConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code allRequiresHierarchyContentConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAllRequiresHierarchyContentConstraint(EvitaQLParser.AllRequiresHierarchyContentConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code priceTypeConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterPriceTypeConstraint(EvitaQLParser.PriceTypeConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code priceTypeConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitPriceTypeConstraint(EvitaQLParser.PriceTypeConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code dataInLocalesConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterDataInLocalesConstraint(EvitaQLParser.DataInLocalesConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code dataInLocalesConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitDataInLocalesConstraint(EvitaQLParser.DataInLocalesConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code facetSummaryConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterFacetSummaryConstraint(EvitaQLParser.FacetSummaryConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code facetSummaryConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitFacetSummaryConstraint(EvitaQLParser.FacetSummaryConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code facetSummaryOfReferenceConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterFacetSummaryOfReferenceConstraint(EvitaQLParser.FacetSummaryOfReferenceConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code facetSummaryOfReferenceConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitFacetSummaryOfReferenceConstraint(EvitaQLParser.FacetSummaryOfReferenceConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code facetGroupsConjunctionConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterFacetGroupsConjunctionConstraint(EvitaQLParser.FacetGroupsConjunctionConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code facetGroupsConjunctionConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitFacetGroupsConjunctionConstraint(EvitaQLParser.FacetGroupsConjunctionConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code facetGroupsDisjunctionConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterFacetGroupsDisjunctionConstraint(EvitaQLParser.FacetGroupsDisjunctionConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code facetGroupsDisjunctionConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitFacetGroupsDisjunctionConstraint(EvitaQLParser.FacetGroupsDisjunctionConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code facetGroupsNegationConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterFacetGroupsNegationConstraint(EvitaQLParser.FacetGroupsNegationConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code facetGroupsNegationConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitFacetGroupsNegationConstraint(EvitaQLParser.FacetGroupsNegationConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code attributeHistogramConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterAttributeHistogramConstraint(EvitaQLParser.AttributeHistogramConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code attributeHistogramConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitAttributeHistogramConstraint(EvitaQLParser.AttributeHistogramConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code priceHistogramConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterPriceHistogramConstraint(EvitaQLParser.PriceHistogramConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code priceHistogramConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitPriceHistogramConstraint(EvitaQLParser.PriceHistogramConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code hierarchyDistanceConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyDistanceConstraint(EvitaQLParser.HierarchyDistanceConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code hierarchyDistanceConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyDistanceConstraint(EvitaQLParser.HierarchyDistanceConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code hierarchyLevelConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyLevelConstraint(EvitaQLParser.HierarchyLevelConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code hierarchyLevelConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyLevelConstraint(EvitaQLParser.HierarchyLevelConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code hierarchyNodeConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyNodeConstraint(EvitaQLParser.HierarchyNodeConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code hierarchyNodeConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyNodeConstraint(EvitaQLParser.HierarchyNodeConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code hierarchyStopAtConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyStopAtConstraint(EvitaQLParser.HierarchyStopAtConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code hierarchyStopAtConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyStopAtConstraint(EvitaQLParser.HierarchyStopAtConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code hierarchyStatisticsConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyStatisticsConstraint(EvitaQLParser.HierarchyStatisticsConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code hierarchyStatisticsConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyStatisticsConstraint(EvitaQLParser.HierarchyStatisticsConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code hierarchyFromRootConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyFromRootConstraint(EvitaQLParser.HierarchyFromRootConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code hierarchyFromRootConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyFromRootConstraint(EvitaQLParser.HierarchyFromRootConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code hierarchyFromNodeConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyFromNodeConstraint(EvitaQLParser.HierarchyFromNodeConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code hierarchyFromNodeConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyFromNodeConstraint(EvitaQLParser.HierarchyFromNodeConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code hierarchyChildrenConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyChildrenConstraint(EvitaQLParser.HierarchyChildrenConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code hierarchyChildrenConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyChildrenConstraint(EvitaQLParser.HierarchyChildrenConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code emptyHierarchySiblingsConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterEmptyHierarchySiblingsConstraint(EvitaQLParser.EmptyHierarchySiblingsConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code emptyHierarchySiblingsConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitEmptyHierarchySiblingsConstraint(EvitaQLParser.EmptyHierarchySiblingsConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code basicHierarchySiblingsConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterBasicHierarchySiblingsConstraint(EvitaQLParser.BasicHierarchySiblingsConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code basicHierarchySiblingsConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitBasicHierarchySiblingsConstraint(EvitaQLParser.BasicHierarchySiblingsConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code fullHierarchySiblingsConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterFullHierarchySiblingsConstraint(EvitaQLParser.FullHierarchySiblingsConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code fullHierarchySiblingsConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitFullHierarchySiblingsConstraint(EvitaQLParser.FullHierarchySiblingsConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code hierarchyParentsConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyParentsConstraint(EvitaQLParser.HierarchyParentsConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code hierarchyParentsConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyParentsConstraint(EvitaQLParser.HierarchyParentsConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code basicHierarchyOfSelfConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterBasicHierarchyOfSelfConstraint(EvitaQLParser.BasicHierarchyOfSelfConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code basicHierarchyOfSelfConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitBasicHierarchyOfSelfConstraint(EvitaQLParser.BasicHierarchyOfSelfConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code fullHierarchyOfSelfConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterFullHierarchyOfSelfConstraint(EvitaQLParser.FullHierarchyOfSelfConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code fullHierarchyOfSelfConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitFullHierarchyOfSelfConstraint(EvitaQLParser.FullHierarchyOfSelfConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code basicHierarchyOfReferenceConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterBasicHierarchyOfReferenceConstraint(EvitaQLParser.BasicHierarchyOfReferenceConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code basicHierarchyOfReferenceConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitBasicHierarchyOfReferenceConstraint(EvitaQLParser.BasicHierarchyOfReferenceConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code basicHierarchyOfReferenceWithBehaviourConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterBasicHierarchyOfReferenceWithBehaviourConstraint(EvitaQLParser.BasicHierarchyOfReferenceWithBehaviourConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code basicHierarchyOfReferenceWithBehaviourConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitBasicHierarchyOfReferenceWithBehaviourConstraint(EvitaQLParser.BasicHierarchyOfReferenceWithBehaviourConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code fullHierarchyOfReferenceConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterFullHierarchyOfReferenceConstraint(EvitaQLParser.FullHierarchyOfReferenceConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code fullHierarchyOfReferenceConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitFullHierarchyOfReferenceConstraint(EvitaQLParser.FullHierarchyOfReferenceConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code fullHierarchyOfReferenceWithBehaviourConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterFullHierarchyOfReferenceWithBehaviourConstraint(EvitaQLParser.FullHierarchyOfReferenceWithBehaviourConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code fullHierarchyOfReferenceWithBehaviourConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitFullHierarchyOfReferenceWithBehaviourConstraint(EvitaQLParser.FullHierarchyOfReferenceWithBehaviourConstraintContext ctx);
	/**
	 * Enter a parse tree produced by the {@code queryTelemetryConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void enterQueryTelemetryConstraint(EvitaQLParser.QueryTelemetryConstraintContext ctx);
	/**
	 * Exit a parse tree produced by the {@code queryTelemetryConstraint}
	 * labeled alternative in {@link EvitaQLParser#requireConstraint}.
	 * @param ctx the parse tree
	 */
	void exitQueryTelemetryConstraint(EvitaQLParser.QueryTelemetryConstraintContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#headConstraintList}.
	 * @param ctx the parse tree
	 */
	void enterHeadConstraintList(EvitaQLParser.HeadConstraintListContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#headConstraintList}.
	 * @param ctx the parse tree
	 */
	void exitHeadConstraintList(EvitaQLParser.HeadConstraintListContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#filterConstraintList}.
	 * @param ctx the parse tree
	 */
	void enterFilterConstraintList(EvitaQLParser.FilterConstraintListContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#filterConstraintList}.
	 * @param ctx the parse tree
	 */
	void exitFilterConstraintList(EvitaQLParser.FilterConstraintListContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#orderConstraintList}.
	 * @param ctx the parse tree
	 */
	void enterOrderConstraintList(EvitaQLParser.OrderConstraintListContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#orderConstraintList}.
	 * @param ctx the parse tree
	 */
	void exitOrderConstraintList(EvitaQLParser.OrderConstraintListContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#requireConstraintList}.
	 * @param ctx the parse tree
	 */
	void enterRequireConstraintList(EvitaQLParser.RequireConstraintListContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#requireConstraintList}.
	 * @param ctx the parse tree
	 */
	void exitRequireConstraintList(EvitaQLParser.RequireConstraintListContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#constraintListArgs}.
	 * @param ctx the parse tree
	 */
	void enterConstraintListArgs(EvitaQLParser.ConstraintListArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#constraintListArgs}.
	 * @param ctx the parse tree
	 */
	void exitConstraintListArgs(EvitaQLParser.ConstraintListArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#emptyArgs}.
	 * @param ctx the parse tree
	 */
	void enterEmptyArgs(EvitaQLParser.EmptyArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#emptyArgs}.
	 * @param ctx the parse tree
	 */
	void exitEmptyArgs(EvitaQLParser.EmptyArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#filterConstraintListArgs}.
	 * @param ctx the parse tree
	 */
	void enterFilterConstraintListArgs(EvitaQLParser.FilterConstraintListArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#filterConstraintListArgs}.
	 * @param ctx the parse tree
	 */
	void exitFilterConstraintListArgs(EvitaQLParser.FilterConstraintListArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#filterConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void enterFilterConstraintArgs(EvitaQLParser.FilterConstraintArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#filterConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void exitFilterConstraintArgs(EvitaQLParser.FilterConstraintArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#orderConstraintListArgs}.
	 * @param ctx the parse tree
	 */
	void enterOrderConstraintListArgs(EvitaQLParser.OrderConstraintListArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#orderConstraintListArgs}.
	 * @param ctx the parse tree
	 */
	void exitOrderConstraintListArgs(EvitaQLParser.OrderConstraintListArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#requireConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void enterRequireConstraintArgs(EvitaQLParser.RequireConstraintArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#requireConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void exitRequireConstraintArgs(EvitaQLParser.RequireConstraintArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#requireConstraintListArgs}.
	 * @param ctx the parse tree
	 */
	void enterRequireConstraintListArgs(EvitaQLParser.RequireConstraintListArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#requireConstraintListArgs}.
	 * @param ctx the parse tree
	 */
	void exitRequireConstraintListArgs(EvitaQLParser.RequireConstraintListArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#classifierArgs}.
	 * @param ctx the parse tree
	 */
	void enterClassifierArgs(EvitaQLParser.ClassifierArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#classifierArgs}.
	 * @param ctx the parse tree
	 */
	void exitClassifierArgs(EvitaQLParser.ClassifierArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#classifierWithValueArgs}.
	 * @param ctx the parse tree
	 */
	void enterClassifierWithValueArgs(EvitaQLParser.ClassifierWithValueArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#classifierWithValueArgs}.
	 * @param ctx the parse tree
	 */
	void exitClassifierWithValueArgs(EvitaQLParser.ClassifierWithValueArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#classifierWithOptionalValueArgs}.
	 * @param ctx the parse tree
	 */
	void enterClassifierWithOptionalValueArgs(EvitaQLParser.ClassifierWithOptionalValueArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#classifierWithOptionalValueArgs}.
	 * @param ctx the parse tree
	 */
	void exitClassifierWithOptionalValueArgs(EvitaQLParser.ClassifierWithOptionalValueArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#classifierWithValueListArgs}.
	 * @param ctx the parse tree
	 */
	void enterClassifierWithValueListArgs(EvitaQLParser.ClassifierWithValueListArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#classifierWithValueListArgs}.
	 * @param ctx the parse tree
	 */
	void exitClassifierWithValueListArgs(EvitaQLParser.ClassifierWithValueListArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#classifierWithBetweenValuesArgs}.
	 * @param ctx the parse tree
	 */
	void enterClassifierWithBetweenValuesArgs(EvitaQLParser.ClassifierWithBetweenValuesArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#classifierWithBetweenValuesArgs}.
	 * @param ctx the parse tree
	 */
	void exitClassifierWithBetweenValuesArgs(EvitaQLParser.ClassifierWithBetweenValuesArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#valueArgs}.
	 * @param ctx the parse tree
	 */
	void enterValueArgs(EvitaQLParser.ValueArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#valueArgs}.
	 * @param ctx the parse tree
	 */
	void exitValueArgs(EvitaQLParser.ValueArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#valueListArgs}.
	 * @param ctx the parse tree
	 */
	void enterValueListArgs(EvitaQLParser.ValueListArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#valueListArgs}.
	 * @param ctx the parse tree
	 */
	void exitValueListArgs(EvitaQLParser.ValueListArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#betweenValuesArgs}.
	 * @param ctx the parse tree
	 */
	void enterBetweenValuesArgs(EvitaQLParser.BetweenValuesArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#betweenValuesArgs}.
	 * @param ctx the parse tree
	 */
	void exitBetweenValuesArgs(EvitaQLParser.BetweenValuesArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#classifierListArgs}.
	 * @param ctx the parse tree
	 */
	void enterClassifierListArgs(EvitaQLParser.ClassifierListArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#classifierListArgs}.
	 * @param ctx the parse tree
	 */
	void exitClassifierListArgs(EvitaQLParser.ClassifierListArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#valueWithClassifierListArgs}.
	 * @param ctx the parse tree
	 */
	void enterValueWithClassifierListArgs(EvitaQLParser.ValueWithClassifierListArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#valueWithClassifierListArgs}.
	 * @param ctx the parse tree
	 */
	void exitValueWithClassifierListArgs(EvitaQLParser.ValueWithClassifierListArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#classifierWithFilterConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void enterClassifierWithFilterConstraintArgs(EvitaQLParser.ClassifierWithFilterConstraintArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#classifierWithFilterConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void exitClassifierWithFilterConstraintArgs(EvitaQLParser.ClassifierWithFilterConstraintArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#classifierWithOrderConstraintListArgs}.
	 * @param ctx the parse tree
	 */
	void enterClassifierWithOrderConstraintListArgs(EvitaQLParser.ClassifierWithOrderConstraintListArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#classifierWithOrderConstraintListArgs}.
	 * @param ctx the parse tree
	 */
	void exitClassifierWithOrderConstraintListArgs(EvitaQLParser.ClassifierWithOrderConstraintListArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#valueWithRequireConstraintListArgs}.
	 * @param ctx the parse tree
	 */
	void enterValueWithRequireConstraintListArgs(EvitaQLParser.ValueWithRequireConstraintListArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#valueWithRequireConstraintListArgs}.
	 * @param ctx the parse tree
	 */
	void exitValueWithRequireConstraintListArgs(EvitaQLParser.ValueWithRequireConstraintListArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#hierarchyWithinConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyWithinConstraintArgs(EvitaQLParser.HierarchyWithinConstraintArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#hierarchyWithinConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyWithinConstraintArgs(EvitaQLParser.HierarchyWithinConstraintArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#hierarchyWithinSelfConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyWithinSelfConstraintArgs(EvitaQLParser.HierarchyWithinSelfConstraintArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#hierarchyWithinSelfConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyWithinSelfConstraintArgs(EvitaQLParser.HierarchyWithinSelfConstraintArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#hierarchyWithinRootConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyWithinRootConstraintArgs(EvitaQLParser.HierarchyWithinRootConstraintArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#hierarchyWithinRootConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyWithinRootConstraintArgs(EvitaQLParser.HierarchyWithinRootConstraintArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#hierarchyWithinRootSelfConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyWithinRootSelfConstraintArgs(EvitaQLParser.HierarchyWithinRootSelfConstraintArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#hierarchyWithinRootSelfConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyWithinRootSelfConstraintArgs(EvitaQLParser.HierarchyWithinRootSelfConstraintArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#attributeSetExactArgs}.
	 * @param ctx the parse tree
	 */
	void enterAttributeSetExactArgs(EvitaQLParser.AttributeSetExactArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#attributeSetExactArgs}.
	 * @param ctx the parse tree
	 */
	void exitAttributeSetExactArgs(EvitaQLParser.AttributeSetExactArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#pageConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void enterPageConstraintArgs(EvitaQLParser.PageConstraintArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#pageConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void exitPageConstraintArgs(EvitaQLParser.PageConstraintArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#stripConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void enterStripConstraintArgs(EvitaQLParser.StripConstraintArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#stripConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void exitStripConstraintArgs(EvitaQLParser.StripConstraintArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#priceContentArgs}.
	 * @param ctx the parse tree
	 */
	void enterPriceContentArgs(EvitaQLParser.PriceContentArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#priceContentArgs}.
	 * @param ctx the parse tree
	 */
	void exitPriceContentArgs(EvitaQLParser.PriceContentArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#singleRefReferenceContentArgs}.
	 * @param ctx the parse tree
	 */
	void enterSingleRefReferenceContentArgs(EvitaQLParser.SingleRefReferenceContentArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#singleRefReferenceContentArgs}.
	 * @param ctx the parse tree
	 */
	void exitSingleRefReferenceContentArgs(EvitaQLParser.SingleRefReferenceContentArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#singleRefWithFilterReferenceContentArgs}.
	 * @param ctx the parse tree
	 */
	void enterSingleRefWithFilterReferenceContentArgs(EvitaQLParser.SingleRefWithFilterReferenceContentArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#singleRefWithFilterReferenceContentArgs}.
	 * @param ctx the parse tree
	 */
	void exitSingleRefWithFilterReferenceContentArgs(EvitaQLParser.SingleRefWithFilterReferenceContentArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#singleRefWithOrderReferenceContentArgs}.
	 * @param ctx the parse tree
	 */
	void enterSingleRefWithOrderReferenceContentArgs(EvitaQLParser.SingleRefWithOrderReferenceContentArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#singleRefWithOrderReferenceContentArgs}.
	 * @param ctx the parse tree
	 */
	void exitSingleRefWithOrderReferenceContentArgs(EvitaQLParser.SingleRefWithOrderReferenceContentArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#singleRefWithFilterAndOrderReferenceContentArgs}.
	 * @param ctx the parse tree
	 */
	void enterSingleRefWithFilterAndOrderReferenceContentArgs(EvitaQLParser.SingleRefWithFilterAndOrderReferenceContentArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#singleRefWithFilterAndOrderReferenceContentArgs}.
	 * @param ctx the parse tree
	 */
	void exitSingleRefWithFilterAndOrderReferenceContentArgs(EvitaQLParser.SingleRefWithFilterAndOrderReferenceContentArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#multipleRefsReferenceContentArgs}.
	 * @param ctx the parse tree
	 */
	void enterMultipleRefsReferenceContentArgs(EvitaQLParser.MultipleRefsReferenceContentArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#multipleRefsReferenceContentArgs}.
	 * @param ctx the parse tree
	 */
	void exitMultipleRefsReferenceContentArgs(EvitaQLParser.MultipleRefsReferenceContentArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#allRefsReferenceContentArgs}.
	 * @param ctx the parse tree
	 */
	void enterAllRefsReferenceContentArgs(EvitaQLParser.AllRefsReferenceContentArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#allRefsReferenceContentArgs}.
	 * @param ctx the parse tree
	 */
	void exitAllRefsReferenceContentArgs(EvitaQLParser.AllRefsReferenceContentArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#singleRequireHierarchyContentArgs}.
	 * @param ctx the parse tree
	 */
	void enterSingleRequireHierarchyContentArgs(EvitaQLParser.SingleRequireHierarchyContentArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#singleRequireHierarchyContentArgs}.
	 * @param ctx the parse tree
	 */
	void exitSingleRequireHierarchyContentArgs(EvitaQLParser.SingleRequireHierarchyContentArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#allRequiresHierarchyContentArgs}.
	 * @param ctx the parse tree
	 */
	void enterAllRequiresHierarchyContentArgs(EvitaQLParser.AllRequiresHierarchyContentArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#allRequiresHierarchyContentArgs}.
	 * @param ctx the parse tree
	 */
	void exitAllRequiresHierarchyContentArgs(EvitaQLParser.AllRequiresHierarchyContentArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#facetSummaryArgs}.
	 * @param ctx the parse tree
	 */
	void enterFacetSummaryArgs(EvitaQLParser.FacetSummaryArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#facetSummaryArgs}.
	 * @param ctx the parse tree
	 */
	void exitFacetSummaryArgs(EvitaQLParser.FacetSummaryArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#facetSummaryOfReferenceArgs}.
	 * @param ctx the parse tree
	 */
	void enterFacetSummaryOfReferenceArgs(EvitaQLParser.FacetSummaryOfReferenceArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#facetSummaryOfReferenceArgs}.
	 * @param ctx the parse tree
	 */
	void exitFacetSummaryOfReferenceArgs(EvitaQLParser.FacetSummaryOfReferenceArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#hierarchyStatisticsArgs}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyStatisticsArgs(EvitaQLParser.HierarchyStatisticsArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#hierarchyStatisticsArgs}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyStatisticsArgs(EvitaQLParser.HierarchyStatisticsArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#hierarchyRequireConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyRequireConstraintArgs(EvitaQLParser.HierarchyRequireConstraintArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#hierarchyRequireConstraintArgs}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyRequireConstraintArgs(EvitaQLParser.HierarchyRequireConstraintArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#hierarchyFromNodeArgs}.
	 * @param ctx the parse tree
	 */
	void enterHierarchyFromNodeArgs(EvitaQLParser.HierarchyFromNodeArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#hierarchyFromNodeArgs}.
	 * @param ctx the parse tree
	 */
	void exitHierarchyFromNodeArgs(EvitaQLParser.HierarchyFromNodeArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#fullHierarchyOfSelfArgs}.
	 * @param ctx the parse tree
	 */
	void enterFullHierarchyOfSelfArgs(EvitaQLParser.FullHierarchyOfSelfArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#fullHierarchyOfSelfArgs}.
	 * @param ctx the parse tree
	 */
	void exitFullHierarchyOfSelfArgs(EvitaQLParser.FullHierarchyOfSelfArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#basicHierarchyOfReferenceArgs}.
	 * @param ctx the parse tree
	 */
	void enterBasicHierarchyOfReferenceArgs(EvitaQLParser.BasicHierarchyOfReferenceArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#basicHierarchyOfReferenceArgs}.
	 * @param ctx the parse tree
	 */
	void exitBasicHierarchyOfReferenceArgs(EvitaQLParser.BasicHierarchyOfReferenceArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#basicHierarchyOfReferenceWithBehaviourArgs}.
	 * @param ctx the parse tree
	 */
	void enterBasicHierarchyOfReferenceWithBehaviourArgs(EvitaQLParser.BasicHierarchyOfReferenceWithBehaviourArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#basicHierarchyOfReferenceWithBehaviourArgs}.
	 * @param ctx the parse tree
	 */
	void exitBasicHierarchyOfReferenceWithBehaviourArgs(EvitaQLParser.BasicHierarchyOfReferenceWithBehaviourArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#fullHierarchyOfReferenceArgs}.
	 * @param ctx the parse tree
	 */
	void enterFullHierarchyOfReferenceArgs(EvitaQLParser.FullHierarchyOfReferenceArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#fullHierarchyOfReferenceArgs}.
	 * @param ctx the parse tree
	 */
	void exitFullHierarchyOfReferenceArgs(EvitaQLParser.FullHierarchyOfReferenceArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#fullHierarchyOfReferenceWithBehaviourArgs}.
	 * @param ctx the parse tree
	 */
	void enterFullHierarchyOfReferenceWithBehaviourArgs(EvitaQLParser.FullHierarchyOfReferenceWithBehaviourArgsContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#fullHierarchyOfReferenceWithBehaviourArgs}.
	 * @param ctx the parse tree
	 */
	void exitFullHierarchyOfReferenceWithBehaviourArgs(EvitaQLParser.FullHierarchyOfReferenceWithBehaviourArgsContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#positionalParameter}.
	 * @param ctx the parse tree
	 */
	void enterPositionalParameter(EvitaQLParser.PositionalParameterContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#positionalParameter}.
	 * @param ctx the parse tree
	 */
	void exitPositionalParameter(EvitaQLParser.PositionalParameterContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaQLParser#namedParameter}.
	 * @param ctx the parse tree
	 */
	void enterNamedParameter(EvitaQLParser.NamedParameterContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaQLParser#namedParameter}.
	 * @param ctx the parse tree
	 */
	void exitNamedParameter(EvitaQLParser.NamedParameterContext ctx);
	/**
	 * Enter a parse tree produced by the {@code positionalParameterVariadicClassifierTokens}
	 * labeled alternative in {@link EvitaQLParser#variadicClassifierTokens}.
	 * @param ctx the parse tree
	 */
	void enterPositionalParameterVariadicClassifierTokens(EvitaQLParser.PositionalParameterVariadicClassifierTokensContext ctx);
	/**
	 * Exit a parse tree produced by the {@code positionalParameterVariadicClassifierTokens}
	 * labeled alternative in {@link EvitaQLParser#variadicClassifierTokens}.
	 * @param ctx the parse tree
	 */
	void exitPositionalParameterVariadicClassifierTokens(EvitaQLParser.PositionalParameterVariadicClassifierTokensContext ctx);
	/**
	 * Enter a parse tree produced by the {@code namedParameterVariadicClassifierTokens}
	 * labeled alternative in {@link EvitaQLParser#variadicClassifierTokens}.
	 * @param ctx the parse tree
	 */
	void enterNamedParameterVariadicClassifierTokens(EvitaQLParser.NamedParameterVariadicClassifierTokensContext ctx);
	/**
	 * Exit a parse tree produced by the {@code namedParameterVariadicClassifierTokens}
	 * labeled alternative in {@link EvitaQLParser#variadicClassifierTokens}.
	 * @param ctx the parse tree
	 */
	void exitNamedParameterVariadicClassifierTokens(EvitaQLParser.NamedParameterVariadicClassifierTokensContext ctx);
	/**
	 * Enter a parse tree produced by the {@code explicitVariadicClassifierTokens}
	 * labeled alternative in {@link EvitaQLParser#variadicClassifierTokens}.
	 * @param ctx the parse tree
	 */
	void enterExplicitVariadicClassifierTokens(EvitaQLParser.ExplicitVariadicClassifierTokensContext ctx);
	/**
	 * Exit a parse tree produced by the {@code explicitVariadicClassifierTokens}
	 * labeled alternative in {@link EvitaQLParser#variadicClassifierTokens}.
	 * @param ctx the parse tree
	 */
	void exitExplicitVariadicClassifierTokens(EvitaQLParser.ExplicitVariadicClassifierTokensContext ctx);
	/**
	 * Enter a parse tree produced by the {@code positionalParameterClassifierToken}
	 * labeled alternative in {@link EvitaQLParser#classifierToken}.
	 * @param ctx the parse tree
	 */
	void enterPositionalParameterClassifierToken(EvitaQLParser.PositionalParameterClassifierTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code positionalParameterClassifierToken}
	 * labeled alternative in {@link EvitaQLParser#classifierToken}.
	 * @param ctx the parse tree
	 */
	void exitPositionalParameterClassifierToken(EvitaQLParser.PositionalParameterClassifierTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code namedParameterClassifierToken}
	 * labeled alternative in {@link EvitaQLParser#classifierToken}.
	 * @param ctx the parse tree
	 */
	void enterNamedParameterClassifierToken(EvitaQLParser.NamedParameterClassifierTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code namedParameterClassifierToken}
	 * labeled alternative in {@link EvitaQLParser#classifierToken}.
	 * @param ctx the parse tree
	 */
	void exitNamedParameterClassifierToken(EvitaQLParser.NamedParameterClassifierTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code stringClassifierToken}
	 * labeled alternative in {@link EvitaQLParser#classifierToken}.
	 * @param ctx the parse tree
	 */
	void enterStringClassifierToken(EvitaQLParser.StringClassifierTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code stringClassifierToken}
	 * labeled alternative in {@link EvitaQLParser#classifierToken}.
	 * @param ctx the parse tree
	 */
	void exitStringClassifierToken(EvitaQLParser.StringClassifierTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code positionalParameterVariadicValueTokens}
	 * labeled alternative in {@link EvitaQLParser#variadicValueTokens}.
	 * @param ctx the parse tree
	 */
	void enterPositionalParameterVariadicValueTokens(EvitaQLParser.PositionalParameterVariadicValueTokensContext ctx);
	/**
	 * Exit a parse tree produced by the {@code positionalParameterVariadicValueTokens}
	 * labeled alternative in {@link EvitaQLParser#variadicValueTokens}.
	 * @param ctx the parse tree
	 */
	void exitPositionalParameterVariadicValueTokens(EvitaQLParser.PositionalParameterVariadicValueTokensContext ctx);
	/**
	 * Enter a parse tree produced by the {@code namedParameterVariadicValueTokens}
	 * labeled alternative in {@link EvitaQLParser#variadicValueTokens}.
	 * @param ctx the parse tree
	 */
	void enterNamedParameterVariadicValueTokens(EvitaQLParser.NamedParameterVariadicValueTokensContext ctx);
	/**
	 * Exit a parse tree produced by the {@code namedParameterVariadicValueTokens}
	 * labeled alternative in {@link EvitaQLParser#variadicValueTokens}.
	 * @param ctx the parse tree
	 */
	void exitNamedParameterVariadicValueTokens(EvitaQLParser.NamedParameterVariadicValueTokensContext ctx);
	/**
	 * Enter a parse tree produced by the {@code explicitVariadicValueTokens}
	 * labeled alternative in {@link EvitaQLParser#variadicValueTokens}.
	 * @param ctx the parse tree
	 */
	void enterExplicitVariadicValueTokens(EvitaQLParser.ExplicitVariadicValueTokensContext ctx);
	/**
	 * Exit a parse tree produced by the {@code explicitVariadicValueTokens}
	 * labeled alternative in {@link EvitaQLParser#variadicValueTokens}.
	 * @param ctx the parse tree
	 */
	void exitExplicitVariadicValueTokens(EvitaQLParser.ExplicitVariadicValueTokensContext ctx);
	/**
	 * Enter a parse tree produced by the {@code positionalParameterValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void enterPositionalParameterValueToken(EvitaQLParser.PositionalParameterValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code positionalParameterValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void exitPositionalParameterValueToken(EvitaQLParser.PositionalParameterValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code namedParameterValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void enterNamedParameterValueToken(EvitaQLParser.NamedParameterValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code namedParameterValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void exitNamedParameterValueToken(EvitaQLParser.NamedParameterValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code stringValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void enterStringValueToken(EvitaQLParser.StringValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code stringValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void exitStringValueToken(EvitaQLParser.StringValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code intValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void enterIntValueToken(EvitaQLParser.IntValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code intValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void exitIntValueToken(EvitaQLParser.IntValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code floatValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void enterFloatValueToken(EvitaQLParser.FloatValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code floatValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void exitFloatValueToken(EvitaQLParser.FloatValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code booleanValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void enterBooleanValueToken(EvitaQLParser.BooleanValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code booleanValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void exitBooleanValueToken(EvitaQLParser.BooleanValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code dateValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void enterDateValueToken(EvitaQLParser.DateValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code dateValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void exitDateValueToken(EvitaQLParser.DateValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code timeValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void enterTimeValueToken(EvitaQLParser.TimeValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code timeValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void exitTimeValueToken(EvitaQLParser.TimeValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code dateTimeValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void enterDateTimeValueToken(EvitaQLParser.DateTimeValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code dateTimeValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void exitDateTimeValueToken(EvitaQLParser.DateTimeValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code offsetDateTimeValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void enterOffsetDateTimeValueToken(EvitaQLParser.OffsetDateTimeValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code offsetDateTimeValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void exitOffsetDateTimeValueToken(EvitaQLParser.OffsetDateTimeValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code floatNumberRangeValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void enterFloatNumberRangeValueToken(EvitaQLParser.FloatNumberRangeValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code floatNumberRangeValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void exitFloatNumberRangeValueToken(EvitaQLParser.FloatNumberRangeValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code intNumberRangeValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void enterIntNumberRangeValueToken(EvitaQLParser.IntNumberRangeValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code intNumberRangeValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void exitIntNumberRangeValueToken(EvitaQLParser.IntNumberRangeValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code dateTimeRangeValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void enterDateTimeRangeValueToken(EvitaQLParser.DateTimeRangeValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code dateTimeRangeValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void exitDateTimeRangeValueToken(EvitaQLParser.DateTimeRangeValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code enumValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void enterEnumValueToken(EvitaQLParser.EnumValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code enumValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void exitEnumValueToken(EvitaQLParser.EnumValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code multipleValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void enterMultipleValueToken(EvitaQLParser.MultipleValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code multipleValueToken}
	 * labeled alternative in {@link EvitaQLParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void exitMultipleValueToken(EvitaQLParser.MultipleValueTokenContext ctx);
}