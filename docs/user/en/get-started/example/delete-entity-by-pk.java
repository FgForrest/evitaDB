evita.updateCatalog(
	"testCatalog", session -> {
		return session.deleteEntity(
			"Brand",
			1
		);
	}
);