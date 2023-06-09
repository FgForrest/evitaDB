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
classifierTokenUnit : classifierToken EOF ;
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
    : 'collection'                      args = classifierArgs                                       # collectionConstraint
    ;

filterConstraint
    : 'filterBy'                        args = filterConstraintListArgs                             # filterByConstraint
    | 'filterGroupBy'                   args = filterConstraintListArgs                             # filterGroupByConstraint
    | 'and'                             (emptyArgs | args = filterConstraintListArgs)               # andConstraint
    | 'or'                              (emptyArgs | args = filterConstraintListArgs)               # orConstraint
    | 'not'                             args = filterConstraintArgs                                 # notConstraint
    | 'userFilter'                      (emptyArgs | args = filterConstraintListArgs)               # userFilterConstraint
    | 'attributeEquals'                 args = classifierWithValueArgs                              # attributeEqualsConstraint
    | 'attributeGreaterThan'            args = classifierWithValueArgs                              # attributeGreaterThanConstraint
    | 'attributeGreaterThanEquals'      args = classifierWithValueArgs                              # attributeGreaterThanEqualsConstraint
    | 'attributeLessThan'               args = classifierWithValueArgs                              # attributeLessThanConstraint
    | 'attributeLessThanEquals'         args = classifierWithValueArgs                              # attributeLessThanEqualsConstraint
    | 'attributeBetween'                args = classifierWithBetweenValuesArgs                      # attributeBetweenConstraint
    | 'attributeInSet'                  args = classifierWithValueListArgs                          # attributeInSetConstraint
    | 'attributeContains'               args = classifierWithValueArgs                              # attributeContainsConstraint
    | 'attributeStartsWith'             args = classifierWithValueArgs                              # attributeStartsWithConstraint
    | 'attributeEndsWith'               args = classifierWithValueArgs                              # attributeEndsWithConstraint
    | 'attributeEqualsTrue'             args = classifierArgs                                       # attributeEqualsTrueConstraint
    | 'attributeEqualsFalse'            args = classifierArgs                                       # attributeEqualsFalseConstraint
    | 'attributeIs'                     args = classifierWithValueArgs                              # attributeIsConstraint
    | 'attributeIsNull'                 args = classifierArgs                                       # attributeIsNullConstraint
    | 'attributeIsNotNull'              args = classifierArgs                                       # attributeIsNotNullConstraint
    | 'attributeInRange'                args = classifierWithValueArgs                              # attributeInRangeConstraint
    | 'entityPrimaryKeyInSet'           args = valueListArgs                                        # entityPrimaryKeyInSetConstraint
    | 'entityLocaleEquals'              args = valueArgs                                            # entityLocaleEqualsConstraint
    | 'priceInCurrency'                 args = valueArgs                                            # priceInCurrencyConstraint
    | 'priceInPriceLists'               (emptyArgs | args = classifierListArgs)                     # priceInPriceListsConstraints
    | 'priceValidInNow'                 emptyArgs                                                   # priceValidInNowConstraint
    | 'priceValidIn'                    args = valueArgs                                            # priceValidInConstraint
    | 'priceBetween'                    args = betweenValuesArgs                                    # priceBetweenConstraint
    | 'facetHaving'                     args = classifierWithFilterConstraintArgs                   # facetHavingConstraint
    | 'referenceHaving'                 args = classifierWithFilterConstraintArgs                   # referenceHavingConstraint
    | 'hierarchyWithin'                 args = hierarchyWithinConstraintArgs                        # hierarchyWithinConstraint
    | 'hierarchyWithinSelf'             args = hierarchyWithinSelfConstraintArgs                    # hierarchyWithinSelfConstraint
    | 'hierarchyWithinRoot'             args = hierarchyWithinRootConstraintArgs                    # hierarchyWithinRootConstraint
    | 'hierarchyWithinRootSelf'         (emptyArgs | args = hierarchyWithinRootSelfConstraintArgs)  # hierarchyWithinRootSelfConstraint
    | 'directRelation'                  emptyArgs                                                   # hierarchyDirectRelationConstraint
    | 'having'                          args = filterConstraintListArgs                             # hierarchyHavingConstraint
    | 'excludingRoot'                   emptyArgs                                                   # hierarchyExcludingRootConstraint
    | 'excluding'                       args = filterConstraintListArgs                             # hierarchyExcludingConstraint
    | 'entityHaving'                    args = filterConstraintArgs                                 # entityHavingConstraint
    ;

