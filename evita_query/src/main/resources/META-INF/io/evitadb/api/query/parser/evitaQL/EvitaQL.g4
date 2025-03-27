/**
 * ANTLRv4 grammar for Evita Query Language (EvitaQL) - parser and lexer
 */
grammar EvitaQL;

@header {
package io.evitadb.api.query.parser.grammar;
}

/**
 * Root rules
 */

queryUnit : query EOF ;
headConstraintListUnit : headConstraintList EOF ;
filterConstraintListUnit : filterConstraintList EOF ;
orderConstraintListUnit : orderConstraintList EOF ;
requireConstraintListUnit : requireConstraintList EOF ;
valueTokenUnit : valueToken EOF ;


/**
 * Whole query with constraints
 */
query : 'query' args = constraintListArgs ;

/**
 * Constraints rules.
 */

constraint
    : headConstraint
    | filterConstraint
    | orderConstraint
    | requireConstraint
    ;

headConstraint
    : 'head'                                args = headConstraintListArgs                                   # headContainerConstraint
    | 'collection'                          args = classifierArgs                                           # collectionConstraint
    | 'label'                               args = classifierWithValueArgs                                  # labelConstraint
    ;

filterConstraint
    : 'filterBy'                            args = filterConstraintListArgs                                 # filterByConstraint
    | 'filterGroupBy'                       args = filterConstraintListArgs                                 # filterGroupByConstraint
    | 'and'                                 (emptyArgs | args = filterConstraintListArgs)                   # andConstraint
    | 'or'                                  (emptyArgs | args = filterConstraintListArgs)                   # orConstraint
    | 'not'                                 args = filterConstraintArgs                                     # notConstraint
    | 'userFilter'                          (emptyArgs | args = filterConstraintListArgs)                   # userFilterConstraint
    | 'attributeEquals'                     args = classifierWithValueArgs                                  # attributeEqualsConstraint
    | 'attributeGreaterThan'                args = classifierWithValueArgs                                  # attributeGreaterThanConstraint
    | 'attributeGreaterThanEquals'          args = classifierWithValueArgs                                  # attributeGreaterThanEqualsConstraint
    | 'attributeLessThan'                   args = classifierWithValueArgs                                  # attributeLessThanConstraint
    | 'attributeLessThanEquals'             args = classifierWithValueArgs                                  # attributeLessThanEqualsConstraint
    | 'attributeBetween'                    args = classifierWithBetweenValuesArgs                          # attributeBetweenConstraint
    | 'attributeInSet'                      args = classifierWithOptionalValueListArgs                      # attributeInSetConstraint
    | 'attributeContains'                   args = classifierWithValueArgs                                  # attributeContainsConstraint
    | 'attributeStartsWith'                 args = classifierWithValueArgs                                  # attributeStartsWithConstraint
    | 'attributeEndsWith'                   args = classifierWithValueArgs                                  # attributeEndsWithConstraint
    | 'attributeEqualsTrue'                 args = classifierArgs                                           # attributeEqualsTrueConstraint
    | 'attributeEqualsFalse'                args = classifierArgs                                           # attributeEqualsFalseConstraint
    | 'attributeIs'                         args = classifierWithValueArgs                                  # attributeIsConstraint
    | 'attributeIsNull'                     args = classifierArgs                                           # attributeIsNullConstraint
    | 'attributeIsNotNull'                  args = classifierArgs                                           # attributeIsNotNullConstraint
    | 'attributeInRange'                    args = classifierWithValueArgs                                  # attributeInRangeConstraint
    | 'attributeInRangeNow'                 args = classifierArgs                                           # attributeInRangeNowConstraint
    | 'entityPrimaryKeyInSet'               (emptyArgs | args = valueListArgs)                              # entityPrimaryKeyInSetConstraint
    | 'entityLocaleEquals'                  args = valueArgs                                                # entityLocaleEqualsConstraint
    | 'priceInCurrency'                     args = valueArgs                                                # priceInCurrencyConstraint
    | 'priceInPriceLists'                   (emptyArgs | args = classifierListArgs)                         # priceInPriceListsConstraints
    | 'priceValidInNow'                     emptyArgs                                                       # priceValidInNowConstraint
    | 'priceValidIn'                        args = valueArgs                                                # priceValidInConstraint
    | 'priceBetween'                        args = betweenValuesArgs                                        # priceBetweenConstraint
    | 'facetHaving'                         args = classifierWithTwoFilterConstraintArgs                    # facetHavingConstraint
    | 'includingChildren'                   emptyArgs                                                       # facetIncludingChildrenConstraint
    | 'includingChildrenHaving'             args = filterConstraintArgs                                     # facetIncludingChildrenHavingConstraint
    | 'includingChildrenExcept'             args = filterConstraintArgs                                     # facetIncludingChildrenExceptConstraint
    | 'referenceHaving'                     (args = classifierArgs | classifierWithFilterConstraintArgs)    # referenceHavingConstraint
    | 'hierarchyWithin'                     args = hierarchyWithinConstraintArgs                            # hierarchyWithinConstraint
    | 'hierarchyWithinSelf'                 args = hierarchyWithinSelfConstraintArgs                        # hierarchyWithinSelfConstraint
    | 'hierarchyWithinRoot'                 args = hierarchyWithinRootConstraintArgs                        # hierarchyWithinRootConstraint
    | 'hierarchyWithinRootSelf'             (emptyArgs | args = hierarchyWithinRootSelfConstraintArgs)      # hierarchyWithinRootSelfConstraint
    | 'directRelation'                      emptyArgs                                                       # hierarchyDirectRelationConstraint
    | 'having'                              args = filterConstraintListArgs                                 # hierarchyHavingConstraint
    | 'excludingRoot'                       emptyArgs                                                       # hierarchyExcludingRootConstraint
    | 'excluding'                           args = filterConstraintListArgs                                 # hierarchyExcludingConstraint
    | 'entityHaving'                        args = filterConstraintArgs                                     # entityHavingConstraint
    | 'inScope'                             args = inScopeFilterArgs                                        # filterInScopeConstraint
    | 'scope'                               args = valueListArgs                                            # entityScopeConstraint
    ;

