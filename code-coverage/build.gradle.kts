
plugins {
  base
  id("jacoco-report-aggregation")
}

repositories {
  mavenCentral()
}

dependencies {
  jacocoAggregation(project(":sunday-core"))
  jacocoAggregation(project(":sunday-jdk"))
  jacocoAggregation(project(":sunday-okhttp"))
}

reporting {
  reports {
    create<JacocoCoverageReport>("testCoverageReport") {
      testType.set(TestSuiteType.UNIT_TEST)
      reportTask {
        reports.xml.required.set(true)
      }
    }
  }
}

tasks {

  check {
    finalizedBy(named<JacocoReport>("testCoverageReport"))
  }

}