orderConstraint
    : 'orderBy'                         (emptyArgs | args = orderConstraintListArgs)                # orderByConstraint
    | 'orderGroupBy'                    (emptyArgs | args = orderConstraintListArgs)                # orderGroupByConstraint
    | 'attributeNatural'                args = classifierWithOptionalValueArgs                      # attributeNaturalConstraint
    | 'attributeSetExact'               args = attributeSetExactArgs                                # attributeSetExactConstraint
    | 'attributeSetInFilter'            args = classifierArgs                                       # attributeSetInFilterConstraint
    | 'priceNatural'                    (emptyArgs | args = valueArgs)                              # priceNaturalConstraint
    | 'random'                          emptyArgs                                                   # randomConstraint
    | 'referenceProperty'               args = classifierWithOrderConstraintListArgs                # referencePropertyConstraint
    | 'entityPrimaryKeyExact'           args = valueListArgs                                        # entityPrimaryKeyExactConstraint
    | 'entityPrimaryKeyInFilter'        emptyArgs                                                   # entityPrimaryKeyInFilterConstraint
    | 'entityProperty'                  args = orderConstraintListArgs                              # entityPropertyConstraint
    ;

requireConstraint
    : 'require'                         (emptyArgs | args = requireConstraintListArgs)              # requireContainerConstraint
    | 'page'                            args = pageConstraintArgs                                   # pageConstraint
    | 'strip'                           args = stripConstraintArgs                                  # stripConstraint
    | 'entityFetch'                     (emptyArgs | args = requireConstraintListArgs)              # entityFetchConstraint
    | 'entityGroupFetch'                (emptyArgs | args = requireConstraintListArgs)              # entityGroupFetchConstraint
    | 'attributeContent'                args = classifierListArgs                                   # attributeContentConstraint
    | 'attributeContentAll'             emptyArgs                                                   # attributeContentConstraint
    | 'priceContent'                    args = priceContentArgs                                     # priceContentConstraint
    | 'priceContentAll'                 emptyArgs                                                   # priceContentAllConstraint
    | 'priceContentRespectingFilter'    (emptyArgs | args = valueListArgs)                          # priceContentRespectingFilterConstraint
    | 'associatedDataContent'           args = classifierListArgs                                   # associatedDataContentConstraint
    | 'associatedDataContentAll'        emptyArgs                                                   # associatedDataContentConstraint
    | 'referenceContentAll'             (emptyArgs | args = allRefsReferenceContentArgs)            # allRefsReferenceContentConstraint
    | 'referenceContent'                args = multipleRefsReferenceContentArgs                     # multipleRefsReferenceContentConstraint
    | 'referenceContent'                args = singleRefReferenceContentArgs                        # singleRefReferenceContentConstraint
    | 'referenceContent'                args = singleRefWithFilterReferenceContentArgs              # singleRefWithFilterReferenceContentConstraint
    | 'referenceContent'                args = singleRefWithOrderReferenceContentArgs               # singleRefWithOrderReferenceContentConstraint
    | 'referenceContent'                args = singleRefWithFilterAndOrderReferenceContentArgs      # singleRefWithFilterAndOrderReferenceContentConstraint
    | 'hierarchyContent'                emptyArgs                                                   # emptyHierarchyContentConstraint
    | 'hierarchyContent'                args = singleRequireHierarchyContentArgs                    # singleRequireHierarchyContentConstraint
    | 'hierarchyContent'                args = allRequiresHierarchyContentArgs                      # allRequiresHierarchyContentConstraint
    | 'priceType'                       args = valueArgs                                            # priceTypeConstraint
    | 'dataInLocales'                   (emptyArgs | args = valueListArgs)                          # dataInLocalesConstraint
    | 'facetSummary'                    (emptyArgs | args = facetSummary1Args)                      # facetSummary1Constraint
    | 'facetSummary'                    args = facetSummary2Args                                    # facetSummary2Constraint
    | 'facetSummaryOfReference'         args = facetSummaryOfReference1Args                         # facetSummaryOfReference1Constraint
    | 'facetSummaryOfReference'         args = facetSummaryOfReference2Args                         # facetSummaryOfReference2Constraint
    | 'facetGroupsConjunction'          args = classifierWithFilterConstraintArgs                   # facetGroupsConjunctionConstraint
    | 'facetGroupsDisjunction'          args = classifierWithFilterConstraintArgs                   # facetGroupsDisjunctionConstraint
    | 'facetGroupsNegation'             args = classifierWithFilterConstraintArgs                   # facetGroupsNegationConstraint
    | 'attributeHistogram'              args = valueWithClassifierListArgs                          # attributeHistogramConstraint
    | 'priceHistogram'                  args = valueArgs                                            # priceHistogramConstraint
    | 'distance'                        args = valueArgs                                            # hierarchyDistanceConstraint
    | 'level'                           args = valueArgs                                            # hierarchyLevelConstraint
    | 'node'                            args = filterConstraintArgs                                 # hierarchyNodeConstraint
    | 'stopAt'                          args = requireConstraintArgs                                # hierarchyStopAtConstraint
    | 'statistics'                      (emptyArgs | args = hierarchyStatisticsArgs)                # hierarchyStatisticsConstraint
    | 'fromRoot'                        args = hierarchyRequireConstraintArgs                       # hierarchyFromRootConstraint
    | 'fromNode'                        args = hierarchyFromNodeArgs                                # hierarchyFromNodeConstraint
    | 'children'                        args = hierarchyRequireConstraintArgs                       # hierarchyChildrenConstraint
    | 'siblings'                        emptyArgs                                                   # emptyHierarchySiblingsConstraint
    | 'siblings'                        args = requireConstraintListArgs                            # basicHierarchySiblingsConstraint
    | 'siblings'                        args = hierarchyRequireConstraintArgs                       # fullHierarchySiblingsConstraint
    | 'parents'                         args = hierarchyRequireConstraintArgs                       # hierarchyParentsConstraint
    | 'hierarchyOfSelf'                 args = requireConstraintListArgs                            # basicHierarchyOfSelfConstraint
    | 'hierarchyOfSelf'                 args = fullHierarchyOfSelfArgs                              # fullHierarchyOfSelfConstraint
    | 'hierarchyOfReference'            args = basicHierarchyOfReferenceArgs                        # basicHierarchyOfReferenceConstraint
    | 'hierarchyOfReference'            args = basicHierarchyOfReferenceWithBehaviourArgs           # basicHierarchyOfReferenceWithBehaviourConstraint
    | 'hierarchyOfReference'            args = fullHierarchyOfReferenceArgs                         # fullHierarchyOfReferenceConstraint
    | 'hierarchyOfReference'            args = fullHierarchyOfReferenceWithBehaviourArgs            # fullHierarchyOfReferenceWithBehaviourConstraint
    | 'queryTelemetry'                  emptyArgs                                                   # queryTelemetryConstraint
    ;

