evita.updateCatalog(
	"testCatalog",
	session -> {
		session.goLiveAndClose();
	}
);