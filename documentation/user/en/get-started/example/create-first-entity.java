evita.updateCatalog(
	"evita",
	session -> {
		session
			.createNewEntity("Brand", 1)
			.setAttribute("name", "Samsung")
			.upsertVia(session);
	}
);