orderConstraint
    : 'orderBy'                             (emptyArgs | args = orderConstraintListArgs)                    # orderByConstraint
    | 'orderGroupBy'                        (emptyArgs | args = orderConstraintListArgs)                    # orderGroupByConstraint
    | 'attributeNatural'                    args = classifierWithOptionalValueArgs                          # attributeNaturalConstraint
    | 'attributeSetExact'                   args = attributeSetExactArgs                                    # attributeSetExactConstraint
    | 'attributeSetInFilter'                args = classifierArgs                                           # attributeSetInFilterConstraint
    | 'priceNatural'                        (emptyArgs | args = valueArgs)                                  # priceNaturalConstraint
    | 'priceDiscount'                       args = valueListArgs                                            # priceDiscountConstraint
    | 'random'                              emptyArgs                                                       # randomConstraint
    | 'randomWithSeed'                      args = valueArgs                                                # randomWithSeedConstraint
    | 'referenceProperty'                   args = classifierWithOrderConstraintListArgs                    # referencePropertyConstraint
    | 'traverseByEntityProperty'            args = traverseOrderConstraintListArgs                          # traverseByEntityPropertyConstraint
    | 'pickFirstByEntityProperty'           args = orderConstraintListArgs                                  # pickFirstByByEntityPropertyConstraint
    | 'entityPrimaryKeyNatural'             (emptyArgs | args = valueArgs)                                  # entityPrimaryKeyExactNatural
    | 'entityPrimaryKeyExact'               args = valueListArgs                                            # entityPrimaryKeyExactConstraint
    | 'entityPrimaryKeyInFilter'            emptyArgs                                                       # entityPrimaryKeyInFilterConstraint
    | 'entityProperty'                      args = orderConstraintListArgs                                  # entityPropertyConstraint
    | 'entityGroupProperty'                 args = orderConstraintListArgs                                  # entityGroupPropertyConstraint
    | 'segments'                            args = orderConstraintListArgs                                  # segmentsConstraint
    | 'segment'                             args = segmentArgs                                              # segmentConstraint
    | 'limit'                               args = valueArgs                                                # segmentLimitConstraint
    | 'inScope'                             args = inScopeOrderArgs                                         # orderInScopeConstraint
    ;

