package com.intellij.lang.jsgraphql.schema

import com.intellij.lang.jsgraphql.GraphQLTestCaseBase
import com.intellij.lang.jsgraphql.types.schema.idl.SchemaPrinter
import com.intellij.psi.PsiFile
import java.util.function.UnaryOperator

class GraphQLSchemaBuilderTest : GraphQLTestCaseBase() {

  companion object {
    private fun getOptions(optionsBuilder: UnaryOperator<SchemaPrinter.Options>?): SchemaPrinter.Options {
      val options = SchemaPrinter.Options.defaultOptions().includeDirectiveDefinitions(false)
      return optionsBuilder?.apply(options) ?: options
    }
  }

  override fun getBasePath() = "/schema/builder"

  fun testObjects() {
    doTest()
  }

  fun testInterfaces() {
    doTest()
  }

  fun testUnions() {
    doTest()
  }

  fun testInputObjects() {
    doTest()
  }

  fun testScalars() {
    doTest()
  }

  fun testEnums() {
    doTest()
  }

  fun testDirectives() {
    doTest { it.includeDirectiveDefinitions(true) }
  }

  fun testSchemas() {
    doTest()
  }

  fun testSpecifiedByAndDeprecatedDirectives() {
    doTest()
  }

  fun testSchemaInInjections() {
    doProjectTest("type1.graphql")
  }

  fun testRecursiveDefaultObjectValues() {
    doTest()
  }

  private fun doTest(optionsBuilder: UnaryOperator<SchemaPrinter.Options>? = null) {
    myFixture.configureByFile(getTestName(true) + ".graphql")

    val file = myFixture.file
    checkByExpectedSchema(file, optionsBuilder)
  }

  private fun doProjectTest(fileName: String, optionsBuilder: UnaryOperator<SchemaPrinter.Options>? = null) {
    myFixture.copyDirectoryToProject(getTestName(true), "")
    reloadProjectConfiguration()

    val file = myFixture.configureFromTempProjectFile(fileName)!!
    checkByExpectedSchema(file, optionsBuilder)
  }

  private fun checkByExpectedSchema(
    file: PsiFile?,
    optionsBuilder: UnaryOperator<SchemaPrinter.Options>?
  ) {
    val schemaProvider = GraphQLSchemaProvider.getInstance(myFixture.project)
    val schema = schemaProvider.getSchemaInfo(file).schema
    myFixture.configureByText("result.graphql", SchemaPrinter(project, getOptions(optionsBuilder)).print(schema))
    myFixture.checkResultByFile("${getTestName(true)}_expected.graphql")
  }
}
