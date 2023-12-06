evita.UpdateCatalog(
	"testCatalog", session => {
		return session.DeleteEntity(
			"Brand",
			1
		);
	}
);