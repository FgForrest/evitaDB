---
name: evita-schema-change
description: Use when adding a new field, enum, or configuration option to any evitaDB schema type (ReferenceSchema, EntitySchema, AttributeSchema, etc.). Provides a comprehensive 8-layer recipe covering contracts, DTOs, builders, mutations, external APIs (gRPC/GraphQL/REST) with backward compatibility, Kryo serializers, and WAL serializers.
---

# evitaDB Schema Change Recipe

## Overview

This skill is a step-by-step recipe for adding a new field or configuration option to any evitaDB schema type. It covers all 8 layers that must be modified, generalized from multiple reference implementations in the codebase.

### When to Use

- Adding a new field of **any type** (boolean, enum, string, scoped collection, etc.) to any schema type
- Extending schema mutations with new parameters
- Any change that must propagate through contracts -> DTOs -> builders -> mutations -> external APIs -> serializers

### Field Type Variants

The recipe adapts based on the type of field being added:

| Field Type | Contract Getter | DTO Storage | Mutation Field | Serialization |
|---|---|---|---|---|
| **Simple boolean** | `boolean isNewField()` | `boolean newField` | `boolean newField` | `output.writeBoolean()` |
| **Simple enum** | `NewEnum getNewField()` | `NewEnum newField` | `NewEnum newField` | `kryo.writeObject()` |
| **Nullable value** | `@Nullable T getNewField()` | `@Nullable T newField` | `@Nullable T newField` | boolean flag + value |
| **Scope-aware flag** | three-tier getters (see below) | `Map<Scope, T>` | `ScopedNewField[]` | array of scope+value pairs |
| **Scope-aware collection** | three-tier getters (see below) | `Map<Scope, Set<NewEnum>>` | `ScopedNewField[]` | nested scope+set pairs |

Each layer section below shows the **scope-aware collection** pattern (most complex) with callouts for simpler variants.

### Notation Conventions

| Placeholder | Meaning | Example |
|---|---|---|
| `NewField` / `newField` | The new field being added | `indexedComponents` |
| `NewEnum` | A new enum type (if applicable) | `ReferenceIndexedComponents` |
| `ScopedNewField` | Scoped wrapper record (if scope-aware) | `ScopedReferenceIndexedComponents` |
| `SchemaType` | The schema type being extended | `ReferenceSchema`, `AttributeSchema` |
| `SchemaTypeContract` | The schema contract interface | `ReferenceSchemaContract` |
| `YYYY_M` | Version identifier for backward compat | `2026_2` |

---

## Layer 1: Schema Contracts & Editors

**Module:** `evita_api`
**Package:** `io.evitadb.api.requestResponse.schema`

### 1.1 Define a New Enum (if applicable)

Only needed when the field introduces a new enumeration. Skip for boolean/string fields.

```java
public enum NewEnum {
	/** Description of first value. */
	VALUE_ONE,
	/** Description of second value. */
	VALUE_TWO
}
```

### 1.2 Add Getters to the Schema Contract

**For scope-aware fields** — use the three-tier getter pattern (default scope -> specific scope -> all scopes):

```java
@Nonnull
default Set<NewEnum> getNewField() {
	return getNewField(Scope.DEFAULT_SCOPE);
}

@Nonnull
Set<NewEnum> getNewField(@Nonnull Scope scope);

@Nonnull
Map<Scope, Set<NewEnum>> getNewFieldInScopes();
```

**For simple fields** — a single getter suffices:

```java
// Boolean:
boolean isNewField();

// Enum:
@Nonnull
NewEnum getNewField();

// Nullable:
@Nullable
String getNewField();
```

### 1.3 Add Editor Methods

Fluent methods returning the builder type `T`:

```java
// Scope-aware:
@Nonnull
default T withNewField(@Nonnull NewEnum... values) {
	return withNewFieldInScope(Scope.DEFAULT_SCOPE, values);
}

@Nonnull
T withNewFieldInScope(@Nonnull Scope scope, @Nonnull NewEnum... values);

// Simple boolean:
@Nonnull
T withNewField();

@Nonnull
T withoutNewField();
```

### 1.4 Reflected Reference Schema (ReferenceSchema changes only)

**This step applies only when modifying `ReferenceSchema`**, because `ReflectedReferenceSchema` inherits settings from its target reference. Other schema types do not have a reflected variant.

```java
// In ReflectedReferenceSchemaContract.java:
boolean isNewFieldInherited();

// In ReflectedReferenceSchemaEditor.java:
@Nonnull
S withNewFieldInherited();
```

### Checklist

- [ ] New enum created (if applicable)
- [ ] Getters added to `SchemaTypeContract` (three-tier for scope-aware, single for simple)
- [ ] Editor fluent methods added to `SchemaTypeEditor`
- [ ] `@Nonnull` / `@Nullable` annotations on all parameters and return types
- [ ] JavaDoc with Markdown formatting on all new methods
- [ ] *(ReferenceSchema only)* `isNewFieldInherited()` in `ReflectedReferenceSchemaContract`
- [ ] *(ReferenceSchema only)* `withNewFieldInherited()` in `ReflectedReferenceSchemaEditor`

---

## Layer 2: Schema DTOs

**Module:** `evita_api`
**Package:** `io.evitadb.api.requestResponse.schema.dto`

### 2.1 Add Field and Constructor Parameter

```java
// Scope-aware:
protected final Map<Scope, Set<NewEnum>> newFieldInScopes;
// In constructor:
this.newFieldInScopes = CollectionUtils.toUnmodifiableMap(newFieldInScopes);

// Simple:
private final boolean newField;
```

