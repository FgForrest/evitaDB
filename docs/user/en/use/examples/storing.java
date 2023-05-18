session.createNewEntity("Product")
	.setAssociatedData(
		"stockAvailability",
		new ProductStockAvailability()
	)
	.upsertVia(session);