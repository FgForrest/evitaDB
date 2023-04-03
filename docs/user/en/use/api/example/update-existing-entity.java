evita.updateCatalog(
	"testCatalog",
	session -> {
		session.getEntity("product", 1)
			.orElseThrow(
				() -> new IllegalArgumentException("Product `1` not found!")
			)
			.openForWrite()
			.setAttribute(
				"name", Locale.ENGLISH,
				"ASUS Vivobook 16 X1605EA-MB044W Indie Black"
			)
			.setAttribute(
				"name", Locale.GERMAN,
				"ASUS Vivobook 16 X1605EA-MB044W Indie Schwarz"
			)
			.upsertVia(session);
	}
);