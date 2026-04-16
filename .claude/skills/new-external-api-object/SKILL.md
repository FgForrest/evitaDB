---
name: new-external-api-object
description: Use when adding a new response object (DTO surface) to evitaDB's external APIs — GraphQL, REST, and gRPC. Covers descriptors and their inheritance, interfaces and field-override rules, per-parameter object caches, GraphQL data fetchers, and gRPC proto/converter patterns with backward-compat rules. Use this whenever introducing a new object type, a suffix variant, or a polymorphic interface that must be exposed via at least two of the three APIs.
---

# Adding a new external API object

evitaDB projects every response object through three external APIs — GraphQL, REST (OpenAPI), gRPC. All three share a single API-independent model in `evita_external_api_core` called **descriptors**, then each API module translates those descriptors into its own runtime types.

Use this skill when:
- Introducing a brand-new response object that is not yet exposed.
- Adding a new suffix variant of an existing object (e.g. `withHistograms` alongside the regular form).
- Refactoring an existing concrete object into an **interface + implementations** polymorphic shape.

## Module map

```
evita_external_api_core/          descriptors (API-independent)
└─ .../dataApi/model/**/          PropertyDescriptor + ObjectDescriptor interfaces

evita_external_api_graphql/       GraphQL schema + data fetchers
├─ .../api/*/builder/             building contexts (parametrized-object caches live here)
├─ .../api/**/builder/**/         *Builder classes — each owns a slice of the schema
└─ .../api/**/resolver/dataFetcher/** data fetchers per object

evita_external_api_rest/          REST / OpenAPI
├─ .../api/*/builder/             building contexts
├─ .../api/**/builder/**/         *Builder classes
└─ .../api/**/resolver/serializer/ JSON serializers (Jackson is not used directly for DTOs that carry Optional)

evita_external_api_grpc/shared/   proto + generated classes + client converters
├─ .../META-INF/io/evitadb/externalApi/grpc/*.proto  proto sources
├─ .../generated/                                    auto-regenerated
└─ .../requestResponse/                              ResponseConverter, EntityConverter, EvitaEnumConverter

evita_external_api_grpc/server/   gRPC server-side builders
└─ .../builders/**/                                  Grpc*Builder per outer DTO
```

The specific builder classes that register schema types differ between responses (full-response builders, entity builders, reference builders, extra-result builders, mutation builders, etc.). Treat them as **one slice per concern**: each owns its building context reference, the relevant transformers, and the methods that call `registerType` / `registerFieldToObject` for the objects it produces. When adding a new object, either extend the existing slice that owns the parent type or create a new dedicated builder next to it — don't conflate slices that touch different parent types.

---

## 1. Descriptors and their inheritance

Descriptors are the API-independent description of an object. There are two key types:

- **`PropertyDescriptor`** — a single field with a name, description, and optional type (`PrimitivePropertyDataTypeDescriptor` for primitives, `TypePropertyDataTypeDescriptor.nonNullRef(...)` / `nullableRef(...)` / `nonNullListRef(...)` for named object references). Types may be left unset when the field's type depends on per-instance context (e.g. per-reference-schema entity types) — schema builders plug the concrete type at build time.
- **`ObjectDescriptor`** — a named object with a description, an ordered list of static properties, an optional `interfaceDescriptor`, and an optional `representedClass`. Use one per response object type.

### 1.1 Static constants pattern

```java
public interface HistogramDescriptor {
    PropertyDescriptor MIN = PropertyDescriptor.builder()
        .name("min").description("...").type(nonNull(BigDecimal.class)).build();
    PropertyDescriptor BUCKETS = PropertyDescriptor.builder()
        .name("buckets").description("...").type(nonNullListRef(BucketDescriptor.THIS)).build();

    ObjectDescriptor THIS = ObjectDescriptor.builder()
        .name("Histogram")
        .description("...")
        .staticProperties(List.of(MIN, MAX, OVERALL_COUNT, BUCKETS))
        .build();
}
```

