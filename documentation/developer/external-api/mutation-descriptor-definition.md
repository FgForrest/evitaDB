# Mutation descriptor definition

_Note: this may change in the future to simplify the process of defining new mutations, checkout progress in this [issue](https://github.com/FgForrest/evitaDB/issues/1008)._

As other objects in external APIs, the mutations must also be described with the `io.evitadb.externalApi.api.model.ObjectDescriptor`s.
APIs distinguish between input and output mutation formats which come with additional setup requirements.
The mutations in Java API are also backed by different grouping interfaces. These are described in the external APIs
as union objects (not all of them, only the ones that are relevant for the external API endpoints/queries).

## Describing a new mutation type

### Mutation descriptor definition

First, create a new interface extending `io.evitadb.externalApi.api.model.MutationDescriptor` and define the object
descriptor and property descriptors within it as follows:

```java
public interface UpsertAttributeMutationDescriptor extends AttributeMutationDescriptor {
	PropertyDescriptor VALUE = PropertyDescriptor.builder()
		.name("value")
		.description("""
			New value of this attribute. Data type is expected to be the same as in schema or must be explicitly
			set via `valueType`.
			""")
		.type(nonNull(Any.class))
		.build();
	PropertyDescriptor VALUE_TYPE = PropertyDescriptor.builder()
		.name("valueType")
		.description("""
			Data type of passed value of this attribute. Required only when inserting new attribute
			without prior schema. Otherwise data type is found in schema.
			""")
		.type(nullableRef(SCALAR_ENUM))
		.build();

	// output object variant
	ObjectDescriptor THIS = ObjectDescriptor.implementing(THIS_INTERFACE)
		.representedClass(UpsertAttributeMutation.class)
		.description("""
			Upsert attribute mutation will either update existing attribute or create new one.
			""")
		.staticProperty(NAME)
		.staticProperty(LOCALE)
		.staticProperty(VALUE)
		.staticProperty(VALUE_TYPE)
		.build();
	// input object variant
	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS, INPUT_OBJECT_PROPERTIES_FILTER)
		.name("UpsertAttributeMutationInput")
		.build();
}
```

Based on if it's a data, schema, or engine mutation, place it into the appropriate package:
- data -> `io.evitadb.externalApi.api.catalog.dataApi.model.mutation`
- schema -> `io.evitadb.externalApi.api.catalog.schemaApi.model.mutation`
- engine -> `io.evitadb.externalApi.api.system.model.mutation.engine`

There may be other categories in the future such as the `TransactionMutation`, but for now, these must be solved
on a case-by-case basis.

### Mutation output delegate definition

The output variant of the mutation descriptor must be added to existing union descriptors so that the queries/endpoints that
reference unions can access the new mutation type. Each union descriptor usually copies the Java API's mutation interfaces
(some are API-specific unions, these need to be extended as well). There
are several existing when you need to add the new mutation descriptor based on the implemented interface:

- `io.evitadb.externalApi.api.catalog.dataApi.model.mutation.LocalMutationUnionDescriptor`
- `io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.AttributeMutationUnionDescriptor`
- `io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalEntitySchemaMutationUnionDescriptor`
- `io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalCatalogSchemaMutationUnionDescriptor`
- `io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.ReferenceAttributeSchemaMutationUnionDescriptor`
- `io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ReferenceSortableAttributeCompoundSchemaMutationUnionDescriptor`
- `io.evitadb.externalApi.api.system.model.mutation.engine.EngineMutationUnionDescriptor`
- `io.evitadb.externalApi.api.system.model.mutation.engine.EngineMutationUnionDescriptor`
- `io.evitadb.externalApi.graphql.api.catalog.dataApi.model.mutation.CatalogDataMutationUnionDescriptor`
- `io.evitadb.externalApi.graphql.api.catalog.schemaApi.model.mutation.CatalogSchemaMutationUnionDescriptor`

If you need to create a new union, you can use the `io.evitadb.externalApi.api.model.UnionDescriptor` builder:

```java
public interface LocalMutationUnionDescriptor {

	UnionDescriptor THIS = UnionDescriptor.builder()
		.name("LocalMutationUnion")
		.description("Lists all possible types of mutations for entity data modification.")
		.discriminator(MutationDescriptor.MUTATION_TYPE)
		.type(RemoveAssociatedDataMutationDescriptor.THIS)
		.type(UpsertAssociatedDataMutationDescriptor.THIS)
		.type(ApplyDeltaAttributeMutationDescriptor.THIS)
		.type(UpsertAttributeMutationDescriptor.THIS)
		.type(RemoveAttributeMutationDescriptor.THIS)
		.type(RemoveParentMutationDescriptor.THIS)
		.type(SetParentMutationDescriptor.THIS)
		.type(SetEntityScopeMutationDescriptor.THIS)
		.type(SetPriceInnerRecordHandlingMutationDescriptor.THIS)
		.type(RemovePriceMutationDescriptor.THIS)
		.type(UpsertPriceMutationDescriptor.THIS)
		.type(InsertReferenceMutationDescriptor.THIS)
		.type(RemoveReferenceMutationDescriptor.THIS)
		.type(SetReferenceGroupMutationDescriptor.THIS)
		.type(RemoveReferenceGroupMutationDescriptor.THIS)
		.type(ReferenceAttributeMutationDescriptor.THIS)
		.build();
}
```

You can also use the `io.evitadb.externalApi.api.model.UnionDescriptor.Builder#typesFrom(UnionDescriptor)`
method to simply copy all types from another union.

### Mutation input aggregate definition

The input variant of the mutation descriptor must be added to existing aggregate descriptors so that the mutations/endpoints
can access the new mutation type. Each aggregate descriptor usually copies the Java API's mutation interfaces
(some are API-specific unions, these need to be extended as well). There
are several existing when you need to add the new mutation descriptor based on the implemented interface:

- `io.evitadb.externalApi.api.catalog.dataApi.model.mutation.LocalMutationInputAggregateDescriptor`
- `io.evitadb.externalApi.api.catalog.dataApi.model.mutation.attribute.AttributeMutationInputAggregateDescriptor`
- `io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalEntitySchemaMutationInputAggregateDescriptor`
- `io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.LocalCatalogSchemaMutationInputAggregateDescriptor`
- `io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.attribute.ReferenceAttributeSchemaMutationInputAggregateDescriptor`
- `io.evitadb.externalApi.api.catalog.schemaApi.model.mutation.sortableAttributeCompound.ReferenceSortableAttributeCompoundSchemaMutationInputAggregateDescriptor`

If you need to create a new aggregate, you need to use the `io.evitadb.externalApi.api.model.ObjectDescriptor` builder:

```java
public interface LocalEntitySchemaMutationInputAggregateDescriptor {

	PropertyDescriptor ALLOW_CURRENCY_IN_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"allowCurrencyInEntitySchemaMutation",
		AllowCurrencyInEntitySchemaMutationDescriptor.THIS_INPUT
	);
	PropertyDescriptor ALLOW_EVOLUTION_MODE_IN_ENTITY_SCHEMA_MUTATION = PropertyDescriptor.nullableFromObject(
		"allowEvolutionModeInEntitySchemaMutation",
		AllowEvolutionModeInEntitySchemaMutationDescriptor.THIS_INPUT
	);

	/* ... omitted for brevity ... */

	ObjectDescriptor THIS_INPUT = ObjectDescriptor.builder()
		.name("LocalEntitySchemaMutationInputAggregate")
		.description("""
			             Contains all possible entity schema mutations.
			             """)
		.staticProperties(List.of(
			ALLOW_CURRENCY_IN_ENTITY_SCHEMA_MUTATION,
			ALLOW_EVOLUTION_MODE_IN_ENTITY_SCHEMA_MUTATION,

			/* ... omitted for brevity ... */
		))
		.build();
}
```

### Mutation converters

Each mutation descriptor, mutation union descriptor, and mutation input aggregate descriptor must have a converter
that converts the mutation object between Java API and the GraphQL and REST API representation.

Mutation descriptors must implement the `io.evitadb.externalApi.api.resolver.mutation.MutationConverter`.
Mutation union descriptor must implement the `io.evitadb.externalApi.api.resolver.mutation.DelegatingMutationConverter`.
Mutation input aggregate descriptor must implement the `io.evitadb.externalApi.api.resolver.mutation.MutationInputAggregateConverter`.

These must be registered in respective parent delegating and aggregate converters based on the categorizing interfaces.

### Register descriptor

The final step is to register all the mutation descriptors, union descriptors, and input aggregate descriptors in the GraphQL
and REST API schema builders based on the mutation type.

#### GraphQL system API

This API needs to know about all the mutation descriptors and unions (not input aggregates). 
Register in:

- `io.evitadb.externalApi.graphql.api.system.builder.SystemGraphQLSchemaBuilder.buildOutputMutations`

#### GraphQL catalog data API

This API needs to know about all the data mutation descriptors and unions **and** input aggregates. 
Register in:

- `io.evitadb.externalApi.graphql.api.catalog.dataApi.CatalogDataApiGraphQLSchemaBuilder.buildInputMutations`
- `io.evitadb.externalApi.graphql.api.catalog.dataApi.CatalogDataApiGraphQLSchemaBuilder.buildOutputMutations`

#### GraphQL catalog schema API

This API needs to know about all the schema mutation descriptors and unions **and** input aggregates.
Register in:

- `io.evitadb.externalApi.graphql.api.catalog.schemaApi.builder.CommonEvitaSchemaSchemaBuilder.buildInputMutations`
- `io.evitadb.externalApi.graphql.api.catalog.schemaApi.builder.CommonEvitaSchemaSchemaBuilder.buildOutputMutations`

#### REST system API

This API needs to know about all the system/engine mutation descriptors and unions (not input aggregates).
Register both in `io.evitadb.externalApi.rest.api.system.SystemRestBuilder.buildMutationInterface` and 
`io.evitadb.externalApi.rest.api.system.SystemRestBuilder.buildOutputMutations`.

#### REST catalog API

This API needs to know about all catalog-related data and schema mutations descriptors and unions **and** input aggregates.
Register in:

- `io.evitadb.externalApi.rest.api.catalog.CatalogRestBuilder.buildMutationInterface`
- `io.evitadb.externalApi.rest.api.catalog.CatalogRestBuilder.buildOutputMutations`