headConstraintList : constraints += headConstraint (ARGS_DELIMITER constraints += headConstraint)* ;
filterConstraintList : constraints += filterConstraint (ARGS_DELIMITER constraints += filterConstraint)* ;
orderConstraintList : constraints += orderConstraint (ARGS_DELIMITER constraints += orderConstraint)* ;
requireConstraintList : constraints += requireConstraint (ARGS_DELIMITER constraints += requireConstraint)* ;


/**
 * Arguments syntax rules for query and constraints.
 * Used for better reusability and clearer generated contexts' structure ("args" label).
 */

constraintListArgs :                                ARGS_OPENING constraints += constraint (ARGS_DELIMITER constraints += constraint)* ARGS_CLOSING ;

emptyArgs :                                         ARGS_OPENING ARGS_CLOSING ;

filterConstraintListArgs :                          ARGS_OPENING constraints += filterConstraint (ARGS_DELIMITER constraints += filterConstraint)* ARGS_CLOSING ;

filterConstraintArgs :                              ARGS_OPENING filter = filterConstraint ARGS_CLOSING ;

orderConstraintListArgs :                           ARGS_OPENING constraints += orderConstraint (ARGS_DELIMITER constraints += orderConstraint)* ARGS_CLOSING ;

requireConstraintArgs :                             ARGS_OPENING requirement = requireConstraint ARGS_CLOSING ;

