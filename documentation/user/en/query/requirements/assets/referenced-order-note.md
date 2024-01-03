The ordering constraints can only target properties on the target entity and cannot target reference attributes in
the source entity that are specific to a relationship with the target entity. Even though it is technically possible to
order by attributes of references themself, because we don't have direct relation
with specific entity here, it would be too difficult to wrap a mind around this concept. That's why we don't support this
approach and you can order directly referenced entities only.