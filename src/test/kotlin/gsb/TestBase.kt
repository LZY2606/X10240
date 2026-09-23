package gsb

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random

/** All tests: fixed seed, temporary directory, virtual time, no network. */
abstract class TestBase {
    @TempDir @JvmField var tmpRoot: File? = null
    val seed: Random = Random(42)
    val clock = VirtualClock()
    val tmp: File get() = tmpRoot!!

    fun casepackDir(): File {
        var d = File("casepack")
        while (!File(d, "00-base.json").isFile && d.parentFile != null) d = File(d.parentFile.parentFile, "casepack")
        require(File(d, "00-base.json").isFile) { "casepack not found from ${File("").absolutePath}" }
        return d
    }

    fun newWorkbench(dbName: String = "wb.db"): Workbench {
        val cp = File(tmp, "casepack")
        casepackDir().copyRecursively(cp)
        return Workbench(Store(File(tmp, dbName), clock), cp)
    }

    fun replayAll(wb: Workbench) {
        for (name in listOf("00-base", "01-param-revision", "02-local-source-change", "03-conflicting-decisions"))
            wb.applyCasepackStep(name)
    }
}
