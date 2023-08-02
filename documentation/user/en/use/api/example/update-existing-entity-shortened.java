session.getEntity("Product", 1, attributeContentAll(), dataInLocales(Locale.ENGLISH))
	.orElseThrow(
		() -> new IllegalArgumentException("Product `1` not found!")
	)
	.openForWrite()
	.setAttribute(
		"name", Locale.ENGLISH,
		"ASUS Vivobook 16 X1605EA-MB044W Indie Black"
	)
	.upsertVia(session);