requireConstraintListArgs :                         ARGS_OPENING requirements += requireConstraint (ARGS_DELIMITER requirements += requireConstraint)* ARGS_CLOSING ;

classifierArgs :                                    ARGS_OPENING classifier = classifierToken ARGS_CLOSING ;

classifierWithValueArgs :                           ARGS_OPENING classifier = classifierToken ARGS_DELIMITER value = valueToken ARGS_CLOSING ;

classifierWithOptionalValueArgs :                   ARGS_OPENING classifier = classifierToken (ARGS_DELIMITER value = valueToken)? ARGS_CLOSING ;

classifierWithValueListArgs :                       ARGS_OPENING classifier = classifierToken ARGS_DELIMITER values = variadicValueTokens ARGS_CLOSING ;

classifierWithBetweenValuesArgs :                   ARGS_OPENING classifier = classifierToken ARGS_DELIMITER valueFrom = valueToken ARGS_DELIMITER valueTo = valueToken ARGS_CLOSING ;

valueArgs :                                         ARGS_OPENING value = valueToken ARGS_CLOSING ;

valueListArgs :                                     ARGS_OPENING values = variadicValueTokens ARGS_CLOSING ;

betweenValuesArgs :                                 ARGS_OPENING valueFrom = valueToken ARGS_DELIMITER valueTo = valueToken ARGS_CLOSING ;

classifierListArgs :                                ARGS_OPENING classifiers = variadicClassifierTokens ARGS_CLOSING ;

valueWithClassifierListArgs :                       ARGS_OPENING value = valueToken ARGS_DELIMITER classifiers = variadicClassifierTokens ARGS_CLOSING ;

classifierWithFilterConstraintArgs :                ARGS_OPENING classifier = classifierToken ARGS_DELIMITER filter = filterConstraint ARGS_CLOSING ;

classifierWithOrderConstraintListArgs :             ARGS_OPENING classifier = classifierToken (ARGS_DELIMITER constrains += orderConstraint)+ ARGS_CLOSING ;

valueWithRequireConstraintListArgs:                 ARGS_OPENING value = valueToken (ARGS_DELIMITER requirements += requireConstraint)* ARGS_CLOSING ;

hierarchyWithinConstraintArgs :                     ARGS_OPENING classifier = classifierToken ARGS_DELIMITER ofParent = filterConstraint (ARGS_DELIMITER constrains += filterConstraint)* ARGS_CLOSING ;

hierarchyWithinSelfConstraintArgs :                 ARGS_OPENING ofParent = filterConstraint (ARGS_DELIMITER constrains += filterConstraint)* ARGS_CLOSING ;

hierarchyWithinRootConstraintArgs :                 ARGS_OPENING (classifier = classifierToken | (classifier = classifierToken (ARGS_DELIMITER constrains += filterConstraint)*)) ARGS_CLOSING ;

hierarchyWithinRootSelfConstraintArgs :             ARGS_OPENING constrains += filterConstraint (ARGS_DELIMITER constrains += filterConstraint)* ARGS_CLOSING ;

attributeSetExactArgs :                             ARGS_OPENING attributeName = classifierToken ARGS_DELIMITER attributeValues = variadicValueTokens ARGS_CLOSING ;

