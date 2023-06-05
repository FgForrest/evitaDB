# Query constraint description framework

Constraint classes implementing the `io.evitadb.api.query.Constraint` can be used immediately after creation in the Java
API. To be able to use them in other APIs such as GraphQL or REST, where these constraints are transformed to
API-specific formats, such created constraints must be annotated and registered. Registered constraints are
automatically transformed at the start of Evita using `io.evitadb.api.query.descriptor.ConstraintProcessor` into generic
descriptors (represented by `io.evitadb.api.query.descriptor.ConstraintDescriptor`). Generated descriptors can be
queried using singleton provider `io.evitadb.api.query.descriptor.ConstraintDescriptorProvider` for generating e.g.
API-specific constraint formats.

## Used terminology

To be able to describe quite complex constraints, some terms have been defined:

- *descriptor* (represented by `io.evitadb.api.query.descriptor.ConstraintDescriptor`)
    - represents single variant of described constraint
- *type* (represented by `io.evitadb.api.query.descriptor.ConstraintType`)
    - defines for which purposes a constraint will be used (filter, order, ...)
- *property type* (represented by `io.evitadb.api.query.descriptor.ConstraintPropertyType`)
    - defines on which properties of targeted data (entity, reference, ...) a constraint can operate
- *domain* (represented by `io.evitadb.api.query.descriptor.ConstraintDomain`)
    - defines set of allowed/supported constraints and overall data
    - a domain can be `entity`, `reference` or `hierarchy reference`
- *base name*
    - describes a main condition or operation of a constraint for distinguishing it from other constraints
- *full name*
    - extends *base name* with suffix for supporting multiple variants of the same constraint
- *creator* (represented by `io.evitadb.api.query.descriptor.ConstraintCreator`)
    - describes constructor or factory method which defines suffix (and ultimately *full name*) and instantiation parameters
    - is used to instantiate the representing constraint
- *classifier*
    - is special *creator* parameter that is used by Evita to find target data (entity type, attribute name, ...)

## Important interfaces

There are several constraint interfaces which are important for categorizing each constraint into types defining usage
of these constraints. A constraint must implement subclasses of both `io.evitadb.api.query.TypeDefiningConstraint`
and `io.evitadb.api.query.PropertyTypeDefiningConstraint` where the first interface defines *type* and the second one
defines *property type*.

### Constraint type

A constraint *type* is specified by implementing any supported subclass of `io.evitadb.api.query.TypeDefiningConstraint`
. There are 4 types to choose from:

- head
    - represented by `io.evitadb.api.query.HeadConstraint` interface
- filter
    - represented by `io.evitadb.api.query.FilterConstraint ` interface
- order
    - represented by `io.evitadb.api.query.OrderConstraint` interface
- require
    - represented by `io.evitadb.api.query.RequireConstraint` interface

Constraint implementing such interface may look like this:
```java
public class FacetHaving extends ConstraintLeaf<FilterConstraint> implements FilterConstraint {
```

### Constraint property type

A constraint *property type* is specified by implementing any supported subclass
of `io.evitadb.api.query.PropertyTypeDefiningConstraint`. There are 8 types to choose from:

- generic
    - represented by `io.evitadb.api.query.GenericConstraint` interface
- entity
    - represented by `io.evitadb.api.query.EntityConstraint` interface
- attribute
    - represented by `io.evitadb.api.query.AttributeConstraint` interface
- associated data
    - represented by `io.evitadb.api.query.AssociatedDataConstraint` interface
- price
    - represented by `io.evitadb.api.query.PriceConstraint` interface
- reference
    - represented by `io.evitadb.api.query.ReferenceConstraint` interface
- hierarchy
    - represented by `io.evitadb.api.query.HierarchyConstraint` interface
- facet
    - represented by `io.evitadb.api.query.FacetConstraint` interface

Constraint implementing such interface with constraint type interface may look like this:
```java
public class FacetHaving extends ConstraintLeaf<FilterConstraint> implements FilterConstraint, FacetConstraint<FilterConstraint> {
```

## Annotation framework

Each constraint that should be registered has to be somehow described. For that, there are ready-to-use annotations
in `io/evitadb/api/query/descriptor/annotation`.

### Constraint definition

Firstly, a constraint class have to be annotated with the `io.evitadb.api.query.descriptor.annotation.ConstraintDefinition` to
tell the processor some basic metadata about it. *Type* and *property type* are defined using above interfaces, thus are
not specified in this annotation. The most important information to specify in this annotation is *base name* (which must be unique across all constraints).
Optionally, set of *domain*s in which the constraint is supported and set of supported values. Supported value are
values that the constraint can operate on, not that the constraint accepts these values as parameters. Finally, short 
description must be filled (usually one longer sentence) to provide some sort of quick documentation for clients.

Constraint definition may look like this:
```java
@ConstraintDefinition(
    name = "equals", 
    shortDescription = "Compares value of the attribute with passed value..."
    supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE },
    supportedValues = @ConstraintSupportedValues(allTypesSupported = true, arraysSupported = true)
)
public class AttributeEquals extends /* ... */ {
```

### Creator definition

Then, at least one constructor or factory method must be annotated with
the `io.evitadb.api.query.descriptor.annotation.Creator` to mark it as a *creator*. A *creator*
may define a custom suffix (to form *full name*) and an implicit classifier and all of its parameters (if there are any)
must be annotated with the  `io.evitadb.api.query.descriptor.annotation.Classifier`, 
`io.evitadb.api.query.descriptor.annotation.Value`, `io.evitadb.api.query.descriptor.annotation.Child`, or
`io.evitadb.api.query.descriptor.annotation.AdditionalChild`.

