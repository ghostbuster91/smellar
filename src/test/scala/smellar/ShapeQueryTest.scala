package smellar

class ShapeQueryTest extends munit.FunSuite:

  private def rank(query: String, names: List[String]): List[String] =
    names.sortBy(ShapeQuery.rankKey(query.toLowerCase, _))

  test("an exact name wins over a longer name containing it") {
    assertEquals(rank("user", List("UserSummary", "GetUser", "User")).head, "User")
  }

  test("a prefix match beats a mid-string match") {
    assertEquals(rank("user", List("GetUserOutput", "UserProfile")).head, "UserProfile")
  }

  test("among equally-matching names the shorter one comes first") {
    assertEquals(rank("user", List("UserProfileSummary", "UserProfile")).head, "UserProfile")
  }

  test("ranking ignores case, so a lowercase trait name still matches exactly") {
    assertEquals(rank("taggable", List("TaggableApiConfig", "taggable")).head, "taggable")
  }