pageConstraintArgs :                                ARGS_OPENING pageNumber = valueToken ARGS_DELIMITER pageSize = valueToken ARGS_CLOSING ;

stripConstraintArgs :                               ARGS_OPENING offset = valueToken ARGS_DELIMITER limit = valueToken ARGS_CLOSING ;

priceContentArgs :                                  ARGS_OPENING contentMode = valueToken (ARGS_DELIMITER priceLists = variadicValueTokens)? ARGS_CLOSING ;

singleRefReferenceContentArgs :                     ARGS_OPENING (
                                                        (classifier = classifierToken (ARGS_DELIMITER requirement = requireConstraint)?) |
                                                        (classifier = classifierToken ARGS_DELIMITER facetEntityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint)
                                                    ) ARGS_CLOSING ;

singleRefWithFilterReferenceContentArgs :           ARGS_OPENING (
                                                        (classifier = classifierToken ARGS_DELIMITER filterBy = filterConstraint (ARGS_DELIMITER requirement = requireConstraint)?) |
                                                        (classifier = classifierToken ARGS_DELIMITER filterBy = filterConstraint ARGS_DELIMITER facetEntityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint)
                                                    ) ARGS_CLOSING ;

singleRefWithOrderReferenceContentArgs :            ARGS_OPENING (
                                                        (classifier = classifierToken (ARGS_DELIMITER orderBy = orderConstraint)? (ARGS_DELIMITER requirement = requireConstraint)?) |
                                                        (classifier = classifierToken (ARGS_DELIMITER orderBy = orderConstraint)? ARGS_DELIMITER facetEntityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint)
                                                    ) ARGS_CLOSING ;

singleRefWithFilterAndOrderReferenceContentArgs :   ARGS_OPENING (
                                                        (classifier = classifierToken ARGS_DELIMITER filterBy = filterConstraint ARGS_DELIMITER orderBy = orderConstraint (ARGS_DELIMITER requirement = requireConstraint)?) |
                                                        (classifier = classifierToken ARGS_DELIMITER filterBy = filterConstraint ARGS_DELIMITER orderBy = orderConstraint ARGS_DELIMITER facetEntityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint)
                                                    ) ARGS_CLOSING ;

multipleRefsReferenceContentArgs :                  ARGS_OPENING (
                                                        (classifiers = variadicClassifierTokens (ARGS_DELIMITER requirement = requireConstraint)?) |
                                                        (classifiers = variadicClassifierTokens ARGS_DELIMITER facetEntityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint)
                                                    ) ARGS_CLOSING ;

allRefsReferenceContentArgs :                       ARGS_OPENING (
                                                        (requirement = requireConstraint) |
                                                        (facetEntityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint)
                                                    ) ARGS_CLOSING ;

singleRequireHierarchyContentArgs :                 ARGS_OPENING requirement = requireConstraint ARGS_CLOSING ;

allRequiresHierarchyContentArgs :                   ARGS_OPENING stopAt = requireConstraint ARGS_DELIMITER entityRequirement = requireConstraint ARGS_CLOSING ;

facetSummary1Args :                                 ARGS_OPENING depth = valueToken ARGS_CLOSING ;

facetSummary2Args :                                 ARGS_OPENING depth = valueToken (ARGS_DELIMITER filter = facetSummaryFilterArgs)? (ARGS_DELIMITER order = facetSummaryOrderArgs)? (ARGS_DELIMITER requirements = facetSummaryRequirementsArgs)? ARGS_CLOSING ;

facetSummaryOfReference1Args :                      ARGS_OPENING referenceName = classifierToken ARGS_CLOSING ;

facetSummaryOfReference2Args :                      ARGS_OPENING referenceName = classifierToken ARGS_DELIMITER depth = valueToken (ARGS_DELIMITER filter = facetSummaryFilterArgs)? (ARGS_DELIMITER order = facetSummaryOrderArgs)? (ARGS_DELIMITER requirements = facetSummaryRequirementsArgs)? ARGS_CLOSING ;

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

hierarchyStatisticsArgs :                           ARGS_OPENING settings = variadicValueTokens ARGS_CLOSING ;

