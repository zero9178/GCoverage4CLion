package net.zero9178.cov.data

import com.beust.klaxon.Json
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Klaxon
import com.beust.klaxon.Parser
import com.beust.klaxon.jackson.jackson
import com.intellij.execution.ExecutionTarget
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.cidr.cpp.cmake.workspace.CMakeWorkspace
import com.jetbrains.cidr.cpp.execution.CMakeAppRunConfiguration
import com.jetbrains.cidr.cpp.toolchains.CPPEnvironment
import com.jetbrains.cidr.cpp.toolchains.CPPToolSet
import com.jetbrains.cidr.lang.psi.*
import com.jetbrains.cidr.lang.psi.visitors.OCRecursiveVisitor
import net.zero9178.cov.notification.CoverageNotification
import net.zero9178.cov.settings.CoverageGeneratorSettings
import net.zero9178.cov.util.toCP
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.ceil

class GCCJSONCoverageGenerator(private val myGcov: String) : CoverageGenerator {

    private fun findStatementsForBranches(
        lines: List<Line>,
        file: String,
        project: Project
    ): List<CoverageBranchData> {
        if (lines.isEmpty()) {
            return emptyList()
        }
        return DumbService.getInstance(project).runReadActionInSmartMode<List<CoverageBranchData>> {
            val vfs = LocalFileSystem.getInstance().findFileByPath(file) ?: return@runReadActionInSmartMode emptyList()
            val psiFile = PsiManager.getInstance(project).findFile(vfs) ?: return@runReadActionInSmartMode emptyList()
            val document =
                PsiDocumentManager.getInstance(project).getDocument(psiFile)
                    ?: return@runReadActionInSmartMode emptyList()
            lines.filter { it.branches.isNotEmpty() }.flatMap { line ->
                val startOffset = document.getLineStartOffset(line.lineNumber - 1)
                val lineEndOffset = document.getLineEndOffset(line.lineNumber - 1)
                val result = mutableListOf<OCElement>()
                val leftOutStmts = mutableListOf<OCStatement>()
                object : OCRecursiveVisitor(
                    TextRange(
                        startOffset,
                        lineEndOffset
                    )
                ) {
                    override fun visitLoopStatement(loop: OCLoopStatement?) {
                        loop ?: return super.visitLoopStatement(loop)
                        matchBranch(loop)
                        super.visitLoopStatement(loop)
                    }

                    override fun visitIfStatement(stmt: OCIfStatement?) {
                        stmt ?: return super.visitIfStatement(stmt)
                        matchBranch(stmt)
                        super.visitIfStatement(stmt)
                    }

                    private fun matchBranch(element: OCElement) {
                        //If and if or loop statement has an operator that can short circuit inside its expression
                        //then we dont have any branch coverage for those itself. We return here as we are handling
                        //it in the BinaryExpression
                        val isShortCicuit = { statement: OCStatement, condition: PsiElement ->
                            val list = PsiTreeUtil.getChildrenOfTypeAsList(
                                condition,
                                OCBinaryExpression::class.java
                            )
                            if (list.any { listOf("||", "or", "&&", "and").contains(it.operationSignNode.text) }) {
                                leftOutStmts += statement
                                true
                            } else {
                                false
                            }
                        }

                        when (element) {
                            is OCIfStatement -> {
                                if (element.firstChild.textOffset !in startOffset..lineEndOffset) {
                                    return
                                }
                                if (element.condition?.let { isShortCicuit(element, it) } == true) {
                                    return
                                }
                            }
                            is OCDoWhileStatement -> {
                                if (element.lastChild.textOffset !in startOffset..lineEndOffset) {
                                    return
                                }
                            }
                            else -> {
                                if (element.firstChild.textOffset !in startOffset..lineEndOffset) {
                                    return
                                }
                            }
                        }

                        if (element is OCLoopStatement) {
                            if (element.condition?.let { isShortCicuit(element, it) } == true) {
                                return
                            }
                        }

                        result += element
                    }

                    override fun visitBinaryExpression(expression: OCBinaryExpression?) {
                        super.visitBinaryExpression(expression)
                        expression ?: return
                        when (expression.operationSignNode.text) {
                            "||", "or", "&&", "and" -> {
                                //Calling with the operands here as it creates a branch for each operand
                                val left = expression.left
                                if (left != null) {
                                    if (PsiTreeUtil.findChildrenOfType(left, OCBinaryExpression::class.java).none {
                                            listOf("||", "or", "&&", "and").contains(it.operationSignNode.text)
                                        }) {
                                        matchBranch(left)
                                    }
                                }
                                val right = expression.right
                                if (right != null) {
                                    if (PsiTreeUtil.findChildrenOfType(right, OCBinaryExpression::class.java).none {
                                            listOf("||", "or", "&&", "and").contains(it.operationSignNode.text)
                                        }) {
                                        matchBranch(right)
                                    }
                                }
                            }
                        }
                    }
                }.visitElement(psiFile)

                val zip =
                    line.branches.chunked(2).filter { it.none { branch -> branch.throwing } && it.size == 2 }
                        .map { it[0] to it[1] }
                        .zip(result)

                fun OCStatement.getCondition() = when (this) {
                    is OCIfStatement -> this.condition
                    is OCLoopStatement -> this.condition
                    else -> null
                }

                fun OCStatement.getLParenth() = when (this) {
                    is OCIfStatement -> this.lParenth
                    is OCLoopStatement -> this.lParenth
                    else -> null
                }

                /**
                 * OR Test code:
                 * for(; E0 || ... || En;) {
                 *      ...
                 * }
                 *
                 * How gcov sees it:
                 * {
                 *  int i = 0;
                 * check:
                 *  if(E0)//Branch 1
                 *  {
                 *      goto body;
                 *  }
                 *  else if(E1)
                 *  {
                 *
                 *  }
                 *  ...
                 *  else if(!(En))//Branch 2
                 *  {
                 *      goto end;
                 *  }
                 *  else
                 *  {
                 *      goto body;
                 *  }
                 *  body:
                 *  ...
                 *  goto check;
                 *  end:
                 *
                 *  AND Test code:
                 * for(int i = 0; i < 5 && i % 2 == 0; i++) {
                 *      ...
                 * }
                 *
                 * How gcov sees it:
                 * {
                 *  int i = 0;
                 * check:
                 *  if(i < 5)//Branch 1
                 *  {
                 *      if(i % 2 == 0)//Branch 2
                 *      {
                 *          goto body;
                 *      }
                 *      else
                 *      {
                 *          goto end;
                 *      }
                 *  }
                 *  else
                 *  {
                 *      goto end;
                 *  }
                 *  body:
                 *  ...
                 *  goto check;
                 *  end:
                 *
                 *  To figure out the branch probability of a loop or if that has short circuiting
                 *  we need to check how many times the else was NOT reached incase of OR or check how many times
                 *  all branches reached the deepest body
                 */
                val stmts = leftOutStmts.map { thisStmt ->
                    val filter =
                        zip.filter { thisStmt.getCondition()?.textRange?.contains(it.second.textRange) ?: false }
                    thisStmt to filter
                        .foldIndexed<Pair<Pair<Branch, Branch>, OCElement>, Pair<Int, Int>?>(null) { index, current, (branches, element) ->
                            val skipped = if (branches.first.fallthrough) branches.second else branches.first
                            val steppedIn = if (branches.first.fallthrough) branches.first else branches.second

                            //if current != null than operand is always the one in the second branch so to say
                            val parentExpression = element.parent as? OCBinaryExpression ?: return@foldIndexed current
                            val isLast = index == filter.lastIndex
                            when (parentExpression.operationSignNode.text) {
                                "or", "||" -> if (current == null) {
                                    skipped.count to steppedIn.count
                                } else {
                                    if (current.second != 0) {
                                        if (isLast) {
                                            current.first + skipped.count to steppedIn.count
                                        } else {
                                            current.first + steppedIn.count to skipped.count
                                        }
                                    } else {
                                        current
                                    }
                                }
                                "and", "&&" -> if (current == null) {
                                    steppedIn.count to skipped.count
                                } else {
                                    if (current.first != 0) {
                                        steppedIn.count to current.second + skipped.count
                                    } else {
                                        current
                                    }
                                }
                                else -> current
                            }
                        }
                }.filter { it.second != null }.map { it.first to it.second!! }.map { (thisIf, pair) ->
                    val startLine = document.getLineNumber(thisIf.getLParenth()?.startOffset ?: thisIf.textOffset) + 1
                    val startColumn =
                        (thisIf.getLParenth()?.startOffset ?: thisIf.textOffset) - document.getLineStartOffset(
                            startLine - 1
                        ) + 1
                    CoverageBranchData(
                        startLine toCP startColumn,
                        pair.first, pair.second
                    )
                }

                zip.filter {
                    when (it.second) {
                        is OCLoopStatement -> CoverageGeneratorSettings.getInstance().loopBranchCoverageEnabled
                        is OCIfStatement -> CoverageGeneratorSettings.getInstance().ifBranchCoverageEnabled
                        is OCExpression -> CoverageGeneratorSettings.getInstance().booleanOpBranchCoverageEnabled
                        else -> true
                    }
                }.fold(stmts) { list, (branches, element) ->
                    val steppedIn = if (branches.first.fallthrough) branches.first else branches.second
                    val skipped = if (branches.first.fallthrough) branches.second else branches.first
                    list + CoverageBranchData(
                        when (element) {
                            is OCLoopStatement -> {
                                val startLine = document.getLineNumber(
                                    element.lParenth?.textRange?.startOffset ?: element.textOffset
                                ) + 1
                                val column = (element.lParenth?.textRange?.startOffset
                                    ?: element.textOffset) - document.getLineStartOffset(startLine - 1) + 1
                                startLine toCP column
                            }
                            is OCIfStatement -> {
                                val startLine = document.getLineNumber(
                                    element.lParenth?.textRange?.startOffset ?: element.textOffset
                                ) + 1
                                val column = (element.lParenth?.textRange?.startOffset
                                    ?: element.textOffset) - document.getLineStartOffset(startLine - 1) + 1
                                startLine toCP column
                            }
                            is OCExpression -> {
                                val startLine =
                                    document.getLineNumber(element.textOffset) + 1
                                val column =
                                    element.textOffset - document.getLineStartOffset(
                                        startLine - 1
                                    ) + 1
                                startLine toCP column
                            }
                            else -> {
                                val startLine = document.getLineNumber(element.textOffset) + 1
                                val column = element.textOffset - document.getLineStartOffset(startLine - 1) + 1
                                startLine toCP column
                            }
                        }, steppedIn.count, skipped.count
                    )
                }
            }
        }
    }

