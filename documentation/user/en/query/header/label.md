---
title: Label
date: '12.12.2024'
perex: Labels allow tagging the query for later identification.
author: 'Ing. Jan Novotný'
proofreading: 'done'
preferredLang: 'evitaql'
---

## Label

```evitaql-syntax
label(
    argument:string!,
    argument:any!
)
```

<dl>
    <dt>argument:string!</dt>
    <dd>
        mandatory string argument representing the name of the label
    </dd>
    <dt>argument:any!</dt>
    <dd>
        mandatory any argument representing the value of the label, 
        any [supported type](../../use/data-types.md#simple-data-types) can be used
    </dd>
</dl>

This `label` constraint allows a single label name with associated value to be specified in the query header and
propagated to the trace generated for the query. A query can be tagged with multiple labels.

Labels are also recorded with the query in the [traffic record](../../operate/observe.md#traffic-recording) and can be
used to look up the query in the traffic inspection or traffic replay. Labels are also attached to JFR events related
to the query.

Each label is a key-value pair appended to the query header, as shown in the following example:

<SourceCodeTabs requires="/evita_functional_tests/src/test/resources/META-INF/documentation/evitaql-init.java" langSpecificTabOnly>

[Attaching labels to query](/documentation/user/en/query/header/examples/labels.evitaql)

</SourceCodeTabs>