hierarchyRequireConstraintArgs :                    ARGS_OPENING outputName = classifierToken (ARGS_DELIMITER requirements += requireConstraint)* ARGS_CLOSING ;

hierarchyFromNodeArgs :                             ARGS_OPENING outputName = classifierToken ARGS_DELIMITER node = requireConstraint (ARGS_DELIMITER requirements += requireConstraint)* ARGS_CLOSING ;

fullHierarchyOfSelfArgs :                           ARGS_OPENING orderBy = orderConstraint (ARGS_DELIMITER requirements += requireConstraint)+ ARGS_CLOSING;

// todo lho support for multiple reference names

basicHierarchyOfReferenceArgs :                     ARGS_OPENING referenceName = classifierToken (ARGS_DELIMITER requirements += requireConstraint)+ ARGS_CLOSING ;

basicHierarchyOfReferenceWithBehaviourArgs :        ARGS_OPENING referenceName = classifierToken ARGS_DELIMITER emptyHierarchicalEntityBehaviour = valueToken (ARGS_DELIMITER requirements += requireConstraint)+ ARGS_CLOSING ;

fullHierarchyOfReferenceArgs :                      ARGS_OPENING referenceName = classifierToken ARGS_DELIMITER orderBy = orderConstraint (ARGS_DELIMITER requirements += requireConstraint)+ ARGS_CLOSING ;

fullHierarchyOfReferenceWithBehaviourArgs :         ARGS_OPENING referenceName = classifierToken ARGS_DELIMITER emptyHierarchicalEntityBehaviour = valueToken ARGS_DELIMITER orderBy = orderConstraint (ARGS_DELIMITER requirements += requireConstraint)+ ARGS_CLOSING ;


/**
 * Parameters for classifiers and values
 */

positionalParameter : POSITIONAL_PARAMETER ;
namedParameter : NAMED_PARAMETER ;

/**
 * Classifier rule representing classifier types supported by Evita
 */

variadicClassifierTokens
    : positionalParameter                                                                                 # positionalParameterVariadicClassifierTokens
    | namedParameter                                                                                      # namedParameterVariadicClassifierTokens
    | classifierTokens += classifierToken (ARGS_DELIMITER classifierTokens += classifierToken)*           # explicitVariadicClassifierTokens
    ;

classifierToken
    : positionalParameter                                                                                 # positionalParameterClassifierToken
    | namedParameter                                                                                      # namedParameterClassifierToken
    | STRING                                                                                              # stringClassifierToken
    ;


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
    | ENUM                                                                                                # enumValueToken
    ;


/**
 * Value, classifier and misc tokens
 */

// special generic literal that is resolved to actual value after parsing from external queue of values
POSITIONAL_PARAMETER : '?' ;

// special generic literal that is resolved to actual value after parsing from external map of values
NAMED_PARAMETER : '@' [a-z] [a-zA-Z0-9]* ;

STRING : '\'' .*? '\'' ;

INT : '-'? [0-9]+ ;

FLOAT : '-'? [0-9]* '.' [0-9]+ ;

BOOLEAN
    : 'false'
    | 'true'
    ;

DATE : [0-9][0-9][0-9][0-9] '-' [0-9][0-9] '-' [0-9][0-9] ;

TIME : [0-9][0-9] ':' [0-9][0-9] ':' [0-9][0-9] ;

DATE_TIME : DATE 'T' TIME ;

OFFSET_DATE_TIME : DATE_TIME ('+'|'-') [0-9][0-9] ':' [0-9][0-9] ;

FLOAT_NUMBER_RANGE : '[' FLOAT? ',' FLOAT? ']' ;

INT_NUMBER_RANGE : '[' INT? ',' INT? ']' ;

DATE_TIME_RANGE : '[' OFFSET_DATE_TIME? ',' OFFSET_DATE_TIME? ']' ;

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

WHITESPACE : [ \r\t\n]+ -> channel(HIDDEN) ;

UNEXPECTED_CHAR : . ;
