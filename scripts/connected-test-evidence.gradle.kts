import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.w3c.dom.Element
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

@DisableCachingByDefault(because = "Reports the actual connected-test results, including failed runs")
abstract class ConnectedTestEvidenceReport : DefaultTask() {
    @get:Internal
    abstract val resultsDirectory: DirectoryProperty

    @TaskAction
    fun report() {
        val directory = resultsDirectory.get().asFile
        val reports = if (directory.isDirectory) {
            directory.walkTopDown()
                .filter { it.isFile && it.name.startsWith("TEST-") && it.extension == "xml" }.toList()
        } else {
            emptyList()
        }
        var total = 0
        var failures = 0
        var errors = 0
        for (report in reports.sortedBy { it.path }) {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            }
            val cases = factory.newDocumentBuilder().parse(report).getElementsByTagName("testcase")
            total += cases.length
            for (index in 0 until cases.length) {
                val case = cases.item(index) as Element
                for (tag in listOf("failure", "error")) {
                    val details = case.getElementsByTagName(tag)
                    if (tag == "failure") failures += details.length else errors += details.length
                    for (detail in 0 until details.length) {
                        val text = details.item(detail).textContent.take(6000).replace(
                            Regex(
                                "(?i)(authorization|api[_-]?key|access[_-]?token|secret[_-]?access[_-]?key|password|passphrase)\\s*[:=]\\s*\\S+"
                            ),
                            "$1=<redacted>",
                        )
                        logger.lifecycle(
                            "CONNECTED_TEST_FAILURE {}#{}\n{}",
                            case.getAttribute("classname"),
                            case.getAttribute("name"),
                            text
                        )
                    }
                }
            }
        }
        logger.lifecycle(
            "CONNECTED_TEST_EVIDENCE cases={} failed={} errors={} reports={}",
            total,
            failures,
            errors,
            reports.size
        )
        if (reports.isEmpty()) logger.warn("No connected JUnit XML was produced; this is not passing test evidence.")
    }
}

val connectedTestEvidence = tasks.register<ConnectedTestEvidenceReport>("reportConnectedTestEvidence") {
    resultsDirectory.set(layout.buildDirectory.dir("outputs/androidTest-results/connected"))
}
tasks.configureEach {
    if (name.startsWith("connected") && name.endsWith("AndroidTest")) finalizedBy(connectedTestEvidence)
}
