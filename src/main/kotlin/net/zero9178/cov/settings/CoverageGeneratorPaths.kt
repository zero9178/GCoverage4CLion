package net.zero9178.cov.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.io.exists
import com.jetbrains.cidr.cpp.toolchains.CPPToolchains
import com.jetbrains.cidr.cpp.toolchains.CPPToolchainsListener
import com.jetbrains.cidr.cpp.toolchains.MinGW
import com.jetbrains.cidr.cpp.toolchains.NativeUnixToolSet
import com.jetbrains.cidr.toolchains.OSType
import net.zero9178.cov.data.CoverageGenerator
import net.zero9178.cov.data.getGeneratorFor
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

@State(
    name = "net.zero9178.coverage.settings",
    storages = [Storage("zero9178.coverage.xml", roamingType = RoamingType.DISABLED)]
)
class CoverageGeneratorPaths : PersistentStateComponent<CoverageGeneratorPaths.State> {

    data class GeneratorInfo(var gcovOrllvmCovPath: String = "", var llvmProfDataPath: String? = null) {
        fun copy() = GeneratorInfo(gcovOrllvmCovPath, llvmProfDataPath)
    }

    data class State(var paths: MutableMap<String, GeneratorInfo> = mutableMapOf())

    private var myState: State = State()

    private var myGenerators: MutableMap<String, Pair<CoverageGenerator?, String?>> = mutableMapOf()

    var paths: Map<String, GeneratorInfo>
        get() = myState.paths
        set(value) {
            myState.paths = value.toMutableMap()
            myGenerators.clear()
            myState.paths.forEach {
                generateGeneratorFor(it.key, it.value)
            }
        }

    fun getGeneratorFor(toolchain: String) = myGenerators[toolchain]

    override fun getState() = myState

    override fun loadState(state: State) {
        myState = state
        ensurePopulatedPaths()
    }

    private fun ensurePopulatedPaths() {
        if (paths.isEmpty()) {
            paths = CPPToolchains.getInstance().toolchains.associateBy({ it.name }, {
                guessCoverageGeneratorForToolchain(it)
            }).toMutableMap()
        } else {
            paths = paths.mapValues {
                if (it.value.gcovOrllvmCovPath.isNotEmpty()) {
                    it.value
                } else {
                    val toolchain =
                        CPPToolchains.getInstance().toolchains.find { toolchain -> toolchain.name == it.key }
                            ?: return@mapValues GeneratorInfo()
                    guessCoverageGeneratorForToolchain(toolchain)
                }
            }
        }
    }

    private fun generateGeneratorFor(name: String, info: GeneratorInfo) {
        myGenerators[name] = getGeneratorFor(info.gcovOrllvmCovPath, info.llvmProfDataPath)
    }

    init {
        ApplicationManager.getApplication().messageBus.connect()
            .subscribe(CPPToolchainsListener.TOPIC, object : CPPToolchainsListener {
                override fun toolchainsRenamed(renamed: MutableMap<String, String>) {
                    for (renames in renamed) {
                        val value = myState.paths.remove(renames.key)
                        if (value != null) {
                            myState.paths[renames.value] = value
                        }
                        val generator = myGenerators.remove(renames.key)
                        if (generator != null) {
                            myGenerators[renames.value] = generator
                        }
                    }
                }

                override fun toolchainCMakeEnvironmentChanged(toolchains: MutableSet<CPPToolchains.Toolchain>) {
                    toolchains.groupBy {
                        CPPToolchains.getInstance().toolchains.contains(it)
                    }.forEach { group ->
                        if (group.key) {
                            group.value.forEach {
                                val path = guessCoverageGeneratorForToolchain(it)
                                myState.paths[it.name] = path
                                generateGeneratorFor(it.name, path)
                            }
                        } else {
                            group.value.forEach {
                                myState.paths.remove(it.name)
                                myGenerators.remove(it.name)
                            }
                        }
                    }
                }
            })
        ensurePopulatedPaths()
    }

    companion object {
        fun getInstance() = ApplicationManager.getApplication().getComponent(CoverageGeneratorPaths::class.java)!!
    }
}

private fun guessCoverageGeneratorForToolchain(toolchain: CPPToolchains.Toolchain): CoverageGeneratorPaths.GeneratorInfo {
    val toolset = toolchain.toolSet ?: return CoverageGeneratorPaths.GeneratorInfo()
    var compiler =
        toolchain.customCXXCompilerPath ?: System.getenv("CXX")?.ifBlank { System.getenv("CC") }
    //Lets not deal with WSL yet
    return if (toolset is MinGW || toolset is NativeUnixToolSet) {

        val findExe = { prefix: String, name: String, suffix: String, extraPath: Path ->
            val insideSameDir = extraPath.toFile().listFiles()?.asSequence()?.map {
                "($prefix)?$name($suffix)?".toRegex().matchEntire(it.name)
            }?.filterNotNull()?.maxBy {
                it.value.length
            }?.value
            if (insideSameDir != null) {
                extraPath.resolve(insideSameDir).toString()
            } else {
                System.getenv("PATH").splitToSequence(File.pathSeparatorChar).asSequence().map {
                    Paths.get(it)
                }.map { path ->
                    path.toFile().listFiles()?.asSequence()?.map {
                        "($prefix)?$name($suffix)?".toRegex().matchEntire(it.name)
                    }?.filterNotNull()?.maxBy { it.value.length }
                }.filterNotNull().maxBy { it.value.length }?.value
            }
        }

        if (compiler != null && compiler.contains("clang", true)) {
            //We are using clang so we need to look for llvm-cov. We are first going to check if
            //llvm-cov is next to the compiler. If not we looking for it on PATH

            val compilerName = Paths.get(compiler).fileName.toString()

            val clangName = if (compilerName.contains("clang++")) "clang++" else "clang"

            val prefix = compilerName.substringBefore(clangName)

            val suffix = compilerName.substringAfter(clangName)

            val covPath = findExe(prefix, "llvm-cov", suffix, Paths.get(compiler).parent)

            val profPath = findExe(prefix, "llvm-profdata", suffix, Paths.get(compiler).parent)

            return if (profPath == null || covPath == null) {
                CoverageGeneratorPaths.GeneratorInfo()
            } else {
                CoverageGeneratorPaths.GeneratorInfo(covPath, profPath)
            }
        } else if (compiler == null) {
            if (toolset is MinGW) {
                val path = toolset.home.toPath().resolve("bin")
                    .resolve(if (OSType.getCurrent() == OSType.WIN) "gcov.exe" else "gcov")
                return if (path.exists()) {
                    CoverageGeneratorPaths.GeneratorInfo(path.toString())
                } else {
                    CoverageGeneratorPaths.GeneratorInfo()
                }
            }
            compiler = "/usr/bin/gcc"
        }

        val compilerName = Paths.get(compiler).fileName.toString()

        val gccName = if (compilerName.contains("g++")) "g++" else "gcc"

        val prefix = compilerName.substringBefore(gccName)

        val suffix = compilerName.substringAfter(gccName)

        val gcovPath = findExe(prefix, "gcov", suffix, Paths.get(compiler).parent)
        if (gcovPath != null) {
            CoverageGeneratorPaths.GeneratorInfo(gcovPath)
        } else {
            CoverageGeneratorPaths.GeneratorInfo()
        }
    } else {
        CoverageGeneratorPaths.GeneratorInfo()
    }
}