### 2.2 Implement Contract Getters

```java
// Scope-aware:
@Nonnull
@Override
public Set<NewEnum> getNewField(@Nonnull Scope scope) {
	final Set<NewEnum> values = this.newFieldInScopes.get(scope);
	return values != null ? values : Collections.emptySet();
}

@Nonnull
@Override
public Map<Scope, Set<NewEnum>> getNewFieldInScopes() {
	return this.newFieldInScopes;
}
```

### 2.3 Static Converter Methods (scope-aware fields only)

For scope-aware fields, add conversion helpers on the DTO class:

- **`toNewFieldEnumMap(ScopedNewField[])`** — converts scoped array to `Map<Scope, Set<NewEnum>>`
- **`defaultNewField(Map<Scope, ...>)`** — creates default values for scopes that need them
- **`resolveNewField(ScopedNewField[], ...)`** — resolves explicit array or falls back to defaults

These follow the pattern of existing methods like `toReferenceIndexEnumMap()` and `resolveIndexedComponents()` in `ReferenceSchema.java`.

### 2.4 Update `_internalBuild()` Overloads

Add the new parameter to all `_internalBuild()` overloads. There are typically two forms:

```java
// Form 1: Array/primitive-based (used by mutations and serializers)
@Nonnull
public static SchemaType _internalBuild(
	// ... existing params ...
	@Nullable ScopedNewField[] newFieldInScopes,  // scope-aware
	// OR: boolean newField,                       // simple
	// ... remaining params ...
)

// Form 2: Map/final-type-based (used by internal construction)
@Nonnull
public static SchemaType _internalBuild(
	// ... existing params ...
	@Nonnull Map<Scope, Set<NewEnum>> newFieldInScopes,  // scope-aware
	// OR: boolean newField,                               // simple
	// ... remaining params ...
)
```

### 2.5 Reflected Reference Schema DTO (ReferenceSchema changes only)

In `ReflectedReferenceSchema.java`, add inheritance resolution:

```java
private final boolean newFieldInherited;

// In constructor:
this.newFieldInherited = newFieldInScopes == null;
```

Add a private static resolution method that checks: explicit value -> inherited from reflected reference -> default fallback.

### 2.6 Update equals() / hashCode() / toString()

Include the new field in all three methods on the DTO.

### Checklist

- [ ] Field added to DTO with immutable wrapping
- [ ] Contract getter methods implemented
- [ ] Static converter/resolver methods added (scope-aware fields)
- [ ] Both `_internalBuild()` overloads updated
- [ ] `equals()` / `hashCode()` / `toString()` updated
- [ ] *(ReferenceSchema only)* Reflected DTO inheritance resolution added

---

## Layer 3: Builders

**Module:** `evita_api`
**Package:** `io.evitadb.api.requestResponse.schema.builder`

### 3.1 Implement Editor Method

Builders accumulate mutations — they do NOT store the field directly:

```java
@Nonnull
@Override
public SchemaTypeBuilder withNewFieldInScope(
	@Nonnull Scope scope,
	@Nonnull NewEnum... values
) {
	this.updatedSchemaDirty = updateMutationImpact(
		this.updatedSchemaDirty,
		addMutations(
			this.catalogSchema, this.entitySchema, this.mutations,
			new SetSchemaTypeNewFieldMutation(
				getName(),
				/* ... existing related fields ... */,
				new ScopedNewField[]{
					new ScopedNewField(scope, values)
				}
			)
		)
	);
	return this;
}
```

### 3.2 Update CreateMutation Initialization

In the builder constructor (`if (createNew)` block), pass `null` for the new field to indicate defaults:

```java
this.mutations.add(
	new CreateSchemaTypeSchemaMutation(
		/* ... existing args ... */,
		null,  // newFieldInScopes — null means use defaults
		/* ... remaining args ... */
	)
);
```

### 3.3 Reflected Builder (ReferenceSchema changes only)

```java
@Nonnull
@Override
public ReflectedReferenceSchemaBuilder withNewFieldInherited() {
	this.updatedSchemaDirty = updateMutationImpact(
		this.updatedSchemaDirty,
		addMutations(
			this.catalogSchema, this.entitySchema, this.mutations,
			new SetSchemaTypeNewFieldMutation(
				getName(),
				/* ... */,
				(ScopedNewField[]) null  // null = inherited
			)
		)
	);
	return this;
}
```

### Checklist

- [ ] Editor method implemented — creates mutation, does NOT store field
- [ ] CreateMutation initialization passes `null` for new field (defaults)
- [ ] Builder returns `this` for fluent chaining
- [ ] *(ReferenceSchema only)* Reflected builder `withNewFieldInherited()`

---

## Layer 4: Mutations

**Module:** `evita_api`
**Package:** `io.evitadb.api.requestResponse.schema.mutation.*`

### 4.1 Create Scoped Wrapper Record (scope-aware fields only)

If the new field is scope-aware, create a `ScopedNewField` record:

```java
public record ScopedNewField(
	@Nonnull Scope scope,
	@Nonnull NewEnum[] values
) implements Serializable {

	public static final ScopedNewField[] EMPTY = new ScopedNewField[0];

	public ScopedNewField {
		Assert.notNull(scope, "Scope must not be null");
		Assert.notNull(values, "Values must not be null");
	}

	// IMPORTANT: Override equals/hashCode because arrays use reference equality
	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ScopedNewField that)) return false;
		return scope == that.scope && Arrays.equals(values, that.values);
	}

	@Override
	public int hashCode() {
		int result = scope.hashCode();
		result = 31 * result + Arrays.hashCode(values);
		return result;
	}

	@Override
	public String toString() {
		return "ScopedNewField[scope=" + scope +
			", values=" + Arrays.toString(values) + ']';
	}
}
```