requireConstraint
    : 'require'                             (emptyArgs | args = requireConstraintListArgs)                  # requireContainerConstraint
    | 'page'                                args = pageConstraintArgs                                       # pageConstraint
    | 'strip'                               args = stripConstraintArgs                                      # stripConstraint
    | 'entityFetch'                         (emptyArgs | args = requireConstraintListArgs)                  # entityFetchConstraint
    | 'entityGroupFetch'                    (emptyArgs | args = requireConstraintListArgs)                  # entityGroupFetchConstraint
    | 'attributeContent'                    args = classifierListArgs                                       # attributeContentConstraint
    | 'attributeContentAll'                 emptyArgs                                                       # attributeContentConstraint
    | 'priceContent'                        args = priceContentArgs                                         # priceContentConstraint
    | 'priceContentAll'                     emptyArgs                                                       # priceContentAllConstraint
    | 'priceContentRespectingFilter'        (emptyArgs | args = valueListArgs)                              # priceContentRespectingFilterConstraint
    | 'associatedDataContent'               args = classifierListArgs                                       # associatedDataContentConstraint
    | 'associatedDataContentAll'            emptyArgs                                                       # associatedDataContentConstraint
    | 'referenceContentAll'                 (emptyArgs | args = allRefsReferenceContentArgs)                # allRefsReferenceContentConstraint
    | 'referenceContent'                    args = multipleRefsReferenceContentArgs                         # multipleRefsReferenceContentConstraint
    | 'referenceContent'                    args = singleRefReferenceContent1Args                           # singleRefReferenceContent1Constraint
    | 'referenceContent'                    args = singleRefReferenceContent2Args                           # singleRefReferenceContent2Constraint
    | 'referenceContent'                    args = singleRefReferenceContent3Args                           # singleRefReferenceContent3Constraint
    | 'referenceContent'                    args = singleRefReferenceContent4Args                           # singleRefReferenceContent4Constraint
    | 'referenceContent'                    args = singleRefReferenceContent5Args                           # singleRefReferenceContent5Constraint
    | 'referenceContent'                    args = singleRefReferenceContent6Args                           # singleRefReferenceContent6Constraint
    | 'referenceContent'                    args = singleRefReferenceContent7Args                           # singleRefReferenceContent7Constraint
    | 'referenceContent'                    args = singleRefReferenceContent8Args                           # singleRefReferenceContent8Constraint
    | 'referenceContentAllWithAttributes'   (emptyArgs | args = allRefsWithAttributesReferenceContent1Args) # allRefsWithAttributesReferenceContent1Constraint
    | 'referenceContentAllWithAttributes'   args = allRefsWithAttributesReferenceContent2Args               # allRefsWithAttributesReferenceContent2Constraint
    | 'referenceContentAllWithAttributes'   args = allRefsWithAttributesReferenceContent3Args               # allRefsWithAttributesReferenceContent3Constraint
    | 'referenceContentWithAttributes'      args = singleRefReferenceContent1Args                           # singleRefReferenceContentWithAttributes1Constraint
    | 'referenceContentWithAttributes'      args = singleRefReferenceContentWithAttributes0Args             # singleRefReferenceContentWithAttributes0Constraint
    | 'referenceContentWithAttributes'      args = singleRefReferenceContentWithAttributes1Args             # singleRefReferenceContentWithAttributes2Constraint
    | 'referenceContentWithAttributes'      args = singleRefReferenceContentWithAttributes2Args             # singleRefReferenceContentWithAttributes3Constraint
    | 'referenceContentWithAttributes'      args = singleRefReferenceContent3Args                           # singleRefReferenceContentWithAttributes4Constraint
    | 'referenceContentWithAttributes'      args = singleRefReferenceContentWithAttributes3Args             # singleRefReferenceContentWithAttributes5Constraint
    | 'referenceContentWithAttributes'      args = singleRefReferenceContentWithAttributes4Args             # singleRefReferenceContentWithAttributes6Constraint
    | 'referenceContentWithAttributes'      args = singleRefReferenceContent5Args                           # singleRefReferenceContentWithAttributes7Constraint
    | 'referenceContentWithAttributes'      args = singleRefReferenceContentWithAttributes5Args             # singleRefReferenceContentWithAttributes8Constraint
    | 'referenceContentWithAttributes'      args = singleRefReferenceContentWithAttributes6Args             # singleRefReferenceContentWithAttributes9Constraint
    | 'referenceContentWithAttributes'      args = singleRefReferenceContent7Args                           # singleRefReferenceContentWithAttributes10Constraint
    | 'referenceContentWithAttributes'      args = singleRefReferenceContentWithAttributes7Args             # singleRefReferenceContentWithAttributes11Constraint
    | 'referenceContentWithAttributes'      args = singleRefReferenceContentWithAttributes8Args             # singleRefReferenceContentWithAttributes12Constraint
    | 'hierarchyContent'                    emptyArgs                                                       # emptyHierarchyContentConstraint
    | 'hierarchyContent'                    args = singleRequireHierarchyContentArgs                        # singleRequireHierarchyContentConstraint
    | 'hierarchyContent'                    args = allRequiresHierarchyContentArgs                          # allRequiresHierarchyContentConstraint
    | 'priceType'                           args = valueArgs                                                # priceTypeConstraint
    | 'dataInLocalesAll'                    emptyArgs                                                       # dataInLocalesAllConstraint
    | 'dataInLocales'                       args = valueListArgs                                            # dataInLocalesConstraint
    | 'facetSummary'                        (emptyArgs | args = facetSummary1Args)                          # facetSummary1Constraint
    | 'facetSummary'                        args = facetSummary2Args                                        # facetSummary2Constraint
    | 'facetSummary'                        args = facetSummary3Args                                        # facetSummary3Constraint
    | 'facetSummary'                        args = facetSummary4Args                                        # facetSummary4Constraint
    | 'facetSummary'                        args = facetSummary5Args                                        # facetSummary5Constraint
    | 'facetSummary'                        args = facetSummary6Args                                        # facetSummary6Constraint
    | 'facetSummary'                        args = facetSummary7Args                                        # facetSummary7Constraint
    | 'facetSummaryOfReference'             args = classifierArgs                                           # facetSummaryOfReference1Constraint
    | 'facetSummaryOfReference'             args = facetSummaryOfReference2Args                             # facetSummaryOfReference2Constraint
    | 'facetGroupsConjunction'              args = facetGroupRelationArgs                                   # facetGroupsConjunctionConstraint
    | 'facetGroupsDisjunction'              args = facetGroupRelationArgs                                   # facetGroupsDisjunctionConstraint
    | 'facetGroupsNegation'                 args = facetGroupRelationArgs                                   # facetGroupsNegationConstraint
    | 'facetGroupsExclusivity'              args = facetGroupRelationArgs                                   # facetGroupsExclusivityConstraint
    | 'facetCalculationRules'               args = facetCalculationRulesArgs                                # facetCalculationRulesConstraint
    | 'attributeHistogram'                  args = attributeHistogramArgs                                   # attributeHistogramConstraint
    | 'priceHistogram'                      args = priceHistogramArgs                                       # priceHistogramConstraint
    | 'distance'                            args = valueArgs                                                # hierarchyDistanceConstraint
    | 'level'                               args = valueArgs                                                # hierarchyLevelConstraint
    | 'node'                                args = filterConstraintArgs                                     # hierarchyNodeConstraint
    | 'stopAt'                              args = requireConstraintArgs                                    # hierarchyStopAtConstraint
    | 'statistics'                          (emptyArgs | args = hierarchyStatisticsArgs)                    # hierarchyStatisticsConstraint
    | 'fromRoot'                            args = hierarchyRequireConstraintArgs                           # hierarchyFromRootConstraint
    | 'fromNode'                            args = hierarchyFromNodeArgs                                    # hierarchyFromNodeConstraint
    | 'children'                            args = hierarchyRequireConstraintArgs                           # hierarchyChildrenConstraint
    | 'siblings'                            emptyArgs                                                       # emptyHierarchySiblingsConstraint
    | 'siblings'                            args = requireConstraintListArgs                                # basicHierarchySiblingsConstraint
    | 'siblings'                            args = hierarchyRequireConstraintArgs                           # fullHierarchySiblingsConstraint
    | 'spacing'                             args = spacingRequireConstraintArgs                             # spacingConstraint
    | 'gap'                                 args = gapRequireConstraintArgs                                 # gapConstraint
    | 'parents'                             args = hierarchyRequireConstraintArgs                           # hierarchyParentsConstraint
    | 'hierarchyOfSelf'                     args = requireConstraintListArgs                                # basicHierarchyOfSelfConstraint
    | 'hierarchyOfSelf'                     args = fullHierarchyOfSelfArgs                                  # fullHierarchyOfSelfConstraint
    | 'hierarchyOfReference'                args = basicHierarchyOfReferenceArgs                            # basicHierarchyOfReferenceConstraint
    | 'hierarchyOfReference'                args = basicHierarchyOfReferenceWithBehaviourArgs               # basicHierarchyOfReferenceWithBehaviourConstraint
    | 'hierarchyOfReference'                args = fullHierarchyOfReferenceArgs                             # fullHierarchyOfReferenceConstraint
    | 'hierarchyOfReference'                args = fullHierarchyOfReferenceWithBehaviourArgs                # fullHierarchyOfReferenceWithBehaviourConstraint
    | 'queryTelemetry'                      emptyArgs                                                       # queryTelemetryConstraint
    | 'inScope'                             args = inScopeRequireArgs                                       # requireInScopeConstraint
    ;

