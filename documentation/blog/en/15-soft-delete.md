---
title: Streamlining Data Models with Native Soft-Delete Support in evitaDB
perex: |
  Native soft-delete support in evitaDB simplifies application data models, enhances performance, and reduces complexity
  by handling archived entities directly within the database. 
date: '23.09.2024'
author: 'Ing. Jan Novotný'
motive: assets/images/15-soft-delete.png
proofreading: 'done'
---
In the ever-evolving landscape of e-commerce, the need to maintain access to obsolete or discontinued products is more
significant than ever. These products often serve as valuable resources for technical information, SEO optimization, and
historical data linking to orders or customer histories. Traditionally, handling such archived entities introduces
additional complexity into the application data model. However, with evitaDB's native support for soft deletion, we can
now streamline this process, reducing complexity and improving overall system performance.

Another significant advantage of evitaDB's soft-delete support is the ability to define indexing rules for archived
entities differently from live entities. Because evitaDB keeps all indexes in memory, limiting the number of indexed
data for archived entities can significantly reduce memory consumption while maintaining the ability to access essential
historical data.

## The Complexity of Traditional Archiving Methods
Typically, when products are no longer active, developers resort to creating separate entity collections, such as
ObsoleteProduct, to manage them. This approach, while functional, leads to several challenges.
The data model becomes more complex as relationships and schemas must be duplicated or modified to accommodate the new
entity types. This duplication not only increases the risk of inconsistencies but also adds to the maintenance overhead,
making the application harder to manage and evolve.

Another approach is to implement soft deletion at the application level, where entities are flagged as archived but
remain in the same collection. While this method avoids schema duplication, it introduces its own set of challenges.
This method requires developers to add additional conditions to queries to filter out archived entities, increasing the
complexity of the application code and the risk of errors. It also means that archived entities remain in active indexes,
occupying valuable RAM and impacting overall performance.

## evitaDB's Native Soft-Delete Solution
To address these challenges, evitaDB introduces native support for soft deletion through the `archiveEntity` and
`restoreEntity` methods. By handling archiving directly within the database, evitaDB eliminates the need for separate
collections or additional flags managed at the application level. This native support ensures that archived entities are
flagged appropriately. In its default setup, archived entities are automatically excluded from all active search indexes,
except primary key indexes, freeing up RAM and improving search performance for "living" entities.

Developers, however, can define custom indexing rules for archived entities, allowing them to optimize memory consumption
based on the application's specific needs while keeping the ability to search for archived entities by a selected set of
attributes or relationships.

When retrieving entities, developers can use the [`scope(LIVE, ARCHIVED)`](/documentation/query/requirements/fetching#scope)
require constraint to specify whether they want to include live entities, archived entities, or both in their queries.
This flexibility allows for precise control over the data retrieved, without adding complexity to the application code.
The database handles the filtering internally, ensuring consistency and reducing the risk of errors.

## Managing Unique Constraints Across Scopes
One of the challenges in handling archived entities is managing unique constraints. In evitaDB, unique constraints are
enforced within the same scope. However, an entity in the `ARCHIVED` scope can share a unique value with a `LIVE` entity
and vice versa. This feature allows developers to reuse identifiers like product codes or URLs for new entities without
conflicting with archived ones. It simplifies data management and ensures that the application can continue to use
meaningful and consistent identifiers.

When restoring an archived entity or archiving a live entity, evitaDB automatically checks for unique constraints within
the same scope to prevent conflicts. If a conflict is detected, the restore operation fails and the developer must first
assign a new unique value to the entity.

When an application queries for entities by unique values, evitaDB automatically prefers entities in the `LIVE` scope over
`ARCHIVED` entities when their unique values conflict. This behavior ensures that the application retrieves the most
relevant and up-to-date information for the user.

## Practical Application in E-Commerce
Consider an e-commerce platform where products are regularly updated, discontinued, or reintroduced. With evitaDB's
soft-delete support, when a product is discontinued, a developer can simply call the `archiveEntity` method. The product
is then flagged as archived and removed from active searches but remains accessible for SEO purposes or historical data
retrieval. The database maintains a searchable index for its `URL` property and primary key, so that when the product was
referenced in client orders, or wishlists, the application can still display the archived product details.

If the product is reintroduced, calling the `restoreEntity` method brings it back into the `LIVE` scope, making it visible
in active searches again. This process eliminates the need to recreate the product or manage complex data migrations. It
ensures that all historical data, such as customer reviews or sales history, remains intact and associated with the
product.

## Simplifying Queries with Scope Constraints
Using the `scope(LIVE, ARCHIVED)` require constraint in queries provides developers with powerful control over data
retrieval. For instance, an administrator interface may need to display both live and archived products with a single
query for management purposes. By specifying both scopes in the query, the application retrieves all relevant entities
without the need to issue two queries and combine the results in the application code.

Developers still have the option to access live or archived entities separately by specifying only one scope in the
query. When no require constraint is specified, the database defaults to the `LIVE` scope, ensuring that the application
only retrieves active entities by default.

This approach also improves security and data integrity. Since the database manages the visibility of entities based on
their scope, the risk of accidentally exposing archived or sensitive data is minimized. The application can trust that
the database will only return entities that match the specified scope, reducing the potential for bugs or unintended
data leaks.

## Conclusion
evitaDB's native support for soft deletion represents a significant advancement in database management for applications
that require archiving capabilities. By handling soft deletion internally, evitaDB simplifies the application data
model, reduces complexity, and enhances performance. Developers can avoid the pitfalls of traditional archiving methods,
such as schema duplication and complex query filtering, leading to more maintainable and efficient applications.

By embracing database-level soft deletion, organizations can streamline their development processes, improve system
performance, and focus on delivering value to their users without getting bogged down in the intricacies of data
management.