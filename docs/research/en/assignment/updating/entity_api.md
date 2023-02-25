---
title: Entity API design
perex:
date: '15.12.2022'
author: 'Ing. Jan Novotný'
proofreading: 'done'
---

All model classes are **designed to be immutable**. The reason for this is simplicity, implicit correct behaviour in
concurrent access (in other words, entities can be cached without the fear of race condition situations) and easy identity checks
(where only the primary key and the version is necessary to claim two data objects of the same type, if they are identical).

All model classes are described by interfaces and there should not be any reason for using direct classes or
instantiating them directly. The interfaces follow this structure:

- **ModelNameContract** - contains all read methods, represents the base contract for the model
- **ModelNameEditor** - contains all modification methods
- **ModelNameBuilder** - combines Contract + Editor, and it is actually used for building the instance

### Versioning

All model classes are versioned - in other words, when any change in the model instance occurs, a new instance created from
this altered state will have version number incremented. Version information is not only on
<SourceClass>[EntityContract.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/EntityContract.java)</SourceClass> level, but
also on more granular levels (such as
<SourceClass>[AttributesContract.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/AttributesContract.java)</SourceClass>
<SourceClass>[ReferenceContract.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/ReferenceContract.java)</SourceClass>
<SourceClass>[AssociatedDataContract.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/AssociatedDataContract.java)</SourceClass>.
All model classes that support versioning implement the
<SourceClass>[Versioned.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/Versioned.java)</SourceClass> interface.

This version information is used for two purposes:

1. **fast hashing + equality check:** only the primaryKey + version information suffices to tell whether two instances are equal,
  and we can tell that with enough confidence even if only a part of the entity was really loaded from the persistent
  storage (if you need thorough comparison that compares all model data, you need to take advantage of the `differsFrom` method
  in the <SourceClass>[ContentComparator.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/ContentComparator.java)</SourceClass> interface)
2. **optimistic locking:** when there is a concurrent update of the same entity, we could automatically resolve the conflict,
  provided that the changes themselves do not overlap

This information may prove useful when this database goes into to distributed mode.

### Removal

No data is really removed once it is created and stored. When you remove the reference / attribute / whatever, it stays
in the entity and is just marked as `dropped`. See the implementations of the
<SourceClass>[Droppable.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/Droppable.java)</SourceClass>
interface.

This decision has two roots:

- it's good to have the last version of the data when things go wrong, because we can still restore the previous value
- it's easy implementation-wise, and it doesn't require data deletion

This decision quite complicates the work with the model data which is planned as follows:

- application loads entity from the database
- application deletes some data -> data stays in the entity but are marked as `dropped`
- application stores entity to the database, data is marked as `dropped`
- application loads entity again -> data doesn't contain any deleted items even if they are actually in DB, evitaDB
  will clean dropped data before handing the entity to the application
- application may create new data with the same "primary key" as previously removed data and store them back to the DB
- EvitaDB will overwrite previously `dropped` data with new ones - versioning continues from the dropped version (i.e. it
  doesn't start from one)

Nevertheless, it only complicates the internal code of evitaDB, and it should not impact developer code that uses
our API. There may even be a new `require` constraint that allows access to dropped data in the future (not planned
currently). There would (proofreaders note: would implies a condition. Example:I would have washed my hair, if the shower wasn't so cold.) also be an automatic cleaning process that will go through "dirty" entities and clean up dropped data
once a while.

## Working with entities from code

It's expected that most of the entity instances will be created by the evitaDB service classes - such as
<SourceClass>[EvitaSession.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_db/src/main/java/io/evitadb/api/EvitaSession.java)</SourceClass> 
Anyway, there is always the [possibility of creating them directly](#creating-entities-in-detached-mode).

Usually the entity creation will look like this:

```java
// create evita instance with empty test catalog
final Evita evita = new Evita(
	EvitaConfiguration
		.builder()
		.build()
);

evita.defineCatalog("testCatalog")
	.withEntitySchema("brand")
	.updateViaNewSession(evita);
```

And then followed by an operation, which will create a new entity:

```java
evita.updateCatalog(
	"testCatalog",
	session -> {
		session.upsertEntity(
			session.createNewEntity("brand", 1)
				.setAttribute("code", "siemens")
				.setAttribute("name", Locale.ENGLISH, "Siemens")
				.setAttribute("logo", "https://www.siemens.com/logo.png")
				.setAttribute("productCount", 1)
		);
	}
);
```

This way, the created entity can be immediately checked against the schema. This form of code is a condensed version, and it
may be split into several parts, which will reveal the "builder" used in the process.

## Creating entities in detached mode

Entities can be also created even when an EvitaDB instance is not at hand:

```java
final SealedEntity brand = new InitialEntityBuilder("brand", 1)
	.setAttribute("code", "siemens")
	.setAttribute("name", Locale.ENGLISH, "Siemens")
	.setAttribute("logo", "https://www.siemens.com/logo.png")
	.setAttribute("productCount", 1)
	.toInstance();
```

The <SourceClass>[SealedEntity.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/SealedEntity.java)</SourceClass> form is not
possible to directly upsert to evitaDB. The API only accepts the <SourceClass>[EntityMutation.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_api/src/main/java/io/evitadb/api/data/mutation/EntityMutation.java)</SourceClass>
but that is easily available by calling `toMutation()` instead of `toInstance()` (alternatively, the `EntityBuilder` itself,
and the `toMutation()` is executed internally).

## Session

Communication with evitaDB instance goes always through the
<SourceClass>[EvitaSession.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_db/src/main/java/io/evitadb/api/EvitaSession.java)</SourceClass> interface. Sessions are
created by the clients to envelope a "piece of work" with evitaDB. In the web environment, it's a good idea to have
one session per request, in batch processing it's recommended to keep a single session for an entire batch.

There may be multiple transactions (<SourceClass>[Transaction.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_db/src/main/java/io/evitadb/api/Transaction.java)</SourceClass>
during a single session instance's life, but transaction overlap is not supported - there can be, at most, a single
transaction open in a single session simultaneously.

<Note type="info">
**Note for implementation teams:** transactional access doesn't need to be implemented in the initial
research stage.
</Note>

## Read only vs. Read-Write sessions

We distinguish between the sessions by checking, if they allow writes ahead of session creation, or not. Read only sessions are opened by calling the
<SourceClass>[Evita.java](https://github.com/FgForrest/evitaDB-research/blob/master/evita_db/src/main/java/io/evitadb/api/Evita.java)</SourceClass> `queryCatalog`, and read-write
sessions by calling the `updateCatalog` method. No writes will be allowed in a read-only session. This also allows evitaDB to optimize
its behaviour when working with the database.

In the future, the read only sessions may be spread out to multiple read nodes, while the read-write sessions will need
to communicate with the master node.