    @Suppress("ConvertCallChainIntoSequence")
    private fun rooToCoverageData(root: Root, env: CPPEnvironment, project: Project) =
        CoverageData(root.files.chunked(ceil(root.files.size / Thread.activeCount().toDouble()).toInt()).map {
            ApplicationManager.getApplication().executeOnPooledThread<List<CoverageFileData>> {
                it.filter { it.lines.isNotEmpty() || it.functions.isNotEmpty() }.map { file ->
                    CoverageFileData(env.toLocalPath(file.file).replace('\\', '/'), file.functions.map { function ->
                        val lines = file.lines.filter {
                            it.functionName == function.name
                        }
                        CoverageFunctionData(
                            function.startLine,
                            function.endLine,
                            function.demangledName,
                            FunctionLineData(lines.associate { it.lineNumber to it.count }),
                            findStatementsForBranches(
                                lines,
                                env.toLocalPath(file.file),
                                project
                            )
                        )
                    }.associateBy { it.functionName })
                }
            }
        }.flatMap { it.get() }.associateBy { it.filePath })

    private data class Root(
        @Json(name = "current_working_directory") val currentWorkingDirectory: String,
        @Json(name = "data_file") val dataFile: String,
        @Json(name = "gcc_version") val gccVersion: String,
        val files: List<File>
    )