### 4.2 Update Create Mutation

Add the field to both constructor overloads:

```java
@Getter @Nullable private final ScopedNewField[] newFieldInScopes;
// OR for simple: @Getter private final boolean newField;

// Simple constructor chains to full constructor with null/default:
this(/* ... */, null, /* ... */);

// Full constructor (@SerializableCreator):
this.newFieldInScopes = newFieldInScopes;
```

Update `mutate()` to pass the field to `_internalBuild()`.

Update `combineWith()` to add a `makeMutationIfDifferent()` call comparing the new field.

### 4.3 Create or Update Set Mutation

Add the new field as a parameter to the set mutation. Follow the constructor hierarchy pattern (simple -> detailed -> full with `@SerializableCreator`).

### 4.4 Mutation Combination — Merging

When combining mutations with the same name, merge scoped fields by putting newer scope entries over older ones. For `null` (inherited), let `null` win as the latest mutation.

### 4.5 Reflected Change Detection (ReferenceSchema changes only)

```java
private boolean hasNewFieldChanged(@Nonnull ReflectedReferenceSchema schema) {
	if (this.newFieldInScopes == null) {
		return !schema.isNewFieldInherited();
	}
	if (schema.isNewFieldInherited()) {
		return true;
	}
	return !schema.getNewFieldInScopes().equals(
		SchemaType.toNewFieldEnumMap(this.newFieldInScopes)
	);
}
```

### Checklist

- [ ] Scoped wrapper record created (if scope-aware) with `EMPTY` constant, validation, custom `equals`/`hashCode`
- [ ] Create mutation updated — both constructors, field, `mutate()`, `combineWith()`
- [ ] Set mutation updated (or created) with new field parameter
- [ ] Mutation combination properly merges fields
- [ ] *(ReferenceSchema only)* Create reflected mutation updated
- [ ] *(ReferenceSchema only)* Reflected change detection implemented

---

## Layer 5: External API Core — Descriptors & Converters

**Module:** `evita_external_api_core`
**Package:** `io.evitadb.externalApi.api.catalog.schemaApi.model`

### 5.1 Create Scoped Descriptor (scope-aware fields only)

For scope-aware fields, create `ScopedNewFieldDescriptor.java` extending `ScopedDataDescriptor`:

```java
public interface ScopedNewFieldDescriptor extends ScopedDataDescriptor {
	PropertyDescriptor VALUES = PropertyDescriptor.builder()
		.name("values")
		.description(/* ... */)
		.type(nonNull(NewEnum[].class))
		.build();

	ObjectDescriptor THIS = ObjectDescriptor.builder()
		.name("ScopedNewField")
		.staticProperties(List.of(SCOPE, VALUES))
		.build();

	ObjectDescriptor THIS_INPUT = ObjectDescriptor.from(THIS)
		.name("InputScopedNewField")
		.build();
}
```

### 5.2 Add PropertyDescriptor to Schema Descriptor

```java
// In SchemaTypeDescriptor.java:
PropertyDescriptor NEW_FIELD = PropertyDescriptor.builder()
	.name("newField")
	.description(/* ... */)
	.type(/* nonNull(Boolean.class) or nonNullListRef(ScopedNewFieldDescriptor.THIS) */)
	.build();
```

### 5.3 Add to Mutation Descriptors

Add `PropertyDescriptor` to each mutation descriptor (`Create*Descriptor`, `Set*Descriptor`) with both output and input variants (using `PropertyDescriptor.from()` for the input variant).

### 5.4 Update Mutation Converters

For scope-aware fields, use `PropertyObjectListMapper` to deserialize nested structures:

```java
final ScopedNewField[] newFieldInScopes = input.getOptionalProperty(
	MutationDescriptor.NEW_FIELD_IN_SCOPES.name(),
	new PropertyObjectListMapper<>(
		getMutationName(),
		getExceptionFactory(),
		MutationDescriptor.NEW_FIELD_IN_SCOPES,
		ScopedNewField.class,
		nestedInput -> new ScopedNewField(
			nestedInput.getProperty(ScopedDataDescriptor.SCOPE),
			nestedInput.getProperty(ScopedNewFieldDescriptor.VALUES)
		)
	)
);
```

For simple fields, use direct `input.getProperty()` or `input.getOptionalProperty()`.

**Important — `getOptionalProperty()` overloads:** When reading array-typed properties (e.g., `Scope[]`), always use the **typed overload** `input.getOptionalProperty(name, Class)` — not the raw `input.getOptionalProperty(name)`. The raw overload returns the underlying `List` without type conversion, causing `ClassCastException` when the code expects an array. Example:

```java
// WRONG — returns raw List, will ClassCast to Scope[]:
final Scope[] scopes = input.getOptionalProperty(FACETED_IN_SCOPES.name());

// CORRECT — invokes toTargetType() -> toArrayOfSpecificType():
final Scope[] scopes = input.getOptionalProperty(FACETED_IN_SCOPES.name(), Scope[].class);
```

### 5.5 Custom Output Serialization for Non-Primitive Types

The base `MutationConverter.convertObjectToOutput()` uses reflection over `@SerializableCreator` constructor parameters and `Output.toSerializableValue()` for each value. **If any constructor parameter type is not natively supported** by `toSerializableValue()` (e.g., `Expression`, custom domain objects), the reflection-based output will throw.

