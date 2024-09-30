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
 * ANTLRv4 grammar for math predicates - parser and lexer.
 * Based on https://github.com/antlr/grammars-v4/blob/master/calculator/calculator.g4
 */
grammar Predicate;

@header {
package io.evitadb.api.query.predicate.parser.grammar;
}

predicate
    : LPAREN predicate RPAREN # nestedPredicate
    | NOT LPAREN predicate RPAREN # negatingPredicate
    | leftPredicate = predicate (AND rightPredicate = predicate)+ # andPredicate
    | leftPredicate = predicate (OR rightPredicate = predicate)+ # orPredicate
    | leftExpression = expression EQ rightExpression = expression # equalsPredicate
    | leftExpression = expression NOT_EQ rightExpression = expression # notEqualsPredicate
    | leftExpression = expression GT rightExpression = expression # greaterThanPredicate
    | leftExpression = expression LT rightExpression = expression # lessThanPredicate
    ;

expression
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
    : scientific # scientificAtom
    | variable # variableAtom
    | LPAREN expression RPAREN # expressionAtom
    ;

scientific
    : SCIENTIFIC_NUMBER
    ;

variable
    : VARIABLE
    ;

function
    : CEIL LPAREN expression RPAREN # ceilFunction
    | FLOOR LPAREN expression RPAREN # floorFunction
    | RANDOM_INT LPAREN expression (COMMA expression)? RPAREN # randomIntFunction
    ;

LPAREN : '(' ;

RPAREN : ')' ;

PLUS : '+' ;

MINUS : '-' ;

TIMES : '*' ;

DIV : '/' ;

MOD : '%' ;

GT : '>' ;

LT : '<' ;

EQ : '==' ;

NOT_EQ : '!=' ;

NOT : '!' ;

AND : '&&' ;

OR : '||' ;

COMMA : ',' ;

POINT : '.' ;

POW : '^' ;

VARIABLE : '$' [a-z] [a-zA-Z0-9]* ;

SCIENTIFIC_NUMBER : '0' ..'9'+ ('.' '0' ..'9'+)? ;

CEIL : 'ceil' ;

FLOOR : 'floor' ;

RANDOM_INT : 'randomInt' ;

fragment SIGN
    : '+'
    | '-'
    ;

WS : [ \r\n\t]+ -> skip ;