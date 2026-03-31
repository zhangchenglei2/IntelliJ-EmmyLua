/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.editor.completion

import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.tang.intellij.lua.lang.LuaIcons
import com.tang.intellij.lua.project.LuaSettings
import com.tang.intellij.lua.psi.LuaDeclarationTree
import com.tang.intellij.lua.psi.LuaPsiFile

/**
 * 智能 require 模块补全提供者。
 *
 * 触发条件：
 *  - 当前光标在 LuaNameExpr 中（已由 LuaCompletionContributor 的 IN_NAME_EXPR 模式保证）
 *  - 输入的变量名前缀在当前作用域内未定义（local/global 均未定义）
 *  - 在 RequireModuleIndex 中存在对应记录
 *
 * 补全行为：
 *  - 补全列表右侧显示 require 路径，方便区分重名模块
 *  - 选中后自动在文件顶部合适位置插入 require 语句
 */
class RequireModuleCompletionProvider : LuaCompletionProvider() {

    override fun addCompletions(session: CompletionSession) {
        val parameters = session.parameters
        val resultSet = session.resultSet
        val position = parameters.position
        val file = position.containingFile as? LuaPsiFile ?: return

        // 至少输入 2 个字符才触发，避免过早弹出大量候选
        val prefix = resultSet.prefixMatcher.prefix
        if (prefix.length < 2) return

        val index = RequireModuleIndex.getInstance(file.project)

        for (varName in index.getAllVarNames()) {
            // 前缀不匹配则跳过
            if (!resultSet.prefixMatcher.prefixMatches(varName)) continue

            // 如果该变量名已在当前作用域中定义（local 或 global），则跳过
            if (isDefinedInCurrentScope(varName, position)) continue

            // 如果当前文件已经有该变量名的 require 语句，则跳过
            if (isAlreadyRequired(varName, file)) continue

            val modules = index.getModulesByName(varName)
            if (modules.isEmpty()) continue

            for (info in modules) {
                val element = buildLookupElement(info)
                // 优先级 80：高于普通单词补全（-1），低于本地变量补全（默认 0）
                resultSet.addElement(PrioritizedLookupElement.withPriority(element, 80.0))
            }
        }
    }

    /**
     * 检查变量名是否已在当前作用域中定义（local 或 global 均算）
     */
    private fun isDefinedInCurrentScope(varName: String, position: com.intellij.psi.PsiElement): Boolean {
        var found = false
        LuaDeclarationTree.get(position.containingFile).walkUp(position) { declaration ->
            if (declaration.name == varName) {
                found = true
                false  // 停止遍历
            } else {
                true   // 继续遍历
            }
        }
        return found
    }

    /**
     * 检查当前文件是否已经存在对应变量名的 require 语句
     */
    private fun isAlreadyRequired(varName: String, file: LuaPsiFile): Boolean {
        val text = file.text ?: return false
        // 匹配 local varName = require(...)
        val pattern = Regex("""local\s+${Regex.escape(varName)}\s*=\s*require""")
        return pattern.containsMatchIn(text)
    }

    /**
     * 构建补全 LookupElement：
     *  - 图标使用 MODULE 图标，表示这是一个模块导入
     *  - 右侧 typeText 显示 require 路径，方便区分重名模块
     *  - tailText 提示 "(auto require)"，告知用户会自动插入 require 语句
     */
    private fun buildLookupElement(info: RequireModuleInfo): LookupElement {
        return LookupElementBuilder
            .create(info.varName)
            .withIcon(LuaIcons.MODULE)
            .withTypeText(info.requirePath, true)
            .withTailText("  (auto require)", true)
            .withInsertHandler(RequireInsertHandler(info))
    }
}

/**
 * 补全插入处理器：在文件顶部合适位置插入 require 语句。
 *
 * 插入位置策略（按优先级）：
 *  1. 找到最后一个 `local xxx = require(...)` 语句所在行的行尾，在其后插入
 *  2. 如果没有已有 require，找到第一个非注释、非空行的位置，在其前插入
 *  3. 如果文件为空，插入到文件开头
 */
class RequireInsertHandler(private val info: RequireModuleInfo) : InsertHandler<LookupElement> {

    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val file = context.file as? LuaPsiFile ?: return
        val document = context.editor.document

        WriteCommandAction.runWriteCommandAction(file.project, "Insert require statement", null, {
            insertRequireStatement(document)
        }, file)
    }

    private fun insertRequireStatement(document: Document) {
        val text = document.charsSequence.toString()
        val insertOffset = findInsertOffset(text)
        val requireStatement = buildRequireStatement()
        document.insertString(insertOffset, requireStatement)
    }

    /**
     * 根据 LuaSettings 中配置的 require 函数名构建 require 语句字符串
     */
    private fun buildRequireStatement(): String {
        // 优先使用用户配置的第一个 require-like 函数名，默认为 "require"
        val requireFuncName = LuaSettings.instance.requireLikeFunctionNames
            .firstOrNull { it.isNotBlank() } ?: "require"
        return "local ${info.varName} = $requireFuncName(\"${info.requirePath}\")\n"
    }

    /**
     * 计算 require 语句的插入位置（字符偏移量）
     */
    private fun findInsertOffset(text: String): Int {
        if (text.isEmpty()) return 0

        val lines = text.split("\n")
        // 匹配 require 语句行
        val requireLinePattern = Regex("""^\s*local\s+\w+\s*=\s*require\s*[\("']""")
        // 匹配空行或纯注释行
        val blankOrCommentPattern = Regex("""^\s*(--.*)?\s*$""")

        var currentOffset = 0
        var lastRequireLineEnd = -1
        var firstCodeLineStart = -1

        for (line in lines) {
            val lineStart = currentOffset
            // +1 是换行符 '\n' 的长度（split 会去掉换行符）
            val lineEnd = currentOffset + line.length + 1

            when {
                requireLinePattern.containsMatchIn(line) -> {
                    // 记录最后一个 require 行的结束位置
                    lastRequireLineEnd = lineEnd
                }
                !blankOrCommentPattern.matches(line) && firstCodeLineStart == -1 -> {
                    // 记录第一个非注释、非空行的起始位置
                    firstCodeLineStart = lineStart
                }
            }
            currentOffset = lineEnd
        }

        return when {
            // 优先：在最后一个 require 语句之后插入
            lastRequireLineEnd > 0 -> minOf(lastRequireLineEnd, text.length)
            // 其次：在第一个代码行之前插入
            firstCodeLineStart > 0 -> firstCodeLineStart
            // 兜底：文件开头
            else -> 0
        }
    }
}
