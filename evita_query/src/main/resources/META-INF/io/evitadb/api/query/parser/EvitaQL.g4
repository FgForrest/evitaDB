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
    : 'collection'                      args = classifierArgs                                   # collectionConstraint
    ;

filterConstraint
    : 'filterBy'                        args = filterConstraintArgs                             # filterByConstraint
    | 'and'                             (emptyArgs | args = filterConstraintListArgs)           # andConstraint
    | 'or'                              (emptyArgs | args = filterConstraintListArgs)           # orConstraint
    | 'not'                             args = filterConstraintArgs                             # notConstraint
    | 'userFilter'                      (emptyArgs | args = filterConstraintListArgs)           # userFilterConstraint
    | 'attributeEquals'                 args = classifierWithValueArgs                          # attributeEqualsConstraint
    | 'attributeGreaterThan'            args = classifierWithValueArgs                          # attributeGreaterThanConstraint
    | 'attributeGreaterThanEquals'      args = classifierWithValueArgs                          # attributeGreaterThanEqualsConstraint
    | 'attributeLessThan'               args = classifierWithValueArgs                          # attributeLessThanConstraint
    | 'attributeLessThanEquals'         args = classifierWithValueArgs                          # attributeLessThanEqualsConstraint
    | 'attributeBetween'                args = classifierWithBetweenValuesArgs                  # attributeBetweenConstraint
    | 'attributeInSet'                  args = classifierWithValueListArgs                      # attributeInSetConstraint
    | 'attributeContains'               args = classifierWithValueArgs                          # attributeContainsConstraint
    | 'attributeStartsWith'             args = classifierWithValueArgs                          # attributeStartsWithConstraint
    | 'attributeEndsWith'               args = classifierWithValueArgs                          # attributeEndsWithConstraint
    | 'attributeEqualsTrue'             args = classifierArgs                                   # attributeEqualsTrueConstraint
    | 'attributeEqualsFalse'            args = classifierArgs                                   # attributeEqualsFalseConstraint
    | 'attributeIs'                     args = classifierWithValueArgs                          # attributeIsConstraint
    | 'attributeIsNull'                 args = classifierArgs                                   # attributeIsNullConstraint
    | 'attributeIsNotNull'              args = classifierArgs                                   # attributeIsNotNullConstraint
    | 'attributeInRange'                args = classifierWithValueArgs                          # attributeInRangeConstraint
    | 'entityPrimaryKeyInSet'           args = valueListArgs                                    # entityPrimaryKeyInSetConstraint
    | 'entityLocaleEquals'              args = valueArgs                                        # entityLocaleEqualsConstraint
    | 'priceInCurrency'                 args = valueArgs                                        # priceInCurrencyConstraint
    | 'priceInPriceLists'               (emptyArgs | args = classifierListArgs)                 # priceInPriceListsConstraints
    | 'priceValidNow'                   emptyArgs                                               # priceValidNowConstraint
    | 'priceValidIn'                    (emptyArgs | args = valueArgs)                          # priceValidInConstraint
    | 'priceBetween'                    args = betweenValuesArgs                                # priceBetweenConstraint
    | 'facetInSet'                      args = classifierWithValueListArgs                      # facetInSetConstraint
    | 'referenceHaving'                 args = classifierWithFilterConstraintArgs               # referenceHavingConstraint
    | 'hierarchyWithin'                 args = hierarchyWithinConstraintArgs                    # hierarchyWithinConstraint
    | 'hierarchyWithinSelf'             args = hierarchyWithinSelfConstraintArgs                # hierarchyWithinSelfConstraint
    | 'hierarchyWithinRoot'             args = hierarchyWithinRootConstraintArgs                # hierarchyWithinRootConstraint
    | 'hierarchyWithinRootSelf'         args = hierarchyWithinRootSelfConstraintArgs            # hierarchyWithinRootSelfConstraint
    | 'directRelation'                  emptyArgs                                               # hierarchyDirectRelationConstraint
    | 'excludingRoot'                   emptyArgs                                               # hierarchyExcludingRootConstraint
    | 'excluding'                       (emptyArgs | args = filterConstraintListArgs)           # hierarchyExcludingConstraint
    | 'entityHaving'                    args = filterConstraintArgs                             # entityHavingConstraint
    ;

orderConstraint
    : 'orderBy'                         (emptyArgs | args = orderConstraintListArgs)            # orderByConstraint
    | 'attributeNatural'                args = classifierWithOptionalValueArgs                  # attributeNaturalConstraint
    | 'priceNatural'                    (emptyArgs | args = valueArgs)                          # priceNaturalConstraint
    | 'random'                          emptyArgs                                               # randomConstraint
    | 'referenceProperty'               args = classifierWithOrderConstraintListArgs            # referencePropertyConstraint
    | 'entityProperty'                  args = orderConstraintListArgs                          # entityPropertyConstraint
    ;

