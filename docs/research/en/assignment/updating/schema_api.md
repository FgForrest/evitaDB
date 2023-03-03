---
title: Schema API design
perex:
date: '15.12.2022'
author: 'Ing. Jan Novotný'
proofreading: 'done'
---

All schema classes are designed as **immutable** and follow similar rules as [entities](entity_api) - including
naming conventions and [versioning](entity_api#versioning). They don't follow the [soft-removal](entity_api#removal)
approach, though. The changes in the schema affect the database structure and once applied, the previous schema of the catalog
is no longer available.

### Evolution

evitaDB is designed to be schema-full with automatic evolution support. One can start without a schema and immediately
create new entities in the collection without reasoning about the structure. evitaDB works in "auto evolution" mode
and builds schemas along the way. The existing schemas are still validated on each entity insertion/update - you will not
be allowed to store same attribute the first time as a number type and next time as a string. First usage will set up
the attribute schema, which will have to be respected from that moment on.

Default schema implicitly creates all attributes as `nullable`, `filterable` and non-array data types as `sortable`.
This means the client is immediately able to filter / sort almost by anything, but the database itself will consume
a lot of resources.

evitaDB can operate in strict schema mode when the structure is defined up-front, and then sealed so that all entities
must strictly correspond to the predefined schema and violations will generate exceptions. This behaviour needs to be
specified at the moment of collection of the creation.

There are several partial lax modes between the strict and the lax mode - see
<SourceClass>[EvolutionMode.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/schema/EvolutionMode.java)</SourceClass>

### Schema definition example

A schema can be programmatically defined this way:

```java
evita.updateCatalog(
	"testCatalog",
	session -> {
		session.defineEntitySchema(PRODUCT)
			/* all is strictly verified but associated data and references can be added on the fly */
			.verifySchemaButAllow(EvolutionMode.ADDING_ASSOCIATED_DATA, EvolutionMode.ADDING_REFERENCES)
			/* product are not organized in the tree */
			.withoutHierarchy()
			/* prices are referencing another entity stored in Evita */
			.withPrice()
			/* en + cs localized attributes and associated data are allowed only */
			.withLocale(Locale.ENGLISH, new Locale("cs", "CZ"))
			/* here we define list of attributes with indexes for search / sort */
			.withAttribute("code", String.class, whichIs -> whichIs.unique())
			.withAttribute("url", String.class, whichIs -> whichIs.unique().localized())
			.withAttribute("oldEntityUrls", String[].class, whichIs -> whichIs.filterable().localized())
			.withAttribute("name", String.class, whichIs -> whichIs.filterable().sortable())
			.withAttribute("ean", String.class, whichIs -> whichIs.filterable())
			.withAttribute("priority", Long.class, whichIs -> whichIs.sortable())
			.withAttribute("validity", DateTimeRange.class, whichIs -> whichIs.filterable())
			.withAttribute("quantity", BigDecimal.class, whichIs -> whichIs.filterable().indexDecimalPlaces(2))
			.withAttribute("alias", Boolean.class, whichIs -> whichIs.filterable())
			/* here we define set of associated data, that can be stored along with entity */
			.withAssociatedData("referencedFiles", ReferencedFileSet.class)
			.withAssociatedData("labels", Labels.class, whichIs -> whichIs.localized())
			/* here we define references that relate to another entities stored in Evita */
			.withReferenceToEntity(
				CATEGORY,
				CATEGORY,
				Cardinality.ZERO_OR_MORE,
				whichIs ->
					/* we can specify special attributes on relation */
					whichIs.filterable().withAttribute("categoryPriority", Long.class, thatIs -> thatIs.sortable())
			)
			/* for faceted references we can compute "counts" */
			.withReferenceToEntity(
				BRAND,
				BRAND,
				Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.faceted())
			/* references may be also represented be entities unknown to Evita */
			.withReferenceTo(
				"stock",
				"stock",
				Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs.faceted()
			)
			/* finally apply schema changes */
			.updateVia(session);
	}
);
```