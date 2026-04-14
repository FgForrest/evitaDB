---
name: new-constraint
description: Plans and executes implementation of a new evitaDB query constraint (filter, order, or require). Produces a task checklist covering all layers — query model, EvitaQL grammar, engine translator, Kryo serializer, external APIs, tests, and documentation — with exact file paths. Use when adding new filter/order/require constraints or variants of existing ones.
allowed-tools: Read, Glob, Grep, Bash(rg *), Bash(find *), Bash(ls *), Bash(wc *), Write, Agent
---

# Planning New Constraint

Produces an implementation plan for adding a new query constraint to evitaDB.

## Procedure

### Step 1: Gather requirements

Ask the user (if not already clear):
1. **Constraint type**: filter, order, or require
2. **What it does**: behavior, parameters, return semantics
3. **Supported domains**: ENTITY, REFERENCE, INLINE_REFERENCE, FACET, etc.
4. **Does it need a new formula?** Or reuses existing infrastructure

### Step 2: Find the closest existing constraint

Search the codebase for the most similar existing constraint to use as a template:

| If the new constraint is...       | Study this template                                    |
|-----------------------------------|--------------------------------------------------------|
| Filter leaf, entity property      | `EntityPrimaryKeyInSet`, `EntityPrimaryKeyGreaterThan` |
| Filter leaf, attribute comparison | `AttributeGreaterThan`, `AttributeBetween`             |
| Filter leaf, price                | `PriceBetween`, `PriceInCurrency`                      |
| Filter container                  | `And`, `ReferenceHaving`, `HierarchyWithin`            |
| Order leaf                        | `EntityPrimaryKeyNatural`, `AttributeNatural`          |
| Require leaf                      | `Page`, `PriceContent`                                 |
| Require container                 | `EntityFetch`, `ReferenceContent`                      |

### Step 3: Trace the template through all layers

For the chosen template, search for every file that references it. This reveals the exact set of files the new constraint must touch:

```bash
rg "TemplateConstraintName" --type java -l
```

Cross-reference the results against the full checklist in [LAYERS.md](LAYERS.md) to ensure nothing is missed.

### Step 4: Produce the plan

For each item in the checklist from [LAYERS.md](LAYERS.md):
- Provide the **exact file path** to create or modify
- Specify **which template file** to mimic (the one from Step 2)
- For modifications, note **where in the file** to add (e.g., "after the `EntityPrimaryKeyInSet` entry")
- Include brief notes on any deviations from the template pattern

### Step 5: Save the plan

Write to `docs/plans/YYYY-MM-DD-{constraint-name}.md` with this structure:

```markdown
# [Constraint Name] Implementation Plan

**Goal:** [One sentence]
**Template constraint:** [Name of template being followed]
**Architecture:** [2-3 sentences about approach]

---

## Task N: [Layer/component name]

**Files:**
- Create: `exact/path/to/NewConstraint.java` (template: `exact/path/to/TemplateConstraint.java`)
- Modify: `exact/path/to/Registry.java` (add after `TemplateConstraint.class`)

**Key differences from template:**
- [What's different about the new constraint vs the template]
```

## Critical rules

- **Kryo registration**: ALWAYS append at the END of `QuerySerializationKryoConfigurer.java`. Inserting in the middle shifts class IDs and breaks deserialization of existing stored data.
- **Grammar regeneration**: Run `cd evita_query && ./generate_grammar.sh evitaql` after modifying `EvitaQL.g4`. The generated files in `evita_query/.../parser/grammar/` are checked into version control.
- **Formula framework**: New formulas MUST follow `documentation/developer/formula/formula_framework.md` — lazy evaluation, unique CLASS_ID, benchmarked `getOperationCost()` via `FormulaCostMeasurement.java` (COST 1 = 1M ops/s).
- **GraphQL/REST**: Usually automatic from constraint descriptors. Manual API code only needed for container constraints with additional children.
- **gRPC**: No changes needed — passes EvitaQL strings.
- **Tests are domain-grouped**: E2E tests live in `evita_test/.../functional/{entity,attribute,price,hierarchy,facet,fetch}/` — place the test in the matching domain package.
- **Constraint descriptor annotations**: See `documentation/developer/query/query_constraint_description_framework.md` for the full annotation framework (`@ConstraintDefinition`, `@Creator`, `@Classifier`, `@Value`, `@Child`, `@AdditionalChild`).