requireConstraint
    : 'require'                         (emptyArgs | args = requireConstraintListArgs)          # requireContainerConstraint
    | 'page'                            args = pageConstraintArgs                               # pageConstraint
    | 'strip'                           args = stripConstraintArgs                              # stripConstraint
    | 'entityFetch'                     (emptyArgs | args = requireConstraintListArgs)          # entityFetchConstraint
    | 'entityGroupFetch'                (emptyArgs | args = requireConstraintListArgs)          # entityGroupFetchConstraint
    | 'attributeContent'                (emptyArgs | args = classifierListArgs)                 # attributeContentConstraint
    | 'priceContent'                    (emptyArgs | args = valueListArgs)                      # priceContentConstraint
    | 'priceContentAll'                 emptyArgs                                               # priceContentAllConstraint
    | 'associatedDataContent'           (emptyArgs | args = classifierListArgs)                 # associatedDataContentConstraint
    | 'referenceContent'                (emptyArgs | args = allRefsReferenceContentArgs)        # allRefsReferenceContentConstraint
    | 'referenceContent'                args = multipleRefsReferenceContentArgs                 # multipleRefsReferenceContentConstraint
    | 'referenceContent'                args = singleRefReferenceContentArgs                    # singleRefReferenceContentConstraint
    | 'referenceContent'                args = singleRefWithFilterReferenceContentArgs          # singleRefWithFilterReferenceContentConstraint
    | 'referenceContent'                args = singleRefWithOrderReferenceContentArgs           # singleRefWithOrderReferenceContentConstraint
    | 'referenceContent'                args = singleRefWithFilterAndOrderReferenceContentArgs  # singleRefWithFilterAndOrderReferenceContentConstraint
    | 'priceType'                       args = valueArgs                                        # priceTypeConstraint
    | 'dataInLocales'                   (emptyArgs | args = valueListArgs)                      # dataInLocalesConstraint
    | 'hierarchyParentsOfSelf'          (emptyArgs | args = requireConstraintArgs)              # hierarchyParentsOfSelfConstraint
    | 'hierarchyParentsOfReference'     args = classifierListWithOptionalRequireConstraintArgs  # hierarchyParentsOfReferenceConstraint
    | 'facetSummary'                    (emptyArgs | args = facetSummaryArgs)                   # facetSummaryConstraint
    | 'facetSummaryOfReference'         args = facetSummaryOfReferenceArgs                      # facetSummaryOfReferenceConstraint
    | 'facetGroupsConjunction'          args = classifierWithValueListArgs                      # facetGroupsConjunctionConstraint
    | 'facetGroupsDisjunction'          args = classifierWithValueListArgs                      # facetGroupsDisjunctionConstraint
    | 'facetGroupsNegation'             args = classifierWithValueListArgs                      # facetGroupsNegationConstraint
    | 'attributeHistogram'              args = valueWithClassifierListArgs                      # attributeHistogramConstraint
    | 'priceHistogram'                  args = valueArgs                                        # priceHistogramConstraint
    | 'hierarchyStatisticsOfSelf'       (emptyArgs | args = requireConstraintArgs)              # hierarchyStatisticsOfSelfConstraint
    | 'hierarchyStatisticsOfReference'  args = classifierListWithOptionalRequireConstraintArgs  # hierarchyStatisticsOfReferenceConstraint
    | 'queryTelemetry'                  emptyArgs                                               # queryTelemetryConstraint
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

classifierWithFilterConstraintArgs :                ARGS_OPENING classifier = classifierToken ARGS_DELIMITER filterConstraint ARGS_CLOSING ;

classifierWithOrderConstraintListArgs :             ARGS_OPENING classifier = classifierToken (ARGS_DELIMITER constrains += orderConstraint)+ ARGS_CLOSING ;

valueWithRequireConstraintListArgs:                 ARGS_OPENING value = valueToken (ARGS_DELIMITER requirements += requireConstraint)* ARGS_CLOSING ;

classifierListWithOptionalRequireConstraintArgs :   ARGS_OPENING classifiers = variadicClassifierTokens (ARGS_DELIMITER requirement = requireConstraint)? ARGS_CLOSING ;

hierarchyWithinConstraintArgs :                     ARGS_OPENING classifier = classifierToken ARGS_DELIMITER primaryKey = valueToken (ARGS_DELIMITER constrains += filterConstraint)* ARGS_CLOSING ;

hierarchyWithinSelfConstraintArgs :                 ARGS_OPENING primaryKey = valueToken (ARGS_DELIMITER constrains += filterConstraint)* ARGS_CLOSING ;

hierarchyWithinRootConstraintArgs :                 ARGS_OPENING (classifier = classifierToken | (classifier = classifierToken (ARGS_DELIMITER constrains += filterConstraint)*)) ARGS_CLOSING ;

hierarchyWithinRootSelfConstraintArgs :             ARGS_OPENING constrains += filterConstraint (ARGS_DELIMITER constrains += filterConstraint)* ARGS_CLOSING ;

pageConstraintArgs :                                ARGS_OPENING pageNumber = valueToken ARGS_DELIMITER pageSize = valueToken ARGS_CLOSING ;

stripConstraintArgs :                               ARGS_OPENING offset = valueToken ARGS_DELIMITER limit = valueToken ARGS_CLOSING ;

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

facetSummaryArgs :                                  ARGS_OPENING (
                                                        (depth = valueToken) |
                                                        (depth = valueToken ARGS_DELIMITER requirement = requireConstraint) |
                                                        (depth = valueToken ARGS_DELIMITER facetEntityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint)
                                                    ) ARGS_CLOSING ;

facetSummaryOfReferenceArgs :                       ARGS_OPENING (
                                                        (referenceName = classifierToken) |
                                                        (referenceName = classifierToken ARGS_DELIMITER depth = valueToken) |
                                                        (referenceName = classifierToken ARGS_DELIMITER depth = valueToken ARGS_DELIMITER requirement = requireConstraint) |
                                                        (referenceName = classifierToken ARGS_DELIMITER depth = valueToken ARGS_DELIMITER facetEntityRequirement = requireConstraint ARGS_DELIMITER groupEntityRequirement = requireConstraint)
                                                    ) ARGS_CLOSING ;


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
    | MULTIPLE_OPENING values = variadicValueTokens MULTIPLE_CLOSING                                      # multipleValueToken
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

MULTIPLE_OPENING : '{' ;

MULTIPLE_CLOSING : '}' ;


/**
 * Miscellaneous tokens
 */

WHITESPACE : [ \r\t\n]+ -> channel(HIDDEN) ;

UNEXPECTED_CHAR : . ;
