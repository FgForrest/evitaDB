```json
{
  "operation": "OVERALL",
  "spentTime": 16600528,
  "start": 182789468201165,
  "steps": [
    {
      "operation": "PLANNING",
      "spentTime": 5364071,
      "start": 182789468283318,
      "steps": [
        {
          "operation": "PLANNING_INDEX_USAGE",
          "spentTime": 122980,
          "start": 182789468289249
        },
        {
          "arguments": [
            "Selected index: Index type: GLOBAL, estimated costs 585"
          ],
          "operation": "PLANNING_FILTER",
          "spentTime": 4078102,
          "start": 182789468487099,
          "steps": [
            {
              "arguments": [
                "Index type: GLOBAL, estimated costs 585"
              ],
              "operation": "PLANNING_FILTER_ALTERNATIVE",
              "spentTime": 4022569,
              "start": 182789468497849
            }
          ]
        },
        {
          "operation": "PLANNING_SORT",
          "spentTime": 886673,
          "start": 182789472570591
        },
        {
          "operation": "PLANNING_EXTRA_RESULT_FABRICATION",
          "spentTime": 155420,
          "start": 182789473465750
        }
      ]
    },
    {
      "operation": "EXECUTION",
      "spentTime": 11145588,
      "start": 182789473655695,
      "steps": [
        {
          "operation": "EXECUTION_FILTER",
          "spentTime": 224608,
          "start": 182789473704034
        },
        {
          "operation": "EXECUTION_SORT_AND_SLICE",
          "spentTime": 778762,
          "start": 182789473928893
        },
        {
          "operation": "FETCHING",
          "spentTime": 10044736,
          "start": 182789474755324
        }
      ]
    }
  ]
}
```