    private data class File(val file: String, val functions: List<Function>, val lines: List<Line>)

    private data class Function(
        val blocks: Int, @Json(name = "blocks_executed") val blocksExecuted: Long, @Json(name = "demangled_name") val demangledName: String, @Json(
            name = "end_column"
        ) val endColumn: Int, @Json(name = "end_line") val endLine: Int, @Json(name = "execution_count") val executionCount: Long,
        val name: String, @Json(name = "start_column") val startColumn: Int, @Json(name = "start_line") val startLine: Int
    )

    private data class Line(
        val branches: List<Branch>,
        val count: Long, @Json(name = "line_number") val lineNumber: Int, @Json(name = "unexecuted_block") val unexecutedBlock: Boolean, @Json(
            name = "function_name"
        ) val functionName: String = ""
    )

    private data class Branch(val count: Int, val fallthrough: Boolean, @Json(name = "throw") val throwing: Boolean)

    private fun processJson(
        jsonContents: List<String>,
        env: CPPEnvironment,
        project: Project
    ): CoverageData {

        val root = jsonContents.map {
            ApplicationManager.getApplication().executeOnPooledThread<List<File>> {
                Klaxon().maybeParse<Root>(Parser.jackson().parse(StringReader(it)) as JsonObject)?.files
            }
        }.flatMap {
            it.get()
        }

        return rooToCoverageData(Root("", "", "", root), env, project)
    }

    override fun generateCoverage(
        configuration: CMakeAppRunConfiguration,
        environment: CPPEnvironment,
        executionTarget: ExecutionTarget
    ): CoverageData? {
        val config = CMakeWorkspace.getInstance(configuration.project).getCMakeConfigurationFor(
            configuration.getResolveConfiguration(executionTarget)
        ) ?: return null
        val files =
            config.configurationGenerationDir.walkTopDown().filter {
                it.isFile && it.name.endsWith(".gcda")
            }.map { environment.toEnvPath(it.absolutePath) }.toList()

        val processBuilder =
            ProcessBuilder().command(
                (if (environment.toolchain.toolSetKind == CPPToolSet.Kind.WSL) listOf(
                    environment.toolchain.toolSetPath,
                    "run"
                ) else emptyList()) +
                        listOf(
                            myGcov,
                            "-i",
                            "-m",
                            "-t"
                        ) + if (CoverageGeneratorSettings.getInstance().branchCoverageEnabled) {
                    listOf("-b")
                } else {
                    emptyList()
                } + files
            ).redirectErrorStream(true)
        val p = processBuilder.start()
        val lines = p.inputStream.bufferedReader().readLines()
        val retCode = p.waitFor()
        if (retCode != 0) {
            val notification = CoverageNotification.GROUP_DISPLAY_ID_INFO.createNotification(
                "gcov returned error code $retCode",
                "Invocation and error output:",
                "Invocation: ${processBuilder.command().joinToString(" ")}\n Stderr: ${lines.joinToString("\n")}",
                NotificationType.ERROR
            )
            Notifications.Bus.notify(notification, configuration.project)
            return null
        }

        files.forEach { Files.deleteIfExists(Paths.get(environment.toLocalPath(it))) }

        return processJson(lines, environment, configuration.project)
    }
}