A property with **no type** is valid — the schema builder will plug the concrete type later via the `propertyBuilderTransformer`/`fieldBuilderTransformer`. Use this for fields whose type depends on context (e.g. `GROUP_ENTITY`, `FACET_ENTITY`, `MIN_REFERENCED_ENTITY`).

### 1.2 Wildcard names for parametrized objects

An `ObjectDescriptor` name that contains `*` is a template resolved at build time:
- `"*Histogram"` — `*` is a prefix placeholder, replaced with one or more dynamic names joined in PascalCase (e.g. `CategoryHistogram` for referenced entity type `Category`).
- `"*FacetStatistics"` — same — generates names like `ProductCategoriesFacetStatistics`.
- Call site: `ReferenceHistogramDescriptor.THIS.name(referencedEntityType)`.

Use static names when the descriptor represents one singleton type (shared by all queries). Use wildcard names when the descriptor is a **template** generating a family of concrete types.

### 1.3 Inheriting from another descriptor

Two factory methods on `ObjectDescriptor` let a new descriptor borrow properties from an existing one (`ObjectDescriptor.java:104-150`):

- **`ObjectDescriptor.from(base)`** — copies `base.staticProperties` into the new descriptor's builder but **discards the name** so a new name can be supplied. No interface relationship is recorded. Use when you want to reuse fields but the new object is **not** polymorphically related to the base.
- **`ObjectDescriptor.implementing(interfaceDescriptor)`** — copies `base.staticProperties` **and** records `interfaceDescriptor` as the new object's `interfaceDescriptor`. REST's object transformer reads this and emits OpenAPI `allOf`; GraphQL concrete implementations must additionally call `withInterface(...)` (see §2.4). Use when the new object is a concrete implementation of a polymorphic interface.

```java
// Reuse properties, no interface relation:
ObjectDescriptor THIS = ObjectDescriptor.from(HistogramDescriptor.THIS).name("*Histogram").build();

// Polymorphic concrete implementation:
ObjectDescriptor THIS = ObjectDescriptor.implementing(HistogramDescriptor.THIS_INTERFACE)
    .name("*Histogram")
    .build();
```

### 1.4 `normalizeProperties()` and overriding

`ObjectDescriptor.normalizeProperties` de-duplicates `staticProperties` by property name keeping the **last** occurrence. That means you can use `.staticProperty(MY_OVERRIDE)` after a `.from(...)` to replace an inherited property's definition with a narrower one. Rely on this rather than manually filtering the list.

---

## 2. Interface descriptors

Polymorphic objects in evitaDB follow the **`THIS_INTERFACE` + concrete `THIS`** pattern. The descriptor module declares both; every module that exposes the object picks them up.

### 2.1 Declaring an interface

```java
public interface HistogramDescriptor {
    PropertyDescriptor MIN = ...; PropertyDescriptor MAX = ...; /* ... */

    // interface: named after the user-facing type. Lists the common fields.
    ObjectDescriptor THIS_INTERFACE = ObjectDescriptor.builder()
        .name("Histogram")
        .staticProperties(List.of(MIN, MAX, OVERALL_COUNT, BUCKETS))
        .build();

    // concrete default implementation (used where no polymorphism is needed).
    // Distinct name is required — GraphQL does not allow an interface and an object
    // to share a name.
    ObjectDescriptor THIS = ObjectDescriptor.implementing(THIS_INTERFACE)
        .name("BaseHistogram")
        .build();
}
```

Sibling descriptors declare themselves as implementations:

```java
public interface ReferenceHistogramDescriptor {
    PropertyDescriptor MIN_REFERENCED_ENTITY = ...;
    PropertyDescriptor MAX_REFERENCED_ENTITY = ...;

    ObjectDescriptor THIS = ObjectDescriptor.implementing(HistogramDescriptor.THIS_INTERFACE)
        .name("*Histogram")         // parametrized per referenced entity type
        .build();
}
```