headConstraintList : constraints += headConstraint (ARGS_DELIMITER constraints += headConstraint)* ;
filterConstraintList : constraints += filterConstraint (ARGS_DELIMITER constraints += filterConstraint)* ;
orderConstraintList : constraints += orderConstraint (ARGS_DELIMITER constraints += orderConstraint)* ;
requireConstraintList : constraints += requireConstraint (ARGS_DELIMITER constraints += requireConstraint)* ;


/**
 * Arguments syntax rules for query and constraints.
 * Used for better reusability and clearer generated contexts' structure ("args" label).
 */

argsOpening :                                       ARGS_OPENING ;

argsClosing :                                       (ARGS_DELIMITER)? ARGS_CLOSING ;

constraintListArgs :                                argsOpening constraints += constraint (ARGS_DELIMITER constraints += constraint)* argsClosing ;

emptyArgs :                                         argsOpening argsClosing ;

headConstraintListArgs :                            argsOpening constraints += headConstraint (ARGS_DELIMITER constraints += headConstraint)* argsClosing ;

filterConstraintListArgs :                          argsOpening constraints += filterConstraint (ARGS_DELIMITER constraints += filterConstraint)* argsClosing ;

filterConstraintArgs :                              argsOpening filter = filterConstraint argsClosing ;

traverseOrderConstraintListArgs :                   argsOpening(
                                                        (traversalMode = valueToken) |
                                                        ((traversalMode = valueToken ARGS_DELIMITER)? constraints += orderConstraint (ARGS_DELIMITER constraints += orderConstraint)*)
                                                    ) argsClosing ;