To handle this, **override `convertToOutput(M mutation, Output output)`** in the converter to pre-set properties with custom serialization **before** calling `super.convertToOutput()`. Pre-set properties are skipped by the reflection logic (line ~167 of `MutationConverter`):

```java
@Override
protected void convertToOutput(
	@Nonnull MyMutation mutation,
	@Nonnull Output output
) {
	// Pre-serialize the non-primitive field BEFORE super call
	final ScopedFacetedPartially[] partially = mutation.getFacetedPartiallyInScopes();
	if (partially != null) {
		final List<Map<String, Object>> serialized = new ArrayList<>(partially.length);
		for (ScopedFacetedPartially entry : partially) {
			final Map<String, Object> entryMap = new LinkedHashMap<>(2);
			entryMap.put("scope", entry.scope());
			final Expression expr = entry.expression();
			entryMap.put("expression", expr != null ? expr.toExpressionString() : null);
			serialized.add(entryMap);
		}
		output.setProperty("facetedPartiallyInScopes", serialized);
	}
	super.convertToOutput(mutation, output);
}
```

**Tip:** If multiple converters need the same custom serialization, extract it as a `protected static` helper in the shared base class (e.g., `ReferenceSchemaMutationConverter`) to avoid code duplication.

### Checklist

- [ ] Scoped descriptor created (if scope-aware) with `THIS` and `THIS_INPUT`
- [ ] `PropertyDescriptor` added to schema descriptor
- [ ] Mutation descriptors updated (both output and input variants)
- [ ] Mutation converters updated with appropriate deserialization
- [ ] Typed `getOptionalProperty(name, Class)` overload used for array properties (not raw overload)
- [ ] `convertToOutput()` overridden for non-primitive types not supported by `Output.toSerializableValue()`

---

## Layer 6: External APIs

**Important — Backward Compatibility:** All external API changes must be **additive** (non-breaking). New fields must be **optional** or have sensible defaults so that older clients continue to work. Specifically:

- **gRPC:** New proto fields are optional by default in proto3. Never reuse field numbers. Old clients simply ignore unknown fields.
- **GraphQL:** New fields in output types are non-breaking. New fields in input types must be nullable/optional so existing mutations keep working.
- **REST:** New JSON fields in responses are non-breaking. New fields in request bodies must be optional with server-side defaults.

### 6a: gRPC

**Module:** `evita_external_api_grpc`

#### Proto Definitions

Add the new field to the relevant `.proto` files. Use the **next available field number** — never reuse old numbers, even for removed fields.

For a new enum, add to `GrpcEnums.proto`:

```protobuf
enum GrpcNewEnum {
	NEW_ENUM_VALUE_ONE = 0;
	NEW_ENUM_VALUE_TWO = 1;
}
```

For a scoped wrapper, add to `GrpcEvitaDataTypes.proto`:

```protobuf
message GrpcScopedNewField {
	GrpcEntityScope scope = 1;
	repeated GrpcNewEnum values = 2;
}
```

Add to schema and mutation messages in the appropriate `.proto` files. For simple booleans, use `bool` or `google.protobuf.BoolValue` (for nullable booleans).

After editing protos, **regenerate Java stubs** (build the `evita_external_api_grpc` module).

#### EvitaEnumConverter (if new enum)

Add bidirectional conversion methods. The `toEvita()` direction must handle `UNRECOGNIZED`:

```java
@Nonnull
public static NewEnum toNewEnum(@Nonnull GrpcNewEnum grpc) {
	return switch (grpc) {
		case NEW_ENUM_VALUE_ONE -> NewEnum.VALUE_ONE;
		case NEW_ENUM_VALUE_TWO -> NewEnum.VALUE_TWO;
		case UNRECOGNIZED ->
			throw new EvitaInvalidUsageException("Unrecognized: " + grpc);
	};
}
```

#### EntitySchemaConverter and Mutation Converters

Update `EntitySchemaConverter` and gRPC mutation converters to map the new field between gRPC and domain types. For scope-aware fields, the pattern uses `addAll*` on the builder and stream mapping for the reverse direction.

**Backward compatibility note:** gRPC `repeated` fields default to empty lists, and new `bool` fields default to `false`. Converters must handle these defaults gracefully — empty list should map to `null` (not provided), not to an empty configuration.

### 6b: GraphQL

**Module:** `evita_external_api_graphql`

1. **Type registration in Catalog API** — register `ScopedNewFieldDescriptor.THIS` and `THIS_INPUT` in `CommonEvitaSchemaSchemaBuilder`
2. **Type registration in System API** — register `ScopedNewFieldDescriptor.THIS` in `SystemGraphQLSchemaBuilder`. This is a **separate** GraphQL schema builder for the system-level API (health checks, catalog management, CDC subscriptions). It maintains its own list of registered types independently of the catalog schema builder. Missing this causes `graphql.AssertException: type ... not found in schema` errors in `EvitaServerTest` and all `SystemGraphQL*` tests.
3. **DataFetcher** — for scope-aware fields, create a singleton `DataFetcher` that converts `Map<Scope, Set<NewEnum>>` to `List<ScopedNewField>` for GraphQL output. For simple fields, no DataFetcher is needed — GraphQL Java resolves them from getters automatically.
4. **Register DataFetcher** in `EntitySchemaSchemaBuilder` (or the appropriate schema builder)

**Backward compatibility note:** New output fields are non-breaking. New input fields must be optional so existing mutations without the field still parse.

