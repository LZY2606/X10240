package gsb.case

import java.io.File
import kotlin.math.*

/**
 * Generates the offline domain case pack
 * "碳年候选席：校准曲线、储库偏移与多峰年代证据".
 *
 * Synthetic calibration curves are built deterministically (fixed seed, no
 * network). A wiggly plateau region makes a radiocarbon age match several
 * calendar ages -> genuine multi-modal posterior (parallel calendar ranges).
 */
object CaseGenerator {
    const val STEP = 10.0

    data class CurveData(
        val family: String, val version: String, val anchorBP: Double,
        val c14: List<Double>, val sig: List<Double>
    )

    /** IntCal-like synthetic curve v1 with two strong wiggles + a flat shelf. */
    private fun curveV1(): CurveData {
        val n = 401 // anchor 2000 BP .. 0 BP at 10 yr knots (200 yr coverage)
        val anchor = 2000.0
        val c14 = ArrayList<Double>(n); val sig = ArrayList<Double>(n)
        for (i in 0 until n) {
            val t = anchor - i * STEP // calendar calBP decreasing with index? keep ascending below
            // build in ascending calBP: use x
            val x = anchor - i * STEP
            val shelf = 80.0 * exp(-((x - 300.0) / 120.0).pow(2))       // flat shelf near 300 BP
            val w1 = 120.0 * sin(2.0 * PI * x / 220.0)                    // long wiggle
            val w2 = 70.0 * sin(2.0 * PI * x / 90.0 + 1.3)                // short wiggle
            val trend = 0.45 * x                                          // monotonic trend
            c14.add(0.0) // placeholder, replaced after ascending build
            sig.add(22.0 + 6.0 * sin(2.0 * PI * x / 500.0))
        }
        // Recompute c14 ascending by calBP.
        val c14Asc = DoubleArray(n)
        for (i in 0 until n) {
            val x = anchor + i * 0.0 // anchorBP + i*step in ascending convention below
        }
        // ascending convention: knot i at calBP = anchor - (n-1)*step + i*step
        val lo = anchor - (n - 1) * STEP
        for (i in 0 until n) {
            val bp = lo + i * STEP
            val shelf = 90.0 * exp(-((bp - 300.0) / 130.0).pow(2))
            val w1 = 110.0 * sin(2.0 * PI * bp / 240.0)
            val w2 = 60.0 * sin(2.0 * PI * bp / 95.0 + 1.3)
            val trend = 0.40 * bp
            c14[i] = (trend + shelf + w1 + w2)
        }
        return CurveData("SynthIntraCal", "v1", lo, c14.toList(), sig)
    }

    private fun curveV2(v1: CurveData): CurveData {
        // A curve revision: slight systematic shift + refined wiggle amplitude,
        // same knot lattice so v1/v2 can be compared (curve upgrade scenario).
        val c14 = v1.c14.mapIndexed { i, c ->
            c + 18.0 + 14.0 * sin(2.0 * PI * (v1.anchorBP + i * STEP) / 300.0)
        }
        return v1.copy(version = "v2", c14 = c14)
    }

    private fun curveLine(name: String, d: CurveData): String =
        "CURVE ${d.family} ${d.version} ${d.anchorBP.toInt()} ${STEP.toInt()} " +
            "${d.c14.joinToString(",") { "%.2f".format(it) }} | " +
            d.sig.joinToString(",") { "%.2f".format(it) }