orderConstraintListArgs :                           argsOpening constraints += orderConstraint (ARGS_DELIMITER constraints += orderConstraint)* argsClosing ;

requireConstraintArgs :                             argsOpening requirement = requireConstraint argsClosing ;

requireConstraintListArgs :                         argsOpening requirements += requireConstraint (ARGS_DELIMITER requirements += requireConstraint)* argsClosing ;

classifierArgs :                                    argsOpening classifier = valueToken argsClosing ;

classifierWithValueArgs :                           argsOpening classifier = valueToken ARGS_DELIMITER value = valueToken argsClosing ;

classifierWithOptionalValueArgs :                   argsOpening classifier = valueToken (ARGS_DELIMITER value = valueToken)? argsClosing ;

classifierWithValueListArgs :                       argsOpening classifier = valueToken ARGS_DELIMITER values = variadicValueTokens argsClosing ;

classifierWithOptionalValueListArgs :               argsOpening classifier = valueToken (ARGS_DELIMITER values = variadicValueTokens)? argsClosing ;

classifierWithBetweenValuesArgs :                   argsOpening classifier = valueToken ARGS_DELIMITER valueFrom = valueToken ARGS_DELIMITER valueTo = valueToken argsClosing ;

valueArgs :                                         argsOpening value = valueToken argsClosing ;

valueListArgs :                                     argsOpening values = variadicValueTokens argsClosing ;

betweenValuesArgs :                                 argsOpening valueFrom = valueToken ARGS_DELIMITER valueTo = valueToken argsClosing ;

classifierListArgs :                                argsOpening classifiers = variadicValueTokens argsClosing ;

classifierWithFilterConstraintArgs :                argsOpening classifier = valueToken ARGS_DELIMITER filter = filterConstraint argsClosing ;

classifierWithTwoFilterConstraintArgs :             argsOpening classifier = valueToken ARGS_DELIMITER filter1 = filterConstraint (ARGS_DELIMITER filter2 = filterConstraint)? argsClosing ;

facetGroupRelationArgs :                            argsOpening classifier = valueToken (ARGS_DELIMITER facetGroupRelationLevel = valueToken)? (ARGS_DELIMITER filter = filterConstraint)? argsClosing ;

facetCalculationRulesArgs :                         argsOpening facetsWithSameGroup = valueToken ARGS_DELIMITER facetsWithDifferentGroups = valueToken argsClosing ;

classifierWithOrderConstraintListArgs :             argsOpening classifier = valueToken (ARGS_DELIMITER constrains += orderConstraint)+ argsClosing ;

hierarchyWithinConstraintArgs :                     argsOpening classifier = valueToken ARGS_DELIMITER ofParent = filterConstraint (ARGS_DELIMITER constrains += filterConstraint)* argsClosing ;

hierarchyWithinSelfConstraintArgs :                 argsOpening ofParent = filterConstraint (ARGS_DELIMITER constrains += filterConstraint)* argsClosing ;

hierarchyWithinRootConstraintArgs :                 argsOpening (classifier = valueToken | (classifier = valueToken (ARGS_DELIMITER constrains += filterConstraint)*)) argsClosing ;

hierarchyWithinRootSelfConstraintArgs :             argsOpening constrains += filterConstraint (ARGS_DELIMITER constrains += filterConstraint)* argsClosing ;

attributeSetExactArgs :                             argsOpening attributeName = valueToken ARGS_DELIMITER attributeValues = variadicValueTokens argsClosing ;

pageConstraintArgs :                                argsOpening pageNumber = valueToken ARGS_DELIMITER pageSize = valueToken (ARGS_DELIMITER constrain = requireConstraint)? argsClosing ;

stripConstraintArgs :                               argsOpening offset = valueToken ARGS_DELIMITER limit = valueToken argsClosing ;

priceContentArgs :                                  argsOpening contentMode = valueToken (ARGS_DELIMITER priceLists = variadicValueTokens)? argsClosing ;

