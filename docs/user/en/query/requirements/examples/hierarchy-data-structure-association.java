final EvitaResponse<SealedEntity> result = session.querySealedEntity(
	query(
		// query hierarchy entity type
		collection("Category"),
		// target "Accessories" category
		filterBy(
			hierarchyWithinSelf(
				attributeEquals("code", "audio")
			)
		),
		require(
			hierarchyOfSelf(
				// request computation of direct children of the category
				children(
					"directChildren",
					entityFetch(attributeContent()),
					stopAt(distance(1)),
				),
				// request computation of immediate parent of the category
				parents(
					"directParent",
					entityFetch(attributeContent()),
					stopAt(distance(1))
				)
			)
		)
	)
);

final Hierarchy hierarchyResult = result.getExtraResult(Hierarchy.class);
// list direct children
final List<LevelInfo> directChildren = hierarchyResult.getSelfHierarchy("directChildren");
for (LevelInfo directChild : directChildren) {
	System.out.println(
		((SealedEntity)directChild.entity()).getAttribute("code")
	);
}
// display parent
final List<LevelInfo> directParent = hierarchyResult.getSelfHierarchy("directParent");
System.out.println(
	((SealedEntity)directParent.get(0).entity()).getAttribute("code")
);