### 2.2 Registering the GraphQL interface

Register the interface in whichever builder owns the surrounding type — typically the builder that registers the concrete implementations. Each builder holds its own `interfaceBuilderTransformer` and building context:

```java
final GraphQLInterfaceType histogramInterface = HistogramDescriptor.THIS_INTERFACE
    .to(this.interfaceBuilderTransformer)   // emits fields from staticProperties
    .field(buildHistogramBucketsField())    // overrides auto-generated `buckets` with arguments (see §3)
    .build();
this.buildingContext.registerType(histogramInterface);
this.buildingContext.registerTypeResolver(histogramInterface, HelperInterfaceTypeResolver.getInstance());
```

`HelperInterfaceTypeResolver` is used for interfaces that are never returned directly by a field selection — GraphQL still requires a `TypeResolver`, and the helper throws if called (because queries return concrete implementations, not the interface itself). Use a real `TypeResolver` only when a field's declared return type is the interface and the runtime type must be disambiguated.

### 2.3 Registering the REST interface

REST has no native interface. `ObjectDescriptorToOpenApiObjectTransformer` reads `interfaceDescriptor()` from any concrete descriptor and emits an OpenAPI `allOf` link to that interface — so in practice the interface is registered as a plain object type and concrete implementations reference it via `allOf`. Register the interface object alongside any concrete implementation that uses it:

```java
this.buildingContext.registerType(HistogramDescriptor.THIS_INTERFACE.to(this.objectBuilderTransformer).build());
this.buildingContext.registerType(HistogramDescriptor.THIS.to(this.objectBuilderTransformer).build());
```

### 2.4 Concrete types declaring interface membership

Every concrete GraphQL type must explicitly declare its interface:

```java
HistogramDescriptor.THIS
    .to(this.objectBuilderTransformer)
    .withInterface(typeRef(HistogramDescriptor.THIS_INTERFACE.name()))   // <-- required
    .field(buildHistogramBucketsField())
    .build();
```

For REST this is automatic — the transformer reads `interfaceDescriptor()` from the descriptor. For GraphQL it is manual (`withInterface(...)`) and must appear on every concrete implementation, including per-parametrized types built inside dedicated per-key builders (§4).

---

## 3. Field overrides and the "signature must match" rule

### 3.1 The GraphQL rule

> An object type implementing an interface must declare every interface field with an **identical signature** — same name, same return type, and same argument list. Adding extra non-nullable arguments is a schema-validation error.

Symptom:
```
invalid schema: object type 'BaseHistogram' field 'buckets' defines an additional non-optional
argument 'requestedCount' which is not allowed because field is also defined in interface 'Histogram'
```

### 3.2 Overriding a transformer-generated field

`ObjectDescriptorToGraphQLInterfaceTransformer` and `ObjectDescriptorToGraphQLObjectTransformer` both walk `staticProperties` and emit one field per property via `fieldBuilderTransformer`. That field has **no arguments** — only the name/description/type from the descriptor.

If any implementation needs to add arguments to a field, the interface **must declare the same arguments**. Do this by:

1. Extracting the field definition into a helper method (single source of truth).
2. Calling the transformer first, then `.field(helper())` — `GraphQLInterfaceType.Builder#field` and `GraphQLObjectType.Builder#field` replace any earlier entry with the same name (the internal storage is keyed by name).

```java
// helper — single definition of `buckets` with its arguments
@Nonnull
private GraphQLFieldDefinition buildHistogramBucketsField() {
    return HistogramDescriptor.BUCKETS
        .to(this.fieldBuilderTransformer)
        .argument(BucketsFieldHeaderDescriptor.REQUESTED_COUNT.to(this.argumentBuilderTransformer))
        .argument(BucketsFieldHeaderDescriptor.BEHAVIOR.to(this.argumentBuilderTransformer))
        .build();
}

// interface — .to(...) emits the 4 base fields, then .field(...) replaces `buckets`
final GraphQLInterfaceType histogramInterface = HistogramDescriptor.THIS_INTERFACE
    .to(this.interfaceBuilderTransformer)
    .field(buildHistogramBucketsField())
    .build();

// concrete implementation — same override so the signatures line up
return HistogramDescriptor.THIS
    .to(this.objectBuilderTransformer)
    .withInterface(typeRef(HistogramDescriptor.THIS_INTERFACE.name()))
    .field(buildHistogramBucketsField())
    .build();
```

