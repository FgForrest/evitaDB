evita.UpdateCatalog(
	"testCatalog",
	session => {
		session
			.CreateNewEntity("Brand", 1)
			.SetAttribute("name", "Samsung")
			.UpsertVia(session);
	}
);