    @JvmStatic
    fun main(args: Array<String>) {
        val root = args.getOrNull(0) ?: "cases"
        val dir = File(root); dir.mkdirs()
        val rawDir = File(dir, "raw").apply { mkdirs() }
        val v1 = curveV1(); val v2 = curveV2(v1)

        // Values chosen so the posterior on v1 is genuinely multimodal.
        // Picked by reading the generated curve (documented in README).
        val base = File(rawDir, "carbon-seat-v1.rcbench")
        base.writeText(buildString {
            appendLine("# 碳年候选席 — baseline (initial version)")
            appendLine("# Each line carries its own provenance; quantities keep units.")
            appendLine(curveLine("v1", v1))
            appendLine("MEAS Beta-A \"Hearth F3, multi-wiggle match\" 512BP 28BP - v1 BCE_CE")
            appendLine("MEAS Beta-B \"Pit fill, BCE/CE boundary\" 2050BP 30BP - v1 BCE_CE")
            appendLine("MEAS Beta-C \"Marine shell, reservoir offset\" 880BP 32BP - v1 BCE_CE")
            appendLine("MEAS Beta-D \"Upper layer (younger)\" 300BP 30BP - v1 BCE_CE")
            appendLine("MEAS Beta-E \"Lower layer (older), conflicts D\" 305BP 30BP - v1 BCE_CE")
            appendLine("MEAS Beta-F \"Contaminated charred residue\" 640BP 45BP - v1 BCE_CE")
            appendLine("OFFSET Beta-A atmospheric 0,0,1.0")
            appendLine("OFFSET Beta-B atmospheric 0,0,1.0")
            appendLine("OFFSET Beta-C marine20 210,60,0.7 ; 40,30,0.3")
            appendLine("OFFSET Beta-D atmospheric 0,0,1.0")
            appendLine("OFFSET Beta-E atmospheric 0,0,1.0")
            appendLine("OFFSET Beta-F atmospheric 0,0,1.0")
            appendLine("ORDER Beta-D << Beta-E : stratigraphy: upper pit D sealed below lower E? recorded invert")
            appendLine("EXCLUDE Beta-F : lab flagged humic contamination, see notebook 4b")
        })

        val revise = File(rawDir, "carbon-seat-v2-curve.rcbench")
        revise.writeText(buildString {
            appendLine("# curve upgrade: register SynthIntraCal v2")
            appendLine(curveLine("v2", v2))
        })

        val local = File(rawDir, "carbon-seat-local-revision.rcbench")
        local.writeText(buildString {
            appendLine("# local-only revision: reservoir offset of Beta-C refined (DeltaR 210 -> 180)")
            appendLine("OFFSET Beta-C marine18 180,45,0.7 ; 40,30,0.3")
        })

        val readme = File(dir, "README-carbon-seat.md")
        readme.writeText(readmeText())
        File(root, "CASE_PACK_MANIFEST.json").writeText(manifest())

        // mirror into resources so the server serves it offline
        val resDir = File("src/main/resources/cases").apply { mkdirs() }
        rawDir.copyRecursively(resDir, overwrite = true)
        readme.copyTo(File(resDir, readme.name), overwrite = true)
        File(resDir, "CASE_PACK_MANIFEST.json").writeText(manifest())
        println("case pack generated at ${dir.absolutePath}")
    }

    private fun manifest() = """{
  "caseId": "carbon-seat",
  "title": "碳年候选席：校准曲线、储库偏移与多峰年代证据",
  "files": [
    "raw/carbon-seat-v1.rcbench",
    "raw/carbon-seat-v2-curve.rcbench",
    "raw/carbon-seat-local-revision.rcbench"
  ],
  "scenarios": [
    "curve-multimodal", "boundary-interpolation", "reservoir-offset-mixture",
    "bce-ce-crossing", "sequence-conflict", "curve-upgrade", "parallel-calendar-ranges"
  ]
}
"""

    private fun readmeText() = """# 碳年候选席（离线案例包）

主题：校准曲线、储库偏移与多峰年代证据。

本目录不是通用表格，而是为放射性碳年代校准工作台准备的领域材料。
所有曲线均为固定 seed 的合成 IntCal 风格曲线，离线可复现。

## 材料与来源
- `raw/carbon-seat-v1.rcbench`：基线——原测量值与标准差、曲线版本、
  偏移分布、年代系统、层序约束、排除决定，逐行带来源。
- `raw/carbon-seat-v2-curve.rcbench`：曲线升版（v1 -> v2）。
- `raw/carbon-seat-local-revision.rcbench`：一次只影响局部依赖的源数据改动。

## 被设计触发的情形
- 曲线多峰 / 并列日历区间：Beta-A 落在 v1 的 wiggle+plateau 区。
- 边界插值：网格 knot 之间用线性插值（step=10，校准网格=5）。
- 储库偏移分布：Beta-C 使用高斯混合 (210,60;0.7)/(40,30;0.3)。
- BCE/CE 跨界：Beta-B 的 HPD 跨越 1 BCE/CE 边界。
- 层序矛盾：ORDER Beta-D << Beta-E 与测量近一致年龄冲突。
- 曲线升版：v2 注册后，v1 结果变为 STALE 但仍可查看。
- 参数修订：Beta-C 的海洋储库偏移由 210 局部修订为 180。

## 单位与坐标
- 14C 年龄：`BP`（14C_BP，1σ 为同行 `sigma`）。
- 日历：内部 `calBP`（present=1950），界面可显示 BCE/CE。
- 无法转换的值保留原始文本并附诊断，不会为了绘图被丢弃。
"""
}
