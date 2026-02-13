
/**
 * Module contains evitaDB query language and functionality related to it.
 */
module evita.query {
	uses io.evitadb.api.query.expression.evaluate.function.FunctionProcessor;
	uses io.evitadb.api.query.expression.evaluate.object.accessor.ObjectElementAccessor;
	uses io.evitadb.api.query.expression.evaluate.object.accessor.ObjectPropertyAccessor;

	provides io.evitadb.api.query.expression.evaluate.function.FunctionProcessor with
		io.evitadb.api.query.expression.evaluate.function.AbsFunctionProcessor,
		io.evitadb.api.query.expression.evaluate.function.CeilFunctionProcessor,
		io.evitadb.api.query.expression.evaluate.function.FloorFunctionProcessor,
		io.evitadb.api.query.expression.evaluate.function.LogFunctionProcessor,
		io.evitadb.api.query.expression.evaluate.function.MaxFunctionProcessor,
		io.evitadb.api.query.expression.evaluate.function.MinFunctionProcessor,
		io.evitadb.api.query.expression.evaluate.function.PowFunctionProcessor,
		io.evitadb.api.query.expression.evaluate.function.RandomFunctionProcessor,
		io.evitadb.api.query.expression.evaluate.function.RoundFunctionProcessor,
		io.evitadb.api.query.expression.evaluate.function.SqrtFunctionProcessor;

	provides io.evitadb.api.query.expression.evaluate.object.accessor.ObjectPropertyAccessor with
		io.evitadb.api.query.expression.evaluate.object.accessor.common.MapPropertyAccessor,
		io.evitadb.api.query.expression.evaluate.object.accessor.common.MapEntryPropertyAccessor;

	provides io.evitadb.api.query.expression.evaluate.object.accessor.ObjectElementAccessor with
		io.evitadb.api.query.expression.evaluate.object.accessor.common.ListElementAccessor,
		io.evitadb.api.query.expression.evaluate.object.accessor.common.ArrayElementAccessor,
		io.evitadb.api.query.expression.evaluate.object.accessor.common.MapElementAccessor;

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
	exports io.evitadb.api.query.expression.evaluate.function;
	exports io.evitadb.api.query.expression.evaluate.object.accessor;
	exports io.evitadb.api.query.expression.evaluate.object.accessor.common;

	requires static jsr305;
	requires static lombok;
	requires org.antlr.antlr4.runtime;

	requires evita.common;
	requires jdk.jfr;

}