Basic *creator* may look like this:
```java
@Creator
public AttributeIs(@Nonnull @Classifier String attributeName,
                   @Nonnull @Value AttributeSpecialValue specialValue) {
    /* ... */
}
```
or in case of factory method:
```java
@Creator
public static AttributeIs attributeIs(@Nonnull @Classifier String attributeName,
                                      @Nonnull @Value AttributeSpecialValue specialValue) {
    /* ... */
}
```

Each *full name*, defined by *base name* of constraint and suffix of *creator*s, must be unique among all constraint creators.
Each *creator* then conform to single unique constraint *descriptor*, i.e. if constraint has multiple creators
there will be multiple *descriptor*s describing single constraint, each with different *full name* and corresponding
*creator*.

#### Classifier

A constraint can have at most one *classifier* which can be either implicit or explicit,
but not both. Implicit one can be either silent (automatically resolved by system, specified using the `silentImplicitClassifier` property) 
or fixed (specified at the constraint class creation using the `implicitClassifier` property).
Silent implicit *classifier* should be used anywhere where the system automatically picks up classifier when resolving
the constraint based on some other circumstances. Fixed implicit *classifier* should be used anywhere where
the constraint is able to operate only on that one particular *classifier* only. Explicit *classifier* is specified by
annotating any constructor parameter with the `io.evitadb.api.query.descriptor.annotation.Classifier`.
Explicit one should be used anywhere where the constraint is able to handle multiple different *classifier*s at runtime.

#### Values

Other *creator* constructor parameters must be annotated as values or children. Value parameters are primitive
arguments (strings, ints, ...) and are annotated with
the `io.evitadb.api.query.descriptor.annotation.Value` annotation. Constructor can have multiple value
parameters.

#### Children

There are two types of children that constraint containers support: main and additional. Main children refers to 
children that are of the same type as the parent container is and directly correlate with logic of the parent container. 
On the other hand, additional children extend such containers with possibility of adding constraint containers
of different constraint type, e.g. require container can have its own embedded filter container.

Such a parameter must be annotated with the `io.evitadb.api.query.descriptor.annotation.Child` annotation. With this annotation,
multiple parameters can be annotated in single constructor.
Additionally, one can mark array of children as unique where each child constraint can be used only once. This is useful
for constraint containers where only limited number of child constraints are allowed.
By default, only subclasses of the parameter type are allowed which are then limited to subset defined by context of current
*domain*.
To further limit allowed child constraints, one can specify set of `allowed` or `forbidden` constraints in this
annotation.

Additional child parameters must be annotated with the `io.evitadb.api.query.descriptor.annotation.AdditionalChild` annotation
and cannot be of same constraint type as the parent container. However, there are some limitations in place to simplify
processing logic of these annotations and to produce reasonable JSON objects (which are also quite limited for our use cases).
The type of parameter must be some concrete generic constraint container of desired constraint type, so that it generates
enclosing JSON object automatically. Also, the container must have *creator* with only one parameter which 
must be the `io.evitadb.api.query.descriptor.annotation.Child`.
Lastly, the additional child parameter cannot be an array, because the referenced container can have its own array child parameter,
which would clash.

Both child parameters support overriding *domain* for its children. By default, the domain for children is passed from the
parent constraint which has its domain resolved from the *property type*. However, sometimes we need completely different
context for children (e.g. we want nested `filterBy` container inside hierarchy constraint to support filtering on referenced hierarchical entity),
then we can use the `domain` parameter on either `io.evitadb.api.query.descriptor.annotation.Child` or 
`io.evitadb.api.query.descriptor.annotation.AdditionalChild` annotation. Unfortunately, not all combinations of
parent domain and overridden domain are possible, check JavaDoc of the `domain` parameter for more info. 

More complex set of *creator*s combining options described above may look like this:
```java
@Creator
public HierarchyWithin(@Nonnull @Classifier String entityType,
                       @Nonnull @Value Integer ofParent,
                       @Nonnull @Child(uniqueChildren = true) HierarchySpecificationFilterConstraint... with) {
    /* ... */
}

@Creator(suffix = "self", silentImplicitClassifier = true)
public HierarchyWithin(@Nonnull @Value Integer ofParent,
                       @Nonnull @Child(uniqueChildren = true) HierarchySpecificationFilterConstraint... with) {
    /* ... */
}

@Creator
public ReferenceContent(@Nonnull @Classifier String referenceName,
                        @Nullable @AdditionalChild FilterBy filterBy,
                        @Nullable @AdditionalChild OrderBy orderBy,
                        @Nullable @Child(uniqueChildren = true) EntityRequire... requirements) {
    /* ... */
}   
```

## Registration

An annotation constraint must be registered manually in
the `io.evitadb.api.query.descriptor.RegisteredConstraintProvider` whose only job is to gather registered constraints
for processing. The order of registered constraint doesn't matter.

## Processing

After start of Evita and call to the `io.evitadb.api.query.descriptor.ConstraintDescriptorProvider`, the provider
requests processed constraints from the `io.evitadb.api.query.descriptor.ConstraintProcessor`. The processor goes
through a list of passed annotated constraints, creates
individual `io.evitadb.api.query.descriptor.ConstraintDescriptor`s from them (by using Java reflection and above
annotations), validates them against each other and passes them back to the provider.

## Provider

Provider `io.evitadb.api.query.descriptor.ConstraintDescriptorProvider` provides access to descriptors of registered and
processed constraints. It is singleton and is used to access constraints in target environment where e.g. API-specific
constraints should be generated from original constraints.

It provides several query methods to filter registered and processed constraints.

Simple query for descriptors in certain category may look like this:
```java
final Set<ConstraintDescriptor> descriptors = ConstraintDescriptorProvider.getConstraints(
    ConstraintType.FILTER,
    ConstraintPropertyType.HIERARCHY,
    ConstraintDomain.ENTITY
)
```
