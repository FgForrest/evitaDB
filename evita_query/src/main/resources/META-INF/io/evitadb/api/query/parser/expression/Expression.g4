/*
BSD License

Copyright (c) 2013, Tom Everett
Copyright (c) 2024, FG Forrest a.s.
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

1. Redistributions of source code must retain the above copyright
   notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.
3. Neither the name of Tom Everett nor the names of its contributors
   may be used to endorse or promote products derived from this software
   without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

/**
 * ANTLRv4 grammar for math expressions - parser and lexer.
 * Based on https://github.com/antlr/grammars-v4/blob/master/calculator/calculator.g4
 */
grammar Expression;

@header {
    package io.evitadb.api.query.expression.parser.grammar;
}

expression
    : atom # standaloneExpression
    | function # functionExpression
    | combinationExpression # standaloneCombinationExpression
    | NOT BOOLEAN () # booleanNegatingExpression
    | LPAREN expression RPAREN # nestedExpression
    | NOT LPAREN expression RPAREN # negatingExpression
    | leftExpression = expression (AND rightExpression = expression)+ # andExpression
    | leftExpression = expression (OR rightExpression = expression)+ # orExpression
    | leftExpression = expression EQ rightExpression = expression # equalsExpression
    | leftExpression = expression NOT_EQ rightExpression = expression # notEqualsExpression
    | leftExpression = combinationExpression GT rightExpression = combinationExpression # greaterThanExpression
    | leftExpression = combinationExpression GT_EQ rightExpression = combinationExpression # greaterThanEqualsExpression
    | leftExpression = combinationExpression LT rightExpression = combinationExpression # lessThanExpression
    | leftExpression = combinationExpression LT_EQ rightExpression = combinationExpression # lessThanEqualsExpression
    ;

combinationExpression
    : multiplyingExpression (PLUS multiplyingExpression)* # plusExpression
    | multiplyingExpression (MINUS multiplyingExpression)* # minusExpression
    ;

multiplyingExpression
    : powExpression (TIMES powExpression)* # timesExpression
    | powExpression (DIV powExpression)* # divExpression
    | powExpression (MOD powExpression)* # modExpression
    ;

powExpression
    : signedAtom (POW signedAtom)*
    ;

signedAtom
    : PLUS signedAtom # positiveSignedAtom
    | MINUS signedAtom # negativeSignedAtom
    | function # functionSignedAtom
    | atom # baseSignedAtom
    ;

atom
    : valueToken # valueAtom
    | variable # variableAtom
    | LPAREN combinationExpression RPAREN # expressionAtom
    ;

variable
    : VARIABLE
    ;

function
    : SQRT LPAREN combinationExpression RPAREN # sqrtFunction
    | CEIL LPAREN combinationExpression RPAREN # ceilFunction
    | FLOOR LPAREN combinationExpression RPAREN # floorFunction
    | ABS LPAREN combinationExpression RPAREN # absFunction
    | ROUND LPAREN combinationExpression RPAREN # roundFunction
    | LOG LPAREN combinationExpression RPAREN # logFunction
    | MIN LPAREN leftOperand = combinationExpression COMMA rightOperand = combinationExpression RPAREN # minFunction
    | MAX LPAREN leftOperand = combinationExpression COMMA rightOperand = combinationExpression RPAREN # maxFunction
    | RANDOM LPAREN (combinationExpression)? RPAREN # randomIntFunction
    ;

LPAREN : '(' ;

RPAREN : ')' ;

PLUS : '+' ;

MINUS : '-' ;

TIMES : '*' ;

DIV : '/' ;

MOD : '%' ;

GT : '>' ;

GT_EQ : '>=' ;

LT : '<' ;

LT_EQ : '<=' ;

EQ : '==' ;

NOT_EQ : '!=' ;

NOT : '!' ;

AND : '&&' ;

OR : '||' ;

COMMA : ',' ;

POINT : '.' ;

POW : '^' ;

VARIABLE : '$' [a-z] [a-zA-Z0-9]* ;

CEIL : 'ceil' ;

SQRT : 'sqrt' ;

FLOOR : 'floor' ;

ABS : 'abs' ;

ROUND : 'round' ;

LOG : 'log' ;

MAX : 'max' ;

MIN : 'min' ;

RANDOM : 'random' ;

fragment SIGN
    : '+'
    | '-'
    ;

WS : [ \r\n\t]+ -> skip ;

valueToken
    : STRING                                                                                              # stringValueToken
    | INT                                                                                                 # intValueToken
    | FLOAT                                                                                               # floatValueToken
    | BOOLEAN                                                                                             # booleanValueToken
    ;

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