If the helper lives on a different builder class than the one declaring the concrete implementation, pass it in as a `Supplier<GraphQLFieldDefinition>` or expose it via a shared utility — whichever fits the existing class boundaries. Apply the same helper in every concrete implementation, including per-parametrized types built in dedicated builders (§4).

### 3.3 Dynamic typing of descriptor-less fields

When a `PropertyDescriptor` is declared without a type (its `.type(...)` call is omitted), the type is plugged per-instance at build time. Use the descriptor itself (not a copy) so the name/description are preserved:

```java
typeBuilder.field(
    ReferenceHistogramDescriptor.MIN_REFERENCED_ENTITY
        .to(this.fieldBuilderTransformer)
        .type(referencedEntityObject)     // plugged at build time
        .build()
);
```

The same call in REST uses `this.propertyBuilderTransformer`:

```java
objectBuilder.property(
    ReferenceHistogramDescriptor.MIN_REFERENCED_ENTITY
        .to(this.propertyBuilderTransformer)
        .type(referencedEntityObject)
);
```

---

## 4. Parametrized object cache (per-context deduplication)

When a descriptor is a template that produces a family of concrete types (e.g. per-reference-schema, per-referenced-entity-type), you must avoid building the same type twice. There are two caching strategies, and the choice matters:

- **Cache by `(owningEntity, reference)`** — a new concrete type per `(owner, reference)` pair. Use only when the type genuinely varies between owners.
- **Cache by referenced-entity-type only** — a single concrete type shared by every reference targeting the same entity. Prefer this when the target entity schema alone determines the shape.

### 4.1 Cache key records

One immutable record per cache, co-located with the builder that uses it:

```java
public record ReferenceHistogramKey(@Nonnull String referencedEntityType) {
}
```

Use `record` so `equals`/`hashCode` are free. Keep the record in the same package as the builder that owns the cache semantics.

### 4.2 GraphQL — cache on the building context

Caches live on the **building context** that spans the lifetime of schema construction (for catalog-level types this is `CatalogGraphQLSchemaBuildingContext`; for collection-level types it is the narrower collection context). Each cache exposes a `getOrCompute...` method that registers the type with the schema the first time it is built:

```java
@Nonnull private final Map<ReferenceHistogramKey, GraphQLObjectType> referenceHistogramObjects;

// in the constructor, pre-size to the expected cardinality
this.referenceHistogramObjects = createHashMap(uniqueReferencedEntityTypesCount);

@Nonnull
public GraphQLObjectType getOrComputeReferenceHistogramObject(
    @Nonnull ReferenceHistogramKey key,
    @Nonnull Supplier<GraphQLObjectType> builder
) {
    return this.referenceHistogramObjects.computeIfAbsent(
        key,
        k -> {
            final GraphQLObjectType newObject = builder.get();
            this.registerType(newObject);
            return newObject;
        }
    );
}
```

For interfaces, the cache value is `GraphQLInterfaceType` and the registration also calls `registerTypeResolver(newInterface, HelperInterfaceTypeResolver.getInstance())`.

Pick the narrowest building context that still spans every caller. A type shared across entity collections belongs on the catalog context; a type scoped to one collection belongs on that collection's context.

### 4.3 Dedicated builder class

Extract each cached per-key builder into a small `@RequiredArgsConstructor` class. Its constructor accepts:

- The building context that owns the cache (for `getOrCompute...`).
- The transformers it needs (object / interface / field / property / argument).
- Any sibling builders it delegates to.

