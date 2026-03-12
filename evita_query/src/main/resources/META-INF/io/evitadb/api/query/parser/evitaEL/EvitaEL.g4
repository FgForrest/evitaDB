/*
BSD License

Copyright (c) 2026, FG Forrest a.s.
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
 * Inspired by grammars in https://github.com/antlr/grammars-v4
 */
grammar EvitaEL;

@header {
    package io.evitadb.api.query.expression.parser.grammar;
}

root : expression EOF ;

expression
    : literal # literalExpression
    | variable # variableExpression
    | functionName = IDENTIFIER LPAREN (arguments += expression (COMMA arguments += expression)*)? RPAREN # functionExpression
    | LPAREN nested = expression RPAREN # nestedExpression
    | operand = operandOperationOperand (elementAccessExpression | propertyAccessExpression | spreadAccessExpression)* # objectAccessExpression
    | EXCLAMATION_MARK nested = expression # negatingExpression
    | PLUS nested = expression # positiveExpression
    | MINUS nested = expression # negativeExpression
    | leftExpression = expression STAR rightExpression = expression # multiplicationExpression
    | leftExpression = expression DIV rightExpression = expression # divisionExpression
    | leftExpression = expression PERCENT rightExpression = expression # moduloExpression
    | leftExpression = expression PLUS rightExpression = expression # additionExpression
    | leftExpression = expression MINUS rightExpression = expression # substractionExpression
    | leftExpression = expression GT rightExpression = expression # greaterThanExpression
    | leftExpression = expression GT_EQ rightExpression = expression # greaterThanEqualsExpression
    | leftExpression = expression LT rightExpression = expression # lessThanExpression
    | leftExpression = expression LT_EQ rightExpression = expression # lessThanEqualsExpression
    | leftExpression = expression EQ rightExpression = expression # equalsExpression
    | leftExpression = expression NOT_EQ rightExpression = expression # notEqualsExpression
    | leftExpression = expression XOR rightExpression = expression # xorExpression
    | leftExpression = expression AND rightExpression = expression # andExpression
    | leftExpression = expression OR rightExpression = expression # orExpression
    | value = expression nullSafe = QUESTION_MARK? STAR_QUESTION_MARK defaultValue = expression # spreadNullCoalesceExpression
    | value = expression DOUBLE_QUESTION_MARK defaultValue = expression # nullCoalesceExpression
    ;

operandOperationOperand
    : literal # literalCallOperand
    | variable # variableCallOperand
    | LPAREN nested = expression RPAREN # nestedExpressionCallOperand
    ;

elementAccessExpression
    : nullSafe = QUESTION_MARK? LBRACKET elementIdentifier = expression RBRACKET
    ;

propertyAccessExpression
    : nullSafe = QUESTION_MARK? DOT propertyIdentifier = IDENTIFIER
    ;

spreadAccessExpression
    : nullSafe = QUESTION_MARK? DOT_STAR compact = EXCLAMATION_MARK? LBRACKET itemAccessExpression = expression RBRACKET
    ;

variable
    : VARIABLE
    ;

literal
    : NULL # nullValueToken
    | STRING # stringValueToken
    | INT # intValueToken
    | FLOAT # floatValueToken
    | BOOLEAN # booleanValueToken
    ;

LPAREN : '(' ;
RPAREN : ')' ;
LBRACKET : '[' ;
RBRACKET : ']' ;
DOUBLE_QUESTION_MARK : '??' ;
QUESTION_MARK : '?' ;
STAR_QUESTION_MARK : '*?' ;
DOT_STAR : '.*' ;
DOT : '.' ;
EXCLAMATION_MARK : '!' ;
COMMA : ',' ;
PLUS : '+' ;
MINUS : '-' ;
DIV : '/' ;
STAR : '*' ;
PERCENT : '%' ;
GT : '>' ;
GT_EQ : '>=' ;
LT : '<' ;
LT_EQ : '<=' ;
EQ : '==' ;
NOT_EQ : '!=' ;
XOR : '^' ;
AND : '&&' ;
OR : '||' ;

NULL : 'null' ;
INT : '-'? [0-9]+ ;
FLOAT : '-'? [0-9]* '.' [0-9]+ ;
BOOLEAN
    : 'false'
    | 'true'
    ;

IDENTIFIER : [a-z] [a-zA-Z0-9]* ;
VARIABLE : '$' ([a-z] [a-zA-Z0-9]*)? ;

STRING
    : '"' (STRING_DOUBLE_QUOTATION_ESC | STRING_DOUBLE_QUOTATION_SAFECODEPOINT)* '"'
    | '\'' (STRING_SINGLE_QUOTATION_ESC | STRING_SINGLE_QUOTATION_SAFECODEPOINT)* '\''
    ;
fragment STRING_DOUBLE_QUOTATION_ESC : '\\' (["\\/bfnrt] | STRING_UNICODE) ;
fragment STRING_SINGLE_QUOTATION_ESC : '\\' (['\\/bfnrt] | STRING_UNICODE) ;
fragment STRING_UNICODE : 'u' STRING_HEX STRING_HEX STRING_HEX STRING_HEX ;
fragment STRING_HEX : [0-9a-fA-F] ;
fragment STRING_DOUBLE_QUOTATION_SAFECODEPOINT : ~ ["\\\u0000-\u001F] ;
fragment STRING_SINGLE_QUOTATION_SAFECODEPOINT : ~ ['\\\u0000-\u001F] ;

WS : [ \r\n\t]+ -> skip ;

