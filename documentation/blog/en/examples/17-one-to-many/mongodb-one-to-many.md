```mongodb-json
db.products.aggregate([
  // Join with the groups collection
  {
    $lookup: {
      from: "groups",
      localField: "groups.id",
      foreignField: "_id",
      as: "groupDetails"
    }
  },
  // Merge groupDetails with order info
  {
    $addFields: {
      groupsMerged: {
        $map: {
          input: "$groups",
          as: "pg",
          in: {
            $mergeObjects: [
              "$$pg",
              {
                $arrayElemAt: [
                  {
                    $filter: {
                      input: "$groupDetails",
                      as: "gd",
                      cond: { $eq: [ "$$gd._id", "$$pg.id" ] }
                    }
                  },
                  0
                ]
              }
            ]
          }
        }
      }
    }
  },
  // Sort merged groups by name ascending
  {
    $addFields: {
      groupsSorted: {
        $sortArray: {
          input: "$groupsMerged",
          sortBy: { name: 1 }
        }
      }
    }
  },
  // Extract the order from the first group (after name sort)
  {
    $addFields: {
      sortOrder: { $arrayElemAt: [ "$groupsSorted.order", 0 ] }
    }
  },
  // Final projection
  {
    $project: {
      _id: 1,
      name: 1,
      description: 1,
      sortOrder: 1,
      groups: {
        $map: {
          input: "$groupsSorted",
          as: "g",
          in: { id: "$$g.id", name: "$$g.name" }
        }
      }
    }
  },
  // Sort products by that order
  {
    $sort: { sortOrder: 1 }
  }
])
```