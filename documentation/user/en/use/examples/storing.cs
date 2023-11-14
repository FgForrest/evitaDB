session.CreateNewEntity("Product")
	.SetAssociatedData(
		"stockAvailability",
		new ProductStockAvailability()
	)
	.UpsertVia(session);