singleRefReferenceContent1Args :                    argsOpening (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifier = valueToken argsClosing ;

singleRefReferenceContent2Args :                    argsOpening (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifier = valueToken ARGS_DELIMITER entityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint (ARGS_DELIMITER requirement = requireConstraint)? argsClosing ;

singleRefReferenceContent3Args :                    argsOpening (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifier = valueToken ARGS_DELIMITER filterBy = filterConstraint (ARGS_DELIMITER requirement = requireConstraint)? argsClosing ;

singleRefReferenceContent4Args :                    argsOpening (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifier = valueToken ARGS_DELIMITER filterBy = filterConstraint ARGS_DELIMITER entityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint (ARGS_DELIMITER requirement = requireConstraint)? argsClosing ;

singleRefReferenceContent5Args :                    argsOpening (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifier = valueToken ARGS_DELIMITER orderBy = orderConstraint (ARGS_DELIMITER requirement = requireConstraint)? argsClosing ;

singleRefReferenceContent6Args :                    argsOpening (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifier = valueToken ARGS_DELIMITER orderBy = orderConstraint ARGS_DELIMITER entityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint (ARGS_DELIMITER requirement = requireConstraint)? argsClosing ;

singleRefReferenceContent7Args :                    argsOpening (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifier = valueToken ARGS_DELIMITER filterBy = filterConstraint ARGS_DELIMITER orderBy = orderConstraint (ARGS_DELIMITER requirement = requireConstraint)? argsClosing ;

singleRefReferenceContent8Args :                    argsOpening (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifier = valueToken ARGS_DELIMITER filterBy = filterConstraint ARGS_DELIMITER orderBy = orderConstraint ARGS_DELIMITER entityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint (ARGS_DELIMITER requirement = requireConstraint)? argsClosing ;

singleRefReferenceContentWithAttributes0Args :      argsOpening (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifier = valueToken ARGS_DELIMITER requirement = requireConstraint argsClosing;

singleRefReferenceContentWithAttributes1Args :      argsOpening (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifier = valueToken ARGS_DELIMITER requirement1 = requireConstraint ARGS_DELIMITER requirement2 = requireConstraint argsClosing ;

singleRefReferenceContentWithAttributes2Args :      argsOpening (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifier = valueToken ARGS_DELIMITER attributeContent = requireConstraint ARGS_DELIMITER entityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint (ARGS_DELIMITER requirement = requireConstraint)? argsClosing ;

singleRefReferenceContentWithAttributes3Args :      argsOpening (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifier = valueToken ARGS_DELIMITER filterBy = filterConstraint ARGS_DELIMITER requirement1 = requireConstraint ARGS_DELIMITER requirement2 = requireConstraint argsClosing ;

singleRefReferenceContentWithAttributes4Args :      argsOpening (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifier = valueToken ARGS_DELIMITER filterBy = filterConstraint ARGS_DELIMITER attributeContent = requireConstraint ARGS_DELIMITER entityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint (ARGS_DELIMITER requirement = requireConstraint)? argsClosing ;

singleRefReferenceContentWithAttributes5Args :      argsOpening (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifier = valueToken ARGS_DELIMITER orderBy = orderConstraint ARGS_DELIMITER requirement1 = requireConstraint ARGS_DELIMITER requirement2 = requireConstraint argsClosing ;

singleRefReferenceContentWithAttributes6Args :      argsOpening (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifier = valueToken ARGS_DELIMITER orderBy = orderConstraint ARGS_DELIMITER attributeContent = requireConstraint ARGS_DELIMITER entityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint (ARGS_DELIMITER requirement = requireConstraint)? argsClosing ;

singleRefReferenceContentWithAttributes7Args :      argsOpening (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifier = valueToken ARGS_DELIMITER filterBy = filterConstraint ARGS_DELIMITER orderBy = orderConstraint ARGS_DELIMITER requirement1 = requireConstraint ARGS_DELIMITER requirement2 = requireConstraint argsClosing ;

singleRefReferenceContentWithAttributes8Args :      argsOpening (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifier = valueToken ARGS_DELIMITER filterBy = filterConstraint ARGS_DELIMITER orderBy = orderConstraint ARGS_DELIMITER attributeContent = requireConstraint ARGS_DELIMITER entityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint (ARGS_DELIMITER requirement = requireConstraint)? argsClosing ;

multipleRefsReferenceContentArgs :                  argsOpening (
                                                        ((managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifiers = variadicValueTokens) |
                                                        ((managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifiers = variadicValueTokens ARGS_DELIMITER requirement = requireConstraint) |
                                                        ((managedReferencesBehaviour = valueToken ARGS_DELIMITER)? classifiers = variadicValueTokens ARGS_DELIMITER entityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint)
                                                    ) argsClosing ;

allRefsReferenceContentArgs :                       argsOpening (
                                                        (managedReferencesBehaviour = valueToken) |
                                                        ((managedReferencesBehaviour = valueToken ARGS_DELIMITER)? requirement = requireConstraint) |
                                                        ((managedReferencesBehaviour = valueToken ARGS_DELIMITER)? entityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint)
                                                    ) argsClosing ;

allRefsWithAttributesReferenceContent1Args :        argsOpening (
                                                        (managedReferencesBehaviour = valueToken) |
                                                        (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? requirement = requireConstraint
                                                    ) argsClosing ;

allRefsWithAttributesReferenceContent2Args :        argsOpening (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? requirement1 = requireConstraint ARGS_DELIMITER requirement2 = requireConstraint argsClosing ;

allRefsWithAttributesReferenceContent3Args :        argsOpening (managedReferencesBehaviour = valueToken ARGS_DELIMITER)? attributeContent = requireConstraint ARGS_DELIMITER entityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint (ARGS_DELIMITER requirement = requireConstraint)? argsClosing ;

singleRequireHierarchyContentArgs :                 argsOpening requirement = requireConstraint argsClosing ;

allRequiresHierarchyContentArgs :                   argsOpening stopAt = requireConstraint ARGS_DELIMITER entityRequirement = requireConstraint argsClosing ;

facetSummary1Args :                                 argsOpening depth = valueToken argsClosing ;

facetSummary2Args :                                 argsOpening depth = valueToken ARGS_DELIMITER filter = facetSummaryFilterArgs (ARGS_DELIMITER order = facetSummaryOrderArgs)? (ARGS_DELIMITER requirements = facetSummaryRequirementsArgs)? argsClosing ;

facetSummary3Args :                                 argsOpening depth = valueToken ARGS_DELIMITER order = facetSummaryOrderArgs (ARGS_DELIMITER requirements = facetSummaryRequirementsArgs)? argsClosing ;

facetSummary4Args :                                 argsOpening depth = valueToken ARGS_DELIMITER requirements = facetSummaryRequirementsArgs argsClosing ;

facetSummary5Args :                                 argsOpening filter = facetSummaryFilterArgs (ARGS_DELIMITER order = facetSummaryOrderArgs)? (ARGS_DELIMITER requirements = facetSummaryRequirementsArgs)? argsClosing ;

facetSummary6Args :                                 argsOpening order = facetSummaryOrderArgs (ARGS_DELIMITER requirements = facetSummaryRequirementsArgs)? argsClosing ;

facetSummary7Args :                                 argsOpening requirements = facetSummaryRequirementsArgs argsClosing ;

facetSummaryOfReference2Args :                      argsOpening referenceName = valueToken (ARGS_DELIMITER depth = valueToken)? (ARGS_DELIMITER filter = facetSummaryFilterArgs)? (ARGS_DELIMITER order = facetSummaryOrderArgs)? (ARGS_DELIMITER requirements = facetSummaryRequirementsArgs)? argsClosing ;

facetSummaryRequirementsArgs :                      (
                                                        (requirement = requireConstraint) |
                                                        (facetEntityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint)
                                                    ) ;

facetSummaryFilterArgs :                            (
                                                        (filterBy = filterConstraint) |
                                                        (filterBy = filterConstraint ARGS_DELIMITER filterGroupBy = filterConstraint)
                                                    ) ;

facetSummaryOrderArgs :                             (
                                                        (orderBy = orderConstraint) |
                                                        (orderBy = orderConstraint ARGS_DELIMITER orderGroupBy = orderConstraint)
                                                    ) ;

attributeHistogramArgs :                            argsOpening requestedBucketCount = valueToken ARGS_DELIMITER values = variadicValueTokens argsClosing ;

priceHistogramArgs :                                argsOpening requestedBucketCount = valueToken (ARGS_DELIMITER behaviour = valueToken)? argsClosing ;

hierarchyStatisticsArgs :                           argsOpening settings = variadicValueTokens argsClosing ;

hierarchyRequireConstraintArgs :                    argsOpening outputName = valueToken (ARGS_DELIMITER requirements += requireConstraint)* argsClosing ;

hierarchyFromNodeArgs :                             argsOpening outputName = valueToken ARGS_DELIMITER node = requireConstraint (ARGS_DELIMITER requirements += requireConstraint)* argsClosing ;

fullHierarchyOfSelfArgs :                           argsOpening orderBy = orderConstraint (ARGS_DELIMITER requirements += requireConstraint)+ argsClosing;

// TOBEDONE LHO https://github.com/FgForrest/evitaDB/issues/155 support for multiple reference names

basicHierarchyOfReferenceArgs :                     argsOpening referenceName = valueToken (ARGS_DELIMITER requirements += requireConstraint)+ argsClosing ;

basicHierarchyOfReferenceWithBehaviourArgs :        argsOpening referenceName = valueToken ARGS_DELIMITER emptyHierarchicalEntityBehaviour = valueToken (ARGS_DELIMITER requirements += requireConstraint)+ argsClosing ;

fullHierarchyOfReferenceArgs :                      argsOpening referenceName = valueToken ARGS_DELIMITER orderBy = orderConstraint (ARGS_DELIMITER requirements += requireConstraint)+ argsClosing ;

fullHierarchyOfReferenceWithBehaviourArgs :         argsOpening referenceName = valueToken ARGS_DELIMITER emptyHierarchicalEntityBehaviour = valueToken ARGS_DELIMITER orderBy = orderConstraint (ARGS_DELIMITER requirements += requireConstraint)+ argsClosing ;

spacingRequireConstraintArgs :                      argsOpening constraints += requireConstraint (ARGS_DELIMITER constraints += requireConstraint)* argsClosing ;

gapRequireConstraintArgs :                          argsOpening size = valueToken ARGS_DELIMITER expression = valueToken argsClosing ;

segmentArgs:                                        argsOpening (entityHaving = filterConstraint ARGS_DELIMITER)? orderBy = orderConstraint (ARGS_DELIMITER limit = orderConstraint)? argsClosing ;

inScopeFilterArgs:                                  argsOpening scope = valueToken (ARGS_DELIMITER filterConstraints += filterConstraint)* argsClosing ;

inScopeOrderArgs:                                   argsOpening scope = valueToken (ARGS_DELIMITER orderConstraints += orderConstraint)* argsClosing ;

inScopeRequireArgs:                                 argsOpening scope = valueToken (ARGS_DELIMITER requireConstraints += requireConstraint)* argsClosing ;

/**
 * Parameters values
 */

positionalParameter : POSITIONAL_PARAMETER ;
namedParameter : NAMED_PARAMETER ;


/**
 * Value rules representing any value supported by Evita data types
 */

variadicValueTokens
    : positionalParameter                                                                                 # positionalParameterVariadicValueTokens
    | namedParameter                                                                                      # namedParameterVariadicValueTokens
    | valueTokens += valueToken (ARGS_DELIMITER valueTokens += valueToken)*                               # explicitVariadicValueTokens
    ;

valueToken
    : positionalParameter                                                                                 # positionalParameterValueToken
    | namedParameter                                                                                      # namedParameterValueToken
    | STRING                                                                                              # stringValueToken
    | INT                                                                                                 # intValueToken
    | FLOAT                                                                                               # floatValueToken
    | BOOLEAN                                                                                             # booleanValueToken
    | DATE                                                                                                # dateValueToken
    | TIME                                                                                                # timeValueToken
    | DATE_TIME                                                                                           # dateTimeValueToken
    | OFFSET_DATE_TIME                                                                                    # offsetDateTimeValueToken
    | FLOAT_NUMBER_RANGE                                                                                  # floatNumberRangeValueToken
    | INT_NUMBER_RANGE                                                                                    # intNumberRangeValueToken
    | DATE_TIME_RANGE                                                                                     # dateTimeRangeValueToken
    | UUID                                                                                                # uuidValueToken
    | ENUM                                                                                                # enumValueToken
    ;


/**
 * Value and misc tokens
 */

// special generic literal that is resolved to actual value after parsing from external queue of values
POSITIONAL_PARAMETER : '?' ;

// special generic literal that is resolved to actual value after parsing from external map of values
NAMED_PARAMETER : '@' [a-z] [a-zA-Z0-9]* ;

STRING
    : '"' (STRING_DOUBLE_QUOTATION_ESC | STRING_DOUBLE_QUOTATION_SAFECODEPOINT)* '"'
    | '\'' (STRING_SINGLE_QUOTATION_ESC | STRING_SINGLE_QUOTATION_SAFECODEPOINT)* '\''
    ;

fragment STRING_DOUBLE_QUOTATION_ESC
    : '\\' (["\\/bfnrt] | STRING_UNICODE)
    ;

fragment STRING_SINGLE_QUOTATION_ESC
    : '\\' (['\\/bfnrt] | STRING_UNICODE)
    ;

fragment STRING_UNICODE
    : 'u' STRING_HEX STRING_HEX STRING_HEX STRING_HEX
    ;

fragment STRING_HEX
    : [0-9a-fA-F]
    ;

fragment STRING_DOUBLE_QUOTATION_SAFECODEPOINT
    : ~ ["\\\u0000-\u001F]
    ;

fragment STRING_SINGLE_QUOTATION_SAFECODEPOINT
    : ~ ['\\\u0000-\u001F]
    ;

INT : '-'? [0-9]+ ;

FLOAT : '-'? [0-9]* '.' [0-9]+ ;

BOOLEAN
    : 'false'
    | 'true'
    ;

DATE : [0-9][0-9][0-9][0-9] '-' [0-9][0-9] '-' [0-9][0-9] ;

TIME : [0-9][0-9] ':' [0-9][0-9] ':' [0-9][0-9] ('.' [0-9]+)? ;

DATE_TIME : DATE 'T' TIME ;

OFFSET_DATE_TIME : DATE_TIME ('+'|'-') [0-9][0-9] ':' [0-9][0-9] ;

FLOAT_NUMBER_RANGE : '[' FLOAT? ',' FLOAT? ']' ;

INT_NUMBER_RANGE : '[' INT? ',' INT? ']' ;

DATE_TIME_RANGE : '[' OFFSET_DATE_TIME? ',' OFFSET_DATE_TIME? ']' ;

UUID : [a-z0-9]+ '-' [a-z0-9]+ '-' [a-z0-9]+ '-' [a-z0-9]+ '-' [a-z0-9]+;

ENUM : [A-Z]+ ('_' [A-Z]+)* ;


/**
 * General delimiter tokens
 */

ARGS_OPENING : '(' ;

ARGS_CLOSING : ')' ;

ARGS_DELIMITER : ',' ;


/**
 * Miscellaneous tokens
 */

COMMENT : '//' ~[\r\n]* -> channel(HIDDEN) ;

WHITESPACE : [ \r\t\n]+ -> channel(HIDDEN) ;

UNEXPECTED_CHAR : . ;
