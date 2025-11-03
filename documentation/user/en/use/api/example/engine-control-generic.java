final Progress<CommitVersions> progress = evita.applyMutation(
	new MakeCatalogAliveMutation("evita")
);
progress.addProgressListener(completed -> System.out.println("Progress: " + completed + "%"));
progress.onCompletion().thenApply(
	commitVersions -> {
		System.out.println(
			"Catalog is ready to accept queries:\n" +
				"\tinitial catalog version:" + commitVersions.catalogVersion() + "\n" +
				"\tinitial schema version: " + commitVersions.catalogSchemaVersion() + "\n"
		);
		return commitVersions;
	}
);