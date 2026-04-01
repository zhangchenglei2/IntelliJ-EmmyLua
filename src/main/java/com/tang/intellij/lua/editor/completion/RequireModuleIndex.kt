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
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.LocalFileSystem
import com.tang.intellij.lua.project.LuaSourceRootManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

/**
 * 项目级别的 require 模块索引服务。
 *
 * 遍历所有源码根目录下的 .lua 文件，提取其中的 require 语句：
 *   local UIConfig = require("Game.Mod.BaseMod.Client.Config.UIConfig")
 * 以变量名（UIConfig）为 key，require 路径（Game.Mod.BaseMod.Client.Config.UIConfig）为 value，
 * 建立 varName -> List<RequireModuleInfo> 的映射。
 *
 * 支持增量更新：通过 VFS 监听文件变化，仅重新解析变更的文件。
 */
@Service(Service.Level.PROJECT)
class RequireModuleIndex(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(RequireModuleIndex::class.java)
        private const val LOG_PREFIX = "EmmyLuaAutoRequire"

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
            """local\s+(\w+)\s*=\s*require\s*\(?\s*["']([^"']+)["']\s*\)?"""
        )

        private fun isLuaFile(file: VirtualFile): Boolean {
            return !file.isDirectory && file.extension?.lowercase() == "lua"
        }
    }

    // varName -> List<RequireModuleInfo>，线程安全
    private val indexMap = ConcurrentHashMap<String, MutableList<RequireModuleInfo>>()

    /** 索引是否已构建完成 */
    private val isBuilt = AtomicBoolean(false)

    /** 是否正在构建中（防止重复触发） */
    private val isBuilding = AtomicBoolean(false)

    init {
        // 监听文件变化，实现增量更新索引
        project.messageBus.connect().subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                for (event in events) {
                    val file = event.file ?: continue
                    if (!isLuaFile(file)) continue

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
     * 在后台任务中预热索引，显示进度条。
     * 由 StartupActivity 在项目打开后调用。
     */
    fun warmUp() {
        if (isBuilt.get() || isBuilding.get()) return
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "EmmyLua: 正在建立 require 模块索引…",
            false  // 不可取消，确保索引完整
        ) {
            override fun run(indicator: ProgressIndicator) {
                if (!isBuilding.compareAndSet(false, true)) return
                try {
                    indicator.isIndeterminate = false
                    indicator.fraction = 0.0
                    indicator.text = "正在收集 Lua 源码根目录…"

                    val sourceRoots = collectSourceRoots()
                    if (sourceRoots.isEmpty()) {
                        LOG.warn("$LOG_PREFIX no source roots found, index will be empty")
                        isBuilt.set(true)
                        return
                    }

                    // 先收集所有 lua 文件，再逐个解析，以便显示准确进度
                    indicator.text = "正在扫描 .lua 文件…"
                    indicator.fraction = 0.1
                    val allLuaFiles = mutableListOf<VirtualFile>()
                    for (root in sourceRoots) {
                        collectLuaFiles(root, allLuaFiles)
                    }

                    val total = allLuaFiles.size
                    LOG.info("$LOG_PREFIX found $total lua files in ${sourceRoots.size} source roots")

                    indicator.text = "正在解析 require 语句（共 $total 个文件）…"
                    allLuaFiles.forEachIndexed { idx, file ->
                        indicator.fraction = 0.1 + 0.9 * (idx.toDouble() / total)
                        indicator.text2 = file.name
                        parseAndIndex(file)
                    }

                    isBuilt.set(true)
                    LOG.info("$LOG_PREFIX index built, total varNames=${indexMap.size}")
                } finally {
                    isBuilding.set(false)
                }
            }

            override fun onSuccess() {
                LOG.info("$LOG_PREFIX warm-up finished, varNames=${indexMap.size}")
            }
        })
    }

    /**
     * 索引是否已就绪
     */
    fun isReady(): Boolean = isBuilt.get()

    /**
     * 根据变量名查询所有匹配的模块信息（精确匹配）
     * 若索引尚未就绪，返回空列表（不阻塞）
     */
    fun getModulesByName(varName: String): List<RequireModuleInfo> {
        if (!isBuilt.get()) return emptyList()
        return indexMap[varName]?.toList() ?: emptyList()
    }

    /**
     * 获取所有已索引的变量名集合（用于前缀匹配）
     * 若索引尚未就绪，返回空集合（不阻塞）
     */
    fun getAllVarNames(): Set<String> {
        if (!isBuilt.get()) return emptySet()
        return indexMap.keys.toSet()
    }

    /**
     * 强制重建整个索引（例如源码根目录发生变化时调用）
     */
    fun rebuildIndex() {
        indexMap.clear()
        isBuilt.set(false)
        warmUp()
    }

    // -------------------------------------------------------------------------
    // 私有实现
    // -------------------------------------------------------------------------

    private fun collectSourceRoots(): Set<VirtualFile> {
        val sourceRoots = mutableSetOf<VirtualFile>()
        val lfs = LocalFileSystem.getInstance()

        // 1. 用户在 Project Settings > EmmyLua 中配置的 Auto Require 检索路径（项目级）
        val manager = LuaSourceRootManager.getInstance(project)
        for (path in manager.getAutoRequireSourceRoots()) {
            if (path.isBlank()) continue
            val vf = lfs.findFileByPath(path)
            if (vf != null && vf.isDirectory) sourceRoots.add(vf)
        }

        // 2. 在 Project Structure 中配置的 Source Root（包括 Test Source Root）
        sourceRoots.addAll(manager.getSourceRoots())

        LOG.info("$LOG_PREFIX collectSourceRoots => ${sourceRoots.map { it.path }}")
        return sourceRoots
    }

    private fun collectLuaFiles(dir: VirtualFile, result: MutableList<VirtualFile>) {
        if (!dir.isValid) return
        for (child in dir.children) {
            when {
                child.isDirectory -> collectLuaFiles(child, result)
                isLuaFile(child) -> result.add(child)
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
     * 解析单个 .lua 文件，提取所有 require 语句并写入索引。
     *
     * 例如：local UIConfig = require("Game.Mod.BaseMod.Client.Config.UIConfig")
     *  -> varName = "UIConfig", requirePath = "Game.Mod.BaseMod.Client.Config.UIConfig"
     */
    private fun parseAndIndex(file: VirtualFile) {
        if (!file.isValid || file.isDirectory) return
        try {
            val content = String(file.contentsToByteArray(), Charsets.UTF_8)
            val matcher = REQUIRE_PATTERN.matcher(content)
            val foundVarNames = mutableListOf<String>()
            while (matcher.find()) {
                val varName = matcher.group(1) ?: continue
                val requirePath = matcher.group(2) ?: continue
                if (varName.isBlank() || requirePath.isBlank()) continue

                foundVarNames.add(varName)
                val info = RequireModuleInfo(varName, requirePath.trim(), file)
                indexMap.getOrPut(varName) { mutableListOf() }.let { list ->
                    // 相同 varName + 相同 requirePath 视为重复，不管来自哪个文件，只保留一条
                    if (list.none { it.requirePath == requirePath }) {
                        list.add(info)
                    }
                }
            }
            if (foundVarNames.isNotEmpty()) {
                LOG.warn("RequireModuleIndex: parsed ${file.path}, found varNames=$foundVarNames")
            } else {
                LOG.warn("RequireModuleIndex: parsed ${file.path}, no require statements found")
            }
        } catch (e: Exception) {
            LOG.warn("RequireModuleIndex: failed to parse ${file.path}: ${e.message}")
        }
    }
}