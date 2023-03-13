evita.updateCatalog(
	"testCatalog",
	session -> {
		session
			.createNewEntity("brand", 1)
			.setAttribute("name", "Lenovo")
			.upsertVia(session);
	}
);