```java
@RequiredArgsConstructor
public class ReferenceHistogramObjectBuilder {
    @Nonnull private final CatalogGraphQLSchemaBuildingContext buildingContext;
    @Nonnull private final ObjectDescriptorToGraphQLObjectTransformer objectBuilderTransformer;
    @Nonnull private final PropertyDescriptorToGraphQLFieldTransformer fieldBuilderTransformer;
    @Nonnull private final PropertyDescriptorToGraphQLArgumentTransformer argumentBuilderTransformer;

    @Nonnull
    public GraphQLObjectType getOrBuild(@Nonnull ReferenceSchemaContract referenceSchema) {
        final String referencedEntityType = referenceSchema.getReferencedEntityType();
        final ReferenceHistogramKey key = new ReferenceHistogramKey(referencedEntityType);
        return this.buildingContext.getOrComputeReferenceHistogramObject(
            key,
            () -> buildReferenceHistogramObject(referenceSchema, referencedEntityType)
        );
    }
    // ...
}
```

Inject (or instantiate) the builder from whatever parent slice owns the enclosing type. Any caller that needs the cached type calls `.getOrBuild(key)`; the cache ensures a single registration per key.

### 4.4 REST — `getRegisteredType(name)` as cache

REST does not keep a separate cache map — `RestBuildingContext.getRegisteredType(name)` serves as the de-duplication point. The builder computes the final type name first, then:

```java
return this.buildingContext.getRegisteredType(typeName)
    .orElseGet(() -> buildAndRegister(typeName, ...));
```

Name the type deterministically, including every cache-key component in the name so two different keys never collide on the same name (e.g. `{referencedEntityType}[Localized]Histogram`). The localisation flag must be part of the name when localised and non-localised variants coexist.

---

## 5. GraphQL data fetchers

Every field on a GraphQL type needs a `DataFetcher`. There are three flavours.

### 5.1 Plain bean accessor

For fields that map 1:1 to a POJO getter, use `graphql.schema.PropertyDataFetcher.fetching(Type::getter)` directly — no dedicated class. This is the fallback when the builder registers a field without an explicit fetcher.

### 5.2 Singleton passthrough / simple getter

Stateless fetchers are singletons. Use `@NoArgsConstructor(access = AccessLevel.PRIVATE)` + a static `getInstance()`:

```java
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ReferenceHistogramMinEntityDataFetcher implements DataFetcher<SealedEntity> {
    private static final ReferenceHistogramMinEntityDataFetcher INSTANCE = new ReferenceHistogramMinEntityDataFetcher();

    @Nonnull
    public static ReferenceHistogramMinEntityDataFetcher getInstance() { return INSTANCE; }

    @Nullable
    @Override
    public SealedEntity get(DataFetchingEnvironment environment) throws Exception {
        final HistogramContract histogram = environment.getSource();
        if (histogram == null) return null;
        return histogram.getMinReferencedEntity().orElse(null);
    }
}
```

Pair this with a **wrapper fetcher** for parent fields that merely passes the source through so child fields can extract from it:

```java
public class HistogramStatisticsWrapperDataFetcher implements DataFetcher<ReferenceGroupStatistics> {
    // ... returns environment.getSource()
}
```

### 5.3 Parametrized per-field fetcher

When the fetcher needs an identifier (e.g. a named histogram index to pluck from a map) use a per-instance fetcher:

```java
@RequiredArgsConstructor
public class HistogramStatisticsDataFetcher implements DataFetcher<HistogramContract> {
    @Nonnull private final String histogramIndexName;

    @Nullable
    @Override
    public HistogramContract get(DataFetchingEnvironment environment) throws Exception {
        final ReferenceGroupStatistics stats = environment.getSource();
        return stats == null ? null : stats.getHistogramStatistics(this.histogramIndexName);
    }
}
```

### 5.4 Wiring a fetcher

Attach the fetcher to a specific object + field via `buildingContext.registerFieldToObject`:

