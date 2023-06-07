evita.updateCatalog(
	"testCatalog",
	session -> {
		session
			.createNewEntity("Brand", 1)
			.setAttribute("name", "Lenovo")
			.upsertVia(session);
	}
);