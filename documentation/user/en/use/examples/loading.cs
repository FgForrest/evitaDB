//some custom logic to load proper entity
ISealedEntity? entity = session
	.GetEntity("Product", 1, AssociatedDataContentAll());
//deserialize the associated data
ProductStockAvailability stockAvailability = entity.GetAssociatedData<ProductStockAvailability>(
    "stockAvailability",
);