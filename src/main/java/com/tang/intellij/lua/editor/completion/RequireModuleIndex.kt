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

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.tang.intellij.lua.lang.LuaFileType
import com.tang.intellij.lua.project.LuaSourceRootManager
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * 项目级别的 require 模块索引服务。
 *
 * 扫描所有源码根目录下的 .lua 文件，提取 require 语句，
 * 建立 varName -> List<RequireModuleInfo> 的映射。
 *
 * 支持增量更新：通过 VFS 监听文件变化，仅重新解析变更的文件。
 */
@Service(Service.Level.PROJECT)
class RequireModuleIndex(private val project: Project) {

    companion object {
        fun getInstance(project: Project): RequireModuleIndex =
            project.getService(RequireModuleIndex::class.java)

        /**
         * 匹配以下形式的 require 语句：
         *   local VarName = require("path.to.module")
         *   local VarName = require('path.to.module')
         *   local VarName = require "path.to.module"
         *   local VarName = require 'path.to.module'
         */
        private val REQUIRE_PATTERN: Pattern = Pattern.compile(
            """local\s+(\w+)\s*=\s*require\s*[\("']([^"')]+)["')]?"""
        )
    }

    // varName -> List<RequireModuleInfo>，线程安全
    private val indexMap = ConcurrentHashMap<String, MutableList<RequireModuleInfo>>()

    @Volatile
    private var isBuilt = false

    init {
        // 监听文件变化，实现增量更新索引
        project.messageBus.connect().subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                for (event in events) {
                    val file = event.file ?: continue
                    if (file.fileType != LuaFileType.INSTANCE) continue

                    when (event) {
                        is VFileDeleteEvent -> {
                            // 文件删除：移除该文件贡献的所有条目
                            removeFileEntries(file)
                        }
                        is VFileContentChangeEvent -> {
                            // 文件内容变更：先移除旧条目，再重新解析
                            removeFileEntries(file)
                            parseAndIndex(file)
                        }
                        is VFileCreateEvent -> {
                            // 新文件创建：直接解析
                            parseAndIndex(file)
                        }
                    }
                }
            }
        })
    }

    /**
     * 根据变量名查询所有匹配的模块信息（精确匹配）
     */
    fun getModulesByName(varName: String): List<RequireModuleInfo> {
        ensureBuilt()
        return indexMap[varName]?.toList() ?: emptyList()
    }

    /**
     * 获取所有已索引的变量名集合（用于前缀匹配）
     */
    fun getAllVarNames(): Set<String> {
        ensureBuilt()
        return indexMap.keys.toSet()
    }

    /**
     * 强制重建整个索引（例如源码根目录发生变化时调用）
     */
    fun rebuildIndex() {
        indexMap.clear()
        isBuilt = false
        buildIndex()
        isBuilt = true
    }

    // -------------------------------------------------------------------------
    // 私有实现
    // -------------------------------------------------------------------------

    private fun ensureBuilt() {
        if (!isBuilt) {
            synchronized(this) {
                if (!isBuilt) {
                    buildIndex()
                    isBuilt = true
                }
            }
        }
    }

    private fun buildIndex() {
        val sourceRoots = LuaSourceRootManager.getInstance(project).getSourceRoots()
        for (root in sourceRoots) {
            scanDirectory(root)
        }
    }

    private fun scanDirectory(dir: VirtualFile) {
        for (child in dir.children) {
            when {
                child.isDirectory -> scanDirectory(child)
                child.fileType == LuaFileType.INSTANCE -> parseAndIndex(child)
            }
        }
    }

    /**
     * 移除某个文件贡献的所有索引条目
     */
    private fun removeFileEntries(file: VirtualFile) {
        val emptyKeys = mutableListOf<String>()
        for ((key, list) in indexMap) {
            list.removeAll { it.sourceFile == file }
            if (list.isEmpty()) emptyKeys.add(key)
        }
        emptyKeys.forEach { indexMap.remove(it) }
    }

    /**
     * 解析单个 .lua 文件，提取 require 语句并写入索引
     */
    private fun parseAndIndex(file: VirtualFile) {
        if (!file.isValid || file.isDirectory) return
        try {
            val content = String(file.contentsToByteArray(), Charsets.UTF_8)
            val matcher = REQUIRE_PATTERN.matcher(content)
            while (matcher.find()) {
                val varName = matcher.group(1) ?: continue
                val requirePath = matcher.group(2) ?: continue
                if (varName.isBlank() || requirePath.isBlank()) continue

                val info = RequireModuleInfo(varName, requirePath.trim(), file)
                indexMap.getOrPut(varName) { mutableListOf() }.let { list ->
                    // 避免重复添加同一文件的同一条目
                    if (list.none { it.sourceFile == file && it.requirePath == requirePath }) {
                        list.add(info)
                    }
                }
            }
        } catch (_: Exception) {
            // 忽略无法读取的文件（如二进制文件、权限问题等）
        }
    }
}
