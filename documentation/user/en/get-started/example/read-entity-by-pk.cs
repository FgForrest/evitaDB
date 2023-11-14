string brandNameInEnglish = evita.QueryCatalog(
	"testCatalog",
	session => {
		return session.GetEntity("brand", 1)
			.GetAttribute("name");
	}
);