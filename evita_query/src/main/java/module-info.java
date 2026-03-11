/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

import io.evitadb.api.query.expression.function.processor.*;
import io.evitadb.api.query.expression.object.accessor.ObjectElementAccessor;
import io.evitadb.api.query.expression.object.accessor.ObjectPropertyAccessor;
import io.evitadb.api.query.expression.object.accessor.common.ArrayElementAccessor;
import io.evitadb.api.query.expression.object.accessor.common.ListElementAccessor;
import io.evitadb.api.query.expression.object.accessor.common.MapElementAccessor;
import io.evitadb.api.query.expression.object.accessor.common.MapEntryPropertyAccessor;
import io.evitadb.api.query.expression.object.accessor.common.MapPropertyAccessor;

/**
 * Module contains evitaDB query language and functionality related to it.
 */
module evita.query {
	uses FunctionProcessor;
	uses ObjectElementAccessor;
	uses ObjectPropertyAccessor;

	provides FunctionProcessor with
		AbsFunctionProcessor,
		CeilFunctionProcessor,
		FloorFunctionProcessor,
		LogFunctionProcessor,
		MaxFunctionProcessor,
		MinFunctionProcessor,
		PowFunctionProcessor,
		RandomFunctionProcessor,
		RoundFunctionProcessor,
		SqrtFunctionProcessor;

	provides ObjectPropertyAccessor with
		MapPropertyAccessor,
		MapEntryPropertyAccessor;

	provides ObjectElementAccessor with
		ListElementAccessor,
		ArrayElementAccessor,
		MapElementAccessor;

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
	exports io.evitadb.api.query.expression.object.accessor;
	exports io.evitadb.api.query.expression.object.accessor.common;
	exports io.evitadb.api.query.expression.function.processor;
	exports io.evitadb.api.query.expression.bool;
	exports io.evitadb.api.query.expression.utility;
	exports io.evitadb.api.query.expression.object;
	exports io.evitadb.api.query.expression.operand;
	exports io.evitadb.api.query.expression.numeric;
	exports io.evitadb.api.query.expression.coalesce;
	exports io.evitadb.api.query.expression.function;
	exports io.evitadb.api.query.expression.visitor;

	requires static jsr305;
	requires static lombok;
	requires org.antlr.antlr4.runtime;

	requires evita.common;
	requires jdk.jfr;

}
