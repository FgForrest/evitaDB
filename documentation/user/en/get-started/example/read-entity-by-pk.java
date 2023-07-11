final String brandNameInEnglish = evita.queryCatalog(
	"testCatalog",
	session -> {
		return session.getEntity("brand", 1)
			.orElseThrow()
			.getAttribute("name");
	}
);