```java
this.buildingContext.registerFieldToObject(
    typeName,
    typeBuilder,
    new BuiltFieldDescriptor(
        ReferenceHistogramDescriptor.MIN_REFERENCED_ENTITY
            .to(this.fieldBuilderTransformer)
            .type(referencedEntityObject)
            .build(),
        ReferenceHistogramMinEntityDataFetcher.getInstance()
    )
);
```

If the field is appended directly via `.field(...)` instead of `registerFieldToObject`, register the fetcher separately via `buildingContext.registerDataFetcher(objectName, propertyDescriptor, fetcher)`.

### 5.5 REST has no fetchers

REST serialises the response tree directly from the Java DTO. Jackson's default bean reflection walks getters on the DTO and emits a JSON field for each. See §6 for why this can bite.

---

## 6. REST JSON serialization — explicit serializer for anything with `Optional<SealedEntity>`

Jackson refuses to serialize `Optional<T>` by default (requires the `jackson-datatype-jdk8` module, which evitaDB does not enable). Any getter on a DTO returning `Optional<SealedEntity>` — including default interface methods — trips `valueToTree(...)` with:

```
Java 8 optional type `java.util.Optional<io.evitadb.api.requestResponse.data.SealedEntity>`
not supported by default: add Module "com.fasterxml.jackson.datatype:jackson-datatype-jdk8"
```

Symptom: HTTP 500 on queries where a `valueToTree`-serialized DTO contains (or could contain) an `Optional<SealedEntity>` field.

### 6.1 Fix: explicit field-by-field serializer

Never use `objectMapper.valueToTree(dto)` on a DTO whose contract exposes `Optional<SealedEntity>` getters. Instead, write an explicit serializer that lives alongside the other REST serializers for that concern, emits each field manually, and calls the shared `EntityJsonSerializer` path (via `serializeEntity(...)`) for entity values:

```java
@Nonnull
private JsonNode serializeHistogram(@Nonnull HistogramContract histogram, @Nonnull CatalogSchemaContract catalogSchema) {
    final ObjectNode node = this.objectJsonSerializer.objectNode();
    node.putIfAbsent(HistogramDescriptor.MIN.name(), this.objectJsonSerializer.serializeObject(histogram.getMin()));
    node.putIfAbsent(HistogramDescriptor.MAX.name(), this.objectJsonSerializer.serializeObject(histogram.getMax()));
    node.put(HistogramDescriptor.OVERALL_COUNT.name(), histogram.getOverallCount());
    node.putIfAbsent(HistogramDescriptor.BUCKETS.name(),
        this.objectJsonSerializer.getObjectMapper().valueToTree(histogram.getBuckets()));
    histogram.getMinReferencedEntity().ifPresent(entity -> node.putIfAbsent(
        ReferenceHistogramDescriptor.MIN_REFERENCED_ENTITY.name(),
        serializeEntity(entity, catalogSchema)));
    histogram.getMaxReferencedEntity().ifPresent(entity -> node.putIfAbsent(
        ReferenceHistogramDescriptor.MAX_REFERENCED_ENTITY.name(),
        serializeEntity(entity, catalogSchema)));
    return node;
}
```

Use the **same serializer** for all code paths that touch the DTO (e.g. attribute histogram, price histogram, reference-scope histogram all share one histogram serializer). Anchor-entity branches short-circuit when the optionals are empty, so the output shape stays unchanged for DTOs that never carry them.

---

## 7. gRPC protobuf + converters

### 7.1 Adding proto fields

All proto files live under `evita_external_api_grpc/shared/src/main/resources/META-INF/io/evitadb/externalApi/grpc/`. After editing:

```
mvn generate-sources -pl evita_external_api/evita_external_api_grpc/shared
```

The `protobuf-maven-plugin` regenerates Java under `src/main/java/io/evitadb/externalApi/grpc/generated/`. The maven-replacer plugin restamps licence headers automatically.

### 7.2 Message fields are inherently optional

