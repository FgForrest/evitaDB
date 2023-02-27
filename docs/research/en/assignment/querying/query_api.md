---
title: Query API design
perex:
date: '15.12.2022'
author: 'Ing. Jan Novotný'
proofreading: 'needed'
---

The *evitaQL* (evitaDB Query Language) entry point is represented by
<SourceClass>[Query.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_query/src/main/java/io/evitadb/api/query/Query.java)</SourceClass, and looks like
a [Lisp flavoured language](https://en.wikipedia.org/wiki/Lisp_(programming_language)). It always starts with the name
of the function followed by set of arguments in brackets. Arguments can be another functions as well.

The evitaQL is represented by simple [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html)
that is parsed to abstract syntax tree consisting of constraints
(<SourceClass>[Constraint.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_query/src/main/java/io/evitadb/api/query/Constraint.java)</SourceClass)
encapsulated in <SourceClass>[Query.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_query/src/main/java/io/evitadb/api/query/Query.java)</SourceClass.
We design the *evitaQL* String representation to look similar to query defined in *Java* notation.

Developers should create their queries programmatically in their code by using static `query` methods in
<SourceClass>[Query.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_query/src/main/java/io/evitadb/api/query/Query.java)</SourceClass
and then composing internal constraints from the static methods in
<SourceClass>[QueryConstraints.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_query/src/main/java/io/evitadb/api/query/QueryConstraints.java)</SourceClass. When this
class is statically imported, the Java query definition looks like the string form of the query.

## Conversion of the evitaQL from String to AST and back

There is also <SourceClass>[QueryParser.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_query/src/main/java/io/evitadb/api/query/QueryParser.java)</SourceClass, that allows
parsing query from the [String](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html).
The string notation can be created anytime by calling method `toString()` on <SourceClass>[Query.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_query/src/main/java/io/evitadb/api/query/Query.java)</SourceClass
object.

Parser supports passing values by reference copying the proven approach from JDBC [prepared statement](https://docs.oracle.com/javase/tutorial/jdbc/basics/prepared.html)
allowing to use character `?` in the query and providing array of correctly sorted input parameters. It also supports
so-called [named queries](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/core/namedparam/NamedParameterJdbcTemplate.html),
which are widely used in [Spring framework](https://spring.io/projects/spring-data-jdbc) using variables in the query
in format `:name` and providing a [Map](https://docs.oracle.com/javase/8/docs/api/java/util/Map.html) with named
input parameters.

In the opposite direction it offers method `toStringWithParameterExtraction` on the <SourceClass>[Query.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_query/src/main/java/io/evitadb/api/query/Query.java)</SourceClass
object which allows creating the string format of the *evitaQL* in the form of *prepared statement* and extracting all
parameters in separate array.

## Defining queries in Java code

This is example how the query is composed and evitaDB requested. The example statically imports two classes:
<SourceClass>[Query.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_query/src/main/java/io/evitadb/api/query/Query.java)</SourceClass and
<SourceClass>[QueryConstraints.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_query/src/main/java/io/evitadb/api/query/QueryConstraints.java)</SourceClass

``` java
final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"testCatalog",
	session -> {
		return session.query(
			query(
				collection(Entities.BRAND),
				filterBy(
					and(
						primaryKey(1, 2, 3),
						language(Locale.ENGLISH)
					)
				),
				orderBy(
					asc("name")
				),
				require(
					entityBody(), attributes(), associatedData(), allPrices(), references()
				)
			),
			SealedEntity.class
		);
	}
);
```

Query can contain also "dirty" parts - i.e. null constraints and unnecessary parts:

``` java
final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"testCatalog",
	session -> {
		return session.query(
			query(
				collection(Entities.BRAND),
				filterBy(
					and(
						primaryKey(1, 2, 3),
						locale != null ? language(Locale.ENGLISH) : null
					)
				),
				orderBy(
					asc("name")
				),
				require(
					entityBody(), attributes(), associatedData(), allPrices(), references()
				)
			),
			SealedEntity.class
		);
	}
);
```

The query is automatically cleaned and unnecessary constraints are purged before processing by evitaDB engine.

There are several handy visitors (and new will be added) that allows to work with the query. They are placed in the package
<SourceClass branch="POC">evita_query/src/main/java/io/evitadb/api/query/visitor/</SourceClass>, and some have quick methods in
<SourceClass>[QueryUtils.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_query/src/main/java/io/evitadb/api/query/QueryUtils.java)</SourceClass class.

Query can be "pretty-printed" by `prettyPrint` method on <SourceClass>[Query.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_query/src/main/java/io/evitadb/api/query/Query.java)</SourceClass class.