### 6c: REST

**Module:** `evita_external_api_rest`

1. **Type registration** in `EntitySchemaObjectBuilder`
2. **JSON serialization** — for scope-aware fields, add a serialization method in `SchemaJsonSerializer` that converts `Map<Scope, ...>` to a JSON array. Call it from `EntitySchemaJsonSerializer`. For simple fields, Jackson serializes them from the getter automatically.
3. **REST functional test DTO helpers** — update `CatalogRestSchemaEndpointFunctionalTest.createReferenceSchemaDto()` (or equivalent `create*SchemaDto()` method) to include the new field. For scope-aware fields, create a new `create*Dto()` helper method that converts the schema's map data into the expected JSON structure (list of maps with scope + value). Without this, all REST schema endpoint functional tests will fail with JSON path mismatches.

**Backward compatibility note:** New JSON fields in responses are non-breaking. Ensure request deserialization treats the new field as optional.

### Checklist

- [ ] **gRPC:** Proto definitions added with correct field numbers (never reused)
- [ ] **gRPC:** Java stubs regenerated
- [ ] **gRPC:** `EvitaEnumConverter` updated (if new enum)
- [ ] **gRPC:** `EntitySchemaConverter` and mutation converters updated
- [ ] **gRPC:** Empty/default handling is backward compatible
- [ ] **GraphQL:** Types registered in `CommonEvitaSchemaSchemaBuilder` (Catalog API)
- [ ] **GraphQL:** Types registered in `SystemGraphQLSchemaBuilder` (System API)
- [ ] **GraphQL:** DataFetcher created and registered (if scope-aware)
- [ ] **GraphQL:** Input types allow omission of new field
- [ ] **REST:** Types registered in `EntitySchemaObjectBuilder`
- [ ] **REST:** Serializer added/updated
- [ ] **REST:** Request deserialization treats new field as optional

---

## Layer 7: Kryo Schema Serializers

**Module:** `evita_store_server`
**Package:** `io.evitadb.store.schema.serializer`

### 7.1 Register New Enum (if applicable)

In `SchemaKryoConfigurer.java` — add at the **end** (before the assertion):

```java
kryo.register(NewEnum.class, new EnumNameSerializer<>(), index++);
```

Always use `EnumNameSerializer` for enums — it persists names (not ordinals), safe across reordering. Never insert in the middle — append to preserve stable index numbering.

### 7.2 Serial Version Hash Workflow

The `SerialVersionBasedSerializer` uses the target class's `serialVersionUID` (explicitly declared via `@Serial private static final long serialVersionUID = ...L;`) to detect format changes. Every serialized object is prefixed with this UID; on deserialization, the UID is read first to dispatch to the correct serializer version.

**Important — release-only backward compatibility:** We only maintain backward compatibility with the **latest released version** (the latest `release_YYYY-M` branch). If a model changes multiple times during a single development cycle (between releases), do NOT create intermediate backward-compatible serializers — just update the current serializer. Only the release format matters.

**Step-by-step process:**

1. **Determine the latest release branch and version suffix:**

   ```shell
   git branch -r --list 'origin/release_*' --sort=-v:refname | head -1
   ```
   This gives e.g. `origin/release_2026-1` → suffix `_2026_1`, annotation `@Deprecated(since = "2026.1")`.

2. **Compare `serialVersionUID`** of the target class between the release branch and the current branch:

   ```shell
   # Release branch UID:
   git show origin/release_YYYY-M:path/to/SchemaType.java | grep serialVersionUID
   # Current branch UID:
   grep serialVersionUID path/to/SchemaType.java
   ```

3. **Decision tree:**

   - **UIDs identical** → The target class hasn't changed since the release. No backward-compat serializer needed. Just update the current serializer to write/read the new field.

   - **UIDs differ AND `SchemaTypeSerializer_YYYY_M.java` already exists** → A backward-compat serializer for the release format was already created earlier in this dev cycle (from a previous change to this model). Just update the current serializer. No new backward-compat file needed.

   - **UIDs differ AND no `SchemaTypeSerializer_YYYY_M.java` exists** → This is the first change to this model since the release. Full workflow:

     a. **Get the release-version serializer** from the release branch:
        ```shell
        git show origin/release_YYYY-M:path/to/SchemaTypeSerializer.java
        ```
     b. **Save it** as `SchemaTypeSerializer_YYYY_M.java`. This becomes the backward-compatible reader for the release format.
     c. **Annotate** with `@Deprecated(since = "YYYY.M", forRemoval = true)`.
     d. **In the copy**, make `write()` throw `UnsupportedOperationException` and keep `read()` unchanged — it reads the release format.
     e. **Modify the original serializer** to write/read the new field.
     f. **Run the tests.** `SerialVersionBasedSerializer` will detect the UID mismatch and report the **old hash** in the error message. Capture this value.
     g. **Register** the backward-compatible serializer with the captured hash:

     ```java
     kryo.register(
     	SchemaType.class,
     	new SerialVersionBasedSerializer<>(
     		new SchemaTypeSerializer(), SchemaType.class  // current version
     	)
     		.addBackwardCompatibleSerializer(EXISTING_HASH_1, new SchemaTypeSerializer_OLD_1())
     		.addBackwardCompatibleSerializer(CAPTURED_OLD_HASH, new SchemaTypeSerializer_YYYY_M()),  // NEW
     	index++
     );
     ```

### 7.3 Update Current Serializer

Add the new field to write/read in the current serializer. Follow existing patterns in the file:

```java
// Simple boolean:
output.writeBoolean(schema.isNewField());
// Read:
final boolean newField = input.readBoolean();

// Enum via Kryo:
kryo.writeObject(output, schema.getNewField());
// Read:
final NewEnum newField = kryo.readObject(input, NewEnum.class);

// Nullable field — boolean flag pattern:
if (value != null) {
	output.writeBoolean(true);
	/* write value */
} else {
	output.writeBoolean(false);
}
// Read:
final T value = input.readBoolean() ? /* read value */ : null;
```

For `ReflectedReferenceSchema` (ReferenceSchema changes only), inherited fields use the boolean-flag pattern — `false` means inherited, `true` means explicitly set followed by the value.

### 7.4 Backward-Compatible Serializer

The backward-compat serializer reads the **release format** (without the new field). It must:

- Read fields in exactly the order the release version wrote them
- NOT read the new field (it doesn't exist in release data)
- Pass a default or `null` to the `_internalBuild()` method for the new field

```java
@Deprecated(since = "YYYY.M", forRemoval = true)
public class SchemaTypeSerializer_YYYY_M extends Serializer<SchemaType> {
	@Override
	public void write(Kryo kryo, Output output, SchemaType schema) {
		throw new UnsupportedOperationException(
			"This serializer is deprecated and should not be used for writing."
		);
	}

	@Override
	public SchemaType read(Kryo kryo, Input input, Class<? extends SchemaType> aClass) {
		// Read release format fields in original order...
		// Do NOT read the new field
		return SchemaType._internalBuild(/* old params, default for new field */);
	}
}
```

### Checklist

- [ ] `EnumNameSerializer` registered for new enum (if applicable)
- [ ] Release branch identified, `serialVersionUID` compared (see decision tree above)
- [ ] If first change since release: backward-compat serializer created from release-branch source, annotated `@Deprecated`, write throws
- [ ] If subsequent change (backward-compat already exists): just update the current serializer — no new backward-compat file
- [ ] Current serializer updated with new field read/write
- [ ] Tests run to capture old serial version hash (first change only)
- [ ] Backward-compatible serializer registered with captured hash in `SchemaKryoConfigurer` (first change only)
- [ ] *(ReferenceSchema only)* Both `ReferenceSchemaSerializer` and `ReflectedReferenceSchemaSerializer` updated

---

## Layer 8: WAL Mutation Serializers

**Module:** `evita_store_server`
**Package:** `io.evitadb.store.wal.schema.*`

### 8.1 Register New Enum (if applicable)

In `WalKryoConfigurer.java` — add at the **end** (before the assertion):

```java
kryo.register(NewEnum.class, new EnumNameSerializer<>(), index++);
```

### 8.2 Serial Version Hash Workflow

Follow the same **decision tree** as Layer 7.2 — compare the mutation class's `serialVersionUID` between the latest release branch and the current branch:

1. **UIDs identical** → No backward-compat needed, just update the current serializer.
2. **UIDs differ AND `MutationSerializer_YYYY_M.java` already exists** → Backward compat for the release format is already handled. Just update the current serializer.
3. **UIDs differ AND no backward-compat serializer exists** → First change since release. Full workflow:
   a. Get the release-version serializer from the release branch
   b. Save as `MutationSerializer_YYYY_M.java`, annotate `@Deprecated(since = "YYYY.M", forRemoval = true)`, make write throw `UnsupportedOperationException`
   c. Modify original to write/read new field
   d. Run tests, capture old hash from error message
   e. Register backward-compatible serializer with old hash in `WalKryoConfigurer`

### 8.3 Update Current Mutation Serializers

Use the boolean-flag + conditional pattern for nullable fields:

```java
// Write:
if (mutation.getNewFieldInScopes() != null) {
	output.writeBoolean(true);
	/* write the field data */
} else {
	output.writeBoolean(false);
}

// Read:
final ScopedNewField[] newFieldInScopes =
	input.readBoolean() ? /* read the field data */ : null;
```

For simple non-nullable fields (e.g., boolean), just write/read directly without the flag.

Use existing helper methods from `MutationSerializationFunctions` interface (e.g., `writeScopeArray`, `readScopeArray`, `writeScopedReferenceIndexTypeArray`) as reference. Add new helpers to the interface if needed by multiple serializers.

### 8.4 Backward-Compatible Mutation Serializers

The backward-compatible serializer reads the old format without the new field and passes `null` or a default to the mutation constructor. Each affected mutation needs its own backward-compatible serializer.

### Checklist

- [ ] `EnumNameSerializer` registered for new enum in `WalKryoConfigurer` (if applicable)
- [ ] Release branch identified, mutation class `serialVersionUID` compared (see decision tree above)
- [ ] If first change since release: backward-compat serializer(s) created from release-branch source and deprecated
- [ ] If subsequent change (backward-compat already exists): just update the current serializer — no new backward-compat file
- [ ] Current mutation serializers updated with new field
- [ ] Tests run to capture old hash(es) (first change only)
- [ ] `SerialVersionBasedSerializer` chain updated in `WalKryoConfigurer` for each mutation (first change only)

---

## Parallelization Guidance

### Dependency Diagram

```
Layers 1 → 2 → 3 → 4  (strictly sequential)
                  ↓
       ┌──────────┼──────────┐
       ↓          ↓          ↓
   Layer 5     Layer 7    Layer 8
       ↓       (Kryo)     (WAL)
  ┌────┼────┐
  ↓    ↓    ↓
 6a   6b   6c
gRPC  GQL  REST
```

### Safe Parallel Dispatch After Layer 4

| Group | Layers | Description |
|---|---|---|
| **A** | 5 -> 6a + 6b + 6c | External API core, then all three APIs in parallel |
| **B** | 7 | Kryo schema serializers |
| **C** | 8 | WAL mutation serializers |

Groups A, B, and C can run concurrently. Within Group A, the three external APIs (gRPC, GraphQL, REST) are independent after Layer 5 completes.

**Testing should happen after all groups complete**, since tests span all layers.

---

## Testing

**Module:** `evita_test/evita_functional_tests`

### Test Categories

| Category | Location | What to Test |
|---|---|---|
| Schema builder | `schema/SchemaTypeBuilderTest.java` | Fluent API, mutation generation |
| DTO | `schema/dto/SchemaTypeDTOTest.java` | Construction, getters, equality |
| Create mutation | `schema/mutation/.../CreateMutationTest.java` | `mutate()`, `combineWith()` |
| Set mutation | `schema/mutation/.../SetMutationTest.java` | Apply, combine, change detection |
| Scoped record | `schema/mutation/.../ScopedNewFieldTest.java` | Validation, equality, hashCode |
| Core converter | `schemaApi/resolver/mutation/.../ConverterTest.java` | Round-trip conversion |
| gRPC converter | `grpc/requestResponse/schema/...` | gRPC <-> domain conversion |
| gRPC assertions | `grpc/testUtils/GrpcAssertions.java` | Update assertion helpers |
| GraphQL | `graphql/api/catalog/schemaApi/...` | Query functional tests |
| REST | `rest/api/catalog/schemaApi/...` | Endpoint functional tests |
| REST DTO helpers | `rest/.../CatalogRestSchemaEndpointFunctionalTest.java` | `create*SchemaDto()` helpers |

### Test Checklist

- [ ] DTO: construction with explicit values and with defaults, getter behavior
- [ ] Builder: fluent API produces correct mutations, `null` defaults work
- [ ] Create mutation: `mutate()` builds correct schema, `combineWith()` generates diff mutations
- [ ] Set mutation: applies correctly, combines correctly
- [ ] Core converter: round-trip from JSON-like input to mutation and back
- [ ] gRPC: converter round-trip, enum conversion both directions (if applicable)
- [ ] GraphQL: query returns correct structure for new field
- [ ] REST: JSON output includes new field in correct format
- [ ] REST: `create*SchemaDto()` test helpers updated in `CatalogRestSchemaEndpointFunctionalTest`
- [ ] *(Scope-aware only)* Scoped record: null-argument validation, equality with array fields
- [ ] *(ReferenceSchema only)* Reflected: inheritance flag toggling, change detection edge cases

---

## Quick Reference — File Patterns

| Layer | File Pattern |
|---|---|
| 1 - Contract | `evita_api/.../schema/SchemaTypeContract.java` |
| 1 - Editor | `evita_api/.../schema/SchemaTypeEditor.java` |
| 2 - DTO | `evita_api/.../schema/dto/SchemaType.java` |
| 3 - Builder | `evita_api/.../schema/builder/SchemaTypeBuilder.java` |
| 4 - Mutations | `evita_api/.../schema/mutation/.../*Mutation.java` |
| 5 - Descriptors | `evita_external_api_core/.../schemaApi/model/*Descriptor.java` |
| 5 - Converters | `evita_external_api_core/.../schemaApi/resolver/mutation/.../*Converter.java` |
| 6a - Proto | `evita_external_api_grpc/.../resources/META-INF/.../Grpc*.proto` |
| 6a - Enum conv | `evita_external_api_grpc/.../requestResponse/EvitaEnumConverter.java` |
| 6a - Schema conv | `evita_external_api_grpc/.../requestResponse/schema/EntitySchemaConverter.java` |
| 6b - GQL catalog builder | `evita_external_api_graphql/.../schemaApi/builder/*SchemaBuilder.java` |
| 6b - GQL system builder | `evita_external_api_graphql/.../system/builder/SystemGraphQLSchemaBuilder.java` |
| 6b - DataFetcher | `evita_external_api_graphql/.../schemaApi/resolver/dataFetcher/*DataFetcher.java` |
| 6c - REST builder | `evita_external_api_rest/.../schemaApi/builder/*ObjectBuilder.java` |
| 6c - REST serial | `evita_external_api_rest/.../schemaApi/resolver/serializer/*Serializer.java` |
| 7 - Schema serial | `evita_store_server/.../schema/serializer/SchemaTypeSerializer.java` |
| 7 - Schema compat | `evita_store_server/.../schema/serializer/SchemaTypeSerializer_YYYY_M.java` |
| 7 - Schema config | `evita_store_server/.../schema/SchemaKryoConfigurer.java` |
| 8 - WAL serial | `evita_store_server/.../wal/schema/.../*MutationSerializer.java` |
| 8 - WAL compat | `evita_store_server/.../wal/schema/.../*MutationSerializer_YYYY_M.java` |
| 8 - WAL config | `evita_store_server/.../wal/WalKryoConfigurer.java` |

---

## Common Pitfalls

1. **Array equality in records.** Java records use `Object.equals()` for array fields, which is reference equality. Always override `equals()`, `hashCode()`, and `toString()` in records that contain arrays.

2. **Null semantics for reflected reference schemas.** `null` means "inherited from the target reference." Explicit empty array means "explicitly set to empty." These are semantically different. This only applies to `ReflectedReferenceSchema`.

3. **EnumMap initialization.** Always use `new EnumMap<>(Scope.class)` or `EnumSet.noneOf(NewEnum.class)` — never raw `HashMap`/`HashSet` for enum keys.

4. **Unmodifiable wrappers.** DTO fields must be wrapped with `CollectionUtils.toUnmodifiableMap()` / `CollectionUtils.toUnmodifiableSet()`. Missing this causes mutation bugs.

5. **`_internalBuild()` overload mismatch.** There are typically two forms: array-based (for mutations/serializers) and map-based (for internal construction). Ensure both are updated and parameter order is consistent.

6. **Boolean flag for nullable serialization.** In Kryo serializers, always write a boolean flag before nullable/optional fields. Read must match: `input.readBoolean() ? readValue() : null`. Forgetting the flag causes deserialization offset errors.

7. **Backward-compat serializer read order.** The old serializer must read fields in exactly the order the old version wrote them. Do not read the new field — it doesn't exist in old data.

8. **Serial version hash capture.** After modifying a Kryo serializer, the old hash must be captured by running tests — `SerialVersionBasedSerializer` reports the mismatch. Register the old hash with the backward-compatible serializer. Forgetting this step causes deserialization failures on stored data.

9. **gRPC proto field numbers are permanent.** Never reuse a field number. Always use the next available number. Old clients ignore unknown field numbers, so additions are safe.

10. **gRPC empty-list vs null.** In proto3, unset `repeated` fields are empty lists. Converters must treat empty list as `null` (not provided) to distinguish "not set" from "explicitly empty."

11. **External API backward compatibility.** All three web APIs (gRPC, GraphQL, REST) must handle requests that omit the new field. Converters must supply a sensible default or `null` for the missing field. Test with payloads that don't include the new field.

12. **Enum registration order in Kryo configurers.** New registrations must be appended at the end (before the assertion) to maintain stable index numbering. Never insert in the middle — it shifts all subsequent indices and breaks deserialization.

13. **Missing gRPC `UNRECOGNIZED` case.** The gRPC enum converter `toEvita()` method must handle `UNRECOGNIZED` by throwing `EvitaInvalidUsageException`. The `toGrpc()` direction does not need it.

14. **`Output.toSerializableValue()` doesn't support custom types.** The reflection-based `convertObjectToOutput()` in `MutationConverter` will throw for any `@SerializableCreator` parameter type not handled by `toSerializableValue()` (enums, primitives, strings, arrays of primitives are supported; custom domain objects like `Expression` are NOT). You must override `convertToOutput()` to pre-serialize these fields. This is easy to miss because the compile succeeds — the error only surfaces at runtime during test execution.

15. **REST functional test DTO helpers must match serializer output.** When adding a field to `SchemaJsonSerializer`, the corresponding `create*SchemaDto()` test helper in `CatalogRestSchemaEndpointFunctionalTest` (or the relevant endpoint test base class) must also be updated. Missing this causes all REST schema endpoint functional tests to fail with JSON path mismatches — often 10+ failures that look like unrelated issues.

16. **`SystemGraphQLSchemaBuilder` is a separate type registry.** evitaDB has **two independent** GraphQL schema builders: `CommonEvitaSchemaSchemaBuilder` (catalog API) and `SystemGraphQLSchemaBuilder` (system API). They maintain separate type registries. Registering a new type in one does NOT make it available in the other. Missing the system builder causes 500+ `graphql.AssertException: type ... not found in schema` errors across all system GraphQL tests and `EvitaServerTest`. Look for existing `Scoped*Descriptor.THIS` registrations in `SystemGraphQLSchemaBuilder.build()` and add the new one alongside them.

17. **Backward-compat serializer version suffix and release-only policy.** The `YYYY_M` suffix on backward-compatible serializer files must match the **last released version** (latest `release_YYYY-M` branch), not the current development version. Determine it via `git branch -r --list 'origin/release_*' --sort=-v:refname | head -1`. For example, `origin/release_2026-1` → suffix `_2026_1`, annotation `@Deprecated(since = "2026.1")`. **Crucially, we only maintain backward compatibility with the release version.** If a model changes multiple times during a dev cycle and a backward-compat serializer for the release already exists, do NOT create additional backward-compat serializers for intermediate states — just update the current serializer.

18. **`input.getOptionalProperty()` raw vs typed overload.** `getOptionalProperty(String)` returns the raw underlying object (e.g., `List`) without type conversion. `getOptionalProperty(String, Class)` invokes `toTargetType()` which handles `List` -> array conversion. Using the wrong overload causes `ClassCastException` at runtime when the mutation constructor expects an array type.

---

## Implementation Order

1. **Layers 1-4** (strictly sequential): Contracts -> DTOs -> Builders -> Mutations
2. **Build verification**: Compile `evita_api` module to verify no API breaks
3. **Layers 5, 7, 8** (in parallel):
   - **Group A:** Layer 5 (Core API descriptors/converters), then Layer 6a/6b/6c in parallel
   - **Group B:** Layer 7 (Kryo schema serializers + backward compat)
   - **Group C:** Layer 8 (WAL mutation serializers + backward compat)
4. **Testing** (after all groups complete): Write/update tests across all layers
5. **Build verification**: Full `mvn clean install` with `unitAndFunctional` profile

---

## Final Step: Code Quality Review

After all layers are implemented and tests pass, **offer the user to run the `/code-quality-pipeline` skill** on all changed files. This runs the `test-architect`, `code-simplifier`, and `bug-hunter-tdd` agents to review test coverage, code clarity, and potential bugs across the full set of changes.