proto3 message-typed fields do not need the `optional` keyword — the generated code always emits `has*()` and `get*()` accessors. Prefer message types for anything optional rather than wrapper types (except for primitive `Int32Value`/`StringValue` which require `google/protobuf/wrappers.proto`).

```proto
message GrpcHistogram {
  GrpcBigDecimal min = 1;
  GrpcBigDecimal max = 2;
  int32 overallCount = 3;
  repeated GrpcBucket buckets = 4;
  GrpcSealedEntity minReferencedEntity = 5;   // optional without keyword — use hasMinReferencedEntity()
  GrpcSealedEntity maxReferencedEntity = 6;
}
```

### 7.3 Enum backward compatibility

Two iron rules:

1. **Never change an existing enum value's tag.** Old clients and any serialized bytes on disk rely on the tag. Only append new values with fresh tags. Keep the default (tag 0) meaningful — proto3 decodes an unset field to that value.
2. **Enum value names share a file-level namespace in proto3.** A plain `NONE` in `GrpcFacetStatisticsDepth` collides with `NONE` in `GrpcPriceInnerRecordHandling` defined in the same file. The compiler error is `"NONE" is already defined in "io.evitadb.externalApi.grpc.generated"`. Prefix new values when a clash exists: `STATISTICS_NONE = 2`.

When a request-only enum (never emitted by the server) gains a new value, old clients are unaffected — they never receive the new tag. When a response enum gains a value, cap rollout or gate behind client-version detection.

### 7.4 Server-side builders — thread `clientVersion`

Builders that convert Java DTOs to gRPC messages live in `evita_external_api_grpc/server/.../builders/**/`. If the new DTO embeds `SealedEntity`, the builder must accept a `@Nullable SemVer clientVersion` and forward it to `EntityConverter.toGrpcSealedEntity(entity, clientVersion)`:

```java
@Nonnull
static GrpcHistogram buildHistogram(@Nonnull HistogramContract histogram, @Nullable SemVer clientVersion) {
    final GrpcHistogram.Builder builder = GrpcHistogram.newBuilder()
        .setMin(toGrpcBigDecimal(histogram.getMin()))
        .setMax(toGrpcBigDecimal(histogram.getMax()))
        .setOverallCount(histogram.getOverallCount())
        .addAllBuckets(...);
    histogram.getMinReferencedEntity().ifPresent(entity ->
        builder.setMinReferencedEntity(EntityConverter.toGrpcSealedEntity(entity, clientVersion)));
    histogram.getMaxReferencedEntity().ifPresent(entity ->
        builder.setMaxReferencedEntity(EntityConverter.toGrpcSealedEntity(entity, clientVersion)));
    return builder.build();
}
```

Each caller upstream already has a `clientVersion` in scope — pass it through. Keep the builder's signature consistent across DTO flavours that may not embed entities (e.g. attribute/price histograms) to avoid a dual code path.

### 7.5 Client-side converters — thread entity context

`ResponseConverter` turns `GrpcXxx` messages into Java DTOs. Simple DTOs use a one-arg `toXxx(grpc)` overload; DTOs that embed `SealedEntity` need entity-fetching context and therefore a richer overload:

```java
@Nonnull
private static Histogram toHistogram(@Nonnull GrpcHistogram grpcHistogram) {
    // used where the DTO never carries entities
    return new Histogram(buckets, toBigDecimal(grpcHistogram.getMax()));
}

@Nonnull
private static Histogram toHistogram(
    @Nonnull Function<GrpcSealedEntity, SealedEntitySchema> entitySchemaFetcher,
    @Nonnull EvitaRequest evitaRequest,
    @Nullable EntityFetch entityFetch,
    @Nonnull GrpcHistogram grpcHistogram
) {
    final SealedEntity minEntity = grpcHistogram.hasMinReferencedEntity()
        ? EntityConverter.toEntity(
            entitySchemaFetcher,
            evitaRequest.deriveCopyWith(grpcHistogram.getMinReferencedEntity().getEntityType(), entityFetch),
            grpcHistogram.getMinReferencedEntity(),
            SealedEntity.class, SEALED_ENTITY_TYPE_CONVERTER)
        : null;
    // ... and same for max
    return new Histogram(buckets, toBigDecimal(grpcHistogram.getMax()), minEntity, maxEntity);
}
```

