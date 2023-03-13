evita.updateCatalog(
	"testCatalog",
	session -> {
		session
			.createNewEntity(ENTITY_BRAND, 1)
			.setAttribute(ATTRIBUTE_NAME, "Lenovo")
			.upsertVia(session);
	}
);