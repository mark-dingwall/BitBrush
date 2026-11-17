// Applied only by CI. Raw Gradle XML/HTML and exception/stream output are disabled
// at generation time. The allowlisted listener never reads failure operands.
import org.gradle.api.tasks.testing.*
import org.gradle.api.tasks.testing.logging.TestLogEvent

allprojects {
    tasks.withType<Test>().configureEach {
        reports.junitXml.required.set(false)
        reports.html.required.set(false)
        testLogging {
            events = emptySet<TestLogEvent>()
            showExceptions = false
            showCauses = false
            showStackTraces = false
            showStandardStreams = false
        }
        val rows = java.util.Collections.synchronizedList(mutableListOf<String>())
        val target = layout.buildDirectory.file("safe-results/$name/results.xml")
        doFirst {
            rows.clear()
            target.get().asFile.apply {
                parentFile.mkdirs()
                writeText("<?xml version=\"1.0\"?><testsuite></testsuite>")
            }
        }
        addTestListener(object : TestListener {
            override fun beforeSuite(suite: TestDescriptor) {}
            override fun beforeTest(test: TestDescriptor) {}
            override fun afterSuite(suite: TestDescriptor, result: TestResult) {}
            override fun afterTest(test: TestDescriptor, result: TestResult) {
                fun identifier(value: String?) = value?.takeIf { it.matches(Regex("[A-Za-z_$][A-Za-z0-9_.$]*")) } ?: "test"
                val className = identifier(test.className)
                // Parameterized display names may contain credentials. Use their static parent method.
                val method = identifier((if (test.name.startsWith("[")) test.parent?.name else test.name)?.substringBefore('('))
                val outcome = when (result.resultType) {
                    TestResult.ResultType.SUCCESS -> ""
                    TestResult.ResultType.SKIPPED -> "<skipped/>"
                    else -> "<failure message=\"E_TEST_FAILURE\"/>"
                }
                val duration = (result.endTime - result.startTime).coerceAtLeast(0) / 1000.0
                rows.add("<testcase classname=\"$className\" name=\"$method\" time=\"$duration\">$outcome</testcase>")
                // Persist after each result so interrupted/failing suites retain completed results.
                synchronized(rows) {
                    target.get().asFile.writeText("<?xml version=\"1.0\"?><testsuite>${rows.joinToString("")}</testsuite>")
                }
                logger.lifecycle("{} {} {} {}", className, method, result.resultType,
                    if (result.resultType == TestResult.ResultType.FAILURE) "E_TEST_FAILURE" else "")
            }
        })
    }
}
