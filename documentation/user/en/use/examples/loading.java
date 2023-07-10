//some custom logic to load proper entity
final SealedEntity entity = session
	.getEntity("Product", 1, associatedDataContent())
	.orElseThrow();
//deserialize the associated data
final ProductStockAvailability stockAvailability = entity.getAssociatedData(
    "stockAvailability", ProductStockAvailability.class,
	new ReflectionLookup(ReflectionCachingBehaviour.NO_CACHE)
);