Call the rich overload from the parent deserializer that already has the context (`entitySchemaFetcher`, `evitaRequest`, `entityFetch`). The `entityFetch` usually comes from the same require constraint that caused the engine to populate the entity in the first place.

### 7.6 Enum converter — new values everywhere

`EvitaEnumConverter` has switch-case methods in both directions. Add the new value to **both** methods and cover the `UNRECOGNIZED` gRPC sentinel with an explicit error:

```java
return switch (grpcFacetStatisticsDepth) {
    case COUNTS -> FacetStatisticsDepth.COUNTS;
    case IMPACT -> FacetStatisticsDepth.IMPACT;
    case STATISTICS_NONE -> FacetStatisticsDepth.NONE;
    case UNRECOGNIZED -> throw new EvitaInvalidUsageException("Unrecognized remote facet statistics depth: " + grpcFacetStatisticsDepth);
};
```

The Java-to-gRPC direction must be exhaustive over the Java enum (the switch expression forces this at compile time).

---

## 8. Local install ordering when running tests

The test module (`evita_test/evita_functional_tests`) resolves sibling modules through the **local Maven repo** rather than the reactor. After any change to `evita_api` / `evita_query` / `evita_external_api_*` / `evita_test_support`, run:

```
mvn install -pl <changed-module> -am -DskipTests -Dmaven.test.skip=true
```

before running surefire, otherwise the test JVM loads a stale snapshot jar and you will see `NoSuchMethodError`/`invalid schema` noise that has nothing to do with your change.

To run a specific test without the full lifecycle:

```
mvn surefire:test -pl evita_test/evita_functional_tests \
    -Dtest=YourTest -DSKIP_TESTS=false \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -DsurefireArgLine=""
```

The empty `-DsurefireArgLine=""` overrides the `${surefireArgLine}` placeholder that Jacoco's `prepare-agent` normally populates; without it the surefire fork crashes with `ClassNotFoundException: ${surefireArgLine}`.

---

## 9. Troubleshooting checklist (symptom → file to touch)

| Symptom | Root cause | Fix in |
|---|---|---|
| `invalid schema: object type 'X' field 'y' defines an additional non-optional argument` | Interface's `y` has no args but implementation's `y` does | Extract field into helper, use it in both interface and implementation (§3.2) |
| `"NAME" is already defined in "io.evitadb.externalApi.grpc.generated"` | Enum value clash in a proto file | Prefix the new value (`STATISTICS_NONE` not `NONE`) (§7.3) |
| HTTP 500 with `Java 8 optional type ... not supported by default` | REST path uses `valueToTree` on a DTO exposing `Optional<...>` getters | Explicit field-by-field serializer (§6.1) |
| `NoSuchMethodError` on a method you just added | Stale snapshot jar in `~/.m2/repository` | `mvn install -pl <module> -am -DskipTests` before running tests (§8) |
| Constraint roundtrip prints `someClassName` instead of the EvitaQL keyword | Constructor skipped passing `CONSTRAINT_NAME` to `super(...)` so `BaseConstraint.getName()` falls back to class-derived name | Ensure every public constructor forwards `CONSTRAINT_NAME` (or overrides `getName()`) |
| GraphQL `__typename` changed for an existing concrete type | A concrete type was promoted into an interface (name taken by interface) | Acceptable if field-selection stays compatible; document in release notes |
| OpenAPI loses field-level inheritance (`allOf` missing) | Concrete descriptor built with `from(base)` instead of `implementing(base)` | Switch to `implementing(...)` when polymorphism is needed (§1.3) |
