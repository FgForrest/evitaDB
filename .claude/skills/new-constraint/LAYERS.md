# Constraint Implementation Layers

Complete checklist of files to create/modify when adding a new constraint. Copy relevant items into your plan.

## Checklist template

```
Progress:
- [ ] 1. Constraint class
- [ ] 2. Registry registration
- [ ] 3. Factory method
- [ ] 4. EvitaQL grammar rule
- [ ] 5. Regenerate parser
- [ ] 6. Parser visitor method
- [ ] 7. Formula (if new computation needed)
- [ ] 8. Engine translator
- [ ] 9. Translator registration
- [ ] 10. Kryo serializer
- [ ] 11. Kryo registration (APPEND AT END)
- [ ] 12. Constraint unit test
- [ ] 13. Parser visitor test
- [ ] 14. Parser list visitor test
- [ ] 15. Serialization round-trip test
- [ ] 16. Descriptor provider test (update counts)
- [ ] 17. Formula test (if new formula)
- [ ] 18. JSON converter test
- [ ] 19. Constraint resolver tests (GraphQL + REST)
- [ ] 20. E2E functional test
- [ ] 21. GraphQL API functional test
- [ ] 22. REST API functional test
- [ ] 23. Performance benchmark (if new formula)
- [ ] 24. User documentation
- [ ] 25. Example .evitaql files
```

## File paths by layer

### Layer 1: Query Model (`evita_query`)

| # | What | Path |
|---|------|------|
| 1 | Constraint class | `evita_query/src/main/java/io/evitadb/api/query/{filter,order,require}/` |
| 2 | Registration | `evita_query/.../descriptor/ConstraintRegistry.java` |
| 3 | Factory method | `evita_query/.../QueryConstraints.java` |
| 4 | Grammar | `evita_query/src/main/resources/META-INF/io/evitadb/api/query/parser/evitaQL/EvitaQL.g4` |
| 5 | Regenerate | `cd evita_query && ./generate_grammar.sh evitaql` |
| 6 | Parser visitor | `evita_query/.../parser/visitor/EvitaQL{Filter,Order,Require}ConstraintVisitor.java` |

**Grammar arg types** (defined at bottom of `EvitaQL.g4`):

| Rule | Parameters | Example |
|------|-----------|---------|
| `valueArgs` | 1 value, no classifier | `entityLocaleEquals` |
| `valueListArgs` | N values, no classifier | `entityPrimaryKeyInSet` |
| `betweenValuesArgs` | 2 values, no classifier | `priceBetween` |
| `classifierArgs` | classifier only | `attributeIsNull` |
| `classifierWithValueArgs` | classifier + 1 value | `attributeEquals` |
| `classifierWithBetweenValuesArgs` | classifier + 2 values | `attributeBetween` |
| `classifierWithOptionalValueListArgs` | classifier + optional values | `attributeInSet` |
| `filterConstraintListArgs` | child filter constraints | `and`, `or` |
| `orderConstraintListArgs` | child order constraints | `orderBy` |
| `requireConstraintListArgs` | child require constraints | `require` |

**Constraint annotation reference**: `documentation/developer/query/query_constraint_description_framework.md`

### Layer 2: Engine (`evita_engine`)

| # | What | Path |
|---|------|------|
| 7 | Formula | `evita_engine/src/main/java/io/evitadb/core/query/algebra/{subpackage}/` |
| 8 | Translator | `evita_engine/.../query/{filter/translator,sort/translator,extraResult/translator}/{subpackage}/` |
| 9 | Registration | Filter: `FilterByVisitor.java` / Order: `OrderByVisitor.java` / Require: `ExtraResultPlanningVisitor.java` |

**Formula rules**: See `documentation/developer/formula/formula_framework.md`

### Layer 3: Storage (`evita_store`)

| # | What | Path |
|---|------|------|
| 10 | Serializer | `evita_store/evita_store_server/.../serializer/{filter,order,require}/` |
| 11 | Registration | `evita_store/.../QuerySerializationKryoConfigurer.java` — **APPEND AT END** |

### Layer 4: External APIs

GraphQL/REST schemas are auto-generated from constraint descriptors. No manual code for basic leaf constraints.

Manual changes needed only for: container constraints with additional children, custom input types, special endpoint handling.

### Layer 5: Tests

| # | What | Path |
|---|------|------|
| 12 | Constraint unit | `evita_test/.../query/{filter,order,require}/NewConstraintTest.java` |
| 13 | Parser visitor | `evita_test/.../parser/visitor/EvitaQL{Filter,Order,Require}ConstraintVisitorTest.java` |
| 14 | List visitor | `evita_test/.../parser/visitor/EvitaQL{Filter,Order,Require}ConstraintListVisitorTest.java` |
| 15 | Serialization | `evita_test/.../store/query/QuerySerializationTest.java` |
| 16 | Descriptor | `evita_test/.../query/descriptor/ConstraintDescriptorProviderTest.java` |
| 17 | Formula | `evita_test/.../core/query/algebra/{subpackage}/NewFormulaTest.java` |
| 18 | JSON converter | `evita_test/.../client/query/FilterConstraintToJsonConverterTest.java` |
| 19 | Resolvers | `evita_test/.../graphql/.../resolver/constraint/FilterConstraintResolverTest.java` + REST equivalent |
| 20 | E2E functional | `evita_test/.../functional/{entity,attribute,price,hierarchy,facet,fetch}/` |
| 21 | GraphQL API | `evita_test/.../graphql/.../CatalogGraphQL*FunctionalTest.java` |
| 22 | REST API | `evita_test/.../rest/.../CatalogRest*FunctionalTest.java` |
| 23 | Benchmark | `evita_test/evita_performance_tests/.../spike/FormulaCostMeasurement.java` |

### Layer 6: Documentation

| # | What | Path |
|---|------|------|
| 24 | User docs | `documentation/user/en/query/{filtering,ordering,requirements}/` |
| 25 | Examples | `documentation/user/en/query/.../examples/` |
