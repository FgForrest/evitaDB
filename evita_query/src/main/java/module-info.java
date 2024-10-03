/**
 * Module contains evitaDB query language and functionality related to it.
 */
module evita.query {

	exports io.evitadb.api.query;
	exports io.evitadb.api.query.descriptor;
	exports io.evitadb.api.query.descriptor.annotation;
	exports io.evitadb.api.query.visitor;
	exports io.evitadb.api.query.head;
	exports io.evitadb.api.query.filter;
	exports io.evitadb.api.query.order;
	exports io.evitadb.api.query.require;
	exports io.evitadb.api.query.parser;
	exports io.evitadb.api.query.expression;
	exports io.evitadb.api.query.expression.evaluate;
	exports io.evitadb.api.query.expression.exception;

	requires static jsr305;
	requires static lombok;
	requires org.antlr.antlr4.runtime;

	requires evita.common;
	requires jdk.jfr;

}
